package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.JdbcLoader;
import dev.tableflow.load.MigrationService;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 파일(표) → 대상 DB에 없는 테이블을 만들어 적재. 일회용 테스트 컨테이너 전용입니다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class FileLoadCreateIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired JdbcLoader loader;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=(db==Dbms.mysql||db==Dbms.mariadb)?"`":"\"";return c+id+c;}
    void dropQuiet(Connection c,String name){try(var st=c.createStatement()){st.execute("DROP TABLE "+name);}catch(SQLException ignored){}}

    Table sample() {
        var cols=List.of(new Column("c1","EMP_NO","EMP_NO",DataType.string,false,true,20,null,null),
            new Column("c2","EMP_NM","EMP_NM",DataType.string,false,true,null,null,null),
            new Column("c3","HIRE_DT","HIRE_DT",DataType.date,false,true,null,null,null),
            new Column("c4","SALARY","SALARY",DataType.decimal,false,true,null,12,2));
        String[][] data={{"001","김하늘","2026-01-15","3500000.50"},{"002","이도윤 O'Brien","2026-03-02","4200000"},{"003","박서연","2026-06-18","3800000.25"}};
        var rows=new ArrayList<Row>();
        for(int i=0;i<data.length;i++){var cells=new LinkedHashMap<String,Cell>();for(int k=0;k<4;k++)cells.put("c"+(k+1),new Cell(data[i][k],data[i][k],null,false));rows.add(new Row("r"+(i+1),cells));}
        return new Table("1.0",UUID.randomUUID().toString(),"employees",new Source("employees.csv","csv","file"),cols,rows);
    }

    @ParameterizedTest(name="pk {0}")
    @EnumSource(Dbms.class)
    void createsTableWithChosenPrimaryKeyAndRejectsDuplicates(Dbms db)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(db.name()));
        var c=credentials(db); String sc=schema(db), name="TF_FILE_PK";
        try(var conn=DriverManager.getConnection(explorer.url(c),explorer.properties(c))) {
            dropQuiet(conn,q(db,name)); dropQuiet(conn,q(db,"tableflow_load_receipts"));
            var plan=migration.planCreate(c,sample(),sc,name,List.of("EMP_NO"));
            assertTrue(plan.ddl().get(0).contains("PRIMARY KEY"),plan.ddl().get(0));
            assertTrue(plan.warnings().stream().noneMatch(w->w.contains("기본 키 없이")),plan.warnings().toString());
            assertThrows(IllegalArgumentException.class,()->migration.planCreate(c,sample(),sc,name,List.of("NOPE")));
            var dup=sample(); dup.rows().get(1).cells().put("c1",new Cell("001","001",null,false)); // EMP_NO 중복
            var e=assertThrows(IllegalArgumentException.class,()->migration.planCreate(c,dup,sc,name,List.of("EMP_NO")));
            assertTrue(e.getMessage().contains("중복 값 1건"),e.getMessage());
            migration.ensureTable(c,sample(),sc,name,List.of("EMP_NO"));
            assertTrue(explorer.columns(c,sc,name).items().stream().anyMatch(col->col.name().equalsIgnoreCase("EMP_NO")&&col.primaryKey()));
            dropQuiet(conn,q(db,name)); dropQuiet(conn,q(db,"tableflow_load_receipts"));
        }
    }

    @ParameterizedTest(name="{0}")
    @EnumSource(Dbms.class)
    void createsTableThenLoadsAndRetriesIdempotently(Dbms db)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(db.name()));
        var c=credentials(db); String sc=schema(db), name="TF_FILE_NEW";
        try(var conn=DriverManager.getConnection(explorer.url(c),explorer.properties(c))) {
            dropQuiet(conn,q(db,name)); dropQuiet(conn,q(db,"tableflow_load_receipts"));
            var plan=migration.planCreate(c,sample(),sc,name);
            assertTrue(plan.createsTable()); assertEquals(3,plan.rows()); assertEquals(List.of("EMP_NO","EMP_NM","HIRE_DT","SALARY"),plan.columns());
            assertTrue(plan.ddl().get(0).startsWith("CREATE TABLE") && plan.ddl().size()==2,plan.ddl().toString());   // 테이블 + 영수증
            assertTrue(plan.warnings().stream().anyMatch(w->w.contains("기본 키")));
            assertThrows(IllegalArgumentException.class,()->migration.planCreate(c,sample(),sc,"tableflow_load_receipts"));

            String id=UUID.randomUUID().toString(); var table=sample();
            migration.ensureTable(c,table,sc,name);
            var done=loader.load(c,table,sc,name,id);
            assertEquals(3,done.insertedRows()); assertFalse(done.replayed());
            migration.ensureTable(c,table,sc,name);                   // 같은 요청의 재시도: 이미 있으므로 건너뜀
            assertTrue(loader.load(c,table,sc,name,id).replayed());
            try(var st=conn.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+q(db,name))){rs.next();assertEquals(3,rs.getLong(1));}
            assertThrows(IllegalArgumentException.class,()->migration.planCreate(c,sample(),sc,name),"이미 있는 테이블은 신규 생성으로 점검할 수 없다");
            dropQuiet(conn,q(db,name)); dropQuiet(conn,q(db,"tableflow_load_receipts"));
        }
    }
}
