package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.BatchMigration;
import dev.tableflow.load.BatchMigration.Item;
import dev.tableflow.load.BatchMigration.Options;
import dev.tableflow.load.BatchMigration.TableOption;
import dev.tableflow.load.MigrationService.Mapping;
import dev.tableflow.load.ValueTransform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 일괄 이관의 테이블별 설정(컬럼 매핑·변환 규칙·행 조건), 이관 후 검증, 재개 때 빠진 인덱스·FK 보완, 취소 후 재개를 실서버에서 확인한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class BatchTableOptionsIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired BatchMigration batch; @Autowired ObjectMapper json;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=(db==Dbms.mysql||db==Dbms.mariadb)?"`":"\"";return c+id+c;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void dropQuiet(Connection c,String name){try(var st=c.createStatement()){st.execute("DROP TABLE "+name);}catch(SQLException ignored){}}
    String one(Connection c,String sql)throws SQLException{try(var st=c.createStatement();var rs=st.executeQuery(sql)){rs.next();return rs.getString(1);}}
    String actual(Credentials c,String schema,String wanted){for(String t:explorer.tables(c,schema).items())if(t.equalsIgnoreCase(wanted))return t;return null;}
    void clean(Connection c,Credentials cred,Dbms db,String s){
        for(String n:List.of("TF_O_C","TF_O_P","tableflow_load_receipts")){String a=actual(cred,s,n);if(a!=null)dropQuiet(c,q(db,a));}
    }
    static class Recording implements BatchMigration.BatchProgress {
        volatile List<Item> items=List.of(); String cancelAfter;
        public boolean cancelled(){return cancelAfter!=null&&items.stream().anyMatch(i->i.table().equals(cancelAfter)&&i.doneRows()>0);}
        public void total(int chunks,long rows){} public void chunk(int d,long rows,int r){} public void items(List<Item> i){items=List.copyOf(i);}
    }
    static Item item(List<Item> items,String table){return items.stream().filter(i->i.table().equals(table)).findFirst().orElseThrow();}

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void appliesPerTableOptionsVerifiesAndRepairsStructureOnResume(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        String innodb=(from==Dbms.mysql||from==Dbms.mariadb)?" ENGINE=InnoDB":"", text=from==Dbms.oracle?"VARCHAR2":"VARCHAR";
        String p=BatchMigration.convert(to,"TF_O_P","TBL"), c=BatchMigration.convert(to,"TF_O_C","TBL");
        String id=BatchMigration.convert(to,"ID","COL"), pid=BatchMigration.convert(to,"PID","COL"), label=BatchMigration.convert(to,"LABEL","COL"), note=BatchMigration.convert(to,"NOTE","COL");
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            clean(t,tgt,to,ts);
            for(String n:List.of("TF_O_C","TF_O_P")) dropQuiet(s,q(from,n));
            exec(s,"CREATE TABLE "+q(from,"TF_O_P")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"NAME")+" "+text+"(40),"+q(from,"SECRET")+" "+text+"(40))"+innodb);
            exec(s,"CREATE TABLE "+q(from,"TF_O_C")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"PID")+" INTEGER,"+q(from,"NOTE")+" "+text+"(40),CONSTRAINT "+q(from,"FK_TF_O_C")+" FOREIGN KEY ("+q(from,"PID")+") REFERENCES "+q(from,"TF_O_P")+"("+q(from,"ID")+"))"+innodb);
            exec(s,"CREATE INDEX "+q(from,"IX_TF_O_C_NOTE")+" ON "+q(from,"TF_O_C")+" ("+q(from,"NOTE")+")");
            for(int i=1;i<=3;i++) exec(s,"INSERT INTO "+q(from,"TF_O_P")+" VALUES ("+i+",'name"+i+"','비밀"+i+"')");
            for(int i=1;i<=250;i++) exec(s,"INSERT INTO "+q(from,"TF_O_C")+" VALUES ("+i+","+(i%2+1)+",'n"+i+"')");

            // 부모: SECRET 제외 + NAME을 대문자로 바꿔 LABEL로, ID<=2만. 자식: 조건 없음(PID는 1,2만 참조하므로 FK 성립)
            var parent=new TableOption("TF_O_P",List.of(new Mapping("ID",id),
                new Mapping("NAME",label,null,null,null,List.of(json.readValue("{\"op\":\"upper\"}",ValueTransform.Step.class)))),q(from,"ID")+" <= 2");
            var options=new Options(false,true,false,true,"skip",100,List.of(parent));
            var tables=List.of("TF_O_C","TF_O_P");

            // 잘못된 설정은 아무것도 만들지 않고 거부한다
            assertThrows(IllegalArgumentException.class,()->batch.plan(src,ss,tgt,ts,tables,new Options(false,false,false,false,"skip",100,List.of(new TableOption("TF_NOPE",null,null)))));
            assertThrows(IllegalArgumentException.class,()->batch.plan(src,ss,tgt,ts,tables,new Options(false,false,false,false,"skip",100,List.of(new TableOption("TF_O_P",null,"1=1; DROP TABLE x")))));
            var wrongColumn=batch.plan(src,ss,tgt,ts,tables,new Options(false,false,false,false,"skip",100,List.of(new TableOption("TF_O_P",List.of(new Mapping("NOPE","x")),null))));
            assertTrue(wrongColumn.blocked(),wrongColumn.toString()); assertNull(actual(tgt,ts,p));

            var plan=batch.plan(src,ss,tgt,ts,tables,options);
            assertFalse(plan.blocked(),plan.toString());
            assertEquals(252,plan.totalRows(),"부모는 조건에 맞는 2행만");
            var parentPlan=plan.items().stream().filter(i->i.table().equals("TF_O_P")).findFirst().orElseThrow();
            assertEquals(List.of("SECRET"),parentPlan.excluded()); assertEquals(2,parentPlan.columns());

            // 취소 후 재개: 자식 첫 분할(100행) 뒤 취소 → 대기 상태라도 이 작업이 만든 테이블이므로 건너뛰지 않고 이어서 적재한다
            var cancelling=new Recording(); cancelling.cancelAfter="TF_O_C";
            assertThrows(RuntimeException.class,()->batch.run("opt-1",src,ss,tgt,ts,tables,options,List.of(),cancelling));
            assertEquals("pending",item(cancelling.items,"TF_O_C").state()); assertTrue(item(cancelling.items,"TF_O_C").created());
            assertEquals("100",one(t,"SELECT COUNT(*) FROM "+q(to,actual(tgt,ts,c))));

            // 구조 보완: 테이블 생성 뒤 인덱스·FK DDL이 실패한 상황을 만들기 위해 앱이 만든 대상 자식에서 FK와 인덱스만 제거한다(행과 영수증은 유지)
            String childActual=actual(tgt,ts,c); boolean my=to==Dbms.mysql||to==Dbms.mariadb;
            var before=explorer.extras(tgt,ts,childActual);
            for(var fk:before.foreignKeys()) exec(t,"ALTER TABLE "+q(to,childActual)+(my?" DROP FOREIGN KEY ":" DROP CONSTRAINT ")+q(to,fk.name()));
            for(var ix:explorer.extras(tgt,ts,childActual).indexes()) exec(t,"DROP INDEX "+q(to,ix.name())+(my?" ON "+q(to,childActual):""));
            assertTrue(explorer.extras(tgt,ts,childActual).foreignKeys().isEmpty()); assertTrue(explorer.extras(tgt,ts,childActual).indexes().isEmpty());

            var resumed=new Recording();
            var done=batch.run("opt-1",src,ss,tgt,ts,tables,options,cancelling.items,resumed);
            assertEquals("succeeded",item(resumed.items,"TF_O_C").state(),resumed.items.toString());
            assertEquals(252,done.insertedRows());
            var extras=explorer.extras(tgt,ts,actual(tgt,ts,c));
            assertEquals(1,extras.foreignKeys().size(),"빠진 FK를 재개 때 추가: "+item(resumed.items,"TF_O_C").message());
            assertTrue(extras.indexes().stream().anyMatch(i->i.columns().size()==1&&i.columns().get(0).equalsIgnoreCase(note)),"빠진 인덱스를 재개 때 추가: "+extras.indexes());

            // 설정이 실제로 적용되었는지
            assertEquals("2",one(t,"SELECT COUNT(*) FROM "+q(to,actual(tgt,ts,p))));
            assertEquals("NAME1",one(t,"SELECT "+q(to,label)+" FROM "+q(to,actual(tgt,ts,p))+" WHERE "+q(to,id)+"=1"));
            assertEquals(2,explorer.columns(tgt,ts,actual(tgt,ts,p)).items().size());

            // 검증: 조건·매핑을 반영해 일치, 대상 값을 바꾸면 그 테이블만 불일치
            var ok=batch.verify(src,ss,tgt,ts,tables,options);
            assertEquals(2,ok.matched(),ok.toString()); assertEquals(0,ok.mismatched()+ok.failed());
            exec(t,"UPDATE "+q(to,actual(tgt,ts,c))+" SET "+q(to,note)+"='changed-longer-value' WHERE "+q(to,id)+"=1");
            var bad=batch.verify(src,ss,tgt,ts,tables,options);
            assertEquals(1,bad.mismatched(),bad.toString());
            assertEquals("mismatch",bad.items().stream().filter(i->i.table().equals("TF_O_C")).findFirst().orElseThrow().status());
            // 대상 테이블이 없으면 그 테이블만 missing
            dropQuiet(t,q(to,actual(tgt,ts,c)));
            var missing=batch.verify(src,ss,tgt,ts,tables,options);
            assertEquals(1,missing.failed(),missing.toString()); assertEquals(1,missing.matched());

            clean(t,tgt,to,ts);
            for(String n:List.of("TF_O_C","TF_O_P")) dropQuiet(s,q(from,n));
        }
    }
}
