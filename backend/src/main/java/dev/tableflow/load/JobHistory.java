package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.export.TableExporter.InvalidTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

/**
 * 적재·이관 작업 이력. 상태 DB의 {@code job_history} 표에 추가한다.
 * 비밀번호·호스트·사용자명·행 데이터는 저장하지 않고 이름·건수·결과만 남긴다.
 * 이력 저장 실패는 적재 결과에 영향을 주지 않는다.
 */
@Service
public class JobHistory {
    private static final Logger log = LoggerFactory.getLogger(JobHistory.class);
    private static final int KEEP = 500, TRIM_AT = 1000;
    public record Entry(String id, String kind, String requestId, String startedAt, long durationMs, String status,
                        String source, String target, int rows, String errorCode, String message) {}
    private final boolean enabled; private final JdbcClient jdbc;
    public JobHistory(@Value("${tableflow.history.enabled:true}") boolean enabled, DataSource dataSource) {
        this.enabled = enabled; this.jdbc = JdbcClient.create(dataSource);
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
        else if (e instanceof org.springframework.dao.DataAccessResourceFailureException || e instanceof org.springframework.dao.TransientDataAccessException) {
            code = "STATE_DB_UNAVAILABLE"; message = "상태 DB를 사용할 수 없습니다. PostgreSQL 실행 상태를 확인한 뒤 다시 시도해주세요.";
        }
        return new String[]{code, message == null ? "" : message.length() > 300 ? message.substring(0, 300) : message};
    }
    private static long elapsed(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    /** 이력 저장 실패는 작업 결과에 영향을 주지 않는다(상태 DB가 잠시 안 되어도 이관은 끝난다). */
    void record(Entry entry) {
        if (!enabled) return;
        try {
            jdbc.sql("""
                    INSERT INTO job_history (id, kind, request_id, started_at, duration_ms, status, source, target, row_count, error_code, message)
                    VALUES (:id, :kind, :requestId, :startedAt, :durationMs, :status, :source, :target, :rows, :errorCode, :message)""")
                .param("id", UUID.fromString(entry.id())).param("kind", entry.kind()).param("requestId", clean(entry.requestId()))
                .param("startedAt", OffsetDateTime.ofInstant(Instant.parse(entry.startedAt()), ZoneOffset.UTC))
                .param("durationMs", entry.durationMs()).param("status", entry.status()).param("source", clean(entry.source())).param("target", clean(entry.target()))
                .param("rows", entry.rows()).param("errorCode", clean(entry.errorCode())).param("message", clean(entry.message()))
                .update();
            // 1000건을 넘으면 최근 500건만 남긴다.
            jdbc.sql("DELETE FROM job_history WHERE seq < (SELECT seq FROM job_history ORDER BY seq DESC OFFSET :keep LIMIT 1) AND (SELECT count(*) FROM job_history) > :trimAt")
                .param("keep", KEEP - 1).param("trimAt", TRIM_AT).update();
        } catch (RuntimeException e) {
            log.warn("작업 이력을 저장하지 못했습니다: {}", e.getClass().getSimpleName());
        }
    }
    /** PostgreSQL 문자열에 저장할 수 없는 NUL 문자는 뺀다. */
    private static String clean(String value) { return value == null ? null : value.replace("\0", ""); }

    /** 최신순(최대 200건). 상태 DB를 읽지 못하면 빈 목록으로 숨기지 않고 오류를 낸다. */
    public List<Entry> recent(int limit) {
        if (!enabled) return List.of();
        return jdbc.sql("SELECT id, kind, request_id, started_at, duration_ms, status, source, target, row_count, error_code, message FROM job_history ORDER BY seq DESC LIMIT :limit")
            .param("limit", Math.max(1, Math.min(limit, 200)))
            .query((rs, i) -> new Entry(rs.getString("id"), rs.getString("kind"), rs.getString("request_id"), rs.getObject("started_at", OffsetDateTime.class).toInstant().toString(),
                rs.getLong("duration_ms"), rs.getString("status"), rs.getString("source"), rs.getString("target"), rs.getInt("row_count"), rs.getString("error_code"), rs.getString("message")))
            .list();
    }
}
