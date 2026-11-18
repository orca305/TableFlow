package dev.tableflow.export;

import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.transform.TableEngine;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class TableExporter {
    private final TableEngine engine;
    public TableExporter(TableEngine engine) { this.engine = engine; }

    public enum Dialect {
        oracle('"'), mysql('`');
        private final char quote;
        Dialect(char quote) { this.quote = quote; }
        String identifier(String name) { return quote + name + quote; }
        String literal(String value, DataType type) {
            if (value == null) return "NULL";
            String text = "'" + value.replace("'", "''") + "'";
            // MySQL treats backslash as an escape by default. Hex literals are SQL-mode independent.
            if (this == mysql && type == DataType.string)
                return "CONVERT(X'" + java.util.HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)) + "' USING utf8mb4)";
            return switch (type) {
                case string -> text;
                case integer, decimal -> value;
                case bool -> value.equals("true") ? "1" : "0";
                case date -> this == oracle ? "TO_DATE(" + text + ", 'YYYY-MM-DD')" : "DATE " + text;
                case datetime -> this == oracle
                    ? "TO_TIMESTAMP('" + timestamp(value) + "', 'YYYY-MM-DD HH24:MI:SS.FF9')"
                    : "TIMESTAMP '" + timestamp(value).substring(0, 26) + "'";
            };
        }
        private static String timestamp(String value) {
            return java.time.LocalDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS"));
        }
    }

    public byte[] sql(Table table, Dialect dialect, String tableName) {
        if (!TableEngine.identifier(tableName)) throw new IllegalArgumentException("대상 테이블명이 올바르지 않습니다. 영문/숫자/_ 조합 최대 64자입니다.");
        requireValid(table);
        List<Column> columns = table.columns().stream().filter(Column::included).toList();
        String names = String.join(", ", columns.stream().map(c -> dialect.identifier(c.targetName())).toList());
        StringBuilder sql = new StringBuilder("-- TableFlow | UTF-8 | " + dialect + "\n");
        for (Table.Row row : table.rows()) {
            for (Column c : columns) {
                String value = engine.normalize(row.cells().get(c.id()).value(), c.dataType());
                if (value == null) continue;
                if (dialect == Dialect.mysql && c.dataType() == DataType.datetime && java.time.LocalDateTime.parse(value).getNano() % 1000 != 0)
                    throw new IllegalArgumentException("MySQL 일시는 소수 초 최대 6자리까지 지원합니다.");
                if (dialect == Dialect.mysql && (c.dataType() == DataType.date || c.dataType() == DataType.datetime) && Integer.parseInt(value.substring(0, 4)) < 1000)
                    throw new IllegalArgumentException("MySQL 날짜는 1000년 이상이어야 합니다.");
                if (dialect == Dialect.oracle && c.dataType() == DataType.string && value.getBytes(StandardCharsets.UTF_8).length > 4000)
                    throw new IllegalArgumentException("Oracle 문자열 리터럴은 UTF-8 기준 최대 4,000바이트까지 지원합니다.");
                if ((c.dataType() == DataType.integer || c.dataType() == DataType.decimal) && new java.math.BigDecimal(value).precision() > (dialect == Dialect.oracle ? 38 : 65))
                    throw new IllegalArgumentException("대상 DBMS의 숫자 리터럴 정밀도를 초과했습니다.");
            }
            List<String> literals = columns.stream().map(c -> dialect.literal(engine.normalize(row.cells().get(c.id()).value(), c.dataType()), c.dataType())).toList();
            sql.append("INSERT INTO ").append(dialect.identifier(tableName)).append(" (").append(names).append(") VALUES (")
                .append(String.join(", ", literals)).append(");\n");
        }
        return sql.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] excel(Table table) throws IOException {
        requireValid(table);
        List<Column> columns = table.columns().stream().filter(Column::included).toList();
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("TableFlow");
            var header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) { header.createCell(c).setCellValue(columns.get(c).targetName()); sheet.setColumnWidth(c, 22 * 256); }
            for (int r = 0; r < table.rows().size(); r++) {
                var output = sheet.createRow(r + 1);
                for (int c = 0; c < columns.size(); c++) {
                    Column column = columns.get(c);
                    String value = engine.normalize(table.rows().get(r).cells().get(column.id()).value(), column.dataType());
                    // Write literal strings, never formulas or floating-point numbers (precision loss).
                    if (value != null) output.createCell(c, org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(value);
                }
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out); return out.toByteArray();
        }
    }

    private void requireValid(Table table) {
        ValidationResult result = engine.validate(table);
        if (!result.valid()) throw new InvalidTable(result);
    }
    public static class InvalidTable extends IllegalArgumentException {
        public final ValidationResult result;
        public InvalidTable(ValidationResult result) { super("검증 오류를 수정한 후 다시 출력해주세요."); this.result = result; }
    }
}
