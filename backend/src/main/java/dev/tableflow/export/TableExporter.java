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
        oracle('"'), mysql('`'), postgresql('"'), mariadb('`');
        private final char quote;
        Dialect(char quote) { this.quote = quote; }
        boolean mysqlFamily() { return this == mysql || this == mariadb; }
        int identifierLimit() { return this == postgresql ? 63 : 64; }
        String identifier(String name) { return quote + name + quote; }
        String literal(String value, DataType type) {
            if (value == null) return "NULL";
            String text = "'" + value.replace("'", "''") + "'";
            // MySQL treats backslash as an escape by default. Hex literals are SQL-mode independent.
            if (mysqlFamily() && type == DataType.string)
                return "CONVERT(X'" + java.util.HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)) + "' USING utf8mb4)";
            return switch (type) {
                case string -> this == postgresql ? "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'" : text;
                case integer, decimal -> value;
                case bool -> this == postgresql ? (value.equals("true") ? "TRUE" : "FALSE") : (value.equals("true") ? "1" : "0");
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

    public byte[] sql(Table table, Dialect dialect, String tableName) { return sql(table, dialect, tableName, TableEngine.DuplicateMode.strict); }
    public byte[] sql(Table table, Dialect dialect, String tableName, TableEngine.DuplicateMode mode) {
        if (!TableEngine.identifier(tableName)) throw new IllegalArgumentException("대상 테이블명이 올바르지 않습니다. 영문/숫자/_ 조합 최대 64자입니다.");
        final Table output = outputTable(table, mode);
        List<Column> columns = output.columns().stream().filter(Column::included).toList();
        if (tableName.length() > dialect.identifierLimit() || columns.stream().anyMatch(column -> column.targetName().length() > dialect.identifierLimit()))
            throw new IllegalArgumentException("선택한 DBMS의 식별자 길이 제한을 초과했습니다: " + dialect.identifierLimit() + "자");
        String names = String.join(", ", columns.stream().map(c -> dialect.identifier(c.targetName())).toList());
        StringBuilder sql = new StringBuilder("-- TableFlow | UTF-8 | " + dialect + "\n");
        for (Table.Row row : output.rows()) {
            for (Column c : columns) {
                String value = engine.normalize(row.cells().get(c.id()).value(), c.dataType());
                if (value == null) continue;
                if ((dialect.mysqlFamily() || dialect == Dialect.postgresql) && c.dataType() == DataType.datetime && java.time.LocalDateTime.parse(value).getNano() % 1000 != 0)
                    throw new IllegalArgumentException("MySQL/MariaDB/PostgreSQL 일시는 소수 초 최대 6자리까지 지원합니다.");
                if (dialect.mysqlFamily() && (c.dataType() == DataType.date || c.dataType() == DataType.datetime) && Integer.parseInt(value.substring(0, 4)) < 1000)
                    throw new IllegalArgumentException("MySQL/MariaDB 날짜는 1000년 이상이어야 합니다.");
                if (dialect == Dialect.postgresql && c.dataType() == DataType.string && value.indexOf(0) >= 0)
                    throw new IllegalArgumentException("PostgreSQL 문자열에는 NUL 문자를 포함할 수 없습니다.");
                if (dialect == Dialect.oracle && c.dataType() == DataType.string && value.getBytes(StandardCharsets.UTF_8).length > 4000)
                    throw new IllegalArgumentException("Oracle 문자열 리터럴은 UTF-8 기준 최대 4,000바이트까지 지원합니다.");
                if ((c.dataType() == DataType.integer || c.dataType() == DataType.decimal) && new java.math.BigDecimal(value).precision() > (dialect == Dialect.oracle ? 38 : dialect == Dialect.postgresql ? 1000 : 65))
                    throw new IllegalArgumentException("대상 DBMS의 숫자 리터럴 정밀도를 초과했습니다.");
            }
            List<String> literals = columns.stream().map(c -> dialect.literal(engine.normalize(row.cells().get(c.id()).value(), c.dataType()), c.dataType())).toList();
            sql.append("INSERT INTO ").append(dialect.identifier(tableName)).append(" (").append(names).append(") VALUES (")
                .append(String.join(", ", literals)).append(");\n");
        }
        return sql.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] excel(Table table) throws IOException { return excel(table, TableEngine.DuplicateMode.strict); }
    public byte[] excel(Table table, TableEngine.DuplicateMode mode) throws IOException {
        final Table output = outputTable(table, mode);
        List<Column> columns = output.columns().stream().filter(Column::included).toList();
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("TableFlow");
            var header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) { header.createCell(c).setCellValue(columns.get(c).targetName()); sheet.setColumnWidth(c, 22 * 256); }
            for (int r = 0; r < output.rows().size(); r++) {
                var outputRow = sheet.createRow(r + 1);
                for (int c = 0; c < columns.size(); c++) {
                    Column column = columns.get(c);
                    String value = engine.normalize(output.rows().get(r).cells().get(column.id()).value(), column.dataType());
                    // Write literal strings, never formulas or floating-point numbers (precision loss).
                    if (value != null) outputRow.createCell(c, org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(value);
                }
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out); return out.toByteArray();
        }
    }

    public byte[] csv(Table table, TableEngine.DuplicateMode mode) throws IOException {
        final Table output = outputTable(table, mode);
        List<Column> columns = output.columns().stream().filter(Column::included).toList();
        try (var out = new java.io.StringWriter(); var printer = new org.apache.commons.csv.CSVPrinter(out, org.apache.commons.csv.CSVFormat.RFC4180)) {
            printer.printRecord(columns.stream().map(column -> safeCsv(column.targetName())).toList());
            for (Table.Row row : output.rows()) {
                printer.printRecord(columns.stream().map(column -> safeCsv(engine.normalize(row.cells().get(column.id()).value(), column.dataType()))).toList());
            }
            printer.flush();
            return ("\uFEFF" + out).getBytes(StandardCharsets.UTF_8);
        }
    }
    private String safeCsv(String value) {
        if (value == null) return "";
        String trimmed = value.stripLeading();
        // CSV has no literal-string cell type. Prefix spreadsheet formulas explicitly.
        if (trimmed.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) return value;
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") || trimmed.startsWith("@") || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) return "'" + value;
        return value;
    }

    public Table outputTable(Table table, TableEngine.DuplicateMode mode) {
        Table normalized = engine.transform(table, mode);
        var result = engine.validate(normalized, mode);
        if (!result.valid()) throw new InvalidTable(result);
        return normalized;
    }
    public static class InvalidTable extends IllegalArgumentException {
        public final ValidationResult result;
        public InvalidTable(ValidationResult result) { super("검증 오류를 수정한 후 다시 출력해주세요."); this.result = result; }
    }
}
