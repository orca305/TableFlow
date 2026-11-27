package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Mapping;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.util.*;

/** 기본 키 없는 큰 테이블의 분할 이관 시간을 전용 컨테이너에서 잰다(명시적으로 켠 경우만). */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_PERF_PAGING",matches="true")
class KeylessPagingPerformanceTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration;
    static final int ROWS = 1_000_000, CHUNK = 10_000;

    @ParameterizedTest(name="unique index = {0}")
    @CsvSource({"true","false"})
    void measuresBulkTimeWithoutPrimaryKey(boolean uniqueIndex)throws Exception {
        var src=new Credentials(Dbms.oracle,"localhost",11521,"FREEPDB1","tableflow","TableFlowTest2026",false);
        var tgt=new Credentials(Dbms.postgresql,"localhost",15432,"tableflow","tableflow","TableFlowTest2026",false);
        var setup=explorer.properties(src); setup.setProperty("oracle.jdbc.ReadTimeout","300000"); // 대량 준비 SQL은 기본 읽기 제한(10초)보다 오래 걸린다
        try(var s=DriverManager.getConnection(explorer.url(src),setup);var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt));
            var so=s.createStatement(); var to=t.createStatement()) {
            try{so.execute("DROP TABLE TF_PG_SRC PURGE");}catch(SQLException ignored){}
            to.execute("DROP TABLE IF EXISTS tf_pg_dst"); to.execute("DROP TABLE IF EXISTS tableflow_load_receipts");
            try {
                so.execute("CREATE TABLE TF_PG_SRC (CODE NUMBER(10) NOT NULL, NAME VARCHAR2(40), AMT NUMBER(12,2), NOTE VARCHAR2(60), D DATE)");
                // 저장 순서와 키 순서가 다르도록 섞어 넣는다
                so.execute("INSERT /*+ APPEND */ INTO TF_PG_SRC SELECT MOD(LEVEL*7919, "+ROWS+")+1, 'name'||MOD(LEVEL,1000), LEVEL/100, RPAD('x',50,'x'), DATE '2020-01-01'+MOD(LEVEL,1000) FROM DUAL CONNECT BY LEVEL <= "+ROWS);
                if(!s.getAutoCommit()) s.commit();
                if(uniqueIndex) so.execute("CREATE UNIQUE INDEX UX_TF_PG_SRC ON TF_PG_SRC (CODE)");
                var map=List.of(new Mapping("CODE","code"),new Mapping("NAME","name"),new Mapping("AMT","amt"),new Mapping("NOTE","note"),new Mapping("D","d"));
                List<Long> marks=new ArrayList<>(); long t0=System.nanoTime();
                var progress=new MigrationService.Progress(){
                    public boolean cancelled(){return false;} public void total(int c,long r){}
                    public void chunk(int d,long r,int rep){marks.add((System.nanoTime()-t0)/1_000_000);}
                };
                var out=migration.runBulk(src,"TABLEFLOW","TF_PG_SRC",tgt,"public","tf_pg_dst",map,ROWS,true,false,UUID.randomUUID().toString(),progress,CHUNK);
                long total=(System.nanoTime()-t0)/1_000_000;
                try(var rs=to.executeQuery("SELECT COUNT(*),COUNT(DISTINCT code) FROM tf_pg_dst")){rs.next();Assertions.assertEquals(ROWS,rs.getInt(1));Assertions.assertEquals(ROWS,rs.getInt(2));}
                Assertions.assertEquals(ROWS,out.insertedRows());
                System.out.println("PERF paging unique="+uniqueIndex+" rows="+ROWS+" totalMs="+total+" firstChunkMs="+(marks.get(1)-marks.get(0))+" lastChunkMs="+(marks.get(marks.size()-1)-marks.get(marks.size()-2)));
            } finally { try{so.execute("DROP TABLE TF_PG_SRC PURGE");}catch(SQLException ignored){} to.execute("DROP TABLE IF EXISTS tf_pg_dst"); to.execute("DROP TABLE IF EXISTS tableflow_load_receipts"); }
        }
    }
}
