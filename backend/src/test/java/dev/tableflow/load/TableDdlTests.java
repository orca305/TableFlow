package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer.Dbms;
import dev.tableflow.load.TableDdl.Col;
import dev.tableflow.model.Table.DataType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TableDdlTests {
    final List<Col> cols = List.of(new Col("EMP_NO", DataType.string, false, true, 20, 0), new Col("EMP_NM", DataType.string, true, false, 100, 0),
        new Col("HIRE_TS", DataType.datetime, false, false, 0, 6), new Col("SALARY", DataType.decimal, false, false, 20, 2),
        new Col("AGE", DataType.integer, true, false, 10, 0), new Col("ACTIVE", DataType.bool, true, false, 1, 0), new Col("BIGNUM", DataType.integer, true, false, 25, 0));

    @Test void postgresql() {
        String ddl = TableDdl.createTable(Dbms.postgresql, "public", "dst", cols);
        assertTrue(ddl.startsWith("CREATE TABLE \"public\".\"dst\" ("), ddl);
        for (String part : List.of("\"EMP_NO\" VARCHAR(20) NOT NULL", "\"EMP_NM\" VARCHAR(100),", "\"HIRE_TS\" TIMESTAMP(6) NOT NULL", "\"SALARY\" NUMERIC(20,2) NOT NULL",
            "\"AGE\" BIGINT,", "\"ACTIVE\" BOOLEAN,", "\"BIGNUM\" NUMERIC(25,0)", "PRIMARY KEY (\"EMP_NO\")")) assertTrue(ddl.contains(part), part + "\n" + ddl);
    }
    @Test void mysqlFamilyUsesBackticksInnoDbAndDatetime() {
        for (Dbms db : List.of(Dbms.mysql, Dbms.mariadb)) {
            String ddl = TableDdl.createTable(db, "tableflow", "dst", cols);
            assertTrue(ddl.contains("`tableflow`.`dst`") && ddl.contains("`HIRE_TS` DATETIME(6) NOT NULL") && ddl.contains("DECIMAL(20,2)") && ddl.endsWith("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"), ddl);
        }
    }
    @Test void oracle() {
        String ddl = TableDdl.createTable(Dbms.oracle, "TABLEFLOW", "dst", cols);
        for (String part : List.of("\"EMP_NO\" VARCHAR2(20 CHAR) NOT NULL", "TIMESTAMP(6)", "NUMBER(20,2)", "\"AGE\" NUMBER(19),", "\"ACTIVE\" NUMBER(1),", "NUMBER(25,0)")) assertTrue(ddl.contains(part), part + "\n" + ddl);
        assertThrows(IllegalArgumentException.class, () -> TableDdl.createTable(Dbms.oracle, "S", "t", List.of(new Col("BIG", DataType.string, true, false, 5000, 0))));
    }
    static String def(Dbms from, Dbms to, DataType type, String raw) { return TableDdl.convertDefault(from, to, type, raw); }

    @Test void defaultsKeepNumbersTextAndBooleans() {
        assertEquals("-1", def(Dbms.postgresql, Dbms.oracle, DataType.integer, "'-1'::integer"));
        assertEquals("-2.50", def(Dbms.postgresql, Dbms.mysql, DataType.decimal, "'-2.50'::numeric"));
        assertEquals("0.5", def(Dbms.oracle, Dbms.postgresql, DataType.decimal, ".5 "));
        assertEquals("-0.5", def(Dbms.oracle, Dbms.mariadb, DataType.decimal, "-.5"));
        assertEquals("'-1'", def(Dbms.postgresql, Dbms.oracle, DataType.string, "'-1'::character varying"));
        assertEquals("TRUE", def(Dbms.mysql, Dbms.postgresql, DataType.bool, "b'1'"));
        assertEquals("0", def(Dbms.mariadb, Dbms.oracle, DataType.bool, "b'0'"));
        assertNull(def(Dbms.postgresql, Dbms.oracle, DataType.integer, "nextval('s'::regclass)"));
        assertNull(def(Dbms.postgresql, Dbms.oracle, DataType.string, "gen_random_uuid()"));
        assertNull(def(Dbms.oracle, Dbms.postgresql, DataType.integer, "'abc'"));
    }
    @Test void dateLiteralDefaultsUseAFixedFormatOnTheTarget() {
        assertEquals("DATE '2024-01-31'", def(Dbms.postgresql, Dbms.oracle, DataType.date, "'2024-01-31'::date"));
        assertEquals("'2024-01-31'", def(Dbms.oracle, Dbms.postgresql, DataType.date, "DATE '2024-01-31'"));
        assertEquals("'2024-01-31'", def(Dbms.oracle, Dbms.postgresql, DataType.datetime, "TO_DATE('2024-01-31', 'YYYY-MM-DD')"));
        assertEquals("'2024-01-31 10:20:30'", def(Dbms.oracle, Dbms.mariadb, DataType.datetime, "TIMESTAMP '2024-01-31 10:20:30'"));
        assertEquals("TIMESTAMP '2024-01-31 10:20:30'", def(Dbms.mysql, Dbms.oracle, DataType.datetime, "2024-01-31 10:20:30"));
        assertEquals("TIMESTAMP '2024-01-31 00:00:00'", def(Dbms.oracle, Dbms.oracle, DataType.datetime, "DATE '2024-01-31'"));
        assertEquals("'2024-01-31'", def(Dbms.postgresql, Dbms.mysql, DataType.date, "'2024-01-31 00:00:00'::timestamp without time zone"));
        assertNull(def(Dbms.postgresql, Dbms.mysql, DataType.date, "'2024-01-31 10:00:00'::timestamp without time zone")); // 시각이 있으면 날짜 컬럼에 옮기지 않는다
        assertNull(def(Dbms.oracle, Dbms.oracle, DataType.date, "'31-JAN-24'")); // 세션 날짜 형식에 의존하는 값
        assertNull(def(Dbms.postgresql, Dbms.oracle, DataType.date, "'2024-02-30'::date"));
    }
    @Test void currentTimeAndTodayDefaults() {
        assertEquals("TRUNC(CURRENT_DATE)", def(Dbms.postgresql, Dbms.oracle, DataType.date, "CURRENT_DATE"));
        assertEquals("(CURRENT_DATE)", def(Dbms.postgresql, Dbms.mysql, DataType.date, "CURRENT_DATE"));
        assertEquals("(CURRENT_DATE)", def(Dbms.mysql, Dbms.mariadb, DataType.date, "curdate()"));
        assertEquals("CURRENT_DATE", def(Dbms.oracle, Dbms.postgresql, DataType.datetime, "TRUNC(SYSDATE) "));
        assertEquals("CAST(CURRENT_TIMESTAMP AS DATE)", def(Dbms.mariadb, Dbms.sqlserver, DataType.date, "curdate()"));
        // Oracle의 CURRENT_DATE·SYSDATE는 시각까지 담는다
        assertEquals("CURRENT_TIMESTAMP", def(Dbms.oracle, Dbms.postgresql, DataType.datetime, "CURRENT_DATE"));
        assertEquals("CURRENT_TIMESTAMP(6)", def(Dbms.sqlserver, Dbms.mysql, DataType.datetime, "(getdate())"));
        assertEquals("CURRENT_TIMESTAMP", def(Dbms.mariadb, Dbms.postgresql, DataType.datetime, "current_timestamp(6)"));
        assertNull(def(Dbms.postgresql, Dbms.oracle, DataType.string, "CURRENT_DATE"));
    }

    @Test void onlyCreateStatementsAndRejectsUnsafeNames() {
        for (Dbms db : Dbms.values()) {
            List<Col> safeCols = db == Dbms.cubrid ? cols.stream().map(c -> new Col(c.name().toLowerCase(java.util.Locale.ROOT), c.type(), c.nullable(), c.primaryKey(), c.size(), c.scale())).toList() : cols;
            String ddl = TableDdl.createTable(db, "s", "t", safeCols).toUpperCase();
            assertFalse(ddl.contains("DROP ") || ddl.contains("ALTER ") || ddl.contains("DELETE ") || ddl.contains("UPDATE ") || ddl.contains(";"));
            assertTrue(TableDdl.receiptsTable(db, "s").contains("request_id"));
        }
        assertThrows(IllegalArgumentException.class, () -> TableDdl.createTable(Dbms.postgresql, "s", "t\"; DROP TABLE x; --", cols));
        assertThrows(IllegalArgumentException.class, () -> TableDdl.createTable(Dbms.postgresql, "s", "t", List.of(new Col("1bad", DataType.string, true, false, 5, 0))));
        assertThrows(IllegalArgumentException.class, () -> TableDdl.createTable(Dbms.postgresql, "s", "t", List.of(new Col("A", DataType.string, true, false, 5, 0), new Col("a", DataType.string, true, false, 5, 0))));
        assertThrows(IllegalArgumentException.class, () -> TableDdl.createTable(Dbms.postgresql, "s", "x".repeat(64), cols));
    }
}
