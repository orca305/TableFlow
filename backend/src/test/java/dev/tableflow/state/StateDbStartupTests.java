package dev.tableflow.state;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 표가 하나도 없는 상태 DB(새 설치)에서 서버가 시작되는지 확인한다. 시작하면서 표를 조회하는 서비스는 마이그레이션 뒤에 만들어져야 한다.
 * 다른 시험은 이미 마이그레이션된 스키마를 다시 쓰므로 이 순서 문제를 드러내지 못한다.
 */
@SpringBootTest
@DirtiesContext
class StateDbStartupTests {
    static final String SCHEMA = "tf_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @DynamicPropertySource static void emptySchema(DynamicPropertyRegistry registry) { registry.add("tableflow.state-db.schema", () -> SCHEMA); }

    @AfterAll static void drop() throws SQLException {
        try (var connection = DriverManager.getConnection(System.getenv("TABLEFLOW_STATE_DB_URL"), System.getenv("TABLEFLOW_STATE_DB_USER"), System.getenv("TABLEFLOW_STATE_DB_PASSWORD"));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test void startsOnAnEmptyStateDatabase(@Autowired DataSource dataSource) {
        assertEquals(0, JdbcClient.create(dataSource).sql("SELECT count(*) FROM migration_job").query(Integer.class).single());
    }
}
