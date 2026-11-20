package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 대용량 DB→DB 이전의 백그라운드 실행·진행률·취소. 작업은 서버 메모리에만 있고(DB 자격증명은 실행 동안만 클로저에 존재하며 저장·응답하지 않음)
 * 서버가 재시작되면 사라진다. 재시작·실패 후에는 같은 요청 ID로 다시 제출하면 청크 영수증 덕분에 완료된 청크를 건너뛰고 이어서 진행한다.
 */
@Service
public class MigrationJobs {
    private static final int KEEP = 50;
    public record Status(String id, String state, long totalRows, long doneRows, int totalChunks, int doneChunks, int replayedChunks,
                         String errorCode, String message, String startedAt, String finishedAt, List<String> notes) {}

    private static final class Job implements MigrationService.Progress {
        final String id; volatile boolean cancel; String state = "queued"; long totalRows, doneRows; int totalChunks, doneChunks, replayedChunks;
        String errorCode, message, startedAt = Instant.now().toString(), finishedAt; List<String> notes = List.of();
        Job(String id) { this.id = id; }
        @Override public synchronized void total(int chunks, long rows) { state = "running"; totalChunks = chunks; totalRows = rows; }
        @Override public synchronized void chunk(int done, long rows, int replayed) { doneChunks = done; doneRows = rows; replayedChunks = replayed; }
        @Override public boolean cancelled() { return cancel; }
        synchronized Status snapshot() { return new Status(id, state, totalRows, doneRows, totalChunks, doneChunks, replayedChunks, errorCode, message, startedAt, finishedAt, notes); }
        synchronized boolean active() { return state.equals("queued") || state.equals("running"); }
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "tableflow-migration"); t.setDaemon(true); return t;
    });
    private final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 같은 ID로 실행 중인 작업이 있으면 새로 시작하지 않고 그 상태를 돌려준다(중복 제출 방지). */
    public Status submit(String id, Function<MigrationService.Progress, MigrationService.Outcome> task) {
        synchronized (jobs) {
            Job existing = jobs.get(id);
            if (existing != null && existing.active()) return existing.snapshot();
            Job job = new Job(id); jobs.remove(id); jobs.put(id, job);
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
            synchronized (job) { job.state = "succeeded"; job.doneRows = outcome.insertedRows(); job.notes = outcome.notes(); job.finishedAt = Instant.now().toString(); }
        } catch (RuntimeException e) {
            String[] d = JobHistory.describe(e);
            synchronized (job) { job.state = "MIGRATION_CANCELLED".equals(d[0]) ? "cancelled" : "failed"; job.errorCode = d[0]; job.message = d[1]; job.finishedAt = Instant.now().toString(); }
        } catch (Throwable e) {
            synchronized (job) { job.state = "failed"; job.errorCode = "INTERNAL_ERROR"; job.message = "처리 중 오류가 발생했습니다."; job.finishedAt = Instant.now().toString(); }
        }
    }
    public Status get(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new JdbcExplorer.Failure(404, "JOB_NOT_FOUND", "작업을 찾을 수 없습니다. 서버가 재시작됐다면 같은 요청 ID로 다시 실행하면 이어서 진행합니다.");
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
