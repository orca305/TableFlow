package dev.tableflow.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 기존 전용 컨테이너에서 반입 드라이버 경로만 조회 검증한다. 운영 DB를 사용하지 않는다. */
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC", matches="true")
class DriverCatalogIntegrationTests {
    @org.junit.jupiter.api.AutoClose final dev.tableflow.state.StateDbTestSupport state = new dev.tableflow.state.StateDbTestSupport();
    @TempDir Path directory;
    @ParameterizedTest @EnumSource(value=Dbms.class, names={"oracle","postgresql","mysql","mariadb"})
    void connectsWithRegisteredJar(Dbms db) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS", "postgresql,mysql,mariadb").split(",")).contains(db.name()));
        var catalog = new DriverCatalog(directory.toString(), state.dataSource());
        try {
            Path jar = Path.of(Class.forName(db.driver).getProtectionDomain().getCodeSource().getLocation().toURI());
            DriverCatalog.Entry entry;
            try (var input = Files.newInputStream(jar)) { entry = catalog.register(db, "fixture", input); }
            var explorer = new JdbcExplorer(true, "localhost"); explorer.setDriverCatalog(catalog);
            int port = switch(db) { case oracle -> 11521; case postgresql -> 15432; case mysql -> 13316; case mariadb -> 13317; default -> throw new IllegalArgumentException("전용 테스트 DB가 없습니다."); };
            var credentials = new Credentials(db, "localhost", port, db == Dbms.oracle ? "FREEPDB1" : "tableflow", "tableflow", "TableFlowTest2026", false, null, entry.id());
            int result = explorer.withConnection(credentials, true, connection -> {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    try (var rows = statement.executeQuery(db == Dbms.oracle ? "SELECT 1 FROM DUAL" : "SELECT 1")) { assertTrue(rows.next()); return rows.getInt(1); }
                }
            });
            assertEquals(1, result);
        } finally { catalog.close(); }
    }
}
