package com.prism7.testtoolbelt.example;

import com.prism7.testtoolbelt.DbJigu;
import com.prism7.testtoolbelt.example.OrderService.OrderLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ガイド（docs/guide.ja.md）に載せている例。受注管理を題材に、Given-When-Then の流れで DbJigu を使う。
 * <ul>
 *   <li>Given: {@code jigu.importFrom} でテストデータを投入する</li>
 *   <li>When: テスト対象（{@link OrderService}）を実行する</li>
 *   <li>Then: {@code jigu.assertExists} / {@code jigu.assertNotExists} でDBの状態を検証する</li>
 * </ul>
 * テーブルの作成からトランザクション内で行い、終了時にロールバックするため、DBには何も残らない。
 */
class OrderServiceTest {

    private static final String DATA = "src/test/resources/data/order/";

    private Connection connection;
    private DbJigu jigu;
    private OrderService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
                System.getProperty("dbjigu.url", "jdbc:postgresql://192.168.64.2:5432/postgres"),
                System.getProperty("dbjigu.user", "postgres"),
                System.getProperty("dbjigu.password", "postgres"));
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String ddl : Files.readString(Path.of("src/test/resources/schema/order.sql")).split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
        }
        jigu = new DbJigu(connection);
        service = new OrderService(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.rollback();
        connection.close();
    }

    // 単一テーブルの投入と検証
    @Test
    void 入荷すると在庫が増える() throws SQLException {
        // Given
        jigu.importFrom(DATA + "restock/given.txt");

        // When
        service.restock("P002", 30);

        // Then
        jigu.assertExists(DATA + "restock/expected.txt");
    }

    @Nested
    class 注文する {

        // 複数テーブルの投入と検証
        @Test
        void 受注と明細が登録され在庫が減る() throws SQLException {
            // Given
            jigu.importFrom(DATA + "place/given.txt");

            // When
            service.placeOrder("A001", "C001", List.of(new OrderLine("P001", 3), new OrderLine("P002", 2)));

            // Then
            jigu.assertExists(DATA + "place/expected.txt");
            jigu.assertNotExists(DATA + "place/not_exists.txt");
        }

        @Test
        void 在庫が足りなければ何も登録しない() {
            // Given
            jigu.importFrom(DATA + "place/given.txt");

            // When
            assertThrows(IllegalStateException.class,
                    () -> service.placeOrder("A001", "C001", List.of(new OrderLine("P002", 51))));

            // Then: 投入したデータのまま変わっておらず、受注と明細は1件も無い
            jigu.assertExists(DATA + "place/given.txt");
            jigu.assertNotExists(DATA + "place/out_of_stock_not_exists.txt");
        }

        // パターン番号で、会員ランクだけを変えて同じテストを繰り返す
        @ParameterizedTest(name = "パターン{0}: {1}", quoteTextArguments = false)
        @CsvSource(delimiter = '|', textBlock = """
                1 | GOLD は10%引き
                2 | SILVER は5%引き
                3 | REGULAR は割引なし
                """)
        void 会員ランクに応じて割り引く(int pattern, String description) throws SQLException {
            // Given
            jigu.importFrom(DATA + "place_by_rank/given.txt", pattern);

            // When
            service.placeOrder("A001", "C001", List.of(new OrderLine("P002", 10)));

            // Then
            jigu.assertExists(DATA + "place_by_rank/expected.txt", pattern);
        }
    }

    @Nested
    class キャンセルする {

        @Test
        void 明細を削除して在庫を戻す() throws SQLException {
            // Given
            jigu.importFrom(DATA + "cancel/given.txt");

            // When
            service.cancel("A001", "お客様都合");

            // Then
            jigu.assertExists(DATA + "cancel/expected.txt");
            jigu.assertNotExists(DATA + "cancel/not_exists.txt");
        }

        @Test
        void 出荷済みの注文はキャンセルできない() {
            // Given
            jigu.importFrom(DATA + "cancel/given.txt");

            // When
            assertThrows(IllegalStateException.class, () -> service.cancel("A002", "お客様都合"));

            // Then: 投入したデータのまま変わっていない
            jigu.assertExists(DATA + "cancel/given.txt");
        }
    }

    @Test
    void 締め日時より前に受け付けた注文を出荷する() throws SQLException {
        // Given
        jigu.importFrom(DATA + "ship/given.txt");

        // When
        int shipped = service.shipOrderedBefore(LocalDateTime.of(2024, 4, 10, 0, 0));

        // Then
        assertEquals(1, shipped);
        jigu.assertExists(DATA + "ship/expected.txt");
        jigu.assertNotExists(DATA + "ship/not_exists.txt");
    }
}
