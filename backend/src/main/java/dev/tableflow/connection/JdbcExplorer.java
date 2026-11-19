package dev.tableflow.connection;

import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.*;
import java.sql.*;
import java.util.*;

/** Short-lived, metadata-only connections. No credentials or connection pool are retained. */
@Service
public class JdbcExplorer {
    private final boolean enabled;
    private final Set<String> hosts;
    public JdbcExplorer(@Value("${tableflow.jdbc.enabled:false}") boolean enabled,
                        @Value("${tableflow.jdbc.allowed-hosts:localhost,127.0.0.1}") String allowedHosts) {
        this.enabled = enabled;
        this.hosts = new HashSet<>(Arrays.stream(allowedHosts.split(",")).map(String::strip).map(s -> s.toLowerCase(Locale.ROOT)).toList());
    }
    public enum Dbms {
        oracle("oracle.jdbc.OracleDriver", 1521), postgresql("org.postgresql.Driver", 5432),
        mysql("com.mysql.cj.jdbc.Driver", 3306), mariadb("org.mariadb.jdbc.Driver", 3306);
        final String driver; final int port;
        Dbms(String driver, int port) { this.driver = driver; this.port = port; }
    }
    public record Credentials(@NotNull Dbms dbms,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9.-]{0,252}") String host,
        @Min(1) @Max(65535) int port,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_][A-Za-z0-9_.$-]{0,127}") String database,
        @NotBlank @Size(max=128) String username, @NotNull @Size(max=1024) String password,
        boolean tls) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record DriverInfo(Dbms dbms, int defaultPort, boolean available, String version) {}
    public record Registry(boolean enabled, List<DriverInfo> drivers) {}
    public Registry registry() {
        return new Registry(enabled, Arrays.stream(Dbms.values()).map(db -> {
            try {
                Driver driver = (Driver) Class.forName(db.driver).getDeclaredConstructor().newInstance();
                return new DriverInfo(db, db.port, true, driver.getMajorVersion() + "." + driver.getMinorVersion());
            } catch (ReflectiveOperationException | LinkageError e) { return new DriverInfo(db, db.port, false, ""); }
        }).toList());
    }
    public record ServerInfo(String product, String serverVersion, String driver, String driverVersion) {}
    public record Items<T>(List<T> items, boolean truncated) {}
    public record ColumnInfo(String name, String type, int jdbcType, int size, int scale,
                             boolean nullable, boolean primaryKey, boolean autoIncrement, int position) {}
    public ServerInfo test(Credentials credentials) {
        return connected(credentials, connection -> {
            var meta = connection.getMetaData();
            return new ServerInfo(meta.getDatabaseProductName(), meta.getDatabaseProductVersion(), meta.getDriverName(), meta.getDriverVersion());
        });
    }
    public Items<String> schemas(Credentials credentials) {
        return connected(credentials, connection -> {
            List<String> items = new ArrayList<>();
            boolean catalog = catalogBased(credentials.dbms());
            try (var rows = catalog ? connection.getMetaData().getCatalogs() : connection.getMetaData().getSchemas()) {
                while (rows.next()) {
                    if (items.size() == 200) return new Items<>(items, true);
                    items.add(rows.getString(catalog ? "TABLE_CAT" : "TABLE_SCHEM"));
                }
            }
            return new Items<>(items, false);
        });
    }
    public Items<String> tables(Credentials credentials, String schema) {
        requireName(schema);
        return connected(credentials, connection -> {
            var meta = connection.getMetaData();
            List<String> items = new ArrayList<>();
            try (var rows = meta.getTables(catalogBased(credentials.dbms()) ? schema : null,
                    catalogBased(credentials.dbms()) ? null : literalPattern(meta, schema), "%", new String[]{"TABLE", "VIEW"})) {
                while (rows.next()) {
                    if (items.size() == 200) return new Items<>(items, true);
                    items.add(rows.getString("TABLE_NAME"));
                }
            }
            return new Items<>(items, false);
        });
    }
    public Items<ColumnInfo> columns(Credentials credentials, String schema, String table) {
        requireName(schema); requireName(table);
        return connected(credentials, connection -> {
            var meta = connection.getMetaData();
            String catalog = catalogBased(credentials.dbms()) ? schema : null;
            String namespace = catalogBased(credentials.dbms()) ? null : schema;
            Set<String> keys = new HashSet<>();
            try (var rows = meta.getPrimaryKeys(catalog, namespace, table)) {
                while (rows.next() && keys.size() < 1000) keys.add(rows.getString("COLUMN_NAME"));
            }
            List<ColumnInfo> items = new ArrayList<>();
            try (var rows = meta.getColumns(catalog, namespace == null ? null : literalPattern(meta, namespace), literalPattern(meta, table), "%")) {
                while (rows.next()) {
                    if (items.size() == 500) return new Items<>(items, true);
                    String name = rows.getString("COLUMN_NAME");
                    items.add(new ColumnInfo(name, rows.getString("TYPE_NAME"), rows.getInt("DATA_TYPE"), rows.getInt("COLUMN_SIZE"),
                        rows.getInt("DECIMAL_DIGITS"), rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        keys.contains(name), "YES".equals(rows.getString("IS_AUTOINCREMENT")), rows.getInt("ORDINAL_POSITION")));
                }
            }
            return new Items<>(items, false);
        });
    }
    private boolean catalogBased(Dbms dbms) { return dbms == Dbms.mysql || dbms == Dbms.mariadb; }
    private void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("조회할 스키마·테이블 이름을 선택해주세요.");
    }
    static String literalPattern(DatabaseMetaData meta, String value) throws SQLException {
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            if (value.contains("%") || value.contains("_")) throw new Failure(400, "JDBC_PATTERN", "이 드라이버는 해당 이름의 정확한 메타데이터 조회를 지원하지 않습니다.");
            return value;
        }
        return value.replace(escape, escape + escape).replace("%", escape + "%").replace("_", escape + "_");
    }
    String url(Credentials c) {
        return switch (c.dbms()) {
            case oracle -> "jdbc:oracle:thin:@" + (c.tls() ? "tcps" : "tcp") + "://" + c.host() + ":" + c.port() + "/" + c.database();
            case postgresql -> "jdbc:postgresql://" + c.host() + ":" + c.port() + "/" + c.database();
            case mysql -> "jdbc:mysql://" + c.host() + ":" + c.port() + "/" + c.database();
            case mariadb -> "jdbc:mariadb://" + c.host() + ":" + c.port() + "/" + c.database();
        };
    }
    Properties properties(Credentials c) {
        Properties props = new Properties(); props.setProperty("user", c.username()); props.setProperty("password", c.password());
        if (c.dbms() == Dbms.oracle) {
            props.setProperty("oracle.net.CONNECT_TIMEOUT", "5000"); props.setProperty("oracle.net.OUTBOUND_CONNECT_TIMEOUT", "5000");
            props.setProperty("oracle.jdbc.ReadTimeout", "10000"); props.setProperty("oracle.net.ssl_server_dn_match", "true");
        } else {
            boolean pg = c.dbms() == Dbms.postgresql;
            props.setProperty("connectTimeout", pg ? "5" : "5000"); props.setProperty("socketTimeout", pg ? "10" : "10000");
            props.setProperty(pg ? "sslmode" : "sslMode", c.tls() ? (c.dbms() == Dbms.mysql ? "VERIFY_IDENTITY" : "verify-full") : (c.dbms() == Dbms.mysql ? "DISABLED" : "disable"));
            if (pg) props.setProperty("loginTimeout", "5");
            else { props.setProperty("allowLocalInfile", "false"); props.setProperty("allowLoadLocalInfile", "false"); }
        }
        return props;
    }
    @FunctionalInterface public interface MetadataWork<T> { T run(Connection connection) throws SQLException; }
    private <T> T connected(Credentials credentials, MetadataWork<T> work) { return withConnection(credentials, true, work); }
    public <T> T withConnection(Credentials credentials, boolean readOnly, MetadataWork<T> work) {
        if (!enabled) throw new Failure(503, "JDBC_DISABLED", "서버에서 DB 탐색을 활성화해주세요. TABLEFLOW_JDBC_ENABLED=true 설정이 필요합니다.");
        if (!hosts.contains(credentials.host().toLowerCase(Locale.ROOT))) throw new Failure(403, "JDBC_HOST_DENIED", "서버의 DB 호스트 허용 목록에 등록되지 않았습니다.");
        if (!credentials.tls()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(credentials.host()))
                    if (!address.isLoopbackAddress()) throw new Failure(400, "JDBC_TLS_REQUIRED", "원격 DB 연결에는 인증서 검증 TLS가 필요합니다.");
            } catch (UnknownHostException e) { throw new Failure(400, "JDBC_HOST", "DB 호스트를 찾을 수 없습니다. 호스트 이름을 확인해주세요."); }
        }
        try {
            // Explicit driver selection prevents MariaDB/MySQL URL ambiguity.
            Class.forName(credentials.dbms().driver);
            try (Connection connection = DriverManager.getConnection(url(credentials), properties(credentials))) {
                connection.setReadOnly(readOnly);
                return work.run(connection);
            }
        } catch (ClassNotFoundException e) { throw new Failure(503, "JDBC_DRIVER", "DB 드라이버를 사용할 수 없습니다. 서버 빌드를 확인해주세요."); }
        catch (SQLException e) { throw classify(e); }
    }
    static Failure classify(SQLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException) return new Failure(502, "JDBC_TLS", "TLS 인증서를 확인할 수 없습니다. 서버 인증서·호스트·신뢰 저장소를 확인해주세요.");
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof SQLTimeoutException)
                return new Failure(504, "JDBC_TIMEOUT", "DB 연결 또는 조회 시간이 초과됐습니다. 방화벽과 서버 상태를 확인해주세요.");
        }
        String state = Optional.ofNullable(error.getSQLState()).orElse("");
        if (state.startsWith("28") || error.getErrorCode() == 1017) return new Failure(401, "JDBC_AUTH", "DB 인증에 실패했습니다. 사용자·비밀번호·접속 권한을 확인해주세요.");
        if (state.equals("3D000") || error.getErrorCode() == 1049 || error.getErrorCode() == 12514) return new Failure(400, "JDBC_DATABASE", "DB 또는 Oracle 서비스명을 확인해주세요.");
        if (state.equals("42501")) return new Failure(403, "JDBC_PERMISSION", "메타데이터 조회 권한이 없습니다. 읽기 전용 계정 권한을 확인해주세요.");
        return new Failure(502, "JDBC_CONNECTION", "DB에 연결하거나 메타데이터를 조회하지 못했습니다. 호스트·포트·TLS·DB 권한을 확인해주세요.");
    }
    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
