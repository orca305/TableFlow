package dev.tableflow.load;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 컬럼 값 변환 규칙. 한 컬럼에 여러 단계를 순서대로 적용한다(앞 단계의 결과가 다음 단계의 현재 값).
 * 값은 서버에서 문자열로 계산하며 SQL을 만들지 않으므로 조건식 같은 주입 위험이 없다.
 * 계산식·문자열 합치기·조건별 값의 결과에서 {컬럼}은 같은 행의 원본 컬럼 값, {@}은 현재 값이다.
 */
public final class ValueTransform {
    private ValueTransform() {}

    public static final int MAX_STEPS = 10;
    public static final int MAX_CASES = 20;
    public static final Set<String> OPS = Set.of("trim", "upper", "lower", "replace", "map", "nullTo", "split", "substr", "template", "calc",
        "dateFormat", "regexReplace", "regexExtract", "case");
    private static final Set<String> TESTS = Set.of("eq", "ne", "contains", "startsWith", "endsWith", "empty", "notEmpty", "gt", "ge", "lt", "le");

    /**
     * op: trim / upper / lower / replace(find→with) / map(치환표) / nullTo(빈 값→value) / split(구분자의 part번째 조각) /
     * substr(start번째부터 length자) / template(문자열 합치기: expression) / calc(계산식: expression, 소수 scale자리 반올림) /
     * dateFormat(find 형식의 날짜를 with 형식으로, find를 비우면 yyyy-MM-dd[THH:mm:ss]) / regexReplace(정규식 find → with, $1 참조) /
     * regexExtract(정규식 find에 맞는 부분, part번째 그룹. 없으면 비움) / case(cases를 위에서부터 검사해 처음 맞는 결과, 모두 아니면 value. value가 없으면 그대로).
     */
    public record Step(@Size(max = 16) String op, @Size(max = 200) String find, @Size(max = 200) String with, @Size(max = 200) String value,
                       @Size(max = 50) Map<@Size(max = 100) String, @Size(max = 200) String> map, @Size(max = 20) String delimiter,
                       @Min(1) @Max(100) Integer part, @Min(1) @Max(10000) Integer start, @Min(1) @Max(10000) Integer length,
                       @Size(max = 500) String expression, @Min(0) @Max(20) Integer scale,
                       @Size(max = 20) List<@NotNull @Valid Case> cases) {
        public Step(String op, String find, String with, String value, Map<String, String> map, String delimiter, Integer part, Integer start, Integer length, String expression, Integer scale) {
            this(op, find, with, value, map, delimiter, part, start, length, expression, scale, null);
        }
    }
    /** column: 검사할 원본 컬럼(비우면 현재 값). test: eq/ne/contains/startsWith/endsWith/empty/notEmpty/gt/ge/lt/le. result: 맞을 때의 값. */
    public record Case(@Size(max = 128) String column, @Size(max = 16) String test, @Size(max = 200) String compare, @Size(max = 500) String result) {}

