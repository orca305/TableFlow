package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 대용량 DB→DB 이관의 백그라운드 실행·진행률·취소. 작업 상태와 다시 실행할 수 있는 정의(비밀번호 제외)는 {@link JobStore}에 저장한다.
 * 같은 상태 DB를 쓰는 서버가 여럿이어도 한 작업은 실행권을 가진 쪽 하나만 실행한다. 실행하는 서버는 주기적으로 생존 신호를 갱신하고,
 * 신호가 끊긴 작업만 "중단됨"으로 바뀐다. 실행권을 잃었거나 신호를 오래 저장하지 못하면 다음 분할을 시작하지 않는다.
 * 재개하면 같은 요청 ID로 다시 제출해 분할 영수증 덕분에 완료된 분량을 건너뛰고 이어서 진행한다.
 */
@Service
public class MigrationJobs {
    private static final Logger log = LoggerFactory.getLogger(MigrationJobs.class);
    private static final int KEEP = 50;
    static final String RESTARTED = "서버가 다시 시작되어 이관이 중단되었습니다. 재개하면 완료된 분량은 건너뛰고 이어서 진행합니다.";
    static final String LEASE_LOST = "상태 DB에 진행 상황을 저장할 수 없거나 실행권이 넘어가 이관을 중단했습니다. 상태 DB를 확인한 뒤 재개하면 완료된 분량은 건너뛰고 이어서 진행합니다.";

    public record Status(String id, String state, long totalRows, long doneRows, int totalChunks, int doneChunks, int replayedChunks,
                         String errorCode, String message, String startedAt, String finishedAt, List<String> notes, List<BatchMigration.Item> items) {
        public Status(String id, String state, long totalRows, long doneRows, int totalChunks, int doneChunks, int replayedChunks,
                      String errorCode, String message, String startedAt, String finishedAt, List<String> notes) {
            this(id, state, totalRows, doneRows, totalChunks, doneChunks, replayedChunks, errorCode, message, startedAt, finishedAt, notes, null);
        }
        Status withItems(List<BatchMigration.Item> next) {
            return new Status(id, state, totalRows, doneRows, totalChunks, doneChunks, replayedChunks, errorCode, message, startedAt, finishedAt, notes, next);
        }
        Status failed(String code, String reason) {
            return new Status(id, "failed", totalRows, doneRows, totalChunks, doneChunks, replayedChunks, code, reason, startedAt, Instant.now().toString(), notes, items);
        }
    }

    /** 목록 한 줄: 상태 + 화면 표시용 이름 + 재개 가능 여부(실패·중지·중단) + 재개할 때 비밀번호가 필요한 쪽. */
    public record Info(Status status, String source, String target, boolean resumable, boolean sourceNeedsPassword, boolean targetNeedsPassword) {}

