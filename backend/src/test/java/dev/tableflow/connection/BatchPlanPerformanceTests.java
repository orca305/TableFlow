package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.BatchMigration;
import dev.tableflow.load.BatchMigration.Options;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.util.*;

/** 테이블이 많은 일괄 사전 점검의 소요 시간을 전용 컨테이너에서 잰다(명시적으로 켠 경우만). */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_PERF_PLAN",matches="true")
class BatchPlanPerformanceTests {
    @Autowired JdbcExplorer explorer; @Autowired BatchMigration batch;
    static final int TABLES = 60;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=db==Dbms.mariadb?"`":"\"";return c+id+c;}
    static String name(int i){return String.format("TF_PERF_%03d",i);}

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,mariadb"})
    void measuresPlanTime(Dbms from,Dbms to)throws Exception {
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src))) {
            List<String> tables=new ArrayList<>();
            try(var st=s.createStatement()) {
                for(int i=TABLES-1;i>=0;i--) try{st.execute("DROP TABLE "+q(from,name(i)));}catch(SQLException ignored){}
                for(int i=0;i<TABLES;i++) {
                    // 10개마다 부모 하나를 두고 나머지는 그 부모를 참조한다
                    String fk=i%10==0?"":",CONSTRAINT "+q(from,"FK_"+name(i))+" FOREIGN KEY ("+q(from,"PID")+") REFERENCES "+q(from,name(i-i%10))+"("+q(from,"ID")+")";
                    String text=from==Dbms.oracle?"VARCHAR2":"VARCHAR";
                    st.execute("CREATE TABLE "+q(from,name(i))+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"PID")+" INTEGER,"+q(from,"NAME")+" "+text+"(40),"+q(from,"AMT")+" NUMERIC(12,2)"+fk+")");
                    tables.add(name(i));
                }
            }
            try {
                var options=new Options(false,true,true,true,"skip",1000);
                List<Long> times=new ArrayList<>(); BatchMigration.Plan plan=null;
                for(int run=0;run<3;run++) { long t0=System.nanoTime(); plan=batch.plan(src,ss,tgt,ts,tables,options); times.add((System.nanoTime()-t0)/1_000_000); }
                Assertions.assertEquals(TABLES,plan.items().size()); Assertions.assertFalse(plan.blocked(),plan.toString());
                System.out.println("PERF plan "+from+"->"+to+" tables="+TABLES+" ms="+times);
            } finally {
                try(var st=s.createStatement()) { for(int i=TABLES-1;i>=0;i--) st.execute("DROP TABLE "+q(from,name(i))); }
            }
        }
    }
}
