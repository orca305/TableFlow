package dev.tableflow.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Mapping;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** compose.sqlserver.yml 전용. 관리자 반입 Microsoft JAR와 라이선스 동의 후 준비한 서버가 필요하다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true", "tableflow.jdbc.load-enabled=true", "tableflow.jdbc.sqlserver-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_SQLSERVER", matches="true")
class SqlServerIntegrationTests {
    @Autowired JdbcExplorer explorer;
    @Autowired MigrationService migration;
    @Autowired dev.tableflow.load.MigrationVerifier verifier;
    @org.junit.jupiter.api.AutoClose final dev.tableflow.state.StateDbTestSupport state = new dev.tableflow.state.StateDbTestSupport();
    @TempDir Path directory;
    private void withSql(java.util.function.Consumer<Credentials> test) throws Exception {
        var catalog = new DriverCatalog(directory.toString(), state.dataSource());
        try {
            DriverCatalog.Entry driver;
            try (var input = Files.newInputStream(Path.of(System.getenv("TABLEFLOW_TEST_SQLSERVER_DRIVER")))) { driver = catalog.register(Dbms.sqlserver, "fixture", input); }
            explorer.setDriverCatalog(catalog);
            test.accept(new Credentials(Dbms.sqlserver, "localhost", 11433, "tableflow", "tableflow", "TableFlowTest2026", false, null, driver.id()));
        } finally { catalog.close(); }
    }
    private void sql(Credentials c, String... commands) {
        explorer.withConnection(c, false, connection -> {
            try (var st = connection.createStatement()) { st.setQueryTimeout(30); for (String command : commands) st.execute(command); }
            return null;
        });
    }

    @Test void copiesForeignKeysIndexesDefaultsAndRejectsTriggersAndUntrustedTls() throws Exception {
        withSql(ms -> {
            sql(ms, "DROP TABLE IF EXISTS dbo.TF_MS_EX_D", "DROP TABLE IF EXISTS dbo.TF_MS_EX_S", "DROP TABLE IF EXISTS dbo.TF_MS_EX_P",
                "CREATE TABLE dbo.TF_MS_EX_P (ID BIGINT PRIMARY KEY)", "INSERT INTO dbo.TF_MS_EX_P VALUES(1)",
                "CREATE TABLE dbo.TF_MS_EX_S (ID BIGINT PRIMARY KEY, PID BIGINT REFERENCES dbo.TF_MS_EX_P(ID), NAME NVARCHAR(20) DEFAULT N'기본')",
                "CREATE INDEX IX_TF_MS_EX_NAME ON dbo.TF_MS_EX_S(NAME)", "INSERT INTO dbo.TF_MS_EX_S(ID,PID) VALUES(1,1)");
            var mappings = List.of("ID", "PID", "NAME").stream().map(n -> new Mapping(n, n)).toList();
            migration.execute(ms, "dbo", "TF_MS_EX_S", ms, "dbo", "TF_MS_EX_D", mappings, 100, true, false, UUID.randomUUID().toString(), new MigrationService.Extras(true, true, true));
            var extras = explorer.extras(ms, "dbo", "TF_MS_EX_D");
            assertEquals(1, extras.foreignKeys().size());
            assertTrue(extras.indexes().stream().anyMatch(i -> i.columns().contains("NAME")));
            assertTrue(extras.defaults().get("NAME").contains("기본"));
            assertTrue(verifier.verify(ms, "dbo", "TF_MS_EX_S", ms, "dbo", "TF_MS_EX_D", mappings, null).allMatch());
            assertThrows(RuntimeException.class, () -> migration.execute(ms, "dbo", "TF_MS_EX_S", ms, "dbo", "TF_MS_EX_D", mappings, 100, false, UUID.randomUUID().toString()));
            assertEquals(1, new JdbcReader(explorer).count(ms, "dbo", "TF_MS_EX_D"));
            sql(ms, "CREATE TRIGGER dbo.TF_MS_EX_TR ON dbo.TF_MS_EX_D AFTER INSERT AS RETURN");
            assertThrows(RuntimeException.class, () -> migration.check(ms, "dbo", "TF_MS_EX_S", ms, "dbo", "TF_MS_EX_D", mappings, 100, false));
            var tls = new Credentials(ms.dbms(), ms.host(), ms.port(), ms.database(), ms.username(), ms.password(), true, null, ms.driverId());
            assertEquals("JDBC_TLS", assertThrows(Failure.class, () -> explorer.test(tls)).code);
        });
    }

    @Test void roundTripsPostgresAndSqlServerWithDatesDecimalsNullsAndLengthChecks() throws Exception {
        withSql(ms -> {
            var pg = new Credentials(Dbms.postgresql, "localhost", 15432, "tableflow", "tableflow", "TableFlowTest2026", false);
            sql(pg, "DROP TABLE IF EXISTS public.\"TF_MS_PG_SRC\"", "DROP TABLE IF EXISTS public.\"TF_MS_PG_BACK\"",
                "CREATE TABLE public.\"TF_MS_PG_SRC\" (\"ID\" BIGINT PRIMARY KEY, \"NAME\" VARCHAR(100), \"AMT\" DECIMAL(12,2), \"DAY\" DATE, \"UPDATED\" TIMESTAMP(3))",
                "INSERT INTO public.\"TF_MS_PG_SRC\" VALUES (1,'한글 😀 끝 공백 ',12.34,'2026-11-25','2026-11-25 12:34:56.123'),(2,NULL,NULL,NULL,NULL)");
            sql(ms, "DROP TABLE IF EXISTS dbo.TF_MS_PG_DST");
            var mappings = List.of("ID", "NAME", "AMT", "DAY", "UPDATED").stream().map(n -> new Mapping(n, n)).toList();
            assertEquals(2, migration.execute(pg, "public", "TF_MS_PG_SRC", ms, "dbo", "TF_MS_PG_DST", mappings, 100, true, UUID.randomUUID().toString()).insertedRows());
            assertTrue(verifier.verify(pg, "public", "TF_MS_PG_SRC", ms, "dbo", "TF_MS_PG_DST", mappings, null).allMatch());
            assertEquals(2, migration.execute(ms, "dbo", "TF_MS_PG_DST", pg, "public", "TF_MS_PG_BACK", mappings, 100, true, UUID.randomUUID().toString()).insertedRows());
            assertTrue(verifier.verify(ms, "dbo", "TF_MS_PG_DST", pg, "public", "TF_MS_PG_BACK", mappings, null).allMatch());
            assertTrue(explorer.schemas(ms).items().contains("dbo"));
        });
    }

