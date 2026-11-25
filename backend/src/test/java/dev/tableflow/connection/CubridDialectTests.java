package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.TableDdl;
import dev.tableflow.model.Table.DataType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CubridDialectTests {
    @Test void usesBrokerUrlAndRejectsUnverifiedTls() {
        var explorer=new JdbcExplorer(true,"localhost");
        var c=new Credentials(Dbms.cubrid,"localhost",33000,"sample","reader","test-only",true);
        assertEquals("jdbc:cubrid:localhost:33000:sample:::?connectTimeout=5&queryTimeout=30&charSet=utf-8",explorer.url(c));
        assertFalse(explorer.url(c).contains(c.password()));
        assertEquals("JDBC_TLS_UNVERIFIED",assertThrows(Failure.class,()->explorer.test(c)).code);
        assertEquals("tableflow.dst",SqlDialect.metadataTable(Dbms.cubrid,"TABLEFLOW","dst"));
        assertEquals("dst",SqlDialect.metadataName(Dbms.cubrid,"TABLEFLOW","tableflow.dst"));
    }
    @Test void ddlUsesCubridTypesAndLowercaseIdentifiers() {
        var columns=List.of(new TableDdl.Col("name",DataType.string,true,false,100,0),new TableDdl.Col("active",DataType.bool,true,false,0,0),new TableDdl.Col("updated",DataType.datetime,true,false,0,0));
        String ddl=TableDdl.createTable(Dbms.cubrid,"TABLEFLOW","dst",columns);
        assertTrue(ddl.contains("VARCHAR(100)"));assertTrue(ddl.contains("SMALLINT"));assertTrue(ddl.contains("DATETIME"));assertFalse(ddl.contains("ENGINE="));
        assertThrows(IllegalArgumentException.class,()->TableDdl.createTable(Dbms.cubrid,"TABLEFLOW","DST",columns));
    }
}
