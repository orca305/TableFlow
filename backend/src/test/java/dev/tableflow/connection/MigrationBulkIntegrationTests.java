package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.DatabaseLoader.LoadFailure;
import dev.tableflow.load.MigrationJobs;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Mapping;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 일회용 테스트 컨테이너 DB 사이의 대용량(청크)·키 보존 이전. 운영/개인 DB로 포트를 바꿔 실행하지 마세요. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true","tableflow.migration.chunk-size=1000"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class MigrationBulkIntegrationTests {
    static final int ROWS = 5432; // 청크 1,000행 → 6개 청크(마지막 432행)
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired MigrationJobs jobs;

    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=(db==Dbms.mysql||db==Dbms.mariadb)?"`":"\"";return c+id+c;}
    boolean family(Dbms db){return db==Dbms.mysql||db==Dbms.mariadb;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void dropQuiet(Connection c,String name){try(var st=c.createStatement()){st.execute("DROP TABLE "+name);}catch(SQLException ignored){}}
    String text(Dbms db){return db==Dbms.oracle?"VARCHAR2":"VARCHAR";}
    String dec(Dbms db){return db==Dbms.oracle?"NUMBER":"DECIMAL";}
    String engine(Dbms db){return family(db)?" ENGINE=InnoDB":"";}
    boolean enabled(Dbms... dbs){var on=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));for(Dbms d:dbs)if(!on.contains(d.name()))return false;return true;}
    long scalar(Connection c,String sql)throws SQLException{try(var st=c.createStatement();var rs=st.executeQuery(sql)){rs.next();return rs.getLong(1);}}
    void receipts(Connection t,Dbms to){exec(t,"CREATE TABLE "+q(to,"tableflow_load_receipts")+" (request_id "+text(to)+"(36) PRIMARY KEY,payload_hash "+text(to)+"(64) NOT NULL,table_name "+text(to)+"(128) NOT NULL,inserted_rows INTEGER NOT NULL)"+engine(to));}

    /** 청크 진행 기록용. cancelAfter 청크가 끝나면 취소를 요청한다. */
    static class Recorder implements MigrationService.Progress {
        int chunks, doneChunks, replayed; long rows; final int cancelAfter;
        Recorder(int cancelAfter){this.cancelAfter=cancelAfter;}
        public void total(int c,long r){chunks=c;rows=r;}
        public void chunk(int d,long r,int rep){doneChunks=d;replayed=rep;}
        public boolean cancelled(){return cancelAfter>0 && doneChunks>=cancelAfter;}
    }

    void fillSource(Connection s,Dbms from,String table)throws SQLException{
        s.setAutoCommit(false);
        try(var ps=s.prepareStatement("INSERT INTO "+table+" VALUES (?,?,?)")){
            for(int i=1;i<=ROWS;i++){
                ps.setInt(1,i);
                ps.setString(2,i==3500?"x".repeat(30):"n"+i); // 3,500번째 행: 대상 VARCHAR(20)을 넘는 값 → 4번째 청크에서 실패
                ps.setBigDecimal(3,new BigDecimal(i).add(new BigDecimal("0.25")));
                ps.addBatch(); if(i%500==0)ps.executeBatch();
            }
            ps.executeBatch();
        }
        s.commit(); s.setAutoCommit(true);
    }

    @ParameterizedTest(name="bulk {0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,mysql","mysql,mariadb","mariadb,oracle"})
    void chunkedMigrationFailsMidwayThenResumesCancelsAndResumesWithoutDuplicates(Dbms from,Dbms to)throws Exception{
        Assumptions.assumeTrue(enabled(from,to));
        var src=credentials(from); var tgt=credentials(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))){
            String sTable=q(from,"TF_BULK_SRC"), tTable=q(to,"TF_BULK_DST"), receipt=q(to,"tableflow_load_receipts");
            dropQuiet(s,sTable); dropQuiet(t,tTable); dropQuiet(t,receipt);
            exec(s,"CREATE TABLE "+sTable+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"NAME")+" "+text(from)+"(40),"+q(from,"AMT")+" "+dec(from)+"(12,2))"+engine(from));
            fillSource(s,from,sTable);
            exec(t,"CREATE TABLE "+tTable+" ("+q(to,"ID")+" INTEGER PRIMARY KEY,"+q(to,"NAME")+" "+text(to)+"(20),"+q(to,"AMT")+" "+dec(to)+"(12,2))"+engine(to));
            receipts(t,to);
            var map=List.of(new Mapping("ID","ID"),new Mapping("NAME","NAME"),new Mapping("AMT","AMT"));
            String sSchema=schema(from), tSchema=schema(to);

            // 1) 대용량 점검: 전체 행 수·청크 수, 표본만 대조한다는 경고, 아무것도 쓰지 않는다.
            var plan=migration.check(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false);
            assertTrue(plan.bulk()); assertEquals(ROWS,plan.rows()); assertEquals(6,plan.chunks()); assertEquals(1000,plan.chunkSize());
            assertTrue(plan.warnings().stream().anyMatch(w->w.contains("처음 200행")),plan.warnings().toString());
            assertEquals(0,scalar(t,"SELECT COUNT(*) FROM "+tTable));
            // 한도보다 많으면 일부만 이전하지 않고 거부
            assertThrows(IllegalArgumentException.class,()->migration.check(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,ROWS-1,false,false)); // 한도 <= 10,000은 동기 경로
            // 동기 경로는 10,000행 초과를 거부한다
            assertThrows(IllegalArgumentException.class,()->migration.execute(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,20000,false,UUID.randomUUID().toString()));

            // 2) 4번째 청크에서 실패: 앞선 3개 청크(3,000행)는 커밋, 메시지에 재개 방법 안내
            String id=UUID.randomUUID().toString(); var first=new Recorder(0);
            var failure=assertThrows(LoadFailure.class,()->migration.runBulk(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,id,first));
            assertTrue(failure.getMessage().contains("분할 4/6회차") && failure.getMessage().contains("3000행"),failure.getMessage());
            assertEquals(3,first.doneChunks); assertEquals(3000,scalar(t,"SELECT COUNT(*) FROM "+tTable));

            // 3) 원인(원본의 긴 값) 수정 후 같은 요청 ID로 재실행: 앞선 3개 청크는 건너뛰고 이어서 완료, 중복 없음
            exec(s,"UPDATE "+sTable+" SET "+q(from,"NAME")+"='ok' WHERE "+q(from,"ID")+"=3500");
            var second=new Recorder(0);
            var done=migration.runBulk(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,id,second);
            assertEquals(ROWS,done.insertedRows()); assertEquals(6,done.chunks()); assertEquals(3,done.replayedChunks()); assertFalse(done.replayed());
            assertEquals(ROWS,scalar(t,"SELECT COUNT(*) FROM "+tTable)); assertEquals(ROWS,scalar(t,"SELECT COUNT(DISTINCT "+q(to,"ID")+") FROM "+tTable));
            assertEquals((long)ROWS*(ROWS+1)/2,scalar(t,"SELECT SUM("+q(to,"ID")+") FROM "+tTable));
            try(var st=t.createStatement();var rs=st.executeQuery("SELECT "+q(to,"AMT")+","+q(to,"NAME")+" FROM "+tTable+" WHERE "+q(to,"ID")+"=3500")){
                assertTrue(rs.next()); assertEquals(0,new BigDecimal("3500.25").compareTo(rs.getBigDecimal(1))); assertEquals("ok",rs.getString(2));
            }
            // 4) 한 번 더: 모든 청크가 영수증으로 건너뛰어진다
            var third=migration.runBulk(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,id,new Recorder(0));
            assertTrue(third.replayed()); assertEquals(6,third.replayedChunks()); assertEquals(ROWS,scalar(t,"SELECT COUNT(*) FROM "+tTable));

            // 5) 취소: 2개 청크 후 멈추고, 같은 요청으로 다시 실행하면 이어서 완료
            exec(t,"DELETE FROM "+tTable); String id2=UUID.randomUUID().toString();
            var cancelled=assertThrows(LoadFailure.class,()->migration.runBulk(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,id2,new Recorder(2)));
            assertEquals("MIGRATION_CANCELLED",cancelled.code); assertEquals(2000,scalar(t,"SELECT COUNT(*) FROM "+tTable));
            var resumed=migration.runBulk(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,id2,new Recorder(0));
            assertEquals(2,resumed.replayedChunks()); assertEquals(ROWS,scalar(t,"SELECT COUNT(*) FROM "+tTable));

            // 5-1) 작업별 분할 단위: 이번 작업만 2,500행씩(3회). 허용 범위(100~10,000) 밖의 값은 보정한다.
            var custom=migration.check(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,2500);
            assertEquals(3,custom.chunks()); assertEquals(2500,custom.chunkSize());
            var clamped=migration.check(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,10);
            assertEquals(100,clamped.chunkSize()); assertEquals(55,clamped.chunks());
            exec(t,"DELETE FROM "+tTable);
            var customRun=migration.runBulk(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,UUID.randomUUID().toString(),new Recorder(0),2500);
            assertEquals(3,customRun.chunks()); assertEquals(ROWS,customRun.insertedRows()); assertEquals(ROWS,scalar(t,"SELECT COUNT(*) FROM "+tTable));

            // 6) 백그라운드 작업: 진행률과 완료 상태, 같은 ID 중복 제출은 새로 시작하지 않는다
            exec(t,"DELETE FROM "+tTable); String id3=UUID.randomUUID().toString();
            var started=jobs.submit(id3,progress->migration.runBulk(src,sSchema,"TF_BULK_SRC",tgt,tSchema,"TF_BULK_DST",map,100000,false,false,id3,progress));
            assertEquals(id3,started.id());
            MigrationJobs.Status status=started; long deadline=System.currentTimeMillis()+90_000;
            while(!status.state().equals("succeeded")&&!status.state().equals("failed")&&System.currentTimeMillis()<deadline){Thread.sleep(200);status=jobs.get(id3);}
            assertEquals("succeeded",status.state(),status.toString()); assertEquals(6,status.totalChunks()); assertEquals(6,status.doneChunks()); assertEquals(ROWS,status.doneRows());
            assertEquals(ROWS,scalar(t,"SELECT COUNT(*) FROM "+tTable));
            assertThrows(JdbcExplorer.Failure.class,()->jobs.get("00000000-0000-0000-0000-000000000000"));
            System.out.println("BULK VERIFIED: "+from+" -> "+to);
            dropQuiet(s,sTable); dropQuiet(t,tTable); dropQuiet(t,receipt);
        }
    }

    String identityColumn(Dbms db,String mode){
        return switch(db){case postgresql->"INTEGER GENERATED "+mode+" AS IDENTITY PRIMARY KEY";case oracle->"NUMBER GENERATED "+mode+" AS IDENTITY PRIMARY KEY";default->"INT AUTO_INCREMENT PRIMARY KEY";};
    }
    void keyScenario(Dbms from,Dbms to,String mode)throws Exception{
        var src=credentials(from); var tgt=credentials(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))){
            String sTable=q(from,"TF_KEY_SRC"), tTable=q(to,"TF_KEY_DST"), receipt=q(to,"tableflow_load_receipts");
            dropQuiet(s,sTable); dropQuiet(t,tTable); dropQuiet(t,receipt);
            exec(s,"CREATE TABLE "+sTable+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"NAME")+" "+text(from)+"(50))"+engine(from));
            for(int[] r:new int[][]{{10,0},{20,1},{30,2}})exec(s,"INSERT INTO "+sTable+" VALUES ("+r[0]+",'"+new String[]{"a","b","c"}[r[1]]+"')"); // 연속되지 않은 원본 키
            exec(t,"CREATE TABLE "+tTable+" ("+q(to,"ID")+" "+identityColumn(to,mode)+","+q(to,"NAME")+" "+text(to)+"(50))"+engine(to)); receipts(t,to);
            var map=List.of(new Mapping("ID","ID"),new Mapping("NAME","NAME"));
            // 옵션 없이는 거부하고, 옵션 안내를 보여준다
            var rejected=assertThrows(IllegalArgumentException.class,()->migration.check(src,schema(from),"TF_KEY_SRC",tgt,schema(to),"TF_KEY_DST",map,100,false,false));
            assertTrue(rejected.getMessage().contains("원본 키 값 보존"),rejected.getMessage());
            if(to==Dbms.oracle&&mode.equals("ALWAYS")){ // Oracle GENERATED ALWAYS는 값을 넣을 수 없다 → 명확히 거부
                var always=assertThrows(IllegalArgumentException.class,()->migration.check(src,schema(from),"TF_KEY_SRC",tgt,schema(to),"TF_KEY_DST",map,100,false,true));
                assertTrue(always.getMessage().contains("ALWAYS"),always.getMessage());
                assertEquals(0,scalar(t,"SELECT COUNT(*) FROM "+tTable));
            } else {
                var plan=migration.check(src,schema(from),"TF_KEY_SRC",tgt,schema(to),"TF_KEY_DST",map,100,false,true);
                assertTrue(plan.notes().stream().anyMatch(n->n.contains("자동 증가 컬럼 ID")),plan.notes().toString());
                assertEquals(0,scalar(t,"SELECT COUNT(*) FROM "+tTable)); // Dry-run은 쓰지 않는다
                var out=migration.execute(src,schema(from),"TF_KEY_SRC",tgt,schema(to),"TF_KEY_DST",map,100,false,true,UUID.randomUUID().toString());
                assertEquals(3,out.insertedRows()); assertFalse(out.notes().isEmpty(),"재동기화 안내");
                assertTrue(out.notes().stream().noneMatch(n->n.contains("실패")),out.notes().toString());
                assertEquals(60,scalar(t,"SELECT SUM("+q(to,"ID")+") FROM "+tTable)); // 10+20+30: 원본 키 값 그대로
                assertEquals(1,scalar(t,"SELECT COUNT(*) FROM "+tTable+" WHERE "+q(to,"ID")+"=20 AND "+q(to,"NAME")+"='b'"));
                // 시퀀스 재동기화: 다음 자동 번호는 원본 최댓값 다음(31)이어야 키 중복 오류가 나지 않는다.
                exec(t,"INSERT INTO "+tTable+" ("+q(to,"NAME")+") VALUES ('new')");
                assertEquals(31,scalar(t,"SELECT "+q(to,"ID")+" FROM "+tTable+" WHERE "+q(to,"NAME")+"='new'"));
            }
            System.out.println("KEYS VERIFIED("+mode+"): "+from+" -> "+to);
            dropQuiet(s,sTable); dropQuiet(t,tTable); dropQuiet(t,receipt);
        }
    }
    @ParameterizedTest(name="keys {0} -> {1}")
    @CsvSource({"mysql,postgresql","postgresql,mysql","postgresql,mariadb","postgresql,oracle"})
    void preservesSourceKeysIntoAutoIncrementTargetAndResyncsSequence(Dbms from,Dbms to)throws Exception{
        Assumptions.assumeTrue(enabled(from,to)); keyScenario(from,to,"BY DEFAULT");
    }
    @Test void postgresAlwaysIdentityAcceptsSourceKeysWithOverridingSystemValue()throws Exception{
        Assumptions.assumeTrue(enabled(Dbms.mysql,Dbms.postgresql)); keyScenario(Dbms.mysql,Dbms.postgresql,"ALWAYS");
    }
    @Test void oracleAlwaysIdentityIsRejectedClearly()throws Exception{
        Assumptions.assumeTrue(enabled(Dbms.postgresql,Dbms.oracle)); keyScenario(Dbms.postgresql,Dbms.oracle,"ALWAYS");
    }
}
