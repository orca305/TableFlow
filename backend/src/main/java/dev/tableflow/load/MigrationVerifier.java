package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.connection.JdbcReader;
import dev.tableflow.load.MigrationService.Mapping;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.DataType;
import dev.tableflow.transform.TableEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 이관 후 검증: 원본과 대상의 행 수와 컬럼별 집계(NULL이 아닌 값의 수, 숫자 합계·최소·최대, 문자열 길이 합계, 날짜 최소·최대)를 비교한다.
 * 행 단위 해시는 DB마다 값 표현(자릿수·시간대·문자 인코딩)이 달라 오탐이 많고 비용이 커서, 같은 의미로 계산되는 집계만 쓴다. 읽기 전용이다.
 * 변환 규칙이 있는 컬럼은 원본 행을 다시 읽어 규칙과 적재 때의 정규화를 적용한 결과의 집계를 대상과 비교한다.
 */
@Service
public class MigrationVerifier {
    private static final int PAGE = 5000;
    private final JdbcExplorer explorer; private final JdbcReader reader; private final TableEngine engine; private final long maxRows;
    public MigrationVerifier(JdbcExplorer explorer, JdbcReader reader, TableEngine engine, @Value("${tableflow.migration.max-rows:1000000}") long maxRows) {
        this.explorer = explorer; this.reader = reader; this.engine = engine; this.maxRows = Math.max(JdbcReader.MAX_ROWS, maxRows);
    }

    public record ColumnCheck(String source, String target, String measure, String sourceValue, String targetValue, boolean match, String note) {}
    public record Result(long sourceRows, long targetRows, boolean rowsMatch, boolean allMatch, List<ColumnCheck> checks, List<String> notes) {}

    private enum Family { STRING, NUMBER, DATE, DATETIME, OTHER }
    private enum Kind { NOT_NULL, SUM, MIN, MAX, LENGTH }
    private record Measure(Kind kind, Family family) {}

