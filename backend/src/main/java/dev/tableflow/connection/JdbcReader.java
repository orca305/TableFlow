package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.transform.TableEngine;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * DB → File 추출용 읽기 전용 조회. 임의 SQL은 받지 않고, 메타데이터로 확인된 스키마·테이블·컬럼만
 * 인용해서 SELECT 한다. 결과는 기존 {@link Table} 구조로 만들어 CSV/Excel/SQL 내보내기를 그대로 재사용한다.
 */
@Service
public class JdbcReader {
    public static final int MAX_ROWS = 10_000; // Table 모델의 행 상한과 동일
    private static final int FETCH_SIZE = 500;
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private final JdbcExplorer explorer;
    public JdbcReader(JdbcExplorer explorer) { this.explorer = explorer; }

    public Table read(Credentials credentials, String schema, String table, List<String> columns, int limit) {
        return explorer.withConnection(credentials, true, connection -> extract(connection, credentials.dbms(), schema, table, columns, limit));
    }

    public record Counted(Table table, long totalRows) {}
    /** 추출과 전체 행 수 조회를 한 연결에서 수행한다(DB→DB가 일부만 이전하는 일을 막기 위함). */
    public Counted readCounted(Credentials credentials, String schema, String table, List<String> columns, int limit) {
        return readCounted(credentials, schema, table, columns, limit, null);
    }
    /** where: 행 조건(없으면 null). RowFilter 규칙을 통과한 조건만 쓴다. */
    public Counted readCounted(Credentials credentials, String schema, String table, List<String> columns, int limit, String where) {
        return explorer.withConnection(credentials, true, connection -> {
            Table extracted = extract(connection, credentials.dbms(), schema, table, columns, limit, -1, null, where);
            return new Counted(extracted, countRows(connection, credentials.dbms(), schema, table, where));
        });
    }

