package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer.Dbms;
import dev.tableflow.model.Table.DataType;
import dev.tableflow.transform.TableEngine;
import java.util.*;

/**
 * 대상 테이블 자동 생성용 CREATE TABLE 생성기. CREATE만 만들며 DROP/ALTER/UPDATE/DELETE는 만들지 않는다.
 * 식별자는 TableEngine 규칙(영문/숫자/_)만 허용하고 DBMS별로 인용한다.
 */
public final class TableDdl {
    private TableDdl() {}
    public record Col(String name, DataType type, boolean nullable, boolean primaryKey, int size, int scale) {}

    public static String quote(Dbms db, String id) {
        char q = family(db) ? '`' : '"';
        return q + id.replace(String.valueOf(q), "" + q + q) + q;
    }
    private static boolean family(Dbms db) { return db == Dbms.mysql || db == Dbms.mariadb; }

    public static String createTable(Dbms db, String schema, String table, List<Col> cols) {
        if (cols.isEmpty()) throw new IllegalArgumentException("생성할 컬럼이 없습니다.");
        int limit = db == Dbms.postgresql ? 63 : 64;
        for (String id : concat(List.of(table), cols.stream().map(Col::name).toList()))
            if (!TableEngine.identifier(id) || id.length() > limit)
                throw new IllegalArgumentException("테이블·컬럼 이름은 영문/숫자/_ 조합으로 숫자로 시작할 수 없고 최대 " + limit + "자입니다: " + id);
        Set<String> seen = new HashSet<>();
        for (Col c : cols) if (!seen.add(c.name().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("대상 컬럼 이름이 중복됩니다: " + c.name());
        List<String> lines = new ArrayList<>();
        for (Col c : cols) lines.add("    " + quote(db, c.name()) + " " + type(db, c) + (c.nullable() && !c.primaryKey() ? "" : " NOT NULL"));
        List<String> pk = cols.stream().filter(Col::primaryKey).map(c -> quote(db, c.name())).toList();
        if (!pk.isEmpty()) lines.add("    PRIMARY KEY (" + String.join(", ", pk) + ")");
        return "CREATE TABLE " + quote(db, schema) + "." + quote(db, table) + " (\n" + String.join(",\n", lines) + "\n)" + (family(db) ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "");
    }

    /** 적재 영수증 테이블(infra/jdbc/*-receipts.sql과 동일 구조). */
    public static String receiptsTable(Dbms db, String schema) {
        String text = db == Dbms.oracle ? "VARCHAR2" : "VARCHAR";
        return "CREATE TABLE " + quote(db, schema) + "." + quote(db, "tableflow_load_receipts") + " (\n    request_id " + text + "(36) PRIMARY KEY,\n    payload_hash "
            + text + "(64) NOT NULL,\n    table_name " + text + "(128) NOT NULL,\n    inserted_rows INTEGER NOT NULL\n)" + (family(db) ? " ENGINE=InnoDB" : "");
    }

    static String type(Dbms db, Col c) {
        return switch (c.type()) {
            case string -> {
                int n = c.size() > 0 ? c.size() : 255;
                yield switch (db) {
                    case oracle -> { if (n > 4000) throw new IllegalArgumentException("Oracle VARCHAR2는 최대 4000자라 이 문자열 컬럼은 생성할 수 없습니다: " + c.name()); yield "VARCHAR2(" + n + " CHAR)"; }
                    case postgresql -> n > 10_000_000 ? "TEXT" : "VARCHAR(" + n + ")";
                    default -> n > 16_000 ? "LONGTEXT" : "VARCHAR(" + n + ")";
                };
            }
            case integer -> {
                if (c.size() > 19) { int p = Math.min(c.size(), db == Dbms.oracle ? 38 : 65); yield decimal(db, p, 0); }
                yield db == Dbms.oracle ? "NUMBER(19)" : "BIGINT";
            }
            case decimal -> {
                int p = c.size() > 0 && c.size() <= (db == Dbms.oracle ? 38 : 65) ? c.size() : 38;
                int s = c.scale() > 0 ? Math.min(c.scale(), p) : Math.min(10, p);
                yield decimal(db, p, s);
            }
            case date -> "DATE";
            case datetime -> switch (db) { case oracle, postgresql -> "TIMESTAMP(6)"; default -> "DATETIME(6)"; };
            case bool -> switch (db) { case oracle -> "NUMBER(1)"; default -> "BOOLEAN"; };
        };
    }
    private static String decimal(Dbms db, int p, int s) {
        return switch (db) { case oracle -> "NUMBER(" + p + "," + s + ")"; case postgresql -> "NUMERIC(" + p + "," + s + ")"; default -> "DECIMAL(" + p + "," + s + ")"; };
    }
    private static <T> List<T> concat(List<T> a, List<T> b) { var r = new ArrayList<>(a); r.addAll(b); return r; }
}
