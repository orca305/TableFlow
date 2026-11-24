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

/** 일회용 테스트 컨테이너 DB 사이의 실제 DB → DB 이전. 운영/개인 DB로 포트를 바꿔 실행하지 마세요. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class MigrationIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration; @Autowired dev.tableflow.load.JobHistory history;
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

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"postgresql,mysql","mysql,postgresql","mariadb,postgresql","postgresql,mariadb","mysql,mariadb","mariadb,mysql","oracle,postgresql","postgresql,oracle"})
    void migratesBetweenRealServersWithDryRunReplayAndRollback(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            String sTable=q(from,"TF_MIG_SRC"), tTable=q(to,"TF_MIG_DST"), receipt=q(to,"tableflow_load_receipts");
            for(String n:List.of(sTable))dropQuiet(s,n);for(String n:List.of(tTable,receipt))dropQuiet(t,n);
            exec(s,"CREATE TABLE "+sTable+" ("+q(from,"EMP_NO")+" "+text(from)+"(20) PRIMARY KEY,"+q(from,"EMP_NM")+" "+text(from)+"(100) NOT NULL,"
                +q(from,"HIRE_TS")+" TIMESTAMP NOT NULL,"+q(from,"SALARY")+" "+dec(from)+"(20,2) NOT NULL)"+engine(from));
            String[] names={"홍길동 O'Brien\\path","이도윤","박서연"};
            for(int i=0;i<3;i++)exec(s,"INSERT INTO "+sTable+" VALUES ('00"+(i+1)+"','"+(family(from)?names[i].replace("\\","\\\\"):names[i]).replace("'","''")+"',"+
                (from==Dbms.oracle?"TIMESTAMP '2026-0"+(i+1)+"-15 00:00:00'":"'2026-0"+(i+1)+"-15 00:00:00'")+","+(i==0?"12345678901234567.12":"4200000.50")+")");
            exec(t,"CREATE TABLE "+tTable+" ("+q(to,"EMP_NO")+" "+text(to)+"(20) PRIMARY KEY,"+q(to,"EMP_NM")+" "+text(to)+"(100) NOT NULL,"
                +q(to,"HIRE_DT")+" DATE NOT NULL,"+q(to,"SALARY")+" "+dec(to)+"(20,2) NOT NULL)"+engine(to));
            exec(t,"CREATE TABLE "+receipt+" (request_id "+text(to)+"(36) PRIMARY KEY,payload_hash "+text(to)+"(64) NOT NULL,table_name "+text(to)+"(128) NOT NULL,inserted_rows INTEGER NOT NULL)"+engine(to));
            var map=List.of(new Mapping("EMP_NO","EMP_NO"),new Mapping("EMP_NM","EMP_NM"),new Mapping("HIRE_TS","HIRE_DT"),new Mapping("SALARY","SALARY"));

            // 1) Dry-run: 아무것도 쓰지 않는다.
            var plan=migration.check(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",map,100,false);
            assertEquals(3,plan.rows());assertEquals(3,plan.sourceRows());
            // Oracle DATE는 JDBC에서 TIMESTAMP로 보고되며 시간을 보존하므로 변환 자체가 필요 없다.
            if(to!=Dbms.oracle)assertTrue(plan.notes().stream().anyMatch(n->n.contains("일시 → 날짜")),plan.notes().toString());
            assertEquals(0,count(t,tTable));

            // 2) 원본이 한도보다 크면 일부만 이전하지 않고 거부
            assertThrows(IllegalArgumentException.class,()->migration.check(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",map,2,false));
            // 3) 잘못된 대상 컬럼 / 중복 매핑
            assertThrows(IllegalArgumentException.class,()->migration.check(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",
                List.of(new Mapping("EMP_NO","NOPE")),100,false));
            assertThrows(IllegalArgumentException.class,()->migration.check(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",
                List.of(new Mapping("EMP_NO","EMP_NO"),new Mapping("EMP_NM","EMP_NO")),100,false));

            // 4) 실제 이전 + 같은 요청 ID 재시도(replay)
            String id=UUID.randomUUID().toString();
            var done=migration.execute(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",map,100,false,id);
            assertEquals(3,done.insertedRows());assertFalse(done.replayed());
            assertTrue(migration.execute(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",map,100,false,id).replayed());
            assertEquals(3,count(t,tTable));
            try(var st=t.createStatement();var rs=st.executeQuery("SELECT "+q(to,"EMP_NM")+","+q(to,"SALARY")+","+q(to,"HIRE_DT")+" FROM "+tTable+" WHERE "+q(to,"EMP_NO")+"='001'")) {
                assertTrue(rs.next());assertEquals("홍길동 O'Brien\\path",rs.getString(1));
                assertEquals(0,new java.math.BigDecimal("12345678901234567.12").compareTo(rs.getBigDecimal(2)));
                assertEquals("2026-01-15",rs.getDate(3).toLocalDate().toString());
            }
            // 5) 같은 PK 재이전은 DB 제약으로 거부되고 대상은 그대로
            assertThrows(RuntimeException.class,()->migration.execute(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",map,100,false,UUID.randomUUID().toString()));
            assertEquals(3,count(t,tTable));

            // 6) 시간 정보가 있으면 DATE로 이전하지 않고 거부(데이터 손실 방지)
            exec(s,"UPDATE "+sTable+" SET "+q(from,"HIRE_TS")+"="+(from==Dbms.oracle?"TIMESTAMP '2026-02-15 09:30:00'":"'2026-02-15 09:30:00'")+" WHERE "+q(from,"EMP_NO")+"='002'");
            exec(t,"DELETE FROM "+tTable); // 연결은 auto-commit
            if(to!=Dbms.oracle) {
                var lost=assertThrows(IllegalArgumentException.class,()->migration.check(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",map,100,false));
                assertTrue(lost.getMessage().contains("시간"));
            }

            // 7) 중간 행이 대상 제약(길이)에 걸리면 전체 롤백
            exec(s,"UPDATE "+sTable+" SET "+q(from,"HIRE_TS")+"="+(from==Dbms.oracle?"TIMESTAMP '2026-02-15 00:00:00'":"'2026-02-15 00:00:00'")+" WHERE "+q(from,"EMP_NO")+"='002'");
            exec(t,"ALTER TABLE "+tTable+(to==Dbms.oracle?" MODIFY ("+q(to,"EMP_NM")+" "+text(to)+"(5))":(family(to)?" MODIFY "+q(to,"EMP_NM")+" VARCHAR(5) NOT NULL":" ALTER COLUMN "+q(to,"EMP_NM")+" TYPE VARCHAR(5)")));
            assertThrows(RuntimeException.class,()->migration.execute(src,schema(from),"TF_MIG_SRC",tgt,schema(to),"TF_MIG_DST",map,100,false,UUID.randomUUID().toString()));
            assertEquals(0,count(t,tTable));
            System.out.println("MIGRATION LIVE VERIFIED: "+from+" -> "+to);
            for(String n:List.of(sTable))dropQuiet(s,n);for(String n:List.of(tTable,receipt))dropQuiet(t,n);
        }
    }
    boolean tableExists(Connection c, Dbms db, String schema, String name) throws SQLException {
        boolean cat = family(db);
        try (var rs = c.getMetaData().getTables(cat ? schema : null, cat ? null : schema, name, new String[]{"TABLE"})) {
            while (rs.next()) if (name.equals(rs.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    @ParameterizedTest(name="create {0} -> {1}")
    @CsvSource({"postgresql,mysql","mysql,postgresql","mariadb,postgresql","postgresql,mariadb","oracle,postgresql","postgresql,oracle","oracle,mysql"})
    void createsMissingTargetTableAndReceiptsThenLoadsAndIsIdempotent(Dbms from, Dbms to) throws Exception {
        var enabled = Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS", "postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src = credentials(from); var tgt = credentials(to);
        try (var s = DriverManager.getConnection(explorer.url(src), explorer.properties(src)); var t = DriverManager.getConnection(explorer.url(tgt), explorer.properties(tgt))) {
            String sTable = q(from, "TF_NEW_SRC"), tTable = q(to, "TF_NEW_DST"), receipt = q(to, "tableflow_load_receipts");
            dropQuiet(s, sTable); dropQuiet(t, tTable); dropQuiet(t, receipt);
            exec(s, "CREATE TABLE " + sTable + " (" + q(from, "EMP_NO") + " " + text(from) + "(20) PRIMARY KEY," + q(from, "EMP_NM") + " " + text(from) + "(100) NOT NULL,"
                + q(from, "HIRE_TS") + " TIMESTAMP NOT NULL," + q(from, "SALARY") + " " + dec(from) + "(20,2) NOT NULL)" + engine(from));
            String[] names = {"홍길동 O'Brien\\path", "이도윤", "박서연"};
            for (int i = 0; i < 3; i++) exec(s, "INSERT INTO " + sTable + " VALUES ('00" + (i + 1) + "','" + (family(from) ? names[i].replace("\\", "\\\\") : names[i]).replace("'", "''") + "',"
                + (from == Dbms.oracle ? "TIMESTAMP '2026-0" + (i + 1) + "-15 09:30:00'" : "'2026-0" + (i + 1) + "-15 09:30:00'") + "," + (i == 0 ? "12345678901234567.12" : "4200000.50") + ")");
            var map = List.of(new Mapping("EMP_NO", "EMP_NO"), new Mapping("EMP_NM", "EMP_NM"), new Mapping("HIRE_TS", "HIRE_TS"), new Mapping("SALARY", "SALARY"));

            // 1) 생성 옵션이 없으면 거부, 있으면 Dry-run이 DDL만 보여주고 아무것도 만들지 않는다.
            assertThrows(IllegalArgumentException.class, () -> migration.check(src, schema(from), "TF_NEW_SRC", tgt, schema(to), "TF_NEW_DST", map, 100, false));
            var plan = migration.check(src, schema(from), "TF_NEW_SRC", tgt, schema(to), "TF_NEW_DST", map, 100, true);
            assertTrue(plan.createsTable()); assertEquals(2, plan.ddl().size(), plan.ddl().toString());
            assertTrue(plan.ddl().getFirst().startsWith("CREATE TABLE") && plan.ddl().get(1).contains("tableflow_load_receipts"));
            assertFalse(tableExists(t, to, schema(to), "TF_NEW_DST")); assertFalse(tableExists(t, to, schema(to), "tableflow_load_receipts"));

            // 2) 실제 생성 + 적재. 기본 키·자료형·값이 정확해야 한다.
            String id = UUID.randomUUID().toString();
            assertEquals(3, migration.execute(src, schema(from), "TF_NEW_SRC", tgt, schema(to), "TF_NEW_DST", map, 100, true, id).insertedRows());
            assertTrue(tableExists(t, to, schema(to), "TF_NEW_DST"));
            boolean cat = family(to); Set<String> pk = new HashSet<>();
            try (var rs = t.getMetaData().getPrimaryKeys(cat ? schema(to) : null, cat ? null : schema(to), "TF_NEW_DST")) { while (rs.next()) pk.add(rs.getString("COLUMN_NAME")); }
            assertEquals(Set.of("EMP_NO"), pk);
            assertEquals(3, count(t, tTable));
            try (var st = t.createStatement(); var rs = st.executeQuery("SELECT " + q(to, "EMP_NM") + "," + q(to, "SALARY") + "," + q(to, "HIRE_TS") + " FROM " + tTable + " WHERE " + q(to, "EMP_NO") + "='001'")) {
                assertTrue(rs.next()); assertEquals("홍길동 O'Brien\\path", rs.getString(1));
                assertEquals(0, new java.math.BigDecimal("12345678901234567.12").compareTo(rs.getBigDecimal(2)));
                assertEquals("2026-01-15T09:30", rs.getTimestamp(3).toLocalDateTime().toString().substring(0, 16)); // 시간 정보 보존
            }
            // 3) 같은 요청 재시도는 replay, 테이블이 이미 있으면 다시 만들지 않는다(DDL 없음).
            assertTrue(migration.execute(src, schema(from), "TF_NEW_SRC", tgt, schema(to), "TF_NEW_DST", map, 100, true, id).replayed());
            var again = migration.check(src, schema(from), "TF_NEW_SRC", tgt, schema(to), "TF_NEW_DST", map, 100, true);
            assertFalse(again.createsTable()); assertTrue(again.ddl().isEmpty());
            assertEquals(3, count(t, tTable));
            System.out.println("MIGRATION CREATE VERIFIED: " + from + " -> " + to);
            dropQuiet(s, sTable); dropQuiet(t, tTable); dropQuiet(t, receipt);
        }
    }

    @ParameterizedTest(name="fk/identity {0} -> {1}")
    @CsvSource({"postgresql,mysql","mysql,postgresql","postgresql,mariadb","postgresql,oracle"})
    void warnsAboutForeignKeysAndRejectsAutoIncrementTargets(Dbms from, Dbms to) throws Exception {
        var enabled = Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS", "postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src = credentials(from); var tgt = credentials(to);
        try (var s = DriverManager.getConnection(explorer.url(src), explorer.properties(src)); var t = DriverManager.getConnection(explorer.url(tgt), explorer.properties(tgt))) {
            String sTable = q(from, "TF_FK_SRC"), parent = q(to, "TF_FK_PARENT"), child = q(to, "TF_FK_DST"), receipt = q(to, "tableflow_load_receipts");
            dropQuiet(s, sTable); dropQuiet(t, child); dropQuiet(t, parent); dropQuiet(t, receipt);
            exec(s, "CREATE TABLE " + sTable + " (" + q(from, "ID") + " INTEGER PRIMARY KEY," + q(from, "NAME") + " " + text(from) + "(50))" + engine(from));
            exec(s, "INSERT INTO " + sTable + " VALUES (1,'a')"); exec(s, "INSERT INTO " + sTable + " VALUES (2,'b')");
            String identity = switch (to) { case postgresql -> "INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"; case oracle -> "NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
                default -> "INT AUTO_INCREMENT PRIMARY KEY"; };
            exec(t, "CREATE TABLE " + parent + " (" + q(to, "ID") + " INTEGER PRIMARY KEY)" + engine(to));
            exec(t, "CREATE TABLE " + child + " (" + q(to, "ID") + " " + identity + "," + q(to, "NAME") + " " + text(to) + "(50)," + q(to, "PARENT_ID") + " INTEGER,FOREIGN KEY (" + q(to, "PARENT_ID") + ") REFERENCES " + parent + "(" + q(to, "ID") + "))" + engine(to));
            exec(t, "CREATE TABLE " + receipt + " (request_id " + text(to) + "(36) PRIMARY KEY,payload_hash " + text(to) + "(64) NOT NULL,table_name " + text(to) + "(128) NOT NULL,inserted_rows INTEGER NOT NULL)" + engine(to));

            var plan = migration.check(src, schema(from), "TF_FK_SRC", tgt, schema(to), "TF_FK_DST", List.of(new Mapping("NAME", "NAME")), 100, false);
            assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("대상 외래 키") && w.contains("TF_FK_PARENT")), plan.warnings().toString());
            assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("자동 증가 컬럼 ID")), plan.warnings().toString());
            var rejected = assertThrows(IllegalArgumentException.class, () -> migration.check(src, schema(from), "TF_FK_SRC", tgt, schema(to), "TF_FK_DST",
                List.of(new Mapping("ID", "ID"), new Mapping("NAME", "NAME")), 100, false));
            assertTrue(rejected.getMessage().contains("자동 증가"), rejected.getMessage());
            // 자동 증가 컬럼을 매핑에서 빼면 DB가 번호를 부여해 적재된다(원본 키 값은 보존되지 않는다).
            assertEquals(2, migration.execute(src, schema(from), "TF_FK_SRC", tgt, schema(to), "TF_FK_DST", List.of(new Mapping("NAME", "NAME")), 100, false, UUID.randomUUID().toString()).insertedRows());
            assertEquals(2, count(t, child));
            System.out.println("MIGRATION FK/IDENTITY VERIFIED: " + from + " -> " + to);
            dropQuiet(s, sTable); dropQuiet(t, child); dropQuiet(t, parent); dropQuiet(t, receipt);
        }
    }

    long count(Connection c,String table)throws SQLException{
        try(var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM "+table)){rs.next();return rs.getLong(1);}
    }
}

