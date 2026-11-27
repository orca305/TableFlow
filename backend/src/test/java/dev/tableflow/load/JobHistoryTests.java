package dev.tableflow.load;

import dev.tableflow.load.DatabaseLoader.LoadFailure;
import dev.tableflow.state.StateDbTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 작업 이력의 동작(순서·정리·비밀정보 제외·저장 실패 격리)을 전용 상태 DB에서 확인한다. */
class JobHistoryTests {
    static StateDbTestSupport db;
    @BeforeAll static void open() { db = new StateDbTestSupport(); }
    @AfterAll static void close() throws Exception { db.close(); }
    JdbcClient jdbc() { return JdbcClient.create(db.dataSource()); }
    @BeforeEach void empty() { jdbc().sql("DELETE FROM job_history").update(); }
    JobHistory history() { return new JobHistory(true, db.dataSource()); }
    int count() { return jdbc().sql("SELECT count(*) FROM job_history").query(Integer.class).single(); }
    static DatabaseLoader.Result ok(String requestId, int rows) { return new DatabaseLoader.Result(requestId, "t", rows, false); }

    @Test void recordsSuccessReplayAndFailureNewestFirstWithoutSecrets() {
        var h = history();
        h.run("migrate", "r1", "oracle · FREEPDB1 · APP.SRC", "postgresql · tableflow · public.dst", () -> new DatabaseLoader.Result("r1", "dst", 3, false));
        h.run("migrate", "r1", "a", "b", () -> new DatabaseLoader.Result("r1", "dst", 3, true));
        var failure = assertThrows(LoadFailure.class, () -> h.run("load", "r2", "한글 파일.csv", "mysql · tableflow · tableflow.t",
            () -> { throw new LoadFailure(422, "LOAD_REJECTED", "DB 제약조건 또는 값 변환에 실패해 전체 적재를 취소했습니다."); }));
        assertEquals("LOAD_REJECTED", failure.code);
        var items = h.recent(10);
        assertEquals(3, items.size());
        assertEquals("failed", items.get(0).status()); assertEquals("LOAD_REJECTED", items.get(0).errorCode()); assertEquals(0, items.get(0).rows());
        assertEquals("한글 파일.csv", items.get(0).source()); assertEquals("DB 제약조건 또는 값 변환에 실패해 전체 적재를 취소했습니다.", items.get(0).message());
        assertEquals("replayed", items.get(1).status()); assertNull(items.get(1).errorCode()); assertNull(items.get(1).message());
        assertEquals("success", items.get(2).status()); assertEquals(3, items.get(2).rows()); assertEquals("r1", items.get(2).requestId());
        assertDoesNotThrow(() -> java.time.Instant.parse(items.get(2).startedAt()));
        assertDoesNotThrow(() -> java.util.UUID.fromString(items.get(2).id()));
        assertEquals(1, h.recent(1).size());
        String raw = String.join("\n", jdbc().sql("SELECT row_to_json(j)::text FROM job_history j").query(String.class).list()).toLowerCase();
        assertFalse(raw.contains("password")); assertFalse(raw.contains("localhost"));
        assertEquals(3, new JobHistory(true, db.dataSource()).recent(10).size(), "서버를 다시 시작해도 남는다");
    }

    @Test void unknownExceptionsDoNotLeakTheirMessage() {
        var h = history();
        assertThrows(IllegalStateException.class, () -> h.run("load", "r", "s", "t", () -> { throw new IllegalStateException("jdbc:postgresql://secret-host/db password=hunter2"); }));
        var entry = h.recent(1).getFirst();
        assertEquals("INTERNAL_ERROR", entry.errorCode());
        assertFalse(entry.message().contains("hunter2")); assertFalse(entry.message().contains("secret-host"));
    }

    @Test void keepsTheNewestFiveHundredOnceThereAreMoreThanAThousand() {
        var h = history();
        for (int i = 0; i < 1000; i++) { int n = i; h.run("load", "r" + n, "s", "t", () -> ok("r" + n, 1)); }
        assertEquals(1000, count(), "1000건까지는 그대로");
        h.run("load", "r1000", "s", "t", () -> ok("r1000", 1));
        assertEquals(500, count());
        var items = h.recent(500);
        assertEquals(200, items.size(), "조회 상한 200건"); assertEquals("r1000", items.getFirst().requestId()); assertEquals("r801", items.getLast().requestId());
        assertEquals("r501", jdbc().sql("SELECT request_id FROM job_history ORDER BY seq LIMIT 1").query(String.class).single());
    }

    @Test void concurrentJobsAreAllRecordedAndUnstorableCharactersDoNotLoseAnEntry() throws Exception {
        var h = history(); var pool = Executors.newFixedThreadPool(8); var tasks = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 80; i++) { int n = i; tasks.add(pool.submit(() -> h.run("load", "c" + n, "s", "t", () -> ok("c" + n, 1)))); }
        for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(80, count());
        h.run("load", "nul", "a\u0000b", "t", () -> ok("nul", 1));
        assertEquals("ab", h.recent(1).getFirst().source());
    }

    @Test void disabledHistoryRecordsNothingAndAnUnavailableDatabaseNeverBreaksTheJob() throws Exception {
        var off = new JobHistory(false, db.dataSource());
        assertEquals(1, off.run("load", "r", "s", "t", () -> ok("r", 1)).insertedRows());
        assertEquals(0, count()); assertTrue(off.recent(5).isEmpty());
        var closed = db.another(); var broken = new JobHistory(true, closed); closed.close();
        assertEquals(2, broken.run("load", "r", "s", "t", () -> ok("r", 2)).insertedRows());
        var failure = assertThrows(LoadFailure.class, () -> broken.run("load", "r", "s", "t", () -> { throw new LoadFailure(422, "LOAD_REJECTED", "원래 실패"); }));
        assertEquals("원래 실패", failure.getMessage(), "이력 저장 실패가 원래 오류를 가리지 않는다");
        assertThrows(DataAccessResourceFailureException.class, () -> broken.recent(5), "읽지 못한 것을 빈 이력으로 보여주지 않는다");
    }
}
