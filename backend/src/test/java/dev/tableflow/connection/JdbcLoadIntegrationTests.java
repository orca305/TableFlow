package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.*;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.parser.FileParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated empty disposable databases only; never point at a production database. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class JdbcLoadIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired JdbcLoader loader; @Autowired FileParser parser;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String quote(Dbms db,String id){String q=(db==Dbms.mysql || db==Dbms.mariadb)?"`":"\"";return q+id+q;}
    Table data(String rows)throws Exception {
        Table t=parser.parse(new MockMultipartFile("file","test.csv","text/csv",("EMP_NO,EMP_NM,HIRE_DT,SALARY\n"+rows).getBytes(java.nio.charset.StandardCharsets.UTF_8)),null,"UTF-8",",").tables().getFirst();
        var types=List.of(DataType.string,DataType.string,DataType.date,DataType.decimal);
        var cols=new ArrayList<Column>();for(int i=0;i<4;i++){var col=t.columns().get(i);cols.add(new Column(col.id(),col.name(),col.name(),types.get(i),false,true,null,null,null));}
        return new Table(t.schemaVersion(),t.tableId(),t.name(),t.source(),cols,t.rows());
    }
    @ParameterizedTest @EnumSource(Dbms.class)
    void actualConnectionMetadataCommitReplayAndFullRollback(Dbms db)throws Exception {
        Assumptions.assumeTrue(Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(",")).contains(db.name()));
        var c=credentials(db);String schema=db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";
        try(var connection=DriverManager.getConnection(explorer.url(c),explorer.properties(c))) {
            String target=quote(db,"TF_LOAD_TEST"), receipt=quote(db,"tableflow_load_receipts");
            // The fixture schema is exclusive to these disposable test containers.
            try(var st=connection.createStatement()) {
                for(String name:List.of(target,receipt))try{st.execute("DROP TABLE "+name);}catch(SQLException ignored){}
                String text=db==Dbms.oracle?"VARCHAR2":"VARCHAR";String decimal=db==Dbms.oracle?"NUMBER":"DECIMAL";
                st.execute("CREATE TABLE "+target+" ("+quote(db,"EMP_NO")+" "+text+"(20) PRIMARY KEY,"+quote(db,"EMP_NM")+" "+text+"(100) NOT NULL,"+quote(db,"HIRE_DT")+" DATE NOT NULL,"+quote(db,"SALARY")+" "+decimal+"(20,2) NOT NULL CHECK ("+quote(db,"SALARY")+">=0))"+(db==Dbms.mysql || db==Dbms.mariadb?" ENGINE=InnoDB":""));
                st.execute("CREATE TABLE "+receipt+" (request_id "+text+"(36) PRIMARY KEY,payload_hash "+text+"(64) NOT NULL,table_name "+text+"(128) NOT NULL,inserted_rows INTEGER NOT NULL)"+(db==Dbms.mysql || db==Dbms.mariadb?" ENGINE=InnoDB":""));
            }
            var server=explorer.test(c);assertFalse(server.product().isBlank());
            System.out.println("JDBC LIVE VERIFIED: "+db+" | "+server.serverVersion()+" | "+server.driverVersion());
            assertTrue(explorer.schemas(c).items().contains(schema));assertTrue(explorer.tables(c,schema).items().contains("TF_LOAD_TEST"));
            assertTrue(explorer.columns(c,schema,"TF_LOAD_TEST").items().stream().anyMatch(col->col.name().equals("EMP_NO") && col.primaryKey()));
            var invalid=new Credentials(db,c.host(),c.port(),c.database(),c.username(),"intentionally-invalid",false);
            assertEquals("JDBC_AUTH",assertThrows(Failure.class,()->explorer.test(invalid)).code);
            Table table=data("001,O'Brien 한글\\path,2026-01-15,12345678901234567.12\n002,Other,2026-03-02,4200000\n");
            assertEquals(2,loader.check(c,table,schema,"TF_LOAD_TEST").rows());
            String id=UUID.randomUUID().toString();assertEquals(2,loader.load(c,table,schema,"TF_LOAD_TEST",id).insertedRows());assertTrue(loader.load(c,table,schema,"TF_LOAD_TEST",id).replayed());
            try(var st=connection.createStatement();var rs=st.executeQuery("SELECT "+quote(db,"EMP_NM")+","+quote(db,"SALARY")+" FROM "+target+" WHERE "+quote(db,"EMP_NO")+"='001'")) {
                assertTrue(rs.next());assertEquals("O'Brien 한글\\path",rs.getString(1));assertEquals(0,new java.math.BigDecimal("12345678901234567.12").compareTo(rs.getBigDecimal(2)));
            }
            var conflict=data("003,Changed,2026-01-15,1\n");assertEquals("LOAD_REQUEST_CONFLICT",assertThrows(DatabaseLoader.LoadFailure.class,()->loader.load(c,conflict,schema,"TF_LOAD_TEST",id)).code);
            StringBuilder bad=new StringBuilder();for(int i=0;i<300;i++)bad.append("B").append(i).append(",Rollback,2026-01-15,").append(i==299?-1:1).append("\n");
            String failedId=UUID.randomUUID().toString();Table failing=data(bad.toString());
            assertEquals("LOAD_REJECTED",assertThrows(DatabaseLoader.LoadFailure.class,()->loader.load(c,failing,schema,"TF_LOAD_TEST",failedId)).code);
            try(var st=connection.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+target)){rs.next();assertEquals(2,rs.getInt(1));}
            try(var st=connection.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+receipt)){rs.next();assertEquals(1,rs.getInt(1));}
            Table concurrent=data("003,Concurrent,2026-01-15,1\n");String concurrentId=UUID.randomUUID().toString();
            try(var executor=Executors.newFixedThreadPool(2)) {
                var a=executor.submit(()->loader.load(c,concurrent,schema,"TF_LOAD_TEST",concurrentId));var b=executor.submit(()->loader.load(c,concurrent,schema,"TF_LOAD_TEST",concurrentId));
                assertNotEquals(a.get(40,TimeUnit.SECONDS).replayed(),b.get(40,TimeUnit.SECONDS).replayed());
            }
            try(var st=connection.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+target)){rs.next();assertEquals(3,rs.getInt(1));}
        }
    }
}
