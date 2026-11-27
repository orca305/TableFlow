package dev.tableflow.state;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 빈 스키마에서 시작해 마이그레이션이 적용되고 상태 확인이 DB를 반영하는지 전용 상태 DB에서 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class StateDbIntegrationTests {
    /** 다른 시험과 섞이지 않는 일회용 스키마. 처음에는 없는 상태다. */
    static final String SCHEMA = "tf_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @DynamicPropertySource static void isolatedSchema(DynamicPropertyRegistry registry) { registry.add("tableflow.state-db.schema", () -> SCHEMA); }
    @Autowired DataSource dataSource; @Autowired MockMvc mvc;
    @Autowired Environment environment;
    static String url, user, password;

    @Test void migratesAnEmptySchemaAndPointsQueriesAtIt() {
        url = environment.getProperty("tableflow.state-db.url"); user = environment.getProperty("tableflow.state-db.user"); password = environment.getProperty("tableflow.state-db.password");
        var jdbc = JdbcClient.create(dataSource);
        assertEquals(SCHEMA, jdbc.sql("SELECT current_schema()").query(String.class).single());
        assertEquals("1", jdbc.sql("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank LIMIT 1").query(String.class).single());
        jdbc.sql("INSERT INTO app_meta (key, value) VALUES (:key, :value)").param("key", "probe").param("value", "ok").update();
        assertEquals("ok", jdbc.sql("SELECT value FROM app_meta WHERE key = :key").param("key", "probe").query(String.class).single());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = :schema AND table_name = 'app_meta'").param("schema", SCHEMA).query(Integer.class).single());
    }
    @Test void healthReportsTheStateDatabaseWithoutConnectionDetails() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.stateDb").value("ok"))
            .andExpect(jsonPath("$.version").value("0.1.0")).andExpect(jsonPath("$.url").doesNotExist());
    }
    @Test void healthIsUnavailableOnceTheDatabaseCannotBeUsed() {
        var broken = StateDbConfig.open(new StateDbSettings(environment.getProperty("tableflow.state-db.url"), environment.getProperty("tableflow.state-db.user"),
            environment.getProperty("tableflow.state-db.password"), SCHEMA));
        var health = new StateDbHealth(broken);
        assertTrue(health.available());
        broken.close();
        assertFalse(health.available());
        var controller = new dev.tableflow.api.TableController(null, null, null, health);
        var response = controller.health();
        assertEquals(503, response.getStatusCode().value()); assertEquals("unavailable", response.getBody().get("stateDb"));
    }
    @AfterAll static void dropSchema() throws Exception {
        if (url == null) return;
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); // 이 시험이 만든 스키마만, 전용 상태 DB에서
        }
    }
}
