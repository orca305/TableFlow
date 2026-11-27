package dev.tableflow.load;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 이관 작업의 상태와 "다시 실행할 수 있는 정의"를 상태 DB의 {@code migration_job} 표에 보관한다.
 * 실행 중인 작업은 그 실행의 식별값(owner_id)을 가진 쪽만 갱신할 수 있고, 시간 비교는 서버마다 다른 시계 대신 DB 시각을 쓴다.
 * 정의에는 DB 비밀번호를 넣지 않는다. 재개할 때는 저장된 연결의 비밀번호(savedId)를 쓰거나 사용자가 다시 입력한다.
 */
@Service
@DependsOnDatabaseInitialization // 시작하면서 표를 조회하므로 Flyway 마이그레이션 뒤에 만든다
public class JobStore {
    private static final int KEEP = 50;
    private static final String ACTIVE = "('queued','running')";
    private static final String SELECT = "SELECT id, status::text AS status, definition::text AS definition, source_label, target_label FROM migration_job";
    /** JSON 문자열의 NUL 표기(역슬래시 + u0000)는 PostgreSQL jsonb에 저장할 수 없다. 앞의 역슬래시가 짝수 개일 때만 NUL 표기다. */
    private static final Pattern NUL = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u0000");

    /** definition: 비밀번호를 비운 요청 본문. source/target: 화면에 보여줄 이름(비밀번호·호스트 없음). */
    public record Row(String id, MigrationJobs.Status status, JsonNode definition, String source, String target) {}
    /** claimed=false면 다른 실행이 살아 있어 status는 그 작업의 현재 상태다. claimed=true면 이어받은 이전 실행 정보를 담는다. */
    public record Claim(boolean claimed, MigrationJobs.Status status, List<BatchMigration.Item> previousItems, String createdTarget) {}

    private final boolean enabled; private final JdbcClient jdbc; private final TransactionTemplate transaction; private final ObjectMapper json;
    public JobStore(@Value("${tableflow.jobs.enabled:true}") boolean enabled, DataSource dataSource, ObjectMapper json) {
        this.enabled = enabled; this.json = json;
        this.jdbc = JdbcClient.create(dataSource); this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
    public boolean enabled() { return enabled; }

    /**
     * 작업을 실행할 권리를 얻는다. 같은 ID가 대기·실행 중이고 생존 신호가 살아 있으면 얻지 못한다.
     * 끝났거나 생존 신호가 끊긴 작업은 이어받으며, 테이블별 결과와(성공한 작업이 아니면) 만든 대상 테이블 정보를 넘겨받는다.
     */
    public Claim claim(MigrationJobs.Status queued, JsonNode definition, String source, String target, UUID owner, Duration lease) {
        requireNoPassword(definition);
        return transaction.execute(tx -> {
            // 같은 ID의 동시 제출을 차례로 처리한다(행이 아직 없을 때도).
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "tableflow.job:" + queued.id()).query((rs, i) -> 0).list();
            var previous = jdbc.sql("SELECT state, status::text AS status, created_target, heartbeat_at < now() - make_interval(secs => :lease) AS stale FROM migration_job WHERE id = :id FOR UPDATE")
                .param("lease", seconds(lease)).param("id", queued.id())
                .query((rs, i) -> new Previous(rs.getString("state"), status(rs.getString("status")), rs.getString("created_target"), rs.getBoolean("stale"))).optional().orElse(null);
            List<BatchMigration.Item> items = null; String created = null;
            if (previous != null) {
                boolean active = previous.state().equals("queued") || previous.state().equals("running");
                if (active && !previous.stale()) return new Claim(false, previous.status(), List.of(), null);
                items = previous.status().items();
                if (!previous.state().equals("succeeded")) created = previous.createdTarget();
            }
            MigrationJobs.Status next = queued.withItems(items);
            jdbc.sql("""
                    INSERT INTO migration_job (id, state, status, definition, source_label, target_label, created_target, owner_id)
                    VALUES (:id, :state, CAST(:status AS jsonb), CAST(:definition AS jsonb), :source, :target, :created, :owner)
                    ON CONFLICT (id) DO UPDATE SET seq = nextval('migration_job_seq'), state = EXCLUDED.state, status = EXCLUDED.status, definition = EXCLUDED.definition,
                        source_label = EXCLUDED.source_label, target_label = EXCLUDED.target_label, created_target = EXCLUDED.created_target, owner_id = EXCLUDED.owner_id,
                        heartbeat_at = now(), cancel_requested = false, updated_at = now()""")
                .param("id", next.id()).param("state", next.state()).param("status", write(next))
                .param("definition", definition == null || definition.isNull() ? null : write(definition), Types.VARCHAR)
                .param("source", source == null ? "" : source).param("target", target == null ? "" : target)
                .param("created", created, Types.VARCHAR).param("owner", owner).update();
            // 최근 50건을 넘는 끝난 작업을 지운다(실행 중인 작업은 남긴다).
            jdbc.sql("DELETE FROM migration_job WHERE state NOT IN " + ACTIVE + " AND seq <= (SELECT seq FROM migration_job ORDER BY seq DESC OFFSET :keep LIMIT 1)")
                .param("keep", KEEP).update();
            return new Claim(true, next, items == null ? List.of() : items, created);
        });
    }
    private record Previous(String state, MigrationJobs.Status status, String createdTarget, boolean stale) {}