    /** 이 서버가 실행 중이거나(대기 포함), 결과를 아직 저장하지 못한 작업. */
    private final class Job implements BatchMigration.BatchProgress {
        final String id; final UUID owner = UUID.randomUUID(); // 실행마다 새 값
        volatile boolean cancel, lost, released; String state = "queued"; long totalRows, doneRows; int totalChunks, doneChunks, replayedChunks;
        String errorCode, message, startedAt = Instant.now().toString(), finishedAt; List<String> notes = List.of(); List<BatchMigration.Item> items;
        List<BatchMigration.Item> previousItems = List.of();
        JsonNode definition; String sourceLabel = "", targetLabel = ""; String createdTarget;
        volatile long savedAt = System.nanoTime();
        Job(String id) { this.id = id; }
        @Override public synchronized void total(int chunks, long rows) { state = "running"; totalChunks = chunks; totalRows = rows; persist(this); }
        @Override public synchronized void chunk(int done, long rows, int replayed) { doneChunks = done; doneRows = rows; replayedChunks = replayed; persist(this); }
        @Override public synchronized void items(List<BatchMigration.Item> next) { items = List.copyOf(next); persist(this); }
        @Override public List<BatchMigration.Item> previousItems() { return previousItems; }
        /** 다음 분할을 시작하기 전에 부른다. 다른 서버에서 요청한 중지와 실행권 상실도 여기서 알게 된다. */
        @Override public boolean cancelled() {
            if (cancel || lost) return true;
            if (!store.enabled()) return false;
            if (System.nanoTime() - savedAt > lease.toNanos()) { lost = true; return true; }
            try {
                var requested = store.cancelRequested(id, owner);
                if (requested.isEmpty()) lost = true;
                else if (requested.get()) cancel = true;
            } catch (RuntimeException e) { log.warn("작업의 중지 요청을 확인하지 못했습니다: {}", e.getClass().getSimpleName()); }
            return cancel || lost;
        }
        @Override public synchronized boolean createdTarget(String key) { return key.equals(createdTarget); }
        @Override public synchronized void targetCreated(String key) { createdTarget = key; persist(this); }
        @Override public synchronized void targetDropped(String key) { if (key.equals(createdTarget)) { createdTarget = null; persist(this); } }
        synchronized Status snapshot() { return new Status(id, state, totalRows, doneRows, totalChunks, doneChunks, replayedChunks, errorCode, message, startedAt, finishedAt, notes, items); }
        synchronized boolean active() { return state.equals("queued") || state.equals("running"); }
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "tableflow-migration"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService beat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread t = new Thread(runnable, "tableflow-job-heartbeat"); t.setDaemon(true); return t;
    });
    private final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Object submitting = new Object();
    private final JobStore store; private final Duration heartbeat, lease;

    @Autowired public MigrationJobs(JobStore store, @Value("${tableflow.jobs.heartbeat-seconds:10}") long heartbeatSeconds,
                                    @Value("${tableflow.jobs.lease-seconds:60}") long leaseSeconds) {
        this(store, Duration.ofSeconds(heartbeatSeconds), Duration.ofSeconds(leaseSeconds));
    }
    MigrationJobs(JobStore store, Duration heartbeat, Duration lease) {
        if (heartbeat.isZero() || heartbeat.isNegative() || lease.compareTo(heartbeat.multipliedBy(2)) < 0)
            throw new IllegalStateException("작업 생존 신호 간격은 0보다 커야 하고 만료 시간은 간격의 2배 이상이어야 합니다.");
        this.store = store; this.heartbeat = heartbeat; this.lease = lease;
    }

    /** 시작할 때 신호가 끊긴 작업을 정리하고 생존 신호를 시작한다. */
    @PostConstruct void restore() {
        if (!store.enabled()) return;
        expire();
        beat.scheduleWithFixedDelay(this::beatSafely, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
    }
    private List<Job> local() { synchronized (jobs) { return new ArrayList<>(jobs.values()); } }
    private void expire() {
        Set<UUID> mine = new HashSet<>(); for (Job job : local()) if (!job.lost) mine.add(job.owner);
        store.expire(mine, lease, old -> old.failed("SERVER_RESTARTED", RESTARTED));
    }
    private void beatSafely() {
        try {
            for (Job job : local()) synchronized (job) {
                if (persist(job) && !job.active()) jobs.remove(job.id, job); // 저장하지 못했던 결과를 다시 저장했다
            }
            expire();
        } catch (Throwable t) { log.warn("작업 생존 신호 처리 중 오류: {}", t.getClass().getSimpleName()); }
    }

    /**
     * 저장에 실패해도 이관은 계속한다(다음 저장이나 생존 신호에서 다시 시도). 실행권이 없으면 lost로 표시한다.
     * @return 상태 DB와 통신해 더 다시 시도할 것이 없으면 true
     */
    private boolean persist(Job job) {
        if (!store.enabled()) return false; // 저장하지 않는 설정에서는 메모리에만 남긴다
        synchronized (job) {
            if (job.released) return false; // 종료하며 "중단"으로 남긴 뒤에는 더 쓰지 않는다
            try {
                if (store.save(job.owner, job.snapshot(), job.createdTarget)) job.savedAt = System.nanoTime();
                else job.lost = true;
                return true;
            } catch (RuntimeException e) { log.warn("작업 상태를 저장하지 못했습니다: {}", e.getClass().getSimpleName()); return false; }
        }
    }

    /** 같은 ID로 실행 중인 작업이 있으면 새로 시작하지 않고 그 상태를 돌려준다(중복 제출 방지). */
    public Status submit(String id, Function<MigrationService.Progress, MigrationService.Outcome> task) { return submit(id, null, "", "", task); }
    /** definition: 재개에 쓸 요청 본문(비밀번호 제외). source/target: 목록에 보여줄 이름. */
    public Status submit(String id, JsonNode definition, String sourceLabel, String targetLabel, Function<MigrationService.Progress, MigrationService.Outcome> task) {
        synchronized (submitting) {
            Job existing = jobs.get(id);
            if (existing != null && existing.active() && !existing.lost) return existing.snapshot();
            Job job = new Job(id); job.definition = definition; job.sourceLabel = sourceLabel; job.targetLabel = targetLabel;
            if (store.enabled()) {
                var claim = store.claim(job.snapshot(), definition, sourceLabel, targetLabel, job.owner, lease);
                if (!claim.claimed()) return claim.status();
                job.items = claim.status().items(); job.previousItems = claim.previousItems(); job.createdTarget = claim.createdTarget();
            } else if (existing != null) {
                // 재개(끝나지 않은 같은 ID)면 만든 테이블 정보를 이어받는다. 성공한 작업의 재제출은 대상 구조를 바꾸지 않는다.
                synchronized (existing) {
                    job.items = existing.items; job.previousItems = existing.items == null ? List.of() : existing.items;
                    if (!existing.state.equals("succeeded")) job.createdTarget = existing.createdTarget;
                }
            }
            job.savedAt = System.nanoTime();
            jobs.remove(id); jobs.put(id, job);
            if (!store.enabled()) synchronized (jobs) { // 저장하지 않을 때는 메모리에서 오래된 완료 작업부터 정리
                while (jobs.size() > KEEP) { var it = jobs.values().iterator(); if (it.next().active()) break; it.remove(); }
            }
            Status queued = job.snapshot();
            executor.submit(() -> run(job, task));
            return queued;
        }
    }
    private void run(Job job, Function<MigrationService.Progress, MigrationService.Outcome> task) {
        // 차례를 기다리는 동안 실행권을 잃은 작업은 대상 DB를 건드리지 않고 끝낸다.
        if (job.cancelled() && job.lost) synchronized (job) { job.state = "failed"; job.errorCode = "STATE_LEASE_LOST"; job.message = LEASE_LOST; }
        else execute(job, task);
        synchronized (job) {
            job.finishedAt = Instant.now().toString();
            if (persist(job)) jobs.remove(job.id, job); // 저장했으면 이후 조회는 상태 DB에서 한다
        }
    }
    private void execute(Job job, Function<MigrationService.Progress, MigrationService.Outcome> task) {
        try {
            var outcome = task.apply(job);
            synchronized (job) { job.state = "succeeded"; job.doneRows = outcome.insertedRows(); job.notes = outcome.notes(); }
        } catch (RuntimeException e) {
            String[] d = JobHistory.describe(e);
            synchronized (job) {
                boolean stopped = "MIGRATION_CANCELLED".equals(d[0]);
                if (stopped && job.lost && !job.cancel) { job.state = "failed"; job.errorCode = "STATE_LEASE_LOST"; job.message = LEASE_LOST; }
                else { job.state = stopped ? "cancelled" : "failed"; job.errorCode = d[0]; job.message = d[1]; }
            }
        } catch (Throwable e) {
            synchronized (job) { job.state = "failed"; job.errorCode = "INTERNAL_ERROR"; job.message = "처리 중 오류가 발생했습니다."; }
        }
    }

    /** 최신순 작업 목록(재시작 뒤에도 유지). */
    public List<Info> list() {
        Map<String, Job> mine; synchronized (jobs) { mine = new LinkedHashMap<>(jobs); }
        List<Info> result = new ArrayList<>();
        if (!store.enabled()) {
            List<Job> all = new ArrayList<>(mine.values()); Collections.reverse(all);
            for (Job j : all) result.add(info(j.snapshot(), j.definition, j.sourceLabel, j.targetLabel));
            return result;
        }
        for (var row : store.list()) {
            Job job = mine.get(row.id());
            result.add(info(job != null && !job.lost ? job.snapshot() : row.status(), row.definition(), row.source(), row.target()));
        }
        return result;
    }
    private static Info info(Status st, JsonNode definition, String source, String target) {
        boolean def = definition != null && !definition.isNull();
        boolean done = st.state().equals("failed") || st.state().equals("cancelled");
        return new Info(st, source, target, def && done, def && needsPassword(definition, "source"), def && needsPassword(definition, "target"));
    }
    private static boolean needsPassword(JsonNode def, String side) { JsonNode id = def.path(side).path("savedId"); return id.isMissingNode() || id.isNull() || id.asText().isBlank(); }
    /** 재개용 정의(비밀번호 제외). 재개할 수 없으면 null. */
    public JsonNode definition(String id) {
        if (!store.enabled()) { Job job = jobs.get(id); return job == null ? null : job.definition; }
        return store.find(id).map(JobStore.Row::definition).orElse(null);
    }
    public Status get(String id) {
        Job job = jobs.get(id);
        if (job != null && !job.lost) return job.snapshot();
        if (store.enabled()) { var row = store.find(id); if (row.isPresent()) return row.get().status(); }
        throw new JdbcExplorer.Failure(404, "JOB_NOT_FOUND", "작업을 찾을 수 없습니다.");
    }
    /** 다음 청크를 시작하기 전에 멈춘다. 이미 커밋된 청크는 되돌리지 않는다. 다른 서버가 실행 중인 작업에는 중지 요청을 남긴다. */
    public Status cancel(String id) {
        Job job = jobs.get(id);
        if (job != null && !job.lost) job.cancel = true;
        else if (store.enabled()) store.requestCancel(id);
        return get(id);
    }
    /** 정상 종료할 때는 실행 중인 작업을 바로 "중단됨"으로 남겨, 다시 시작하자마자 재개할 수 있게 한다. */
    @PreDestroy void shutdown() {
        beat.shutdownNow();
        for (Job job : local()) synchronized (job) {
            if (job.active() && !job.lost) {
                job.state = "failed"; job.errorCode = "SERVER_RESTARTED"; job.message = RESTARTED; job.finishedAt = Instant.now().toString();
                persist(job);
                // 아래의 강제 중단으로 뒤늦게 끝나는 실행이 이 기록을 덮어쓰지 않도록 실행권을 내려놓는다.
                job.released = true; job.lost = true; jobs.remove(job.id, job);
            }
        }
        executor.shutdownNow();
    }
}
