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
    static Step op(String op, String find, String with) { return new Step(op, find, with, null, null, null, null, null, null, null, null); }
    static ValueTransform.Case when(String column, String test, String compare, String result) { return new ValueTransform.Case(column, test, compare, result); }
    static Step choose(String otherwise, ValueTransform.Case... cases) { return new Step("case", null, null, otherwise, null, null, null, null, null, null, null, List.of(cases)); }

    @Test void dateFormats() {
        assertEquals("2024-03-05", run("20240305", Map.of(), op("dateFormat", "yyyyMMdd", "yyyy-MM-dd")));
        assertEquals("2024-03-05", run("20240305", Map.of(), op("dateFormat", "YYYYMMDD", "YYYY-MM-DD"))); // 대문자 연·일도 같은 뜻
        assertEquals("2024-03-05T00:00:00", run("2024/3/5", Map.of(), op("dateFormat", "yyyy/M/d", "yyyy-MM-ddTHH:mm:ss")));
        assertEquals("05.03.2024 07:08", run("2024-03-05T07:08:09", Map.of(), op("dateFormat", null, "dd.MM.yyyy HH:mm"))); // 원본 형식 생략 = ISO
        assertEquals("202403", run("2024-03-05 07:08:09.123", Map.of(), op("dateFormat", "", "yyyyMM")));
        assertEquals("2024-12-31", run("241231", Map.of(), op("dateFormat", "yyMMdd", "yyyy-MM-dd")));
        assertNull(run(null, Map.of(), op("dateFormat", "yyyyMMdd", "yyyy-MM-dd")));
        assertEquals("  ", run("  ", Map.of(), op("dateFormat", "yyyyMMdd", "yyyy-MM-dd"))); // 빈 값은 그대로(적재 때 NULL)
        for (String bad : List.of("20240230", "2024-03-05", "abc", "202403051"))
            assertThrows(IllegalArgumentException.class, () -> run(bad, Map.of(), op("dateFormat", "yyyyMMdd", "yyyy-MM-dd")), bad);
    }
    @Test void regularExpressions() {
        assertEquals("010-1234-5678", run("01012345678", Map.of(), op("regexReplace", "(\\d{3})(\\d{4})(\\d{4})", "$1-$2-$3")));
        assertEquals("AB", run("A1B22", Map.of(), op("regexReplace", "\\d+", null)));
        assertEquals("$5", run("5", Map.of(), op("regexReplace", "^", "\\$")));
        assertEquals("kim", run("kim@example.com", Map.of(), op("regexExtract", "^([^@]+)@", null))); // 그룹이 있으면 1번째 그룹
        assertEquals("123", run("no 123 x", Map.of(), op("regexExtract", "\\d+", null)));
        assertEquals("com", run("a@b.com", Map.of(), new Step("regexExtract", "(\\w+)@(\\w+)\\.(\\w+)", null, null, null, null, 3, null, null, null, null)));
        assertNull(run("없음", Map.of(), op("regexExtract", "\\d+", null)));
        var slow = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
            () -> assertThrows(IllegalArgumentException.class, () -> run("a".repeat(5000) + "!", Map.of(), op("regexReplace", "(a+)+$", "x"))));
        assertTrue(slow.getMessage().contains("정규식"), slow.getMessage());
    }
    @Test void conditionalValues() {
        Map<String, String> row = new HashMap<>(); row.put("AMT", "150.00"); row.put("KIND", "A"); row.put("D", "2024-03-05"); row.put("N", null);
        var grade = choose("일반", when("AMT", "ge", "100", "우수 {KIND}"), when("AMT", "ge", "10", "보통"));
        assertEquals("우수 A", run("x", row, grade));
        row.put("AMT", "50"); assertEquals("보통", run("x", row, grade));
        row.put("AMT", "1"); assertEquals("일반", run("x", row, grade));
        row.put("AMT", null); assertEquals("일반", run("x", row, grade)); // 빈 값은 크기 비교에 맞지 않는다
        assertEquals("x", run("x", row, choose(null, when("KIND", "eq", "Z", "z")))); // 기본값이 없으면 현재 값 유지
        assertEquals("비어 있음", run("x", row, choose(null, when("N", "empty", null, "비어 있음"))));
        assertEquals("x", run("x", row, choose(null, when("N", "ne", "A", "다름"))));
        assertEquals("new-x", run("x", row, choose(null, when(null, "eq", "x", "new-{@}"))));
        assertEquals("y", run("hello", row, choose("n", when("", "startsWith", "he", "y"))));
        assertEquals("y", run("hello", row, choose("n", when("@", "endsWith", "lo", "y"))));
        assertEquals("y", run("hello", row, choose("n", when("@", "contains", "ell", "y"))));
        assertEquals("올해", run("x", row, choose("이전", when("D", "ge", "2024-01-01", "올해"))));
        assertEquals("", run("x", row, choose(null, when("KIND", "notEmpty", null, null))));
        assertThrows(IllegalArgumentException.class, () -> run("x", row, choose(null, when("KIND", "gt", "1", "y"))));
        assertThrows(IllegalArgumentException.class, () -> run("x", row, choose(null, when("없음", "eq", "1", "y"))));
        assertEquals(new LinkedHashSet<>(List.of("AMT", "KIND", "D", "N")),
            ValueTransform.references(List.of(choose("{N}", when("AMT", "eq", "1", "{KIND}{@}"), when("D", "empty", null, "{@}")))));
    }
    @Test void validationRejectsBadNewRules() {
        for (Step bad : List.of(op("dateFormat", "yyyyMMdd", null), op("dateFormat", "yyyyQQ", "yyyy"), op("dateFormat", null, "---"), op("regexReplace", "(", "x"),
                op("regexReplace", "a", "$1"), op("regexReplace", "a", "x$"), op("regexReplace", "a", "x\\"), op("regexExtract", null, null),
                new Step("regexExtract", "a(b)", null, null, null, null, 2, null, null, null, null), choose(null), choose(null, when("A", "like", "1", "y")),
                choose(null, when("A", "eq", "", "y")), choose(null, when("A", "gt", "abc", "y")), choose(null, when("A", "eq", "1", "{B")), choose("{B", when("A", "eq", "1", "y"))))
            assertThrows(IllegalArgumentException.class, () -> ValueTransform.validate("C", List.of(bad)), bad.toString());
        assertDoesNotThrow(() -> ValueTransform.validate("C", List.of(op("regexReplace", "(?<n>a)(b)", "${n}-$2$0"), op("dateFormat", null, "yyyy"),
            choose(null, when(null, "le", "2024-01-01", "{@}")))));
    }
    @Test void stepsDeserializeFromJson() throws Exception {
        var step = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
            "{\"op\":\"case\",\"value\":\"n\",\"cases\":[{\"column\":\"A\",\"test\":\"eq\",\"compare\":\"1\",\"result\":\"y\"}]}", Step.class);
        assertEquals("y", run("x", Map.of("A", "1"), step));
        var old = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"op\":\"upper\"}", Step.class);
        assertNull(old.cases()); assertEquals("X", run("x", Map.of(), old));
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
