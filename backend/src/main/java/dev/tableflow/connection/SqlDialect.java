package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.Dbms;
import java.util.Objects;

/** JDBC 메타데이터와 조회 SQL의 DBMS별 규칙. 값은 PreparedStatement로 바인딩한다. */
public final class SqlDialect {
    private SqlDialect() {}

    public static boolean catalogBased(Dbms db) {
        return switch (Objects.requireNonNull(db)) {
            case mysql, mariadb -> true;
            case oracle, postgresql, sqlserver -> false;
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
            case oracle, sqlserver -> " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
            case postgresql, mysql, mariadb -> " LIMIT " + limit + " OFFSET " + offset;
        };
    }

    public static String first(Dbms db, int limit) {
        if (limit < 1) throw new IllegalArgumentException("조회 건수는 1 이상이어야 합니다.");
        return switch (db) {
            case sqlserver -> page(db, limit, 0);
            case oracle -> " FETCH FIRST " + limit + " ROWS ONLY";
            case postgresql, mysql, mariadb -> " LIMIT " + limit;
        };
    }

    public static String lengthExpression(Dbms db, String quotedColumn) {
        return db == Dbms.sqlserver
            ? "(LEN((CAST(" + quotedColumn + " AS NVARCHAR(MAX)) COLLATE Latin1_General_100_CI_AS_SC) + N'#') - 1)"
            : lengthFunction(db) + "(" + quotedColumn + ")";
    }

    public static String lengthFunction(Dbms db) {
        return switch (db) {
            case mysql, mariadb -> "CHAR_LENGTH";
            case oracle, postgresql -> "LENGTH";
            case sqlserver -> "LEN";
        };
    }
}
