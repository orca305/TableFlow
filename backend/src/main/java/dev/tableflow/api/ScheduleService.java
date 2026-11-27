package dev.tableflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.ConnectionStore;
import dev.tableflow.connection.JdbcExplorer.Credentials;
import dev.tableflow.connection.JdbcExplorer.Failure;
import dev.tableflow.load.*;
import dev.tableflow.load.ScheduleStore.Schedule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 저장된 이관 설정을 주기(N분마다 / 매일 정해진 시각)로 실행한다. 실행은 일반 이관 작업과 같은 경로를 쓰므로 작업 이력에 그대로 남는다.
 * 비밀번호를 저장한 연결만 쓸 수 있고, 매번 새 요청 ID로 처음부터 실행한다. 이전 실행이 아직 진행 중이면 그 회차는 건너뛴다.
 */
@Service
public class ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    public static final int MAX_SCHEDULES = 50, MIN_INTERVAL = 5, MAX_INTERVAL = 10080;
    private final ScheduleStore store; private final ConnectionController controller; private final ConnectionStore connections; private final MigrationJobs jobs;
    private final BatchMigration batch; private final MigrationService migration; private final ObjectMapper json; private final Validator validator;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "tableflow-schedule"); t.setDaemon(true); return t; });
    private Clock clock = Clock.systemDefaultZone();

    public ScheduleService(ScheduleStore store, ConnectionController controller, ConnectionStore connections, MigrationJobs jobs, BatchMigration batch,
                           MigrationService migration, ObjectMapper json, Validator validator) {
        this.store = store; this.controller = controller; this.connections = connections; this.jobs = jobs; this.batch = batch; this.migration = migration; this.json = json; this.validator = validator;
    }
    void useClock(Clock clock) { this.clock = clock; } // 테스트용

    @PostConstruct void start() {
        if (!store.enabled()) return;
        synchronized (this) { // 서버가 꺼져 있던 동안 진행 중이던 회차는 끝난 것으로 보지 않고 다음 점검에서 상태를 확인한다.
            List<Schedule> all = store.all(); boolean changed = false;
            for (int i = 0; i < all.size(); i++) {
                Schedule s = all.get(i);
                if ("running".equals(s.lastState()) && !isActive(s.lastJobId())) { all.set(i, s.with(s.enabled(), s.lastRunAt(), "failed", "서버가 다시 시작되어 이 회차의 결과를 확인하지 못했습니다.", s.lastJobId(), s.nextRunAt())); changed = true; }
            }
            if (changed) store.saveAll(all);
        }
        executor.scheduleWithFixedDelay(this::tickSafely, 10, 15, TimeUnit.SECONDS);
    }
    @PreDestroy void stop() { executor.shutdownNow(); }

    public boolean enabled() { return store.enabled(); }
    public synchronized List<Schedule> list() { return store.all(); }

    public record CreateRequest(String name, String kind, JsonNode request, String mode, Integer intervalMinutes, String dailyAt, String refresh, boolean confirmReplace) {}

    public synchronized Schedule create(CreateRequest c) {
        if (!store.enabled()) throw new Failure(503, "SCHEDULES_DISABLED", "스케줄 기능이 꺼져 있습니다.");
        String name = c.name() == null ? "" : c.name().strip();
        if (name.isEmpty() || name.length() > 60) throw new IllegalArgumentException("스케줄 이름은 1~60자로 입력해주세요.");
        boolean single = "single".equals(c.kind());
        if (!single && !"batch".equals(c.kind())) throw new IllegalArgumentException("스케줄 종류가 올바르지 않습니다.");
        String mode = c.mode();
        if ("interval".equals(mode)) {
            if (c.intervalMinutes() == null || c.intervalMinutes() < MIN_INTERVAL || c.intervalMinutes() > MAX_INTERVAL)
                throw new IllegalArgumentException("반복 간격은 " + MIN_INTERVAL + "분 이상 " + MAX_INTERVAL + "분(7일) 이하로 입력해주세요.");
        } else if ("daily".equals(mode)) {
            try { LocalTime.parse(c.dailyAt()); } catch (RuntimeException e) { throw new IllegalArgumentException("매일 실행 시각은 HH:mm 형식으로 입력해주세요."); }
        } else throw new IllegalArgumentException("반복 방식이 올바르지 않습니다.");
        String refresh = c.refresh() == null ? "append" : c.refresh();
        if (!refresh.equals("append") && !refresh.equals("replace")) throw new IllegalArgumentException("대상 처리 방식이 올바르지 않습니다.");
        if (refresh.equals("replace") && !c.confirmReplace()) throw new IllegalArgumentException("실행할 때마다 대상 테이블의 기존 행을 모두 지우고 다시 적재하는 데 동의해주세요.");
        if (store.all().size() >= MAX_SCHEDULES) throw new IllegalArgumentException("스케줄은 최대 " + MAX_SCHEDULES + "개까지 등록할 수 있습니다.");
        if (c.request() == null || c.request().isNull()) throw new IllegalArgumentException("이관 설정이 없습니다.");
        JsonNode stored; String source, target;
        try {
            if (single) {
                var r = json.treeToValue(c.request(), ConnectionController.MigrateRequest.class); validate(r);
                requireSaved(r.source()); requireSaved(r.target());
                stored = json.valueToTree(r.forStorage(connections));
                source = r.source().dbms() + " · " + r.source().database() + " · " + r.sourceSchema() + "." + r.sourceTable();
                target = r.target().dbms() + " · " + r.target().database() + " · " + r.targetSchema() + "." + r.targetTable();
            } else {
                var r = json.treeToValue(c.request(), ConnectionController.BatchRequest.class); validate(r);
                requireSaved(r.source()); requireSaved(r.target());
                stored = json.valueToTree(r.forStorage(connections));
                source = r.source().dbms() + " · " + r.source().database() + " · " + r.sourceSchema() + " (" + r.tables().size() + "개 테이블)";
                target = r.target().dbms() + " · " + r.target().database() + " · " + r.targetSchema();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("이관 설정을 읽지 못했습니다."); }
        Instant now = clock.instant();
        Schedule s = new Schedule(UUID.randomUUID().toString(), name, c.kind(), stored, mode, "interval".equals(mode) ? c.intervalMinutes() : null, "daily".equals(mode) ? c.dailyAt() : null,
            refresh, true, now.toString(), null, null, null, null, null, source, target);
        s = s.with(true, null, null, null, null, next(s, now).toString());
        List<Schedule> all = store.all(); all.add(s); store.saveAll(all);
        return s;
    }
    private void validate(Object request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new IllegalArgumentException("이관 설정이 올바르지 않습니다: " + violations.iterator().next().getMessage());
    }
    private void requireSaved(Credentials c) {
        boolean ok = c.savedId() != null && connections.list().stream().anyMatch(x -> x.id().equals(c.savedId()) && x.passwordSaved());
        if (!ok) throw new IllegalArgumentException("스케줄은 비밀번호를 저장한 연결만 사용할 수 있습니다. 연결을 저장할 때 '비밀번호 저장'을 선택하세요.");
    }

    public synchronized Schedule setEnabled(String id, boolean enabled) {
        List<Schedule> all = store.all(); int i = indexOf(all, id); Schedule s = all.get(i);
        Schedule next = s.with(enabled, s.lastRunAt(), s.lastState(), s.lastMessage(), s.lastJobId(), enabled ? next(s, clock.instant()).toString() : s.nextRunAt());
        all.set(i, next); store.saveAll(all); return next;
    }
    public synchronized boolean delete(String id) {
        List<Schedule> all = store.all(); boolean removed = all.removeIf(s -> s.id().equals(id)); if (removed) store.saveAll(all); return removed;
    }
    private static int indexOf(List<Schedule> all, String id) {
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(id)) return i;
        throw new Failure(404, "SCHEDULE_NOT_FOUND", "스케줄을 찾을 수 없습니다.");
    }

    /** 지금 한 번 실행한다(예정된 다음 실행 시각은 그대로 둔다). */
    public Schedule runNow(String id) {
        Schedule s; synchronized (this) { s = store.all().get(indexOf(store.all(), id)); }
        return execute(s, false);
    }

    static Instant next(Schedule s, Instant from) {
        if ("daily".equals(s.mode())) {
            ZoneId zone = ZoneId.systemDefault(); LocalTime at = LocalTime.parse(s.dailyAt());
            ZonedDateTime candidate = from.atZone(zone).with(at);
            if (!candidate.toInstant().isAfter(from)) candidate = candidate.plusDays(1);
            return candidate.toInstant();
        }
        return from.plus(Duration.ofMinutes(s.intervalMinutes()));
    }
    /** 작업 기록이 없을 때만 비어 있다. 상태 DB를 읽지 못한 것은 "없음"이 아니므로 예외를 그대로 올려, 중복 실행이나 잘못된 결과 기록을 막는다. */
    private Optional<MigrationJobs.Status> job(String jobId) {
        if (jobId == null) return Optional.empty();
        try { return Optional.of(jobs.get(jobId)); }
        catch (Failure e) { if ("JOB_NOT_FOUND".equals(e.code)) return Optional.empty(); throw e; }
    }
    private static boolean active(MigrationJobs.Status st) { return st.state().equals("queued") || st.state().equals("running"); }
    private boolean isActive(String jobId) { return job(jobId).map(ScheduleService::active).orElse(false); }

    private void tickSafely() { try { tick(); } catch (Throwable t) { log.warn("스케줄 점검 중 오류: {}", t.getClass().getSimpleName()); } }
    /** 진행 중이던 회차의 결과를 반영하고, 시각이 된 스케줄을 실행한다. */
    synchronized void tick() { // 점검은 한 번에 하나만(주기 점검과 직접 호출이 겹쳐 같은 스케줄을 두 번 실행하지 않도록)
        Instant now = clock.instant();
        List<Schedule> due = new ArrayList<>();
        synchronized (this) {
            List<Schedule> all = store.all(); boolean changed = false;
            for (int i = 0; i < all.size(); i++) {
                Schedule s = all.get(i);
                if ("running".equals(s.lastState()) && s.lastJobId() != null) {
                    var found = job(s.lastJobId());
                    if (found.isEmpty() || !active(found.get())) {
                        String state = found.map(MigrationJobs.Status::state).orElse("failed");
                        String message = found.map(st -> st.state().equals("succeeded") ? summary(st) : st.message()).orElse("작업 기록을 찾을 수 없습니다.");
                        s = s.with(s.enabled(), s.lastRunAt(), state, message, s.lastJobId(), s.nextRunAt()); all.set(i, s); changed = true;
                    }
                }
                if (s.enabled() && s.nextRunAt() != null && !Instant.parse(s.nextRunAt()).isAfter(now)) due.add(s);
            }
            if (changed) store.saveAll(all);
        }
        for (Schedule s : due) execute(s, true);
    }
    private static String summary(MigrationJobs.Status st) { return st.doneRows() + "행 이관"; }

    private Schedule execute(Schedule s, boolean scheduled) {
        Instant now = clock.instant();
        if (isActive(s.lastJobId())) return record(s, scheduled, now, s.lastRunAt(), s.lastState(), "이전 실행이 아직 진행 중이라 이번 회차는 건너뛰었습니다.", s.lastJobId());
        String jobId = UUID.randomUUID().toString();
        try {
            if ("single".equals(s.kind())) {
                var r = json.treeToValue(s.request(), ConnectionController.MigrateRequest.class).forRun(jobId);
                if ("replace".equals(s.refresh())) migration.purgeTables(r.target(), r.targetSchema(), List.of(r.targetTable()));
                controller.submitJob(r);
            } else {
                var r = json.treeToValue(s.request(), ConnectionController.BatchRequest.class).forRun(jobId);
                if ("replace".equals(s.refresh())) batch.purgeTargets(r.source(), r.sourceSchema(), r.target(), r.targetSchema(), r.tables());
                controller.submitBatch(r);
            }
            return record(s, scheduled, now, now.toString(), "running", "실행 중", jobId);
        } catch (Exception e) {
            String message = e instanceof RuntimeException re ? JobHistory.describe(re)[1] : "실행하지 못했습니다.";
            return record(s, scheduled, now, now.toString(), "failed", message, jobId);
        }
    }
    private synchronized Schedule record(Schedule s, boolean scheduled, Instant now, String lastRunAt, String state, String message, String jobId) {
        List<Schedule> all = store.all();
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(s.id())) {
            Schedule cur = all.get(i);
            Schedule next = cur.with(cur.enabled(), lastRunAt, state, message, jobId, scheduled ? next(cur, now).toString() : cur.nextRunAt());
            all.set(i, next); store.saveAll(all); return next;
        }
        return s; // 실행 중에 삭제된 스케줄
    }
}
