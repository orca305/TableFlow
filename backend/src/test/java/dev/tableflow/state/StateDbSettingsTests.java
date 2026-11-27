package dev.tableflow.state;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 상태 DB 설정 검사: 기본값 없이 실패하고, 비밀번호 노출과 인증서를 확인하지 않는 원격 접속을 막는다. DB 없이 실행한다. */
class StateDbSettingsTests {
    static final String OK = "jdbc:postgresql://127.0.0.1:15442/tableflow";

    static String refusal(String url, String user, String password, String schema) {
        return assertThrows(IllegalStateException.class, () -> new StateDbSettings(url, user, password, schema), url).getMessage();
    }

    @Test void requiresAddressUserAndPassword() {
        assertTrue(refusal("", "u", "p", "tableflow").contains("TABLEFLOW_STATE_DB_URL"));
        assertTrue(refusal(null, "u", "p", null).contains("TABLEFLOW_STATE_DB_URL"));
        assertTrue(refusal(OK, " ", "p", null).contains("TABLEFLOW_STATE_DB_USER"));
        assertTrue(refusal(OK, "u", "", null).contains("TABLEFLOW_STATE_DB_PASSWORD"));
        assertEquals("tableflow", new StateDbSettings(OK, "u", "p", " ").schema());
    }
    @Test void acceptsLoopbackAndVerifiedTlsOnly() {
        for (String url : List.of(OK, "jdbc:postgresql://localhost/tableflow", "jdbc:postgresql://[::1]:5432/tableflow", OK + "?sslmode=disable",
                "jdbc:postgresql://192.0.2.10:5432/tableflow?sslmode=verify-full"))
            assertDoesNotThrow(() -> new StateDbSettings(url, "u", "p", "tableflow"), url);
        for (String url : List.of("jdbc:postgresql://192.0.2.10/tableflow", "jdbc:postgresql://192.0.2.10/tableflow?sslmode=require",
                "jdbc:postgresql://192.0.2.10/tableflow?sslmode=verify-full&sslmode=disable"))
            assertTrue(refusal(url, "u", "p", "tableflow").contains("verify-full"), url);
    }
    @Test void rejectsOtherDatabasesSecretsInTheAddressAndUnsafeNames() {
        for (String url : List.of("jdbc:h2:mem:state", "jdbc:mysql://localhost/tableflow", "jdbc:postgresql:tableflow", "jdbc:postgresql://localhost:5432,192.0.2.10:5432/tableflow",
                "jdbc:postgresql://u:secret@localhost/tableflow", OK + "?password=secret", OK + "?PassWord=secret", OK + "?%70assword=secret", OK + "?user=other",
                OK + "?socketFactory=x.Y", OK + "?sslfactory=x.Y"))
            assertFalse(refusal(url, "u", "p", "tableflow").contains("secret"), url);
        for (String schema : List.of("Table", "a b", "a;drop", "1a", "a".repeat(64))) refusal(OK, "u", "p", schema);
        assertFalse(new StateDbSettings(OK, "someone", "top-secret", null).toString().contains("top-secret"));
    }
    @Test void applicationDoesNotStartWithoutAnAddressEvenWithAnEmbeddedDatabaseOnTheClasspath() {
        // 테스트 환경 변수(TABLEFLOW_STATE_DB_URL)가 있어도 빈 주소를 명시해 확인한다
        new ApplicationContextRunner().withUserConfiguration(StateDbConfig.class).withPropertyValues("tableflow.state-db.url=").run(context -> {
            Throwable failure = context.getStartupFailure(); assertNotNull(failure);
            while (failure.getCause() != null) failure = failure.getCause();
            assertTrue(failure.getMessage().contains("TABLEFLOW_STATE_DB_URL"), failure.getMessage());
        });
    }
    @Test void unreachableDatabaseStopsStartupWithGuidanceAndWithoutThePassword() {
        var failure = assertThrows(IllegalStateException.class,
            () -> StateDbConfig.open(new StateDbSettings("jdbc:postgresql://127.0.0.1:1/tableflow", "u", "top-secret", "tableflow")));
        assertTrue(failure.getMessage().contains("TABLEFLOW_STATE_DB_URL"), failure.getMessage());
        for (Throwable t = failure; t != null; t = t.getCause()) assertFalse(String.valueOf(t.getMessage()).contains("top-secret"));
    }
}
