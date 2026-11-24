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
import static org.junit.jupiter.api.Assertions.*;

/** 컬럼 매핑 분석(타입·길이·정밀도 호환성)을 실제 서버에서 검증한다. 일회용 테스트 컨테이너 전용입니다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class MigrationCompatIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration;
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
    String integer(Dbms db){return db==Dbms.oracle?"NUMBER(10)":"INTEGER";}
    String engine(Dbms db){return family(db)?" ENGINE=InnoDB":"";}

    @ParameterizedTest(name="new-table types {0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void newTableAcceptsChosenTypesAndLengths(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            String sTable=q(from,"TF_TYP_SRC");
            dropQuiet(s,sTable); dropQuiet(t,q(to,"TF_TYP_DST")); dropQuiet(t,q(to,"tableflow_load_receipts"));
            exec(s,"CREATE TABLE "+sTable+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"NAME")+" "+text(from)+"(100),"+q(from,"NOTE")+" "+text(from)+"(50))"+engine(from));
            exec(s,"INSERT INTO "+sTable+" VALUES (1,'"+"가".repeat(30)+"','10')"); exec(s,"INSERT INTO "+sTable+" VALUES (2,'짧음','20')");
            var integer=dev.tableflow.model.Table.DataType.integer;
            // 길이를 너무 짧게 지정하면 실제 값 기준으로 오류
            var tooShort=migration.analyze(src,schema(from),"TF_TYP_SRC",tgt,schema(to),"TF_TYP_DST",List.of(new Mapping("ID","ID"),new Mapping("NAME","NAME",null,20,null),new Mapping("NOTE","NOTE")),true,false);
            var name=tooShort.columns().stream().filter(c->c.source().equals("NAME")).findFirst().orElseThrow();
            assertEquals("error",name.level(),name.toString()); assertTrue(name.detail().contains("지정한 길이(20자)")&&name.detail().contains("1건"),name.detail());
            // 충분한 길이는 통과하고, 문자열을 정수로 지정하면 변환 위험을 알린다
            var map=List.of(new Mapping("ID","ID"),new Mapping("NAME","NAME",null,40,null),new Mapping("NOTE","NOTE",integer,null,null));
            var ok=migration.analyze(src,schema(from),"TF_TYP_SRC",tgt,schema(to),"TF_TYP_DST",map,true,false);
            assertEquals("ok",ok.columns().stream().filter(c->c.source().equals("NAME")).findFirst().orElseThrow().level());
            assertEquals("warn",ok.columns().stream().filter(c->c.source().equals("NOTE")).findFirst().orElseThrow().level());
            // 실제 이관: 지정한 길이·타입으로 대상이 만들어진다
            assertEquals(2,migration.execute(src,schema(from),"TF_TYP_SRC",tgt,schema(to),"TF_TYP_DST",map,100,true,false,UUID.randomUUID().toString()).insertedRows());
            var cols=new HashMap<String,JdbcExplorer.ColumnInfo>(); explorer.columns(tgt,schema(to),"TF_TYP_DST").items().forEach(c->cols.put(c.name().toUpperCase(),c));
            assertEquals(40,cols.get("NAME").size());
            assertTrue(List.of(Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL).contains(cols.get("NOTE").jdbcType()),cols.get("NOTE").toString());
            dropQuiet(s,sTable); dropQuiet(t,q(to,"TF_TYP_DST")); dropQuiet(t,q(to,"tableflow_load_receipts"));
        }
    }

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void judgesTypeLengthAndPrecisionBeforeMigrating(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            String sTable=q(from,"TF_CMP_SRC"), tTable=q(to,"TF_CMP_DST");
            dropQuiet(s,sTable); dropQuiet(t,tTable);
            exec(s,"CREATE TABLE "+sTable+" ("+q(from,"ID")+" "+text(from)+"(20) PRIMARY KEY,"+q(from,"NAME")+" "+text(from)+"(100),"+q(from,"CODE")+" "+text(from)+"(100),"
                +q(from,"AMT")+" "+dec(from)+"(20,2),"+q(from,"NOTE")+" "+text(from)+"(50))"+engine(from));
            exec(s,"INSERT INTO "+sTable+" VALUES ('1','짧은 이름','A1',10.50,'메모')");
            exec(s,"INSERT INTO "+sTable+" VALUES ('2','"+"가".repeat(30)+"','B2',20.25,'메모')");   // NAME: 대상 한도(20)를 넘는 값 1건
            exec(t,"CREATE TABLE "+tTable+" ("+q(to,"ID")+" "+text(to)+"(20) PRIMARY KEY,"+q(to,"NAME")+" "+text(to)+"(20),"+q(to,"CODE")+" "+text(to)+"(20),"
                +q(to,"AMT")+" "+dec(to)+"(20,0),"+q(to,"NOTE")+" "+integer(to)+")"+engine(to));
            var map=List.of(new Mapping("ID","ID"),new Mapping("NAME","NAME"),new Mapping("CODE","CODE"),new Mapping("AMT","AMT"),new Mapping("NOTE","NOTE"));

            var analysis=migration.analyze(src,schema(from),"TF_CMP_SRC",tgt,schema(to),"TF_CMP_DST",map,false,false);
            var by=new HashMap<String,MigrationService.ColumnPlan>(); analysis.columns().forEach(c->by.put(c.source(),c));
            assertEquals(2,analysis.sourceRows()); assertTrue(analysis.maxRows()>=10000);
            assertEquals("ok",by.get("ID").level(),by.get("ID").toString());
            var name=by.get("NAME");   // 길이 위험 + 실제로 한도를 넘는 값 있음
            assertEquals("error",name.level(),name.toString());
            assertTrue(name.detail().contains("1건") && name.detail().contains("20자"),name.detail());
            var code=by.get("CODE");   // 길이는 원본이 크지만 실제 값은 모두 한도 이하
            assertEquals("ok",code.level(),code.toString());
            assertTrue(code.detail().contains("모두"),code.detail());
            assertEquals("warn",by.get("AMT").level(),by.get("AMT").toString());            // 소수 자리 20,2 → 20,0
            assertEquals("error",by.get("NOTE").level(),by.get("NOTE").toString());          // 문자열 → 정수
            assertTrue(analysis.warnings().stream().anyMatch(w->w.contains("NAME")) && analysis.warnings().stream().anyMatch(w->w.contains("NOTE")),analysis.warnings().toString());
            assertFalse(analysis.createsTable());

            // 아무것도 쓰지 않는다
            try(var st=t.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+tTable)){rs.next();assertEquals(0,rs.getLong(1));}
            // 새 테이블 생성이면 원본 구조를 따르므로 모두 ok
            var created=migration.analyze(src,schema(from),"TF_CMP_SRC",tgt,schema(to),"TF_CMP_NEW",map,true,false);
            assertTrue(created.createsTable());
            assertTrue(created.columns().stream().allMatch(c->c.level().equals("ok")),created.columns().toString());
            dropQuiet(s,sTable); dropQuiet(t,tTable);
        }
    }
}
