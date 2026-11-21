package dev.tableflow.load;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/**
 * 컬럼 값 변환 규칙. 한 컬럼에 여러 단계를 순서대로 적용한다(앞 단계의 결과가 다음 단계의 현재 값).
 * 값은 서버에서 문자열로 계산하며 SQL을 만들지 않으므로 조건식 같은 주입 위험이 없다.
 * 계산식·문자열 합치기에서 {컬럼}은 같은 행의 원본 컬럼 값, {@}은 현재 값이다.
 */
public final class ValueTransform {
    private ValueTransform() {}

    public static final int MAX_STEPS = 10;
    public static final Set<String> OPS = Set.of("trim", "upper", "lower", "replace", "map", "nullTo", "split", "substr", "template", "calc");

    /**
     * op: trim / upper / lower / replace(find→with) / map(치환표) / nullTo(빈 값→value) / split(구분자의 part번째 조각) /
     * substr(start번째부터 length자) / template(문자열 합치기: expression) / calc(계산식: expression, 소수 scale자리 반올림).
     */
    public record Step(@Size(max = 16) String op, @Size(max = 200) String find, @Size(max = 200) String with, @Size(max = 200) String value,
                       @Size(max = 50) Map<@Size(max = 100) String, @Size(max = 200) String> map, @Size(max = 20) String delimiter,
                       @Min(1) @Max(100) Integer part, @Min(1) @Max(10000) Integer start, @Min(1) @Max(10000) Integer length,
                       @Size(max = 500) String expression, @Min(0) @Max(20) Integer scale) {}

    /** 규칙이 읽어야 하는 원본 컬럼 이름({컬럼}). {@}은 제외한다. */
    public static Set<String> references(List<Step> steps) {
        Set<String> names = new LinkedHashSet<>();
        if (steps == null) return names;
        for (Step s : steps) {
            if (s == null || s.expression() == null) continue;
            String e = s.expression(); int i = 0;
            while ((i = e.indexOf('{', i)) >= 0) {
                int j = e.indexOf('}', i);
                if (j < 0) break;
                String name = e.substring(i + 1, j);
                if (!name.equals("@") && !name.isBlank()) names.add(name);
                i = j + 1;
            }
        }
        return names;
    }
    public static boolean produces(List<Step> steps, String op) { return steps != null && steps.stream().anyMatch(s -> s != null && op.equals(s.op())); }

