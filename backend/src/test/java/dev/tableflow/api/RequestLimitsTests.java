package dev.tableflow.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class RequestLimitsTests {
    static final String ORIGIN = "http://localhost:3100";

    static MockHttpServletResponse call(RequestLimits limits, String method, String origin) throws Exception {
        var request = new MockHttpServletRequest(method, "/api/v1/connections/test");
        if (origin != null) request.addHeader("Origin", origin);
        var response = new MockHttpServletResponse();
        limits.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test void rejectsOverTheLimitAndLetsTheAllowedScreenReadTheReason() throws Exception {
        var limits = new RequestLimits(ORIGIN, 2);
        assertEquals(200, call(limits, "POST", ORIGIN).getStatus());
        assertEquals(200, call(limits, "POST", ORIGIN).getStatus());
        var rejected = call(limits, "POST", ORIGIN);
        assertEquals(429, rejected.getStatus());
        assertEquals("60", rejected.getHeader("Retry-After"));
        assertEquals(ORIGIN, rejected.getHeader("Access-Control-Allow-Origin"), "CORS 헤더가 없으면 화면은 네트워크 오류로만 본다");
        assertEquals("Origin", rejected.getHeader("Vary"));
        assertTrue(rejected.getContentAsString().contains("RATE_LIMITED"));
        assertEquals(200, call(limits, "GET", ORIGIN).getStatus(), "진행 현황 같은 조회는 제한하지 않는다");
    }

    @Test void doesNotAllowOtherOrigins() throws Exception {
        var limits = new RequestLimits(ORIGIN, 1);
        call(limits, "POST", ORIGIN);
        for (String origin : new String[]{"http://evil.example", null}) {
            var rejected = call(limits, "POST", origin);
            assertEquals(429, rejected.getStatus());
            assertNull(rejected.getHeader("Access-Control-Allow-Origin"));
        }
    }
}
