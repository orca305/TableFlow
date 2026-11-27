package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.BatchMigration;
import dev.tableflow.load.BatchMigration.Item;
import dev.tableflow.load.BatchMigration.Options;
import dev.tableflow.load.BatchMigration.TableOption;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Extras;
import dev.tableflow.load.MigrationService.Mapping;
import dev.tableflow.load.ValueTransform.Step;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 적재 실패 시 새로 만든 빈 테이블을 정리하는 선택 옵션: 지우는 경우와 절대 지우지 않는 경우를 실서버에서 확인한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class DropEmptyTableIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired BatchMigration batch;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    static boolean my(Dbms db){return db==Dbms.mysql||db==Dbms.mariadb;}
    String q(Dbms db,String id){String c=my(db)?"`":"\"";return c+id+c;}
    String name(Dbms db,String upper){return db==Dbms.postgresql?upper.toLowerCase(Locale.ROOT):upper;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void dropQuiet(Connection c,Dbms db,String table){try(var st=c.createStatement()){st.execute("DROP TABLE "+q(db,table));}catch(SQLException ignored){}}
    String count(Connection c,Dbms db,String table)throws SQLException{try(var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+q(db,table))){rs.next();return rs.getString(1);}}
    boolean exists(Credentials c,String schema,String table){return explorer.tables(c,schema).items().contains(table);}
    static class Progress implements MigrationService.Progress {
        boolean cancel; String created;
        public boolean cancelled(){return cancel;} public void total(int chunks,long rows){} public void chunk(int c,long rows,int replayed){}
        public boolean createdTarget(String key){return key.equals(created);} public void targetCreated(String key){created=key;} public void targetDropped(String key){if(key.equals(created))created=null;}
    }
    static class Recording implements BatchMigration.BatchProgress {
        volatile List<Item> items=List.of();
        public boolean cancelled(){return false;} public void total(int c,long r){} public void chunk(int d,long r,int p){} public void items(List<Item> i){items=List.copyOf(i);}
    }

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,mysql"})
    void dropsOnlyAnEmptyTableCreatedByTheFailingRun(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        String text=from==Dbms.oracle?"VARCHAR2":"VARCHAR", engine=my(from)?" ENGINE=InnoDB":"";
        String dst=name(to,"TF_DE_DST"), ok=name(to,"TF_DE_A_OK"), bad=name(to,"TF_DE_BAD"), old=name(to,"TF_DE_OLD");
        String id=name(to,"ID"), label=name(to,"NAME"), code=name(to,"CODE");
        var sources=List.of("TF_DE_A_OK","TF_DE_BAD");
        var targets=List.of(dst,ok,bad,old,"tableflow_load_receipts","TABLEFLOW_LOAD_RECEIPTS");
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            for(String n:sources) dropQuiet(s,from,n); for(String n:targets) dropQuiet(t,to,n);
            try {
                for(String n:sources) {
                    exec(s,"CREATE TABLE "+q(from,n)+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"NAME")+" "+text+"(40),"+q(from,"CODE")+" "+text+"(10))"+engine);
                    exec(s,"CREATE UNIQUE INDEX "+q(from,"UX_"+n)+" ON "+q(from,n)+" ("+q(from,"CODE")+")");
                }
                exec(s,"INSERT INTO "+q(from,"TF_DE_A_OK")+" VALUES (1,'a','c1')");
                s.setAutoCommit(false);
                for(int i=1;i<=1600;i++) exec(s,"INSERT INTO "+q(from,"TF_DE_BAD")+" VALUES ("+i+",'n"+(i%10)+"','c"+i+"')");
                s.commit(); s.setAutoCommit(true);
                var good=List.of(new Mapping("ID",id),new Mapping("NAME",label),new Mapping("CODE",code));
                // 표본(처음 200행) 밖의 행이 대상 길이를 넘는다 → 첫 분할이 실패한다
                var narrow=List.of(new Mapping("ID",id),new Mapping("NAME",label,null,5,null),new Mapping("CODE",code));
                exec(s,"UPDATE "+q(from,"TF_DE_BAD")+" SET "+q(from,"NAME")+"='too-long-value' WHERE "+q(from,"ID")+"=500");
                var off=new Extras(false,false,false,null,false); var on=new Extras(false,false,false,null,true);

                // 옵션을 끄면 기존 동작: 빈 테이블이 남는다
                var keepId=UUID.randomUUID().toString();
                assertThrows(RuntimeException.class,() -> migration.runBulk(src,ss,"TF_DE_BAD",tgt,ts,dst,narrow,10000,true,false,keepId,new Progress(),1000,off));
                assertTrue(exists(tgt,ts,dst)); assertEquals("0",count(t,to,dst)); dropQuiet(t,to,dst);

                // 옵션을 켜면 삭제하고, 만들었다는 기록도 지운다. 원인을 고쳐 같은 요청으로 다시 실행하면 새로 만든다
                var progress=new Progress(); var runId=UUID.randomUUID().toString();
                var failure=assertThrows(RuntimeException.class,() -> migration.runBulk(src,ss,"TF_DE_BAD",tgt,ts,dst,narrow,10000,true,false,runId,progress,1000,on));
                assertTrue(failure.getMessage().contains("분할 1/2") && failure.getMessage().contains("삭제했습니다"),failure.getMessage());
                assertFalse(exists(tgt,ts,dst)); assertNull(progress.created);
                assertEquals(1600,migration.runBulk(src,ss,"TF_DE_BAD",tgt,ts,dst,good,10000,true,false,runId,progress,1000,on).insertedRows());
                dropQuiet(t,to,dst);

                // 앞선 분할이 커밋되어 행이 있으면 남긴다
                exec(s,"UPDATE "+q(from,"TF_DE_BAD")+" SET "+q(from,"NAME")+"='n0' WHERE "+q(from,"ID")+"=500");
                exec(s,"UPDATE "+q(from,"TF_DE_BAD")+" SET "+q(from,"NAME")+"='too-long-value' WHERE "+q(from,"ID")+"=1500");
                var partial=assertThrows(RuntimeException.class,() -> migration.runBulk(src,ss,"TF_DE_BAD",tgt,ts,dst,narrow,10000,true,false,UUID.randomUUID().toString(),new Progress(),1000,on));
                assertFalse(partial.getMessage().contains("삭제했습니다"),partial.getMessage());
                assertEquals("1000",count(t,to,dst)); dropQuiet(t,to,dst);

                // 중지(취소)는 빈 테이블이어도 남긴다
                var cancelled=new Progress(); cancelled.cancel=true;
                assertThrows(RuntimeException.class,() -> migration.runBulk(src,ss,"TF_DE_BAD",tgt,ts,dst,good,10000,true,false,UUID.randomUUID().toString(),cancelled,1000,on));
                assertTrue(exists(tgt,ts,dst)); dropQuiet(t,to,dst);

                // 원래 있던 빈 테이블은 지우지 않는다
                exec(t,"CREATE TABLE "+q(to,old)+" ("+q(to,id)+" INTEGER PRIMARY KEY,"+q(to,label)+" VARCHAR(5),"+q(to,code)+" VARCHAR(10))"+(my(to)?" ENGINE=InnoDB":""));
                assertThrows(RuntimeException.class,() -> migration.runBulk(src,ss,"TF_DE_BAD",tgt,ts,old,good.stream().map(m -> new Mapping(m.source(),m.target())).toList(),10000,true,false,UUID.randomUUID().toString(),new Progress(),2000,on));
                assertTrue(exists(tgt,ts,old)); assertEquals("0",count(t,to,old));

                // 즉시 실행(한 트랜잭션): 고유 인덱스 위반으로 전체가 롤백되면 새 테이블을 삭제한다
                var clash=List.of(new Mapping("ID",id),new Mapping("NAME",label),new Mapping("CODE",code,null,null,null,
                    List.of(new Step("template",null,null,null,null,null,null,null,null,"same",null))));
                var immediate=assertThrows(RuntimeException.class,() -> migration.execute(src,ss,"TF_DE_BAD",tgt,ts,dst,clash,10000,true,false,UUID.randomUUID().toString(),new Extras(true,false,false,null,true)));
                assertTrue(immediate.getMessage().contains("삭제했습니다"),immediate.getMessage()); assertFalse(exists(tgt,ts,dst));

                // 일괄 이관: 실패한 테이블만 삭제하고 완료한 테이블은 남긴다. 원인을 고쳐 재개하면 완료된다
                exec(s,"UPDATE "+q(from,"TF_DE_BAD")+" SET "+q(from,"NAME")+"='n0' WHERE "+q(from,"ID")+"=1500");
                exec(s,"UPDATE "+q(from,"TF_DE_BAD")+" SET "+q(from,"NAME")+"='too-long-value' WHERE "+q(from,"ID")+"=500");
                var tables=List.of("TF_DE_A_OK","TF_DE_BAD");
                var options=new Options(false,false,false,false,"skip",1000,List.of(new TableOption("TF_DE_BAD",narrow,null)),true);
                var recording=new Recording();
                assertThrows(RuntimeException.class,() -> batch.run("drop-1",src,ss,tgt,ts,tables,options,List.of(),recording));
                var failed=recording.items.stream().filter(i -> i.table().equals("TF_DE_BAD")).findFirst().orElseThrow();
                assertEquals("failed",failed.state()); assertFalse(failed.created(),failed.toString()); assertTrue(failed.message().contains("삭제했습니다"),failed.message());
                assertTrue(exists(tgt,ts,ok)); assertEquals("1",count(t,to,ok)); assertFalse(exists(tgt,ts,bad));
                exec(s,"UPDATE "+q(from,"TF_DE_BAD")+" SET "+q(from,"NAME")+"='n0' WHERE "+q(from,"ID")+"=500");
                var resumed=new Recording();
                batch.run("drop-1",src,ss,tgt,ts,tables,options,recording.items,resumed);
                assertEquals("1600",count(t,to,bad),resumed.items.toString());
            } finally { s.setAutoCommit(true); for(String n:sources) dropQuiet(s,from,n); for(String n:targets) dropQuiet(t,to,n); }
        }
    }
}