    @Test void preservesIdentityAndResumesCompletedChunksAfterLengthFailure() throws Exception {
        withSql(ms -> {
            sql(ms, "DROP TABLE IF EXISTS dbo.TF_MS_BULK_SRC", "DROP TABLE IF EXISTS dbo.TF_MS_BULK_DST",
                "CREATE TABLE dbo.TF_MS_BULK_SRC (ID BIGINT PRIMARY KEY, NAME NVARCHAR(20) NOT NULL)",
                "CREATE TABLE dbo.TF_MS_BULK_DST (ID BIGINT IDENTITY(1,1) PRIMARY KEY, NAME NVARCHAR(1) NOT NULL)",
                "WITH n AS (SELECT TOP (2501) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS i FROM sys.all_objects a CROSS JOIN sys.all_objects b) INSERT INTO dbo.TF_MS_BULK_SRC SELECT i, CASE WHEN i > 1000 THEN N'길이초과' ELSE N'A' END FROM n",
                "IF OBJECT_ID('dbo.tableflow_load_receipts') IS NULL " + dev.tableflow.load.TableDdl.receiptsTable(Dbms.sqlserver, "dbo"));
            var mappings = List.of(new Mapping("ID", "ID"), new Mapping("NAME", "NAME"));
            String id = UUID.randomUUID().toString();
            var progress = new MigrationService.Progress() {
                public void total(int chunks, long rows) {} public void chunk(int chunks, long rows, int replayed) {} public boolean cancelled() { return false; }
            };
            assertThrows(RuntimeException.class, () -> migration.runBulk(ms, "dbo", "TF_MS_BULK_SRC", ms, "dbo", "TF_MS_BULK_DST", mappings, 3000, false, true, id, progress, 1000));
            var reader = new JdbcReader(explorer);
            assertEquals(1000, reader.count(ms, "dbo", "TF_MS_BULK_DST"));
            sql(ms, "ALTER TABLE dbo.TF_MS_BULK_DST ALTER COLUMN NAME NVARCHAR(20) NOT NULL");
            var result = migration.runBulk(ms, "dbo", "TF_MS_BULK_SRC", ms, "dbo", "TF_MS_BULK_DST", mappings, 3000, false, true, id, progress, 1000);
            assertEquals(1, result.replayedChunks()); assertEquals(2501, reader.count(ms, "dbo", "TF_MS_BULK_DST"));
            sql(ms, "INSERT INTO dbo.TF_MS_BULK_DST (NAME) VALUES (N'next')");
            assertEquals(1, reader.count(ms, "dbo", "TF_MS_BULK_DST", "ID=2502"));
        });
    }
    @Test void migratesUnicodeAndReplaysSameRequestWithoutDuplicates() throws Exception {
        String path = System.getenv("TABLEFLOW_TEST_SQLSERVER_DRIVER");
        assertNotNull(path, "TABLEFLOW_TEST_SQLSERVER_DRIVER에 Microsoft JDBC JAR 경로를 지정해야 합니다.");
        var catalog = new DriverCatalog(directory.toString(), state.dataSource());
        try {
            DriverCatalog.Entry driver;
            try (var input = Files.newInputStream(Path.of(path))) { driver = catalog.register(Dbms.sqlserver, "fixture", input); }
            explorer.setDriverCatalog(catalog);
            var c = new Credentials(Dbms.sqlserver, "localhost", 11433, "tableflow", "tableflow", "TableFlowTest2026", false, null, driver.id());
            explorer.withConnection(c, false, connection -> {
                try (var st = connection.createStatement()) {
                    st.setQueryTimeout(30);
                    st.execute("DROP TABLE IF EXISTS dbo.TF_SQLSERVER_SRC");
                    st.execute("DROP TABLE IF EXISTS dbo.TF_SQLSERVER_DST");
                    st.execute("CREATE TABLE dbo.TF_SQLSERVER_SRC (ID BIGINT PRIMARY KEY, NAME NVARCHAR(100) NOT NULL)");
                    st.execute("INSERT INTO dbo.TF_SQLSERVER_SRC VALUES (1,N'한글 O''Brien'),(2,N'끝 공백 ')");
                }
                return null;
            });
            var mappings = List.of(new Mapping("ID", "ID"), new Mapping("NAME", "NAME"));
            String id = UUID.randomUUID().toString();
            assertEquals(2, migration.execute(c, "dbo", "TF_SQLSERVER_SRC", c, "dbo", "TF_SQLSERVER_DST", mappings, 100, true, id).insertedRows());
            assertTrue(migration.execute(c, "dbo", "TF_SQLSERVER_SRC", c, "dbo", "TF_SQLSERVER_DST", mappings, 100, true, id).replayed());
            assertEquals(2, new JdbcReader(explorer).count(c, "dbo", "TF_SQLSERVER_DST"));
        } finally { catalog.close(); }
    }
}
