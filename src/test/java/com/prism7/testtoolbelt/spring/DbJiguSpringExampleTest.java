package com.prism7.testtoolbelt.spring;

import com.prism7.testtoolbelt.DbJigu;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring（Spring Boot + Spring Data JPA）での DbJigu の利用例。
 * <ul>
 *   <li>{@code @Transactional} のテストでは、テストのトランザクションに参加している Connection を
 *       {@link DataSourceUtils#getConnection} で取得して渡す。DbJigu の投入もテスト終了時にロールバックされる。</li>
 *   <li>JPA の変更は flush するまでDBに書かれないため、検証の前に {@code entityManager.flush()} を呼ぶ。</li>
 * </ul>
 * テーブルは {@code @Sql} でテストのトランザクション内に作成するため、テーブルごとロールバックされる。
 */
@SpringBootTest
@Transactional
@Sql("/schema/postgresql.sql")
class DbJiguSpringExampleTest {

    private static final String DATA = "src/test/resources/data/spring/";

    @Autowired
    DataSource dataSource;

    @Autowired
    MemberRepository memberRepository;

    @PersistenceContext
    EntityManager entityManager;

    private DbJigu jigu;

    @BeforeEach
    void setUp() {
        jigu = new DbJigu(DataSourceUtils.getConnection(dataSource));
        jigu.importFrom(DATA + "setup.txt");
    }

    @Test
    void 投入したデータをJPAで読める() {
        assertEquals(2, memberRepository.count());
        assertEquals("sam", memberRepository.findByMemberId(BigDecimal.valueOf(2)).orElseThrow().getMemberName());
    }

    @Test
    void JPAで登録した結果を検証する() {
        memberRepository.save(new Member(BigDecimal.valueOf(10), "bob"));
        entityManager.flush();

        jigu.assertExists(DATA + "expected_registered.txt");
    }

    @Test
    void JPAの変更はflushしてから検証する() {
        memberRepository.findByMemberId(BigDecimal.valueOf(2)).orElseThrow().setMemberName("samuel");

        // flush 前は変更がDBに書かれていないため、samuel の行は見つからない
        assertEquals(1, jigu.verifyExists(DATA + "expected_renamed.txt"));

        entityManager.flush();
        jigu.assertExists(DATA + "expected_renamed.txt");
    }

    // テスト終了後、DbJigu で投入したデータもテーブルごとロールバックされていることを確認する
    @AfterTransaction
    void ロールバックされている() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'member'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }
}
