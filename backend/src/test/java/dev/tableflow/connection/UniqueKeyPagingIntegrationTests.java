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

/** 기본 키가 없는 테이블의 키 선택(NOT NULL 고유 인덱스), 키 기반 분할 이관·재개, 긴 조회의 시간 제한을 실서버에서 확인한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class UniqueKeyPagingIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired JdbcReader reader; @Autowired MigrationService migration;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    static boolean my(Dbms db){return db==Dbms.mysql||db==Dbms.mariadb;}
    String q(Dbms db,String id){String c=my(db)?"`":"\"";return c+id+c;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void drop(Connection c,Dbms db,String table){try(var st=c.createStatement()){st.execute("DROP TABLE "+q(db,table));}catch(SQLException ignored){}}
    void assumeEnabled(Dbms... dbs){var list=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));for(Dbms d:dbs)Assumptions.assumeTrue(list.contains(d.name()));}
    static class Resumable implements MigrationService.Progress {
        boolean stop; int done;
        public boolean cancelled(){return stop&&done>=2;} public void total(int c,long r){} public void chunk(int c,long r,int p){done=c;}
    }

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,mysql"})
    void pagesByANotNullUniqueIndexAndSkipsUnsafeIndexes(Dbms from,Dbms to)throws Exception {
        assumeEnabled(from,to);
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        String text=from==Dbms.oracle?"VARCHAR2":"VARCHAR", engine=my(from)?" ENGINE=InnoDB":"";
        var tables=List.of("TF_UK_SRC","TF_UK_NULL","TF_UK_NONE","TF_UK_PART");
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            for(String n:tables) drop(s,from,n); for(String n:List.of("TF_UK_DST","tableflow_load_receipts")) drop(t,to,n);
            try {
                // 복합 고유 인덱스(A, B): A만으로는 중복. 저장 순서는 키 순서와 다르게 넣는다
                exec(s,"CREATE TABLE "+q(from,"TF_UK_SRC")+" ("+q(from,"A")+" INTEGER NOT NULL,"+q(from,"B")+" "+text+"(10) NOT NULL,"+q(from,"NOTE")+" "+text+"(20),"+q(from,"OPT")+" INTEGER)"+engine);
                exec(s,"CREATE UNIQUE INDEX "+q(from,"UX_TF_UK_SRC_OPT")+" ON "+q(from,"TF_UK_SRC")+" ("+q(from,"OPT")+")"); // NULL 허용 컬럼: 키로 쓰면 안 됨
                exec(s,"CREATE UNIQUE INDEX "+q(from,"UX_TF_UK_SRC_AB")+" ON "+q(from,"TF_UK_SRC")+" ("+q(from,"A")+","+q(from,"B")+")");
                s.setAutoCommit(false);
                try(var st=s.prepareStatement("INSERT INTO "+q(from,"TF_UK_SRC")+" VALUES (?,?,?,?)")) {
                    for(int i=0;i<1050;i++){int k=(i*601)%1050; st.setInt(1,k/7); st.setString(2,"b"+(k%7)); st.setString(3,"n"+(k%3)); if(k%2==0)st.setNull(4,Types.INTEGER);else st.setInt(4,k); st.addBatch();}
                    st.executeBatch();
                }
                s.commit(); s.setAutoCommit(true);
                assertTrue(reader.hasPageKey(src,ss,"TF_UK_SRC"));
                var cursor=new JdbcReader.Keyset(null); var seen=new ArrayList<String>();
                for(int page=0;page<11;page++){
                    var rows=reader.readKeyPage(src,ss,"TF_UK_SRC",List.of("NOTE","A","B"),cursor,100); cursor=new JdbcReader.Keyset(cursor.last());
                    for(var r:rows.rows()) seen.add(r.cells().get("col_2").value()+"/"+r.cells().get("col_3").value());
                }
                assertEquals(1050,seen.size()); assertEquals(1050,new HashSet<>(seen).size(),"모든 행을 정확히 한 번씩");
                assertEquals("0/b0",seen.get(0)); assertEquals("149/b6",seen.get(1049));

                // 분할 이관: 2회 뒤 중지하고 재개해도 중복·누락이 없다
                var map=List.of(new Mapping("NOTE","NOTE"),new Mapping("A","A"),new Mapping("B","B"));
                var progress=new Resumable(); progress.stop=true; var id=UUID.randomUUID().toString();
                assertThrows(RuntimeException.class,() -> migration.runBulk(src,ss,"TF_UK_SRC",tgt,ts,"TF_UK_DST",map,10000,true,false,id,progress,100));
                progress.stop=false; progress.done=0;
                var done=migration.runBulk(src,ss,"TF_UK_SRC",tgt,ts,"TF_UK_DST",map,10000,true,false,id,progress,100);
                assertEquals(1050,done.insertedRows()); assertEquals(2,done.replayedChunks());
                try(var st=t.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM (SELECT DISTINCT "+q(to,"A")+","+q(to,"B")+" FROM "+q(to,"TF_UK_DST")+") x")){rs.next();assertEquals(1050,rs.getInt(1));}

                // NULL 허용 고유 인덱스만 있거나 인덱스가 없으면 OFFSET 방식
                exec(s,"CREATE TABLE "+q(from,"TF_UK_NULL")+" ("+q(from,"A")+" INTEGER)"+engine);
                exec(s,"CREATE UNIQUE INDEX "+q(from,"UX_TF_UK_NULL")+" ON "+q(from,"TF_UK_NULL")+" ("+q(from,"A")+")");
                exec(s,"CREATE TABLE "+q(from,"TF_UK_NONE")+" ("+q(from,"A")+" INTEGER NOT NULL)"+engine);
                exec(s,"CREATE INDEX "+q(from,"IX_TF_UK_NONE")+" ON "+q(from,"TF_UK_NONE")+" ("+q(from,"A")+")");
                assertFalse(reader.hasPageKey(src,ss,"TF_UK_NULL")); assertFalse(reader.hasPageKey(src,ss,"TF_UK_NONE"));
                assertThrows(IllegalArgumentException.class,() -> reader.readKeyPage(src,ss,"TF_UK_NONE",List.of("A"),new JdbcReader.Keyset(null),10));
                if(from==Dbms.postgresql) { // 조건부 고유 인덱스는 전체 행에서 고유하지 않다
                    exec(s,"CREATE TABLE \"TF_UK_PART\" (\"A\" INTEGER NOT NULL)");
                    exec(s,"CREATE UNIQUE INDEX \"UX_TF_UK_PART\" ON \"TF_UK_PART\" (\"A\") WHERE \"A\" > 100");
                    exec(s,"INSERT INTO \"TF_UK_PART\" VALUES (1),(1),(101)");
                    assertFalse(reader.hasPageKey(src,ss,"TF_UK_PART"));
                }
            } finally { s.setAutoCommit(true); for(String n:tables) drop(s,from,n); for(String n:List.of("TF_UK_DST","tableflow_load_receipts")) drop(t,to,n); }
        }
    }

    @ParameterizedTest(name="{0}")
    @CsvSource({"oracle","postgresql","mysql","mariadb"})
    void slowStatementsPassTheDefaultReadTimeoutOnlyWhenAllowed(Dbms db) {
        assumeEnabled(db);
        var cred=credentials(db);
        String sleep=switch(db){case postgresql->"SELECT pg_sleep(12)";case oracle->"BEGIN DBMS_SESSION.SLEEP(12); END;";default->"SELECT SLEEP(12)";};
        assertThrows(RuntimeException.class,() -> explorer.withConnection(cred,true,c -> { try(var st=c.createStatement()){st.execute(sleep);} return null; }),"기본 읽기 제한 10초");
        assertDoesNotThrow(() -> explorer.withConnection(cred,true,c -> { JdbcExplorer.allowSlow(c,60); try(var st=c.createStatement()){st.setQueryTimeout(60);st.execute(sleep);} return null; }));
    }
}
