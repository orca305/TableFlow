package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.connection.JdbcReader;
import dev.tableflow.export.TableExporter.InvalidTable;
import dev.tableflow.load.DatabaseLoader.LoadFailure;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.transform.TableEngine;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * DB → DB 이전. 원본 읽기({@link JdbcReader}) → 대상 자료형에 맞춘 변환 → 기존 {@link JdbcLoader}의
 * 점검·트랜잭션·영수증(멱등성)·전체 롤백 경로를 그대로 사용한다. 기존 테이블의 UPDATE·DELETE·DROP·ALTER는 하지 않는다
 * (새 테이블 생성 옵션의 CREATE, Oracle 시퀀스 재동기화의 ALTER IDENTITY 제외).
 * 10,000행 이하는 한 트랜잭션, 초과(대용량)는 청크마다 별도 트랜잭션·영수증으로 적재하며 같은 요청으로 이어서 실행할 수 있다.
 */
@Service
public class MigrationService {
    private static final String RECEIPTS = "tableflow_load_receipts";
    private static final int SAMPLE_ROWS = 200;
    private final JdbcExplorer explorer; private final JdbcReader reader; private final JdbcLoader loader; private final TableEngine engine;
    private final int chunkSize; private final long maxRows;
    public MigrationService(JdbcExplorer explorer, JdbcReader reader, JdbcLoader loader, TableEngine engine,
                            @Value("${tableflow.migration.chunk-size:5000}") int chunkSize,
                            @Value("${tableflow.migration.max-rows:1000000}") long maxRows) {
        this.explorer = explorer; this.reader = reader; this.loader = loader; this.engine = engine;
        this.chunkSize = Math.max(100, Math.min(chunkSize, JdbcReader.MAX_ROWS)); this.maxRows = Math.max(JdbcReader.MAX_ROWS, maxRows);
    }

    /** type·size·scale은 신규 테이블 생성일 때만 쓰는 대상 컬럼 정의(생략하면 원본 정의를 따른다). size는 문자열이면 길이, 숫자면 정밀도. */
    public record Mapping(@NotBlank @Size(max = 128) String source, @NotBlank @Size(max = 128) String target,
                          DataType type, @Min(1) @Max(32767) Integer size, @Min(0) @Max(38) Integer scale) {
        public Mapping(String source, String target) { this(source, target, null, null, null); }
        boolean overridden() { return type != null || size != null || scale != null; }
    }
    /** level: ok(호환) / info(자동 변환) / warn(주의) / error(이관하면 실패하거나 값이 손상됨). detail은 사람이 읽는 설명. */
    public record ColumnPlan(String source, String target, String sourceType, String targetType, String conversion, String level, String detail) {
        public ColumnPlan(String source, String target, String sourceType, String targetType, String conversion) { this(source, target, sourceType, targetType, conversion, "ok", ""); }
    }
    public record Plan(String sourceDbms, String targetDbms, String source, String target, long sourceRows, long rows,
                       List<ColumnPlan> columns, List<String> notes, boolean createsTable, List<String> ddl, List<String> warnings,
                       boolean bulk, int chunks, int chunkSize) {}
    public record Outcome(String requestId, String tableName, long insertedRows, boolean replayed, List<String> notes, int chunks, int replayedChunks) {
        public DatabaseLoader.Result asResult() { return new DatabaseLoader.Result(requestId, tableName, (int) Math.min(insertedRows, Integer.MAX_VALUE), replayed); }
    }
    /** 대용량 실행의 진행 상황 수신자(작업 서비스가 구현). */
    public interface Progress {
        void total(int chunks, long rows);
        void chunk(int doneChunks, long doneRows, int replayedChunks);
        boolean cancelled();
    }

    private enum Kind { NONE, DATETIME_TO_DATE, DATE_TO_DATETIME }
    private record Layout(List<Mapping> mappings, List<Column> columns, List<Kind> kinds, List<ColumnPlan> plans, List<String> notes, List<String> warnings,
                          List<String> ddl, Set<String> keepIdentity) {}

