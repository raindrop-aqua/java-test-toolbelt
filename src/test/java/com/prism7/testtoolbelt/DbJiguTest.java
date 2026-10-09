package com.prism7.testtoolbelt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL に接続してテストする。
 * テーブルの作成からトランザクション内で行い、終了時にロールバックするため、DBには何も残らない。
 */
class DbJiguTest {

    private static final String DATA = "src/test/resources/data/";

    private Connection connection;
    private DbJigu jigu;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
                System.getProperty("dbjigu.url", "jdbc:postgresql://192.168.64.2:5432/postgres"),
                System.getProperty("dbjigu.user", "postgres"),
                System.getProperty("dbjigu.password", "postgres"));
        connection.setAutoCommit(false);
        String schema = Files.readString(Path.of("src/test/resources/schema/postgresql.sql"));
        try (Statement statement = connection.createStatement()) {
            for (String ddl : schema.split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
        }
        jigu = new DbJigu(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void 投入して存在と削除を検証する() throws SQLException {
        assertEquals(7, jigu.importFrom(DATA + "sample.txt"));
        jigu.assertExists(DATA + "sample.txt");

        assertEquals(2, update("DELETE FROM TestTable WHERE string_column IN ('String2', 'String4')"));

        // String1 は削除していないので、1件だけ存在する
        assertEquals(1, jigu.verifyNotExists(DATA + "verify_deleted_data.txt"));
        AssertionError error = assertThrows(AssertionError.class,
                () -> jigu.assertNotExists(DATA + "verify_deleted_data.txt"));
        assertTrue(error.getMessage().contains("String1"), error.getMessage());
    }

    @Test
    void 存在しない行があればassertExistsが失敗する() {
        jigu.importFrom(DATA + "sample.txt");
        update("DELETE FROM Member WHERE MemberName = 'sam'");

        assertEquals(1, jigu.verifyExists(DATA + "sample.txt"));
        AssertionError error = assertThrows(AssertionError.class, () -> jigu.assertExists(DATA + "sample.txt"));
        assertTrue(error.getMessage().contains("sample.txt:12 [Member] 2|sam"), error.getMessage());
    }

    @Nested
    class パターン番号 {

        // 1つのテストメソッドでパターンを切り替える例。パターンごとにファイルやメソッドを分けずに済む
        @ParameterizedTest(name = "パターン {0} → {1}", quoteTextArguments = false)
        @CsvSource(delimiter = '|', textBlock = """
                1   | common,john,both
                2   | common,sam,both
                3   | common,mike
                1,3 | common,john,both,mike
                """)
        void パターンを切り替えて投入と検証を行う(String patternText, String expectedNames) {
            int[] patterns = Arrays.stream(patternText.split(",")).mapToInt(Integer::parseInt).toArray();
            List<String> expected = List.of(expectedNames.split(","));

            assertEquals(expected.size(), jigu.importFrom(DATA + "patterns.txt", patterns));
            assertEquals(expected, memberNames());
            jigu.assertExists(DATA + "patterns.txt", patterns);
        }

        @Test
        void 指定しなければ全行を投入する() {
            assertEquals(5, jigu.importFrom(DATA + "patterns.txt"));
        }

        @Test
        void 指定したパターンの行だけ検証する() {
            jigu.importFrom(DATA + "patterns.txt", 1);

            jigu.assertExists(DATA + "patterns.txt", 1);
            // パターン2のうち sam が存在しない
            assertEquals(1, jigu.verifyExists(DATA + "patterns.txt", 2));
            // パターン3は mike が存在せず、共通の行は存在する
            assertEquals(1, jigu.verifyNotExists(DATA + "patterns.txt", 3));
        }

        @Test
        void 存在しないパターンはエラーにする() {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> jigu.verifyExists(DATA + "patterns.txt", 1, 9));
            assertTrue(error.getMessage().contains("[9]"), error.getMessage());
        }

        @Test
        void パターン番号が数値でなければエラーにする(@TempDir Path dir) {
            String file = write(dir, "[Member]", "{#|MemberId|MemberName}", "a|1|john");
            assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
        }
    }

    @Nested
    class 型と値 {

        @Test
        void 列の型に合わせて投入する() throws SQLException {
            assertEquals(3, jigu.importFrom(DATA + "types.txt"));

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT * FROM TestTable ORDER BY numeric_column")) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal("1.00"), rs.getBigDecimal("numeric_column"));
                assertEquals("O'Brien", rs.getString("string_column"));
                assertEquals("abc       ", rs.getString("char_column"));
                assertEquals(java.sql.Date.valueOf("2024-04-01"), rs.getDate("date_column"));
                assertEquals(Timestamp.valueOf("2024-04-01 12:34:56.789"), rs.getTimestamp("timestamp_column"));
                assertTrue(rs.getBoolean("boolean_column"));
                assertEquals("テキスト", rs.getString("clob_column"));

                // 前後の空白は取り除き、<null> は NULL として投入する
                assertTrue(rs.next());
                assertEquals(new BigDecimal("2.00"), rs.getBigDecimal("numeric_column"));
                assertEquals("padded", rs.getString("string_column"));
                assertFalse(rs.getBoolean("boolean_column"));
                assertNull(rs.getString("clob_column"));

                // 末尾の空の項目は空文字として投入する
                assertTrue(rs.next());
                assertNull(rs.getString("string_column"));
                assertNull(rs.getDate("date_column"));
                assertEquals("", rs.getString("clob_column"));
            }
        }

        @Test
        void 投入した値をそのまま検証できる() {
            jigu.importFrom(DATA + "types.txt");
            jigu.assertExists(DATA + "types.txt");
        }

        @Test
        void NULLと値は区別して検証する(@TempDir Path dir) {
            jigu.importFrom(DATA + "types.txt");
            String file = write(dir, "[TestTable]", "{numeric_column|clob_column}", "2|<null>", "3|<null>");
            // 3 の clob_column は空文字なので存在しない
            assertEquals(1, jigu.verifyExists(file));
        }

        @Test
        void 型に変換できない値はエラーにする(@TempDir Path dir) {
            String file = write(dir, "[TestTable]", "{numeric_column}", "abc");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
            assertTrue(error.getMessage().startsWith(file + ":3 "), error.getMessage());
        }

        @Test
        void 浮動小数点の列は投入した値で検証できる(@TempDir Path dir) {
            String file = write(dir, "[TestTable]", "{real_column|double_column}", "1.1|0.1", "3.14159|2.5e-3");
            jigu.importFrom(file);
            jigu.assertExists(file);
        }

        @Test
        void 浮動小数点の列はSQLで投入した値とも一致する(@TempDir Path dir) {
            update("INSERT INTO TestTable (real_column, double_column) VALUES (1.1, 0.1)");
            jigu.assertExists(write(dir, "[TestTable]", "{real_column|double_column}", "1.1|0.1"));
        }

        @Test
        void 浮動小数点の範囲外の値はエラーにする(@TempDir Path dir) {
            for (String line : List.of("1e40|1", "1e-50|1", "1|1e400", "1.5f|1")) {
                String file = write(dir, "[TestTable]", "{real_column|double_column}", line);
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> jigu.importFrom(file), line);
                assertTrue(error.getMessage().startsWith(file + ":3 "), error.getMessage());
            }
        }

        @Test
        void 存在しない日付は時刻があってもエラーにする(@TempDir Path dir) {
            for (String[] columnAndValue : List.of(
                    new String[]{"timestamp_column", "2024-02-30 12:00"},
                    new String[]{"date_column", "2024-02-31 00:00"})) {
                String file = write(dir, "[TestTable]", "{" + columnAndValue[0] + "}", columnAndValue[1]);
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> jigu.importFrom(file));
                assertTrue(error.getMessage().startsWith(file + ":3 "), error.getMessage());
            }
        }

        @Test
        void 整数の列に小数を書くとエラーにする(@TempDir Path dir) throws SQLException {
            String file = write(dir, "[TestTable]", "{integer_column}", "1.5");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
            assertTrue(error.getMessage().startsWith(file + ":3 "), error.getMessage());

            // 小数点以下が0なら整数として投入する
            jigu.importFrom(write(dir, "[TestTable]", "{integer_column}", "2.0"));
            assertEquals("2", queryString("SELECT integer_column FROM TestTable"));
        }

        @Test
        void 時刻は秒を省略できる(@TempDir Path dir) throws SQLException {
            String file = write(dir, "[TestTable]", "{string_column|time_column}", "a|12:34", "b|23:59:59", "c|9:05");
            jigu.importFrom(file);
            assertEquals("12:34:00", queryString("SELECT time_column FROM TestTable WHERE string_column = 'a'"));
            jigu.assertExists(file);
            jigu.assertExists(write(dir, "[TestTable]", "{time_column}", "12:34:00"));
        }

        @Test
        void 時刻の書式が誤っていればエラーにする(@TempDir Path dir) {
            for (String value : List.of("12", "24:00", "12:34:56.789", "12:34:60")) {
                String file = write(dir, "[TestTable]", "{time_column}", value);
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> jigu.importFrom(file), value);
                assertTrue(error.getMessage().startsWith(file + ":3 "), error.getMessage());
            }
        }

        @Test
        void UUIDなどの型はDBに変換を任せる(@TempDir Path dir) {
            String file = write(dir, "[TestTable]", "{uuid_column}", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
            jigu.importFrom(file);
            jigu.assertExists(file);

            String invalid = write(dir, "[TestTable]", "{uuid_column}", "not-a-uuid");
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> jigu.importFrom(invalid));
            assertTrue(error.getMessage().startsWith(invalid + ":2 "), error.getMessage());
        }

        // PostgreSQL の text は VARCHAR として報告され、LOB 列の経路を通らないため、Java 側の比較を直接呼んで確かめる
        @ParameterizedTest(name = "{0}: ファイル={1} DB={2} → {3}")
        @CsvSource(delimiter = '|', nullValues = "<null>", textBlock = """
                EQUAL     | abc    | abc    | true
                EQUAL     | abc    | abd    | false
                EQUAL     | abc    | <null> | false
                EQUAL     | <null> | <null> | true
                EQUAL     | <null> | abc    | false
                NOT_EQUAL | abc    | abd    | true
                NOT_EQUAL | abc    | abc    | false
                NOT_EQUAL | abc    | <null> | true
                NOT_EQUAL | <null> | abc    | true
                NOT_EQUAL | <null> | <null> | false
                PREFIX    | ab     | abc    | true
                PREFIX    | bc     | abc    | false
                PREFIX    | ab     | <null> | false
                PREFIX    | <null> | <null> | true
                """)
        void LOB列はJava側で比較する(String operatorName, String expected, String actual, boolean matches)
                throws ReflectiveOperationException {
            Class<?> operatorClass = Class.forName(DbJigu.class.getName() + "$Operator");
            Object operator = Arrays.stream(operatorClass.getEnumConstants())
                    .filter(o -> ((Enum<?>) o).name().equals(operatorName))
                    .findFirst().orElseThrow();
            Method method = DbJigu.class.getDeclaredMethod("matchesInJava", operatorClass, String.class, String.class);
            method.setAccessible(true);
            assertEquals(matches, method.invoke(null, operator, expected, actual));
        }

        @Test
        void 検証のSQLエラーにはファイル名と行番号を含める(@TempDir Path dir) {
            String file = write(dir, "[Member]", "{MemberId|MemberName>=}", "1|a", "2|b");
            // 列の型は取得できるが、比較できない演算子にするため varchar の列を json 型に変える
            update("ALTER TABLE Member ALTER COLUMN MemberName TYPE json USING to_json(MemberName)");
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> jigu.verifyExists(file));
            assertTrue(error.getMessage().startsWith(file + ":3 "), error.getMessage());
        }
    }

    @Nested
    class 比較の条件 {

        @BeforeEach
        void importSample() {
            jigu.importFrom(DATA + "sample.txt");
        }

        @Test
        void 大小比較は境界の値を正しく扱う(@TempDir Path dir) {
            String file = write(dir, "[TestTable]",
                    "{string_column|numeric_column<=|numeric_column<|numeric_column>=|numeric_column>}",
                    "String2|234.56|234.57|234.56|234.55");
            jigu.assertExists(file);

            assertEquals(1, jigu.verifyExists(write(dir, "[TestTable]",
                    "{string_column|numeric_column<}", "String2|234.56")));
            assertEquals(1, jigu.verifyExists(write(dir, "[TestTable]",
                    "{string_column|numeric_column>}", "String2|234.56")));
        }

        @Test
        void 同じ列を2回書いて範囲を指定できる(@TempDir Path dir) {
            String file = write(dir, "[TestTable]",
                    "{timestamp_column>=|timestamp_column<}",
                    "2024-04-02|2024-04-03",
                    "2024-04-06|2024-04-07");
            // 2行目の範囲には該当するデータがない
            assertEquals(1, jigu.verifyExists(file));
        }

        @Test
        void 等しくないことを検証できる(@TempDir Path dir) {
            assertEquals(1, jigu.verifyExists(write(dir, "[Member]",
                    "{MemberId|MemberName!=}", "1|sam", "1|john")));
        }

        @Test
        void 等しくないの比較ではNULLの行も含める(@TempDir Path dir) {
            update("UPDATE Member SET MemberName = NULL WHERE MemberId = 2");
            jigu.assertExists(write(dir, "[Member]", "{MemberId|MemberName!=}", "2|john"));
        }

        @Test
        void NULLは記号に関わらずIS_NULLで比較する(@TempDir Path dir) {
            update("UPDATE Member SET MemberName = NULL WHERE MemberId = 2");
            String file = write(dir, "[Member]", "{MemberId|MemberName>=}", "1|<null>", "2|<null>");
            assertEquals(1, jigu.verifyExists(file));
        }

        @Test
        void 等しくないとNULLの組み合わせはIS_NOT_NULLで比較する(@TempDir Path dir) {
            update("UPDATE Member SET MemberName = NULL WHERE MemberId = 2");
            String file = write(dir, "[Member]", "{MemberId|MemberName!=}", "1|<null>", "2|<null>");
            assertEquals(1, jigu.verifyExists(file));
        }

        @Test
        void 前方一致で比較できる(@TempDir Path dir) {
            String file = write(dir, "[TestTable]", "{string_column%}", "Str", "String1", "tring");
            assertEquals(1, jigu.verifyExists(file));
        }

        @Test
        void 前方一致の値に含まれる記号は文字として扱う(@TempDir Path dir) {
            jigu.importFrom(write(dir, "[Member]", "{MemberId|MemberName}", "3|AB_C", "4|10%OFF!"));
            jigu.assertExists(write(dir, "[Member]", "{MemberName%}", "AB_", "10%", "10%OFF!"));
            jigu.assertNotExists(write(dir, "[Member]", "{MemberName%}", "A_", "1%O", "10!"));
        }

        @Test
        void 前方一致は文字列以外の列に使えない(@TempDir Path dir) {
            String file = write(dir, "[TestTable]", "{numeric_column%}", "123");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.verifyExists(file));
            assertTrue(error.getMessage().contains("numeric_column"), error.getMessage());
        }

        @Test
        void 記号を組み合わせるとエラーにする(@TempDir Path dir) {
            assertThrows(IllegalArgumentException.class,
                    () -> jigu.verifyExists(write(dir, "[TestTable]", "{numeric_column@<}", "1")));
            assertThrows(IllegalArgumentException.class,
                    () -> jigu.verifyExists(write(dir, "[TestTable]", "{numeric_column=}", "1")));
        }

        @Test
        void 記号の付いた列も投入では値をそのまま入れる(@TempDir Path dir) {
            jigu.importFrom(write(dir, "[Member]", "{MemberId>=|MemberName%}", "3|bob"));
            jigu.assertExists(write(dir, "[Member]", "{MemberId|MemberName}", "3|bob"));
        }

        @Test
        void 投入では同じ列を2回書けない(@TempDir Path dir) {
            String file = write(dir, "[Member]", "{MemberId>=|MEMBERID<}", "1|2");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
            assertTrue(error.getMessage().contains(":2 "), error.getMessage());
        }
    }

    @Nested
    class コメント {

        @Test
        void スラッシュ2つで始まる行は無視する(@TempDir Path dir) {
            String file = write(dir,
                    "// 会員",
                    "[Member]",
                    "  // 先頭に空白があってもコメント",
                    "{MemberId|MemberName}",
                    "1|john",
                    "// 2|sam",
                    "3|https://example.com/a");
            assertEquals(2, jigu.importFrom(file));
            assertEquals(List.of("john", "https://example.com/a"), memberNames());
            // コメントにした sam の行は検証もしない
            jigu.assertExists(file);
        }

        @Test
        void コメントがあってもエラーの行番号は変わらない(@TempDir Path dir) {
            String file = write(dir, "// 会員", "[Member]", "{MemberId|MemberName}", "// 1|john", "2|sam|extra");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
            assertTrue(error.getMessage().startsWith(file + ":5 "), error.getMessage());
        }

        @Test
        void 全行をコメントにすると検証はエラーにする(@TempDir Path dir) {
            String file = write(dir, "[Member]", "{MemberId|MemberName}", "// 1|john");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.verifyExists(file));
            assertTrue(error.getMessage().contains("検証対象の行がありません"), error.getMessage());
        }
    }

    @Nested
    class ファイルの誤り {

        @Test
        void 列数と値の数が合わなければエラーにする(@TempDir Path dir) {
            String file = write(dir, "[Member]", "{MemberId|MemberName}", "1|john|extra");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
            assertTrue(error.getMessage().endsWith(":3 列数(2)と値の数(3)が一致しません"), error.getMessage());
        }

        @Test
        void 列名の無いテーブルがあればエラーにする(@TempDir Path dir) {
            String atEnd = write(dir, "[TestTable]", "{string_column}", "x", "[NoSuchTable]");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(atEnd));
            assertTrue(error.getMessage().startsWith(atEnd + ":4 "), error.getMessage());

            String inMiddle = write(dir, "[Member]", "[TestTable]", "{string_column}", "x");
            error = assertThrows(IllegalArgumentException.class, () -> jigu.verifyExists(inMiddle));
            assertTrue(error.getMessage().startsWith(inMiddle + ":1 "), error.getMessage());
        }

        @Test
        void 書式エラーでは区切り文字を案内する(@TempDir Path dir) {
            String file = write(dir, "[Member]", "{MemberId|MemberName");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
            assertTrue(error.getMessage().contains("{列名1|列名2}"), error.getMessage());
        }

        @Test
        void テーブル名の書式が誤っていればエラーにする(@TempDir Path dir) {
            for (String tableLine : List.of("[", "[]", "[ ]", "[Member")) {
                String file = write(dir, tableLine, "{MemberId}", "1");
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> jigu.importFrom(file), tableLine);
                assertTrue(error.getMessage().endsWith(":1 テーブル名は [テーブル名] の形式で記述してください"),
                        error.getMessage());
            }
        }

        @Test
        void 列名の前にデータがあればエラーにする(@TempDir Path dir) {
            String file = write(dir, "[Member]", "1|john");
            assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
        }

        @Test
        void 先頭のBOMは無視する(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("bom.txt");
            Files.writeString(file, "\uFEFF[Member]\n{MemberId|MemberName}\n1|john\n", StandardCharsets.UTF_8);
            assertEquals(1, jigu.importFrom(file.toString()));
            jigu.assertExists(file.toString());
        }

        @Test
        void ファイルが無ければエラーにする() {
            assertThrows(UncheckedIOException.class, () -> jigu.verifyExists(DATA + "no_such_file.txt"));
        }

        @Test
        void 検証対象の行が無ければエラーにする(@TempDir Path dir) {
            String file = write(dir, "[Member]", "{MemberId|MemberName}");
            assertThrows(IllegalArgumentException.class, () -> jigu.verifyExists(file));
        }

        @Test
        void テーブルが無ければエラーにする(@TempDir Path dir) {
            String file = write(dir, "[NoSuchTable]", "{id}", "1");
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> jigu.importFrom(file));
            assertTrue(error.getMessage().startsWith(file + ":2 "), error.getMessage());
        }
    }

    private int update(String sql) {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }

    private List<String> memberNames() {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MemberName FROM Member ORDER BY MemberId")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return names;
    }

    private static String write(Path dir, String... lines) {
        try {
            return Files.write(dir.resolve("fixture.txt"), List.of(lines), StandardCharsets.UTF_8).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
