package dev.tableflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.DriverCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DriverControllerTests {
    @TempDir Path directory;
    String key = "test-only-admin-key-with-32-characters";
    @Test void disabledByDefaultAndRejectsMissingWrongRemoteAndCrossOriginKeys() throws Exception {
        var catalog = new DriverCatalog(directory.toString(), new ObjectMapper());
        var disabled = MockMvcBuilders.standaloneSetup(new DriverController(catalog, "", "http://localhost:3100")).setControllerAdvice(new ApiErrors()).build();
        disabled.perform(get("/api/v1/driver-catalog").header("X-TableFlow-Admin-Key", key)).andExpect(status().isForbidden());
        var mvc = MockMvcBuilders.standaloneSetup(new DriverController(catalog, key, "http://localhost:3100")).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/v1/driver-catalog")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/driver-catalog").header("X-TableFlow-Admin-Key", "wrong")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/driver-catalog").header("X-TableFlow-Admin-Key", key).header("Origin", "https://other.invalid")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/driver-catalog").header("X-TableFlow-Admin-Key", key).with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/driver-catalog").header("X-TableFlow-Admin-Key", key).header("Origin", "http://localhost:3100")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }
}
