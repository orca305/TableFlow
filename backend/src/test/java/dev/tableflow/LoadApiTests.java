package dev.tableflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.model.Table;
import dev.tableflow.parser.FileParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="tableflow.load.enabled=false")
@AutoConfigureMockMvc
class LoadApiTests {
    @Autowired MockMvc mvc;
    @Autowired FileParser parser;
    @Autowired ObjectMapper json;
    @Test void disabledLoadAndExplicitConfirmation() throws Exception {
        mvc.perform(get("/api/v1/loads/target")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        Table table=parser.parse(new MockMultipartFile("file","test.csv","text/csv","name\nAlice\n".getBytes()),null,"UTF-8",",").tables().getFirst();
        var body=new HashMap<String,Object>(Map.of("table",table,"tableName","TB_EMPLOYEE","requestId",UUID.randomUUID().toString(),"confirmed",false));
        mvc.perform(post("/api/v1/loads/database").contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        body.put("confirmed",true);
        mvc.perform(post("/api/v1/loads/database").contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LOAD_DISABLED"));
    }
    @Test void remoteAndCrossOriginCallsAreBlocked() throws Exception {
        mvc.perform(get("/api/v1/loads/target").with(request->{request.setRemoteAddr("192.0.2.10");return request;}).header("X-Forwarded-For","127.0.0.1"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOAD_ACCESS_DENIED"));
        mvc.perform(get("/api/v1/loads/target").header("Origin","https://untrusted.example"))
            .andExpect(status().isForbidden());
    }
}
