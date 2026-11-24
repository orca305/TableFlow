package dev.tableflow.api;

import dev.tableflow.connection.DriverCatalog;
import dev.tableflow.connection.JdbcExplorer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 설치 관리 전용. 서버 인증 통합 전까지는 로컬 접근과 별도 관리자 키가 모두 필요하다. */
@RestController
@RequestMapping("/api/v1/driver-catalog")
public class DriverController {
    private final DriverCatalog catalog;
    private final String adminKey, origin;
    public DriverController(DriverCatalog catalog,
            @Value("${tableflow.drivers.admin-key:}") String adminKey,
            @Value("${tableflow.frontend-origin}") String origin) {
        this.catalog = catalog; this.adminKey = adminKey; this.origin = origin;
    }
    @ModelAttribute void authorize(HttpServletRequest request) throws IOException {
        String supplied = request.getHeader("X-TableFlow-Admin-Key"), incoming = request.getHeader("Origin");
        if (adminKey.length() < 32 || supplied == null
                || !InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
                || (incoming != null && !origin.equals(incoming))
                || !MessageDigest.isEqual(adminKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new JdbcExplorer.Failure(403, "DRIVER_ADMIN_REQUIRED", "드라이버 관리는 로컬 관리자 인증이 필요합니다.");
    }
    @GetMapping public Object list() { return java.util.Map.of("items", catalog.list()); }
    @PostMapping(consumes = "multipart/form-data")
    public DriverCatalog.Entry register(@RequestParam JdbcExplorer.Dbms dbms, @RequestParam String version,
                                        @RequestPart("file") MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) { return catalog.register(dbms, version, input); }
    }
}
