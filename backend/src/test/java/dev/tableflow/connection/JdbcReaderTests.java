package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.Dbms;
import dev.tableflow.model.Table;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** H2 인메모리 DB로 실제 JDBC ResultSet/메타데이터 경로를 검증한다. Oracle/PostgreSQL/MySQL/MariaDB 실서버 검증이 아니다. */
class JdbcReaderTests {
    final JdbcReader reader = new JdbcReader(null);
    Connection connection;

    @BeforeEach void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:reader" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var st = connection.createStatement()) {
            st.execute("CREATE TABLE \"EMP\" (\"EMP_NO\" VARCHAR(20) PRIMARY KEY, \"이름\" VARCHAR(50), \"HIRE_DT\" DATE, \"UPD_TS\" TIMESTAMP, \"SALARY\" DECIMAL(20,2), \"AGE\" INTEGER, \"ACTIVE\" BOOLEAN, \"PHOTO\" BLOB)");
            st.execute("INSERT INTO \"EMP\" VALUES ('001','홍길동 O''Brien\\path','2026-01-15','2026-01-15 09:30:00',12345678901234567.12,30,TRUE,NULL)");
            st.execute("INSERT INTO \"EMP\" VALUES ('002',NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
            st.execute("INSERT INTO \"EMP\" VALUES ('003','C','2026-03-02','2026-03-02 00:00:00',4200000,41,FALSE,NULL)");
        }
    }
    @AfterEach void tearDown() throws Exception { connection.close(); }

    @Test void readsSelectedColumnsWithTypesNullsAndKorean() throws Exception {
        Table t = reader.extract(connection, Dbms.postgresql, "PUBLIC", "EMP", List.of("EMP_NO", "이름", "HIRE_DT", "UPD_TS", "SALARY", "AGE", "ACTIVE"), 100);
        assertEquals(3, t.rows().size());
        assertEquals(List.of("EMP_NO", "이름", "HIRE_DT", "UPD_TS", "SALARY", "AGE", "ACTIVE"), t.columns().stream().map(Table.Column::name).toList());
        var first = t.rows().getFirst().cells();
        assertEquals("001", first.get("col_1").value());
        assertEquals("홍길동 O'Brien\\path", first.get("col_2").value());
        assertEquals("2026-01-15", first.get("col_3").value());
        assertEquals("2026-01-15T09:30:00", first.get("col_4").value());
        assertEquals("12345678901234567.12", first.get("col_5").value());
        assertEquals("30", first.get("col_6").value());
        assertEquals("true", first.get("col_7").value());
        var nulls = t.rows().get(1).cells();
        assertNull(nulls.get("col_2").value()); assertNull(nulls.get("col_5").value()); assertNull(nulls.get("col_7").value());
        assertEquals(Table.DataType.date, t.columns().get(2).dataType());
        assertEquals(Table.DataType.datetime, t.columns().get(3).dataType());
        assertEquals(Table.DataType.decimal, t.columns().get(4).dataType());
        assertEquals(Table.DataType.integer, t.columns().get(5).dataType());
        assertEquals(Table.DataType.bool, t.columns().get(6).dataType());
        assertEquals("database", t.source().type());
        // 한글 컬럼명은 대상 컬럼명 규칙을 만족하도록 정규화되고 중복 없이 유지된다.
        assertTrue(t.columns().stream().allMatch(c -> dev.tableflow.transform.TableEngine.identifier(c.targetName())));
        assertEquals(t.columns().size(), t.columns().stream().map(c -> c.targetName().toLowerCase()).distinct().count());
    }

    @Test void limitCapsRowsAndBounds() throws Exception {
        assertEquals(2, reader.extract(connection, Dbms.postgresql, "PUBLIC", "EMP", List.of("EMP_NO"), 2).rows().size());
        assertThrows(IllegalArgumentException.class, () -> reader.extract(connection, Dbms.postgresql, "PUBLIC", "EMP", List.of("EMP_NO"), 0));
        assertThrows(IllegalArgumentException.class, () -> reader.extract(connection, Dbms.postgresql, "PUBLIC", "EMP", List.of("EMP_NO"), 10001));
    }

    @Test void rejectsUnknownColumnInjectionAndBinary() {
        assertThrows(IllegalArgumentException.class, () -> reader.extract(connection, Dbms.postgresql, "PUBLIC", "EMP", List.of("EMP_NO\" FROM \"EMP\"; DROP TABLE \"EMP\"; --"), 10));
        assertThrows(IllegalArgumentException.class, () -> reader.extract(connection, Dbms.postgresql, "PUBLIC", "EMP", List.of("PHOTO"), 10));
        assertThrows(JdbcExplorer.Failure.class, () -> reader.extract(connection, Dbms.postgresql, "PUBLIC", "NO_SUCH", null, 10));
        assertThrows(JdbcExplorer.Failure.class, () -> reader.extract(connection, Dbms.postgresql, "PUBLIC", "EMP\" --", null, 10));
    }

    @Test void keysetPagesMatchOffsetPagesForCompositeKey() throws Exception {
        try (var st = connection.createStatement()) {
            st.execute("CREATE TABLE \"ORD\" (\"A\" INTEGER, \"B\" VARCHAR(10), \"V\" INTEGER, PRIMARY KEY (\"A\",\"B\"))");
            for (int a = 1; a <= 4; a++) for (String b : List.of("x", "y", "z")) st.execute("INSERT INTO \"ORD\" VALUES (" + a + ",'" + b + "'," + (a * 10) + ")");
        }
        List<String> viaKeyset = new ArrayList<>();
        var cursor = new JdbcReader.Keyset(null);
        while (true) {
            Table page = reader.extract(connection, Dbms.postgresql, "PUBLIC", "ORD", List.of("A", "V"), 5, -1, cursor); // 키 컬럼 A가 선택 컬럼과 겹쳐도 동작(Oracle ORA-00960 회귀)
            if (page.rows().isEmpty()) break;
            page.rows().forEach(r -> viaKeyset.add(r.cells().get("col_2").value()));
            cursor = new JdbcReader.Keyset(cursor.last());
        }
        List<String> viaOffset = new ArrayList<>();
        for (long off = 0; off < 12; off += 5)
            reader.extract(connection, Dbms.postgresql, "PUBLIC", "ORD", List.of("A", "V"), 5, off).rows().forEach(r -> viaOffset.add(r.cells().get("col_2").value()));
        assertEquals(12, viaKeyset.size());
        assertEquals(viaOffset, viaKeyset);
        assertThrows(IllegalArgumentException.class, () -> reader.extract(connection, Dbms.postgresql, "PUBLIC", "ORD", List.of("A", "V"), 5, -1, new JdbcReader.Keyset(List.of(1))));
    }

    @Test void identifierNormalizationAndQuoting() {
        var used = new HashSet<String>();
        assertEquals("EMP_NO", JdbcReader.uniqueIdentifier("EMP_NO", used));
        assertEquals("emp_no_2", JdbcReader.uniqueIdentifier("emp_no", used));
        assertEquals("C_1st", JdbcReader.uniqueIdentifier("1st", used));
        assertEquals("`a``b`", JdbcReader.quote(Dbms.mysql, "a`b"));
        assertEquals("\"a\"\"b\"", JdbcReader.quote(Dbms.oracle, "a\"b"));
    }
}