    /** 전체 행 수(대용량 이전의 청크 계산용). */
    public long count(Credentials credentials, String schema, String table) { return count(credentials, schema, table, null); }
    public long count(Credentials credentials, String schema, String table, String where) {
        return explorer.withConnection(credentials, true, connection -> countRows(connection, credentials.dbms(), schema, table, where));
    }
    private long countRows(Connection connection, Dbms dbms, String schema, String table, String where) throws SQLException {
        String filter = RowFilter.clean(where);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + quote(dbms, schema) + "." + quote(dbms, table) + (filter == null ? "" : " WHERE " + filter))) { rs.next(); return rs.getLong(1); }
            catch (SQLException e) { if (filter == null) throw e; throw filterFailure(e); }
        }
    }
    /**
     * 정렬된 페이지(기본 키 순서, 기본 키가 없으면 선택 컬럼 전체 순서)로 읽는다. 같은 offset은 원본이 바뀌지 않는 한 항상
     * 같은 행을 돌려주므로 청크 영수증으로 이어서 실행(재개)할 수 있다. OFFSET 방식이라 매우 큰 테이블에서는 뒤쪽 페이지가 느려진다.
     */
    public Table readPage(Credentials credentials, String schema, String table, List<String> columns, long offset, int size) {
        return readPage(credentials, schema, table, columns, offset, size, null);
    }
    public Table readPage(Credentials credentials, String schema, String table, List<String> columns, long offset, int size, String where) {
        if (offset < 0) throw new IllegalArgumentException("offset은 0 이상이어야 합니다.");
        return explorer.withConnection(credentials, true, connection -> extract(connection, credentials.dbms(), schema, table, columns, size, offset, null, where));
    }

    /** 키 기반(keyset) 페이지 커서. after=이전 페이지 마지막 행의 기본 키 값(null이면 처음부터), last=이번 페이지 마지막 행의 기본 키 값. */
    public static final class Keyset {
        private final List<Object> after; private List<Object> last;
        public Keyset(List<Object> after) { this.after = after; this.last = after; }
        public List<Object> last() { return last; }
    }
    public boolean hasPrimaryKey(Credentials credentials, String schema, String table) {
        return explorer.withConnection(credentials, true, connection -> !primaryKey(connection, credentials.dbms(), schema, table).isEmpty());
    }
    /**
     * 기본 키 기준 정렬 페이지를 OFFSET 없이 읽는다(WHERE 키 &gt; 이전 마지막 키). 청크 위치와 무관하게 비용이 일정하다.
     * 기본 키가 없으면 IllegalArgumentException. 읽은 뒤 keyset.last()가 다음 호출의 커서가 된다.
     */
    public Table readKeyPage(Credentials credentials, String schema, String table, List<String> columns, Keyset keyset, int size) {
        return readKeyPage(credentials, schema, table, columns, keyset, size, null);
    }
    public Table readKeyPage(Credentials credentials, String schema, String table, List<String> columns, Keyset keyset, int size, String where) {
        return explorer.withConnection(credentials, true, connection -> extract(connection, credentials.dbms(), schema, table, columns, size, -1, keyset, where));
    }
    private List<String> primaryKey(Connection connection, Dbms dbms, String schema, String table) throws SQLException {
        boolean catalogBased = SqlDialect.catalogBased(dbms);
        Map<Integer, String> keys = new TreeMap<>();
        try (var rows = connection.getMetaData().getPrimaryKeys(catalogBased ? schema : null, catalogBased ? null : schema, SqlDialect.metadataTable(dbms, schema, table))) {
            while (rows.next()) keys.put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(keys.values());
    }

    /** 연결을 직접 받는 핵심 로직. 테스트에서 임의 JDBC 연결로 검증할 수 있도록 분리했다. */
    Table extract(Connection connection, Dbms dbms, String schema, String table, List<String> selected, int limit) throws SQLException {
        return extract(connection, dbms, schema, table, selected, limit, -1);
    }
    /** offset &lt; 0: 정렬 없이 처음 limit행(미리보기·소량 추출). offset &ge; 0: 정렬된 페이지. */
    Table extract(Connection connection, Dbms dbms, String schema, String table, List<String> selected, int limit, long offset) throws SQLException {
        return extract(connection, dbms, schema, table, selected, limit, offset, null);
    }
    Table extract(Connection connection, Dbms dbms, String schema, String table, List<String> selected, int limit, long offset, Keyset keyset) throws SQLException {
        return extract(connection, dbms, schema, table, selected, limit, offset, keyset, null);
    }
    Table extract(Connection connection, Dbms dbms, String schema, String table, List<String> selected, int limit, long offset, Keyset keyset, String where) throws SQLException {
        String filter = RowFilter.clean(where);
        if (limit < 1 || limit > MAX_ROWS) throw new IllegalArgumentException("조회 행 수는 1~" + MAX_ROWS + " 사이여야 합니다.");
        require(schema); require(table);
        boolean catalogBased = SqlDialect.catalogBased(dbms);
        var meta = connection.getMetaData();
        // 1) 메타데이터에 실제로 존재하는 컬럼만 허용 (SQL 주입 방지 + 정확한 대소문자)
        Map<String, int[]> available = new LinkedHashMap<>();
        Map<String, String> typeNames = new HashMap<>();
        try (var rows = meta.getColumns(catalogBased ? schema : null,
                catalogBased ? null : JdbcExplorer.literalPattern(meta, schema), JdbcExplorer.literalPattern(meta, SqlDialect.metadataTable(dbms, schema, table)), "%")) {
            while (rows.next()) {
                String name = rows.getString("COLUMN_NAME");
                if (!table.equals(SqlDialect.metadataName(dbms, schema, rows.getString("TABLE_NAME")))) continue; // 패턴 일치 보정
                int type = rows.getInt("DATA_TYPE");
                // Tibero DATE는 시간도 저장하므로 getDate()로 잘라 읽지 않는다.
                if (dbms == Dbms.tibero && type == Types.DATE) type = Types.TIMESTAMP;
                available.put(name, new int[]{type, rows.getInt("DECIMAL_DIGITS")});
                typeNames.put(name, rows.getString("TYPE_NAME"));
            }
        }
        if (available.isEmpty()) throw new Failure(404, "JDBC_TABLE", "스키마 또는 테이블을 찾을 수 없거나 조회 가능한 컬럼이 없습니다.");
        List<String> names = selected == null || selected.isEmpty() ? new ArrayList<>(available.keySet()) : new ArrayList<>(new LinkedHashSet<>(selected));
        if (names.size() > 100) throw new IllegalArgumentException("한 번에 최대 100개 컬럼까지 추출할 수 있습니다.");
        for (String name : names)
            if (!available.containsKey(name)) throw new IllegalArgumentException("테이블에 없는 컬럼입니다: " + name);
        for (String name : names)
            if (unsupported(available.get(name)[0]))
                throw new IllegalArgumentException("지원하지 않는 컬럼 타입입니다(" + typeNames.get(name) + "): " + name + ". 컬럼 선택에서 제외해주세요.");

        // 2) Table 컬럼 구성
        List<Column> tableColumns = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            tableColumns.add(new Column("col_" + (i + 1), name.length() > 200 ? name.substring(0, 200) : name,
                uniqueIdentifier(name, used), dataType(available.get(name)[0], available.get(name)[1]), true, true, null, null, null));
        }

        // 3) 읽기 전용 SELECT (값은 바인딩할 것이 없고, 식별자는 메타데이터로 검증된 이름만 인용)
        List<String> keyColumns = keyset == null ? List.of() : primaryKey(connection, dbms, schema, table);
        if (keyset != null && keyColumns.isEmpty()) throw new IllegalArgumentException("기본 키가 없는 테이블은 키 기반 페이지를 쓸 수 없습니다.");
        if (keyset != null && keyset.after != null && keyset.after.size() != keyColumns.size())
            throw new IllegalArgumentException("커서의 키 컬럼 수가 기본 키와 다릅니다.");
        // 키 값은 맨 뒤에 별칭(TF_KEY_n)을 붙여 덧붙여 읽는다. 선택 컬럼과 겹쳐도 열 이름이 중복되지 않는다(Oracle ORA-00960).
        List<String> selectList = new ArrayList<>(names.stream().map(n -> quote(dbms, n)).toList());
        for (int i = 0; i < keyColumns.size(); i++) selectList.add(quote(dbms, keyColumns.get(i)) + " AS " + quote(dbms, "TF_KEY_" + (i + 1)));
        String sql = "SELECT " + String.join(", ", selectList) + " FROM "
            + quote(dbms, schema) + "." + quote(dbms, table);
        List<Object> binds = new ArrayList<>();
        String keyCondition = null;
        if (keyset != null) {
            if (keyset.after != null) { // (k1,k2) > (a,b) 를 OR 전개: k1>a OR (k1=a AND k2>b) — 튜플 비교를 못 하는 Oracle도 동작
                List<String> ors = new ArrayList<>();
                for (int i = 0; i < keyColumns.size(); i++) {
                    List<String> and = new ArrayList<>();
                    for (int j = 0; j < i; j++) { and.add(quote(dbms, keyColumns.get(j)) + " = ?"); binds.add(keyset.after.get(j)); }
                    and.add(quote(dbms, keyColumns.get(i)) + " > ?"); binds.add(keyset.after.get(i));
                    ors.add("(" + String.join(" AND ", and) + ")");
                }
                keyCondition = String.join(" OR ", ors);
            }
            sql += whereClause(filter, keyCondition);
            sql += " ORDER BY " + String.join(", ", keyColumns.stream().map(n -> quote(dbms, n)).toList());
            sql = SqlDialect.paginate(dbms, sql, limit, -1, keyset != null, names);
        } else if (offset >= 0) {
            sql += whereClause(filter, null);
            List<String> order = new ArrayList<>(); // 기본 키(KEY_SEQ 순) → 없으면 선택한 컬럼 전체
            List<String> keys = primaryKey(connection, dbms, schema, table);
            (keys.isEmpty() ? names : keys).forEach(name -> order.add(quote(dbms, name)));
            sql += " ORDER BY " + String.join(", ", order);
            sql = SqlDialect.paginate(dbms, sql, limit, offset, true, names);
        } else {
            sql += whereClause(filter, null);
            sql = SqlDialect.paginate(dbms, sql, limit, -1, false, names);
        }
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < binds.size(); i++) statement.setObject(i + 1, binds.get(i));
            statement.setFetchSize(FETCH_SIZE);
            statement.setMaxRows(limit);
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = runQuery(statement, filter)) {
                while (rs.next() && rows.size() < limit) {
                    Map<String, Cell> cells = new LinkedHashMap<>();
                    for (int i = 0; i < tableColumns.size(); i++) {
                        String value = text(rs, i + 1, tableColumns.get(i).dataType());
                        cells.put(tableColumns.get(i).id(), new Cell(value, value, null, false));
                    }
                    rows.add(new Row("row_" + (rows.size() + 1), cells));
                    if (keyset != null) {
                        List<Object> last = new ArrayList<>();
                        for (int j = 0; j < keyColumns.size(); j++) last.add(rs.getObject(names.size() + 1 + j));
                        keyset.last = last;
                    }
                }
            }
        }
        return new Table("1.0", UUID.randomUUID().toString(), table.length() > 200 ? table.substring(0, 200) : table,
            new Source(schema + "." + table, "database", "jdbc"), tableColumns, rows);
    }

    private static ResultSet runQuery(PreparedStatement statement, String filter) throws SQLException {
        try { return statement.executeQuery(); } catch (SQLException e) { if (filter == null) throw e; throw filterFailure(e); }
    }
    /** 행 조건이 원인일 때는 연결 오류로 오해하지 않도록 DB가 알려준 이유를 그대로 보여준다. */
    private static IllegalArgumentException filterFailure(SQLException e) {
        String reason = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
        return new IllegalArgumentException("행 조건을 적용하지 못했습니다. 컬럼 이름의 대소문자와 값의 형식을 확인해주세요. (" + reason + ")");
    }
    private static String whereClause(String filter, String keyCondition) {
        List<String> parts = new ArrayList<>();
        if (filter != null) parts.add("(" + filter + ")");
        if (keyCondition != null) parts.add("(" + keyCondition + ")");
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }
    static String text(ResultSet rs, int index, DataType type) throws SQLException {
        return switch (type) {
            case date -> { var v = rs.getDate(index); yield v == null ? null : v.toLocalDate().toString(); }
            case datetime -> {
                var v = rs.getTimestamp(index);
                yield v == null ? null : v.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            case integer, decimal -> { var v = rs.getBigDecimal(index); yield v == null ? null : v.toPlainString(); }
            case bool -> { boolean v = rs.getBoolean(index); yield rs.wasNull() ? null : Boolean.toString(v); }
            case string -> rs.getString(index);
        };
    }

    static DataType dataType(int jdbcType, int scale) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> DataType.integer;
            case Types.DECIMAL, Types.NUMERIC -> scale == 0 ? DataType.integer : DataType.decimal;
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> DataType.decimal;
            case Types.DATE -> DataType.date;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> DataType.datetime;
            case Types.BOOLEAN, Types.BIT -> DataType.bool;
            default -> DataType.string;
        };
    }

    static boolean unsupported(int jdbcType) {
        return switch (jdbcType) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB, Types.ARRAY, Types.STRUCT, Types.JAVA_OBJECT,
                 Types.OTHER, Types.SQLXML, Types.TIME_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    /** 대상 컬럼명 규칙(TableEngine.identifier)을 만족하는 고유 이름으로 정규화한다. */
    static String uniqueIdentifier(String source, Set<String> used) {
        String base = source.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0)) && base.charAt(0) != '_') base = "C_" + base;
        if (base.length() > 60) base = base.substring(0, 60);
        String candidate = base; int n = 2;
        while (!TableEngine.identifier(candidate) || !used.add(candidate.toLowerCase(Locale.ROOT))) candidate = base + "_" + n++;
        return candidate;
    }

    public static String quote(Dbms dbms, String identifier) {
        return SqlDialect.quote(dbms, identifier);
    }
    private static void require(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("스키마와 테이블을 선택해주세요.");
    }
}
