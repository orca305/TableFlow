package dev.tableflow.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.SQLException;

/** 상태 DB에 지금 질의할 수 있는지 확인한다. 실패 이유(주소 등)는 응답에 싣지 않는다. */
@Component
public class StateDbHealth {
    private static final Logger log = LoggerFactory.getLogger(StateDbHealth.class);
    private final DataSource dataSource;
    public StateDbHealth(DataSource dataSource) { this.dataSource = dataSource; }

    public boolean available() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery("SELECT 1")) { return rows.next(); }
        } catch (SQLException e) {
            log.warn("상태 DB 확인에 실패했습니다: sqlState={}", e.getSQLState());
            return false;
        }
    }
}
