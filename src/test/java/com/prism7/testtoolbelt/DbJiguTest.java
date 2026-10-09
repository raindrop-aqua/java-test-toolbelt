package com.prism7.testtoolbelt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
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

        @Test
        void 指定したパターンと共通の行だけ投入する() {
            assertEquals(3, jigu.importFrom(DATA + "patterns.txt", 1));
            assertEquals(List.of("common", "john", "both"), memberNames());
        }

        @Test
        void 複数のパターンを指定できる() {
            assertEquals(4, jigu.importFrom(DATA + "patterns.txt", 1, 3));
            assertEquals(List.of("common", "john", "both", "mike"), memberNames());
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
            assertTrue(error.getMessage().contains("行3"), error.getMessage());
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
        void 列名の前にデータがあればエラーにする(@TempDir Path dir) {
            String file = write(dir, "[Member]", "1|john");
            assertThrows(IllegalArgumentException.class, () -> jigu.importFrom(file));
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
            assertThrows(IllegalStateException.class, () -> jigu.importFrom(file));
        }
    }

    private int update(String sql) {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
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
