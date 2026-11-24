package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Extras;
import dev.tableflow.load.MigrationService.Mapping;
import dev.tableflow.load.MigrationVerifier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 행 조건(WHERE)과 일부 컬럼 선택을 실서버에서 확인한다: 점검, 소량 이관, 분할(키 기반) 이관, 이관 후 검증 모두에 같은 조건이 적용되어야 한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class MigrationRowFilterIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired MigrationVerifier verifier;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=(db==Dbms.mysql||db==Dbms.mariadb)?"`":"\"";return c+id+c;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void dropQuiet(Connection c,String name){try(var st=c.createStatement()){st.execute("DROP TABLE "+name);}catch(SQLException ignored){}}
    long scalar(Connection c,String sql)throws SQLException{try(var st=c.createStatement();var rs=st.executeQuery(sql)){rs.next();return rs.getLong(1);}}
    String text(Dbms db){return db==Dbms.oracle?"VARCHAR2":"VARCHAR";}

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void appliesRowFilterEverywhere(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            for(String n:List.of("TF_F_DST","TF_F_DST2","TF_F_DST3","tableflow_load_receipts")) dropQuiet(t,q(to,n));
            dropQuiet(s,q(from,"TF_F_SRC"));
            exec(s,"CREATE TABLE "+q(from,"TF_F_SRC")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"KIND")+" "+text(from)+"(10),"+q(from,"NAME")+" "+text(from)+"(40),"+q(from,"SECRET")+" "+text(from)+"(40))"+((from==Dbms.mysql||from==Dbms.mariadb)?" ENGINE=InnoDB":""));
            for(int i=1;i<=350;i++) exec(s,"INSERT INTO "+q(from,"TF_F_SRC")+" VALUES ("+i+",'"+(i%7==0?"B":"A")+"','이름"+i+"','비밀"+i+"')");
            long expected=scalar(s,"SELECT COUNT(*) FROM "+q(from,"TF_F_SRC")+" WHERE "+q(from,"KIND")+"='B'"); // 50
            assertEquals(50,expected);
            String where=(from==Dbms.postgresql?"\"KIND\"":"KIND")+" = 'B' AND "+(from==Dbms.postgresql?"\"ID\"":"ID")+" > 7"; long filtered=scalar(s,"SELECT COUNT(*) FROM "+q(from,"TF_F_SRC")+" WHERE "+q(from,"KIND")+"='B' AND "+q(from,"ID")+">7"); // 49
            assertEquals(49,filtered);
            // 일부 컬럼만(SECRET 제외)
            var map=List.of(new Mapping("ID","ID"),new Mapping("KIND","KIND"),new Mapping("NAME","NAME"));
            var ex=new Extras(false,false,false,where);

            // 사전 점검과 분석: 걸러진 행 수가 나온다
            var plan=migration.check(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST",map,1000,true,false,500,ex);
            assertEquals(49,plan.rows()); assertEquals(49,plan.sourceRows());
            assertEquals(49,migration.analyze(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST",map,true,false,ex).sourceRows());

            // 소량 이관
            assertEquals(49,migration.execute(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST",map,1000,true,false,UUID.randomUUID().toString(),ex).insertedRows());
            assertEquals(49,scalar(t,"SELECT COUNT(*) FROM "+q(to,"TF_F_DST")));
            assertEquals(0,scalar(t,"SELECT COUNT(*) FROM "+q(to,"TF_F_DST")+" WHERE "+q(to,"KIND")+"<>'B' OR "+q(to,"ID")+"<=7"));
            var cols=new HashSet<String>(); for(var c:explorer.columns(tgt,ts,"TF_F_DST").items()) cols.add(c.name().toUpperCase());
            assertEquals(Set.of("ID","KIND","NAME"),cols); // SECRET은 만들어지지 않는다

            // 분할(키 기반) 이관: 분할 크기 100 → 1회(49행)
            assertEquals(49,migration.runBulk(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST2",map,1000,true,false,UUID.randomUUID().toString(),new NoProgress(),100,ex).insertedRows());
            assertEquals(49,scalar(t,"SELECT COUNT(*) FROM "+q(to,"TF_F_DST2")));
            // 더 넓은 조건으로 여러 분할: KIND='A' → 300행 = 3회
            var wide=new Extras(false,false,false,(from==Dbms.postgresql?"\"KIND\"":"KIND")+" = 'A'");
            var outcome=migration.runBulk(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST3",map,1000,true,false,UUID.randomUUID().toString(),new NoProgress(),100,wide);
            assertEquals(300,outcome.insertedRows()); assertEquals(3,outcome.chunks());
            assertEquals(0,scalar(t,"SELECT COUNT(*) FROM "+q(to,"TF_F_DST3")+" WHERE "+q(to,"KIND")+"<>'A'"));

            // 이관 후 검증은 원본에만 같은 조건을 적용한다
            var ok=verifier.verify(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST",map,where);
            assertTrue(ok.allMatch(),ok.checks().toString()+ok.notes()); assertEquals(49,ok.sourceRows());
            assertFalse(verifier.verify(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST",map,null).rowsMatch()); // 조건 없이 비교하면 행 수가 다르다

            // 위험한 조건은 어디서든 거부된다
            assertThrows(IllegalArgumentException.class,()->migration.check(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST",map,1000,true,false,500,new Extras(false,false,false,"1=1; DROP TABLE X")));
            assertThrows(IllegalArgumentException.class,()->verifier.verify(src,ss,"TF_F_SRC",tgt,ts,"TF_F_DST",map,"1=1 -- x"));
            for(String n:List.of("TF_F_DST","TF_F_DST2","TF_F_DST3","tableflow_load_receipts")) dropQuiet(t,q(to,n));
            dropQuiet(s,q(from,"TF_F_SRC"));
        }
    }
    static class NoProgress implements MigrationService.Progress {
        public boolean cancelled(){return false;}
        public void total(int chunks,long rows){}
        public void chunk(int done,long rows,int replayed){}
    }
}
