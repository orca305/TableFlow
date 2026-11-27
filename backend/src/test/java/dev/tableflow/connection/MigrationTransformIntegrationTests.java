package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Mapping;
import dev.tableflow.load.MigrationVerifier;
import dev.tableflow.load.ValueTransform.Step;
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

/** 값 변환 규칙(합치기·분할·치환·계산)을 실서버에서 확인한다: 신규 테이블과 기존 테이블 모두, 사전 점검·이관·검증까지. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class MigrationTransformIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired MigrationVerifier verifier;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=(db==Dbms.mysql||db==Dbms.mariadb)?"`":"\"";return c+id+c;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void dropQuiet(Connection c,String name){try(var st=c.createStatement()){st.execute("DROP TABLE "+name);}catch(SQLException ignored){}}
    String text(Dbms db){return db==Dbms.oracle?"VARCHAR2":"VARCHAR";}
    String dec(Dbms db){return db==Dbms.oracle?"NUMBER":"DECIMAL";}
    static Step step(String op){return new Step(op,null,null,null,null,null,null,null,null,null,null);}

    record Out(String full,String area,String flag,String code,BigDecimal total){}
    Map<Integer,Out> read(Connection c,Dbms db,String table)throws SQLException{
        Map<Integer,Out> out=new TreeMap<>();
        String sql="SELECT "+String.join(",",List.of("ID","FULLNAME","AREA","FLAGN","CODE2","TOTAL").stream().map(n->q(db,n)).toList())+" FROM "+q(db,table);
        try(var st=c.createStatement();var rs=st.executeQuery(sql)){while(rs.next()){String f=rs.getString(2);out.put(rs.getInt(1),new Out(f==null?null:f.strip(),rs.getString(3),rs.getString(4),rs.getString(5),rs.getBigDecimal(6)));}}
        return out;
    }

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void appliesTransformsToNewAndExistingTables(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            for(String n:List.of("TF_T_DST","tableflow_load_receipts")) dropQuiet(t,q(to,n));
            dropQuiet(s,q(from,"TF_T_SRC"));
            exec(s,"CREATE TABLE "+q(from,"TF_T_SRC")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"FNAME")+" "+text(from)+"(20),"+q(from,"LNAME")+" "+text(from)+"(20),"
                +q(from,"PHONE")+" "+text(from)+"(20),"+q(from,"FLAG")+" "+text(from)+"(1),"+q(from,"PRICE")+" "+dec(from)+"(10,2),"+q(from,"QTY")+" INTEGER,"+q(from,"CODE")+" "+text(from)+"(20))"
                +((from==Dbms.mysql||from==Dbms.mariadb)?" ENGINE=InnoDB":""));
            exec(s,"INSERT INTO "+q(from,"TF_T_SRC")+" VALUES (1,'길동','홍','010-1234-5678','Y',10.5,3,' ab-12 ')");
            exec(s,"INSERT INTO "+q(from,"TF_T_SRC")+" VALUES (2,'서연','박','02-555-0000','N',2,5,'cd-34')");
            exec(s,"INSERT INTO "+q(from,"TF_T_SRC")+" VALUES (3,'A','"+"Z"+"',NULL,NULL,NULL,1,NULL)");
            exec(s,"UPDATE "+q(from,"TF_T_SRC")+" SET "+q(from,"LNAME")+"=NULL WHERE "+q(from,"ID")+"=3");

            var full=new Step("template",null,null,null,null,null,null,null,null,"{LNAME} {FNAME}",null);
            var area=new Step("split",null,null,null,null,"-",1,null,null,null,null);
            var flag=new Step("map",null,null,null,Map.of("Y","1","N","0"),null,null,null,null,null,null);
            var dash=new Step("replace","-","",null,null,null,null,null,null,null,null);
            var total=new Step("calc",null,null,null,null,null,null,null,null,"{PRICE} * {QTY}",2);
            var map=List.of(new Mapping("ID","ID"),
                new Mapping("FNAME","FULLNAME",null,null,null,List.of(full)),
                new Mapping("PHONE","AREA",null,null,null,List.of(area)),
                new Mapping("FLAG","FLAGN",null,null,null,List.of(flag)),
                new Mapping("CODE","CODE2",null,null,null,List.of(step("trim"),dash,step("upper"))),
                new Mapping("PRICE","TOTAL",null,null,null,List.of(total)));

            // 신규 테이블: 사전 점검(분석 포함) → 이관
            var analysis=migration.analyze(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",map,true,false);
            assertTrue(analysis.columns().stream().filter(c->!c.source().equals("ID")).allMatch(c->c.conversion().equals("변환 규칙 적용")),analysis.columns().toString());
            assertEquals(3,migration.check(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",map,100,true,false,500,MigrationService.Extras.NONE).rows());
            assertEquals(3,migration.execute(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",map,100,true,false,UUID.randomUUID().toString()).insertedRows());
            var got=read(t,to,"TF_T_DST");
            assertEquals("홍 길동",got.get(1).full()); assertEquals("박 서연",got.get(2).full()); assertEquals("A",got.get(3).full());
            assertEquals("010",got.get(1).area()); assertEquals("02",got.get(2).area()); assertNull(got.get(3).area());
            assertEquals("1",got.get(1).flag()); assertEquals("0",got.get(2).flag()); assertNull(got.get(3).flag());
            assertEquals("AB12",got.get(1).code()); assertEquals("CD34",got.get(2).code()); assertNull(got.get(3).code());
            assertEquals(0,new BigDecimal("31.50").compareTo(got.get(1).total())); assertEquals(0,new BigDecimal("10.00").compareTo(got.get(2).total())); assertNull(got.get(3).total());

            // 이관 후 검증: 변환 컬럼은 원본에 규칙을 다시 적용한 결과와 대상을 비교한다
            var ok=verifier.verify(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",map,null);
            assertTrue(ok.allMatch(),ok.checks().toString()+ok.notes()); assertEquals(3,ok.sourceRows());
            assertTrue(ok.checks().stream().anyMatch(c->c.target().equals("TOTAL")&&c.measure().startsWith("합계")),ok.checks().toString());
            assertTrue(ok.checks().stream().anyMatch(c->c.target().equals("CODE2")&&c.measure().startsWith("문자열 길이")),ok.checks().toString());
            exec(t,"UPDATE "+q(to,"TF_T_DST")+" SET "+q(to,"TOTAL")+"=999,"+q(to,"CODE2")+"='AB' WHERE "+q(to,"ID")+"=1");
            var changed=verifier.verify(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",map,null);
            assertFalse(changed.allMatch()); assertTrue(changed.rowsMatch());
            assertEquals(Set.of("TOTAL","CODE2"),changed.checks().stream().filter(c->!c.match()).map(c->c.target()).collect(java.util.stream.Collectors.toSet()),changed.checks().toString());
            // 행 조건은 변환 결과 계산에도 적용된다
            var filtered=verifier.verify(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",map,q(from,"ID")+" > 1");
            assertFalse(filtered.rowsMatch());

            // 기존 테이블: 같은 규칙을 대상 컬럼 타입에 맞춰 적재
            exec(t,"DELETE FROM "+q(to,"TF_T_DST"));
            assertEquals(3,migration.execute(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",map,100,false,false,UUID.randomUUID().toString()).insertedRows());
            assertEquals(got,read(t,to,"TF_T_DST"));

            // 분할(키 기반) 이관에서도 같은 결과 + 규칙이 참조하는 추가 컬럼(LNAME)을 읽는다
            exec(t,"DELETE FROM "+q(to,"TF_T_DST"));
            var outcome=migration.runBulk(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",map,100,false,false,UUID.randomUUID().toString(),new NoProgress(),100,MigrationService.Extras.NONE);
            assertEquals(3,outcome.insertedRows());
            assertEquals(got,read(t,to,"TF_T_DST"));

            // 잘못된 규칙은 거부
            var bad=List.of(new Mapping("ID","ID"),new Mapping("FNAME","FULLNAME",null,null,null,List.of(new Step("template",null,null,null,null,null,null,null,null,"{없는컬럼}",null))));
            assertThrows(IllegalArgumentException.class,()->migration.analyze(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",bad,true,false));
            // 숫자가 아닌 값을 계산하면 이관 전에 거부(사전 점검의 표본 검사)
            var nan=List.of(new Mapping("ID","ID"),new Mapping("PHONE","AREA",null,null,null,List.of(new Step("calc",null,null,null,null,null,null,null,null,"{@} + 1",null))));
            assertThrows(IllegalArgumentException.class,()->migration.check(src,ss,"TF_T_SRC",tgt,ts,"TF_T_DST",nan,100,false,false,500,MigrationService.Extras.NONE));

            for(String n:List.of("TF_T_DST","tableflow_load_receipts")) dropQuiet(t,q(to,n));
            dropQuiet(s,q(from,"TF_T_SRC"));
        }
    }
    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void appliesDateRegexAndConditionRulesAndVerifiesTheResult(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            for(String n:List.of("TF_N_DST","tableflow_load_receipts")) dropQuiet(t,q(to,n));
            dropQuiet(s,q(from,"TF_N_SRC"));
            try {
                exec(s,"CREATE TABLE "+q(from,"TF_N_SRC")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"HIRED")+" DATE,"+q(from,"YMD")+" "+text(from)+"(8),"
                    +q(from,"PHONE")+" "+text(from)+"(20),"+q(from,"AMT")+" "+dec(from)+"(10,2),"+q(from,"JOINED")+" DATE)"+((from==Dbms.mysql||from==Dbms.mariadb)?" ENGINE=InnoDB":""));
                // 분할 읽기(5,000행)를 넘겨 검증이 여러 번 나눠 읽는 경로도 지난다
                s.setAutoCommit(false);
                try(var st=s.prepareStatement("INSERT INTO "+q(from,"TF_N_SRC")+" VALUES (?,?,?,?,?,?)")) {
                    for(int i=1;i<=5200;i++) {
                        boolean empty=i%1000==0; var day=java.time.LocalDate.of(2020,1,1).plusDays(i%1500);
                        st.setInt(1,i); st.setDate(2,empty?null:java.sql.Date.valueOf(day)); st.setString(3,empty?null:day.toString().replace("-",""));
                        st.setString(4,empty?null:"010"+String.format("%08d",i)); st.setBigDecimal(5,empty?null:BigDecimal.valueOf(i%300).setScale(2)); st.setDate(6,empty?null:java.sql.Date.valueOf(day)); st.addBatch();
                    }
                    st.executeBatch();
                }
                s.commit(); s.setAutoCommit(true);
                var map=List.of(new Mapping("ID","ID"),new Mapping("JOINED","JOINED"),
                    new Mapping("HIRED","HIRED_TXT",null,null,null,List.of(new Step("dateFormat",null,"yyyy/MM/dd",null,null,null,null,null,null,null,null))),
                    new Mapping("YMD","YMD_DATE",dev.tableflow.model.Table.DataType.date,null,null,List.of(new Step("dateFormat","yyyyMMdd","yyyy-MM-dd",null,null,null,null,null,null,null,null))),
                    new Mapping("PHONE","PHONE_FMT",null,null,null,List.of(new Step("regexReplace","(\\d{3})(\\d{4})(\\d{4})","$1-$2-$3",null,null,null,null,null,null,null,null))),
                    new Mapping("AMT","GRADE",null,null,null,List.of(new Step("case",null,null,"C",null,null,null,null,null,null,null,
                        List.of(new dev.tableflow.load.ValueTransform.Case(null,"ge","200","최우수"),new dev.tableflow.load.ValueTransform.Case(null,"ge","100","B+"))))));
                // 첫 분할 뒤 중지하고 같은 요청으로 재개: 이 작업이 만든 테이블에서도 완료 분할은 건너뛰고 끝까지 적재한다
                var id=UUID.randomUUID().toString(); var progress=new Resumable(); progress.stop=true;
                assertThrows(RuntimeException.class,()->migration.runBulk(src,ss,"TF_N_SRC",tgt,ts,"TF_N_DST",map,100000,true,false,id,progress,2000,MigrationService.Extras.NONE));
                progress.stop=false; progress.done=0;
                var resumed=migration.runBulk(src,ss,"TF_N_SRC",tgt,ts,"TF_N_DST",map,100000,true,false,id,progress,2000,MigrationService.Extras.NONE);
                assertEquals(5200,resumed.insertedRows()); assertEquals(1,resumed.replayedChunks());
                String row="SELECT "+q(to,"HIRED_TXT")+","+q(to,"YMD_DATE")+","+q(to,"PHONE_FMT")+","+q(to,"GRADE")+" FROM "+q(to,"TF_N_DST")+" WHERE "+q(to,"ID")+"=";
                try(var st=t.createStatement()) {
                    try(var rs=st.executeQuery(row+"250")){rs.next();assertEquals("2020/09/07",rs.getString(1));assertEquals(java.sql.Date.valueOf("2020-09-07"),rs.getDate(2));assertEquals("010-0000-0250",rs.getString(3));assertEquals("최우수",rs.getString(4));}
                    try(var rs=st.executeQuery(row+"1000")){rs.next();assertNull(rs.getString(1));assertNull(rs.getDate(2));assertNull(rs.getString(3));assertEquals("C",rs.getString(4));}
                }
                var ok=verifier.verify(src,ss,"TF_N_SRC",tgt,ts,"TF_N_DST",map,null);
                assertTrue(ok.allMatch(),ok.checks().toString()+ok.notes()); assertEquals(5200,ok.targetRows());
                for(String column:List.of("HIRED_TXT","YMD_DATE","PHONE_FMT","GRADE")) assertTrue(ok.checks().stream().anyMatch(c->c.target().equals(column)),column);
                exec(t,"UPDATE "+q(to,"TF_N_DST")+" SET "+q(to,"YMD_DATE")+"=DATE '1999-01-01' WHERE "+q(to,"ID")+"=5100");
                var bad=verifier.verify(src,ss,"TF_N_SRC",tgt,ts,"TF_N_DST",map,null);
                assertEquals(List.of("YMD_DATE"),bad.checks().stream().filter(c->!c.match()).map(c->c.target()).distinct().toList(),bad.checks().toString());
            } finally {
                for(String n:List.of("TF_N_DST","tableflow_load_receipts")) dropQuiet(t,q(to,n));
                dropQuiet(s,q(from,"TF_N_SRC"));
            }
        }
    }
    /** 작업 목록처럼 "이 작업이 만든 테이블"을 기억하고, stop이면 첫 분할 뒤 중지한다. */
    static class Resumable implements MigrationService.Progress {
        boolean stop; int done; String created;
        public boolean cancelled(){return stop&&done>=1;}
        public void total(int chunks,long rows){}
        public void chunk(int c,long rows,int replayed){done=c;}
        public boolean createdTarget(String key){return key.equals(created);}
        public void targetCreated(String key){created=key;}
    }
    static class NoProgress implements MigrationService.Progress {
        public boolean cancelled(){return false;}
        public void total(int chunks,long rows){}
        public void chunk(int done,long rows,int replayed){}
    }
}
