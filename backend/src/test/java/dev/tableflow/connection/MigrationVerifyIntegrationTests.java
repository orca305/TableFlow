package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.MigrationService;
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

/** 이관 후 건수·집계 비교 검증을 실서버에서 확인한다(일치, 값 변경, 행 삭제). 일회용 테스트 컨테이너 전용입니다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class MigrationVerifyIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired MigrationVerifier verifier;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;};
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

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void verifiesMatchThenDetectsChangedValueAndMissingRow(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            dropQuiet(s,q(from,"TF_V_SRC")); dropQuiet(t,q(to,"TF_V_DST")); dropQuiet(t,q(to,"tableflow_load_receipts"));
            exec(s,"CREATE TABLE "+q(from,"TF_V_SRC")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"NAME")+" "+text(from)+"(40),"+q(from,"AMT")+" "+dec(from)+"(12,2),"+q(from,"TS")+" TIMESTAMP)"+engine(from));
            String[] rows={"(1,'홍길동',100.50,"+ts(from,"2026-01-15 09:30:00")+")","(2,'이도윤',200.25,"+ts(from,"2026-02-20 10:00:00")+")","(3,NULL,NULL,NULL)","(4,'박서연',0.10,"+ts(from,"2026-03-01 00:00:00")+")"};
            for(String r:rows)exec(s,"INSERT INTO "+q(from,"TF_V_SRC")+" VALUES "+r);
            var map=List.of(new Mapping("ID","ID"),new Mapping("NAME","NAME"),new Mapping("AMT","AMT"),new Mapping("TS","TS"));
            assertEquals(4,migration.execute(src,ss,"TF_V_SRC",tgt,ts,"TF_V_DST",map,100,true,false,UUID.randomUUID().toString()).insertedRows());

            var ok=verifier.verify(src,ss,"TF_V_SRC",tgt,ts,"TF_V_DST",map,null);
            assertTrue(ok.rowsMatch()&&ok.allMatch(),ok.checks().toString()+ok.notes());
            assertEquals(4,ok.sourceRows()); assertEquals(4,ok.targetRows());
            var amtSum=ok.checks().stream().filter(c->c.source().equals("AMT")&&c.measure().equals("합계")).findFirst().orElseThrow();
            assertEquals("300.85",amtSum.sourceValue()); assertEquals("300.85",amtSum.targetValue());
            assertTrue(ok.checks().stream().anyMatch(c->c.source().equals("NAME")&&c.measure().equals("문자열 길이 합계")&&c.sourceValue().equals("9")));
            assertTrue(ok.checks().stream().anyMatch(c->c.source().equals("TS")&&c.measure().equals("최대값")&&c.match()));

            // 대상 값이 바뀌면 해당 컬럼만 불일치로 잡는다
            exec(t,"UPDATE "+q(to,"TF_V_DST")+" SET "+q(to,"AMT")+"="+q(to,"AMT")+"+1 WHERE "+q(to,"ID")+"=1");
            var changed=verifier.verify(src,ss,"TF_V_SRC",tgt,ts,"TF_V_DST",map,null);
            assertFalse(changed.allMatch()); assertTrue(changed.rowsMatch());
            assertTrue(changed.checks().stream().filter(c->!c.match()).allMatch(c->c.source().equals("AMT")),changed.checks().toString());
            assertEquals("301.85",changed.checks().stream().filter(c->c.source().equals("AMT")&&c.measure().equals("합계")).findFirst().orElseThrow().targetValue());

            // 행이 빠지면 행 수 불일치
            exec(t,"DELETE FROM "+q(to,"TF_V_DST")+" WHERE "+q(to,"ID")+"=4");
            var missing=verifier.verify(src,ss,"TF_V_SRC",tgt,ts,"TF_V_DST",map,null);
            assertFalse(missing.rowsMatch()); assertEquals(3,missing.targetRows()); assertFalse(missing.allMatch());
            dropQuiet(s,q(from,"TF_V_SRC")); dropQuiet(t,q(to,"TF_V_DST")); dropQuiet(t,q(to,"tableflow_load_receipts"));
        }
    }
    String ts(Dbms db,String v){return db==Dbms.oracle?"TIMESTAMP '"+v+"'":"'"+v+"'";}
}
