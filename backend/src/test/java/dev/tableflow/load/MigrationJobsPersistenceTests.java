package dev.tableflow.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tableflow.state.StateDbTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 이관 작업을 상태 DB에 두었을 때의 동작: 여러 서버 중 하나만 실행, 신호가 끊긴 작업만 "중단됨", 실행권을 잃은 실행은 덮어쓰지 못함,
 * 상태 DB 장애 중에도 이관은 계속, 정의에 비밀번호 없음. 서버 여러 대는 같은 스키마를 보는 별도 서비스 객체로 흉내 낸다.
 */
class MigrationJobsPersistenceTests {
    static final Duration BEAT = Duration.ofMillis(200), LEASE = Duration.ofSeconds(2);
    final ObjectMapper json = new ObjectMapper();
    final List<MigrationJobs> servers = new ArrayList<>();
    final List<AutoCloseable> pools = new ArrayList<>();
    final List<CountDownLatch> gates = new ArrayList<>();
    StateDbTestSupport db; JdbcClient jdbc;

    @BeforeEach void open() { db = new StateDbTestSupport(); jdbc = JdbcClient.create(db.dataSource()); }
    @AfterEach void close() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        servers.forEach(MigrationJobs::shutdown);
        for (var pool : pools) pool.close();
        db.close();
    }

    JobStore store(DataSource dataSource) { return new JobStore(true, dataSource, json); }
    DataSource separatePool() { var pool = db.another(); pools.add(pool); return pool; }
    /** 시작까지 마친 서버(생존 신호를 보낸다). */
    MigrationJobs server() { return server(separatePool(), LEASE); }
    MigrationJobs server(DataSource dataSource, Duration lease) { var s = new MigrationJobs(store(dataSource), BEAT, lease); s.restore(); servers.add(s); return s; }
    /** 생존 신호 스레드 없이 만든 서버(멈춰 버린 서버를 흉내 낸다). */
    MigrationJobs silentServer() { var s = new MigrationJobs(store(separatePool()), BEAT, LEASE); servers.add(s); return s; }

    /** 상태 DB 접속을 끊었다 붙일 수 있는 연결. */
    static final class Switch extends DelegatingDataSource {
        volatile boolean down;
        Switch(DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException {
            if (down) throw new SQLTransientConnectionException("down", "08001");
            return super.getConnection();
        }
    }

    ObjectNode definition(String sourceSavedId) {
        ObjectNode def = json.createObjectNode();
        var src = def.putObject("source"); src.put("host", "localhost"); src.put("password", "");
        if (sourceSavedId != null) src.put("savedId", sourceSavedId); else src.putNull("savedId");
        var tgt = def.putObject("target"); tgt.put("host", "localhost"); tgt.put("password", ""); tgt.put("savedId", "123e4567-e89b-12d3-a456-426614174000");
        return def;
    }
    static String id() { return UUID.randomUUID().toString(); }
    static MigrationService.Outcome done(String id, long rows) { return new MigrationService.Outcome(id, "t", rows, false, List.of(), 1, 0); }

    /** 열어 줄 때까지 실행 중으로 머무는 작업. 열린 뒤 중지 신호가 있으면 실제 실행기처럼 취소 오류를 낸다. */
    final class Gate implements Function<MigrationService.Progress, MigrationService.Outcome> {
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger(); final long rows; volatile MigrationService.Progress progress; volatile Boolean inherited;
        Gate(long rows) { this.rows = rows; gates.add(release); }
        @Override public MigrationService.Outcome apply(MigrationService.Progress p) {
            runs.incrementAndGet(); progress = p; inherited = p.createdTarget("pg|public|t");
            p.total(3, 30); p.chunk(1, 10, 0); p.targetCreated("pg|public|t"); started.countDown();
            try { if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("시험 시간 초과"); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            if (p.cancelled()) throw new DatabaseLoader.LoadFailure(409, "MIGRATION_CANCELLED", "취소했습니다.");
            return done("x", rows);
        }
        void await() throws InterruptedException { assertTrue(started.await(10, TimeUnit.SECONDS), "작업이 시작되지 않았습니다"); }
    }
    /** 중지 신호가 올 때까지 분할 사이를 도는 작업. */
    static Function<MigrationService.Progress, MigrationService.Outcome> untilStopped(CountDownLatch started) {
        return p -> {
            p.total(100, 1000); started.countDown();
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (p.cancelled()) throw new DatabaseLoader.LoadFailure(409, "MIGRATION_CANCELLED", "취소했습니다.");
                pause(50);
            }
            throw new IllegalStateException("중지 신호가 오지 않았습니다");
        };
    }
    static void pause(long millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { throw new IllegalStateException(e); } }
    static void until(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) { if (System.currentTimeMillis() > deadline) fail("기다리다 시간 초과: " + what); pause(20); }
    }
    MigrationJobs.Status finished(MigrationJobs jobs, String id) {
        until("작업 종료", () -> !Set.of("queued", "running").contains(jobs.get(id).state()));
        return jobs.get(id);
    }
    String storedState(String id) { return jdbc.sql("SELECT state FROM migration_job WHERE id = :id").param("id", id).query(String.class).single(); }
    void silence(String id) { jdbc.sql("UPDATE migration_job SET heartbeat_at = now() - interval '1 hour' WHERE id = :id").param("id", id).update(); }

    @Test void jobOfDeadServerBecomesResumableInterrupted() {
        String id = id(); var store = store(db.dataSource()); UUID dead = UUID.randomUUID();
        var queued = new MigrationJobs.Status(id, "queued", 0, 0, 0, 0, 0, null, null, "2026-11-21T00:00:00Z", null, List.of());
        assertTrue(store.claim(queued, definition(null), "Oracle · HR.EMP", "PostgreSQL · public.emp", dead, LEASE).claimed());
        assertTrue(store.save(dead, new MigrationJobs.Status(id, "running", 12500, 5000, 3, 1, 0, null, null, "2026-11-21T00:00:00Z", null, List.of()), "pg|public|t"));
        silence(id);
        var restarted = server();
        var info = restarted.list().get(0);
        assertEquals("failed", info.status().state()); assertEquals("SERVER_RESTARTED", info.status().errorCode());
        assertTrue(info.resumable()); assertEquals("Oracle · HR.EMP", info.source());
        assertTrue(info.sourceNeedsPassword());   // 원본은 저장된 비밀번호가 없어 재개할 때 입력해야 한다
        assertFalse(info.targetNeedsPassword());  // 대상은 저장된 연결(savedId)로 비밀번호를 쓴다
        assertEquals(5000, info.status().doneRows());
        assertNotNull(restarted.definition(id));
        assertFalse(store.save(dead, queued, null), "중단 처리 뒤에는 이전 실행이 덮어쓰지 못한다");
        assertEquals("failed", server().get(id).state(), "다시 열어도 바뀐 상태가 유지된다");
    }

    @Test void liveJobIsLeftAloneAndNotStartedTwice() throws Exception {
        String id = id(); var first = server(); var gate = new Gate(30);
        first.submit(id, definition(null), "A", "B", gate); gate.await();
        var second = server(); var duplicate = new Gate(1);
        pause(LEASE.toMillis() + 800); // 생존 신호가 있으면 만료 시간이 지나도 중단되지 않는다
        assertEquals("running", second.get(id).state());
        assertEquals("running", second.submit(id, definition(null), "A", "B", duplicate).state());
        assertEquals(10, second.list().get(0).status().doneRows());
        assertFalse(second.list().get(0).resumable());
        gate.release.countDown();
        assertEquals("succeeded", finished(first, id).state());
        assertEquals("succeeded", second.get(id).state());
        assertEquals(0, duplicate.runs.get());
    }

    @Test void simultaneousSubmitsOnTwoServersRunOnce() throws Exception {
        var a = server(); var b = server(); var pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                String id = id(); var gate = new Gate(30); var go = new CountDownLatch(1);
                var futures = new ArrayList<Future<?>>();
                for (var s : List.of(a, b)) futures.add(pool.submit(() -> { go.await(); return s.submit(id, definition(null), "A", "B", gate); }));
                go.countDown(); for (var f : futures) f.get(10, TimeUnit.SECONDS);
                gate.await(); gate.release.countDown();
                assertEquals("succeeded", finished(a, id).state());
                assertEquals(1, gate.runs.get(), "같은 요청 ID는 한 서버에서 한 번만 실행한다");
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void takenOverRunStopsAndCannotOverwriteTheNewRun() throws Exception {
        String id = id(); var stalled = silentServer(); var old = new Gate(11);
        stalled.submit(id, definition(null), "A", "B", old); old.await();
        silence(id);
        var healthy = server(); var next = new Gate(99);
        healthy.submit(id, definition(null), "A", "B", next); next.await();
        assertTrue(next.inherited, "이어받은 실행은 앞 실행이 만든 대상 테이블을 안다");
        assertTrue(old.progress.cancelled(), "실행권을 잃으면 다음 분할을 시작하지 않는다");
        old.progress.chunk(2, 20, 0);
        assertEquals(10, healthy.get(id).doneRows(), "이전 실행의 진행률은 저장되지 않는다");
        old.release.countDown();
        pause(500);
        assertEquals("running", storedState(id));
        assertEquals("running", stalled.get(id).state(), "실행권을 잃은 서버도 상태 DB의 현재 실행을 보여준다");
        next.release.countDown();
        assertEquals(99, finished(healthy, id).doneRows());
        assertEquals("succeeded", stalled.get(id).state());
    }

    @Test void queuedJobThatLostOwnershipNeverRuns() throws Exception {
        var stalled = silentServer(); var busy1 = new Gate(1); var busy2 = new Gate(1); var waiting = new Gate(1); String id = id();
        stalled.submit(id(), busy1); stalled.submit(id(), busy2); busy1.await(); busy2.await(); // 동시 실행 2개를 모두 채운다
        stalled.submit(id, definition(null), "A", "B", waiting);
        silence(id);
        assertEquals("failed", server().get(id).state());
        busy1.release.countDown();
        until("대기 작업 정리", () -> stalled.list().stream().filter(i -> i.status().id().equals(id)).allMatch(i -> i.status().state().equals("failed")));
        pause(300);
        assertEquals(0, waiting.runs.get());
        assertEquals("SERVER_RESTARTED", stalled.get(id).errorCode());
    }

    @Test void cancelRequestedOnAnotherServerStopsTheRun() throws Exception {
        String id = id(); var runner = server(); var started = new CountDownLatch(1);
        runner.submit(id, definition(null), "A", "B", untilStopped(started)); assertTrue(started.await(10, TimeUnit.SECONDS));
        var other = server();
        other.cancel(id);
        var end = finished(runner, id);
        assertEquals("cancelled", end.state()); assertEquals("MIGRATION_CANCELLED", end.errorCode());
        assertEquals("cancelled", other.get(id).state());
    }

    @Test void stateDbOutageDoesNotStopTheRunAndResultIsSavedLater() throws Exception {
        String id = id(); var link = new Switch(separatePool()); var jobs = server(link, Duration.ofSeconds(60)); var gate = new Gate(30);
        jobs.submit(id, definition(null), "A", "B", gate); gate.await();
        link.down = true;
        assertDoesNotThrow(() -> gate.progress.chunk(2, 20, 0));
        assertFalse(gate.progress.cancelled(), "만료 시간 안의 장애로는 멈추지 않는다");
        assertEquals(20, jobs.get(id).doneRows(), "실행 중인 작업은 서버 메모리의 진행률을 보여준다");
        gate.release.countDown();
        until("메모리 결과", () -> jobs.get(id).state().equals("succeeded"));
        assertEquals("running", storedState(id), "저장하지 못한 동안 상태 DB에는 이전 값이 남는다");
        link.down = false;
        until("다시 저장", () -> storedState(id).equals("succeeded"));
        assertEquals(30, jobs.get(id).doneRows());
    }

    @Test void longOutageStopsBeforeTheNextChunk() throws Exception {
        String id = id(); var link = new Switch(separatePool()); var jobs = server(link, Duration.ofMillis(600)); var started = new CountDownLatch(1);
        jobs.submit(id, definition(null), "A", "B", untilStopped(started)); assertTrue(started.await(10, TimeUnit.SECONDS));
        link.down = true;
        pause(1500);
        link.down = false;
        until("실행권 상실 저장", () -> storedState(id).equals("failed"));
        var end = jobs.get(id);
        assertEquals("STATE_LEASE_LOST", end.errorCode()); assertEquals(MigrationJobs.LEASE_LOST, end.message());
        assertTrue(jobs.list().get(0).resumable());
    }

    @Test void submitFailsWithoutRunningWhenStateDbIsDown() {
        var link = new Switch(separatePool()); var jobs = server(link, LEASE); var gate = new Gate(1);
        link.down = true;
        assertThrows(RuntimeException.class, () -> jobs.submit(id(), definition(null), "A", "B", gate));
        link.down = false;
        pause(200);
        assertEquals(0, gate.runs.get()); assertTrue(jobs.list().isEmpty());
    }

    @Test void finishedJobsSurviveRestartWithoutPasswords() {
        var jobs = server(); String ok = id(), bad = id(), refused = id();
        jobs.submit(ok, definition("123e4567-e89b-12d3-a456-426614174001"), "A", "B", p -> { p.total(1, 10); p.chunk(1, 10, 0); return done(ok, 10); });
        jobs.submit(bad, definition(null), "C", "D", p -> { throw new IllegalArgumentException("테스트 실패"); });
        finished(jobs, ok); finished(jobs, bad);
        var withPassword = definition(null); ((ObjectNode) withPassword.get("source")).put("password", "secret-value");
        var gate = new Gate(1);
        assertThrows(IllegalStateException.class, () -> jobs.submit(refused, withPassword, "E", "F", gate));
        assertEquals(0, gate.runs.get());

        var byId = new HashMap<String, MigrationJobs.Info>(); server().list().forEach(i -> byId.put(i.status().id(), i));
        assertEquals(Set.of(ok, bad), byId.keySet());
        assertEquals("succeeded", byId.get(ok).status().state()); assertFalse(byId.get(ok).resumable());
        assertEquals("failed", byId.get(bad).status().state()); assertTrue(byId.get(bad).resumable());
        assertEquals("INVALID_INPUT", byId.get(bad).status().errorCode());
        assertEquals(definition(null), server().definition(bad));
        assertFalse(jdbc.sql("SELECT string_agg(m::text, ' ') FROM migration_job m").query(String.class).single().contains("secret-value"));
    }

    @Test void messagesWithNulCharactersAreStored() {
        var jobs = server(); String id = id(); String literal = "\\u0000";
        jobs.submit(id, definition(null), "A", "B", p -> { throw new IllegalArgumentException("앞" + (char) 0 + "뒤 " + literal); });
        finished(jobs, id);
        assertEquals("앞뒤 " + literal, server().get(id).message(), "NUL 문자만 빼고, 글자로 쓴 표기는 남긴다");
    }

    @Test void createdTargetSurvivesRestartAndIsInheritedOnlyByUnfinishedRetries() {
        String id = id(); var first = server();
        first.submit(id, definition(null), "A", "B", p -> { p.targetCreated("pg|public|t"); throw new IllegalArgumentException("인덱스 생성 실패"); });
        finished(first, id);
        var restarted = server(); var seen = new CopyOnWriteArrayList<Boolean>();
        Function<MigrationService.Progress, MigrationService.Outcome> retry = p -> {
            seen.add(p.createdTarget("pg|public|t")); seen.add(p.createdTarget("pg|public|other")); return done(id, 0);
        };
        restarted.submit(id, definition(null), "A", "B", retry); assertEquals("succeeded", finished(restarted, id).state());
        assertEquals(List.of(true, false), seen, "재시작 뒤에도 같은 대상 테이블만 이 작업이 만든 것으로 본다");
        seen.clear();
        restarted.submit(id, definition(null), "A", "B", retry); finished(restarted, id);
        assertEquals(List.of(false, false), seen, "성공한 작업을 다시 제출하면 대상 구조를 보완하지 않는다");
    }

    @Test void batchTableResultsAreCarriedToTheRetry() {
        String id = id(); var first = server();
        var item = new BatchMigration.Item("EMP", "emp", "failed", 10, 5, "중간에 실패", true);
        first.submit(id, definition(null), "A", "B", p -> { ((BatchMigration.BatchProgress) p).items(List.of(item)); throw new IllegalArgumentException("실패"); });
        assertEquals(List.of(item), finished(first, id).items());
        var restarted = server(); var seen = new CompletableFuture<List<BatchMigration.Item>>();
        var queued = restarted.submit(id, definition(null), "A", "B", p -> { seen.complete(((BatchMigration.BatchProgress) p).previousItems()); return done(id, 10); });
        assertEquals(List.of(item), queued.items());
        assertEquals(List.of(item), seen.orTimeout(10, TimeUnit.SECONDS).join());
        finished(restarted, id);
    }

    @Test void keepsTheLatestFiftyAndNeverDropsActiveJobs() throws Exception {
        var jobs = server(); var gate = new Gate(1); String active = id();
        jobs.submit(active, definition(null), "A", "B", gate); gate.await();
        var store = store(db.dataSource()); var ids = new ArrayList<String>();
        for (int i = 0; i < 55; i++) {
            String id = id(); ids.add(id); UUID owner = UUID.randomUUID();
            var status = new MigrationJobs.Status(id, "queued", 0, 0, 0, 0, 0, null, null, "2026-11-21T00:00:00Z", null, List.of());
            store.claim(status, definition(null), "A", "B", owner, LEASE);
            store.save(owner, status.failed("INVALID_INPUT", "x"), null);
        }
        var listed = jobs.list().stream().map(i -> i.status().id()).toList();
        assertEquals(51, listed.size());
        assertEquals(ids.get(54), listed.get(0));
        assertEquals(active, listed.get(50), "실행 중인 작업은 오래되어도 남는다");
        assertFalse(listed.contains(ids.get(4)));
        // 다시 제출한 작업은 목록 맨 앞으로 온다
        jobs.submit(ids.get(30), definition(null), "A", "B", p -> done("x", 0));
        assertEquals(ids.get(30), jobs.list().get(0).status().id());
        gate.release.countDown();
        finished(jobs, active);
    }

    @Test void gracefulShutdownMarksInterruptedAtOnce() throws Exception {
        String id = id(); var leaving = server(); var gate = new Gate(30);
        leaving.submit(id, definition(null), "A", "B", gate); gate.await();
        leaving.shutdown();
        pause(500); // 종료로 끊긴 실행이 뒤늦게 끝날 시간을 준다. 그 결과가 "중단" 기록을 덮어쓰면 안 된다.
        var next = server();
        var status = next.get(id);
        assertEquals("failed", status.state()); assertEquals("SERVER_RESTARTED", status.errorCode()); assertEquals(10, status.doneRows());
        assertTrue(next.list().get(0).resumable());
    }

    @Test void disabledStoreWritesNothing() {
        var jobs = new MigrationJobs(new JobStore(false, db.dataSource(), json), BEAT, LEASE); jobs.restore(); servers.add(jobs);
        String id = id();
        jobs.submit(id, definition(null), "A", "B", p -> done(id, 3));
        assertEquals(3, finished(jobs, id).doneRows());
        assertEquals(1, jobs.list().size()); assertNotNull(jobs.definition(id));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM migration_job").query(Integer.class).single());
    }

    @Test void unknownJobIsNotFound() {
        var failure = assertThrows(dev.tableflow.connection.JdbcExplorer.Failure.class, () -> server().get(id()));
        assertEquals("JOB_NOT_FOUND", failure.code);
    }

    @Test void leaseShorterThanTwoBeatsIsRejected() {
        var store = store(db.dataSource());
        assertThrows(IllegalStateException.class, () -> new MigrationJobs(store, Duration.ofSeconds(10), Duration.ofSeconds(19)));
        assertThrows(IllegalStateException.class, () -> new MigrationJobs(store, Duration.ZERO, Duration.ofSeconds(19)));
    }
}
