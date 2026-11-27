package dev.tableflow.state;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 상태 DB 접속 설정. 서버 상태(저장 연결·스케줄·작업·이력)를 담는 전용 PostgreSQL이며 이관 원본·대상 접속과 섞지 않는다.
 * 기본값이 없으므로 URL을 지정하지 않으면 서버를 시작하지 않는다. 비밀번호는 URL에 넣지 않고, 원격 접속은 인증서를 검증하는 TLS만 허용한다.
 */
public record StateDbSettings(String url, String user, String password, String schema) {
    private static final Pattern SCHEMA = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public StateDbSettings {
        url = url == null ? "" : url.strip(); user = user == null ? "" : user.strip(); password = password == null ? "" : password;
        schema = schema == null || schema.isBlank() ? "tableflow" : schema.strip();
        if (url.isEmpty()) throw invalid("상태 DB 주소가 없습니다. TABLEFLOW_STATE_DB_URL(예: jdbc:postgresql://127.0.0.1:15442/tableflow)을 지정해주세요.");
        if (user.isEmpty()) throw invalid("상태 DB 사용자가 없습니다. TABLEFLOW_STATE_DB_USER를 지정해주세요.");
        if (password.isEmpty()) throw invalid("상태 DB 비밀번호가 없습니다. TABLEFLOW_STATE_DB_PASSWORD를 지정해주세요.");
        if (!SCHEMA.matcher(schema).matches()) throw invalid("상태 DB 스키마 이름은 영문 소문자·숫자·밑줄만 쓸 수 있습니다.");
        checkUrl(url);
    }

    private static void checkUrl(String url) {
        if (!url.startsWith("jdbc:postgresql://")) throw invalid("상태 DB는 PostgreSQL만 지원합니다. 주소는 jdbc:postgresql://호스트:포트/DB 형식이어야 합니다.");
        URI uri;
        try { uri = new URI(url.substring("jdbc:".length())); }
        catch (URISyntaxException e) { throw invalid("상태 DB 주소 형식이 올바르지 않습니다."); }
        // 쉼표로 여러 호스트를 적는 형식 등 호스트를 하나로 해석할 수 없는 주소는 받지 않는다.
        if (uri.getHost() == null || uri.getUserInfo() != null) throw invalid("상태 DB 주소에는 호스트 하나를 지정해주세요. 사용자와 비밀번호는 주소에 넣지 않습니다.");
        String sslmode = null;
        for (String pair : uri.getRawQuery() == null ? new String[0] : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2); String key = kv[0].toLowerCase(Locale.ROOT);
            if (key.contains("%") || key.equals("password") || key.equals("user") || key.equals("sslpassword"))
                throw invalid("상태 DB의 사용자·비밀번호는 주소가 아니라 별도 설정에 넣어주세요.");
            if (key.equals("sslmode")) sslmode = kv.length > 1 ? kv[1] : "";
            if (key.equals("sslfactory") || key.equals("socketfactory")) throw invalid("상태 DB 주소에서 접속 방식을 바꾸는 설정은 허용하지 않습니다.");
        }
        if (!loopback(uri.getHost()) && !"verify-full".equals(sslmode))
            throw invalid("다른 장비의 상태 DB에는 인증서를 검증하는 TLS(sslmode=verify-full)로만 접속할 수 있습니다.");
    }
    private static boolean loopback(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) if (!address.isLoopbackAddress()) return false;
            return true;
        } catch (UnknownHostException e) { throw invalid("상태 DB 호스트를 찾을 수 없습니다."); }
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
    @Override public String toString() { return "StateDbSettings[redacted]"; }
}