    // ---------------------------------------------------------------- 파일(표) → 신규 DB 테이블 생성
    public record CreatePlan(String dbms, String schema, String tableName, int rows, List<String> columns, boolean createsTable, List<String> ddl, List<String> warnings) {}
    private List<TableDdl.Col> columnsFor(Table table) {
        List<TableDdl.Col> cols = new ArrayList<>();
        for (Column c : table.columns()) {
            if (!c.included()) continue;
            int size = 0;
            if (c.dataType() == DataType.string) {
                int longest = 0;
                for (Row r : table.rows()) { var cell = r.cells().get(c.id()); if (cell != null && cell.value() != null) longest = Math.max(longest, cell.value().length()); }
                int n = Math.max(c.maxLength() == null ? 0 : c.maxLength(), longest);
                size = c.maxLength() != null ? c.maxLength() : Math.max(50, ((n * 2 + 49) / 50) * 50); // 길이를 지정하지 않으면 실제 최대 길이의 2배(50 단위)
            } else if (c.precision() != null) size = c.precision();
            cols.add(new TableDdl.Col(c.targetName(), c.dataType(), c.nullable(), false, size, c.scale() == null ? 0 : c.scale()));
        }
        return cols;
    }
    /** 대상에 아직 없는 테이블을 변환한 표의 컬럼 정의로 만드는 계획(CREATE TABLE). 아무것도 쓰지 않는다. */
    public CreatePlan planCreate(Credentials target, Table table, String schema, String name) {
        validateOnly(table);
        if (name.equalsIgnoreCase(RECEIPTS)) throw new IllegalArgumentException("영수증 테이블 이름은 사용할 수 없습니다.");
        boolean[] present = explorer.withConnection(target, true, connection -> new boolean[]{exists(connection, target.dbms(), schema, name), exists(connection, target.dbms(), schema, RECEIPTS)});
        if (present[0]) throw new IllegalArgumentException("이미 있는 테이블입니다: " + schema + "." + name + ". '기존 테이블에 적재'를 선택하거나 다른 이름을 입력하세요.");
        List<TableDdl.Col> cols = columnsFor(table);
        List<String> ddl = new ArrayList<>(List.of(TableDdl.createTable(target.dbms(), schema, name, cols)));
        if (!present[1]) ddl.add(TableDdl.receiptsTable(target.dbms(), schema));
        List<String> warnings = new ArrayList<>(List.of("기본 키 없이 생성됩니다. 필요하면 적재 후 DB에서 직접 추가하세요."));
        if (target.dbms() != Dbms.postgresql) warnings.add("이 DB는 테이블 생성(DDL)을 되돌릴 수 없습니다. 적재가 실패하면 빈 테이블이 남을 수 있습니다.");
        return new CreatePlan(target.dbms().name(), schema, name, table.rows().size(), cols.stream().map(TableDdl.Col::name).toList(), true, ddl, warnings);
    }
    /** 적재 직전에 테이블이 없으면 만든다. 이미 있으면(같은 요청의 재시도) 건너뛰어 같은 요청 ID로 안전하게 다시 보낼 수 있다. */
    public void ensureTable(Credentials target, Table table, String schema, String name) {
        boolean exists = explorer.withConnection(target, true, connection -> exists(connection, target.dbms(), schema, name));
        if (exists) return;
        var plan = planCreate(target, table, schema, name);
        explorer.withConnection(target, false, connection -> {
            for (String statement : plan.ddl()) try (var st = connection.createStatement()) { st.setQueryTimeout(30); st.execute(statement); }
            return null;
        });
    }

    // ---------------------------------------------------------------- 매핑 분석(값 검증 없이 컬럼 호환성만)
    public record Analysis(long sourceRows, long maxRows, List<ColumnPlan> columns, List<String> notes, List<String> warnings, boolean createsTable, List<String> ddl) {}
    /**
     * 컬럼 매핑 화면용. 행 값은 검증하지 않고 컬럼 메타데이터로 호환성(타입·길이·정밀도)과 필요한 변환을 판정한다.
     * 길이 위험이 있는 문자열 컬럼은 원본의 실제 최대 길이를 한 번 조회한다. 아무것도 쓰지 않는다.
     */
    public Analysis analyze(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema,
                            String targetTable, List<Mapping> mappings, boolean createTable, boolean preserveKeys) {
        validateMappings(mappings);
        Table shape = reader.read(source, sourceSchema, sourceTable, mappings.stream().map(Mapping::source).toList(), 1);
        Layout layout = layout(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, createTable, preserveKeys, shape);
        return new Analysis(reader.count(source, sourceSchema, sourceTable), maxRows, layout.plans(), layout.notes(), layout.warnings(), !layout.ddl().isEmpty(), layout.ddl());
    }

    // ---------------------------------------------------------------- 점검(Dry-run)
    public Plan check(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema,
                      String targetTable, List<Mapping> mappings, int limit, boolean createTable) {
        return check(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, limit, createTable, false);
    }
    /** 아무것도 쓰지 않는다. limit이 10,000을 넘으면 대용량 점검(처음 200행 표본 + 전체 행 수)을 한다. */
    public Plan check(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema,
                      String targetTable, List<Mapping> mappings, int limit, boolean createTable, boolean preserveKeys) {
        return check(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, limit, createTable, preserveKeys, chunkSize);
    }
    /** chunk: 이번 작업의 분할 단위(행). 서버가 허용하는 100~10,000행으로 보정한다. */
    public Plan check(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema,
                      String targetTable, List<Mapping> mappings, int limit, boolean createTable, boolean preserveKeys, int chunk) {
        validateMappings(mappings);
        if (limit > JdbcReader.MAX_ROWS) return checkBulk(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, limit, createTable, preserveKeys, clampChunk(chunk));
        var counted = reader.readCounted(source, sourceSchema, sourceTable, mappings.stream().map(Mapping::source).toList(), limit);
        if (counted.totalRows() > limit)
            throw new IllegalArgumentException("원본이 " + counted.totalRows() + "행으로 최대 이관 행 수(" + limit + ")보다 많습니다. 일부만 이관하는 것을 막았습니다. 한도를 "
                + JdbcReader.MAX_ROWS + "행보다 크게 올리면 대용량(분할) 이관을 사용할 수 있습니다.");
        Layout layout = layout(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, createTable, preserveKeys, counted.table());
        Table table = materialize(layout, counted.table());
        verify(target, targetSchema, targetTable, layout, table);
        return plan(source, sourceSchema, sourceTable, target, targetSchema, targetTable, layout, counted.totalRows(), table.rows().size(), false, 0, 0);
    }
    private Plan checkBulk(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema,
                           String targetTable, List<Mapping> mappings, int limit, boolean createTable, boolean preserveKeys, int chunk) {
        long total = reader.count(source, sourceSchema, sourceTable);
        if (total > limit) throw new IllegalArgumentException("원본이 " + total + "행으로 최대 이관 행 수(" + limit + ")보다 많습니다. 일부만 이관하는 것을 막았습니다. 한도를 올려주세요.");
        if (total > maxRows) throw new IllegalArgumentException("원본이 " + total + "행으로 서버 한도(" + maxRows + "행)를 넘습니다. 더 작은 단위로 나눠 이관해주세요.");
        if (total == 0) throw new IllegalArgumentException("이관할 데이터가 없습니다.");
        List<String> names = mappings.stream().map(Mapping::source).toList();
        Table sample = reader.readPage(source, sourceSchema, sourceTable, names, 0, (int) Math.min(SAMPLE_ROWS, total));
        Layout layout = layout(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, createTable, preserveKeys, sample);
        Table table = materialize(layout, sample);
        verify(target, targetSchema, targetTable, layout, table);
        int chunks = (int) ((total + chunk - 1) / chunk);
        layout.notes().add("대용량: " + total + "행을 " + chunks + "회로 분할(분할당 최대 " + chunk + "행)해 분할마다 별도 트랜잭션으로 적재합니다. 실패해도 완료된 분할은 이미 커밋되어 있으며, "
            + "같은 요청으로 다시 실행하면 완료된 분할은 건너뛰고 이어서 진행합니다. 실행 중 원본 데이터가 바뀌면 안 됩니다.");
        layout.warnings().add("점검은 처음 " + table.rows().size() + "행만 대상 스키마와 대조합니다. 이후 행의 값·길이·제약 오류는 해당 분할에서 실패로 나타납니다.");
        return plan(source, sourceSchema, sourceTable, target, targetSchema, targetTable, layout, total, total, true, chunks, chunk);
    }
    private void verify(Credentials target, String targetSchema, String targetTable, Layout layout, Table table) {
        if (layout.ddl().isEmpty()) loader.check(target, table, targetSchema, targetTable, layout.keepIdentity());
        else validateOnly(table); // 아직 없는 테이블은 점검할 수 없으므로 데이터만 검증한다
    }
    private Plan plan(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema, String targetTable,
                      Layout layout, long sourceRows, long rows, boolean bulk, int chunks, int chunk) {
        return new Plan(source.dbms().name(), target.dbms().name(), sourceSchema + "." + sourceTable, targetSchema + "." + targetTable, sourceRows, rows,
            layout.plans(), layout.notes(), !layout.ddl().isEmpty(), layout.ddl(), layout.warnings(), bulk, chunks, bulk ? chunk : 0);
    }

