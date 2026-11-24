package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.Dbms;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlDialectTests {
    @Test void identifiersEscapeEmbeddedDelimiters() {
        assertEquals("`a``b`", SqlDialect.quote(Dbms.mysql, "a`b"));
        assertEquals("\"a\"\"b\"", SqlDialect.quote(Dbms.postgresql, "a\"b"));
        for (Dbms db : Dbms.values()) {
            assertEquals(SqlDialect.quote(db, "name"), dev.tableflow.load.TableDdl.quote(db, "name"));
            assertEquals(SqlDialect.quote(db, "name"), JdbcReader.quote(db, "name"));
        }
    }
    @Test void paginationIsExplicitForEverySupportedDatabase() {
        assertEquals(" OFFSET 500 ROWS FETCH NEXT 100 ROWS ONLY", SqlDialect.page(Dbms.oracle, 100, 500));
        assertEquals(" FETCH FIRST 100 ROWS ONLY", SqlDialect.first(Dbms.oracle, 100));
        for (Dbms db : new Dbms[]{Dbms.mysql, Dbms.mariadb, Dbms.postgresql}) {
            assertEquals(" LIMIT 100 OFFSET 500", SqlDialect.page(db, 100, 500));
            assertEquals(" LIMIT 100", SqlDialect.first(db, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> SqlDialect.page(Dbms.oracle, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> SqlDialect.first(Dbms.mysql, 0));
    }
}
