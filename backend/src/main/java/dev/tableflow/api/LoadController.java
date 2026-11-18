package dev.tableflow.api;

import dev.tableflow.load.DatabaseLoader;
import dev.tableflow.model.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/loads")
public class LoadController {
    private final DatabaseLoader loader;
    @Value("${tableflow.frontend-origin}") private String origin;
    public LoadController(DatabaseLoader loader) { this.loader = loader; }
    @ModelAttribute void authorizeLocalWorkspace(HttpServletRequest request) throws java.net.UnknownHostException {
        String incoming = request.getHeader("Origin");
        if (!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress() || (incoming != null && !origin.equals(incoming)))
            throw new DatabaseLoader.LoadFailure(403, "LOAD_ACCESS_DENIED", "DB 적재는 로컬 워크스페이스에서만 사용할 수 있습니다.");
    }
    public record CheckRequest(@NotNull @Valid Table table, @NotBlank @Size(max=64) String tableName) {}
    public record LoadRequest(@NotNull @Valid Table table, @NotBlank @Size(max=64) String tableName,
        @NotBlank @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String requestId, boolean confirmed) {}
    @GetMapping("/target") public DatabaseLoader.Target target() { return loader.target(); }
    @PostMapping("/check") public DatabaseLoader.Plan check(@Valid @RequestBody CheckRequest request) { return loader.check(request.table(), request.tableName()); }
    @PostMapping("/database") public DatabaseLoader.Result load(@Valid @RequestBody LoadRequest request,
        @RequestHeader(value="X-TableFlow-Reviewed", defaultValue="false") boolean reviewed) {
        if (!request.confirmed()) throw new IllegalArgumentException("대상 테이블과 행 수를 확인하고 실제 적재에 동의해주세요.");
        if (request.table().source().extractionMethod().contains("ocr") && !reviewed)
            throw new IllegalArgumentException("OCR 결과를 원본과 대조한 후 검토 확인을 해주세요.");
        return loader.load(request.table(), request.tableName(), request.requestId());
    }
}
