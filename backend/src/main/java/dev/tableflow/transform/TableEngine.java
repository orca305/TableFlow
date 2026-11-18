package dev.tableflow.transform;

import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TableEngine {
    public static boolean identifier(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"); }

    public String normalize(String value, DataType type) {
        if (value == null || value.isBlank()) return null;
        String s = value.strip();
        return switch (type) {
            case string -> value;
            case integer, decimal -> {
                if (!s.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?"))
                    throw new IllegalArgumentException("숫자 형식이 올바르지 않습니다.");
                BigDecimal n = new BigDecimal(s.replace(",", ""));
                if (type == DataType.integer) {
                    try { yield n.toBigIntegerExact().toString(); }
                    catch (ArithmeticException e) { throw new IllegalArgumentException("정수만 입력할 수 있습니다."); }
                }
                yield n.toPlainString();
            }
            case date -> {
                try {
                    LocalDate date = LocalDate.parse(s.replace('.', '-').replace('/', '-'), DateTimeFormatter.ISO_LOCAL_DATE);
                    if (date.getYear() < 1 || date.getYear() > 9999) throw new DateTimeException("year");
                    yield date.toString();
                }
                catch (DateTimeException e) { throw new IllegalArgumentException("날짜는 YYYY-MM-DD 형식이어야 합니다."); }
            }
            case datetime -> {
                try {
                    LocalDateTime date = LocalDateTime.parse(s.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    if (date.getYear() < 1 || date.getYear() > 9999) throw new DateTimeException("year");
                    yield date.toString();
                }
                catch (DateTimeException e) { throw new IllegalArgumentException("일시는 YYYY-MM-DDTHH:mm:ss 형식이어야 합니다."); }
            }
            case bool -> switch (s.toLowerCase(Locale.ROOT)) {
                case "true", "1" -> "true";
                case "false", "0" -> "false";
                default -> throw new IllegalArgumentException("불리언은 true/false 또는 1/0이어야 합니다.");
            };
        };
    }

    public ValidationResult validate(Table table) {
        List<Issue> issues = new ArrayList<>();
        Set<String> ids = new HashSet<>(), targets = new HashSet<>(), rowIds = new HashSet<>();
        List<Column> included = table.columns().stream().filter(Column::included).toList();
        if (included.isEmpty()) issues.add(new Issue(null, null, "NO_COLUMNS", "출력할 컬럼을 하나 이상 선택해주세요."));
        for (Column c : table.columns()) {
            if (!ids.add(c.id())) issues.add(new Issue(null, c.id(), "DUPLICATE_ID", "컬럼 ID가 중복됩니다."));
            if (!c.included()) continue;
            if (!identifier(c.targetName())) issues.add(new Issue(null, c.id(), "IDENTIFIER", "대상 컬럼명은 영문/숫자/_ 조합(최대 64자)이며 숫자로 시작할 수 없습니다."));
            if (!targets.add(c.targetName().toUpperCase(Locale.ROOT))) issues.add(new Issue(null, c.id(), "DUPLICATE_COLUMN", "대상 컬럼명이 중복됩니다."));
            if (c.precision() != null && c.scale() != null && c.scale() > c.precision())
                issues.add(new Issue(null, c.id(), "PRECISION", "소수 자릿수는 전체 정밀도 이하여야 합니다."));
        }
        Set<List<String>> seen = new HashSet<>();
        for (Table.Row row : table.rows()) {
            if (issues.size() >= 1000) break;
            if (!rowIds.add(row.id())) issues.add(new Issue(row.id(), null, "DUPLICATE_ID", "행 ID가 중복됩니다."));
            if (!row.cells().keySet().equals(ids)) issues.add(new Issue(row.id(), null, "CELL_KEYS", "행의 셀 ID가 컬럼 ID와 일치하지 않습니다."));
            List<String> normalized = new ArrayList<>();
            boolean rowValid = true;
            for (Column c : included) {
                Table.Cell cell = row.cells().get(c.id());
                try {
                    String value = normalize(cell == null ? null : cell.value(), c.dataType());
                    normalized.add(value);
                    if (value == null && !c.nullable()) throw new IllegalArgumentException("필수값입니다.");
                    if (value != null && c.maxLength() != null && value.codePointCount(0, value.length()) > c.maxLength())
                        throw new IllegalArgumentException("최대 문자열 길이를 초과했습니다.");
                    if (value != null && (c.dataType() == DataType.decimal || c.dataType() == DataType.integer)) {
                        BigDecimal n = new BigDecimal(value);
                        int fraction = Math.max(0, n.scale());
                        int integral = Math.max(0, n.precision() - n.scale());
                        if (c.scale() != null && fraction > c.scale()) throw new IllegalArgumentException("소수 자릿수를 초과했습니다.");
                        if (c.precision() != null && (integral + fraction > c.precision() ||
                            (c.scale() != null && integral > c.precision() - c.scale())))
                            throw new IllegalArgumentException("숫자 정밀도를 초과했습니다.");
                    }
                } catch (IllegalArgumentException e) {
                    issues.add(new Issue(row.id(), c.id(), "VALUE", e.getMessage())); rowValid = false;
                }
            }
            if (rowValid && !seen.add(normalized)) issues.add(new Issue(row.id(), null, "DUPLICATE_ROW", "포함된 컬럼 기준으로 중복된 행입니다."));
        }
        return new ValidationResult(issues.isEmpty(), issues.size() > 1000 ? issues.subList(0, 1000) : issues);
    }

    public Table transform(Table table) {
        List<Table.Row> rows = table.rows().stream().map(row -> {
            Map<String, Table.Cell> cells = new LinkedHashMap<>(row.cells());
            for (Column c : table.columns()) {
                Table.Cell cell = cells.get(c.id());
                if (cell == null || !c.included()) continue;
                try { cells.put(c.id(), new Table.Cell(cell.raw(), normalize(cell.value(), c.dataType()), cell.confidence(), cell.edited())); }
                catch (IllegalArgumentException ignored) { /* Retain invalid values for user correction. */ }
            }
            return new Table.Row(row.id(), cells);
        }).toList();
        return new Table(table.schemaVersion(), table.tableId(), table.name(), table.source(), table.columns(), rows);
    }
}
