package dev.tableflow.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.export.TableExporter.InvalidTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * 적재·이전 작업 이력. 서버의 JSON Lines 파일에 추가만 하며 별도 DB가 필요 없다.
 * 비밀번호·호스트·사용자명·행 데이터는 저장하지 않고 이름·건수·결과만 남긴다.
 * 이력 저장 실패는 적재 결과에 영향을 주지 않는다.
 */
@Service
public class JobHistory {
    private static final Logger log = LoggerFactory.getLogger(JobHistory.class);
    private static final int KEEP = 500, TRIM_AT = 1000;
    public record Entry(String id, String kind, String requestId, String startedAt, long durationMs, String status,
                        String source, String target, int rows, String errorCode, String message) {}
    private final boolean enabled; private final Path file; private final ObjectMapper json;
    public JobHistory(@Value("${tableflow.history.enabled:true}") boolean enabled,
                      @Value("${tableflow.history.file:data/job-history.jsonl}") String file, ObjectMapper json) {
        this.enabled = enabled; this.file = Path.of(file); this.json = json;
    }
    public boolean enabled() { return enabled; }

    /** 작업을 실행하고 성공·재시도(replay)·실패를 이력에 남긴다. 예외는 그대로 다시 던진다. */
    public DatabaseLoader.Result run(String kind, String requestId, String source, String target, Supplier<DatabaseLoader.Result> action) {
        return track(kind, requestId, source, target, action, r -> r);
    }
    public <T> T track(String kind, String requestId, String source, String target, Supplier<T> action, java.util.function.Function<T, DatabaseLoader.Result> summary) {
        Instant started = Instant.now(); long t0 = System.nanoTime();
        try {
            T value = action.get(); var result = summary.apply(value);
            record(new Entry(UUID.randomUUID().toString(), kind, requestId, started.toString(), elapsed(t0), result.replayed() ? "replayed" : "success",
                source, target, result.insertedRows(), null, null));
            return value;
        } catch (RuntimeException e) {
            String[] d = describe(e);
            record(new Entry(UUID.randomUUID().toString(), kind, requestId, started.toString(), elapsed(t0), "failed", source, target, 0, d[0], d[1]));
            throw e;
        }
    }
    /** {코드, 메시지}. 알 수 없는 예외는 원문(주소·비밀번호가 섞일 수 있음)을 노출하지 않는다. */
    public static String[] describe(RuntimeException e) {
        String code = "INTERNAL_ERROR", message = "처리 중 오류가 발생했습니다.";
        if (e instanceof DatabaseLoader.LoadFailure f) { code = f.code; message = f.getMessage(); }
        else if (e instanceof JdbcExplorer.Failure f) { code = f.code; message = f.getMessage(); }
        else if (e instanceof InvalidTable) { code = "VALIDATION_FAILED"; message = e.getMessage(); }
        else if (e instanceof IllegalArgumentException) { code = "INVALID_INPUT"; message = e.getMessage(); }
        return new String[]{code, message == null ? "" : message.length() > 300 ? message.substring(0, 300) : message};
    }
    private static long elapsed(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    synchronized void record(Entry entry) {
        if (!enabled) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(entry) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            List<String> lines = Files.readAllLines(file);
            if (lines.size() > TRIM_AT) Files.write(file, lines.subList(lines.size() - KEEP, lines.size()));
        } catch (IOException | RuntimeException e) {
            log.warn("작업 이력을 저장하지 못했습니다: {}", e.getClass().getSimpleName()); // 적재 결과에는 영향 없음
        }
    }

    /** 최신순. 손상된 줄은 건너뛴다. */
    public synchronized List<Entry> recent(int limit) {
        if (!enabled || !Files.isRegularFile(file)) return List.of();
        try {
            List<String> lines = Files.readAllLines(file); List<Entry> result = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && result.size() < Math.max(1, Math.min(limit, 200)); i--) {
                try { result.add(json.readValue(lines.get(i), Entry.class)); } catch (IOException ignored) {}
            }
            return result;
        } catch (IOException e) { return List.of(); }
    }
}
