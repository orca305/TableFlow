package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.MigrationJobs;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Extras;
import dev.tableflow.load.MigrationService.Mapping;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 단건 분할 이관에서 테이블 생성 뒤 인덱스·FK DDL이 실패하면, 원인을 해결하고 재개할 때 빠진 구조를 채우는지 실서버에서 확인한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true","tableflow.jobs.file=build/tmp/resume-structure-jobs.json"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class MigrationResumeStructureIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired MigrationJobs jobs;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=(db==Dbms.mysql||db==Dbms.mariadb)?"`":"\"";return c+id+c;}
    String name(Dbms db,String upper){return db==Dbms.postgresql?upper.toLowerCase(Locale.ROOT):upper;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void drop(Connection c,Dbms db,String table){try(var st=c.createStatement()){st.execute("DROP TABLE "+q(db,table));}catch(SQLException ignored){}}
    String one(Connection c,String sql)throws SQLException{try(var st=c.createStatement();var rs=st.executeQuery(sql)){rs.next();return rs.getString(1);}}
    MigrationJobs.Status await(String id)throws InterruptedException {
        long deadline=System.currentTimeMillis()+90_000;
        while(System.currentTimeMillis()<deadline){var s=jobs.get(id);if(!s.state().equals("queued")&&!s.state().equals("running"))return s;Thread.sleep(50);}
        throw new AssertionError("작업이 끝나지 않음");
    }

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,mysql"})
    void resumeAddsStructureThatFailedAfterTheTableWasCreated(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        boolean myTarget=to==Dbms.mysql||to==Dbms.mariadb;
        String innodb=(from==Dbms.mysql||from==Dbms.mariadb)?" ENGINE=InnoDB":"", text=from==Dbms.oracle?"VARCHAR2":"VARCHAR";
        String sp=name(from,"TF_RS_P"), sc=name(from,"TF_RS_C"), tp=name(to,"TF_RS_P"), tc=name(to,"TF_RS_C"), blocker=name(to,"TF_RS_X"), existing=name(to,"TF_RS_E");
        String sid=name(from,"ID"), spid=name(from,"PID"), snote=name(from,"NOTE"), tid=name(to,"ID"), tpid=name(to,"PID"), tnote=name(to,"NOTE");
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            Runnable clean=() -> {
                for(String n:List.of(blocker,tc,existing,tp,"tableflow_load_receipts","TABLEFLOW_LOAD_RECEIPTS")) drop(t,to,n);
            };
            clean.run(); for(String n:List.of(sc,sp)) drop(s,from,n);
            try {
                exec(s,"CREATE TABLE "+q(from,sp)+" ("+q(from,sid)+" INTEGER PRIMARY KEY)"+innodb);
                exec(s,"CREATE TABLE "+q(from,sc)+" ("+q(from,sid)+" INTEGER PRIMARY KEY,"+q(from,spid)+" INTEGER,"+q(from,snote)+" "+text+"(40),CONSTRAINT "+q(from,"FK_TF_RS_C")+" FOREIGN KEY ("+q(from,spid)+") REFERENCES "+q(from,sp)+"("+q(from,sid)+"))"+innodb);
                exec(s,"CREATE INDEX "+q(from,"IX_TF_RS_C_NOTE")+" ON "+q(from,sc)+" ("+q(from,snote)+")");
                exec(s,"INSERT INTO "+q(from,sp)+" VALUES (1)");
                for(int i=1;i<=250;i++) exec(s,"INSERT INTO "+q(from,sc)+" VALUES ("+i+",1,'n"+i+"')");
                var extras=new Extras(true,false,true);
                var parentMap=List.of(new Mapping(sid,tid));
                var childMap=List.of(new Mapping(sid,tid),new Mapping(spid,tpid),new Mapping(snote,tnote));
                var parentJob=UUID.randomUUID().toString();
                jobs.submit(parentJob,p -> migration.runBulk(src,ss,sp,tgt,ts,tp,parentMap,1000,true,false,parentJob,p,100,Extras.NONE));
                assertEquals("succeeded",await(parentJob).state(),jobs.get(parentJob).message());

                // 앱이 만들 이름을 다른 테이블이 먼저 쓰고 있어, 자식 테이블 생성 뒤의 DDL이 실패한다
                if(myTarget) exec(t,"CREATE TABLE "+q(to,blocker)+" ("+q(to,"A")+" INTEGER PRIMARY KEY,"+q(to,"B")+" INTEGER,CONSTRAINT "+q(to,"fk_"+tc+"_1")+" FOREIGN KEY ("+q(to,"B")+") REFERENCES "+q(to,blocker)+"("+q(to,"A")+")) ENGINE=InnoDB");
                else { exec(t,"CREATE TABLE "+q(to,blocker)+" ("+q(to,"A")+" INTEGER)"); exec(t,"CREATE INDEX "+q(to,"ix_"+tc+"_1")+" ON "+q(to,blocker)+" ("+q(to,"A")+")"); }
                var childJob=UUID.randomUUID().toString();
                java.util.function.Function<MigrationService.Progress,MigrationService.Outcome> child=p -> migration.runBulk(src,ss,sc,tgt,ts,tc,childMap,1000,true,false,childJob,p,100,extras);
                jobs.submit(childJob,child);
                var failed=await(childJob);
                assertEquals("failed",failed.state(),"DDL 실패를 재현해야 함: "+failed.notes());
                assertTrue(explorer.extras(tgt,ts,tc).foreignKeys().isEmpty(),"FK가 빠진 상태");
                assertEquals("0",one(t,"SELECT COUNT(*) FROM "+q(to,tc)));

                drop(t,to,blocker); // 원인 해결 후 같은 요청으로 재개
                jobs.submit(childJob,child);
                var done=await(childJob);
                assertEquals("succeeded",done.state(),done.message());
                assertEquals("250",one(t,"SELECT COUNT(*) FROM "+q(to,tc)));
                var after=explorer.extras(tgt,ts,tc);
                assertEquals(1,after.foreignKeys().size(),"재개 때 FK 추가: "+done.notes());
                assertEquals(1,after.indexes().stream().filter(i -> i.columns().size()==1&&i.columns().get(0).equalsIgnoreCase(tnote)).count(),"재개 때 인덱스 추가(중복 없이): "+after.indexes());
                assertTrue(done.notes().stream().anyMatch(n -> n.contains("빠져 있던")),done.notes().toString());

                // 이 작업이 만들지 않은 기존 테이블에는 인덱스·FK를 추가하지 않는다
                exec(t,"CREATE TABLE "+q(to,existing)+" ("+q(to,tid)+" INTEGER PRIMARY KEY,"+q(to,tpid)+" INTEGER,"+q(to,tnote)+" VARCHAR(40))");
                var existingJob=UUID.randomUUID().toString();
                java.util.function.Function<MigrationService.Progress,MigrationService.Outcome> intoExisting=p -> migration.runBulk(src,ss,sc,tgt,ts,existing,childMap,1000,true,false,existingJob,p,100,extras);
                jobs.submit(existingJob,intoExisting); assertEquals("succeeded",await(existingJob).state(),jobs.get(existingJob).message());
                jobs.submit(existingJob,intoExisting); assertEquals("succeeded",await(existingJob).state(),jobs.get(existingJob).message());
                var untouched=explorer.extras(tgt,ts,existing);
                assertTrue(untouched.foreignKeys().isEmpty() && untouched.indexes().isEmpty(),untouched.toString());
            } finally { clean.run(); for(String n:List.of(sc,sp)) drop(s,from,n); }
        }
    }
}
