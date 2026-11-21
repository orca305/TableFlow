package dev.tableflow.connection;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RowFilterTests {
    @Test void blankMeansNoFilter() {
        assertNull(RowFilter.clean(null)); assertNull(RowFilter.clean("   "));
    }
    @Test void acceptsPlainConditions() {
        assertEquals("STATUS = 'A' AND AMT > 100", RowFilter.clean("  STATUS = 'A' AND AMT > 100 "));
        assertDoesNotThrow(() -> RowFilter.clean("(NAME LIKE 'K%' OR ID IN (1,2,3)) AND TS BETWEEN '2026-01-01' AND '2026-12-31' AND X IS NOT NULL"));
        // 따옴표 안의 값은 금지어·세미콜론이어도 허용한다.
        assertDoesNotThrow(() -> RowFilter.clean("\"Kind\" = 'B' AND `x` > 1"));
        assertDoesNotThrow(() -> RowFilter.clean("NAME = 'DROP; --/* x */ it''s'"));
    }
    @Test void rejectsEverythingElse() {
        for (String bad : new String[]{"WHERE A = 1", "A = 1; DROP TABLE T", "A = 1 -- x", "A = 1 /* x */", "A = 1 UNION SELECT 1", "A = (SELECT 1) INTO X",
            "A = 'x", "(A = 1", "A = 1)", "A = 1 OR pg_sleep(10) IS NULL", "\"A = 1", "A = 1 # x", "A = \\1", "A = 1\u0000", "A = " + "1".repeat(1001)})
            assertThrows(IllegalArgumentException.class, () -> RowFilter.clean(bad), bad);
    }
}
