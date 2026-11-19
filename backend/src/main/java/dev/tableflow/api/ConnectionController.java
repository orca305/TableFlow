package dev.tableflow.api;

import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.connection.JdbcExplorer.Credentials;
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
    public ConnectionController(JdbcExplorer explorer, dev.tableflow.load.JdbcLoader loader) { this.explorer = explorer; this.loader=loader; }
    @ModelAttribute void localOnly(HttpServletRequest request) throws java.net.UnknownHostException {
        String incoming = request.getHeader("Origin");
        if (!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress() || (incoming != null && !origin.equals(incoming)))
            throw new JdbcExplorer.Failure(403, "JDBC_ACCESS_DENIED", "DB 탐색은 로컬 워크스페이스에서만 사용할 수 있습니다.");
    }
    public record BrowseRequest(@NotNull @Valid Credentials connection, @Size(max=128) String schema, @Size(max=128) String table) {
        @Override public String toString() { return "BrowseRequest[redacted]"; }
    }
    @GetMapping("/drivers") public Object drivers() { var registry=explorer.registry(); return java.util.Map.of("enabled",registry.enabled(),"drivers",registry.drivers(),"loadEnabled",loader.enabled()); }
    @PostMapping("/test") public JdbcExplorer.ServerInfo test(@Valid @RequestBody Credentials request) { return explorer.test(request); }
    @PostMapping("/schemas") public JdbcExplorer.Items<String> schemas(@Valid @RequestBody Credentials request) { return explorer.schemas(request); }
    @PostMapping("/tables") public JdbcExplorer.Items<String> tables(@Valid @RequestBody BrowseRequest request) { return explorer.tables(request.connection(), request.schema()); }
    @PostMapping("/columns") public JdbcExplorer.Items<JdbcExplorer.ColumnInfo> columns(@Valid @RequestBody BrowseRequest request) { return explorer.columns(request.connection(), request.schema(), request.table()); }
    public record LoadRequest(@NotNull @Valid Credentials connection, @NotNull @Valid dev.tableflow.model.Table table,
        @NotBlank @Size(max=128) String schema, @NotBlank @Size(max=128) String tableName,
        @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId, boolean confirmed) {
        @Override public String toString(){return "LoadRequest[redacted]";}
    }
    @PostMapping("/load/check") public Object check(@Valid @RequestBody LoadRequest request){return loader.check(request.connection(),request.table(),request.schema(),request.tableName());}
    @PostMapping("/load") public Object load(@Valid @RequestBody LoadRequest request,
        @RequestHeader(value="X-TableFlow-Reviewed",defaultValue="false") boolean reviewed){
        if(!request.confirmed() || request.requestId()==null)throw new IllegalArgumentException("대상과 행 수를 확인하고 실제 적재에 동의해주세요.");
        try {java.util.UUID.fromString(request.requestId());}catch(IllegalArgumentException e){throw new IllegalArgumentException("요청 ID가 올바르지 않습니다.");}
        if(request.table().source().extractionMethod().contains("ocr") && !reviewed)throw new IllegalArgumentException("OCR 원본 대조 검토가 필요합니다.");
        return loader.load(request.connection(),request.table(),request.schema(),request.tableName(),request.requestId());
    }
}
