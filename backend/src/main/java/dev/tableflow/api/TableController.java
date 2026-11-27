package dev.tableflow.api;

import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.parser.DocumentParser;
import dev.tableflow.transform.TableEngine;
import dev.tableflow.export.TableExporter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TableController {
    private final DocumentParser parser;
    private final TableEngine engine;
    private final TableExporter exporter;
    private final dev.tableflow.state.StateDbHealth stateDb;
    public TableController(DocumentParser parser, TableEngine engine, TableExporter exporter, dev.tableflow.state.StateDbHealth stateDb) {
        this.parser = parser; this.engine = engine; this.exporter = exporter; this.stateDb = stateDb;
    }
    /** 서버와 상태 DB의 상태. 상태 DB를 쓸 수 없으면 503이다(접속 정보는 싣지 않는다). */
    @GetMapping("/health") public ResponseEntity<Map<String, String>> health() {
        boolean up = stateDb.available();
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("status", up ? "ok" : "unavailable", "version", "0.1.0", "stateDb", up ? "ok" : "unavailable"));
    }

    @PostMapping(value="/files/parse", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ParseResult parse(@RequestParam MultipartFile file, @RequestParam(required=false) String sheet,
                             @RequestParam(defaultValue="UTF-8") String encoding,
                             @RequestParam(defaultValue=",") String delimiter, @RequestParam(defaultValue="0") int page,
                             @RequestParam(defaultValue="false") boolean externalConsent, @RequestParam(defaultValue="1") int headerRow) throws IOException {
        return parser.parse(file, sheet, encoding, delimiter, page, externalConsent, headerRow);
    }
    @PostMapping("/tables/transform") public Table transform(@Valid @RequestBody Table table, @RequestParam(defaultValue="strict") TableEngine.DuplicateMode duplicateMode) { return engine.transform(table, duplicateMode); }
    @PostMapping("/tables/validate") public ValidationResult validate(@Valid @RequestBody Table table, @RequestParam(defaultValue="strict") TableEngine.DuplicateMode duplicateMode) { return engine.validate(table, duplicateMode); }

    public record SqlRequest(@NotNull @Valid Table table, @NotNull TableExporter.Dialect dbms,
                             @NotBlank @Size(max=64) String tableName) {}
    @PostMapping("/exports/sql") public ResponseEntity<byte[]> sql(@Valid @RequestBody SqlRequest request, @RequestParam(defaultValue="strict") TableEngine.DuplicateMode duplicateMode, @RequestHeader(value="X-TableFlow-Reviewed", defaultValue="false") boolean reviewed) {
        requireReview(request.table(), reviewed);
        return download(exporter.sql(request.table(), request.dbms(), request.tableName(), duplicateMode), "application/sql", "tableflow.sql");
    }
    @PostMapping("/exports/excel") public ResponseEntity<byte[]> excel(@Valid @RequestBody Table table, @RequestParam(defaultValue="strict") TableEngine.DuplicateMode duplicateMode, @RequestHeader(value="X-TableFlow-Reviewed", defaultValue="false") boolean reviewed) throws IOException {
        requireReview(table, reviewed);
        return download(exporter.excel(table, duplicateMode), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "tableflow.xlsx");
    }
    @PostMapping("/exports/csv") public ResponseEntity<byte[]> csv(@Valid @RequestBody Table table,
            @RequestParam(defaultValue="strict") TableEngine.DuplicateMode duplicateMode,
            @RequestHeader(value="X-TableFlow-Reviewed", defaultValue="false") boolean reviewed) throws IOException {
        requireReview(table, reviewed);
        return download(exporter.csv(table, duplicateMode), "text/csv;charset=UTF-8", "tableflow.csv");
    }
    private void requireReview(Table table, boolean reviewed) {
        if (table.source().extractionMethod().contains("ocr") && !reviewed)
            throw new IllegalArgumentException("OCR 결과를 원본과 대조한 후 검토 확인을 해주세요.");
    }
    private ResponseEntity<byte[]> download(byte[] data, String type, String filename) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(data);
    }
}
