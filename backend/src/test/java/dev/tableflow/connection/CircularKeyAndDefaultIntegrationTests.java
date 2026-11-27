package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.BatchMigration;
import dev.tableflow.load.BatchMigration.Item;
import dev.tableflow.load.BatchMigration.Options;
import dev.tableflow.load.BatchMigration.TableOption;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Extras;
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

/** 순환·자기 참조 외래 키를 적재 후에 만드는 것과 기본값 복제를 실서버에서 확인한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class CircularKeyAndDefaultIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired BatchMigration batch;
    static final List<String> TABLES=List.of("TF_FK_D","TF_FK_A","TF_FK_B","TF_FK_E");
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    static boolean my(Dbms db){return db==Dbms.mysql||db==Dbms.mariadb;}
    String q(Dbms db,String id){String c=my(db)?"`":"\"";return c+id+c;}
    void enabled(Dbms from,Dbms to){var list=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));Assumptions.assumeTrue(list.contains(from.name())&&list.contains(to.name()));}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    String one(Connection c,String sql)throws SQLException{try(var st=c.createStatement();var rs=st.executeQuery(sql)){rs.next();return rs.getString(1);}}
    String actual(Credentials c,String schema,String wanted){for(String t:explorer.tables(c,schema).items())if(t.equalsIgnoreCase(wanted))return t;return null;}
    /** 서로 참조하는 테이블은 FK를 먼저 지워야 삭제할 수 있다. */
    void drop(Connection c,Credentials cred,Dbms db,List<String> names){
        String s=schema(db); List<String> found=names.stream().map(n->actual(cred,s,n)).filter(Objects::nonNull).toList();
        for(String t:found) for(var fk:explorer.extras(cred,s,t).foreignKeys()) exec(c,"ALTER TABLE "+q(db,t)+(my(db)?" DROP FOREIGN KEY ":" DROP CONSTRAINT ")+q(db,fk.name()));
        for(String t:found) exec(c,"DROP TABLE "+q(db,t));
    }
    static class Resumable implements MigrationService.Progress {
        boolean stop; int done; String created;
        public boolean cancelled(){return stop&&done>=1;}
        public void total(int chunks,long rows){} public void chunk(int c,long rows,int replayed){done=c;}
        public boolean createdTarget(String key){return key.equals(created);} public void targetCreated(String key){created=key;}
    }
    static class Recording implements BatchMigration.BatchProgress {
        volatile List<Item> items=List.of();
        public boolean cancelled(){return false;} public void total(int c,long r){} public void chunk(int d,long r,int p){} public void items(List<Item> i){items=List.copyOf(i);}
    }

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,mysql"})
    void createsCircularAndSelfReferencingKeysAfterLoading(Dbms from,Dbms to)throws Exception {
        enabled(from,to);
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        String engine=my(from)?" ENGINE=InnoDB":"";
        var all=new ArrayList<>(TABLES); all.add("tableflow_load_receipts"); all.add("TF_FK_E2"); all.add("TF_FK_E3");
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            drop(s,src,from,TABLES); drop(t,tgt,to,all);
            try {
                for(String n:List.of("TF_FK_A","TF_FK_B","TF_FK_D","TF_FK_E"))
                    exec(s,"CREATE TABLE "+q(from,n)+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"REF_ID")+" INTEGER)"+engine);
                for(int i=1;i<=3;i++){ exec(s,"INSERT INTO "+q(from,"TF_FK_A")+" VALUES ("+i+",NULL)"); exec(s,"INSERT INTO "+q(from,"TF_FK_B")+" VALUES ("+i+","+i+")"); exec(s,"INSERT INTO "+q(from,"TF_FK_D")+" VALUES ("+i+","+i+")"); }
                exec(s,"UPDATE "+q(from,"TF_FK_A")+" SET "+q(from,"REF_ID")+"="+q(from,"ID"));
                s.setAutoCommit(false);
                for(int i=1;i<=250;i++) exec(s,"INSERT INTO "+q(from,"TF_FK_E")+" VALUES ("+i+",NULL)");
                s.commit(); s.setAutoCommit(true);
                // 앞 행이 뒤 행을 참조한다: 분할 적재 중에 FK가 있으면 실패하는 모양
                exec(s,"UPDATE "+q(from,"TF_FK_E")+" SET "+q(from,"REF_ID")+"="+q(from,"ID")+"+1 WHERE "+q(from,"ID")+"<250");
                for(var fk:List.of(new String[]{"A","B"},new String[]{"B","A"},new String[]{"D","A"},new String[]{"E","E"}))
                    exec(s,"ALTER TABLE "+q(from,"TF_FK_"+fk[0])+" ADD CONSTRAINT "+q(from,"FK_TF_"+fk[0]+fk[1])+" FOREIGN KEY ("+q(from,"REF_ID")+") REFERENCES "+q(from,"TF_FK_"+fk[1])+"("+q(from,"ID")+")");

                var options=new Options(false,false,false,true,"skip",100);
                var plan=batch.plan(src,ss,tgt,ts,TABLES,options);
                assertFalse(plan.blocked(),plan.toString()); assertTrue(plan.cycles().containsAll(List.of("TF_FK_A","TF_FK_B")),plan.cycles().toString());
                var progress=new Recording();
                var done=batch.run("fk-1",src,ss,tgt,ts,TABLES,options,List.of(),progress);
                assertEquals(259,done.insertedRows(),progress.items.toString());
                for(String n:TABLES) {
                    var keys=explorer.extras(tgt,ts,actual(tgt,ts,n)).foreignKeys();
                    assertEquals(1,keys.size(),n+" "+progress.items+done.notes());
                }
                assertTrue(explorer.extras(tgt,ts,actual(tgt,ts,"TF_FK_E")).foreignKeys().get(0).parentTable().equalsIgnoreCase("TF_FK_E"));
                // 같은 작업을 다시 실행해도 외래 키가 중복되지 않는다
                batch.run("fk-1",src,ss,tgt,ts,TABLES,options,progress.items,new Recording());
                for(String n:TABLES) assertEquals(1,explorer.extras(tgt,ts,actual(tgt,ts,n)).foreignKeys().size(),n);

                // 단건 분할 이관: 첫 분할 뒤 중지하고 재개해도 자기 참조 FK는 마지막에 만든다
                var map=List.of(new Mapping("ID",q0(to,"ID")),new Mapping("REF_ID",q0(to,"REF_ID")));
                var extras=new Extras(false,false,true);
                var analysis=migration.analyze(src,ss,"TF_FK_E",tgt,ts,q0(to,"TF_FK_E2"),map,true,false,extras);
                assertTrue(analysis.ddl().stream().anyMatch(d->d.contains("FOREIGN KEY")),analysis.ddl().toString());
                var resumable=new Resumable(); resumable.stop=true; var id=UUID.randomUUID().toString();
                assertThrows(RuntimeException.class,()->migration.runBulk(src,ss,"TF_FK_E",tgt,ts,q0(to,"TF_FK_E2"),map,1000,true,false,id,resumable,100,extras));
                assertTrue(explorer.extras(tgt,ts,actual(tgt,ts,"TF_FK_E2")).foreignKeys().isEmpty(),"적재가 끝나기 전에는 만들지 않는다");
                resumable.stop=false; resumable.done=0;
                var resumed=migration.runBulk(src,ss,"TF_FK_E",tgt,ts,q0(to,"TF_FK_E2"),map,1000,true,false,id,resumable,100,extras);
                assertEquals(250,resumed.insertedRows());
                assertEquals(1,explorer.extras(tgt,ts,actual(tgt,ts,"TF_FK_E2")).foreignKeys().size(),resumed.notes().toString());

                // 행 조건으로 참조 대상이 빠지면: 데이터는 적재하고 만들지 못한 FK를 알린다(즉시 실행)
                var partial=migration.execute(src,ss,"TF_FK_E",tgt,ts,q0(to,"TF_FK_E3"),map,1000,true,false,UUID.randomUUID().toString(),new Extras(false,false,true,q(from,"ID")+" <= 50"));
                assertEquals(50,partial.insertedRows());
                assertTrue(partial.notes().stream().anyMatch(n->n.contains("외래 키를 만들지 못했습니다")),partial.notes().toString());
                assertTrue(explorer.extras(tgt,ts,actual(tgt,ts,"TF_FK_E3")).foreignKeys().isEmpty());
            } finally { s.setAutoCommit(true); drop(s,src,from,TABLES); drop(t,tgt,to,all); }
        }
    }
    /** 대상 이름 관례(PostgreSQL 소문자). 원본은 어느 DB든 따옴표를 붙여 대문자로 만든다. */
    String q0(Dbms db,String upper){return db==Dbms.postgresql?upper.toLowerCase(Locale.ROOT):upper;}

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,mysql","mariadb,postgresql","postgresql,mysql"})
    void copiesLiteralAndCurrentDateDefaults(Dbms from,Dbms to)throws Exception {
        enabled(from,to);
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            var names=List.of("TF_DF","tableflow_load_receipts");
            drop(s,src,from,List.of("TF_DF")); drop(t,tgt,to,names);
            try {
                boolean ora=from==Dbms.oracle;
                String today=ora?"TRUNC(SYSDATE)":my(from)?"(CURRENT_DATE)":"CURRENT_DATE";
                String day=ora?"DATE '2024-01-31'":"'2024-01-31'";
                String stamp=ora?"TIMESTAMP '2024-01-31 10:20:30'":"'2024-01-31 10:20:30'";
                exec(s,"CREATE TABLE "+q(from,"TF_DF")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"
                    +q(from,"NEG")+" INTEGER DEFAULT -5,"+q(from,"TXT")+" "+(ora?"VARCHAR2":"VARCHAR")+"(20) DEFAULT 'abc',"
                    +q(from,"DAY_FIXED")+" DATE DEFAULT "+day+","+q(from,"DAY_NOW")+" DATE DEFAULT "+today+","
                    +q(from,"STAMP")+" "+(my(from)?"DATETIME":"TIMESTAMP")+" DEFAULT "+stamp+","+q(from,"RATE")+" "+(ora?"NUMBER":"DECIMAL")+"(10,2) DEFAULT 0.50)"+(my(from)?" ENGINE=InnoDB":""));
                exec(s,"INSERT INTO "+q(from,"TF_DF")+" ("+q(from,"ID")+") VALUES (1)");
                var columns=explorer.columns(src,ss,"TF_DF").items();
                var map=columns.stream().map(c->new Mapping(c.name(),q0(to,c.name().toUpperCase(Locale.ROOT)))).toList();
                var out=migration.execute(src,ss,"TF_DF",tgt,ts,q0(to,"TF_DF"),map,100,true,false,UUID.randomUUID().toString(),new Extras(false,true,false));
                assertEquals(1,out.insertedRows());
                // 대상에 ID만 넣으면 복제한 기본값이 채워진다
                exec(t,"INSERT INTO "+q(to,q0(to,"TF_DF"))+" ("+q(to,q0(to,"ID"))+") VALUES (100)");
                String select="SELECT "+String.join(",",List.of("NEG","TXT","DAY_FIXED","DAY_NOW","STAMP","RATE").stream().map(n->q(to,q0(to,n))).toList())
                    +",CURRENT_DATE FROM "+q(to,q0(to,"TF_DF"))+" WHERE "+q(to,q0(to,"ID"))+"=100";
                try(var st=t.createStatement();var rs=st.executeQuery(select)) {
                    assertTrue(rs.next());
                    assertEquals(-5,rs.getInt(1)); assertFalse(rs.wasNull()); assertEquals("abc",rs.getString(2));
                    assertEquals(java.time.LocalDate.of(2024,1,31),rs.getTimestamp(3).toLocalDateTime().toLocalDate());
                    assertNotNull(rs.getTimestamp(4),"오늘 날짜 기본값");
                    assertEquals(rs.getTimestamp(7).toLocalDateTime().toLocalDate(),rs.getTimestamp(4).toLocalDateTime().toLocalDate());
                    assertEquals(java.time.LocalTime.MIDNIGHT,rs.getTimestamp(4).toLocalDateTime().toLocalTime(),"시각 없는 오늘 날짜");
                    assertEquals(java.time.LocalDateTime.of(2024,1,31,10,20,30),rs.getTimestamp(5).toLocalDateTime());
                    assertEquals(0,new BigDecimal("0.50").compareTo(rs.getBigDecimal(6)));
                }
            } finally { drop(s,src,from,List.of("TF_DF")); drop(t,tgt,to,names); }
        }
    }
}
