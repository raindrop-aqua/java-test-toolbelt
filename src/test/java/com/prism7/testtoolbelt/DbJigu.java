package com.prism7.testtoolbelt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DB治具（DbJigu） v1.0.0
 * <p>
 * テキストファイル（フィクスチャ）を使って、テストデータのDB投入とDB内容の検証を行う。
 * 依存ライブラリは無く、JDK（Java 17以上）と JDBC ドライバだけで動作する。
 * このファイルを利用するプロジェクトのテストソースにコピーし、パッケージ名を変更して利用する。
 *
 * <h2>フィクスチャの書式</h2>
 * <pre>
 * [テーブル名]
 * {#;列名1;列名2;列名3@}
 * 1;値1;値2;値3
 * 1,2;値1;値2;&lt;null&gt;
 * ;値1;値2;値3
 * </pre>
 * <ul>
 *   <li>文字コードは UTF-8、区切り文字は {@code ;}。空行は無視する。</li>
 *   <li>1ファイルに複数の {@code [テーブル名]} を記述できる。</li>
 *   <li>値の前後の空白は取り除く。{@code <null>} は NULL を表す。</li>
 *   <li>値は列の型（DBから取得）に合わせて変換して渡す。日付は {@code 2024-04-01} / {@code 2024/04/01}、
 *       日時は {@code 2024-04-01 12:34:56[.123]} の形式で記述する。</li>
 *   <li>列名の末尾に {@code @} を付けた列は、比較の対象外とする（投入は行う）。</li>
 *   <li>CLOB 等の LOB 列は SQL の {@code =} で比較できないため、それ以外の列で絞り込んだ後に Java 側で比較する。</li>
 * </ul>
 *
 * <h2>パターン番号</h2>
 * ヘッダーの先頭を {@code #} にすると、各行の先頭の項目がパターン番号になる。
 * パターン番号は {@code 1,2} のように複数指定でき、空にすると全パターン共通の行になる。
 * 各メソッドにパターン番号を渡すと、そのパターンの行と共通の行だけを処理する。
 * パターン番号を渡さない場合は全行を処理する。{@code #} の無いテーブルの行は常に処理する。
 *
 * <h2>利用例（Spring の {@code @Transactional} テスト）</h2>
 * <pre>
 * // テストのトランザクションに参加している Connection を渡すこと（終了後にロールバックされる）
 * DbJigu jigu = new DbJigu(DataSourceUtils.getConnection(dataSource));
 * jigu.importFrom("src/test/resources/data/setup.txt", 1);
 * service.execute();
 * entityManager.flush(); // JPA を使う場合は、検証前に変更をDBへ反映させる
 * jigu.assertExists("src/test/resources/data/expected.txt", 1);
 * </pre>
 * 渡された Connection のコミット、ロールバック、クローズは行わない。
 */
public class DbJigu {

    private static final String SEPARATOR = ";";
    private static final String NULL_VALUE = "<null>";
    private static final String PATTERN_COLUMN = "#";
    private static final String PATTERN_SEPARATOR = ",";
    private static final String IGNORE_MARK = "@";

    private static final DateTimeFormatter DATE_TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm")
            .optionalStart().appendPattern(":ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();

    private final Connection connection;

    public DbJigu(Connection connection) {
        this.connection = connection;
    }

    /**
     * ファイルをDBに投入する
     *
     * @param filePath 投入するファイル
     * @param patterns 対象のパターン番号（省略時は全行）
     * @return 投入した件数
     */
    public int importFrom(String filePath, int... patterns) {
        int total = 0;
        for (Section section : readFile(filePath, patterns)) {
            if (section.rows.isEmpty()) {
                continue;
            }
            int[] types = resolveTypes(section);
            String sql = "INSERT INTO " + section.tableName
                    + " (" + section.columns.stream().map(c -> c.name).collect(Collectors.joining(", "))
                    + ") VALUES (" + section.columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (Row row : section.rows) {
                    for (int i = 0; i < row.values.size(); i++) {
                        bind(ps, i + 1, types[i], row.values.get(i), row);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new IllegalStateException("投入に失敗しました: " + filePath + " [" + section.tableName + "] " + sql, e);
            }
            System.out.printf("投入件数: %d 実行SQL: %s%n", section.rows.size(), sql);
            total += section.rows.size();
        }
        return total;
    }

    /**
     * ファイルの各行がDBに存在するか検証する
     *
     * @param filePath 検証するファイル
     * @param patterns 対象のパターン番号（省略時は全行）
     * @return 存在しなかった行の件数（0なら成功）
     */
    public int verifyExists(String filePath, int... patterns) {
        return verify(filePath, true, patterns).size();
    }

    /**
     * ファイルの各行がDBに存在しないことを検証する
     *
     * @param filePath 検証するファイル
     * @param patterns 対象のパターン番号（省略時は全行）
     * @return 存在した行の件数（0なら成功）
     */
    public int verifyNotExists(String filePath, int... patterns) {
        return verify(filePath, false, patterns).size();
    }

    /**
     * ファイルの各行がDBに存在することを検証し、存在しない行があれば AssertionError を投げる
     *
     * @param filePath 検証するファイル
     * @param patterns 対象のパターン番号（省略時は全行）
     */
    public void assertExists(String filePath, int... patterns) {
        List<String> failures = verify(filePath, true, patterns);
        if (!failures.isEmpty()) {
            throw new AssertionError("DBに存在しない行があります: " + filePath + System.lineSeparator()
                    + String.join(System.lineSeparator(), failures));
        }
    }

    /**
     * ファイルの各行がDBに存在しないことを検証し、存在する行があれば AssertionError を投げる
     *
     * @param filePath 検証するファイル
     * @param patterns 対象のパターン番号（省略時は全行）
     */
    public void assertNotExists(String filePath, int... patterns) {
        List<String> failures = verify(filePath, false, patterns);
        if (!failures.isEmpty()) {
            throw new AssertionError("DBに存在する行があります: " + filePath + System.lineSeparator()
                    + String.join(System.lineSeparator(), failures));
        }
    }

    // 検証を行い、期待と異なった行の一覧を返す
    private List<String> verify(String filePath, boolean expectExists, int... patterns) {
        List<Section> sections = readFile(filePath, patterns);
        if (sections.stream().allMatch(s -> s.rows.isEmpty())) {
            throw new IllegalArgumentException("検証対象の行がありません: " + filePath);
        }
        List<String> failures = new ArrayList<>();
        for (Section section : sections) {
            if (section.rows.isEmpty()) {
                continue;
            }
            int[] types = resolveTypes(section);
            for (Row row : section.rows) {
                String description = String.format("%s:%d [%s] %s", filePath, row.lineNumber, section.tableName, row.text);
                if (exists(section, types, row) == expectExists) {
                    System.out.println("○: " + description);
                } else {
                    System.out.println("×: " + description);
                    failures.add(description);
                }
            }
        }
        return failures;
    }

    // 行がDBに存在するか判定する
    private boolean exists(Section section, int[] types, Row row) {
        List<Integer> whereIndexes = new ArrayList<>();
        List<Integer> lobIndexes = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        for (int i = 0; i < section.columns.size(); i++) {
            Column column = section.columns.get(i);
            if (column.ignored) {
                continue;
            }
            if (isLob(types[i])) {
                lobIndexes.add(i);
            } else if (row.values.get(i) == null) {
                conditions.add(column.name + " IS NULL");
            } else {
                // CHAR型は空白で埋められるため、末尾の空白を除いて比較する
                boolean isChar = types[i] == Types.CHAR || types[i] == Types.NCHAR;
                conditions.add((isChar ? "RTRIM(" + column.name + ")" : column.name) + " = ?");
                whereIndexes.add(i);
            }
        }
        String selectList = lobIndexes.isEmpty() ? "1"
                : lobIndexes.stream().map(i -> section.columns.get(i).name).collect(Collectors.joining(", "));
        String sql = "SELECT " + selectList + " FROM " + section.tableName
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int p = 0; p < whereIndexes.size(); p++) {
                int i = whereIndexes.get(p);
                bind(ps, p + 1, types[i], row.values.get(i), row);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (lobsMatch(rs, lobIndexes, row)) {
                        return true;
                    }
                }
                return false;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("検証に失敗しました: 行" + row.lineNumber + " " + sql, e);
        }
    }

    // LOB列の値を Java 側で比較する
    private boolean lobsMatch(ResultSet rs, List<Integer> lobIndexes, Row row) throws SQLException {
        for (int p = 0; p < lobIndexes.size(); p++) {
            String expected = row.values.get(lobIndexes.get(p));
            String actual = rs.getString(p + 1);
            if (expected == null ? actual != null : !expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLob(int type) {
        return type == Types.CLOB || type == Types.NCLOB
                || type == Types.LONGVARCHAR || type == Types.LONGNVARCHAR;
    }

    // 列の型をDBのメタデータから取得する
    private int[] resolveTypes(Section section) {
        String sql = "SELECT " + section.columns.stream().map(c -> c.name).collect(Collectors.joining(", "))
                + " FROM " + section.tableName + " WHERE 1 = 0";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int[] types = new int[section.columns.size()];
            for (int i = 0; i < types.length; i++) {
                types[i] = metaData.getColumnType(i + 1);
            }
            return types;
        } catch (SQLException e) {
            throw new IllegalStateException("列の型を取得できません: " + sql, e);
        }
    }

    // 値を列の型に合わせて変換し、バインドする
    private static void bind(PreparedStatement ps, int index, int type, String value, Row row) throws SQLException {
        if (value == null) {
            ps.setNull(index, type);
            return;
        }
        try {
            switch (type) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                     Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE ->
                        ps.setBigDecimal(index, new BigDecimal(value));
                case Types.DATE -> {
                    // OracleのDATE型は時刻を持つため、時刻が書かれていれば日時として渡す
                    LocalDateTime dateTime = parseDateTime(value);
                    if (hasTime(value)) {
                        ps.setTimestamp(index, Timestamp.valueOf(dateTime));
                    } else {
                        ps.setDate(index, java.sql.Date.valueOf(dateTime.toLocalDate()));
                    }
                }
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                        ps.setTimestamp(index, Timestamp.valueOf(parseDateTime(value)));
                case Types.TIME -> ps.setTime(index, Time.valueOf(value));
                case Types.BOOLEAN, Types.BIT -> ps.setBoolean(index, parseBoolean(value));
                // PostgreSQL の uuid や json など。型の変換はDBに任せる
                case Types.OTHER -> ps.setObject(index, value, Types.OTHER);
                default -> ps.setString(index, value);
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("値を列の型に変換できません: 行" + row.lineNumber + " 値=" + value, e);
        }
    }

    private static boolean hasTime(String value) {
        return value.length() > 10;
    }

    private static LocalDateTime parseDateTime(String value) {
        String normalized = value.replace('/', '-').replace('T', ' ');
        if (!hasTime(normalized)) {
            return LocalDate.parse(normalized).atStartOfDay();
        }
        return LocalDateTime.parse(normalized, DATE_TIME_FORMAT);
    }

    private static boolean parseBoolean(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "t", "1", "yes", "y":
                return true;
            case "false", "f", "0", "no", "n":
                return false;
            default:
                throw new IllegalArgumentException("真偽値ではありません: " + value);
        }
    }

    // ファイルを読み込み、対象のパターンの行だけを持つセクションの一覧を返す
    private static List<Section> readFile(String filePath, int... patterns) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("ファイルを読み込めません: " + filePath, e);
        }

        Set<Integer> targetPatterns = Arrays.stream(patterns).boxed().collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> foundPatterns = new LinkedHashSet<>();
        List<Section> sections = new ArrayList<>();
        String tableName = null;
        Section section = null;

        for (int n = 0; n < lines.size(); n++) {
            int lineNumber = n + 1;
            String line = lines.get(n).trim();
            if (lineNumber == 1 && line.startsWith("﻿")) {
                line = line.substring(1).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]") || line.length() == 2) {
                    throw formatError(filePath, lineNumber, "テーブル名は [テーブル名] の形式で記述してください");
                }
                tableName = line.substring(1, line.length() - 1).trim();
                section = null;
            } else if (line.startsWith("{")) {
                if (tableName == null) {
                    throw formatError(filePath, lineNumber, "列名の前に [テーブル名] を記述してください");
                }
                if (!line.endsWith("}")) {
                    throw formatError(filePath, lineNumber, "列名は {列名1;列名2} の形式で記述してください");
                }
                section = parseHeader(filePath, lineNumber, tableName, line.substring(1, line.length() - 1));
                sections.add(section);
            } else {
                if (section == null) {
                    throw formatError(filePath, lineNumber, "データの前に {列名1;列名2} を記述してください");
                }
                Row row = parseRow(filePath, lineNumber, section, line);
                foundPatterns.addAll(row.patterns);
                if (targetPatterns.isEmpty() || row.patterns.isEmpty()
                        || !Collections.disjoint(row.patterns, targetPatterns)) {
                    section.rows.add(row);
                }
            }
        }

        // パターン番号の指定間違いで、何も検証せずに成功するのを防ぐ
        Set<Integer> missing = new LinkedHashSet<>(targetPatterns);
        missing.removeAll(foundPatterns);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("パターン番号 " + missing + " の行がありません: " + filePath);
        }
        return sections;
    }

    private static Section parseHeader(String filePath, int lineNumber, String tableName, String header) {
        List<String> names = split(header);
        boolean hasPattern = names.get(0).equals(PATTERN_COLUMN);
        if (hasPattern) {
            names = names.subList(1, names.size());
        }
        List<Column> columns = new ArrayList<>();
        for (String name : names) {
            boolean ignored = name.endsWith(IGNORE_MARK);
            String columnName = ignored ? name.substring(0, name.length() - IGNORE_MARK.length()).trim() : name;
            if (columnName.isEmpty()) {
                throw formatError(filePath, lineNumber, "空の列名があります");
            }
            columns.add(new Column(columnName, ignored));
        }
        if (columns.isEmpty()) {
            throw formatError(filePath, lineNumber, "列名がありません");
        }
        return new Section(tableName, columns, hasPattern);
    }

    private static Row parseRow(String filePath, int lineNumber, Section section, String line) {
        List<String> values = split(line);
        Set<Integer> rowPatterns = new LinkedHashSet<>();
        if (section.hasPattern) {
            String patternText = values.get(0);
            if (!patternText.isEmpty()) {
                for (String p : patternText.split(PATTERN_SEPARATOR, -1)) {
                    try {
                        rowPatterns.add(Integer.parseInt(p.trim()));
                    } catch (NumberFormatException e) {
                        throw formatError(filePath, lineNumber, "パターン番号が数値ではありません: " + patternText);
                    }
                }
            }
            values = values.subList(1, values.size());
        }
        if (values.size() != section.columns.size()) {
            throw formatError(filePath, lineNumber,
                    "列数(" + section.columns.size() + ")と値の数(" + values.size() + ")が一致しません");
        }
        List<String> converted = values.stream().map(v -> v.equals(NULL_VALUE) ? null : v)
                .collect(Collectors.toCollection(ArrayList::new));
        return new Row(lineNumber, line, rowPatterns, converted);
    }

    // 区切り文字で分割し、各項目の前後の空白を除く（末尾の空の項目も残す）
    private static List<String> split(String line) {
        return Arrays.stream(line.split(SEPARATOR, -1)).map(String::trim).collect(Collectors.toList());
    }

    private static IllegalArgumentException formatError(String filePath, int lineNumber, String message) {
        return new IllegalArgumentException(filePath + ":" + lineNumber + " " + message);
    }

    private static final class Section {
        final String tableName;
        final List<Column> columns;
        final boolean hasPattern;
        final List<Row> rows = new ArrayList<>();

        Section(String tableName, List<Column> columns, boolean hasPattern) {
            this.tableName = tableName;
            this.columns = columns;
            this.hasPattern = hasPattern;
        }
    }

    private record Column(String name, boolean ignored) {
    }

    private record Row(int lineNumber, String text, Set<Integer> patterns, List<String> values) {
    }
}
