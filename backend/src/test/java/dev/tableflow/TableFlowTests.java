package dev.tableflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.parser.FileParser;
import dev.tableflow.transform.TableEngine;
import dev.tableflow.export.TableExporter;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TableFlowTests {
    @Autowired FileParser parser;
    @Autowired TableEngine engine;
    @Autowired TableExporter exporter;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private Table csv(String content) throws IOException {
        return parser.parse(new MockMultipartFile("file", "test.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)), null, "UTF-8", ",").tables().getFirst();
    }
    private Table typed(Table table, DataType... types) {
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            Column c = table.columns().get(i);
            columns.add(new Column(c.id(), c.name(), c.targetName(), types[i], false, true, null, null, null));
        }
        return new Table(table.schemaVersion(), table.tableId(), table.name(), table.source(), columns, table.rows());
    }

    @Test void quotedCsvAndPrecisionArePreserved() throws Exception {
        Table t = csv("\uFEFFid,name,amount\r\n001,\"O'Brien, Kim\",12345678901234567890.12\r\n");
        assertEquals("001", t.rows().getFirst().cells().get("col_1").raw());
        assertEquals(DataType.string, t.columns().getFirst().dataType());
        assertEquals(DataType.decimal, t.columns().get(2).dataType());
        assertEquals("O'Brien, Kim", t.rows().getFirst().cells().get("col_2").value());
        assertEquals("12345678901234567890.12", t.rows().getFirst().cells().get("col_3").value());
        assertEquals("15000.00", engine.normalize("15,000.00", DataType.decimal));
        assertThrows(IllegalArgumentException.class, () -> engine.normalize("1,23", DataType.integer));
        assertThrows(IllegalArgumentException.class, () -> engine.normalize("2026-02-30", DataType.date));
        assertEquals("2026-11-18", engine.normalize("2026.11.18", DataType.date));
    }

    @Test void oracleAndMysqlSerializeSafely() throws Exception {
        Table t = typed(csv("name,amount,date,flag\nO'Brien,12345678901234567890.12,2026.11.18,true\n"), DataType.string, DataType.decimal, DataType.date, DataType.bool);
        String oracle = new String(exporter.sql(t, TableExporter.Dialect.oracle, "TB_TEST"), StandardCharsets.UTF_8);
        assertTrue(oracle.contains("'O''Brien'"));
        assertTrue(oracle.contains("12345678901234567890.12"));
        assertTrue(oracle.contains("TO_DATE('2026-11-18', 'YYYY-MM-DD')"));
        String mysql = new String(exporter.sql(t, TableExporter.Dialect.mysql, "TB_TEST"), StandardCharsets.UTF_8);
        assertTrue(mysql.contains("`TB_TEST`")); assertTrue(mysql.contains("CONVERT(X'4f27427269656e' USING utf8mb4)"));
        assertThrows(IllegalArgumentException.class, () -> exporter.sql(t, TableExporter.Dialect.mysql, "X; DROP TABLE Y"));
        Table malicious = csv("name\n\"\\'); DROP TABLE users; --\"\n");
        assertTrue(new String(exporter.sql(malicious, TableExporter.Dialect.mysql, "T"), StandardCharsets.UTF_8).contains("CONVERT(X'"));
    }

    @Test void validationBlocksBadValuesAndDuplicateRows() throws Exception {
        Table t = typed(csv("name,amount,date\n,abc,2026-02-30\nA,2,2026-01-01\nA,2,2026-01-01\n"), DataType.string, DataType.decimal, DataType.date);
        var result = engine.validate(t);
        assertFalse(result.valid()); assertEquals(4, result.issues().size());
        assertThrows(TableExporter.InvalidTable.class, () -> exporter.excel(t));
        Table transform = engine.transform(typed(csv("amount\n\"15,000\"\n"), DataType.decimal));
        assertEquals("15,000", transform.rows().getFirst().cells().get("col_1").raw());
        assertEquals("15000", transform.rows().getFirst().cells().get("col_1").value());
    }

    @Test void excelExportDoesNotExecuteFormulasOrLoseDigits() throws Exception {
        Table t = csv("formula,amount\n=1+1,12345678901234567890\n");
        byte[] output = exporter.excel(t);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            assertEquals(CellType.STRING, workbook.getSheetAt(0).getRow(1).getCell(0).getCellType());
            assertEquals("=1+1", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("12345678901234567890", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
        }
        var parsed = parser.parse(new MockMultipartFile("file", "export.xlsx", "application/octet-stream", output), "TableFlow", "UTF-8", ",");
        assertEquals("=1+1", parsed.tables().getFirst().rows().getFirst().cells().get("col_1").raw());
    }

    @Test void sheetSelectionAndFormulaInput() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            book.createSheet("First").createRow(0).createCell(0).setCellValue("first");
            var sheet = book.createSheet("Second"); sheet.createRow(0).createCell(0).setCellValue("calculation");
            sheet.createRow(1).createCell(0).setCellFormula("1+1"); book.write(out); bytes = out.toByteArray();
        }
        var result = parser.parse(new MockMultipartFile("file", "sheets.xlsx", "application/octet-stream", bytes), "Second", "UTF-8", ",");
        assertEquals(List.of("First", "Second"), result.sheets());
        assertFalse(result.warnings().isEmpty()); assertEquals("=1+1", result.tables().getFirst().rows().getFirst().cells().get("col_1").raw());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", "bad".getBytes()), null, "UTF-8", ","));
    }

    @Test void apiUploadValidateAndBothDownloads() throws Exception {
        var upload = new MockMultipartFile("file", "test.csv", "text/csv", "id,name\n001,Kim\n".getBytes());
        String response = mvc.perform(multipart("/api/v1/files/parse").file(upload)).andExpect(status().isOk())
            .andExpect(jsonPath("$.tables[0].schema_version").value("1.0")).andReturn().getResponse().getContentAsString();
        Table table = json.treeToValue(json.readTree(response).get("tables").get(0), Table.class);
        mvc.perform(post("/api/v1/tables/validate").contentType("application/json").content(json.writeValueAsBytes(table)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v1/exports/sql").contentType("application/json").content(json.writeValueAsBytes(Map.of("table", table, "dbms", "oracle", "tableName", "TB_TEST"))))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=\"tableflow.sql\""));
        mvc.perform(post("/api/v1/exports/excel").contentType("application/json").content(json.writeValueAsBytes(table)))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mvc.perform(post("/api/v1/tables/validate").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void malformedAndExcessiveCsvRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> csv("a\n1,2\n"));
        assertThrows(IllegalArgumentException.class, () -> csv("a\n" + "x\n".repeat(10001)));
        assertThrows(IllegalArgumentException.class, () -> csv("a\n\"unclosed"));
    }

    @Test void nullLengthScaleAndMappingConstraints() throws Exception {
        Table input = csv("a,b\nlong,12.345\n");
        var columns = List.of(
            new Column("col_1", "a", "A", DataType.string, false, true, 2, null, null),
            new Column("col_2", "b", "B", DataType.decimal, false, true, null, 4, 2));
        Table limited = new Table("1.0", "id", "test", input.source(), columns, input.rows());
        assertEquals(2, engine.validate(limited).issues().size());
        Table malformed = new Table("1.0", "id", "test", input.source(), columns,
            List.of(new Table.Row("r", Map.of("unexpected", new Table.Cell("x", "x", null, false)))));
        assertTrue(engine.validate(malformed).issues().stream().anyMatch(i -> i.code().equals("CELL_KEYS")));
        Table optional = new Table("1.0", "id", "test", input.source(),
            List.of(new Column("col_1", "a", "A", DataType.string, true, true, null, null, null)),
            List.of(new Table.Row("r", Map.of("col_1", new Table.Cell("", "", null, false)))));
        assertTrue(new String(exporter.sql(optional, TableExporter.Dialect.oracle, "T"), StandardCharsets.UTF_8).contains("(NULL)"));
    }

    @Test void malformedZipAndMissingUploadReturnClientErrors() throws Exception {
        byte[] bytes;
        try (var output = new ByteArrayOutputStream(); var zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("hello.txt")); zip.write("not an xlsx".getBytes()); zip.closeEntry(); zip.finish(); bytes = output.toByteArray();
        }
        mvc.perform(multipart("/api/v1/files/parse").file(new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", bytes)))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/files/parse")).andExpect(status().isBadRequest());
        Table invalid = typed(csv("name\n\n"), DataType.string);
        // Explicit blank record: Commons CSV skips completely empty lines, so send one missing required value directly.
        invalid = new Table("1.0", "id", "test", invalid.source(), invalid.columns(),
            List.of(new Table.Row("r", Map.of("col_1", new Table.Cell("", "", null, false)))));
        mvc.perform(post("/api/v1/exports/excel").contentType("application/json").content(json.writeValueAsBytes(invalid)))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.issues[0].rowId").value("r"));
    }

    @Test void ocrExportRequiresExplicitReviewHeader() throws Exception {
        Table input = csv("name\nAlice\n");
        Table ocr = new Table("1.0", "id", "image", new Source("table.png", "image", "local_ocr", null, "local"), input.columns(), input.rows());
        mvc.perform(post("/api/v1/exports/excel").contentType("application/json").content(json.writeValueAsBytes(ocr)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/exports/excel").header("X-TableFlow-Reviewed", "true").contentType("application/json").content(json.writeValueAsBytes(ocr)))
            .andExpect(status().isOk());
    }
}
