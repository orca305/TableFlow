package dev.tableflow.api;

import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.connection.JdbcExplorer.Credentials;
import dev.tableflow.load.MigrationService.Mapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {
    private final JdbcExplorer explorer;
    private final dev.tableflow.load.JdbcLoader loader;
    @Value("${tableflow.frontend-origin}") private String origin;
    private final dev.tableflow.connection.JdbcReader reader;
    private final dev.tableflow.load.MigrationService migration;
    private final dev.tableflow.load.JobHistory history;
    private final dev.tableflow.load.MigrationJobs jobs;
    private final dev.tableflow.connection.ConnectionStore store;
    public ConnectionController(JdbcExplorer explorer, dev.tableflow.load.JdbcLoader loader, dev.tableflow.connection.JdbcReader reader, dev.tableflow.load.MigrationService migration, dev.tableflow.load.JobHistory history, dev.tableflow.load.MigrationJobs jobs, dev.tableflow.connection.ConnectionStore store) { this.explorer = explorer; this.loader=loader; this.reader=reader; this.migration=migration; this.history=history; this.jobs=jobs; this.store=store; }
    private static String label(Credentials c,String schema,String table){return c.dbms()+" · "+c.database()+" · "+schema+"."+table;}
    @ModelAttribute void localOnly(HttpServletRequest request) throws java.net.UnknownHostException {
        String incoming = request.getHeader("Origin");
        if (!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress() || (incoming != null && !origin.equals(incoming)))
            throw new JdbcExplorer.Failure(403, "JDBC_ACCESS_DENIED", "DB 탐색은 로컬 워크스페이스에서만 사용할 수 있습니다.");
    }
    public record BrowseRequest(@NotNull @Valid Credentials connection, @Size(max=128) String schema, @Size(max=128) String table) {
        @Override public String toString() { return "BrowseRequest[redacted]"; }
    }
    @GetMapping("/drivers") public Object drivers() { var registry=explorer.registry(); return java.util.Map.of("enabled",registry.enabled(),"drivers",registry.drivers(),"loadEnabled",loader.enabled(),
        "savedEnabled",store.enabled(),"savePasswordEnabled",store.passwordAllowed(),"defaultChunkSize",migration.defaultChunkSize()); }
    public record SaveRequest(@NotBlank @Size(max=60) String name, @NotNull @Valid Credentials connection, boolean savePassword) {
        @Override public String toString(){return "SaveRequest[redacted]";}
    }
    /** 저장된 연결 목록. 비밀번호는 포함하지 않고 저장 여부(passwordSaved)만 알린다. */
    @GetMapping("/saved") public Object saved(){return java.util.Map.of("enabled",store.enabled(),"savePasswordEnabled",store.passwordAllowed(),"items",store.list());}
    /** 연결 저장·갱신. savePassword가 true일 때만 비밀번호를 암호화해 서버 로컬 파일에 보관한다. */
    @PostMapping("/saved") public Object save(@Valid @RequestBody SaveRequest r){return store.save(r.name(),r.connection(),r.savePassword());}
    @DeleteMapping("/saved/{id}") public Object deleteSaved(@PathVariable String id){return java.util.Map.of("deleted",store.delete(id));}
    @PostMapping("/test") public JdbcExplorer.ServerInfo test(@Valid @RequestBody Credentials request) { return explorer.test(request); }
    @PostMapping("/schemas") public JdbcExplorer.Items<String> schemas(@Valid @RequestBody Credentials request) { return explorer.schemas(request); }
    @PostMapping("/tables") public JdbcExplorer.Items<String> tables(@Valid @RequestBody BrowseRequest request) { return explorer.tables(request.connection(), request.schema()); }
    /** 테이블 전체 행 수(읽기 전용). 이관 전에 분할 횟수를 계산하는 데 쓴다. */
    @PostMapping("/count") public java.util.Map<String,Object> count(@Valid @RequestBody BrowseRequest request){
        if(request.schema()==null||request.table()==null)throw new IllegalArgumentException("스키마와 테이블을 선택해주세요.");
        return java.util.Map.of("rows",reader.count(request.connection(),request.schema(),request.table()));
    }
    @PostMapping("/columns") public JdbcExplorer.Items<JdbcExplorer.ColumnInfo> columns(@Valid @RequestBody BrowseRequest request) { return explorer.columns(request.connection(), request.schema(), request.table()); }
    public record LoadRequest(@NotNull @Valid Credentials connection, @NotNull @Valid dev.tableflow.model.Table table,
        @NotBlank @Size(max=128) String schema, @NotBlank @Size(max=128) String tableName,
        @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId, boolean confirmed, boolean createTable) {
        @Override public String toString(){return "LoadRequest[redacted]";}
    }
    public record ReadRequest(@NotNull @Valid Credentials connection, @NotBlank @Size(max=128) String schema,
        @NotBlank @Size(max=128) String table, @Size(max=100) java.util.List<@NotBlank @Size(max=128) String> columns,
        @Min(1) @Max(10000) int limit) {
        @Override public String toString(){return "ReadRequest[redacted]";}
    }
    /** DB → File: 읽기 전용으로 테이블을 조회해 Table 구조로 반환한다. 이후 /exports/csv·excel·sql을 재사용한다. */
    @PostMapping("/read") public dev.tableflow.model.Table read(@Valid @RequestBody ReadRequest request){
        return reader.read(request.connection(),request.schema(),request.table(),request.columns(),request.limit());
    }
    public record MigrateRequest(@NotNull @Valid Credentials source, @NotBlank @Size(max=128) String sourceSchema, @NotBlank @Size(max=128) String sourceTable,
        @NotNull @Valid Credentials target, @NotBlank @Size(max=128) String targetSchema, @NotBlank @Size(max=128) String targetTable,
        @NotEmpty @Size(max=100) java.util.List<@NotNull Mapping> mappings,
        @Min(1) @Max(1000000) int limit, boolean createTable, boolean preserveKeys, @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId, boolean confirmed,
        @Min(100) @Max(10000) Integer chunkSize) {
        @Override public String toString(){return "MigrateRequest[redacted]";}
    }
    /** DB → DB Dry-run: 원본을 읽고 대상 스키마까지 검사하지만 아무것도 쓰지 않는다. */
    @PostMapping("/migrate/check") public dev.tableflow.load.MigrationService.Plan migrateCheck(@Valid @RequestBody MigrateRequest r){
        return migration.check(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.limit(),r.createTable(),r.preserveKeys(),r.chunkSize()==null?migration.defaultChunkSize():r.chunkSize());
    }
    /** 컬럼 매핑 분석: 값은 검증하지 않고 컬럼별 호환성(ok/info/warn/error)과 변환·경고만 돌려준다. 아무것도 쓰지 않는다. */
    @PostMapping("/migrate/analyze") public dev.tableflow.load.MigrationService.Analysis migrateAnalyze(@Valid @RequestBody MigrateRequest r){
        return migration.analyze(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.createTable(),r.preserveKeys());
    }
    /** DB → DB 실행: 명시적 동의와 요청 ID가 있어야 하며 전체 성공 또는 전체 롤백한다. */
    @PostMapping("/migrate") public Object migrate(@Valid @RequestBody MigrateRequest r){
        if(!r.confirmed() || r.requestId()==null)throw new IllegalArgumentException("원본·대상과 행 수를 확인하고 실제 이관에 동의해주세요.");
        return history.track("migrate",r.requestId(),label(r.source(),r.sourceSchema(),r.sourceTable()),label(r.target(),r.targetSchema(),r.targetTable()),
            ()->migration.execute(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.limit(),r.createTable(),r.preserveKeys(),r.requestId()),
            dev.tableflow.load.MigrationService.Outcome::asResult);
    }
    /** 대용량(10,000행 초과) DB→DB: 백그라운드로 시작하고 진행률을 조회한다. 같은 requestId로 다시 제출하면 완료된 청크는 건너뛰고 이어서 진행한다. */
    @PostMapping("/migrate/jobs") public Object migrateJob(@Valid @RequestBody MigrateRequest r){
        if(!r.confirmed() || r.requestId()==null)throw new IllegalArgumentException("원본·대상과 행 수를 확인하고 실제 이관에 동의해주세요.");
        return jobs.submit(r.requestId(),progress->history.track("migrate",r.requestId(),label(r.source(),r.sourceSchema(),r.sourceTable()),label(r.target(),r.targetSchema(),r.targetTable()),
            ()->migration.runBulk(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.limit(),r.createTable(),r.preserveKeys(),r.requestId(),progress,r.chunkSize()==null?migration.defaultChunkSize():r.chunkSize()),
            dev.tableflow.load.MigrationService.Outcome::asResult));
    }
    @GetMapping("/migrate/jobs/{id}") public Object migrateJobStatus(@PathVariable String id){return jobs.get(id);}
    @DeleteMapping("/migrate/jobs/{id}") public Object migrateJobCancel(@PathVariable String id){return jobs.cancel(id);}
    /** 최근 적재·이전 이력(최신순, 최대 200건). 비밀번호·호스트·행 데이터는 저장하지 않는다. */
    @GetMapping("/history") public Object history(@RequestParam(defaultValue="50") int limit){return java.util.Map.of("enabled",history.enabled(),"items",history.recent(limit));}
    /** 파일 → DB 점검. createTable이면 대상에 없는 테이블을 만들 계획(DDL)을, 아니면 기존 테이블과의 호환성을 점검한다. */
    @PostMapping("/load/check") public Object check(@Valid @RequestBody LoadRequest request){
        if(request.createTable())return migration.planCreate(request.connection(),request.table(),request.schema(),request.tableName());
        return loader.check(request.connection(),request.table(),request.schema(),request.tableName());
    }
    @PostMapping("/load") public Object load(@Valid @RequestBody LoadRequest request,
        @RequestHeader(value="X-TableFlow-Reviewed",defaultValue="false") boolean reviewed){
        if(!request.confirmed() || request.requestId()==null)throw new IllegalArgumentException("대상과 행 수를 확인하고 실제 적재에 동의해주세요.");
        try {java.util.UUID.fromString(request.requestId());}catch(IllegalArgumentException e){throw new IllegalArgumentException("요청 ID가 올바르지 않습니다.");}
        if(request.table().source().extractionMethod().contains("ocr") && !reviewed)throw new IllegalArgumentException("OCR 원본 대조 검토가 필요합니다.");
        return history.run("load",request.requestId(),request.table().source().filename(),label(request.connection(),request.schema(),request.tableName()),
            ()->{
                if(request.createTable())migration.ensureTable(request.connection(),request.table(),request.schema(),request.tableName());
                return loader.load(request.connection(),request.table(),request.schema(),request.tableName(),request.requestId());
            });
    }
}
