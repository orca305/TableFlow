package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.load.MigrationService;
import dev.tableflow.load.MigrationService.Extras;
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

/** 신규 테이블 생성 시 인덱스·기본값·외래 키 복제와 부모 테이블 이관 순서 안내를 실서버에서 검증한다. 일회용 테스트 컨테이너 전용입니다. */
@SpringBootTest(properties={"tableflow.jdbc.enabled=true","tableflow.jdbc.load-enabled=true"})
@EnabledIfEnvironmentVariable(named="TABLEFLOW_TEST_JDBC",matches="true")
class MigrationExtrasIntegrationTests {
    @Autowired JdbcExplorer explorer; @Autowired MigrationService migration;
    Credentials credentials(Dbms db) {
        int port=switch(db){case postgresql->15432;case mysql->13316;case mariadb->13317;case oracle->11521;};
        return new Credentials(db,"localhost",port,db==Dbms.oracle?"FREEPDB1":"tableflow","tableflow","TableFlowTest2026",false);
    }
    String schema(Dbms db){return db==Dbms.postgresql?"public":db==Dbms.oracle?"TABLEFLOW":"tableflow";}
    String q(Dbms db,String id){String c=(db==Dbms.mysql||db==Dbms.mariadb)?"`":"\"";return c+id+c;}
    boolean family(Dbms db){return db==Dbms.mysql||db==Dbms.mariadb;}
    void exec(Connection c,String sql){try(var st=c.createStatement()){st.execute(sql);}catch(SQLException e){throw new RuntimeException(sql,e);}}
    void dropQuiet(Connection c,String name){try(var st=c.createStatement()){st.execute("DROP TABLE "+name);}catch(SQLException ignored){}}
    String text(Dbms db){return db==Dbms.oracle?"VARCHAR2":"VARCHAR";}
    String engine(Dbms db){return family(db)?" ENGINE=InnoDB":"";}

