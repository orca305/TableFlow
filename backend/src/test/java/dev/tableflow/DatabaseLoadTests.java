package dev.tableflow;

import dev.tableflow.load.DatabaseLoader;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_MYSQL", matches="true")
class DatabaseLoadTests {
    static final String NAME = "TF_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    static final String NON_TX = NAME + "_NON_TX";
    @Autowired DatabaseLoader loader;
    @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
        properties.add("tableflow.load.enabled", () -> "true");
        properties.add("tableflow.load.url", () -> System.getenv().getOrDefault("TABLEFLOW_TEST_MYSQL_URL", "jdbc:mysql://localhost:3307/tableflow"));
        properties.add("tableflow.load.user", () -> "tableflow");
        properties.add("tableflow.load.password", () -> System.getenv("TABLEFLOW_MYSQL_PASSWORD"));
        properties.add("tableflow.load.tables", () -> NAME + "," + NON_TX);
        properties.add("tableflow.load.ssl-mode", () -> "REQUIRED");
    }
    static Connection connection() throws SQLException {
        Properties p = new Properties(); p.setProperty("user", "tableflow"); p.setProperty("password", System.getenv("TABLEFLOW_MYSQL_PASSWORD")); p.setProperty("sslMode", "REQUIRED");
        return DriverManager.getConnection(System.getenv().getOrDefault("TABLEFLOW_TEST_MYSQL_URL", "jdbc:mysql://localhost:3307/tableflow"), p);
    }
    @BeforeAll static void prepare() throws Exception {
        try (var c = connection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE " + NAME + " (ID VARCHAR(40) PRIMARY KEY, LABEL VARCHAR(100), AMOUNT DECIMAL(30,4) NOT NULL CHECK(AMOUNT>=0), DAY DATE, AT_TIME DATETIME(6), FLAG TINYINT) ENGINE=InnoDB");
            s.execute("CREATE TABLE " + NON_TX + " (ID VARCHAR(40)) ENGINE=MyISAM");
        }
    }
    @AfterAll static void cleanup() throws Exception {
        try (var c = connection(); var s = c.createStatement(); var receipts = c.prepareStatement("DELETE FROM tableflow_load_receipts WHERE table_name=?")) {
            receipts.setString(1, NAME); receipts.executeUpdate();
            s.execute("DROP TABLE IF EXISTS " + NAME); s.execute("DROP TABLE IF EXISTS " + NON_TX);
        }
    }
    private Table data(List<String[]> values) {
        String[] names = {"ID", "LABEL", "AMOUNT", "DAY", "AT_TIME", "FLAG"};
        DataType[] types = {DataType.string, DataType.string, DataType.decimal, DataType.date, DataType.datetime, DataType.bool};
        List<Column> columns = new ArrayList<>();
        for (int i=0; i<names.length; i++) columns.add(new Column("c"+i, names[i], names[i], types[i], true, true, null, null, null));
        List<Table.Row> rows = new ArrayList<>();
        for (int r=0; r<values.size(); r++) {
            Map<String, Table.Cell> cells = new LinkedHashMap<>();
            for (int i=0; i<names.length; i++) cells.put("c"+i, new Table.Cell(values.get(r)[i], values.get(r)[i], null, false));
            rows.add(new Table.Row("r"+r, cells));
        }
        return new Table("1.0", "load", "load", new Source("data.csv", "csv", "parser"), columns, rows);
    }
    private String[] row(String id, String amount) { return new String[]{id, "김하늘 O'Brien \\'); DROP TABLE X; --", amount, "2026-11-19", "2026-11-19T10:20:30.123456", "true"}; }

    @Test void realTypedValuesAndPersistentIdempotency() throws Exception {
        String id = "typed-"+UUID.randomUUID().toString().substring(0,8), request = UUID.randomUUID().toString();
        Table table = data(Collections.singletonList(row(id, "12345678901234567890.1234")));
        assertEquals(1, loader.check(table, NAME).rows());
        assertFalse(loader.load(table, NAME, request).replayed());
        assertTrue(loader.load(table, NAME, request).replayed());
        try (var c = connection(); var s = c.prepareStatement("SELECT LABEL,AMOUNT,DAY,AT_TIME,FLAG FROM " + NAME + " WHERE ID=?")) {
            s.setString(1,id); try (var r=s.executeQuery()) {
                assertTrue(r.next()); assertEquals(row(id,"0")[1],r.getString(1));
                assertEquals("12345678901234567890.1234",r.getBigDecimal(2).toPlainString());
                assertEquals("2026-11-19",r.getDate(3).toString()); assertEquals(123456000,r.getTimestamp(4).getNanos()); assertTrue(r.getBoolean(5)); assertFalse(r.next());
            }
        }
        var conflicting = data(Collections.singletonList(row(id,"1")));
        assertEquals(409, assertThrows(DatabaseLoader.LoadFailure.class, () -> loader.load(conflicting, NAME, request)).status);
    }
    @Test void lateFailureRollsBackEarlierExecutedBatches() throws Exception {
        List<String[]> values = new ArrayList<>(); String prefix=UUID.randomUUID().toString().substring(0,8);
        for (int i=0; i<300; i++) values.add(row(prefix+"-"+i, i==299 ? "-1" : "10"));
        String request=UUID.randomUUID().toString();
        assertEquals(422, assertThrows(DatabaseLoader.LoadFailure.class, () -> loader.load(data(values),NAME,request)).status);
        try (var c=connection(); var s=c.prepareStatement("SELECT COUNT(*) FROM " + NAME + " WHERE ID LIKE ?")) {
            s.setString(1,prefix+"-%"); try(var r=s.executeQuery()){ r.next(); assertEquals(0,r.getInt(1)); }
            try(var receipt=c.prepareStatement("SELECT COUNT(*) FROM tableflow_load_receipts WHERE request_id=?")) {
                receipt.setString(1,request); try(var r=receipt.executeQuery()){r.next();assertEquals(0,r.getInt(1));}
            }
        }
    }
    @Test void metadataMismatchAndNontransactionalTablesAreRejected() {
        Table table=data(Collections.singletonList(row("bad-schema","1.12345")));
        assertEquals("LOAD_SCHEMA",assertThrows(DatabaseLoader.LoadFailure.class,()->loader.check(table,NAME)).code);
        assertEquals("LOAD_SCHEMA",assertThrows(DatabaseLoader.LoadFailure.class,()->loader.check(table,NON_TX)).code);
        assertThrows(IllegalArgumentException.class,()->loader.check(table,"TB_NOT_ALLOWED"));
    }
    @Test void concurrentRetriesCommitOnlyOnce() throws Exception {
        String id="race-"+UUID.randomUUID().toString().substring(0,8), request=UUID.randomUUID().toString();
        Table table=data(Collections.singletonList(row(id,"10")));
        try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CountDownLatch(1);
            var tasks=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{gate.await();return loader.load(table,NAME,request);})).toList();
            gate.countDown();
            var results=List.of(tasks.get(0).get(),tasks.get(1).get());
            assertEquals(1,results.stream().filter(DatabaseLoader.Result::replayed).count());
        }
    }
}
