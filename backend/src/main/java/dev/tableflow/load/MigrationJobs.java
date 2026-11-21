package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 대용량 DB→DB 이관의 백그라운드 실행·진행률·취소. 작업 상태와 다시 실행할 수 있는 정의(비밀번호 제외)는 {@link JobStore}에 저장되어
 * 서버를 다시 시작해도 목록이 남는다(그때 실행 중이던 작업은 "중단됨"으로 표시). 재개하면 같은 요청 ID로 다시 제출해 분할 영수증 덕분에 완료된 분량을 건너뛰고 이어서 진행한다.
 */
@Service
public class MigrationJobs {
    private static final int KEEP = 50;
    public record Status(String id, String state, long totalRows, long doneRows, int totalChunks, int doneChunks, int replayedChunks,
                         String errorCode, String message, String startedAt, String finishedAt, List<String> notes) {}

    /** 목록 한 줄: 상태 + 화면 표시용 이름 + 재개 가능 여부(실패·중지·중단) + 재개할 때 비밀번호가 필요한 쪽. */
    public record Info(Status status, String source, String target, boolean resumable, boolean sourceNeedsPassword, boolean targetNeedsPassword) {}

    private final class Job implements MigrationService.Progress {
        final String id; volatile boolean cancel; String state = "queued"; long totalRows, doneRows; int totalChunks, doneChunks, replayedChunks;
        String errorCode, message, startedAt = Instant.now().toString(), finishedAt; List<String> notes = List.of();
        JsonNode definition; String sourceLabel = "", targetLabel = "";
        Job(String id) { this.id = id; }
        @Override public synchronized void total(int chunks, long rows) { state = "running"; totalChunks = chunks; totalRows = rows; persist(this); }
        @Override public synchronized void chunk(int done, long rows, int replayed) { doneChunks = done; doneRows = rows; replayedChunks = replayed; persist(this); }
        @Override public boolean cancelled() { return cancel; }
        synchronized Status snapshot() { return new Status(id, state, totalRows, doneRows, totalChunks, doneChunks, replayedChunks, errorCode, message, startedAt, finishedAt, notes); }
        synchronized boolean active() { return state.equals("queued") || state.equals("running"); }
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "tableflow-migration"); t.setDaemon(true); return t;
    });
    private final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final JobStore store;
    public MigrationJobs(JobStore store) { this.store = store; }

    private void persist(Job job) { store.put(new JobStore.Stored(job.id, job.snapshot(), job.definition, job.sourceLabel, job.targetLabel)); }

    /** 서버를 다시 시작하면 저장된 작업 목록을 복원한다. 그때 실행 중이던 작업은 중단된 것으로 표시한다(재개 가능). */
    @PostConstruct void restore() {
        for (var st : store.all()) {
            var s = st.status(); Job job = new Job(st.id());
            job.state = s.state(); job.totalRows = s.totalRows(); job.doneRows = s.doneRows(); job.totalChunks = s.totalChunks(); job.doneChunks = s.doneChunks();
            job.replayedChunks = s.replayedChunks(); job.errorCode = s.errorCode(); job.message = s.message(); job.startedAt = s.startedAt(); job.finishedAt = s.finishedAt();
            job.notes = s.notes() == null ? List.of() : s.notes(); job.definition = st.definition(); job.sourceLabel = st.source() == null ? "" : st.source(); job.targetLabel = st.target() == null ? "" : st.target();
            if (job.active()) {
                job.state = "failed"; job.errorCode = "SERVER_RESTARTED"; job.finishedAt = Instant.now().toString();
                job.message = "서버가 다시 시작되어 이관이 중단되었습니다. 재개하면 완료된 분량은 건너뛰고 이어서 진행합니다.";
                persist(job);
            }
            jobs.put(job.id, job);
        }
    }

    /** 같은 ID로 실행 중인 작업이 있으면 새로 시작하지 않고 그 상태를 돌려준다(중복 제출 방지). */
    public Status submit(String id, Function<MigrationService.Progress, MigrationService.Outcome> task) { return submit(id, null, "", "", task); }
    /** definition: 재개에 쓸 요청 본문(비밀번호 제외). source/target: 목록에 보여줄 이름. */
    public Status submit(String id, JsonNode definition, String sourceLabel, String targetLabel, Function<MigrationService.Progress, MigrationService.Outcome> task) {
        synchronized (jobs) {
            Job existing = jobs.get(id);
            if (existing != null && existing.active()) return existing.snapshot();
            Job job = new Job(id); job.definition = definition; job.sourceLabel = sourceLabel; job.targetLabel = targetLabel; jobs.remove(id); jobs.put(id, job); persist(job);
            while (jobs.size() > KEEP) { // 오래된 완료 작업부터 정리
                var it = jobs.values().iterator();
                Job oldest = it.next();
                if (oldest.active()) break; it.remove();
            }
            executor.submit(() -> run(job, task));
            return job.snapshot();
        }
    }
    private void run(Job job, Function<MigrationService.Progress, MigrationService.Outcome> task) {
        try {
            var outcome = task.apply(job);
            synchronized (job) { job.state = "succeeded"; job.doneRows = outcome.insertedRows(); job.notes = outcome.notes(); job.finishedAt = Instant.now().toString(); persist(job); }
        } catch (RuntimeException e) {
            String[] d = JobHistory.describe(e);
            synchronized (job) { job.state = "MIGRATION_CANCELLED".equals(d[0]) ? "cancelled" : "failed"; job.errorCode = d[0]; job.message = d[1]; job.finishedAt = Instant.now().toString(); persist(job); }
        } catch (Throwable e) {
            synchronized (job) { job.state = "failed"; job.errorCode = "INTERNAL_ERROR"; job.message = "처리 중 오류가 발생했습니다."; job.finishedAt = Instant.now().toString(); persist(job); }
        }
    }
    /** 최신순 작업 목록(재시작 뒤에도 유지). */
    public List<Info> list() {
        List<Job> all; synchronized (jobs) { all = new ArrayList<>(jobs.values()); }
        Collections.reverse(all); List<Info> result = new ArrayList<>();
        for (Job j : all) {
            Status st = j.snapshot(); boolean def = j.definition != null && !j.definition.isNull();
            boolean done = st.state().equals("failed") || st.state().equals("cancelled");
            result.add(new Info(st, j.sourceLabel, j.targetLabel, def && done, def && needsPassword(j.definition, "source"), def && needsPassword(j.definition, "target")));
        }
        return result;
    }
    private static boolean needsPassword(JsonNode def, String side) { JsonNode id = def.path(side).path("savedId"); return id.isMissingNode() || id.isNull() || id.asText().isBlank(); }
    /** 재개용 정의(비밀번호 제외). 재개할 수 없으면 null. */
    public JsonNode definition(String id) { Job job = jobs.get(id); return job == null ? null : job.definition; }
    public Status get(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new JdbcExplorer.Failure(404, "JOB_NOT_FOUND", "작업을 찾을 수 없습니다.");
        return job.snapshot();
    }
    /** 다음 청크를 시작하기 전에 멈춘다. 이미 커밋된 청크는 되돌리지 않는다. */
    public Status cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new JdbcExplorer.Failure(404, "JOB_NOT_FOUND", "작업을 찾을 수 없습니다.");
        job.cancel = true; return job.snapshot();
    }
    @PreDestroy void shutdown() { executor.shutdownNow(); }
}
