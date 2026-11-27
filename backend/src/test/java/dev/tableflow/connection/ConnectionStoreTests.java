package dev.tableflow.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.state.StateDbTestSupport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 연결 저장소의 보안 동작(암호화, 비밀번호 비반환, 접속 정보 불일치 거부)을 전용 상태 DB에서 검증한다. 이관 대상 DB는 필요 없다. */
class ConnectionStoreTests {
    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper();
    static StateDbTestSupport db;
    @BeforeAll static void open() { db = new StateDbTestSupport(); }
    @AfterAll static void close() throws Exception { db.close(); }
    @BeforeEach void empty() { jdbc().sql("DELETE FROM connection").update(); }
    static JdbcClient jdbc() { return JdbcClient.create(db.dataSource()); }

    ConnectionStore store(boolean passwordAllowed) {
        return new ConnectionStore(true, passwordAllowed, dir.resolve("connections.key").toString(), "", db.dataSource());
    }
    Credentials creds(String host, String user, String password) {
        return new Credentials(Dbms.postgresql, host, 15432, "tableflow", user, password, false);
    }
    Credentials withSaved(Credentials c, String id) {
        return new Credentials(c.dbms(), c.host(), c.port(), c.database(), c.username(), "", c.tls(), id);
    }
    String code(Runnable action) { return assertThrows(Failure.class, action::run).code; }

    @Test void savesWithoutPasswordAndRefusesToUseIt() {
        var store = store(true);
        var saved = store.save("테스트 PG", creds("localhost", "tableflow", ""), false);
        assertFalse(saved.passwordSaved());
        assertEquals(1, store.list().size());
        assertEquals("JDBC_AUTH", code(() -> store.passwordFor(withSaved(creds("localhost", "tableflow", ""), saved.id()))));
    }

    @Test void encryptsPasswordAtRestAndNeverListsIt() throws Exception {
        var store = store(true);
        var saved = store.save("운영 복제본", creds("localhost", "tableflow", "S3cret-Pass!"), true);
        assertTrue(saved.passwordSaved());
        assertFalse(json.writeValueAsString(store.list()).contains("S3cret"), "목록(브라우저로 가는 형태)에 비밀번호가 없어야 한다");
        String rows = String.join("\n", jdbc().sql("SELECT row_to_json(c)::text FROM connection c").query(String.class).list());
        assertFalse(rows.contains("S3cret"), "DB에 평문 비밀번호가 없어야 한다");
        assertTrue(Files.exists(dir.resolve("connections.key")));
        assertEquals("S3cret-Pass!", store.passwordFor(withSaved(creds("localhost", "tableflow", ""), saved.id())));
        assertEquals("S3cret-Pass!", store(true).passwordFor(withSaved(creds("localhost", "tableflow", ""), saved.id())), "새 인스턴스(재시작)에서도 같은 키 파일로 복호화");
    }

    @Test void refusesStoredPasswordForADifferentTarget() {
        var store = store(true);
        var saved = store.save("PG", creds("localhost", "tableflow", "pw"), true);
        assertEquals("CONNECTION_MISMATCH", code(() -> store.passwordFor(withSaved(creds("127.0.0.1", "tableflow", ""), saved.id()))));
        assertEquals("CONNECTION_MISMATCH", code(() -> store.passwordFor(withSaved(creds("localhost", "other", ""), saved.id()))));
        assertEquals("CONNECTION_NOT_FOUND", code(() -> store.passwordFor(withSaved(creds("localhost", "tableflow", ""), "00000000-0000-0000-0000-000000000000"))));
    }

    @Test void updateKeepsOrDropsPasswordAsRequested() {
        var store = store(true);
        var first = store.save("PG", creds("localhost", "tableflow", "pw1"), true);
        var kept = store.save("PG", creds("localhost", "tableflow", ""), true);
        assertEquals(first.id(), kept.id());
        assertEquals("pw1", store.passwordFor(withSaved(creds("localhost", "tableflow", ""), first.id())));
        var dropped = store.save("PG", creds("localhost", "tableflow", ""), false);
        assertFalse(dropped.passwordSaved());
        assertEquals(1, store.list().size());
        assertEquals("JDBC_AUTH", code(() -> store.passwordFor(withSaved(creds("localhost", "tableflow", ""), first.id()))));
        assertThrows(IllegalArgumentException.class, () -> store.save("신규", creds("localhost", "tableflow", ""), true));
    }

    @Test void changingDriverRequiresPasswordAgainAndPersistsSelection() {
        var store = store(true);
        var original = creds("localhost", "tableflow", "pw");
        var saved = store.save("PG", original, true);
        String driver = java.util.UUID.randomUUID().toString();
        var changed = new Credentials(original.dbms(), original.host(), original.port(), original.database(), original.username(), "", original.tls(), saved.id(), driver);
        assertEquals("CONNECTION_MISMATCH", code(() -> store.passwordFor(changed)));
        assertThrows(IllegalArgumentException.class, () -> store.save("PG", changed, true));
        var entered = new Credentials(original.dbms(), original.host(), original.port(), original.database(), original.username(), "new-pw", original.tls(), saved.id(), driver);
        assertEquals(driver, store.save("PG", entered, true).driverId());
        assertEquals("new-pw", store.passwordFor(changed));
    }

