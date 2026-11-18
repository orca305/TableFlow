package dev.tableflow.parser;

import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.transform.TableEngine;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipInputStream;

@Service
public class FileParser {
    private final TableEngine engine;
    public FileParser(TableEngine engine) { this.engine = engine; }
    public ParseResult parse(MultipartFile file, String sheetName, String encoding, String delimiter) throws IOException {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw bad("파일 크기는 1바이트~10MB여야 합니다.");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("upload").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.length() > 255) throw bad("파일명이 너무 깁니다.");
        byte[] bytes = file.getBytes();
        if (filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            try { return excel(bytes, filename, sheetName); }
            catch (IllegalArgumentException e) { throw e; }
            catch (RuntimeException e) { throw bad("XLSX 내부 구조가 올바르지 않습니다. 파일 손상 여부를 확인해주세요."); }
        }
        if (filename.toLowerCase(Locale.ROOT).endsWith(".csv")) return csv(bytes, filename, encoding, delimiter);
        throw bad("현재는 XLSX, CSV 파일을 지원합니다.");
    }

    private ParseResult csv(byte[] bytes, String filename, String encoding, String delimiter) throws IOException {
        if (!Set.of("UTF-8", "MS949", "EUC-KR").contains(encoding)) throw bad("지원하지 않는 CSV 인코딩입니다.");
        if (!Set.of(",", ";", "\t", "|").contains(delimiter)) throw bad("지원하지 않는 구분자입니다.");
        if ((bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K') || (bytes[0] & 255) == 0xD0)
            throw bad("CSV 파일 시그니처가 올바르지 않습니다.");
        String text;
        try { text = Charset.forName(encoding).newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw bad("CSV 인코딩을 확인해주세요."); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.chars().anyMatch(c -> c == 0 || (c < 32 && c != '\r' && c != '\n' && c != '\t')))
            throw bad("CSV에 바이너리 데이터가 포함되어 있습니다.");
        List<List<String>> records = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setDelimiter(delimiter.charAt(0)).get().parse(new StringReader(text))) {
            for (CSVRecord row : parser) {
                if (records.size() >= 10001 || row.size() > 100) throw bad("최대 10,000행, 100열까지 지원합니다.");
                List<String> values = new ArrayList<>();
                row.forEach(v -> { checkCell(v); values.add(v); });
                records.add(values);
            }
        } catch (UncheckedIOException e) { throw bad("CSV 인용부호 또는 파일 형식을 확인해주세요."); }
        return new ParseResult(List.of(table(records, filename, "csv", filename)), List.of(), List.of());
    }

    private ParseResult excel(byte[] bytes, String filename, String sheetName) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4)
            throw bad("XLSX 파일 시그니처가 올바르지 않습니다.");
        // Bound expanded ZIP content before POI constructs its in-memory workbook.
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            long expanded = 0; int entries = 0; byte[] buffer = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > 2000) throw bad("XLSX 내부 항목 수가 너무 많습니다.");
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 32L * 1024 * 1024) throw bad("XLSX 압축 해제 크기가 32MB를 초과합니다.");
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> sheets = new ArrayList<>();
            for (int i = 0; i < book.getNumberOfSheets(); i++) sheets.add(book.getSheetName(i));
            if (sheets.isEmpty()) throw bad("시트가 없습니다.");
            Sheet sheet = sheetName == null || sheetName.isBlank() ? book.getSheetAt(0) : book.getSheet(sheetName);
            if (sheet == null) throw bad("선택한 시트가 없습니다.");
            if (sheet.getLastRowNum() > 10000) throw bad("최대 10,000행까지 지원합니다.");
            List<List<String>> records = new ArrayList<>();
            DataFormatter format = new DataFormatter(Locale.ROOT);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                List<String> values = new ArrayList<>();
                if (row != null) {
                    if (row.getLastCellNum() > 100) throw bad("최대 100열까지 지원합니다.");
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                        String value = "";
                        if (cell != null) {
                            if (cell.getCellType() == CellType.FORMULA) {
                                value = "=" + cell.getCellFormula();
                                if (warnings.isEmpty()) warnings.add("수식은 실행하지 않고 원문 텍스트로 가져왔습니다.");
                            } else if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                                var date = cell.getLocalDateTimeCellValue();
                                value = date.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                                    ? date.toLocalDate().toString() : date.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                            } else value = format.formatCellValue(cell);
                        }
                        checkCell(value); values.add(value);
                    }
                }
                records.add(values);
            }
            if (sheet.getNumMergedRegions() > 0) warnings.add("병합 셀은 왼쪽 위 셀의 값만 가져옵니다.");
            return new ParseResult(List.of(table(records, filename, "xlsx", sheet.getSheetName())), warnings, sheets);
        }
    }

    Table table(List<List<String>> records, String filename, String type, String name) {
        if (records.isEmpty() || records.getFirst().isEmpty()) throw bad("첫 행에 컬럼 헤더가 필요합니다.");
        int width = records.getFirst().size();
        if (records.size() > 10001 || width > 100) throw bad("최대 10,000행, 100열까지 지원합니다.");
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            String header = records.getFirst().get(i).strip();
            if (header.isBlank()) header = "컬럼 " + (i + 1);
            if (header.length() > 200) throw bad("컬럼명은 200자 이하여야 합니다.");
            columns.add(new Column("col_" + (i + 1), header, "COL_" + (i + 1), inferType(records, i), true, true, null, null, null));
        }
        List<Table.Row> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            var values = records.get(r);
            if (values.size() > width) throw bad("헤더보다 많은 열이 있는 행: " + (r + 1));
            Map<String, Table.Cell> cells = new LinkedHashMap<>();
            for (int c = 0; c < width; c++) {
                String raw = c < values.size() ? values.get(c) : "";
                checkCell(raw);
                cells.put(columns.get(c).id(), new Table.Cell(raw, raw, null, false));
            }
            rows.add(new Table.Row("row_" + r, cells));
        }
        return new Table("1.0", UUID.randomUUID().toString(), name, new Source(filename, type, "parser"), columns, rows);
    }

    private DataType inferType(List<List<String>> records, int column) {
        List<String> values = records.stream().skip(1).filter(row -> column < row.size())
            .map(row -> row.get(column).strip()).filter(value -> !value.isEmpty()).toList();
        if (values.isEmpty() || values.stream().anyMatch(value -> value.matches("[+-]?0[0-9].*"))) return DataType.string;
        for (DataType candidate : List.of(DataType.integer, DataType.decimal, DataType.date, DataType.datetime, DataType.bool)) {
            if (candidate == DataType.integer && values.stream().anyMatch(value -> value.contains("."))) continue;
            boolean matches = true;
            for (String value : values) {
                try { engine.normalize(value, candidate); }
                catch (IllegalArgumentException e) { matches = false; break; }
            }
            if (matches) return candidate;
        }
        return DataType.string;
    }

    private static void checkCell(String value) { if (value.length() > 32767) throw bad("셀 값은 최대 32,767자까지 지원합니다."); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
