package dev.tableflow.connection;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.tableflow.connection.JdbcExplorer.*;

class JdbcExplorerTests {
    Credentials credentials(Dbms db) { return new Credentials(db, "localhost", db.port, "sample", "reader", "secret-never-echo", true); }
    @Test void fixedDriversAndSecurePropertiesAreAvailable() {
        var explorer = new JdbcExplorer(true, "localhost");
        assertEquals(5, explorer.registry().drivers().size());
        assertTrue(explorer.registry().drivers().stream().filter(d -> java.util.Set.of(Dbms.oracle,Dbms.postgresql,Dbms.mysql,Dbms.mariadb).contains(d.dbms())).allMatch(DriverInfo::available));
        for (Dbms db : Dbms.values()) {
            var c = credentials(db);
            assertFalse(explorer.url(c).contains(c.password()));
            assertEquals(c.password(), explorer.properties(c).getProperty("password"));
            assertFalse(c.toString().contains(c.password()));
        }
        assertEquals("jdbc:oracle:thin:@tcps://localhost:1521/sample", explorer.url(credentials(Dbms.oracle)));
        assertEquals("verify-full", explorer.properties(credentials(Dbms.postgresql)).getProperty("sslmode"));
        assertEquals("VERIFY_IDENTITY", explorer.properties(credentials(Dbms.mysql)).getProperty("sslMode"));
        assertEquals("verify-full", explorer.properties(credentials(Dbms.mariadb)).getProperty("sslMode"));
        assertEquals("5000", explorer.properties(credentials(Dbms.oracle)).getProperty("oracle.net.CONNECT_TIMEOUT"));
        assertEquals("10", explorer.properties(credentials(Dbms.postgresql)).getProperty("socketTimeout"));
    }
    @Test void disabledAndUnlistedHostsNeverOpenAConnection() {
        assertEquals("JDBC_DISABLED", assertThrows(Failure.class, () -> new JdbcExplorer(false,"localhost").test(credentials(Dbms.mysql))).code);
        assertEquals("JDBC_HOST_DENIED", assertThrows(Failure.class, () -> new JdbcExplorer(true,"example.invalid").test(credentials(Dbms.mysql))).code);
        var remote = new Credentials(Dbms.mysql,"192.0.2.10",3306,"sample","reader","secret",false);
        assertEquals("JDBC_TLS_REQUIRED", assertThrows(Failure.class, () -> new JdbcExplorer(true,"192.0.2.10").test(remote)).code);
    }
    @Test void vendorErrorsAreSanitizedAndClassified() {
        assertEquals("JDBC_AUTH", JdbcExplorer.classify(new SQLException("secret-never-echo", "28000")).code);
        assertEquals("JDBC_AUTH", JdbcExplorer.classify(new SQLException("private", "99999",1017)).code);
        assertEquals("JDBC_DATABASE", JdbcExplorer.classify(new SQLException("private", "3D000")).code);
        assertEquals("JDBC_TIMEOUT", JdbcExplorer.classify(new SQLTimeoutException("private")).code);
        assertEquals("JDBC_TLS", JdbcExplorer.classify(new SQLException("private", new javax.net.ssl.SSLHandshakeException("private"))).code);
        assertFalse(JdbcExplorer.classify(new SQLException("secret-never-echo")).getMessage().contains("secret-never-echo"));
    }
    @Test void metadataUsesLiteralPatternsAndClosesReadOnlyConnections() throws Exception {
        var explorer = new JdbcExplorer(true,"localhost");
        var c = credentials(Dbms.postgresql);
        var connection = mock(Connection.class); var meta = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(meta); when(meta.getSearchStringEscape()).thenReturn("\\");
        var rows = mock(ResultSet.class);
        when(meta.getTables(null,"my\\_schema","%",new String[]{"TABLE","VIEW"})).thenReturn(rows);
        when(rows.next()).thenReturn(true,false); when(rows.getString("TABLE_NAME")).thenReturn("EMPLOYEE");
        try (var manager = mockStatic(DriverManager.class)) {
            manager.when(() -> DriverManager.getConnection(eq(explorer.url(c)), any(Properties.class))).thenReturn(connection);
            assertEquals(List.of("EMPLOYEE"),explorer.tables(c,"my_schema").items());
            verify(connection).setReadOnly(true); verify(connection).close(); verify(rows).close();
        }
        assertEquals("a\\%b\\_c",JdbcExplorer.literalPattern(meta,"a%b_c"));
    }
    @Test void mysqlSchemasUseCatalogsAndColumnsIncludePrimaryKeys() throws Exception {
        var explorer = new JdbcExplorer(true,"localhost"); var c = credentials(Dbms.mysql);
        var connection = mock(Connection.class); var meta = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(meta); when(meta.getSearchStringEscape()).thenReturn("\\");
        var catalogs=mock(ResultSet.class); when(meta.getCatalogs()).thenReturn(catalogs);
        when(catalogs.next()).thenReturn(true,false); when(catalogs.getString("TABLE_CAT")).thenReturn("sample");
        var keys=mock(ResultSet.class); when(meta.getPrimaryKeys("sample",null,"EMP_NO")).thenReturn(keys);
        when(keys.next()).thenReturn(true,false); when(keys.getString("COLUMN_NAME")).thenReturn("ID");
        var columns=mock(ResultSet.class); when(meta.getColumns("sample",null,"EMP\\_NO","%")).thenReturn(columns);
        when(columns.next()).thenReturn(true,false); when(columns.getString("COLUMN_NAME")).thenReturn("ID");
        when(columns.getString("TYPE_NAME")).thenReturn("BIGINT"); when(columns.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls);
        try(var manager=mockStatic(DriverManager.class)) {
            manager.when(() -> DriverManager.getConnection(eq(explorer.url(c)), any(Properties.class))).thenReturn(connection);
            assertEquals(List.of("sample"),explorer.schemas(c).items());
            var result=explorer.columns(c,"sample","EMP_NO");
            assertTrue(result.items().getFirst().primaryKey()); assertFalse(result.items().getFirst().nullable());
            verify(columns).close(); verify(keys).close();
        }
    }
}
