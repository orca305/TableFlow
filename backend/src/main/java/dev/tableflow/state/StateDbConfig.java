package dev.tableflow.state;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 앱이 쓰는 유일한 {@code DataSource}는 상태 DB다. 직접 만들기 때문에 주소가 없을 때 내장 DB가 자동으로 선택되지 않는다.
 * Flyway가 이 연결로 {@code db/migration}의 변경을 적용한다.
 */
@Configuration
public class StateDbConfig {
    @Bean(destroyMethod = "close")
    HikariDataSource stateDataSource(@Value("${tableflow.state-db.url:}") String url, @Value("${tableflow.state-db.user:}") String user,
                                     @Value("${tableflow.state-db.password:}") String password, @Value("${tableflow.state-db.schema:tableflow}") String schema) {
        return open(new StateDbSettings(url, user, password, schema));
    }

    static HikariDataSource open(StateDbSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("tableflow-state");
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(settings.url()); config.setUsername(settings.user()); config.setPassword(settings.password());
        config.setSchema(settings.schema()); // 스키마를 붙이지 않은 SQL이 상태 스키마를 가리키게 한다
        config.setMaximumPoolSize(10); config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000); config.setValidationTimeout(3_000);
        config.addDataSourceProperty("connectTimeout", "5");
        // 응답이 끊긴 상태 DB를 무한정 기다리지 않는다(작업 생존 신호가 멈추면 안 된다).
        config.addDataSourceProperty("socketTimeout", "30");
        config.addDataSourceProperty("ApplicationName", "TableFlow state");
        try { return new HikariDataSource(config); }
        catch (HikariPool.PoolInitializationException e) {
            // 드라이버 메시지에는 비밀번호가 들어가지 않는다. 주소는 반복해서 출력하지 않는다.
            throw new IllegalStateException("상태 DB에 접속할 수 없어 서버를 시작하지 않습니다. PostgreSQL 실행 여부와 TABLEFLOW_STATE_DB_URL·USER·PASSWORD를 확인해주세요.", e);
        }
    }
}
