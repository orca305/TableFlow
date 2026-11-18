package dev.tableflow.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.transform.TableEngine;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;

@Service
public class OcrClient {
    private final RestClient client;
    private final ObjectMapper json;
    private final Validator validator;
    private final TableEngine engine;
    public OcrClient(@Value("${tableflow.ocr-url}") String url,
                     @Value("${tableflow.ocr-timeout-seconds}") int timeout, ObjectMapper json, Validator validator, TableEngine engine) {
        this.json = json; this.validator = validator; this.engine = engine;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(Math.clamp(timeout, 1, 300) * 1000);
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public ParseResult extract(byte[] data, String filename, String pages) {
        return extract(data, filename, pages, false);
    }
    public ParseResult extract(byte[] data, String filename, String pages, boolean externalConsent) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(data) { @Override public String getFilename() { return filename; } });
        form.add("pages", pages);
        form.add("external_upload_consent", externalConsent);
        try {
            ParseResult result = client.post().uri("/internal/v1/ocr/extract").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form).exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        int status = response.getStatusCode().value();
                        if (status == 504) throw new OcrFailure(504, "OCR_TIMEOUT", "OCR 처리 시간이 초과되었습니다.");
                        if (status == 403) throw new OcrFailure(403, "OCR_CONSENT_REQUIRED", "외부 OCR 전송 설정에서 이번 파일의 전송 동의가 필요합니다.");
                        if (status == 400 || status == 413 || status == 422)
                            throw new OcrFailure(422, "OCR_EXTRACTION_FAILED", "표를 추출할 수 없습니다. 선명한 표 이미지와 페이지 설정을 확인해주세요.");
                        throw new OcrFailure(503, "OCR_UNAVAILABLE", "OCR 서비스를 사용할 수 없습니다. 공급자 설정과 실행 상태를 확인해주세요.");
                    }
                    byte[] bytes = response.getBody().readNBytes(32 * 1024 * 1024 + 1);
                    if (bytes.length > 32 * 1024 * 1024) throw new OcrFailure(502, "OCR_INVALID_RESPONSE", "OCR 서비스 응답 크기가 너무 큽니다.");
                    try { return json.readValue(bytes, ParseResult.class); }
                    catch (IOException e) { throw new OcrFailure(502, "OCR_INVALID_RESPONSE", "OCR 서비스 응답 형식이 올바르지 않습니다."); }
                });
            if (result == null || result.tables() == null || result.tables().isEmpty() || result.tables().size() > 20 ||
                result.warnings() == null || result.sheets() == null) throw new IOException("Invalid OCR result");
            if (result.warnings().size() > 100 || result.warnings().stream().anyMatch(warning -> warning == null || warning.length() > 1000))
                throw new IOException("Invalid OCR warnings");
            List<Table> tables = new ArrayList<>();
            for (Table table : result.tables()) {
                if (table == null) throw new IOException("Invalid OCR table");
                if (!validator.validate(table).isEmpty() || table.rows().isEmpty() || engine.validate(table).issues().stream()
                    .anyMatch(issue -> Set.of("CELL_KEYS", "DUPLICATE_ID").contains(issue.code()))) throw new IOException("Invalid OCR table");
                String method = table.source().extractionMethod();
                if (!Set.of("mock_ocr", "local_ocr", "external_ocr").contains(method)) throw new IOException("Invalid OCR source");
                if (!method.equals(table.source().provider() + "_ocr") ||
                    (filename.toLowerCase(Locale.ROOT).endsWith(".pdf") == (table.source().page() == null))) throw new IOException("Invalid OCR provenance");
                Source source = new Source(filename, filename.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "pdf" : "image",
                    method, table.source().page(), table.source().provider());
                tables.add(new Table(table.schemaVersion(), table.tableId(), table.name(), source, table.columns(), table.rows()));
            }
            return new ParseResult(tables, result.warnings(), List.of());
        } catch (OcrFailure e) { throw e; }
        catch (ResourceAccessException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof SocketTimeoutException) throw new OcrFailure(504, "OCR_TIMEOUT", "OCR 처리 시간이 초과되었습니다.");
            throw new OcrFailure(503, "OCR_UNAVAILABLE", "OCR 서비스에 연결할 수 없습니다. Python 서비스를 실행해주세요.");
        } catch (RestClientException | IOException e) {
            throw new OcrFailure(502, "OCR_INVALID_RESPONSE", "OCR 서비스 응답이 올바르지 않습니다.");
        }
    }
    public static class OcrFailure extends RuntimeException {
        public final int status; public final String code;
        public OcrFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