    /** 요청 검증: 알 수 없는 단계·빠진 입력·잘못된 계산식을 이관 전에 알려준다. */
    public static void validate(String column, List<Step> steps) {
        if (steps == null || steps.isEmpty()) return;
        if (steps.size() > MAX_STEPS) throw new IllegalArgumentException("컬럼 " + column + ": 변환 규칙은 최대 " + MAX_STEPS + "단계까지 지정할 수 있습니다.");
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i); String where = "컬럼 " + column + "의 변환 규칙 " + (i + 1) + "단계: ";
            if (s == null || s.op() == null || !OPS.contains(s.op())) throw new IllegalArgumentException(where + "알 수 없는 변환입니다.");
            switch (s.op()) {
                case "replace" -> { if (s.find() == null || s.find().isEmpty()) throw new IllegalArgumentException(where + "바꿀 문자열을 입력해주세요."); }
                case "map" -> { if (s.map() == null || s.map().isEmpty()) throw new IllegalArgumentException(where + "치환표에 값을 하나 이상 넣어주세요."); }
                case "split" -> { if (s.delimiter() == null || s.delimiter().isEmpty() || s.part() == null) throw new IllegalArgumentException(where + "구분자와 몇 번째 조각인지 입력해주세요."); }
                case "substr" -> { if (s.start() == null || s.length() == null) throw new IllegalArgumentException(where + "시작 위치와 글자 수를 입력해주세요."); }
                case "template", "calc" -> {
                    if (s.expression() == null || s.expression().isBlank()) throw new IllegalArgumentException(where + "식을 입력해주세요.");
                    if (s.op().equals("calc")) {
                        try { new Calc(s.expression(), name -> BigDecimal.ONE).evaluate(); }
                        catch (ArithmeticException ignored) { /* 0으로 나누기는 값이 있어야 알 수 있다 */ }
                        catch (IllegalArgumentException e) { throw new IllegalArgumentException(where + e.getMessage()); }
                    }
                }
                default -> { }
            }
        }
    }

    /** 한 값에 단계를 순서대로 적용한다. row는 같은 행의 원본 컬럼 값(이름 → 값). */
    public static String apply(List<Step> steps, String current, Map<String, String> row, String column) {
        String v = current;
        for (Step s : steps) {
            switch (s.op()) {
                case "trim" -> { if (v != null) v = v.strip(); }
                case "upper" -> { if (v != null) v = v.toUpperCase(Locale.ROOT); }
                case "lower" -> { if (v != null) v = v.toLowerCase(Locale.ROOT); }
                case "replace" -> { if (v != null) v = v.replace(s.find(), s.with() == null ? "" : s.with()); }
                case "map" -> { if (v != null && s.map().containsKey(v)) v = s.map().get(v); }
                case "nullTo" -> { if (v == null || v.isBlank()) v = s.value() == null ? "" : s.value(); }
                case "split" -> {
                    if (v != null) { String[] parts = v.split(java.util.regex.Pattern.quote(s.delimiter()), -1); v = s.part() <= parts.length ? parts[s.part() - 1] : null; }
                }
                case "substr" -> {
                    if (v != null) { int from = s.start() - 1; v = from >= v.length() ? "" : v.substring(from, Math.min(v.length(), from + s.length())); }
                }
                case "template" -> v = template(s.expression(), v, row, column);
                case "calc" -> v = calc(s, v, row, column);
                default -> throw new IllegalArgumentException("알 수 없는 변환입니다: " + s.op());
            }
        }
        return v;
    }

    private static String template(String expression, String current, Map<String, String> row, String column) {
        StringBuilder out = new StringBuilder(); int i = 0;
        while (i < expression.length()) {
            int open = expression.indexOf('{', i);
            if (open < 0) { out.append(expression, i, expression.length()); break; }
            int close = expression.indexOf('}', open);
            if (close < 0) throw new IllegalArgumentException("컬럼 " + column + ": 식의 중괄호가 닫히지 않았습니다.");
            out.append(expression, i, open);
            String name = expression.substring(open + 1, close);
            String value = name.equals("@") ? current : row.get(name);
            if (!name.equals("@") && !row.containsKey(name)) throw new IllegalArgumentException("컬럼 " + column + ": 식이 참조하는 컬럼 " + name + "을(를) 찾을 수 없습니다.");
            out.append(value == null ? "" : value);
            i = close + 1;
        }
        return out.toString();
    }

    private static String calc(Step s, String current, Map<String, String> row, String column) {
        boolean[] sawNull = {false};
        BigDecimal result;
        try {
            result = new Calc(s.expression(), name -> {
                String raw = name.equals("@") ? current : row.get(name);
                if (!name.equals("@") && !row.containsKey(name)) throw new IllegalArgumentException("식이 참조하는 컬럼 " + name + "을(를) 찾을 수 없습니다.");
                if (raw == null || raw.isBlank()) { sawNull[0] = true; return BigDecimal.ZERO; }
                try { return new BigDecimal(raw.strip()); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("숫자가 아닌 값(" + raw + ")을 계산할 수 없습니다: " + name); }
            }).evaluate();
        } catch (ArithmeticException e) { throw new IllegalArgumentException("컬럼 " + column + ": 0으로 나눌 수 없습니다."); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("컬럼 " + column + ": " + e.getMessage()); }
        if (sawNull[0]) return null; // 값이 비어 있는 항목이 있으면 결과도 비움(SQL의 NULL 계산과 같음)
        if (s.scale() != null) return result.setScale(s.scale(), RoundingMode.HALF_UP).toPlainString();
        return result.stripTrailingZeros().toPlainString();
    }

    /** 사칙연산·괄호·음수·{컬럼}만 계산하는 작은 계산기. 함수 호출이나 외부 접근이 없다. */
    static final class Calc {
        interface Resolver { BigDecimal value(String name); }
        private final String text; private final Resolver resolver; private int pos;
        Calc(String text, Resolver resolver) { this.text = text; this.resolver = resolver; }
        BigDecimal evaluate() {
            BigDecimal v = expr(); skip();
            if (pos < text.length()) throw new IllegalArgumentException("계산식을 해석할 수 없습니다: '" + text.charAt(pos) + "' 근처");
            return v;
        }
        private void skip() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
        private BigDecimal expr() {
            BigDecimal v = term();
            while (true) {
                skip();
                if (pos < text.length() && text.charAt(pos) == '+') { pos++; v = v.add(term()); }
                else if (pos < text.length() && text.charAt(pos) == '-') { pos++; v = v.subtract(term()); }
                else return v;
            }
        }
        private BigDecimal term() {
            BigDecimal v = factor();
            while (true) {
                skip();
                if (pos < text.length() && text.charAt(pos) == '*') { pos++; v = v.multiply(factor()); }
                else if (pos < text.length() && text.charAt(pos) == '/') { pos++; v = v.divide(factor(), new MathContext(34, RoundingMode.HALF_UP)); }
                else return v;
            }
        }
        private BigDecimal factor() {
            skip();
            if (pos >= text.length()) throw new IllegalArgumentException("계산식이 끝나기 전에 값이 필요합니다.");
            char c = text.charAt(pos);
            if (c == '-') { pos++; return factor().negate(); }
            if (c == '+') { pos++; return factor(); }
            if (c == '(') {
                pos++; BigDecimal v = expr(); skip();
                if (pos >= text.length() || text.charAt(pos) != ')') throw new IllegalArgumentException("계산식의 괄호가 맞지 않습니다.");
                pos++; return v;
            }
            if (c == '{') {
                int close = text.indexOf('}', pos);
                if (close < 0) throw new IllegalArgumentException("계산식의 중괄호가 닫히지 않았습니다.");
                String name = text.substring(pos + 1, close); pos = close + 1;
                return resolver.value(name);
            }
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) pos++;
                try { return new BigDecimal(text.substring(start, pos)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("계산식의 숫자를 해석할 수 없습니다: " + text.substring(start, pos)); }
            }
            throw new IllegalArgumentException("계산식을 해석할 수 없습니다: '" + c + "' 근처");
        }
    }
}
