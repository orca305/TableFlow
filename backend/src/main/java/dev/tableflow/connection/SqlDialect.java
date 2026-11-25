package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.Dbms;
import java.util.Objects;

/** JDBC 메타데이터와 조회 SQL의 DBMS별 규칙. 값은 PreparedStatement로 바인딩한다. */
public final class SqlDialect {
    private SqlDialect() {}

    public static boolean catalogBased(Dbms db) {
        return switch (Objects.requireNonNull(db)) {
            case mysql, mariadb -> true;
            case oracle, postgresql, sqlserver, tibero, cubrid -> false;
        };
    }

    public static String quote(Dbms db, String identifier) {
        if (db == Dbms.sqlserver) return "[" + Objects.requireNonNull(identifier).replace("]", "]]") + "]";
        String q = catalogBased(db) ? "`" : "\"";
        return q + Objects.requireNonNull(identifier).replace(q, q + q) + q;
    }

    /** 호출부에서 ORDER BY를 지정한 조회에만 사용한다. */
    public static String page(Dbms db, int limit, long offset) {
        if (limit < 1 || offset < 0) throw new IllegalArgumentException("조회 건수와 시작 위치를 확인해주세요.");
        return switch (db) {
            case tibero -> throw new IllegalArgumentException("Tibero 조회는 ROWNUM 래퍼를 사용해야 합니다.");
            case oracle, sqlserver -> " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
            case postgresql, mysql, mariadb, cubrid -> " LIMIT " + limit + " OFFSET " + offset;
        };
    }

    public static String first(Dbms db, int limit) {
        if (limit < 1) throw new IllegalArgumentException("조회 건수는 1 이상이어야 합니다.");
        return switch (db) {
            case tibero -> throw new IllegalArgumentException("Tibero 조회는 ROWNUM 래퍼를 사용해야 합니다.");
            case sqlserver -> page(db, limit, 0);
            case oracle -> " FETCH FIRST " + limit + " ROWS ONLY";
            case postgresql, mysql, mariadb, cubrid -> " LIMIT " + limit;
        };
    }

    public static boolean oracleTypes(Dbms db) { return db == Dbms.oracle || db == Dbms.tibero; }
    public static String metadataTable(Dbms db, String schema, String name) {
        return db == Dbms.cubrid ? schema.toLowerCase(java.util.Locale.ROOT) + "." + name : name;
    }
    public static String metadataName(Dbms db, String schema, String reported) {
        if (db == Dbms.cubrid && reported != null && reported.regionMatches(true, 0, schema + ".", 0, schema.length() + 1))
            return reported.substring(schema.length() + 1);
        return reported;
    }
    public static String paginate(Dbms db, String sql, int limit, long offset, boolean ordered, java.util.List<String> names) {
        if (limit < 1 || offset < -1) throw new IllegalArgumentException("조회 범위를 확인해주세요.");
        if (db == Dbms.tibero) {
            if (offset <= 0) return "SELECT * FROM (" + sql + ") WHERE ROWNUM <= " + limit;
            String alias = "TF_PAGE_ROW_NUMBER";
            while (names.stream().map(n -> n.toUpperCase(java.util.Locale.ROOT)).toList().contains(alias)) alias += "_";
            return "SELECT * FROM (SELECT TF_PAGE_DATA.*, ROWNUM " + alias + " FROM (" + sql + ") TF_PAGE_DATA WHERE ROWNUM <= "
                + Math.addExact(offset, limit) + ") WHERE " + alias + " > " + offset;
        }
        if (db == Dbms.sqlserver && !ordered) sql += " ORDER BY (SELECT NULL)";
        return sql + (offset < 0 ? first(db, limit) : page(db, limit, offset));
    }

    public static String lengthExpression(Dbms db, String quotedColumn) {
        return db == Dbms.sqlserver
            ? "(LEN((CAST(" + quotedColumn + " AS NVARCHAR(MAX)) COLLATE Latin1_General_100_CI_AS_SC) + N'#') - 1)"
            : lengthFunction(db) + "(" + quotedColumn + ")";
    }

    public static String lengthFunction(Dbms db) {
        return switch (db) {
            case mysql, mariadb, cubrid -> "CHAR_LENGTH";
            case oracle, postgresql, tibero -> "LENGTH";
            case sqlserver -> "LEN";
        };
    }
}
