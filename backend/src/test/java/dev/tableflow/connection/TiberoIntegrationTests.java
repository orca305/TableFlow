package dev.tableflow.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Mapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 사용자가 정식 라이선스/드라이버로 준비한 전용 루프백 fixture에서만 실행한다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true","tableflow.jdbc.tibero-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_TIBERO",matches="true")
class TiberoIntegrationTests {
    @Autowired JdbcExplorer explorer;
    @Autowired MigrationService migration;
    @TempDir Path directory;
    @Test void migratesWithRegisteredVendorDriverAndReplays() throws Exception {
        String jar=System.getenv("TABLEFLOW_TEST_TIBERO_DRIVER");
        assertNotNull(jar,"정식 Tibero JDBC JAR 경로를 지정해주세요.");
        var catalog=new DriverCatalog(directory.toString(),new ObjectMapper());
        try {
            DriverCatalog.Entry driver;
            try(var input=Files.newInputStream(Path.of(jar))){driver=catalog.register(Dbms.tibero,"fixture",input);}
            explorer.setDriverCatalog(catalog);
            var c=new Credentials(Dbms.tibero,"localhost",18629,"tibero","TABLEFLOW","TableFlowTest2026",false,null,driver.id());
            explorer.withConnection(c,false,connection->{
                try(var st=connection.createStatement()){
                    st.setQueryTimeout(30);
                    for(String table:List.of("TF_TI_SRC","TF_TI_DST")) {
                        try(var tables=connection.getMetaData().getTables(null,"TABLEFLOW",JdbcExplorer.literalPattern(connection.getMetaData(),table),new String[]{"TABLE"})) {
                            if(tables.next()) st.execute("DROP TABLE TABLEFLOW."+table);
                        }
                    }
                    st.execute("CREATE TABLE TABLEFLOW.TF_TI_SRC(ID NUMBER(19) PRIMARY KEY,NAME VARCHAR2(100 CHAR))");
                    st.execute("INSERT INTO TABLEFLOW.TF_TI_SRC VALUES(1,'한글')");
                }return null;
            });
            var map=List.of(new Mapping("ID","ID"),new Mapping("NAME","NAME"));String id=UUID.randomUUID().toString();
            assertEquals(1,migration.execute(c,"TABLEFLOW","TF_TI_SRC",c,"TABLEFLOW","TF_TI_DST",map,100,true,id).insertedRows());
            assertTrue(migration.execute(c,"TABLEFLOW","TF_TI_SRC",c,"TABLEFLOW","TF_TI_DST",map,100,true,id).replayed());
        }finally{catalog.close();}
    }
}
