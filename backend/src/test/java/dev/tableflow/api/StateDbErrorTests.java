package dev.tableflow.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 상태 DB 접속 실패는 503과 안내로 돌려주고 접속 정보는 싣지 않는다. */
class StateDbErrorTests {
    @RestController static class Failing {
        @GetMapping("/down") public String down() { throw new CannotGetJdbcConnectionException("jdbc:postgresql://secret-host/state password=hunter2"); }
        @GetMapping("/slow") public String slow() { throw new QueryTimeoutException("SELECT secret"); }
    }

    @Test void reportsAnUnavailableStateDatabase() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Failing()).setControllerAdvice(new ApiErrors()).build();
        for (String path : new String[]{"/down", "/slow"})
            mvc.perform(get(path)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STATE_DB_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }
}
