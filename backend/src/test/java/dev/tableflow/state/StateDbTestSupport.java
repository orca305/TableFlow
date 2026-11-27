package dev.tableflow.state;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import java.sql.SQLException;
import java.util.UUID;

/** 전용 상태 DB(infra/state/compose.yml)에 시험마다 일회용 스키마를 만들고 마이그레이션한다. 끝나면 그 스키마만 지운다. */
public final class StateDbTestSupport implements AutoCloseable {
    private final String schema = "tf_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final HikariDataSource dataSource;

    public StateDbTestSupport() {
        dataSource = open(schema);
        Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema).locations("classpath:db/migration").load().migrate();
    }
    private static HikariDataSource open(String schema) {
        return StateDbConfig.open(new StateDbSettings(System.getenv("TABLEFLOW_STATE_DB_URL"), System.getenv("TABLEFLOW_STATE_DB_USER"),
            System.getenv("TABLEFLOW_STATE_DB_PASSWORD"), schema));
    }
    public HikariDataSource dataSource() { return dataSource; }
    /** 같은 스키마를 보는 별도의 연결 묶음(다른 서버 인스턴스를 흉내 낼 때). */
    public HikariDataSource another() { return open(schema); }

    @Override public void close() throws SQLException {
        try (var cleanup = open(schema); var connection = cleanup.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally { dataSource.close(); }
    }
}