    /** 규칙이 읽어야 하는 원본 컬럼 이름({컬럼}). {@}은 제외한다. */
    public static Set<String> references(List<Step> steps) {
        Set<String> names = new LinkedHashSet<>();
        if (steps == null) return names;
        for (Step s : steps) {
            if (s == null) continue;
            collect(s.expression(), names);
            if (!"case".equals(s.op())) continue;
            if (s.cases() != null) for (Case c : s.cases()) {
                if (c == null) continue;
                if (usesColumn(c)) names.add(c.column());
                collect(c.result(), names);
            }
            collect(s.value(), names);
        }
        return names;
    }
    private static boolean usesColumn(Case c) { return c.column() != null && !c.column().isBlank() && !c.column().equals("@"); }
    private static void collect(String e, Set<String> names) {
        if (e == null) return;
        int i = 0;
        while ((i = e.indexOf('{', i)) >= 0) {
            int j = e.indexOf('}', i);
            if (j < 0) break;
            String name = e.substring(i + 1, j);
            if (!name.equals("@") && !name.isBlank()) names.add(name);
            i = j + 1;
        }
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
                case "dateFormat" -> {
                    if (s.with() == null || s.with().isEmpty()) throw new IllegalArgumentException(where + "바꿀 날짜 형식을 입력해주세요.");
                    try { if (s.find() != null && !s.find().isEmpty()) dateFormatter(s.find()); dateFormatter(s.with()); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException(where + e.getMessage()); }
                }
                case "regexReplace", "regexExtract" -> {
                    if (s.find() == null || s.find().isEmpty()) throw new IllegalArgumentException(where + "정규식을 입력해주세요.");
                    Pattern pattern;
                    try { pattern = Pattern.compile(s.find()); }
                    catch (PatternSyntaxException e) { throw new IllegalArgumentException(where + "정규식을 해석할 수 없습니다: " + e.getDescription()); }
                    int groups = pattern.matcher("").groupCount();
                    if (s.op().equals("regexExtract")) { if (s.part() != null && s.part() > groups) throw new IllegalArgumentException(where + "정규식에 " + s.part() + "번째 그룹이 없습니다."); }
                    else checkReplacement(pattern, groups, s.with(), where);
                }
                case "case" -> {
                    if (s.cases() == null || s.cases().isEmpty()) throw new IllegalArgumentException(where + "조건을 하나 이상 넣어주세요.");
                    if (s.cases().size() > MAX_CASES) throw new IllegalArgumentException(where + "조건은 최대 " + MAX_CASES + "개까지 지정할 수 있습니다.");
                    for (Case c : s.cases()) {
                        if (c == null || c.test() == null || !TESTS.contains(c.test())) throw new IllegalArgumentException(where + "알 수 없는 조건입니다.");
                        if (!Set.of("empty", "notEmpty").contains(c.test()) && (c.compare() == null || c.compare().isEmpty())) throw new IllegalArgumentException(where + "비교할 값을 입력해주세요.");
                        if (Set.of("gt", "ge", "lt", "le").contains(c.test()) && number(c.compare()) == null && moment(c.compare()) == null)
                            throw new IllegalArgumentException(where + "크기 비교의 기준값은 숫자 또는 날짜(yyyy-MM-dd)여야 합니다.");
                        checkBraces(c.result(), where);
                    }
                    checkBraces(s.value(), where);
                }
                default -> { }
            }
        }
    }
    private static void checkBraces(String e, String where) {
        if (e == null) return;
        for (int i = e.indexOf('{'); i >= 0; i = e.indexOf('{', i + 1)) if (e.indexOf('}', i) < 0) throw new IllegalArgumentException(where + "식의 중괄호가 닫히지 않았습니다.");
    }
    /** 치환 문자열의 $번호·${이름}·역슬래시를 이관 전에 검사한다(실행 중에야 드러나지 않게). */
    private static void checkReplacement(Pattern pattern, int groups, String with, String where) {
        if (with == null) return;
        for (int i = 0; i < with.length(); i++) {
            char c = with.charAt(i);
            if (c == '\\') { if (++i >= with.length()) throw new IllegalArgumentException(where + "바꿀 내용이 역슬래시로 끝날 수 없습니다."); }
            else if (c == '$') {
                char next = i + 1 < with.length() ? with.charAt(i + 1) : ' ';
                if (next >= '0' && next <= '9') { if (next - '0' > groups) throw new IllegalArgumentException(where + "정규식에 " + next + "번째 그룹이 없습니다."); i++; }
                else if (next == '{' && with.indexOf('}', i) > 0 && pattern.namedGroups().containsKey(with.substring(i + 2, with.indexOf('}', i)))) i = with.indexOf('}', i);
                else throw new IllegalArgumentException(where + "$ 뒤에는 그룹 번호를 씁니다. $ 문자 자체는 \\$로 입력해주세요.");
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
                case "dateFormat" -> { if (v != null && !v.isBlank()) v = reformat(s, v.strip(), column); }
                case "regexReplace", "regexExtract" -> { if (v != null) v = regex(s, v, column); }
                case "case" -> v = choose(s, v, row, column);
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

    /** 날짜 형식 문자(yyyy yy MM M dd d HH H mm ss SSS)만 받는다. 대문자 YYYY·DD도 연·일로 읽어 흔한 실수를 막는다. */
    static DateTimeFormatter dateFormatter(String pattern) {
        var b = new DateTimeFormatterBuilder(); int i = 0; boolean field = false;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) || c == 'T') { b.appendLiteral(c); i++; continue; }
            int n = 1; while (i + n < pattern.length() && pattern.charAt(i + n) == c) n++;
            String token = pattern.substring(i, i + n);
            switch (token) {
                case "yyyy", "YYYY" -> b.appendValue(ChronoField.YEAR, 4);
                case "yy", "YY" -> b.appendValueReduced(ChronoField.YEAR, 2, 2, 2000);
                case "MM" -> b.appendValue(ChronoField.MONTH_OF_YEAR, 2);
                case "M" -> b.appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE);
                case "dd", "DD" -> b.appendValue(ChronoField.DAY_OF_MONTH, 2);
                case "d" -> b.appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE);
                case "HH" -> b.appendValue(ChronoField.HOUR_OF_DAY, 2);
                case "H" -> b.appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NOT_NEGATIVE);
                case "mm" -> b.appendValue(ChronoField.MINUTE_OF_HOUR, 2);
                case "ss" -> b.appendValue(ChronoField.SECOND_OF_MINUTE, 2);
                case "SSS" -> b.appendValue(ChronoField.MILLI_OF_SECOND, 3);
                default -> throw new IllegalArgumentException("날짜 형식에 쓸 수 없는 문자입니다: " + token + " (사용 가능: yyyy yy MM dd HH mm ss SSS)");
            }
            field = true; i += n;
        }
        if (!field) throw new IllegalArgumentException("날짜 형식에 yyyy, MM, dd 같은 항목이 필요합니다.");
        return b.toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    }
    /** ISO 날짜(yyyy-MM-dd) 또는 일시(T나 공백 구분). 아니면 null. */
    private static LocalDateTime moment(String text) {
        if (text == null) return null;
        String s = text.strip();
        try { return s.length() <= 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s.replace(' ', 'T')); }
        catch (DateTimeException e) { return null; }
    }
    private static BigDecimal number(String text) {
        if (text == null) return null;
        try { return new BigDecimal(text.strip()); } catch (NumberFormatException e) { return null; }
    }
    private static String reformat(Step s, String value, String column) {
        String from = s.find() == null || s.find().isEmpty() ? null : s.find();
        try {
            LocalDateTime at;
            if (from == null) at = moment(value);
            else {
                TemporalAccessor parsed = dateFormatter(from).parse(value);
                LocalDate date = parsed.query(TemporalQueries.localDate()); LocalTime time = parsed.query(TemporalQueries.localTime());
                at = date == null ? null : date.atTime(time == null ? LocalTime.MIDNIGHT : time);
            }
            if (at != null) return dateFormatter(s.with()).format(at);
        } catch (DateTimeException ignored) { /* 아래에서 형식 오류로 알린다 */ }
        throw new IllegalArgumentException("컬럼 " + column + ": 날짜 형식(" + (from == null ? "yyyy-MM-dd 또는 yyyy-MM-ddTHH:mm:ss" : from) + ")에 맞지 않거나 없는 날짜인 값이 있습니다.");
    }
    private static String regex(Step s, String value, String column) {
        try {
            Matcher m = Pattern.compile(s.find()).matcher(new Bounded(value, new long[]{200_000L + 200L * value.length()}));
            if (s.op().equals("regexReplace")) return m.replaceAll(s.with() == null ? "" : s.with());
            if (!m.find()) return null;
            return m.group(s.part() != null ? s.part() : m.groupCount() > 0 ? 1 : 0);
        } catch (TooComplex | StackOverflowError e) {
            throw new IllegalArgumentException("컬럼 " + column + ": 정규식 처리가 너무 오래 걸리는 값이 있습니다. 정규식을 단순하게 바꿔주세요.");
        }
    }
    private static final class TooComplex extends RuntimeException {}
    /** 역추적이 폭증하는 정규식이 서버를 붙잡지 않도록 문자 접근 횟수를 제한한다. */
    private record Bounded(CharSequence text, long[] budget) implements CharSequence {
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) { if (--budget[0] < 0) throw new TooComplex(); return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { return new Bounded(text.subSequence(start, end), budget); }
        @Override public String toString() { return text.toString(); }
    }
    private static String choose(Step s, String current, Map<String, String> row, String column) {
        for (Case c : s.cases()) {
            String value = current;
            if (usesColumn(c)) {
                if (!row.containsKey(c.column())) throw new IllegalArgumentException("컬럼 " + column + ": 조건이 참조하는 컬럼 " + c.column() + "을(를) 찾을 수 없습니다.");
                value = row.get(c.column());
            }
            if (matches(c, value, column)) return template(c.result() == null ? "" : c.result(), current, row, column);
        }
        return s.value() == null ? current : template(s.value(), current, row, column);
    }
    /** 빈 값은 empty에만 맞는다(SQL의 NULL 비교와 같이 같지 않음·크기 비교도 거짓). 같음은 글자 그대로, 크기 비교는 숫자 또는 날짜로 한다. */
    private static boolean matches(Case c, String value, String column) {
        boolean blank = value == null || value.isBlank();
        if (c.test().equals("empty")) return blank;
        if (blank) return false;
        return switch (c.test()) {
            case "notEmpty" -> true;
            case "eq" -> value.equals(c.compare());
            case "ne" -> !value.equals(c.compare());
            case "contains" -> value.contains(c.compare());
            case "startsWith" -> value.startsWith(c.compare());
            case "endsWith" -> value.endsWith(c.compare());
            default -> {
                int order;
                BigDecimal limit = number(c.compare());
                if (limit != null) {
                    BigDecimal n = number(value);
                    if (n == null) throw new IllegalArgumentException("컬럼 " + column + ": 숫자 조건에 숫자가 아닌 값이 있습니다.");
                    order = n.compareTo(limit);
                } else {
                    LocalDateTime at = moment(value);
                    if (at == null) throw new IllegalArgumentException("컬럼 " + column + ": 날짜 조건에 날짜(yyyy-MM-dd)가 아닌 값이 있습니다.");
                    order = at.compareTo(moment(c.compare()));
                }
                yield switch (c.test()) { case "gt" -> order > 0; case "ge" -> order >= 0; case "lt" -> order < 0; default -> order <= 0; };
            }
        };
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
