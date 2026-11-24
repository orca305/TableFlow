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
    public record Col(String name, DataType type, boolean nullable, boolean primaryKey, int size, int scale, String defaultSql) {
        public Col(String name, DataType type, boolean nullable, boolean primaryKey, int size, int scale) { this(name, type, nullable, primaryKey, size, scale, null); }
    }

    public static String quote(Dbms db, String id) {
        return dev.tableflow.connection.SqlDialect.quote(db, id);
    }
    private static boolean family(Dbms db) { return dev.tableflow.connection.SqlDialect.catalogBased(db); }

    public static String createTable(Dbms db, String schema, String table, List<Col> cols) {
        if (cols.isEmpty()) throw new IllegalArgumentException("생성할 컬럼이 없습니다.");
        int limit = db == Dbms.postgresql ? 63 : 64;
        for (String id : concat(List.of(table), cols.stream().map(Col::name).toList()))
            if (!TableEngine.identifier(id) || id.length() > limit)
                throw new IllegalArgumentException("테이블·컬럼 이름은 영문/숫자/_ 조합으로 숫자로 시작할 수 없고 최대 " + limit + "자입니다: " + id);
        Set<String> seen = new HashSet<>();
        for (Col c : cols) if (!seen.add(c.name().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("대상 컬럼 이름이 중복됩니다: " + c.name());
        List<String> lines = new ArrayList<>();
        for (Col c : cols) lines.add("    " + quote(db, c.name()) + " " + type(db, c) + (c.defaultSql() == null ? "" : " DEFAULT " + c.defaultSql()) + (c.nullable() && !c.primaryKey() ? "" : " NOT NULL"));
        List<String> pk = cols.stream().filter(Col::primaryKey).map(c -> quote(db, c.name())).toList();
        if (!pk.isEmpty()) lines.add("    PRIMARY KEY (" + String.join(", ", pk) + ")");
        return "CREATE TABLE " + quote(db, schema) + "." + quote(db, table) + " (\n" + String.join(",\n", lines) + "\n)" + (family(db) ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "");
    }

    /** 보조 인덱스 생성문. 이름은 호출자가 규칙에 맞게 정한다. */
    public static String createIndex(Dbms db, String schema, String table, String index, boolean unique, List<String> columns) {
        checkName(index, db);
        String cols = String.join(", ", columns.stream().map(c -> quote(db, c)).toList());
        String name = (db == Dbms.oracle) ? quote(db, schema) + "." + quote(db, index) : quote(db, index);
        return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + name + " ON " + quote(db, schema) + "." + quote(db, table) + " (" + cols + ")";
    }
    /** 외래 키 추가문(부모 테이블이 대상에 이미 있을 때만 호출한다). */
    public static String addForeignKey(Dbms db, String schema, String table, String name, List<String> columns, String parentSchema, String parentTable, List<String> parentColumns) {
        checkName(name, db);
        return "ALTER TABLE " + quote(db, schema) + "." + quote(db, table) + " ADD CONSTRAINT " + quote(db, name) + " FOREIGN KEY ("
            + String.join(", ", columns.stream().map(c -> quote(db, c)).toList()) + ") REFERENCES " + quote(db, parentSchema) + "." + quote(db, parentTable)
            + " (" + String.join(", ", parentColumns.stream().map(c -> quote(db, c)).toList()) + ")";
    }
    private static void checkName(String id, Dbms db) {
        if (!TableEngine.identifier(id) || id.length() > (db == Dbms.postgresql ? 63 : 64)) throw new IllegalArgumentException("이름이 올바르지 않습니다: " + id);
    }
    /**
     * 원본 기본값(원문)을 대상 DBMS의 DEFAULT 식으로 바꾼다. 숫자·문자 리터럴·불리언·현재 시각만 안전하게 옮기고,
     * 시퀀스·함수·표현식은 null(복제 안 함)을 돌려준다.
     */
    public static String convertDefault(Dbms from, Dbms to, DataType type, String raw) {
        if (raw == null) return null;
        String v = raw.strip();
        // MySQL은 문자열·날짜 기본값을 따옴표 없이 돌려준다. 함수·표현식이 아닌 단순 값이면 리터럴로 본다.
        if (from == Dbms.mysql && !v.startsWith("'") && !v.contains("(") && !v.matches("(?i)current_timestamp|now|null|true|false|-?\\d+(\\.\\d+)?")
                && (type == DataType.string || ((type == DataType.date || type == DataType.datetime) && v.matches("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?)?"))))
            v = "'" + v.replace("'", "''") + "'";
        while (v.startsWith("(") && v.endsWith(")")) v = v.substring(1, v.length() - 1).strip();
        v = v.replaceAll("(::[A-Za-z_ ]+(\\([0-9, ]*\\))?(\\[\\])?)+$", "").strip();
        if (v.isEmpty() || v.equalsIgnoreCase("null")) return null;
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.matches("current_timestamp(\\(\\d*\\))?|now\\(\\)|sysdate|systimestamp|localtimestamp(\\(\\d*\\))?|current_date")) {
            if (type == DataType.datetime) return to == Dbms.mysql || to == Dbms.mariadb ? "CURRENT_TIMESTAMP(6)" : "CURRENT_TIMESTAMP";
            if (type == DataType.date && to == Dbms.sqlserver) return "CAST(CURRENT_TIMESTAMP AS DATE)";
            if (type == DataType.date) return to == Dbms.mysql || to == Dbms.mariadb ? null : "CURRENT_DATE";
            return null;
        }
        if (type == DataType.bool && (lower.equals("true") || lower.equals("false") || lower.equals("0") || lower.equals("1") || lower.equals("'t'") || lower.equals("'f'"))) {
            boolean on = lower.equals("true") || lower.equals("1") || lower.equals("'t'");
            return (to == Dbms.oracle) || to == Dbms.sqlserver ? (on ? "1" : "0") : (on ? "TRUE" : "FALSE");
        }
        if (v.matches("-?\\d+(\\.\\d+)?")) return type == DataType.string ? "'" + v + "'" : (type == DataType.integer || type == DataType.decimal) ? v : null;
        if (from == Dbms.sqlserver && v.matches("N'([^']|'')*'")) v = v.substring(1);
        if (to == Dbms.sqlserver && type == DataType.string && v.matches("'([^']|'')*'")) return "N" + v;
        if (v.matches("'([^']|'')*'")) return type == DataType.string || type == DataType.date || type == DataType.datetime ? v.replace("\\", to == Dbms.mysql || to == Dbms.mariadb ? "\\\\" : "\\") : null;
        return null;
    }

    /** 적재 영수증 테이블(infra/jdbc/*-receipts.sql과 동일 구조). */
    public static String receiptsTable(Dbms db, String schema) {
        String text = (db == Dbms.oracle) ? "VARCHAR2" : "VARCHAR";
        return "CREATE TABLE " + quote(db, schema) + "." + quote(db, "tableflow_load_receipts") + " (\n    request_id " + text + "(36) PRIMARY KEY,\n    payload_hash "
            + text + "(64) NOT NULL,\n    table_name " + text + "(128) NOT NULL,\n    inserted_rows INTEGER NOT NULL\n)" + (family(db) ? " ENGINE=InnoDB" : "");
    }

    static String type(Dbms db, Col c) {
        return switch (c.type()) {
            case string -> {
                int n = c.size() > 0 ? c.size() : 255;
                yield switch (db) {
                    case oracle -> { if (n > 4000) throw new IllegalArgumentException("Oracle VARCHAR2는 최대 4000자라 이 문자열 컬럼은 생성할 수 없습니다: " + c.name()); yield "VARCHAR2(" + n + " CHAR)"; }
                    case sqlserver -> n > 4000 ? "NVARCHAR(MAX)" : "NVARCHAR(" + n + ")";
                    case postgresql -> n > 10_000_000 ? "TEXT" : "VARCHAR(" + n + ")";
                    default -> n > 16_000 ? "LONGTEXT" : "VARCHAR(" + n + ")";
                };
            }
            case integer -> {
                if (c.size() > 19) { int p = Math.min(c.size(), (db == Dbms.oracle) || db == Dbms.sqlserver ? 38 : 65); yield decimal(db, p, 0); }
                yield (db == Dbms.oracle) ? "NUMBER(19)" : "BIGINT";
            }
            case decimal -> {
                int p = c.size() > 0 && c.size() <= ((db == Dbms.oracle) || db == Dbms.sqlserver ? 38 : 65) ? c.size() : 38;
                int s = c.scale() > 0 ? Math.min(c.scale(), p) : Math.min(10, p);
                yield decimal(db, p, s);
            }
            case date -> "DATE";
            case datetime -> switch (db) { case sqlserver -> "DATETIME2(6)"; case oracle, postgresql -> "TIMESTAMP(6)"; default -> "DATETIME(6)"; };
            case bool -> switch (db) { case sqlserver -> "BIT"; case oracle -> "NUMBER(1)"; default -> "BOOLEAN"; };
        };
    }
    private static String decimal(Dbms db, int p, int s) {
        return switch (db) { case oracle -> "NUMBER(" + p + "," + s + ")"; case postgresql -> "NUMERIC(" + p + "," + s + ")"; default -> "DECIMAL(" + p + "," + s + ")"; };
    }
    private static <T> List<T> concat(List<T> a, List<T> b) { var r = new ArrayList<>(a); r.addAll(b); return r; }
}