    @Test void ciphertextMovedToAnotherEntryCannotBeDecrypted() throws Exception {
        var store = store(true);
        var a = store.save("A", creds("localhost", "ua", "pw-a"), true);
        var b = store.save("B", creds("localhost", "ub", "pw-b"), true);
        jdbc().sql("UPDATE connection SET password_enc = (SELECT password_enc FROM connection WHERE id = :a) WHERE id = :b")
            .param("a", java.util.UUID.fromString(a.id())).param("b", java.util.UUID.fromString(b.id())).update();
        assertEquals("CONNECTION_KEY", code(() -> store.passwordFor(withSaved(creds("localhost", "ub", ""), b.id()))));
    }

    @Test void deleteAndDisabledPasswordSaving() {
        var store = store(true);
        var saved = store.save("PG", creds("localhost", "tableflow", "pw"), true);
        assertTrue(store.delete(saved.id()));
        assertFalse(store.delete(saved.id()));
        assertTrue(store.list().isEmpty());
        var locked = store(false);
        assertEquals("CONNECTION_PASSWORD_DISABLED", code(() -> locked.save("PG", creds("localhost", "tableflow", "pw"), true)));
        assertDoesNotThrow(() -> locked.save("PG(비밀번호 없음)", creds("localhost", "tableflow", ""), false));
    }

    @Test void explorerResolvesSavedPasswordOnlyWhenNoneIsGiven() {
        var store = store(true);
        var saved = store.save("PG", creds("localhost", "tableflow", "stored-pw"), true);
        var explorer = new JdbcExplorer(true, "localhost"); explorer.setStore(store);
        assertEquals("stored-pw", explorer.properties(withSaved(creds("localhost", "tableflow", ""), saved.id())).getProperty("password"));
        var typed = new Credentials(Dbms.postgresql, "localhost", 15432, "tableflow", "tableflow", "typed-pw", false, saved.id());
        assertEquals("typed-pw", explorer.properties(typed).getProperty("password"), "입력한 비밀번호가 있으면 그것을 쓴다");
        assertEquals("", explorer.properties(creds("localhost", "tableflow", "")).getProperty("password"), "저장된 연결 ID가 없으면 빈 비밀번호 그대로");
    }

    @Test void listsInSavedOrderFindsSavedIdAndSharesStateBetweenServers() {
        var first = store(true); var second = store(true);
        var a = first.save("A", creds("localhost", "ua", "pw"), true);
        first.save("B", creds("localhost", "ub", ""), false);
        assertEquals(java.util.List.of("A", "B"), second.list().stream().map(ConnectionStore.Saved::name).toList());
        second.save("A", creds("localhost", "ua", ""), true);
        assertEquals(java.util.List.of("B", "A"), first.list().stream().map(ConnectionStore.Saved::name).toList(), "갱신하면 맨 뒤로");
        assertEquals(a.id(), first.findSavedId(creds("LOCALHOST", "ua", "")), "호스트는 대소문자를 구분하지 않는다");
        assertNull(first.findSavedId(creds("localhost", "ub", "")), "비밀번호를 저장하지 않은 연결은 제외");
        assertNull(first.findSavedId(creds("localhost", "UA", "")));
        for (String bad : java.util.List.of("not-a-uuid", "------------------------------------")) {
            assertFalse(first.delete(bad));
            assertEquals("CONNECTION_NOT_FOUND", code(() -> first.passwordFor(withSaved(creds("localhost", "ua", ""), bad))));
        }
        assertThrows(IllegalArgumentException.class, () -> first.save("C", new Credentials(Dbms.postgresql, "localhost", 1, "d", "u", "", false, null, "------------------------------------"), false));
    }

    @Test void concurrentServersKeepTheLimitAndOneRowPerName() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 99; i++) store(true).save("n" + i, creds("localhost", "u" + i, ""), false);
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 12; i++) { int n = i; tasks.add(() -> { try { store(true).save(n < 6 ? "same" : "extra" + n, creds("localhost", "x", ""), false); return true; } catch (IllegalArgumentException limit) { return false; } }); }
            for (var result : pool.invokeAll(tasks)) result.get();
            assertEquals(100, store(true).list().size());
            assertTrue(store(true).list().stream().filter(s -> s.name().equals("same")).count() <= 1);
        } finally { pool.shutdownNow(); }
    }

    @Test void credentialsJsonAcceptsOptionalSavedIdAndStaysRedacted() throws Exception {
        String base = "{\"dbms\":\"postgresql\",\"host\":\"localhost\",\"port\":15432,\"database\":\"tableflow\",\"username\":\"u\",\"password\":\"pw\",\"tls\":false";
        var plain = json.readValue(base + "}", Credentials.class);
        assertNull(plain.savedId());
        var saved = json.readValue(base + ",\"savedId\":\"123e4567-e89b-12d3-a456-426614174000\"}", Credentials.class);
        assertEquals("123e4567-e89b-12d3-a456-426614174000", saved.savedId());
        assertFalse(saved.toString().contains("pw"));
    }
}
