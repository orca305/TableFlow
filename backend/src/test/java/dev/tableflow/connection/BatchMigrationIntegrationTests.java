package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.BatchMigration;
import dev.tableflow.load.BatchMigration.Item;
import dev.tableflow.load.BatchMigration.Options;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 여러 테이블 일괄 이관: 부모 먼저 순서, 빈 테이블 생성, FK 복제, 이미 있는 테이블 건너뜀, 실패 후 재개를 실서버에서 확인한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class BatchMigrationIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired BatchMigration batch;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=(db==Dbms.mysql||db==Dbms.mariadb)?"`":"\"";return c+id+c;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void dropQuiet(Connection c,String name){try(var st=c.createStatement()){st.execute("DROP TABLE "+name);}catch(SQLException ignored){}}
    long count(Connection c,Dbms db,String table)throws SQLException{try(var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+q(db,table))){rs.next();return rs.getLong(1);}}
    String text(Dbms db){return db==Dbms.oracle?"VARCHAR2":"VARCHAR";}
    String actual(Credentials c,String schema,String wanted){for(String t:explorer.tables(c,schema).items())if(t.equalsIgnoreCase(wanted))return t;return null;}
    void cleanTarget(Connection t,Credentials tgt,Dbms to,String ts){
        for(String n:List.of("TF_B_C","TF_B_E","TF_B_P")){String a=actual(tgt,ts,n);if(a!=null)dropQuiet(t,q(to,a));}
        String r=actual(tgt,ts,"tableflow_load_receipts"); if(r!=null)dropQuiet(t,q(to,r));
    }
    static class Recording implements BatchMigration.BatchProgress {
        volatile List<Item> items=List.of(); public boolean cancelled(){return false;}
        public void total(int chunks,long rows){} public void chunk(int d,long rows,int r){} public void items(List<Item> i){items=List.copyOf(i);}
    }
    static Item item(List<Item> items,String table){return items.stream().filter(i->i.table().equals(table)).findFirst().orElseThrow();}

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void migratesSeveralTablesInParentFirstOrder(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        String innodb=(from==Dbms.mysql||from==Dbms.mariadb)?" ENGINE=InnoDB":"";
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            cleanTarget(t,tgt,to,ts);
            for(String n:List.of("TF_B_C","TF_B_E","TF_B_P")) dropQuiet(s,q(from,n));
            exec(s,"CREATE TABLE "+q(from,"TF_B_P")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"NAME")+" "+text(from)+"(40))"+innodb);
            exec(s,"CREATE TABLE "+q(from,"TF_B_C")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"PID")+" INTEGER,"+q(from,"NOTE")+" "+text(from)+"(40),CONSTRAINT "+q(from,"FK_TF_B_C")+" FOREIGN KEY ("+q(from,"PID")+") REFERENCES "+q(from,"TF_B_P")+"("+q(from,"ID")+"))"+innodb);
            exec(s,"CREATE TABLE "+q(from,"TF_B_E")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY)"+innodb);
            for(int i=1;i<=3;i++) exec(s,"INSERT INTO "+q(from,"TF_B_P")+" VALUES ("+i+",'부모"+i+"')");
            for(int i=1;i<=4;i++) exec(s,"INSERT INTO "+q(from,"TF_B_C")+" VALUES ("+i+","+(i%3+1)+",'자식"+i+"')");

            var opt=new Options(false,false,false,true,"skip",100);
            List<String> selected=List.of("TF_B_C","TF_B_E","TF_B_P"); // 일부러 자식을 먼저 선택
            var plan=batch.plan(src,ss,tgt,ts,selected,opt);
            assertFalse(plan.blocked(),plan.toString());
            assertTrue(plan.items().stream().map(BatchMigration.PlanItem::table).toList().indexOf("TF_B_P")<plan.items().stream().map(BatchMigration.PlanItem::table).toList().indexOf("TF_B_C"),"부모가 먼저: "+plan.items());
            assertEquals(7,plan.totalRows()); assertTrue(plan.items().stream().allMatch(i->i.action().equals("create")));

            // 실행: 순서대로 만들고 적재, 빈 테이블도 생성, FK 복제
            var progress=new Recording();
            var out=batch.run("batch-1",src,ss,tgt,ts,selected,opt,List.of(),progress);
            assertEquals(7,out.insertedRows());
            assertTrue(progress.items.stream().allMatch(i->i.state().equals("succeeded")),progress.items.toString());
            assertEquals(3,count(t,to,actual(tgt,ts,"TF_B_P"))); assertEquals(4,count(t,to,actual(tgt,ts,"TF_B_C"))); assertEquals(0,count(t,to,actual(tgt,ts,"TF_B_E")));
            assertEquals(1,explorer.extras(tgt,ts,actual(tgt,ts,"TF_B_C")).foreignKeys().size(),"자식에 FK가 복제되어야 함");

            // 같은 이름의 테이블이 이미 있으면 건너뜀(사전 점검과 실행 모두)
            var again=batch.plan(src,ss,tgt,ts,selected,opt);
            assertTrue(again.items().stream().allMatch(i->i.action().equals("skip")),again.items().toString());
            var skipProgress=new Recording();
            assertEquals(0,batch.run("batch-2",src,ss,tgt,ts,selected,opt,List.of(),skipProgress).insertedRows());
            assertTrue(skipProgress.items.stream().allMatch(i->i.state().equals("skipped")),skipProgress.items.toString());
            assertEquals(3,count(t,to,actual(tgt,ts,"TF_B_P")));

            // 실패 후 재개: 자식 대상을 구조가 다른 테이블로 미리 만들어 두고 추가 적재 → 자식에서 실패, 부모는 완료
            cleanTarget(t,tgt,to,ts);
            String childName=BatchMigration.convert(to,"TF_B_C","TBL");
            exec(t,"CREATE TABLE "+q(to,childName)+" ("+q(to,BatchMigration.convert(to,"ID","COL"))+" INTEGER PRIMARY KEY)");
            var append=new Options(false,false,false,true,"append",100);
            var failing=new Recording();
            var failure=assertThrows(RuntimeException.class,()->batch.run("batch-3",src,ss,tgt,ts,selected,append,List.of(),failing));
            assertTrue(failure.getMessage().contains("TF_B_C"),failure.getMessage());
            assertEquals("succeeded",item(failing.items,"TF_B_P").state()); assertEquals("failed",item(failing.items,"TF_B_C").state()); assertEquals("succeeded",item(failing.items,"TF_B_E").state()); // 같은 단계(부모 없음)의 빈 테이블은 자식보다 먼저 처리
            assertEquals(3,count(t,to,actual(tgt,ts,"TF_B_P")));
            // 원인(잘못된 대상 테이블)을 고치고 같은 요청으로 재개 → 부모는 다시 읽지 않고 자식부터 이어서
            dropQuiet(t,q(to,actual(tgt,ts,"TF_B_C")));
            exec(t,"UPDATE "+q(to,actual(tgt,ts,"TF_B_P"))+" SET "+q(to,BatchMigration.convert(to,"NAME","COL"))+"='변조' WHERE "+q(to,BatchMigration.convert(to,"ID","COL"))+"=1"); // 부모를 다시 적재했다면 덮어써졌거나 중복 오류가 났을 값
            var resumed=new Recording();
            var done=batch.run("batch-3",src,ss,tgt,ts,selected,append,failing.items,resumed);
            assertTrue(resumed.items.stream().allMatch(i->i.state().equals("succeeded")),resumed.items.toString());
            assertEquals(4,count(t,to,actual(tgt,ts,"TF_B_C"))); assertEquals(0,count(t,to,actual(tgt,ts,"TF_B_E")));
            assertEquals(3,count(t,to,actual(tgt,ts,"TF_B_P")));
            try(var st=t.createStatement();var rs=st.executeQuery("SELECT "+q(to,BatchMigration.convert(to,"NAME","COL"))+" FROM "+q(to,actual(tgt,ts,"TF_B_P"))+" WHERE "+q(to,BatchMigration.convert(to,"ID","COL"))+"=1")){rs.next();assertEquals("변조",rs.getString(1),"완료된 부모 테이블은 재개 때 다시 적재하지 않는다");}
            assertEquals(7,done.insertedRows()); // 이전 실행에서 끝난 부모 3행 + 이번 자식 4행

            cleanTarget(t,tgt,to,ts);
            for(String n:List.of("TF_B_C","TF_B_E","TF_B_P")) dropQuiet(s,q(from,n));
        }
    }
}