    // ---------------------------------------------------------------- 실행(10,000행 이하: 한 트랜잭션)
    public Outcome execute(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema,
                           String targetTable, List<Mapping> mappings, int limit, boolean createTable, String requestId) {
        return execute(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, limit, createTable, false, requestId);
    }
    public Outcome execute(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema,
                           String targetTable, List<Mapping> mappings, int limit, boolean createTable, boolean preserveKeys, String requestId) {
        if (limit > JdbcReader.MAX_ROWS) throw new IllegalArgumentException("행 수가 " + JdbcReader.MAX_ROWS + "을 넘는 이관은 백그라운드 작업(/connections/migrate/jobs)으로 실행해주세요.");
        validateMappings(mappings);
        var counted = reader.readCounted(source, sourceSchema, sourceTable, mappings.stream().map(Mapping::source).toList(), limit);
        if (counted.totalRows() > limit)
            throw new IllegalArgumentException("원본이 " + counted.totalRows() + "행으로 최대 이관 행 수(" + limit + ")보다 많습니다. 일부만 이관하는 것을 막았습니다.");
        Layout layout = layout(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, createTable, preserveKeys, counted.table());
        Table table = materialize(layout, counted.table());
        createIfNeeded(target, layout, table);
        var result = loader.load(target, table, targetSchema, targetTable, requestId, layout.keepIdentity());
        List<String> notes = new ArrayList<>(resync(target, targetSchema, targetTable, layout.keepIdentity()));
        return new Outcome(result.requestId(), result.tableName(), result.insertedRows(), result.replayed(), notes, 0, 0);
    }

