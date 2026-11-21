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
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final dev.tableflow.load.JobStore jobStore;
    private final dev.tableflow.load.MigrationVerifier verifier;
    private final dev.tableflow.load.BatchMigration batch;
    public ConnectionController(JdbcExplorer explorer, dev.tableflow.load.JdbcLoader loader, dev.tableflow.connection.JdbcReader reader, dev.tableflow.load.MigrationService migration, dev.tableflow.load.JobHistory history, dev.tableflow.load.MigrationJobs jobs, dev.tableflow.connection.ConnectionStore store, com.fasterxml.jackson.databind.ObjectMapper json, dev.tableflow.load.JobStore jobStore, dev.tableflow.load.MigrationVerifier verifier, dev.tableflow.load.BatchMigration batch) { this.batch=batch; this.verifier=verifier; this.json=json; this.jobStore=jobStore; this.explorer = explorer; this.loader=loader; this.reader=reader; this.migration=migration; this.history=history; this.jobs=jobs; this.store=store; }
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
    public record CountRequest(@NotNull @Valid Credentials connection, @NotBlank @Size(max=128) String schema, @NotBlank @Size(max=128) String table, @Size(max=1000) String where) {
        @Override public String toString() { return "CountRequest[redacted]"; }
    }
    /** 행 조건(WHERE)에 맞는 행 수(읽기 전용). 조건이 규칙에 어긋나거나 DB가 거부하면 이유를 돌려준다. */
    @PostMapping("/count-filtered") public java.util.Map<String,Object> countFiltered(@Valid @RequestBody CountRequest r){
        return java.util.Map.of("rows",reader.count(r.connection(),r.schema(),r.table(),r.where()));
    }
    @PostMapping("/columns") public JdbcExplorer.Items<JdbcExplorer.ColumnInfo> columns(@Valid @RequestBody BrowseRequest request) { return explorer.columns(request.connection(), request.schema(), request.table()); }
    public record LoadRequest(@NotNull @Valid Credentials connection, @NotNull @Valid dev.tableflow.model.Table table,
        @NotBlank @Size(max=128) String schema, @NotBlank @Size(max=128) String tableName,
        @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId, boolean confirmed, boolean createTable,
        @Size(max=20) java.util.List<@Size(max=128) String> primaryKey) {
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
        @Min(100) @Max(10000) Integer chunkSize, boolean copyIndexes, boolean copyDefaults, boolean copyForeignKeys, @Size(max=1000) String where) {
        /** 재개용으로 보관하는 사본: 비밀번호를 비우고, 저장된 비밀번호가 있는 연결이면 그 ID를 남긴다. */
        MigrateRequest forStorage(dev.tableflow.connection.ConnectionStore store){
            return new MigrateRequest(strip(source,store),sourceSchema,sourceTable,strip(target,store),targetSchema,targetTable,mappings,limit,createTable,preserveKeys,requestId,false,chunkSize,copyIndexes,copyDefaults,copyForeignKeys,where);
        }
        MigrateRequest withPasswords(String sourcePassword,String targetPassword){
            return new MigrateRequest(withPassword(source,sourcePassword),sourceSchema,sourceTable,withPassword(target,targetPassword),targetSchema,targetTable,mappings,limit,createTable,preserveKeys,requestId,true,chunkSize,copyIndexes,copyDefaults,copyForeignKeys,where);
        }
        static Credentials strip(Credentials c,dev.tableflow.connection.ConnectionStore store){
            String id=c.savedId()!=null?c.savedId():store.findSavedId(c);
            return new Credentials(c.dbms(),c.host(),c.port(),c.database(),c.username(),"",c.tls(),id);
        }
        static Credentials withPassword(Credentials c,String password){
            return new Credentials(c.dbms(),c.host(),c.port(),c.database(),c.username(),password==null?"":password,c.tls(),c.savedId());
        }
        /** 스케줄 실행용 사본: 새 요청 ID, 동의 완료, 이관 행 수 상한은 최대로(원본이 늘어도 막히지 않게). */
        MigrateRequest forRun(String id){
            return new MigrateRequest(source,sourceSchema,sourceTable,target,targetSchema,targetTable,mappings,1_000_000,createTable,preserveKeys,id,true,chunkSize,copyIndexes,copyDefaults,copyForeignKeys,where);
        }
        dev.tableflow.load.MigrationService.Extras extras(){return new dev.tableflow.load.MigrationService.Extras(copyIndexes,copyDefaults,copyForeignKeys,where);}
        @Override public String toString(){return "MigrateRequest[redacted]";}
    }
    /** DB → DB Dry-run: 원본을 읽고 대상 스키마까지 검사하지만 아무것도 쓰지 않는다. */
    @PostMapping("/migrate/check") public dev.tableflow.load.MigrationService.Plan migrateCheck(@Valid @RequestBody MigrateRequest r){
        return migration.check(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.limit(),r.createTable(),r.preserveKeys(),r.chunkSize()==null?migration.defaultChunkSize():r.chunkSize(),r.extras());
    }
    /** 이관 후 검증: 원본과 대상의 행 수와 컬럼별 집계(합계·최소·최대·NULL 아닌 값의 수·문자열 길이 합계)를 비교한다. 읽기 전용. */
    @PostMapping("/migrate/verify") public dev.tableflow.load.MigrationVerifier.Result migrateVerify(@Valid @RequestBody MigrateRequest r){
        return verifier.verify(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.where());
    }
    public record OrderRequest(@NotNull @Valid Credentials connection, @NotBlank @Size(max=128) String schema, @NotEmpty @Size(max=200) java.util.List<@NotBlank @Size(max=128) String> tables) {
        @Override public String toString(){return "OrderRequest[redacted]";}
    }
    /** 선택한 테이블의 이관 순서(외래 키 기준, 부모 먼저). 읽기 전용. */
    @PostMapping("/order") public dev.tableflow.load.MigrationService.OrderPlan order(@Valid @RequestBody OrderRequest r){
        return migration.order(r.connection(),r.schema(),r.tables());
    }
    /** 컬럼 매핑 분석: 값은 검증하지 않고 컬럼별 호환성(ok/info/warn/error)과 변환·경고만 돌려준다. 아무것도 쓰지 않는다. */
    @PostMapping("/migrate/analyze") public dev.tableflow.load.MigrationService.Analysis migrateAnalyze(@Valid @RequestBody MigrateRequest r){
        return migration.analyze(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.createTable(),r.preserveKeys(),r.extras());
    }
    /** DB → DB 실행: 명시적 동의와 요청 ID가 있어야 하며 전체 성공 또는 전체 롤백한다. */
    @PostMapping("/migrate") public Object migrate(@Valid @RequestBody MigrateRequest r){
        if(!r.confirmed() || r.requestId()==null)throw new IllegalArgumentException("원본·대상과 행 수를 확인하고 실제 이관에 동의해주세요.");
        return history.track("migrate",r.requestId(),label(r.source(),r.sourceSchema(),r.sourceTable()),label(r.target(),r.targetSchema(),r.targetTable()),
            ()->migration.execute(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.limit(),r.createTable(),r.preserveKeys(),r.requestId(),r.extras()),
            dev.tableflow.load.MigrationService.Outcome::asResult);
    }
    /** 여러 테이블(스키마 전체 포함) 일괄 이관 요청. 대상 테이블은 원본 구조로 새로 만든다. ifExists: skip(기본) | append. */
    public record BatchRequest(@NotNull @Valid Credentials source, @NotBlank @Size(max=128) String sourceSchema, @NotNull @Valid Credentials target, @NotBlank @Size(max=128) String targetSchema,
        @NotEmpty @Size(max=200) java.util.List<@NotBlank @Size(max=128) String> tables, boolean preserveKeys, boolean copyIndexes, boolean copyDefaults, boolean copyForeignKeys,
        @Pattern(regexp="skip|append") String ifExists, @Min(100) @Max(10000) Integer chunkSize, @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId, boolean confirmed) {
        BatchRequest forStorage(dev.tableflow.connection.ConnectionStore store){
            return new BatchRequest(MigrateRequest.strip(source,store),sourceSchema,MigrateRequest.strip(target,store),targetSchema,tables,preserveKeys,copyIndexes,copyDefaults,copyForeignKeys,ifExists,chunkSize,requestId,false);
        }
        BatchRequest withPasswords(String sourcePassword,String targetPassword){
            return new BatchRequest(MigrateRequest.withPassword(source,sourcePassword),sourceSchema,MigrateRequest.withPassword(target,targetPassword),targetSchema,tables,preserveKeys,copyIndexes,copyDefaults,copyForeignKeys,ifExists,chunkSize,requestId,true);
        }
        /** 스케줄 실행용 사본: 새 요청 ID, 동의 완료, 이미 있는 테이블에는 추가 적재(반복 실행이 건너뛰지 않도록). */
        BatchRequest forRun(String id){
            return new BatchRequest(source,sourceSchema,target,targetSchema,tables,preserveKeys,copyIndexes,copyDefaults,copyForeignKeys,"append",chunkSize,id,true);
        }
        dev.tableflow.load.BatchMigration.Options options(){return new dev.tableflow.load.BatchMigration.Options(preserveKeys,copyIndexes,copyDefaults,copyForeignKeys,ifExists,chunkSize);}
        @Override public String toString(){return "BatchRequest[redacted]";}
    }
    /** 일괄 이관 사전 점검: 처리 순서(부모 먼저), 테이블별 행 수·대상 이름·충돌·오류를 돌려준다. 아무것도 쓰지 않는다. */
    @PostMapping("/migrate/batch/check") public dev.tableflow.load.BatchMigration.Plan batchCheck(@Valid @RequestBody BatchRequest r){
        return batch.plan(r.source(),r.sourceSchema(),r.target(),r.targetSchema(),r.tables(),r.options());
    }
    /** 일괄 이관 실행(백그라운드). 진행률과 테이블별 상태는 /migrate/jobs/{id}로 조회한다. */
    @PostMapping("/migrate/batch") public Object batchRun(@Valid @RequestBody BatchRequest r){
        if(!r.confirmed() || r.requestId()==null)throw new IllegalArgumentException("원본·대상과 테이블 목록을 확인하고 실제 이관에 동의해주세요.");
        return submitBatch(r);
    }
    Object submitBatch(BatchRequest r){
        var definition=json.valueToTree(r.forStorage(store));
        String src=r.source().dbms()+" · "+r.source().database()+" · "+r.sourceSchema()+" ("+r.tables().size()+"개 테이블)", dst=r.target().dbms()+" · "+r.target().database()+" · "+r.targetSchema();
        var prior=jobs.items(r.requestId());
        return jobs.submit(r.requestId(),definition,src,dst,progress->history.track("migrate",r.requestId(),src,dst,
            ()->batch.run(r.requestId(),r.source(),r.sourceSchema(),r.target(),r.targetSchema(),r.tables(),r.options(),prior,(dev.tableflow.load.BatchMigration.BatchProgress)progress),
            dev.tableflow.load.MigrationService.Outcome::asResult));
    }
    /** 대용량(10,000행 초과) DB→DB: 백그라운드로 시작하고 진행률을 조회한다. 같은 requestId로 다시 제출하면 완료된 청크는 건너뛰고 이어서 진행한다. */
    @PostMapping("/migrate/jobs") public Object migrateJob(@Valid @RequestBody MigrateRequest r){
        if(!r.confirmed() || r.requestId()==null)throw new IllegalArgumentException("원본·대상과 행 수를 확인하고 실제 이관에 동의해주세요.");
        return submitJob(r);
    }
    Object submitJob(MigrateRequest r){
        var definition=json.valueToTree(r.forStorage(store)); // 비밀번호는 비우고 저장 → 서버를 다시 시작해도 재개할 수 있다
        return jobs.submit(r.requestId(),definition,label(r.source(),r.sourceSchema(),r.sourceTable()),label(r.target(),r.targetSchema(),r.targetTable()),progress->history.track("migrate",r.requestId(),label(r.source(),r.sourceSchema(),r.sourceTable()),label(r.target(),r.targetSchema(),r.targetTable()),
            ()->migration.runBulk(r.source(),r.sourceSchema(),r.sourceTable(),r.target(),r.targetSchema(),r.targetTable(),r.mappings(),r.limit(),r.createTable(),r.preserveKeys(),r.requestId(),progress,r.chunkSize()==null?migration.defaultChunkSize():r.chunkSize(),r.extras()),
            dev.tableflow.load.MigrationService.Outcome::asResult));
    }
    /** 이관 작업 목록(서버를 다시 시작해도 유지). 실패·중지·중단된 작업은 resumable=true. */
    @GetMapping("/migrate/jobs") public Object migrateJobs(){return java.util.Map.of("enabled",jobStore.enabled(),"items",jobs.list());}
    public record ResumeRequest(@Size(max=1024) String sourcePassword,@Size(max=1024) String targetPassword){@Override public String toString(){return "ResumeRequest[redacted]";}}
    /** 중단된 이관을 같은 요청 ID로 다시 시작한다. 완료된 분할은 영수증으로 건너뛰고 이어서 진행한다. 저장된 비밀번호가 없는 쪽은 비밀번호를 받는다. */
    @PostMapping("/migrate/jobs/{id}/resume") public Object migrateJobResume(@PathVariable String id,@Valid @RequestBody ResumeRequest body){
        var def=jobs.definition(id);
        if(def==null||def.isNull())throw new JdbcExplorer.Failure(404,"JOB_NOT_RESUMABLE","재개할 수 없는 작업입니다. DB 이관 화면에서 같은 설정으로 다시 실행해주세요.");
        if(def.path("tables").isArray()){ // 일괄 이관 재개
            BatchRequest stored;
            try{stored=json.treeToValue(def,BatchRequest.class);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new JdbcExplorer.Failure(500,"JOB_DEFINITION","저장된 작업 정보를 읽지 못했습니다.");}
            var b=stored.withPasswords(body.sourcePassword(),body.targetPassword());
            boolean noS=b.source().password().isEmpty()&&b.source().savedId()==null, noT=b.target().password().isEmpty()&&b.target().savedId()==null;
            if(noS||noT)throw new IllegalArgumentException((noS?"원본":"")+(noS&&noT?"과 ":"")+(noT?"대상":"")+" DB의 비밀번호를 입력해주세요(저장된 비밀번호가 없습니다).");
            return submitBatch(b);
        }
        MigrateRequest stored;
        try{stored=json.treeToValue(def,MigrateRequest.class);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new JdbcExplorer.Failure(500,"JOB_DEFINITION","저장된 작업 정보를 읽지 못했습니다.");}
        var r=stored.withPasswords(body.sourcePassword(),body.targetPassword());
        boolean noSource=r.source().password().isEmpty()&&r.source().savedId()==null, noTarget=r.target().password().isEmpty()&&r.target().savedId()==null;
        if(noSource||noTarget)throw new IllegalArgumentException((noSource?"원본":"")+(noSource&&noTarget?"과 ":"")+(noTarget?"대상":"")+" DB의 비밀번호를 입력해주세요(저장된 비밀번호가 없습니다).");
        return submitJob(r);
    }
    @GetMapping("/migrate/jobs/{id}") public Object migrateJobStatus(@PathVariable String id){return jobs.get(id);}
    @DeleteMapping("/migrate/jobs/{id}") public Object migrateJobCancel(@PathVariable String id){return jobs.cancel(id);}
    /** 최근 적재·이전 이력(최신순, 최대 200건). 비밀번호·호스트·행 데이터는 저장하지 않는다. */
    @GetMapping("/history") public Object history(@RequestParam(defaultValue="50") int limit){return java.util.Map.of("enabled",history.enabled(),"items",history.recent(limit));}
    /** 파일 → DB 점검. createTable이면 대상에 없는 테이블을 만들 계획(DDL)을, 아니면 기존 테이블과의 호환성을 점검한다. */
    @PostMapping("/load/check") public Object check(@Valid @RequestBody LoadRequest request){
        if(request.createTable())return migration.planCreate(request.connection(),request.table(),request.schema(),request.tableName(),request.primaryKey());
        return loader.check(request.connection(),request.table(),request.schema(),request.tableName());
    }
    @PostMapping("/load") public Object load(@Valid @RequestBody LoadRequest request,
        @RequestHeader(value="X-TableFlow-Reviewed",defaultValue="false") boolean reviewed){
        if(!request.confirmed() || request.requestId()==null)throw new IllegalArgumentException("대상과 행 수를 확인하고 실제 적재에 동의해주세요.");
        try {java.util.UUID.fromString(request.requestId());}catch(IllegalArgumentException e){throw new IllegalArgumentException("요청 ID가 올바르지 않습니다.");}
        if(request.table().source().extractionMethod().contains("ocr") && !reviewed)throw new IllegalArgumentException("OCR 원본 대조 검토가 필요합니다.");
        return history.run("load",request.requestId(),request.table().source().filename(),label(request.connection(),request.schema(),request.tableName()),
            ()->{
                if(request.createTable())migration.ensureTable(request.connection(),request.table(),request.schema(),request.tableName(),request.primaryKey());
                return loader.load(request.connection(),request.table(),request.schema(),request.tableName(),request.requestId());
            });
    }
}
