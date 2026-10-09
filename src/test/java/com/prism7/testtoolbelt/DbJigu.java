/*
 * DbJigu https://github.com/raindrop-aqua/java-test-toolbelt
 * Copyright (c) 2024 Masahiro Atsumi
 * Released under the MIT License.
 */
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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * DB治具（DbJigu） v2.1.2
 * <p>
 * テキストファイル（フィクスチャ）を使って、テストデータのDB投入とDB内容の検証を行う。
 * 依存ライブラリは無く、JDK（Java 17以上）と JDBC ドライバだけで動作する。
 * このファイルを利用するプロジェクトのテストソースにコピーし、パッケージ名を変更して利用する。
 *
 * <h2>フィクスチャの書式</h2>
 * <pre>
 * [テーブル名]
 * {#|列名1|列名2|列名3@}
 * 1|値1|値2|値3
 * 1,2|値1|値2|&lt;null&gt;
 * |値1|値2|値3
 * </pre>
 * <ul>
 *   <li>文字コードは UTF-8、区切り文字は {@code |}。空行は無視する。</li>
 *   <li>1ファイルに複数の {@code [テーブル名]} を記述できる。</li>
 *   <li>値の前後の空白は取り除く。{@code <null>} は NULL を表す。</li>
 *   <li>値は列の型（DBから取得）に合わせて変換して渡す。日付は {@code 2024-04-01} / {@code 2024/04/01}、
 *       日時は {@code 2024-04-01 12:34:56[.123]}、時刻は {@code 12:34[:56]} の形式で記述する。
 *       INTEGER や BIGINT などの整数型の列に小数を書くとエラーにする（DBで丸められて投入されるのを防ぐ）。
 *       ただし NUMERIC(10) などの列（Oracle の NUMBER を含む）は JDBC では整数型と区別できないため、DBの丸めに従う。</li>
 *   <li>CLOB 等の LOB 列は SQL の {@code =} で比較できないため、それ以外の列で絞り込んだ後に Java 側で比較する。</li>
 * </ul>
 *
 * <h2>比較の条件</h2>
 * 比較では、列名の末尾に記号を付けると完全一致以外の条件にできる。記号は1つの列に1つだけ付けられる。
 * 投入では記号を無視し、値をそのまま投入する。
 * <table>
 *   <tr><th>記号</th><th>条件</th></tr>
 *   <tr><td>なし</td><td>DBの値 = ファイルの値</td></tr>
 *   <tr><td>{@code @}</td><td>比較しない</td></tr>
 *   <tr><td>{@code <} {@code <=} {@code >} {@code >=}</td><td>DBの値 &lt; ファイルの値 など（左がDBの値）</td></tr>
 *   <tr><td>{@code !=}</td><td>DBの値 ≠ ファイルの値（DBの値が NULL の行も含む）</td></tr>
 *   <tr><td>{@code %}</td><td>DBの値がファイルの値で始まる（前方一致）。文字列の列だけに使える</td></tr>
 * </table>
 * <ul>
 *   <li>{@code <null>} はどの記号でも {@code IS NULL} として扱う。{@code !=} の場合だけ {@code IS NOT NULL} になる。</li>
 *   <li>比較では同じ列を2回書ける。{@code {id|amount>=|amount<}} のように範囲を指定できる（投入ではエラー）。</li>
 *   <li>{@code %} の値に含まれる {@code %} と {@code _} は、文字そのものとして扱う。</li>
 *   <li>文字列の大小はDBの照合順序で決まるため、大小比較は主に数値と日時に使う。</li>
 *   <li>LOB 列には大小比較の記号を付けられない。</li>
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

    private static final String SEPARATOR = "|";
    private static final String NULL_VALUE = "<null>";
    private static final String PATTERN_COLUMN = "#";
    private static final String PATTERN_SEPARATOR = ",";
    private static final String MARK_CHARACTERS = "@<>=!%";
    private static final char LIKE_ESCAPE = '!';

    private static final DateTimeFormatter DATE_TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm")
            .optionalStart().appendPattern(":ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter()
            // 2024-02-30 などの存在しない日付を、月末に丸めずにエラーにする
            .withResolverStyle(ResolverStyle.STRICT);

    // 時の1桁と、秒の省略を許す。Time は秒未満を持てないため、秒未満は書けない
    private static final DateTimeFormatter TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("H:mm")
            .optionalStart().appendPattern(":ss")
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

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
            Set<String> names = new LinkedHashSet<>();
            for (Column column : section.columns) {
                if (!names.add(column.name.toLowerCase(Locale.ROOT))) {
                    throw formatError(filePath, section.headerLineNumber,
                            "投入では同じ列を2回書けません: " + column.name);
                }
            }
            int[] types = resolveTypes(filePath, section);
            String sql = "INSERT INTO " + section.tableName + " (" + columnList(section)
                    + ") VALUES (" + String.join(", ", Collections.nCopies(section.columns.size(), "?")) + ")";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (Row row : section.rows) {
                    for (int i = 0; i < row.values.size(); i++) {
                        bind(ps, i + 1, types[i], row.values.get(i), filePath, row);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new IllegalStateException(
                        location(filePath, section.headerLineNumber) + " 投入に失敗しました: " + sql, e);
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
        assertVerified(filePath, true, "DBに存在しない行があります: ", patterns);
    }

    /**
     * ファイルの各行がDBに存在しないことを検証し、存在する行があれば AssertionError を投げる
     *
     * @param filePath 検証するファイル
     * @param patterns 対象のパターン番号（省略時は全行）
     */
    public void assertNotExists(String filePath, int... patterns) {
        assertVerified(filePath, false, "DBに存在する行があります: ", patterns);
    }

    // 検証を行い、期待と異なった行があれば AssertionError を投げる
    private void assertVerified(String filePath, boolean expectExists, String message, int... patterns) {
        List<String> failures = verify(filePath, expectExists, patterns);
        if (!failures.isEmpty()) {
            throw new AssertionError(message + filePath + System.lineSeparator()
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
            int[] types = resolveTypes(filePath, section);
            validateOperators(filePath, section, types);
            for (Row row : section.rows) {
                String description = String.format("%s:%d [%s] %s", filePath, row.lineNumber, section.tableName, row.text);
                if (exists(filePath, section, types, row) == expectExists) {
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
    private boolean exists(String filePath, Section section, int[] types, Row row) {
        List<Integer> whereIndexes = new ArrayList<>();
        List<Integer> lobIndexes = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        for (int i = 0; i < section.columns.size(); i++) {
            Column column = section.columns.get(i);
            if (column.operator == Operator.IGNORE) {
                continue;
            }
            if (isLob(types[i])) {
                lobIndexes.add(i);
                continue;
            }
            if (row.values.get(i) == null) {
                conditions.add(column.name + (column.operator == Operator.NOT_EQUAL ? " IS NOT NULL" : " IS NULL"));
                continue;
            }
            // CHAR型は空白で埋められるため、末尾の空白を除いて比較する
            boolean isChar = types[i] == Types.CHAR || types[i] == Types.NCHAR;
            String target = isChar ? "RTRIM(" + column.name + ")" : column.name;
            switch (column.operator) {
                case PREFIX -> conditions.add(target + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'");
                // SQL では NULL <> 値 が真にならないため、NULL の行も等しくないものとして含める
                case NOT_EQUAL -> conditions.add("(" + target + " <> ? OR " + column.name + " IS NULL)");
                default -> conditions.add(target + " " + column.operator.sql + " ?");
            }
            whereIndexes.add(i);
        }
        String selectList = lobIndexes.isEmpty() ? "1"
                : lobIndexes.stream().map(i -> section.columns.get(i).name).collect(Collectors.joining(", "));
        String sql = "SELECT " + selectList + " FROM " + section.tableName
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int p = 0; p < whereIndexes.size(); p++) {
                int i = whereIndexes.get(p);
                if (section.columns.get(i).operator == Operator.PREFIX) {
                    ps.setString(p + 1, escapeLike(row.values.get(i)) + "%");
                } else {
                    bind(ps, p + 1, types[i], row.values.get(i), filePath, row);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (lobsMatch(rs, section, lobIndexes, row)) {
                        return true;
                    }
                }
                return false;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(location(filePath, row.lineNumber) + " 検証に失敗しました: " + sql, e);
        }
    }

    // LOB列の値を Java 側で比較する
    private boolean lobsMatch(ResultSet rs, Section section, List<Integer> lobIndexes, Row row) throws SQLException {
        for (int p = 0; p < lobIndexes.size(); p++) {
            int i = lobIndexes.get(p);
            if (!matchesInJava(section.columns.get(i).operator, row.values.get(i), rs.getString(p + 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesInJava(Operator operator, String expected, String actual) {
        if (expected == null) {
            return operator == Operator.NOT_EQUAL ? actual != null : actual == null;
        }
        return switch (operator) {
            case PREFIX -> actual != null && actual.startsWith(expected);
            case NOT_EQUAL -> !expected.equals(actual);
            default -> expected.equals(actual);
        };
    }

    // LIKE の値に含まれる記号を、文字そのものとして扱うようにエスケープする
    private static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    // 列の型に使えない記号が付いていないか確認する
    private static void validateOperators(String filePath, Section section, int[] types) {
        for (int i = 0; i < section.columns.size(); i++) {
            Column column = section.columns.get(i);
            if (column.operator == Operator.PREFIX && !isCharacter(types[i])) {
                throw formatError(filePath, section.headerLineNumber,
                        "% は文字列の列だけに使えます: " + column.name);
            }
            if (column.operator.ordering && isLob(types[i])) {
                throw formatError(filePath, section.headerLineNumber,
                        "LOB 列は大小比較できません: " + column.name + column.operator.mark);
            }
        }
    }

    private static boolean isCharacter(int type) {
        return type == Types.CHAR || type == Types.VARCHAR || type == Types.NCHAR || type == Types.NVARCHAR
                || isLob(type);
    }

    private static boolean isLob(int type) {
        return type == Types.CLOB || type == Types.NCLOB
                || type == Types.LONGVARCHAR || type == Types.LONGNVARCHAR;
    }

    // 列の型をDBのメタデータから取得する
    private int[] resolveTypes(String filePath, Section section) {
        String sql = "SELECT " + columnList(section) + " FROM " + section.tableName + " WHERE 1 = 0";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int[] types = new int[section.columns.size()];
            for (int i = 0; i < types.length; i++) {
                types[i] = metaData.getColumnType(i + 1);
            }
            return types;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    location(filePath, section.headerLineNumber) + " 列の型を取得できません: " + sql, e);
        }
    }

    private static String columnList(Section section) {
        return section.columns.stream().map(Column::name).collect(Collectors.joining(", "));
    }

    // 値を列の型に合わせて変換し、バインドする
    private static void bind(PreparedStatement ps, int index, int type, String value, String filePath, Row row)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, type);
            return;
        }
        try {
            switch (type) {
                // 小数を BigDecimal で渡すとDBが丸めて投入するため、整数に変換できなければエラーにする
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                        ps.setLong(index, new BigDecimal(value).longValueExact());
                // Oracle の FLOAT 型は内部が NUMBER のため、精度を落とさないよう BigDecimal で渡す
                case Types.NUMERIC, Types.DECIMAL, Types.FLOAT ->
                        ps.setBigDecimal(index, new BigDecimal(value));
                // 浮動小数点の列を numeric で比較すると、誤差で一致しなくなるため列と同じ精度で渡す
                case Types.REAL -> ps.setFloat(index, (float) toFloatingPoint(value, true));
                case Types.DOUBLE -> ps.setDouble(index, toFloatingPoint(value, false));
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
                case Types.TIME -> ps.setTime(index, Time.valueOf(LocalTime.parse(value, TIME_FORMAT)));
                case Types.BOOLEAN, Types.BIT -> ps.setBoolean(index, parseBoolean(value));
                // PostgreSQL の uuid や json など。型の変換はDBに任せる
                case Types.OTHER -> ps.setObject(index, value, Types.OTHER);
                default -> ps.setString(index, value);
            }
        } catch (IllegalArgumentException | ArithmeticException | DateTimeParseException e) {
            throw new IllegalArgumentException(
                    location(filePath, row.lineNumber) + " 値を列の型に変換できません: " + value, e);
        }
    }

    // Float.parseFloat などは範囲外の値を Infinity や 0 に丸めるため、BigDecimal で解析して範囲を確認する
    private static double toFloatingPoint(String value, boolean isFloat) {
        BigDecimal decimal = new BigDecimal(value);
        double converted = isFloat ? decimal.floatValue() : decimal.doubleValue();
        if (Double.isInfinite(converted) || (converted == 0 && decimal.signum() != 0)) {
            throw new IllegalArgumentException("範囲外の値です: " + value);
        }
        return converted;
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
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "t", "1", "yes", "y" -> true;
            case "false", "f", "0", "no", "n" -> false;
            default -> throw new IllegalArgumentException("真偽値ではありません: " + value);
        };
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
        int tableLineNumber = 0;
        Section section = null;

        for (int n = 0; n < lines.size(); n++) {
            int lineNumber = n + 1;
            String line = lines.get(n).trim();
            if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                line = line.substring(1).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]") || line.substring(1, line.length() - 1).isBlank()) {
                    throw formatError(filePath, lineNumber, "テーブル名は [テーブル名] の形式で記述してください");
                }
                requireHeader(filePath, tableName, tableLineNumber, section);
                tableName = line.substring(1, line.length() - 1).trim();
                tableLineNumber = lineNumber;
                section = null;
            } else if (line.startsWith("{")) {
                if (tableName == null) {
                    throw formatError(filePath, lineNumber, "列名の前に [テーブル名] を記述してください");
                }
                if (!line.endsWith("}")) {
                    throw formatError(filePath, lineNumber, "列名は {列名1|列名2} の形式で記述してください");
                }
                section = parseHeader(filePath, lineNumber, tableName, line.substring(1, line.length() - 1));
                sections.add(section);
            } else {
                if (section == null) {
                    throw formatError(filePath, lineNumber, "データの前に {列名1|列名2} を記述してください");
                }
                Row row = parseRow(filePath, lineNumber, section, line);
                foundPatterns.addAll(row.patterns);
                if (targetPatterns.isEmpty() || row.patterns.isEmpty()
                        || !Collections.disjoint(row.patterns, targetPatterns)) {
                    section.rows.add(row);
                }
            }
        }

        requireHeader(filePath, tableName, tableLineNumber, section);

        // パターン番号の指定間違いで、何も検証せずに成功するのを防ぐ
        Set<Integer> missing = new LinkedHashSet<>(targetPatterns);
        missing.removeAll(foundPatterns);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("パターン番号 " + missing + " の行がありません: " + filePath);
        }
        return sections;
    }

    // 列名の無いテーブルを、何も処理せずに見過ごさないようにする
    private static void requireHeader(String filePath, String tableName, int tableLineNumber, Section section) {
        if (tableName != null && section == null) {
            throw formatError(filePath, tableLineNumber, "[" + tableName + "] の後に {列名1|列名2} を記述してください");
        }
    }

    private static Section parseHeader(String filePath, int lineNumber, String tableName, String header) {
        List<String> names = split(header);
        boolean hasPattern = names.get(0).equals(PATTERN_COLUMN);
        if (hasPattern) {
            names = names.subList(1, names.size());
        }
        List<Column> columns = new ArrayList<>();
        for (String name : names) {
            Operator operator = Operator.of(name);
            String columnName = name.substring(0, name.length() - operator.mark.length()).trim();
            if (columnName.isEmpty()) {
                throw formatError(filePath, lineNumber, "空の列名があります");
            }
            if (MARK_CHARACTERS.indexOf(columnName.charAt(columnName.length() - 1)) >= 0) {
                throw formatError(filePath, lineNumber, "列名の末尾の記号は1つだけ付けられます: " + name);
            }
            columns.add(new Column(columnName, operator));
        }
        if (columns.isEmpty()) {
            throw formatError(filePath, lineNumber, "列名がありません");
        }
        return new Section(tableName, columns, hasPattern, lineNumber, new ArrayList<>());
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
        List<String> converted = values.stream().map(v -> v.equals(NULL_VALUE) ? null : v).toList();
        return new Row(lineNumber, line, rowPatterns, converted);
    }

    // 区切り文字で分割し、各項目の前後の空白を除く（末尾の空の項目も残す）
    private static List<String> split(String line) {
        return Arrays.stream(line.split(Pattern.quote(SEPARATOR), -1)).map(String::trim).toList();
    }

    private static IllegalArgumentException formatError(String filePath, int lineNumber, String message) {
        return new IllegalArgumentException(location(filePath, lineNumber) + " " + message);
    }

    private static String location(String filePath, int lineNumber) {
        return filePath + ":" + lineNumber;
    }

    private record Section(String tableName, List<Column> columns, boolean hasPattern, int headerLineNumber,
                           List<Row> rows) {
    }

    // 列名の末尾の記号と、比較の条件
    private enum Operator {
        // <= などの2文字の記号を、< などより先に判定する
        LESS_EQUAL("<=", "<=", true),
        GREATER_EQUAL(">=", ">=", true),
        NOT_EQUAL("!=", "<>", false),
        LESS("<", "<", true),
        GREATER(">", ">", true),
        PREFIX("%", "LIKE", false),
        IGNORE("@", null, false),
        EQUAL("", "=", false);

        final String mark;
        final String sql;
        final boolean ordering;

        Operator(String mark, String sql, boolean ordering) {
            this.mark = mark;
            this.sql = sql;
            this.ordering = ordering;
        }

        static Operator of(String name) {
            for (Operator operator : values()) {
                if (operator != EQUAL && name.endsWith(operator.mark)) {
                    return operator;
                }
            }
            return EQUAL;
        }
    }

    private record Column(String name, Operator operator) {
    }

    private record Row(int lineNumber, String text, Set<Integer> patterns, List<String> values) {
    }
}
