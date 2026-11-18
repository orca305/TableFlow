package dev.tableflow.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
public class RequestLimits extends OncePerRequestFilter {
    private final Semaphore slots = new Semaphore(4);
    private final Map<String, long[]> requests = new LinkedHashMap<>();
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/v1/") || !request.getMethod().equals("POST")) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > 32L * 1024 * 1024) { reject(response, 413, "REQUEST_TOO_LARGE", "요청 크기는 최대 32MB입니다."); return; }
        if (!allow(request.getRemoteAddr()) || !slots.tryAcquire()) { reject(response, 429, "RATE_LIMITED", "요청이 많습니다. 잠시 후 다시 시도해주세요."); return; }
        try { chain.doFilter(request, response); } finally { slots.release(); }
    }
    private synchronized boolean allow(String address) {
        long now = System.currentTimeMillis();
        requests.entrySet().removeIf(entry -> now - entry.getValue()[0] >= 60000);
        if (!requests.containsKey(address) && requests.size() >= 1024) return false;
        long[] window = requests.computeIfAbsent(address, key -> new long[]{now, 0});
        return ++window[1] <= 120;
    }
    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        if (status == 429) response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
