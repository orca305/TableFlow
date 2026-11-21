package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.connection.JdbcReader;
import dev.tableflow.load.MigrationService.Mapping;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 이관 후 검증: 원본과 대상의 행 수와 컬럼별 집계(NULL이 아닌 값의 수, 숫자 합계·최소·최대, 문자열 길이 합계, 날짜 최소·최대)를 비교한다.
 * 행 단위 해시는 DB마다 값 표현(자릿수·시간대·문자 인코딩)이 달라 오탐이 많고 비용이 커서, 같은 의미로 계산되는 집계만 쓴다. 읽기 전용이다.
 */
@Service
public class MigrationVerifier {
    private final JdbcExplorer explorer;
    public MigrationVerifier(JdbcExplorer explorer) { this.explorer = explorer; }

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
        List<Mapping> cols = new ArrayList<>(); List<List<Measure>> measures = new ArrayList<>();
        for (Mapping m : mappings) {
            ColumnInfo s = sc.get(m.source()), t = tc.get(m.target());
            if (m.transformed()) { notes.add("컬럼 " + m.source() + " → " + m.target() + ": 변환 규칙이 있어 값 비교에서 제외했습니다(행 수는 비교합니다)."); continue; }
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
        return new Result(sourceRows, targetRows, rowsMatch, all, checks, notes);
    }

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
        boolean mysql = c.dbms() == Dbms.mysql || c.dbms() == Dbms.mariadb;
        String len = mysql ? "CHAR_LENGTH" : "LENGTH";
        List<String> select = new ArrayList<>(List.of("COUNT(*)")); List<Object[]> kinds = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            String name = JdbcReader.quote(c.dbms(), sourceSide ? cols.get(i).source() : cols.get(i).target());
            for (Measure m : measures.get(i)) {
                select.add(switch (m.kind()) { case NOT_NULL -> "COUNT(" + name + ")"; case SUM -> "SUM(" + name + ")"; case MIN -> "MIN(" + name + ")";
                    case MAX -> "MAX(" + name + ")"; case LENGTH -> "SUM(" + len + "(" + name + "))"; });
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