    // ---------------------------------------------------------------- 실행(대용량: 청크별 트랜잭션, 이어서 실행 가능)
    public Outcome runBulk(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema, String targetTable,
                           List<Mapping> mappings, int limit, boolean createTable, boolean preserveKeys, String requestId, Progress progress) {
        return runBulk(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, limit, createTable, preserveKeys, requestId, progress, chunkSize);
    }
    /** chunk: 이번 작업의 분할 단위(행). 서버가 허용하는 100~10,000행으로 보정한다. */
    public Outcome runBulk(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema, String targetTable,
                           List<Mapping> mappings, int limit, boolean createTable, boolean preserveKeys, String requestId, Progress progress, int chunkRequested) {
        final int chunk = clampChunk(chunkRequested);
        validateMappings(mappings);
        long total = reader.count(source, sourceSchema, sourceTable);
        if (total > limit) throw new IllegalArgumentException("원본이 " + total + "행으로 최대 이관 행 수(" + limit + ")보다 많습니다. 일부만 이관하는 것을 막았습니다.");
        if (total > maxRows) throw new IllegalArgumentException("원본이 " + total + "행으로 서버 한도(" + maxRows + "행)를 넘습니다.");
        if (total == 0) throw new IllegalArgumentException("이관할 데이터가 없습니다.");
        List<String> names = mappings.stream().map(Mapping::source).toList();
        Table sample = reader.readPage(source, sourceSchema, sourceTable, names, 0, (int) Math.min(SAMPLE_ROWS, total));
        Layout layout = layout(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, createTable, preserveKeys, sample);
        createIfNeeded(target, layout, materialize(layout, sample));
        int chunks = (int) ((total + chunk - 1) / chunk);
        progress.total(chunks, total);
        long inserted = 0; int replayed = 0;
        // 기본 키가 있으면 OFFSET 대신 키 기반(WHERE 키 > 이전 마지막 키)으로 읽어 뒤쪽 청크도 느려지지 않게 한다. 없으면 기존 OFFSET.
        boolean keyset = reader.hasPrimaryKey(source, sourceSchema, sourceTable);
        JdbcReader.Keyset cursor = new JdbcReader.Keyset(null);
        for (int i = 0; i < chunks; i++) {
            if (progress.cancelled())
                throw new LoadFailure(409, "MIGRATION_CANCELLED", "취소했습니다. 완료된 " + i + "회 분할(" + inserted + "행)은 이미 커밋되어 있으며, 같은 요청으로 다시 실행하면 이어서 진행합니다.");
            long offset = (long) i * chunk; int size = (int) Math.min(chunk, total - offset);
            DatabaseLoader.Result result;
            try {
                Table raw = keyset ? reader.readKeyPage(source, sourceSchema, sourceTable, names, cursor, size)
                    : reader.readPage(source, sourceSchema, sourceTable, names, offset, size);
                if (keyset) cursor = new JdbcReader.Keyset(cursor.last());
                if (raw.rows().size() != size)
                    throw new LoadFailure(409, "SOURCE_CHANGED", "실행 중 원본 행 수가 바뀌었습니다. 원본이 변하지 않는 상태에서 같은 요청으로 다시 실행해주세요.");
                // 청크 요청 ID는 (작업 요청 ID, 순번)에서 결정적으로 만든다 → 같은 작업을 다시 실행하면 완료된 청크는 영수증으로 건너뛴다.
                String chunkId = UUID.nameUUIDFromBytes((requestId + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
                result = loader.load(target, materialize(layout, raw), targetSchema, targetTable, chunkId, layout.keepIdentity());
            } catch (RuntimeException e) {
                String[] d = JobHistory.describe(e);
                throw new LoadFailure(e instanceof LoadFailure f ? f.status : 422, d[0], "분할 " + (i + 1) + "/" + chunks + "회차에서 실패했습니다(앞선 " + inserted
                    + "행은 이미 커밋됨). " + d[1] + " 원인을 해결한 뒤 같은 요청으로 다시 실행하면 완료된 분할은 건너뛰고 이어서 진행합니다.");
            }
            inserted += result.insertedRows(); if (result.replayed()) replayed++;
            progress.chunk(i + 1, inserted, replayed);
        }
        List<String> notes = new ArrayList<>(resync(target, targetSchema, targetTable, layout.keepIdentity()));
        return new Outcome(requestId, targetTable, inserted, replayed == chunks, notes, chunks, replayed);
    }

    // ---------------------------------------------------------------- 공통
    /** 서버 기본 분할 단위(행). 설정 tableflow.migration.chunk-size. */
    public int defaultChunkSize() { return chunkSize; }
    // ---------------------------------------------------------------- 컬럼 호환성 판정(메타데이터 + 위험한 문자열 컬럼의 실제 최대 길이)
    private enum Family { STRING, INTEGER, DECIMAL, DATE, DATETIME, BOOL, OTHER }
    private static Family familyOf(int jdbcType, int scale) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> Family.STRING;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> Family.INTEGER;
            case Types.NUMERIC, Types.DECIMAL -> scale == 0 ? Family.INTEGER : Family.DECIMAL;
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> Family.DECIMAL;
            case Types.DATE -> Family.DATE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> Family.DATETIME;
            case Types.BOOLEAN, Types.BIT -> Family.BOOL;
            default -> Family.OTHER;
        };
    }
    private static Family familyOf(DataType type) {
        return switch (type) { case string -> Family.STRING; case integer -> Family.INTEGER; case decimal -> Family.DECIMAL; case date -> Family.DATE;
            case datetime -> Family.DATETIME; case bool -> Family.BOOL; };
    }
    /** 신규 테이블 컬럼의 판정: 기본은 원본 정의를 따르므로 호환. 대상 타입·길이를 직접 지정하면 원본과 비교해 위험을 알린다. */
    private ColumnPlan describeNew(Credentials source, String sourceSchema, String sourceTable, Mapping m, ColumnInfo si, DataType from, DataType to, int size, int scale, List<String> warnings) {
        String st = si == null ? "" : si.type();
        String target = "(새 컬럼) " + to + (size > 0 && (to == DataType.string || to == DataType.integer || to == DataType.decimal) ? "(" + size + (to == DataType.decimal ? "," + scale : "") + ")" : "");
        if (!m.overridden()) return new ColumnPlan(m.source(), m.target(), st, target, "새 테이블에 생성");
        String level = "ok", detail = "대상 타입을 직접 지정했습니다.";
        Family ff = familyOf(from), ft = familyOf(to);
        if (ff != ft) {
            boolean numeric = (ff == Family.INTEGER || ff == Family.DECIMAL) && (ft == Family.INTEGER || ft == Family.DECIMAL);
            boolean dates = (ff == Family.DATE || ff == Family.DATETIME) && (ft == Family.DATE || ft == Family.DATETIME);
            if (ft == Family.STRING) { level = "info"; detail = "값을 문자열로 바꿔 넣습니다."; }
            else if (ff == Family.STRING) { level = "warn"; detail = "문자열 값을 " + to + " 타입으로 변환해 넣습니다. 형식이 맞지 않는 값이 있으면 이관이 실패합니다."; }
            else if (numeric || dates) { level = "warn"; detail = from + " → " + to + " 변환으로 값이 바뀌거나 손실될 수 있습니다."; }
            else { level = "error"; detail = from + " 값을 " + to + " 타입으로 만들 수 없습니다. 다른 타입을 선택하세요."; }
        } else if (ff == Family.STRING && si != null && si.size() > 0 && size > 0 && size < si.size()) {
            long[] stats = lengthStats(source, sourceSchema, sourceTable, m.source(), size);
            if (stats == null) { level = "warn"; detail = "지정한 길이(" + size + ")가 원본 컬럼(" + si.size() + ")보다 짧습니다. 실제 값의 길이는 확인하지 못했습니다."; }
            else if (stats[1] > 0) { level = "error"; detail = "원본 값 중 최대 " + stats[0] + "자로, 지정한 길이(" + size + "자)를 넘는 값이 " + stats[1] + "건 있습니다. 길이를 늘려주세요."; }
            else detail = "지정한 길이(" + size + "자)가 원본 컬럼(" + si.size() + ")보다 짧지만 실제 값은 모두 이내입니다(최대 " + stats[0] + "자).";
        } else if ((ff == Family.INTEGER || ff == Family.DECIMAL) && si != null && si.size() > 0 && size > 0 && (size < si.size() || (ft == Family.DECIMAL && scale < si.scale()))) {
            level = "warn"; detail = "지정한 정밀도(" + size + "," + scale + ")가 원본(" + si.size() + "," + si.scale() + ")보다 작습니다. 큰 값이나 소수 자리는 넘칠 수 있습니다.";
        }
        if (level.equals("warn") || level.equals("error")) warnings.add("컬럼 " + m.source() + " → " + m.target() + ": " + detail);
        return new ColumnPlan(m.source(), m.target(), st, target, "새 테이블에 생성(대상 타입 지정)", level, detail);
    }
    private ColumnPlan describe(Credentials source, String sourceSchema, String sourceTable, Mapping m, ColumnInfo si, ColumnInfo ti,
                                DataType from, String conversion, List<String> warnings) {
        String st = si == null ? "" : si.type();
        Family fs = familyOf(from), ft = familyOf(ti.jdbcType(), ti.scale());
        String level = conversion.isEmpty() ? "ok" : "info", detail = conversion;
        if (ft == Family.OTHER) {
            level = "warn"; detail = "대상 타입(" + ti.type() + ")은 자동으로 판정할 수 없습니다. 사전 점검 결과로 값이 들어가는지 확인하세요.";
        } else if (fs == Family.STRING && ft != Family.STRING) {
            level = "error"; detail = "문자열 값을 " + ti.type() + " 컬럼에 넣을 수 없습니다. 다른 컬럼을 선택하거나 이관에서 제외하세요.";
        } else if (fs != Family.STRING && ft != Family.STRING && fs != ft) {
            boolean numeric = (fs == Family.INTEGER || fs == Family.DECIMAL) && (ft == Family.INTEGER || ft == Family.DECIMAL);
            boolean dates = (fs == Family.DATE || fs == Family.DATETIME) && (ft == Family.DATE || ft == Family.DATETIME);
            if (numeric) {
                if (fs == Family.DECIMAL) { level = "warn"; detail = "소수점 이하 값은 정수 컬럼에 넣을 때 반올림되거나 오류가 날 수 있습니다."; }
            } else if (dates) {
                if (fs == Family.DATETIME) { level = "warn"; detail = "일시를 날짜 컬럼에 넣습니다. 시간 정보가 있는 값은 거부됩니다."; }
            } else { level = "error"; detail = st + " 값을 " + ti.type() + " 컬럼에 넣을 수 없습니다. 다른 컬럼을 선택하거나 이관에서 제외하세요."; }
        } else if (fs == Family.STRING && ft == Family.STRING && si != null && si.size() > 0 && ti.size() > 0 && ti.size() < si.size() && ti.size() < 100_000) {
            long[] stats = lengthStats(source, sourceSchema, sourceTable, m.source(), ti.size());
            if (stats == null) { level = "warn"; detail = "원본 컬럼 길이(" + si.size() + ")가 대상(" + ti.size() + ")보다 큽니다. 실제 값의 길이는 확인하지 못했습니다."; }
            else if (stats[1] > 0) { level = "error"; detail = "원본 값 중 최대 " + stats[0] + "자로, 대상 한도(" + ti.size() + "자)를 넘는 값이 " + stats[1] + "건 있습니다. 이관하면 해당 분할에서 실패합니다."; }
            else { level = "ok"; detail = "컬럼 길이는 원본(" + si.size() + ")이 더 크지만 실제 값은 모두 대상 한도(" + ti.size() + "자) 이하입니다(최대 " + stats[0] + "자)."; }
        } else if ((fs == Family.INTEGER || fs == Family.DECIMAL) && (ft == Family.INTEGER || ft == Family.DECIMAL) && si != null
                && si.size() > 0 && ti.size() > 0 && (ti.size() < si.size() || (ft == Family.DECIMAL && ti.scale() < si.scale()))) {
            level = "warn"; detail = "원본 정밀도(" + si.size() + "," + si.scale() + ")가 대상(" + ti.size() + "," + ti.scale() + ")보다 큽니다. 큰 값이나 소수 자리는 넘칠 수 있습니다.";
        }
        if (level.equals("warn") || level.equals("error")) warnings.add("컬럼 " + m.source() + " → " + m.target() + ": " + detail);
        return new ColumnPlan(m.source(), m.target(), st, ti.type(), conversion, level, detail);
    }
    /** {최대 길이, 한도를 넘는 행 수}. 시간이 오래 걸리거나 실패하면 null(판정은 경고로 낮춘다). */
    private long[] lengthStats(Credentials source, String schema, String table, String column, int limit) {
        String fn = source.dbms() == Dbms.mysql || source.dbms() == Dbms.mariadb ? "CHAR_LENGTH" : "LENGTH";
        String c = JdbcReader.quote(source.dbms(), column);
        String sql = "SELECT MAX(" + fn + "(" + c + ")), SUM(CASE WHEN " + fn + "(" + c + ") > " + limit + " THEN 1 ELSE 0 END) FROM "
            + JdbcReader.quote(source.dbms(), schema) + "." + JdbcReader.quote(source.dbms(), table);
        try {
            return explorer.withConnection(source, true, connection -> {
                try (var st = connection.createStatement()) {
                    st.setQueryTimeout(30);
                    try (var rs = st.executeQuery(sql)) { rs.next(); return new long[]{rs.getLong(1), rs.getLong(2)}; }
                }
            });
        } catch (RuntimeException e) { return null; }
    }
    private static int clampChunk(int c) { return Math.max(100, Math.min(c, JdbcReader.MAX_ROWS)); }
    private void validateMappings(List<Mapping> mappings) {
        if (mappings == null || mappings.isEmpty()) throw new IllegalArgumentException("이관할 컬럼 매핑을 하나 이상 지정해주세요.");
        if (mappings.size() > 100) throw new IllegalArgumentException("한 번에 최대 100개 컬럼까지 이관할 수 있습니다.");
        Set<String> sources = new HashSet<>(), targets = new HashSet<>();
        for (Mapping m : mappings) {
            if (!sources.add(m.source())) throw new IllegalArgumentException("같은 원본 컬럼을 두 번 매핑할 수 없습니다: " + m.source());
            if (!targets.add(m.target().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("같은 대상 컬럼에 두 번 매핑할 수 없습니다: " + m.target());
        }
    }
    private void validateOnly(Table table) {
        if (!loader.enabled()) throw new LoadFailure(503, "JDBC_LOAD_DISABLED", "DB 적재를 활성화하려면 TABLEFLOW_JDBC_LOAD_ENABLED=true로 백엔드를 실행해주세요.");
        if (table.rows().isEmpty()) throw new IllegalArgumentException("이관할 데이터가 없습니다.");
        var result = engine.validate(engine.transform(table));
        if (!result.valid()) throw new InvalidTable(result);
    }
    /** CREATE만 실행한다(이미 있는 테이블은 건드리지 않음). PostgreSQL 외에는 DDL이 자동 커밋되어 이후 적재가 실패하면 빈 테이블이 남을 수 있다. */
    private void createIfNeeded(Credentials target, Layout layout, Table sample) {
        if (layout.ddl().isEmpty()) return;
        validateOnly(sample);
        explorer.withConnection(target, false, connection -> {
            for (String statement : layout.ddl()) try (var st = connection.createStatement()) { st.setQueryTimeout(30); st.execute(statement); }
            return null;
        });
    }
    private boolean exists(Connection c, Dbms db, String schema, String name) throws SQLException {
        var meta = c.getMetaData(); boolean catalog = db == Dbms.mysql || db == Dbms.mariadb;
        try (var rs = meta.getTables(catalog ? schema : null, catalog ? null : JdbcExplorer.literalPattern(meta, schema), JdbcExplorer.literalPattern(meta, name), new String[]{"TABLE"})) {
            while (rs.next()) if (name.equals(rs.getString("TABLE_NAME"))) return true;
        }
        return false;
    }
    /** 외래 키 설명(최대 20개). 대상에서는 부모 행이 없으면 DB가 거부해 전체 롤백된다. */
    private List<String> foreignKeys(Credentials c, String schema, String table) {
        return explorer.withConnection(c, true, connection -> {
            var meta = connection.getMetaData(); boolean catalog = c.dbms() == Dbms.mysql || c.dbms() == Dbms.mariadb;
            List<String> result = new ArrayList<>();
            try (var rs = meta.getImportedKeys(catalog ? schema : null, catalog ? null : schema, table)) {
                while (rs.next() && result.size() < 20) {
                    String parent = catalog ? rs.getString("PKTABLE_CAT") : rs.getString("PKTABLE_SCHEM");
                    result.add(rs.getString("FKCOLUMN_NAME") + " → " + parent + "." + rs.getString("PKTABLE_NAME") + "(" + rs.getString("PKCOLUMN_NAME") + ")");
                }
            }
            return result;
        });
    }

    /** 메타데이터만으로 컬럼 매핑·변환 규칙·DDL·경고를 정한다(행 값은 보지 않는다). shape는 컬럼 정의(id·자료형)를 얻기 위한 읽기 결과. */
    private Layout layout(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema, String targetTable,
                          List<Mapping> mappings, boolean createTable, boolean preserveKeys, Table shape) {
        boolean[] present = explorer.withConnection(target, true, connection -> new boolean[]{exists(connection, target.dbms(), targetSchema, targetTable), exists(connection, target.dbms(), targetSchema, RECEIPTS)});
        boolean tableExists = present[0], receiptsExist = present[1];
        if (!tableExists && !createTable)
            throw new IllegalArgumentException("대상 테이블이 없습니다: " + targetSchema + "." + targetTable + ". 먼저 준비하거나 '대상 테이블이 없으면 생성'을 선택해주세요.");
        boolean creating = createTable && (!tableExists || !receiptsExist);
        Map<String, ColumnInfo> targetColumns = new HashMap<>();
        if (tableExists) for (var c : explorer.columns(target, targetSchema, targetTable).items()) targetColumns.put(c.name(), c);
        Map<String, ColumnInfo> sourceColumns = new HashMap<>();
        for (var c : explorer.columns(source, sourceSchema, sourceTable).items()) sourceColumns.put(c.name(), c);
        List<String> warnings = new ArrayList<>(), ddl = new ArrayList<>(), notes = new ArrayList<>();
        List<TableDdl.Col> newColumns = new ArrayList<>();
        Set<String> mappedTargets = new HashSet<>(), keepIdentity = new LinkedHashSet<>();
        List<Column> columns = new ArrayList<>(); List<Kind> kinds = new ArrayList<>(); List<ColumnPlan> plans = new ArrayList<>();
        for (int i = 0; i < mappings.size(); i++) {
            Mapping m = mappings.get(i); Column column = shape.columns().get(i);
            ColumnInfo sourceInfo = sourceColumns.get(m.source());
            ColumnInfo targetInfo = targetColumns.get(m.target());
            if (!tableExists) { // 새 테이블: 원본 컬럼 정의에서 대상 컬럼을 만든다
                boolean allKeysMapped = sourceColumns.values().stream().filter(ColumnInfo::primaryKey).allMatch(k -> mappings.stream().anyMatch(x -> x.source().equals(k.name())));
                DataType newType = m.type() != null ? m.type() : column.dataType();
                int newSize = m.size() != null ? m.size() : sourceInfo == null ? 0 : sourceInfo.size();
                int newScale = m.scale() != null ? m.scale() : sourceInfo == null ? 0 : sourceInfo.scale();
                newColumns.add(new TableDdl.Col(m.target(), newType, sourceInfo == null || sourceInfo.nullable(), sourceInfo != null && sourceInfo.primaryKey() && allKeysMapped, newSize, newScale));
                if (sourceInfo != null && sourceInfo.autoIncrement()) warnings.add("원본 자동 증가 컬럼 " + m.source() + "은 일반 컬럼으로 생성됩니다(원본 값은 그대로 들어가며 자동 증가 속성은 복제하지 않음).");
                plans.add(describeNew(source, sourceSchema, sourceTable, m, sourceInfo, column.dataType(), newType, newSize, newScale, warnings));
                // 대상 타입을 직접 지정한 컬럼만 그 정의(길이·정밀도)로 값을 검증한다. 지정하지 않으면 기존 동작 그대로.
                columns.add(new Column(column.id(), column.name(), m.target(), newType, true, true,
                    m.overridden() && newType == DataType.string && newSize > 0 ? Math.min(newSize, 32767) : null,
                    m.overridden() && (newType == DataType.integer || newType == DataType.decimal) && newSize > 0 ? Math.min(newSize, 38) : null,
                    m.overridden() && newType == DataType.decimal ? Math.min(newScale, 38) : null));
                kinds.add(Kind.NONE);
                continue;
            }
            if (targetInfo == null) throw new IllegalArgumentException("대상 테이블에 없는 컬럼입니다(대소문자까지 일치해야 합니다): " + m.target());
            if (targetInfo.autoIncrement()) {
                if (!preserveKeys)
                    throw new IllegalArgumentException("대상 컬럼 " + m.target() + "은 자동 증가(IDENTITY/AUTO_INCREMENT) 컬럼이라 원본 값을 넣을 수 없습니다. 매핑에서 제외하면 DB가 새 번호를 부여합니다"
                        + "(원본 키 값은 보존되지 않으며 FK 연결이 깨질 수 있습니다). 원본 키 값을 그대로 옮기려면 '자동 증가 컬럼에 원본 키 값 보존' 옵션을 선택하세요.");
                keepIdentity.add(m.target());
            }
            mappedTargets.add(m.target());
            DataType from = column.dataType(), to = from; String conversion = ""; Kind kind = Kind.NONE;
            if (from == DataType.datetime && targetInfo.jdbcType() == Types.DATE) {
                kind = Kind.DATETIME_TO_DATE; to = DataType.date; conversion = "일시 → 날짜 (시간이 00:00:00인 값만 허용, 시간 정보가 있으면 거부)";
            } else if (from == DataType.date && targetInfo.jdbcType() == Types.TIMESTAMP) {
                kind = Kind.DATE_TO_DATETIME; to = DataType.datetime; conversion = "날짜 → 일시 (00:00:00 추가)";
            } else if (from != DataType.string && isText(targetInfo.jdbcType())) {
                to = DataType.string; conversion = from + " → 문자열";
            } else if (from == DataType.integer && (targetInfo.jdbcType() == Types.NUMERIC || targetInfo.jdbcType() == Types.DECIMAL) && targetInfo.scale() > 0) {
                to = DataType.decimal; conversion = "정수 → 소수";
            }
            if (!conversion.isEmpty()) notes.add(m.source() + " → " + m.target() + ": " + conversion);
            columns.add(new Column(column.id(), column.name(), m.target(), to, true, true, null, null, null));
            kinds.add(kind);
            plans.add(describe(source, sourceSchema, sourceTable, m, sourceInfo, targetInfo, from, conversion, warnings));
        }
        if (!tableExists) {
            if (newColumns.stream().noneMatch(TableDdl.Col::primaryKey)) warnings.add("원본의 기본 키가 모두 매핑되지 않아 새 테이블에는 기본 키를 만들지 않습니다.");
            ddl.add(TableDdl.createTable(target.dbms(), targetSchema, targetTable, newColumns));
        } else {
            if (!keepIdentity.isEmpty()) {
                if (target.dbms() == Dbms.oracle) rejectAlwaysIdentity(target, targetSchema, targetTable, keepIdentity);
                notes.add("자동 증가 컬럼 " + String.join(", ", keepIdentity) + "에 원본 값을 그대로 넣고, 적재 후 "
                    + (target.dbms() == Dbms.mysql || target.dbms() == Dbms.mariadb ? "AUTO_INCREMENT는 DB가 최댓값 다음으로 자동 갱신합니다." : "다음 번호가 최댓값 다음이 되도록 시퀀스를 재동기화합니다."));
            }
            for (var info : targetColumns.values())
                if (info.autoIncrement() && !mappedTargets.contains(info.name()))
                    warnings.add("대상 자동 증가 컬럼 " + info.name() + "은 DB가 값을 부여합니다(원본의 같은 의미 컬럼 값은 보존되지 않음).");
            for (var info : targetColumns.values())
                if (!info.nullable() && !info.autoIncrement() && !mappedTargets.contains(info.name()))
                    warnings.add("대상 필수(NOT NULL) 컬럼 " + info.name() + "이 매핑되지 않았습니다. 기본값이 없으면 점검에서 거부됩니다.");
            for (String fk : foreignKeys(target, targetSchema, targetTable))
                warnings.add("대상 외래 키 " + fk + " — 부모 테이블에 해당 행이 먼저 있어야 하며, 없으면 전체 롤백됩니다.");
        }
        if (creating && !receiptsExist) ddl.add(TableDdl.receiptsTable(target.dbms(), targetSchema));
        for (String fk : foreignKeys(source, sourceSchema, sourceTable))
            warnings.add("원본 외래 키 " + fk + " — 부모 테이블을 먼저 이관하세요. 외래 키는 대상에 복제하지 않습니다.");
        return new Layout(mappings, columns, kinds, plans, notes, warnings, ddl, keepIdentity);
    }

    /** 레이아웃의 값 변환을 행에 적용한다(청크마다 호출). */
    private Table materialize(Layout layout, Table raw) {
        List<Row> rows = new ArrayList<>(raw.rows().size());
        for (Row r : raw.rows()) rows.add(new Row(r.id(), new LinkedHashMap<>(r.cells())));
        for (int i = 0; i < layout.kinds().size(); i++) {
            Kind kind = layout.kinds().get(i); if (kind == Kind.NONE) continue;
            String id = raw.columns().get(i).id(); Mapping m = layout.mappings().get(i);
            for (Row r : rows) {
                Cell cell = r.cells().get(id); String value = cell.value(); if (value == null) continue;
                if (kind == Kind.DATETIME_TO_DATE) {
                    if (!LocalDateTime.parse(value).toLocalTime().equals(LocalTime.MIDNIGHT))
                        throw new IllegalArgumentException("시간 정보가 있는 값은 DATE 컬럼으로 이관하면 시간이 사라집니다: " + m.source() + " → " + m.target()
                            + ". 대상 컬럼을 TIMESTAMP로 바꾸거나 시간 값을 확인해주세요.");
                    r.cells().put(id, new Cell(cell.raw(), value.substring(0, 10), null, false));
                } else r.cells().put(id, new Cell(cell.raw(), value + "T00:00:00", null, false));
            }
        }
        return new Table(raw.schemaVersion(), raw.tableId(), raw.name(), raw.source(), layout.columns(), rows);
    }

    private void rejectAlwaysIdentity(Credentials target, String schema, String table, Set<String> keep) {
        explorer.withConnection(target, true, connection -> {
            try (var ps = connection.prepareStatement("SELECT COLUMN_NAME FROM ALL_TAB_IDENTITY_COLS WHERE OWNER=? AND TABLE_NAME=? AND GENERATION_TYPE='ALWAYS'")) {
                ps.setString(1, schema); ps.setString(2, table);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) if (keep.contains(rs.getString(1)))
                        throw new IllegalArgumentException("Oracle GENERATED ALWAYS identity 컬럼 " + rs.getString(1) + "에는 값을 넣을 수 없습니다. 컬럼을 GENERATED BY DEFAULT로 바꾸거나 매핑에서 제외하세요.");
                }
            }
            return null;
        });
    }