    @ParameterizedTest(name="{0} -> {1}")
    @CsvSource({"oracle,postgresql","postgresql,oracle","mysql,mariadb","mariadb,postgresql"})
    void copiesIndexesDefaultsAndForeignKeysAndGuidesParentOrder(Dbms from,Dbms to)throws Exception {
        var enabled=Arrays.asList(System.getenv().getOrDefault("TABLEFLOW_TEST_JDBC_DBMS","postgresql,mysql,mariadb").split(","));
        Assumptions.assumeTrue(enabled.contains(from.name()) && enabled.contains(to.name()));
        var src=credentials(from); var tgt=credentials(to); String ss=schema(from), ts=schema(to);
        try(var s=DriverManager.getConnection(explorer.url(src),explorer.properties(src));var t=DriverManager.getConnection(explorer.url(tgt),explorer.properties(tgt))) {
            for(String n:List.of("TF_X_C","TF_X_P"))dropQuiet(s,q(from,n));
            for(String n:List.of("TF_X_C","TF_X_P","tableflow_load_receipts"))dropQuiet(t,q(to,n));
            exec(s,"CREATE TABLE "+q(from,"TF_X_P")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"NAME")+" "+text(from)+"(20))"+engine(from));
            exec(s,"CREATE TABLE "+q(from,"TF_X_C")+" ("+q(from,"ID")+" INTEGER PRIMARY KEY,"+q(from,"P_ID")+" INTEGER NOT NULL,"+q(from,"CODE")+" "+text(from)+"(10) DEFAULT 'N',"
                +q(from,"QTY")+" INTEGER DEFAULT 5, FOREIGN KEY ("+q(from,"P_ID")+") REFERENCES "+q(from,"TF_X_P")+" ("+q(from,"ID")+"))"+engine(from));
            exec(s,"CREATE INDEX "+q(from,"IX_X_C_CODE")+" ON "+q(from,"TF_X_C")+" ("+q(from,"CODE")+")");
            exec(s,"CREATE UNIQUE INDEX "+q(from,"UX_X_C_PC")+" ON "+q(from,"TF_X_C")+" ("+q(from,"P_ID")+","+q(from,"CODE")+")");
            exec(s,"INSERT INTO "+q(from,"TF_X_P")+" VALUES (1,'부모A')"); exec(s,"INSERT INTO "+q(from,"TF_X_P")+" VALUES (2,'부모B')");
            exec(s,"INSERT INTO "+q(from,"TF_X_C")+" ("+q(from,"ID")+","+q(from,"P_ID")+","+q(from,"CODE")+","+q(from,"QTY")+") VALUES (10,1,'a',1)"); exec(s,"INSERT INTO "+q(from,"TF_X_C")+" ("+q(from,"ID")+","+q(from,"P_ID")+","+q(from,"CODE")+","+q(from,"QTY")+") VALUES (11,2,'b',2)");
            var parentMap=List.of(new Mapping("ID","ID"),new Mapping("NAME","NAME"));
            var childMap=List.of(new Mapping("ID","ID"),new Mapping("P_ID","P_ID"),new Mapping("CODE","CODE"),new Mapping("QTY","QTY"));
            var all=new Extras(true,true,true);

            // 1) 부모가 대상에 없으면 FK는 복제하지 않고 먼저 이관할 테이블을 안내한다.
            var first=migration.analyze(src,ss,"TF_X_C",tgt,ts,"TF_X_C",childMap,true,false,all);
            assertEquals(1,first.foreignKeys().size());
            var fk0=first.foreignKeys().get(0);
            assertFalse(fk0.parentInTarget()); assertFalse(fk0.copied());
            assertTrue(fk0.message().contains("먼저") && fk0.message().toUpperCase().contains("TF_X_P"),fk0.message());
            assertTrue(first.warnings().stream().anyMatch(w->w.contains("TF_X_P")),first.warnings().toString());
            assertTrue(first.ddl().stream().noneMatch(d->d.contains("FOREIGN KEY")));

            // 2) 부모를 먼저 이관(신규 생성)하면 FK를 복제한다고 안내한다.
            assertEquals(2,migration.execute(src,ss,"TF_X_P",tgt,ts,"TF_X_P",parentMap,100,true,false,UUID.randomUUID().toString()).insertedRows());
            var second=migration.analyze(src,ss,"TF_X_C",tgt,ts,"TF_X_C",childMap,true,false,all);
            var fk1=second.foreignKeys().get(0);
            assertTrue(fk1.parentInTarget()&&fk1.copied(),fk1.message());
            assertTrue(second.ddl().stream().anyMatch(d->d.contains("FOREIGN KEY")),second.ddl().toString());
            assertTrue(second.ddl().stream().filter(d->d.contains("INDEX")).count()>=2,second.ddl().toString());
            assertTrue(second.ddl().get(0).contains("DEFAULT 'N'") && second.ddl().get(0).contains("DEFAULT 5"),second.ddl().get(0)); // 컬럼 기본값(MySQL의 DEFAULT CHARSET과 구분)
            // 옵션을 끄면 아무것도 복제하지 않는다
            var plain=migration.analyze(src,ss,"TF_X_C",tgt,ts,"TF_X_C",childMap,true,false,Extras.NONE);
            assertTrue(plain.ddl().stream().noneMatch(d->d.contains("FOREIGN KEY")||d.contains("INDEX")||d.contains("DEFAULT 'N'")||d.contains("DEFAULT 5")),plain.ddl().toString());

            // 3) 실제 이관 후 대상에 인덱스·기본값·FK가 생겨 있다.
            assertEquals(2,migration.execute(src,ss,"TF_X_C",tgt,ts,"TF_X_C",childMap,100,true,false,UUID.randomUUID().toString(),all).insertedRows());
            var extras=explorer.extras(tgt,ts,"TF_X_C");
            assertTrue(extras.indexes().size()>=2,extras.indexes().toString());
            assertTrue(extras.indexes().stream().anyMatch(i->i.unique()&&i.columns().size()==2),extras.indexes().toString());
            assertEquals(1,extras.foreignKeys().stream().filter(f->f.parentTable().equalsIgnoreCase("TF_X_P")).count(),extras.foreignKeys().toString());
            assertTrue(extras.defaults().keySet().stream().anyMatch(k->k.equalsIgnoreCase("CODE")),extras.defaults().toString());
            assertTrue(extras.defaults().keySet().stream().anyMatch(k->k.equalsIgnoreCase("QTY")),extras.defaults().toString());
            var targetCols=explorer.columns(tgt,ts,"TF_X_C").items(); // 컬럼 정보의 PK/FK 표시
            assertTrue(targetCols.stream().anyMatch(c->c.name().equalsIgnoreCase("P_ID")&&c.foreignKey()),targetCols.toString());
            assertTrue(targetCols.stream().anyMatch(c->c.name().equalsIgnoreCase("ID")&&c.primaryKey()&&!c.foreignKey()),targetCols.toString());
            for(String n:List.of("TF_X_C","TF_X_P"))dropQuiet(s,q(from,n));
            for(String n:List.of("TF_X_C","TF_X_P","tableflow_load_receipts"))dropQuiet(t,q(to,n));
        }
    }
}
