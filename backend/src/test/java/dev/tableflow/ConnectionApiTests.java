package dev.tableflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="tableflow.jdbc.enabled=false")
@AutoConfigureMockMvc
class ConnectionApiTests {
    @Autowired MockMvc mvc;
    String body = """
        {"dbms":"mysql","host":"localhost","port":3306,"database":"sample","username":"reader","password":"secret","tls":true}
        """;
    @Test void driverRegistryAndDisabledConnectAreExplicit() throws Exception {
        mvc.perform(get("/api/v1/connections/drivers")).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.drivers.length()").value(7));
        mvc.perform(post("/api/v1/connections/test").contentType("application/json").content(body))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("JDBC_DISABLED"));
    }
    @Test void remoteAndCrossOriginAndUrlInjectionAreRejected() throws Exception {
        mvc.perform(get("/api/v1/connections/drivers").with(r->{r.setRemoteAddr("192.0.2.10");return r;}).header("X-Forwarded-For","127.0.0.1"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/connections/drivers").header("Origin","https://example.invalid")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/connections/test").contentType("application/json").content(body.replace("localhost", "localhost?socketFactory=evil")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Autowired dev.tableflow.parser.FileParser parser;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Test void actualLoadRequiresOptInConfirmationAndOcrReview() throws Exception {
        var table=parser.parse(new org.springframework.mock.web.MockMultipartFile("file","sample.csv","text/csv","name\nAlice\n".getBytes()),null,"UTF-8",",").tables().getFirst();
        var payload=new java.util.HashMap<String,Object>();payload.put("connection",json.readTree(body));payload.put("table",table);payload.put("schema","public");payload.put("tableName","sample");
        mvc.perform(post("/api/v1/connections/load/check").contentType("application/json").content(json.writeValueAsBytes(payload)))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("JDBC_LOAD_DISABLED"));
        mvc.perform(post("/api/v1/connections/load").contentType("application/json").content(json.writeValueAsBytes(payload))).andExpect(status().isBadRequest());
        payload.put("confirmed",true);payload.put("requestId",java.util.UUID.randomUUID().toString());
        var ocr=new dev.tableflow.model.Table(table.schemaVersion(),table.tableId(),table.name(),new dev.tableflow.model.Table.Source("a.png","image","local_ocr"),table.columns(),table.rows());payload.put("table",ocr);
        mvc.perform(post("/api/v1/connections/load").contentType("application/json").content(json.writeValueAsBytes(payload))).andExpect(status().isBadRequest());
    }
}
