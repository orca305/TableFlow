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

@SpringBootTest(properties={"tableflow.jdbc.enabled=true", "tableflow.jdbc.load-enabled=true", "tableflow.jdbc.cubrid-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_CUBRID", matches="true")
class CubridIntegrationTests {
    @Autowired JdbcExplorer explorer;
    @Autowired MigrationService migration;
    @Autowired dev.tableflow.load.MigrationVerifier verifier;
    @Autowired dev.tableflow.load.BatchMigration batch;
    @org.junit.jupiter.api.AutoClose final dev.tableflow.state.StateDbTestSupport state = new dev.tableflow.state.StateDbTestSupport();
    @TempDir Path directory;
    private final Credentials pg = new Credentials(Dbms.postgresql, "localhost", 15432, "tableflow", "tableflow", "TableFlowTest2026", false);
    private void withCubrid(java.util.function.Consumer<Credentials> test) throws Exception {
        var catalog = new DriverCatalog(directory.toString(), state.dataSource());
        try {
            DriverCatalog.Entry driver;
            try (var input = Files.newInputStream(Path.of(System.getenv("TABLEFLOW_TEST_CUBRID_DRIVER")))) { driver = catalog.register(Dbms.cubrid, "fixture", input); }
            explorer.setDriverCatalog(catalog);
            test.accept(new Credentials(Dbms.cubrid, "localhost", 13300, "tableflow", "tableflow", "TableFlowTest2026", false, null, driver.id()));
        } finally { catalog.close(); }
    }
    private void sql(Credentials c, String... commands) {
        explorer.withConnection(c, false, connection -> {
            try (var st = connection.createStatement()) { st.setQueryTimeout(30); for (String command : commands) st.execute(command); }
            return null;
        });
    }
    @Test void copiesIndexesDefaultsForeignKeysAndRejectsExistingIdentityPreservation() throws Exception {
        withCubrid(cu -> {
            sql(cu,"DROP TABLE IF EXISTS tableflow.tf_cu_ex_dst","DROP TABLE IF EXISTS tableflow.tf_cu_ex_src","DROP TABLE IF EXISTS tableflow.tf_cu_ex_parent",
                "CREATE TABLE tableflow.tf_cu_ex_parent(id BIGINT PRIMARY KEY)","INSERT INTO tableflow.tf_cu_ex_parent VALUES(1)",
                "CREATE TABLE tableflow.tf_cu_ex_src(id BIGINT PRIMARY KEY,pid BIGINT REFERENCES tableflow.tf_cu_ex_parent(id),name VARCHAR(20) DEFAULT '기본')",
                "CREATE INDEX ix_cu_name ON tableflow.tf_cu_ex_src(name)","INSERT INTO tableflow.tf_cu_ex_src(id,pid) VALUES(1,1)");
            var mappings=List.of("id","pid","name").stream().map(n->new Mapping(n,n)).toList();
            migration.execute(cu,"TABLEFLOW","tf_cu_ex_src",cu,"TABLEFLOW","tf_cu_ex_dst",mappings,100,true,false,UUID.randomUUID().toString(),new MigrationService.Extras(true,true,true));
            var extras=explorer.extras(cu,"TABLEFLOW","tf_cu_ex_dst");
            assertEquals(1,extras.foreignKeys().size());
            assertTrue(extras.indexes().stream().anyMatch(i->i.columns().contains("name")));
            assertTrue(extras.defaults().get("name").contains("기본"));
            assertThrows(RuntimeException.class,()->migration.execute(cu,"TABLEFLOW","tf_cu_ex_src",cu,"TABLEFLOW","tf_cu_ex_dst",mappings,100,false,UUID.randomUUID().toString()));
            assertEquals(1,new JdbcReader(explorer).count(cu,"TABLEFLOW","tf_cu_ex_dst"));
            sql(cu,"DROP TABLE IF EXISTS tableflow.tf_cu_identity","CREATE TABLE tableflow.tf_cu_identity(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(20))");
            assertTrue(explorer.columns(cu,"TABLEFLOW","tf_cu_identity").items().stream().anyMatch(c->c.name().equals("id")&&c.autoIncrement()));
            assertThrows(IllegalArgumentException.class,()->migration.check(cu,"TABLEFLOW","tf_cu_ex_src",cu,"TABLEFLOW","tf_cu_identity",List.of(new Mapping("id","id"),new Mapping("name","name")),100,false,true));
            var tls=new Credentials(cu.dbms(),cu.host(),cu.port(),cu.database(),cu.username(),cu.password(),true,null,cu.driverId());
            assertEquals("JDBC_TLS_UNVERIFIED",assertThrows(Failure.class,()->explorer.test(tls)).code);
            sql(cu,"CREATE TRIGGER tf_cu_ex_tr BEFORE INSERT ON tableflow.tf_cu_ex_dst EXECUTE REJECT");
            assertThrows(RuntimeException.class,()->migration.check(cu,"TABLEFLOW","tf_cu_ex_src",cu,"TABLEFLOW","tf_cu_ex_dst",mappings,100,false));
            sql(cu,"DROP TRIGGER tf_cu_ex_tr");
        });
    }
    @Test void metadataNamesWithUnderscoresDoNotIncludeSimilarTables() throws Exception {
        withCubrid(cu -> {
            sql(cu,"DROP TABLE IF EXISTS tableflow.tf_cu_pattern","DROP TABLE IF EXISTS tableflow.tfxcuxpattern",
                "CREATE TABLE tableflow.tf_cu_pattern(id BIGINT PRIMARY KEY, wanted VARCHAR(20))",
                "CREATE TABLE tableflow.tfxcuxpattern(unrelated VARCHAR(20))",
                "INSERT INTO tableflow.tf_cu_pattern VALUES(1,'selected')");
            assertEquals(List.of("id","wanted"),explorer.columns(cu,"TABLEFLOW","tf_cu_pattern").items().stream().map(ColumnInfo::name).toList());
            assertTrue(explorer.tables(cu,"TABLEFLOW").items().contains("tf_cu_pattern"));
            assertTrue(explorer.tables(cu,"DBA").items().stream().noneMatch(t -> t.contains("tf_cu_pattern")));
            var data=new JdbcReader(explorer).read(cu,"TABLEFLOW","tf_cu_pattern",List.of("id","wanted"),10);
            assertEquals(1,data.rows().size());
        });
    }
    @Test void batchOrdersParentBeforeChildAndCreatesAnEmptyTable() throws Exception {
        withCubrid(cu -> {
            sql(pg,"DROP TABLE IF EXISTS public.\"TF_CU_BATCH_C\"","DROP TABLE IF EXISTS public.\"TF_CU_BATCH_P\"","DROP TABLE IF EXISTS public.\"TF_CU_BATCH_E\"",
                "CREATE TABLE public.\"TF_CU_BATCH_P\"(\"ID\" BIGINT PRIMARY KEY)",
                "CREATE TABLE public.\"TF_CU_BATCH_C\"(\"ID\" BIGINT PRIMARY KEY,\"PID\" BIGINT REFERENCES public.\"TF_CU_BATCH_P\"(\"ID\"))",
                "CREATE TABLE public.\"TF_CU_BATCH_E\"(\"ID\" BIGINT PRIMARY KEY)",
                "INSERT INTO public.\"TF_CU_BATCH_P\" VALUES(1)","INSERT INTO public.\"TF_CU_BATCH_C\" VALUES(2,1)");
            sql(cu,"DROP TABLE IF EXISTS tableflow.tf_cu_batch_c","DROP TABLE IF EXISTS tableflow.tf_cu_batch_p","DROP TABLE IF EXISTS tableflow.tf_cu_batch_e");
            var options=new dev.tableflow.load.BatchMigration.Options(false,false,false,true,"skip",100);
            var selected=List.of("TF_CU_BATCH_C","TF_CU_BATCH_E","TF_CU_BATCH_P");
            var plan=batch.plan(pg,"public",cu,"TABLEFLOW",selected,options);
            assertFalse(plan.blocked(),plan.toString());
            var order=plan.items().stream().map(dev.tableflow.load.BatchMigration.PlanItem::table).toList();
            assertTrue(order.indexOf("TF_CU_BATCH_P")<order.indexOf("TF_CU_BATCH_C"));
            var progress=new dev.tableflow.load.BatchMigration.BatchProgress(){
                public void items(List<dev.tableflow.load.BatchMigration.Item> items){}
                public void total(int chunks,long rows){} public void chunk(int chunks,long rows,int replayed){} public boolean cancelled(){return false;}
            };
            assertEquals(2,batch.run(UUID.randomUUID().toString(),pg,"public",cu,"TABLEFLOW",selected,options,List.of(),progress).insertedRows());
            assertEquals(1,explorer.extras(cu,"TABLEFLOW","tf_cu_batch_c").foreignKeys().size());
            assertEquals(0,new JdbcReader(explorer).count(cu,"TABLEFLOW","tf_cu_batch_e"));
        });
    }
    @Test void rejectsSubMillisecondDatetimeInsteadOfTruncatingIt() throws Exception {
        withCubrid(cu -> {
            sql(pg,"DROP TABLE IF EXISTS public.tf_cu_precision_src","CREATE TABLE public.tf_cu_precision_src(id BIGINT PRIMARY KEY, updated TIMESTAMP(6))",
                "INSERT INTO public.tf_cu_precision_src VALUES(1,'2026-11-25 12:34:56.123456')");
            sql(cu,"DROP TABLE IF EXISTS tableflow.tf_cu_precision_dst");
            assertThrows(dev.tableflow.load.DatabaseLoader.LoadFailure.class,()->migration.execute(pg,"public","tf_cu_precision_src",cu,"TABLEFLOW","tf_cu_precision_dst",
                List.of(new Mapping("id","id"),new Mapping("updated","updated")),100,true,UUID.randomUUID().toString()));
            assertEquals(0,new JdbcReader(explorer).count(cu,"TABLEFLOW","tf_cu_precision_dst"));
        });
    }
    @Test void roundTripsPostgresWithUnicodeDatesDecimalsNullsAndMetadata() throws Exception {
        withCubrid(cu -> {
            sql(pg, "DROP TABLE IF EXISTS public.tf_cu_pg_src", "DROP TABLE IF EXISTS public.tf_cu_pg_back",
                "CREATE TABLE public.tf_cu_pg_src (id BIGINT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2), day DATE, updated TIMESTAMP(3), active BOOLEAN)",
                "INSERT INTO public.tf_cu_pg_src VALUES (1,'한글 😀 끝 공백 ',12.34,'2026-11-25','2026-11-25 12:34:56.123',true),(2,NULL,NULL,NULL,NULL,NULL)");
            sql(cu, "DROP TABLE IF EXISTS tableflow.tf_cu_pg_dst");
            var mappings = List.of("id", "name", "amount", "day", "updated", "active").stream().map(n -> new Mapping(n,n)).toList();
            String id = UUID.randomUUID().toString();
            assertEquals(2, migration.execute(pg,"public","tf_cu_pg_src",cu,"TABLEFLOW","tf_cu_pg_dst",mappings,100,true,id).insertedRows());
            assertTrue(migration.execute(pg,"public","tf_cu_pg_src",cu,"TABLEFLOW","tf_cu_pg_dst",mappings,100,true,id).replayed());
            assertTrue(explorer.schemas(cu).items().contains("TABLEFLOW"));
            assertTrue(explorer.tables(cu,"TABLEFLOW").items().contains("tf_cu_pg_dst"));
            assertTrue(explorer.columns(cu,"TABLEFLOW","tf_cu_pg_dst").items().stream().anyMatch(c -> c.name().equals("id") && c.primaryKey()));
            assertTrue(verifier.verify(pg,"public","tf_cu_pg_src",cu,"TABLEFLOW","tf_cu_pg_dst",mappings,null).allMatch());
            assertEquals(2, migration.execute(cu,"TABLEFLOW","tf_cu_pg_dst",pg,"public","tf_cu_pg_back",mappings,100,true,UUID.randomUUID().toString()).insertedRows());
            assertTrue(verifier.verify(cu,"TABLEFLOW","tf_cu_pg_dst",pg,"public","tf_cu_pg_back",mappings,null).allMatch());
            assertEquals(1, new JdbcReader(explorer).readPage(cu,"TABLEFLOW","tf_cu_pg_dst",List.of("id"),1,1).rows().size());
        });
    }
    @Test void cancelsThenResumesBulkWithoutDuplicateRows() throws Exception {
        withCubrid(cu -> {
            sql(pg, "DROP TABLE IF EXISTS public.tf_cu_bulk_src", "CREATE TABLE public.tf_cu_bulk_src (id INTEGER PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO public.tf_cu_bulk_src SELECT i,'name' FROM generate_series(1,2501) AS i");
            sql(cu, "DROP TABLE IF EXISTS tableflow.tf_cu_bulk_dst");
            var mappings = List.of(new Mapping("id","id"),new Mapping("name","name"));
            String id = UUID.randomUUID().toString();
            class Progress implements MigrationService.Progress {
                boolean stop; final boolean cancel;
                Progress(boolean cancel) { this.cancel = cancel; }
                public void total(int chunks,long rows) {} public void chunk(int chunks,long rows,int replayed) { if (cancel) stop = true; } public boolean cancelled() { return stop; }
            }
            var cancelled=assertThrows(dev.tableflow.load.DatabaseLoader.LoadFailure.class, () -> migration.runBulk(pg,"public","tf_cu_bulk_src",cu,"TABLEFLOW","tf_cu_bulk_dst",mappings,3000,true,false,id,new Progress(true),1000));
            assertEquals("MIGRATION_CANCELLED",cancelled.code);
            assertEquals(1000,new JdbcReader(explorer).count(cu,"TABLEFLOW","tf_cu_bulk_dst"));
            var result = migration.runBulk(pg,"public","tf_cu_bulk_src",cu,"TABLEFLOW","tf_cu_bulk_dst",mappings,3000,true,false,id,new Progress(false),1000);
            assertEquals(1,result.replayedChunks()); assertEquals(2501,new JdbcReader(explorer).count(cu,"TABLEFLOW","tf_cu_bulk_dst"));
        });
    }
}
