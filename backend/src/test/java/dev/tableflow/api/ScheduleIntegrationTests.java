package dev.tableflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.ConnectionStore;
import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.ScheduleStore.Schedule;
import dev.tableflow.load.MigrationService.Mapping;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 반복 실행: 등록 규칙, 시각이 되면 실행, 전체 교체(실행 전 대상 비움), 일괄 이관 스케줄을 실서버(MySQL→MariaDB)에서 확인한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true",
    "tableflow.connections.key-file=build/tmp/sched-test/connections.key",
    "tableflow.schedules.file=build/tmp/sched-test/schedules.json","tableflow.jobs.file=build/tmp/sched-test/jobs.json"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class ScheduleIntegrationTests {
    /** 저장 연결이 다른 시험의 암호 키와 섞이지 않도록 일회용 상태 스키마를 쓴다. */
    static dev.tableflow.state.StateDbTestSupport state;
    @org.springframework.test.context.DynamicPropertySource static void isolatedState(org.springframework.test.context.DynamicPropertyRegistry registry) {
        state = new dev.tableflow.state.StateDbTestSupport(); registry.add("tableflow.state-db.schema", state::schema);
    }
    @AfterAll static void dropState() throws Exception { if (state != null) state.close(); }
    @Autowired ScheduleService schedules; @Autowired ConnectionStore connections; @Autowired JdbcExplorer explorer; @Autowired ObjectMapper json;

    Credentials plain(Dbms db) {
        int port=db==Dbms.mysql?13316:13317;
        return new Credentials(db,"localhost",port,"tableflow","tableflow","TableFlowTest2026",false);
    }
    Credentials saved(Dbms db,String name,boolean savePassword) {
        var c=plain(db); var s=connections.save(name,c,savePassword);
        return new Credentials(db,c.host(),c.port(),c.database(),c.username(),"",false,s.id());
    }
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void dropQuiet(Connection c,String name){try(var st=c.createStatement()){st.execute("DROP TABLE "+name);}catch(SQLException ignored){}}
    long count(Connection c,String table)throws SQLException{try(var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM `"+table+"`")){rs.next();return rs.getLong(1);}}
    Schedule await(String id,String state)throws Exception{
        long deadline=System.currentTimeMillis()+60_000;
        while(System.currentTimeMillis()<deadline){
            schedules.tick();
            var s=schedules.list().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();
            if(state.equals(s.lastState())||(!"running".equals(s.lastState())&&s.lastState()!=null))return s;
            Thread.sleep(300);
        }
        fail("스케줄 결과를 기다리다 시간이 지났습니다"); return null;
    }

    @Test void runsOnScheduleAndReplacesTargetRows()throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains("mysql")&&enabled.contains("mariadb"));
        for(var old:schedules.list()) schedules.delete(old.id());
        var src=saved(Dbms.mysql,"sched-src",true); var tgt=saved(Dbms.mariadb,"sched-dst",true);
        var unsaved=saved(Dbms.mariadb,"sched-nopw",false);
        try(var s=DriverManager.getConnection("jdbc:mysql://localhost:13316/tableflow","tableflow","TableFlowTest2026");var t=DriverManager.getConnection("jdbc:mariadb://localhost:13317/tableflow","tableflow","TableFlowTest2026")) {
            for(String n:List.of("TF_S_DST","tableflow_load_receipts")) dropQuiet(t,"`"+n+"`");
            dropQuiet(s,"`TF_S_SRC`");
            exec(s,"CREATE TABLE `TF_S_SRC` (`ID` INTEGER PRIMARY KEY,`V` VARCHAR(20)) ENGINE=InnoDB");
            for(int i=1;i<=3;i++) exec(s,"INSERT INTO `TF_S_SRC` VALUES ("+i+",'v"+i+"')");
            var request=new ConnectionController.MigrateRequest(src,"tableflow","TF_S_SRC",tgt,"tableflow","TF_S_DST",List.of(new Mapping("ID","ID"),new Mapping("V","V")),3,true,false,null,false,null,false,false,false,null);

            // 등록 규칙
            var base=new ScheduleService.CreateRequest("매시간 복제","single",json.valueToTree(request),"interval",5,null,"replace",true);
            assertThrows(IllegalArgumentException.class,()->schedules.create(new ScheduleService.CreateRequest("x","single",json.valueToTree(request),"interval",5,null,"replace",false)),"전체 교체는 동의가 필요");
            assertThrows(IllegalArgumentException.class,()->schedules.create(new ScheduleService.CreateRequest("x","single",json.valueToTree(request),"interval",1,null,"append",false)),"너무 짧은 간격");
            assertThrows(IllegalArgumentException.class,()->schedules.create(new ScheduleService.CreateRequest("x","single",json.valueToTree(request),"daily",null,"25:99","append",false)));
            var badConn=new ConnectionController.MigrateRequest(src,"tableflow","TF_S_SRC",unsaved,"tableflow","TF_S_DST",request.mappings(),3,true,false,null,false,null,false,false,false,null);
            assertThrows(IllegalArgumentException.class,()->schedules.create(new ScheduleService.CreateRequest("x","single",json.valueToTree(badConn),"interval",5,null,"append",false)),"비밀번호를 저장하지 않은 연결");
            var created=schedules.create(base);
            assertTrue(created.enabled()); assertNotNull(created.nextRunAt());
            assertFalse(created.request().toString().contains("TableFlowTest2026"),"정의에 비밀번호가 없어야 한다");

            // 시각이 되기 전에는 실행하지 않는다
            schedules.tick(); assertNull(schedules.list().get(0).lastRunAt());
            // 시각이 지나면 실행한다
            schedules.useClock(Clock.fixed(Instant.parse(created.nextRunAt()).plusSeconds(5),ZoneId.systemDefault()));
            schedules.tick();
            var done=await(created.id(),"succeeded");
            assertEquals("succeeded",done.lastState(),done.lastMessage());
            assertEquals(3,count(t,"TF_S_DST"));
            assertTrue(Instant.parse(done.nextRunAt()).isAfter(Instant.parse(created.nextRunAt())));

            // 원본이 늘어도(이관 행 수 상한에 막히지 않고) 전체 교체: 지운 뒤 다시 적재하므로 중복 없이 5행
            exec(s,"INSERT INTO `TF_S_SRC` VALUES (4,'v4'),(5,'v5')");
            schedules.runNow(created.id());
            assertEquals("succeeded",await(created.id(),"succeeded").lastState());
            assertEquals(5,count(t,"TF_S_DST"));

            // 중지하면 시각이 지나도 실행하지 않는다
            schedules.setEnabled(created.id(),false);
            String lastRun=schedules.list().get(0).lastRunAt();
            schedules.useClock(Clock.fixed(Instant.now().plus(Duration.ofDays(2)),ZoneId.systemDefault()));
            schedules.tick(); assertEquals(lastRun,schedules.list().get(0).lastRunAt());
            assertTrue(schedules.delete(created.id())); assertTrue(schedules.list().isEmpty());

            // 일괄 이관 스케줄(전체 교체)
            schedules.useClock(Clock.systemDefaultZone());
            var batchRequest=new ConnectionController.BatchRequest(src,"tableflow",tgt,"tableflow",List.of("TF_S_SRC"),false,false,false,false,"skip",null,null,false);
            var batch=schedules.create(new ScheduleService.CreateRequest("스키마 복제","batch",json.valueToTree(batchRequest),"daily",null,"03:30","replace",true));
            assertEquals("03:30",batch.dailyAt());
            assertTrue(Instant.parse(batch.nextRunAt()).isAfter(Instant.now()));
            schedules.runNow(batch.id()); assertEquals("succeeded",await(batch.id(),"succeeded").lastState());
            assertEquals(5,count(t,"TF_S_SRC"));
            schedules.runNow(batch.id()); assertEquals("succeeded",await(batch.id(),"succeeded").lastState());
            assertEquals(5,count(t,"TF_S_SRC"),"두 번째 실행도 중복 없이 5행");
            schedules.delete(batch.id());

            for(String n:List.of("TF_S_DST","TF_S_SRC","tableflow_load_receipts")) dropQuiet(t,"`"+n+"`");
            dropQuiet(s,"`TF_S_SRC`");
        } finally { for(var c:connections.list()) if(c.name().startsWith("sched-")) connections.delete(c.id()); }
    }

    @Test void nextRunCalculation() {
        var daily=new Schedule("i","n","single",null,"daily",null,"03:30","append",true,null,null,null,null,null,null,"","");
        ZoneId zone=ZoneId.systemDefault();
        Instant before=LocalDate.of(2026,11,21).atTime(1,0).atZone(zone).toInstant(), after=LocalDate.of(2026,11,21).atTime(4,0).atZone(zone).toInstant();
        assertEquals(LocalDate.of(2026,11,21).atTime(3,30).atZone(zone).toInstant(),ScheduleService.next(daily,before));
        assertEquals(LocalDate.of(2026,11,22).atTime(3,30).atZone(zone).toInstant(),ScheduleService.next(daily,after));
        var every=new Schedule("i","n","single",null,"interval",15,null,"append",true,null,null,null,null,null,null,"","");
        assertEquals(before.plusSeconds(900),ScheduleService.next(every,before));
    }
}
