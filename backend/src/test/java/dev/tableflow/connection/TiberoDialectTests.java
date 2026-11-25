package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.TableDdl;
import dev.tableflow.model.Table.DataType;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TiberoDialectTests {
    @Test void driverUrlTimeoutAndCapabilityAreExplicit() {
        var explorer = new JdbcExplorer(true,"localhost");
        var c = new Credentials(Dbms.tibero,"localhost",8629,"tibero","reader","test-only",false);
        assertEquals("jdbc:tibero:thin:@localhost:8629:tibero",explorer.url(c));
        assertEquals("5000",explorer.properties(c).getProperty("login_timeout"));
        assertEquals("10000",explorer.properties(c).getProperty("read_timeout"));
        assertEquals("JDBC_EXPERIMENTAL_DISABLED",assertThrows(Failure.class,()->explorer.test(c)).code);
        var capability = explorer.registry().drivers().stream().filter(d->d.dbms()==Dbms.tibero).findFirst().orElseThrow().capabilities();
        assertTrue(capability.experimental()); assertFalse(capability.verifiedTls()); assertFalse(capability.preserveKeys());
        org.springframework.test.util.ReflectionTestUtils.setField(explorer,"tiberoEnabled",true);
        var tls=new Credentials(c.dbms(),c.host(),c.port(),c.database(),c.username(),c.password(),true);
        assertEquals("JDBC_TLS_UNVERIFIED",assertThrows(Failure.class,()->explorer.test(tls)).code);
    }
    @Test void usesNumberAndVarcharTypesWithoutMysqlFallback() {
        String ddl = TableDdl.createTable(Dbms.tibero,"TABLEFLOW","DST",List.of(
            new TableDdl.Col("ID",DataType.integer,false,true,19,0),new TableDdl.Col("NAME",DataType.string,true,false,100,0)));
        assertTrue(ddl.contains("NUMBER(19)")); assertTrue(ddl.contains("VARCHAR2(100 CHAR)"));
        assertFalse(ddl.contains("ENGINE="));
        assertEquals("1",TableDdl.convertDefault(Dbms.postgresql,Dbms.tibero,DataType.bool,"true"));
    }
    @Test void rownumWrapperPreservesOrderAndAvoidsAliasCollisionInOracleModeH2() throws Exception {
        // SQL 래퍼 알고리즘만 검증한다. Tibero 실서버 검증이 아니다.
        try (var c = DriverManager.getConnection("jdbc:h2:mem:tibero_page;MODE=Oracle");var st=c.createStatement()) {
            st.execute("CREATE TABLE DATA(ID INTEGER PRIMARY KEY, TF_PAGE_ROW_NUMBER INTEGER)");
            st.execute("INSERT INTO DATA VALUES(1,11),(2,22),(3,33),(4,44)");
            String query=SqlDialect.paginate(Dbms.tibero,"SELECT ID, TF_PAGE_ROW_NUMBER FROM DATA ORDER BY ID",2,1,true,List.of("ID","TF_PAGE_ROW_NUMBER"));
            try(var rows=st.executeQuery(query)){assertTrue(rows.next());assertEquals(2,rows.getInt(1));assertTrue(rows.next());assertEquals(3,rows.getInt(1));assertFalse(rows.next());}
        }
    }
}