    private static Family family(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> Family.STRING;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> Family.NUMBER;
            case Types.DATE -> Family.DATE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> Family.DATETIME;
            default -> Family.OTHER;
        };
    }
    private static boolean floating(int jdbcType) { return jdbcType == Types.FLOAT || jdbcType == Types.REAL || jdbcType == Types.DOUBLE; }

    /** where: 원본에만 적용하는 행 조건(없으면 null). 대상은 전체 행을 센다. */
    public Result verify(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema, String targetTable,
                         List<Mapping> mappings, String where) {
        Map<String, ColumnInfo> sc = new HashMap<>(), tc = new HashMap<>();
        for (var c : explorer.columns(source, sourceSchema, sourceTable).items()) sc.put(c.name(), c);
        for (var c : explorer.columns(target, targetSchema, targetTable).items()) tc.put(c.name(), c);
        List<String> notes = new ArrayList<>();
        // 컬럼마다 양쪽이 같은 의미로 계산할 수 있는 측정만 고른다.
        List<Mapping> cols = new ArrayList<>(); List<List<Measure>> measures = new ArrayList<>(); List<Mapping> changed = new ArrayList<>();
        for (Mapping m : mappings) {
            ColumnInfo s = sc.get(m.source()), t = tc.get(m.target());
            if (m.transformed() && s != null && t != null) { changed.add(m); continue; }
            if (s == null || t == null) { notes.add("컬럼 " + m.source() + " → " + m.target() + "을(를) 찾지 못해 검증에서 제외했습니다."); continue; }
            Family fs = family(s.jdbcType()), ft = family(t.jdbcType()); List<Measure> list = new ArrayList<>(List.of(new Measure(Kind.NOT_NULL, Family.OTHER)));
            if (fs == Family.NUMBER && ft == Family.NUMBER) list.addAll(List.of(new Measure(Kind.SUM, fs), new Measure(Kind.MIN, fs), new Measure(Kind.MAX, fs)));
            else if (fs == Family.STRING && ft == Family.STRING) list.add(new Measure(Kind.LENGTH, fs));
            else if ((fs == Family.DATE || fs == Family.DATETIME) && (ft == Family.DATE || ft == Family.DATETIME)) {
                list.addAll(List.of(new Measure(Kind.MIN, fs == ft ? fs : Family.DATE), new Measure(Kind.MAX, fs == ft ? fs : Family.DATE)));
                if (fs != ft) notes.add("컬럼 " + m.source() + ": 날짜와 일시를 비교하므로 날짜 부분만 비교합니다.");
            } else if (fs != ft) notes.add("컬럼 " + m.source() + " → " + m.target() + ": 타입이 달라 NULL이 아닌 값의 수만 비교합니다.");
            cols.add(m); measures.add(list);
        }
        Object[] left = aggregate(source, sourceSchema, sourceTable, cols, measures, true, where);
        Object[] right = aggregate(target, targetSchema, targetTable, cols, measures, false, null);
        long sourceRows = ((Number) left[0]).longValue(), targetRows = ((Number) right[0]).longValue();
        boolean rowsMatch = sourceRows == targetRows;
        if (!rowsMatch) notes.add("행 수가 다릅니다. 대상 테이블에 이관 전부터 있던 행이 있거나, 이관이 끝나지 않았을 수 있습니다.");
        List<ColumnCheck> checks = new ArrayList<>(); int idx = 1; boolean all = rowsMatch;
        for (int i = 0; i < cols.size(); i++) {
            Mapping m = cols.get(i); ColumnInfo s = sc.get(m.source()), t = tc.get(m.target());
            for (Measure me : measures.get(i)) {
                Object a = left[idx], b = right[idx]; idx++;
                boolean fl = floating(s.jdbcType()) || floating(t.jdbcType());
                boolean ok = same(a, b, fl);
                if (!ok) all = false;
                checks.add(new ColumnCheck(m.source(), m.target(), label(me.kind()), show(a), show(b), ok, ok ? "" : fl ? "부동소수점 오차 범위를 넘었습니다." : "값이 다릅니다."));
            }
        }
        if (!changed.isEmpty()) {
            if (sourceRows > maxRows) notes.add("원본이 서버 한도(" + maxRows + "행)를 넘어 변환 규칙이 있는 컬럼의 값 비교는 생략했습니다.");
            else for (ColumnCheck check : compareTransformed(source, sourceSchema, sourceTable, target, targetSchema, targetTable, mappings, changed, tc, where, sourceRows, notes)) {
                if (!check.match()) all = false;
                checks.add(check);
            }
        }
        return new Result(sourceRows, targetRows, rowsMatch, all, checks, notes);
    }

    /** 변환 결과 한 컬럼의 누적값. */
    private static final class Tally { long notNull; BigDecimal sum, min, max, length; LocalDateTime first, last; }

    /** 원본을 분할해 읽어 변환 규칙을 적용하고, 대상 컬럼 타입으로 적재될 값의 집계를 만들어 대상 DB의 집계와 비교한다. */
    private List<ColumnCheck> compareTransformed(Credentials source, String sourceSchema, String sourceTable, Credentials target, String targetSchema, String targetTable,
                                                 List<Mapping> mappings, List<Mapping> changed, Map<String, ColumnInfo> tc, String where, long rows, List<String> notes) {
        List<DataType> types = new ArrayList<>(); List<List<Measure>> measures = new ArrayList<>();
        for (Mapping m : changed) {
            ColumnInfo t = tc.get(m.target()); DataType type = MigrationService.transformedType(t.jdbcType(), t.scale()); types.add(type);
            List<Measure> list = new ArrayList<>(List.of(new Measure(Kind.NOT_NULL, Family.OTHER))); Family f = family(t.jdbcType());
            if (type == DataType.integer || type == DataType.decimal) list.addAll(List.of(new Measure(Kind.SUM, Family.NUMBER), new Measure(Kind.MIN, Family.NUMBER), new Measure(Kind.MAX, Family.NUMBER)));
            else if (type == DataType.date || type == DataType.datetime) list.addAll(List.of(new Measure(Kind.MIN, f), new Measure(Kind.MAX, f)));
            else if (f == Family.STRING) {
                if (t.jdbcType() == Types.CHAR || t.jdbcType() == Types.NCHAR) notes.add("컬럼 " + m.target() + ": 고정 길이(CHAR) 컬럼은 공백 채움 때문에 길이를 비교하지 않습니다.");
                else list.add(new Measure(Kind.LENGTH, f));
            }
            measures.add(list);
        }
        List<String> names = MigrationService.readColumns(mappings);
        List<Tally> tallies = new ArrayList<>(); for (int i = 0; i < changed.size(); i++) tallies.add(new Tally());
        boolean keyset = rows > PAGE && reader.hasPrimaryKey(source, sourceSchema, sourceTable);
        JdbcReader.Keyset cursor = new JdbcReader.Keyset(null);
        for (long offset = 0; offset < rows; offset += PAGE) {
            int size = (int) Math.min(PAGE, rows - offset);
            Table page = keyset ? reader.readKeyPage(source, sourceSchema, sourceTable, names, cursor, size, where)
                : reader.readPage(source, sourceSchema, sourceTable, names, offset, size, where);
            if (keyset) cursor = new JdbcReader.Keyset(cursor.last());
            if (page.rows().size() != size) throw new IllegalArgumentException("검증 중 원본 행 수가 바뀌었습니다. 원본이 변하지 않는 상태에서 다시 검증해주세요.");
            for (var row : page.rows()) {
                Map<String, String> values = new HashMap<>();
                for (var column : page.columns()) { var cell = row.cells().get(column.id()); values.put(column.name(), cell == null ? null : cell.value()); }
                for (int i = 0; i < changed.size(); i++) {
                    Mapping m = changed.get(i);
                    String result = MigrationService.fit(ValueTransform.apply(m.steps(), values.get(m.source()), values, m.source()), types.get(i));
                    try { add(tallies.get(i), engine.normalize(result, types.get(i)), types.get(i)); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("컬럼 " + m.source() + ": 변환 결과를 대상 타입으로 바꿀 수 없는 값이 있습니다. " + e.getMessage()); }
                }
            }
        }
        Object[] actual = aggregate(target, targetSchema, targetTable, changed, measures, false, null);
        List<ColumnCheck> checks = new ArrayList<>(); int idx = 1;
        for (int i = 0; i < changed.size(); i++) {
            Mapping m = changed.get(i); Tally t = tallies.get(i); boolean fl = floating(tc.get(m.target()).jdbcType());
            for (Measure me : measures.get(i)) {
                Object a = expected(t, me), b = actual[idx++]; boolean ok = same(a, b, fl);
                checks.add(new ColumnCheck(m.source(), m.target(), label(me.kind()) + " (변환 결과)", show(a), show(b), ok, ok ? "" : "변환 규칙을 적용한 원본 값과 대상 값이 다릅니다."));
            }
            notes.add("컬럼 " + m.source() + " → " + m.target() + ": 원본 값에 변환 규칙을 다시 적용한 결과를 대상과 비교했습니다.");
        }
        return checks;
    }
    private static void add(Tally t, String value, DataType type) {
        if (value == null) return;
        t.notNull++;
        switch (type) {
            case integer, decimal -> {
                BigDecimal n = new BigDecimal(value);
                t.sum = t.sum == null ? n : t.sum.add(n); t.min = t.min == null || n.compareTo(t.min) < 0 ? n : t.min; t.max = t.max == null || n.compareTo(t.max) > 0 ? n : t.max;
            }
            case date, datetime -> {
                LocalDateTime at = type == DataType.date ? java.time.LocalDate.parse(value).atStartOfDay() : LocalDateTime.parse(value);
                if (t.first == null || at.isBefore(t.first)) t.first = at;
                if (t.last == null || at.isAfter(t.last)) t.last = at;
            }
            case string -> { BigDecimal n = BigDecimal.valueOf(value.codePointCount(0, value.length())); t.length = t.length == null ? n : t.length.add(n); }
            case bool -> { }
        }
    }
    private static Object expected(Tally t, Measure m) {
        return switch (m.kind()) {
            case NOT_NULL -> t.notNull;
            case SUM -> t.sum;
            case LENGTH -> t.length;
            case MIN -> m.family() == Family.NUMBER ? t.min : moment(t.first, m.family());
            case MAX -> m.family() == Family.NUMBER ? t.max : moment(t.last, m.family());
        };
    }
    private static Object moment(LocalDateTime v, Family f) { return v == null ? null : f == Family.DATE ? v.toLocalDate().toString() : v.toString(); }

    private static String label(Kind k) {
        return switch (k) { case NOT_NULL -> "값이 있는 행 수"; case SUM -> "합계"; case MIN -> "최소값"; case MAX -> "최대값"; case LENGTH -> "문자열 길이 합계"; };
    }
    private static String show(Object v) {
        if (v == null) return "(없음)";
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return v.toString();
    }
    private static boolean same(Object a, Object b, boolean floating) {
        if (a == null || b == null) return a == b;
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            if (x.compareTo(y) == 0) return true;
            if (!floating) return false;
            double dx = x.doubleValue(), dy = y.doubleValue(), diff = Math.abs(dx - dy);
            return diff <= 1e-9 * Math.max(1.0, Math.max(Math.abs(dx), Math.abs(dy)));
        }
        return a.equals(b);
    }

    /** 결과 배열: [0]=전체 행 수, 이후 컬럼별 측정값을 순서대로. */
    private Object[] aggregate(Credentials c, String schema, String table, List<Mapping> cols, List<List<Measure>> measures, boolean sourceSide, String where) {
        List<String> select = new ArrayList<>(List.of("COUNT(*)")); List<Object[]> kinds = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            String name = JdbcReader.quote(c.dbms(), sourceSide ? cols.get(i).source() : cols.get(i).target());
            for (Measure m : measures.get(i)) {
                select.add(switch (m.kind()) { case NOT_NULL -> "COUNT(" + name + ")"; case SUM -> "SUM(" + name + ")"; case MIN -> "MIN(" + name + ")";
                    case MAX -> "MAX(" + name + ")"; case LENGTH -> "SUM(" + dev.tableflow.connection.SqlDialect.lengthExpression(c.dbms(), name) + ")"; });
                kinds.add(new Object[]{m});
            }
        }
        String filter = dev.tableflow.connection.RowFilter.clean(where);
        String sql = "SELECT " + String.join(", ", select) + " FROM " + JdbcReader.quote(c.dbms(), schema) + "." + JdbcReader.quote(c.dbms(), table) + (filter == null ? "" : " WHERE " + filter);
        return explorer.withConnection(c, true, connection -> {
            try (var st = connection.createStatement()) {
                st.setQueryTimeout(300);
                try (var rs = st.executeQuery(sql)) {
                    rs.next(); Object[] out = new Object[select.size()]; out[0] = rs.getLong(1);
                    for (int i = 1; i < select.size(); i++) {
                        Measure m = (Measure) kinds.get(i - 1)[0];
                        out[i] = switch (m.kind()) {
                            case NOT_NULL -> rs.getLong(i + 1);
                            case SUM, LENGTH -> rs.getBigDecimal(i + 1);
                            case MIN, MAX -> m.family() == Family.NUMBER ? rs.getBigDecimal(i + 1) : timestamp(rs.getTimestamp(i + 1), m.family());
                        };
                    }
                    return out;
                }
            }
        });
    }
    private static Object timestamp(Timestamp t, Family f) {
        if (t == null) return null;
        LocalDateTime v = t.toLocalDateTime();
        return f == Family.DATE ? v.toLocalDate().toString() : v.toString();
    }
}
