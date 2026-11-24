package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.TableDdl;
import dev.tableflow.model.Table.DataType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** SQL 생성 단위 테스트. SQL Server 실서버 검증을 대체하지 않는다. */
class SqlServerDialectTests {
    @Test void urlAndTlsNeverEmbedCredentialsOrTrustServerCertificate() {
        var explorer = new JdbcExplorer(true, "localhost");
        var c = new Credentials(Dbms.sqlserver, "localhost", 1433, "sample", "reader", "test-only", true);
        assertEquals("jdbc:sqlserver://localhost:1433;databaseName=sample", explorer.url(c));
        assertEquals("true", explorer.properties(c).getProperty("encrypt"));
        assertEquals("false", explorer.properties(c).getProperty("trustServerCertificate"));
        org.springframework.test.util.ReflectionTestUtils.setField(explorer, "sqlserverEnabled", false);
        assertEquals("JDBC_EXPERIMENTAL_DISABLED", assertThrows(Failure.class, () -> explorer.test(c)).code);
        var capabilities = explorer.registry().drivers().stream().filter(d -> d.dbms() == Dbms.sqlserver).findFirst().orElseThrow().capabilities();
        assertFalse(capabilities.experimental());
        assertFalse(capabilities.browse()); assertFalse(capabilities.load());
    }
    @Test void usesSqlServerPagingUnicodeTypesAndSafeDefaults() {
        assertEquals("[a]]b]", SqlDialect.quote(Dbms.sqlserver, "a]b"));
        assertEquals(" OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY", SqlDialect.page(Dbms.sqlserver, 10, 20));
        String ddl = TableDdl.createTable(Dbms.sqlserver, "dbo", "dst", List.of(
            new TableDdl.Col("NAME", DataType.string, false, false, 5000, 0),
            new TableDdl.Col("ACTIVE", DataType.bool, true, false, 0, 0),
            new TableDdl.Col("UPDATED", DataType.datetime, true, false, 0, 0)));
        assertTrue(ddl.contains("[NAME] NVARCHAR(MAX) NOT NULL"), ddl);
        assertTrue(ddl.contains("[ACTIVE] BIT"), ddl);
        assertTrue(ddl.contains("[UPDATED] DATETIME2(6)"), ddl);
        assertEquals("N'한글'", TableDdl.convertDefault(Dbms.postgresql, Dbms.sqlserver, DataType.string, "'한글'"));
        assertEquals("N'한글'", TableDdl.convertDefault(Dbms.sqlserver, Dbms.sqlserver, DataType.string, "(N'한글')"));
        assertEquals("1", TableDdl.convertDefault(Dbms.postgresql, Dbms.sqlserver, DataType.bool, "true"));
        assertEquals("'한글'", TableDdl.convertDefault(Dbms.sqlserver, Dbms.postgresql, DataType.string, "(N'한글')"));
    }
}
