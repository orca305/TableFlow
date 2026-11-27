package dev.tableflow.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
public class RequestLimits extends OncePerRequestFilter {
    private final Semaphore slots = new Semaphore(4);
    private final Map<String, long[]> requests = new LinkedHashMap<>();
    private final String origin; private final int perMinute;
    public RequestLimits(@Value("${tableflow.frontend-origin}") String origin, @Value("${tableflow.api.posts-per-minute:120}") int perMinute) {
        this.origin = origin; this.perMinute = Math.max(1, perMinute);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/v1/") || !request.getMethod().equals("POST")) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > 32L * 1024 * 1024) { reject(request, response, 413, "REQUEST_TOO_LARGE", "요청 크기는 최대 32MB입니다."); return; }
        if (!allow(request.getRemoteAddr()) || !slots.tryAcquire()) { reject(request, response, 429, "RATE_LIMITED", "요청이 많습니다. 잠시 후 다시 시도해주세요."); return; }
        try { chain.doFilter(request, response); } finally { slots.release(); }
    }
    private synchronized boolean allow(String address) {
        long now = System.currentTimeMillis();
        requests.entrySet().removeIf(entry -> now - entry.getValue()[0] >= 60000);
        if (!requests.containsKey(address) && requests.size() >= 1024) return false;
        long[] window = requests.computeIfAbsent(address, key -> new long[]{now, 0});
        return ++window[1] <= perMinute;
    }
    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        // 이 필터는 Spring의 CORS 처리보다 먼저 응답하므로, 허용 출처에는 직접 헤더를 붙여야 화면이 사유를 읽을 수 있다.
        if (origin.equals(request.getHeader("Origin"))) { response.setHeader("Access-Control-Allow-Origin", origin); response.addHeader("Vary", "Origin"); }
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        if (status == 429) response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
