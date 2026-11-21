package dev.tableflow.api;

import dev.tableflow.connection.JdbcExplorer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** 반복 실행(스케줄) 관리. 이관 API와 같이 로컬 워크스페이스에서만 사용할 수 있다. */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    private final ScheduleService schedules;
    @Value("${tableflow.frontend-origin}") private String origin;
    public ScheduleController(ScheduleService schedules) { this.schedules = schedules; }

    @ModelAttribute void localOnly(HttpServletRequest request) throws java.net.UnknownHostException {
        String incoming = request.getHeader("Origin");
        if (!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress() || (incoming != null && !origin.equals(incoming)))
            throw new JdbcExplorer.Failure(403, "JDBC_ACCESS_DENIED", "스케줄은 로컬 워크스페이스에서만 사용할 수 있습니다.");
    }
    @GetMapping public Object list() { return java.util.Map.of("enabled", schedules.enabled(), "items", schedules.list()); }
    @PostMapping public Object create(@RequestBody ScheduleService.CreateRequest request) { return schedules.create(request); }
    public record EnableRequest(boolean enabled) {}
    @PostMapping("/{id}/enabled") public Object enabled(@PathVariable String id, @RequestBody EnableRequest body) { return schedules.setEnabled(id, body.enabled()); }
    @PostMapping("/{id}/run") public Object run(@PathVariable String id) { return schedules.runNow(id); }
    @DeleteMapping("/{id}") public Object delete(@PathVariable String id) { return java.util.Map.of("deleted", schedules.delete(id)); }
}
