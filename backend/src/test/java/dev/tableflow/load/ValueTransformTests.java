package dev.tableflow.load;

import dev.tableflow.load.ValueTransform.Step;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValueTransformTests {
    static Step s(String op) { return new Step(op, null, null, null, null, null, null, null, null, null, null); }
    static Step replace(String find, String with) { return new Step("replace", find, with, null, null, null, null, null, null, null, null); }
    static Step expr(String op, String e, Integer scale) { return new Step(op, null, null, null, null, null, null, null, null, e, scale); }
    static String run(String value, Map<String, String> row, Step... steps) { return ValueTransform.apply(List.of(steps), value, row, "C"); }

    @Test void textSteps() {
        assertEquals("AB12", run("  ab-12 ", Map.of(), s("trim"), replace("-", ""), s("upper")));
        assertEquals("ab", run("AB", Map.of(), s("lower")));
        assertNull(run(null, Map.of(), s("trim"), s("upper")));
        assertEquals("미정", run("  ", Map.of(), new Step("nullTo", null, null, "미정", null, null, null, null, null, null, null)));
        assertEquals("1", run("Y", Map.of(), new Step("map", null, null, null, Map.of("Y", "1", "N", "0"), null, null, null, null, null, null)));
        assertEquals("X", run("X", Map.of(), new Step("map", null, null, null, Map.of("Y", "1"), null, null, null, null, null, null))); // 치환표에 없으면 그대로
    }
    @Test void splitAndSubstr() {
        var split = new Step("split", null, null, null, null, "-", 2, null, null, null, null);
        assertEquals("1234", run("010-1234-5678", Map.of(), split));
        assertNull(run("010", Map.of(), split)); // 없는 조각은 비움
        assertEquals("1234", run("ab1234cd", Map.of(), new Step("substr", null, null, null, null, null, null, 3, 4, null, null)));
        assertEquals("", run("ab", Map.of(), new Step("substr", null, null, null, null, null, null, 5, 2, null, null)));
    }
    @Test void templateMergesColumns() {
        Map<String, String> row = new HashMap<>(); row.put("F", "길동"); row.put("L", "홍"); row.put("N", null);
        assertEquals("홍 길동님", run(null, row, expr("template", "{L} {F}님", null)));
        assertEquals("길동!", run(null, row, expr("template", "{F}{N}!", null))); // 빈 값은 빈 문자열
        assertEquals("ID-길동", run("길동", row, expr("template", "ID-{@}", null)));
        assertThrows(IllegalArgumentException.class, () -> run(null, row, expr("template", "{없음}", null)));
    }
    @Test void calc() {
        Map<String, String> row = new HashMap<>(); row.put("P", "10.5"); row.put("Q", "3"); row.put("Z", "0"); row.put("N", null);
        assertEquals("31.5", run(null, row, expr("calc", "{P} * {Q}", null)));
        assertEquals("31.50", run(null, row, expr("calc", "{P} * {Q}", 2)));
        assertEquals("-1.5", run(null, row, expr("calc", "-({P} - 9) * 1 - ({Q} - 3) / 2", null)).replace("-0", "-0"));
        assertEquals("4", run(null, row, expr("calc", "(1 + 1) * 2 / 2 + 2", null)));
        assertEquals("0.33", run(null, row, expr("calc", "1 / 3", 2)));
        assertEquals("11.5", run("10.5", row, expr("calc", "{@} + 1", null)));
        assertNull(run(null, row, expr("calc", "{P} * {N}", null))); // 비어 있는 값이 끼면 결과도 비움
        assertThrows(IllegalArgumentException.class, () -> run(null, row, expr("calc", "{P} / {Z}", null)));
        assertThrows(IllegalArgumentException.class, () -> run("abc", row, expr("calc", "{@} + 1", null)));
    }
    @Test void validationRejectsBadRules() {
        for (Step bad : List.of(s("drop"), s("replace"), s("map"), s("split"), s("substr"), s("template"), expr("calc", "1 +", null), expr("calc", "abs({A})", null), expr("calc", "(1", null)))
            assertThrows(IllegalArgumentException.class, () -> ValueTransform.validate("C", List.of(bad)), bad.toString());
        assertDoesNotThrow(() -> ValueTransform.validate("C", List.of(expr("calc", "{A} * 1.1 / 0", 2))));
        assertThrows(IllegalArgumentException.class, () -> ValueTransform.validate("C", Collections.nCopies(11, s("trim"))));
    }
    @Test void referencesAreCollected() {
        assertEquals(new LinkedHashSet<>(List.of("A", "B")), ValueTransform.references(List.of(expr("template", "{A}-{@}-{B}-{A}", null), s("trim"))));
    }
}
