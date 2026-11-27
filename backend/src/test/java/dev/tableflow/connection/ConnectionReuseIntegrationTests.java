package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 사전 점검·검증에서 쓰는 연결·메타데이터 재사용의 범위를 실서버에서 확인한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class ConnectionReuseIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired JdbcReader reader;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;default->throw new IllegalArgumentException("전용 테스트 DB가 없습니다.");};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}

    @ParameterizedTest(name="{0}")
    @CsvSource({"oracle","postgresql","mysql","mariadb"})
    void reusesReadOnlyConnectionsOnlyInsideTheScope(Dbms db) {
        Assumptions.assumeTrue(Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(",")).contains(db.name()));
        var cred=credentials(db); String schema=schema(db);
        List<Connection> seen=new ArrayList<>();
        explorer.reusing(() -> {
            Connection first=explorer.withConnection(cred,true,c->c);
            Connection second=explorer.withConnection(cred,true,c->c);
            assertSame(first,second,"같은 접속 정보의 읽기 연결은 재사용");
            Connection writer=explorer.withConnection(cred,false,c->c);
            assertNotSame(first,writer,"쓰기 연결은 재사용하지 않음"); seen.add(writer);
            assertSame(first,explorer.reusing(() -> explorer.withConnection(cred,true,c->c)),"중첩 호출은 바깥 범위를 그대로 씀");
            assertSame(explorer.tables(cred,schema),explorer.tables(cred,schema),"같은 메타데이터 조회는 한 번만");
            // 실패한 조회의 연결은 버리고, 다음 조회는 새 연결에서 정상 동작한다
            assertThrows(RuntimeException.class,() -> reader.count(cred,schema,"TF_NO_SUCH_TABLE"));
            Connection third=explorer.withConnection(cred,true,c->c);
            assertNotSame(first,third); seen.add(first); seen.add(third);
            assertDoesNotThrow(() -> explorer.tables(cred,schema));
            return null;
        });
        for(Connection c:seen) assertTrue(assertDoesNotThrow(c::isClosed),"범위가 끝나면 모두 닫힘");
        assertNotSame(explorer.tables(cred,schema),explorer.tables(cred,schema),"범위 밖에서는 캐시하지 않음");
        Connection a=explorer.withConnection(cred,true,c->c), b=explorer.withConnection(cred,true,c->c);
        assertNotSame(a,b);
        // 범위 안에서 예외가 나가도 연결을 닫고 다음 호출은 재사용하지 않는다
        List<Connection> leaked=new ArrayList<>();
        assertThrows(IllegalStateException.class,() -> explorer.reusing(() -> { leaked.add(explorer.withConnection(cred,true,c->c)); throw new IllegalStateException("중단"); }));
        assertTrue(assertDoesNotThrow(leaked.get(0)::isClosed));
        assertNotSame(leaked.get(0),explorer.withConnection(cred,true,c->c));
    }
}
