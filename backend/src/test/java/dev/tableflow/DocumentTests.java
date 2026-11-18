package dev.tableflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.ocr.OcrClient;
import dev.tableflow.parser.DocumentParser;
import dev.tableflow.transform.TableEngine;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DocumentTests {
    @Autowired DocumentParser parser;
    @Autowired ObjectMapper json;
    @Autowired Validator validator;
    @Autowired TableEngine engine;

    @Test void textPdfSupportsPageSelectionWithoutPython() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("../frontend/public/samples/table-text.pdf"));
        var file = new MockMultipartFile("file", "sample.pdf", "application/pdf", bytes);
        var all = parser.parse(file, null, "UTF-8", ",", 0);
        assertEquals(2, all.tables().size()); assertEquals("pdf_text", all.tables().getFirst().source().extractionMethod());
        assertEquals("Alice", all.tables().getFirst().rows().getFirst().cells().get("col_1").value());
        assertEquals("Bob", parser.parse(file, null, "UTF-8", ",", 2).tables().getFirst().rows().getFirst().cells().get("col_1").value());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(file, null, "UTF-8", ",", 3));
    }

    @Test void malformedSignatureRejectedBeforeOcr() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new MockMultipartFile("file", "fake.png", "image/png", new byte[]{1, 2, 3}), null, "UTF-8", ",", 0));
    }

    @Test void ocrHttpContractAndMalformedResponse() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> payload = new java.util.concurrent.atomic.AtomicReference<>("{}");
        server.createContext("/internal/v1/ocr/extract", exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] response = payload.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            var client = new OcrClient("http://127.0.0.1:" + server.getAddress().getPort(), 1, json, validator, engine);
            assertEquals(502, assertThrows(OcrClient.OcrFailure.class, () -> client.extract(new byte[]{1}, "test.png", "")).status);
            payload.set("not json");
            assertEquals(502, assertThrows(OcrClient.OcrFailure.class, () -> client.extract(new byte[]{1}, "test.png", "")).status);
            var table = new Table("1.0", "id", "test", new Source("fake.png", "image", "local_ocr", null, "local"),
                List.of(new Column("col_1", "Name", "NAME", DataType.string, true, true, null, null, null)),
                List.of(new Table.Row("r", Map.of("col_1", new Table.Cell("Alice", "Alice", .9, false)))));
            payload.set(json.writeValueAsString(new ParseResult(List.of(table), List.of(), List.of())));
            assertEquals("original.png", client.extract(new byte[]{1}, "original.png", "").tables().getFirst().source().filename());
        } finally { server.stop(0); }
    }

    @Test void ocrTimeoutAndUnavailableAreExplicit() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/ocr/extract", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(1600); exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes()); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            var client = new OcrClient("http://127.0.0.1:" + port, 1, json, validator, engine);
            assertEquals(504, assertThrows(OcrClient.OcrFailure.class, () -> client.extract(new byte[]{1}, "x.png", "")).status);
        } finally { server.stop(0); }
        var disconnected = new OcrClient("http://127.0.0.1:" + port, 1, json, validator, engine);
        assertEquals(503, assertThrows(OcrClient.OcrFailure.class, () -> disconnected.extract(new byte[]{1}, "x.png", "")).status);
    }
}