    /** 상태를 저장하고 생존 신호를 갱신한다. 이 실행이 더 이상 실행권을 갖고 있지 않으면 아무것도 바꾸지 않고 false. */
    public boolean save(UUID owner, MigrationJobs.Status status, String createdTarget) {
        return jdbc.sql("UPDATE migration_job SET state = :state, status = CAST(:status AS jsonb), created_target = :created, heartbeat_at = now(), updated_at = now() WHERE id = :id AND owner_id = :owner")
            .param("state", status.state()).param("status", write(status)).param("created", createdTarget, Types.VARCHAR).param("id", status.id()).param("owner", owner).update() == 1;
    }
    /** 이 실행이 실행권을 갖고 있으면 중지 요청 여부, 아니면 비어 있음. */
    public Optional<Boolean> cancelRequested(String id, UUID owner) {
        return jdbc.sql("SELECT cancel_requested FROM migration_job WHERE id = :id AND owner_id = :owner").param("id", id).param("owner", owner)
            .query((rs, i) -> rs.getBoolean(1)).optional();
    }
    public void requestCancel(String id) {
        jdbc.sql("UPDATE migration_job SET cancel_requested = true, updated_at = now() WHERE id = :id AND state IN " + ACTIVE).param("id", id).update();
    }
    public Optional<Row> find(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(this::row).optional();
    }
    /** 최신 제출 순. */
    public List<Row> list() { return jdbc.sql(SELECT + " ORDER BY seq DESC").query(this::row).list(); }

    /**
     * 생존 신호가 끊긴 대기·실행 작업을 바꾸고 실행권을 회수해, 이전 실행이 뒤늦게 덮어쓰지 못하게 한다. 바꾼 건수를 돌려준다.
     * mine: 지금 이 서버에서 살아 있는 실행(자기 작업은 스스로 갱신하므로 제외한다).
     */
    public int expire(Set<UUID> mine, Duration lease, UnaryOperator<MigrationJobs.Status> interrupted) {
        return transaction.execute(tx -> {
            var stale = jdbc.sql("SELECT status::text AS status, owner_id FROM migration_job WHERE state IN " + ACTIVE
                    + " AND heartbeat_at < now() - make_interval(secs => :lease) FOR UPDATE SKIP LOCKED")
                .param("lease", seconds(lease))
                .query((rs, i) -> Map.entry(status(rs.getString("status")), Optional.ofNullable(rs.getObject("owner_id", UUID.class)))).list();
            int changed = 0;
            for (var entry : stale) {
                if (entry.getValue().filter(mine::contains).isPresent()) continue;
                var next = interrupted.apply(entry.getKey());
                changed += jdbc.sql("UPDATE migration_job SET state = :state, status = CAST(:status AS jsonb), owner_id = NULL, updated_at = now() WHERE id = :id")
                    .param("state", next.state()).param("status", write(next)).param("id", next.id()).update();
            }
            return changed;
        });
    }

    private static double seconds(Duration d) { return d.toMillis() / 1000.0; }
    private static void requireNoPassword(JsonNode definition) {
        if (definition == null) return;
        for (String side : new String[]{"source", "target"})
            if (!definition.path(side).path("password").asText("").isEmpty()) throw new IllegalStateException("작업 정의에는 비밀번호를 저장할 수 없습니다.");
    }
    private Row row(ResultSet rs, int index) throws SQLException {
        String definition = rs.getString("definition");
        try {
            return new Row(rs.getString("id"), status(rs.getString("status")), definition == null ? null : json.readTree(definition),
                rs.getString("source_label"), rs.getString("target_label"));
        } catch (JsonProcessingException e) { throw new IllegalStateException("저장된 작업 정의를 읽지 못했습니다."); }
    }
    private MigrationJobs.Status status(String value) {
        try { return json.readValue(value, MigrationJobs.Status.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("저장된 작업 상태를 읽지 못했습니다."); }
    }
    private String write(Object value) {
        try { return NUL.matcher(json.writeValueAsString(value)).replaceAll("$1"); }
        catch (JsonProcessingException e) { throw new IllegalStateException("작업 상태를 저장할 수 없습니다."); }
    }
}
