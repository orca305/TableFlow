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
