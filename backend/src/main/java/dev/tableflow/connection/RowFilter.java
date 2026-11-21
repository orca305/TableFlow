package dev.tableflow.connection;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 이관할 행을 거르는 WHERE 조건(조건식만, "WHERE" 단어 제외)을 검사한다. 원본 DB는 읽기 전용 연결로만 접근하지만,
 * 한 문장의 순수한 조건식만 허용해 다른 구문이 끼어들 여지를 줄인다. 작은따옴표 안의 값은 검사하지 않는다.
 */
public final class RowFilter {
    private RowFilter() {}
    public static final int MAX_LENGTH = 1000;
    private static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "GRANT", "REVOKE", "MERGE",
        "CALL", "EXEC", "EXECUTE", "INTO", "UNION", "REPLACE", "LOCK", "COMMIT", "ROLLBACK", "SET", "BEGIN", "DECLARE", "COPY", "SLEEP", "PG_SLEEP", "DBMS_LOCK",
        "LOAD_FILE", "OUTFILE", "DUMPFILE", "WAITFOR", "BENCHMARK");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 공백이면 null. 규칙에 어긋나면 IllegalArgumentException. 통과하면 앞뒤 공백을 뗀 조건식을 돌려준다. */
    public static String clean(String where) {
        if (where == null) return null;
        String text = where.strip();
        if (text.isEmpty()) return null;
        if (text.length() > MAX_LENGTH) throw new IllegalArgumentException("행 조건은 " + MAX_LENGTH + "자 이하여야 합니다.");
        if (text.regionMatches(true, 0, "WHERE", 0, 5) && (text.length() == 5 || Character.isWhitespace(text.charAt(5))))
            throw new IllegalArgumentException("행 조건에는 WHERE를 쓰지 않고 조건식만 입력해주세요. 예: STATUS = 'A' AND AMT > 100");
        StringBuilder outside = new StringBuilder(); char quote = 0; int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == 0 && (c == '\'' || c == '"' || c == '`')) { quote = c; outside.append(' '); continue; }
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) { i++; continue; } // 같은 따옴표 두 번은 이스케이프
                    quote = 0;
                }
                continue; // 따옴표 안(값 또는 따옴표를 쓴 컬럼 이름)은 검사하지 않는다
            }
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') throw new IllegalArgumentException("행 조건에 제어 문자를 쓸 수 없습니다.");
            if (c == '(') depth++;
            if (c == ')' && --depth < 0) throw new IllegalArgumentException("행 조건의 괄호가 맞지 않습니다.");
            if (c == '\\') throw new IllegalArgumentException("행 조건에 역슬래시를 쓸 수 없습니다.");
            outside.append(c);
        }
        if (quote != 0) throw new IllegalArgumentException("행 조건의 따옴표가 닫히지 않았습니다.");
        if (depth != 0) throw new IllegalArgumentException("행 조건의 괄호가 맞지 않습니다.");
        String rest = outside.toString();
        if (rest.contains(";")) throw new IllegalArgumentException("행 조건에는 세미콜론을 쓸 수 없습니다. 한 개의 조건식만 입력해주세요.");
        if (rest.contains("--") || rest.contains("/*") || rest.contains("*/") || rest.contains("#")) throw new IllegalArgumentException("행 조건에 주석을 쓸 수 없습니다.");
        var m = WORD.matcher(rest);
        while (m.find()) if (FORBIDDEN.contains(m.group().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("행 조건에 사용할 수 없는 단어가 있습니다: " + m.group() + ". 조건식(비교, AND/OR, IN, LIKE, BETWEEN, IS NULL 등)만 입력할 수 있습니다.");
        return text;
    }
}