    /** 키 보존 적재 후 다음 번호가 최댓값 다음이 되도록 맞춘다. 실패해도 적재 결과는 유지하고 안내만 남긴다. */
    private List<String> resync(Credentials target, String schema, String table, Set<String> columns) {
        if (columns.isEmpty()) return List.of();
        Dbms db = target.dbms();
        if (db == Dbms.mysql || db == Dbms.mariadb) return List.of("AUTO_INCREMENT는 DB가 최댓값 다음으로 자동 갱신했습니다.");
        List<String> notes = new ArrayList<>();
        for (String column : columns) {
            try {
                notes.add(explorer.withConnection(target, false, connection -> {
                    if (db == Dbms.oracle) {
                        try (var st = connection.createStatement()) {
                            st.setQueryTimeout(30);
                            st.execute("ALTER TABLE " + TableDdl.quote(db, schema) + "." + TableDdl.quote(db, table) + " MODIFY (" + TableDdl.quote(db, column) + " GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE))");
                        }
                        return "시퀀스 재동기화: " + column + " (START WITH LIMIT VALUE)";
                    }
                    BigDecimal max;
                    try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT MAX(" + TableDdl.quote(db, column) + ") FROM " + TableDdl.quote(db, schema) + "." + TableDdl.quote(db, table))) {
                        rs.next(); max = rs.getBigDecimal(1);
                    }
                    if (max == null) return "시퀀스 재동기화: " + column + " (행이 없어 변경 없음)";
                    String sequence;
                    try (var ps = connection.prepareStatement("SELECT pg_get_serial_sequence(?, ?)")) {
                        ps.setString(1, TableDdl.quote(db, schema) + "." + TableDdl.quote(db, table)); ps.setString(2, column);
                        try (var rs = ps.executeQuery()) { rs.next(); sequence = rs.getString(1); }
                    }
                    if (sequence == null) return "시퀀스를 찾지 못해 재동기화하지 않았습니다: " + column;
                    try (var ps = connection.prepareStatement("SELECT setval(?::regclass, ?, true)")) { ps.setString(1, sequence); ps.setLong(2, max.longValueExact()); ps.execute(); }
                    return "시퀀스 재동기화: " + column + " → 다음 값 " + max.add(BigDecimal.ONE).toPlainString();
                }));
            } catch (RuntimeException e) {
                notes.add("시퀀스 재동기화에 실패했습니다(" + column + "): 권한을 확인하고 수동으로 맞춰주세요. 그렇지 않으면 다음 INSERT에서 키 중복 오류가 날 수 있습니다.");
            }
        }
        return notes;
    }

    private static boolean isText(int type) {
        return type == Types.CHAR || type == Types.VARCHAR || type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGVARCHAR || type == Types.LONGNVARCHAR;
    }
}
