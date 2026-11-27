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
    @Value("${tableflow.jdbc.sqlserver-enabled:true}") private boolean sqlserverEnabled = true;
    @Value("${tableflow.jdbc.tibero-enabled:false}") private boolean tiberoEnabled;
    @Value("${tableflow.jdbc.cubrid-enabled:true}") private boolean cubridEnabled = true;
    private final Set<String> hosts;
    private ConnectionStore store;
    private DriverCatalog driverCatalog;
    @org.springframework.beans.factory.annotation.Autowired(required = false) void setDriverCatalog(DriverCatalog catalog) { this.driverCatalog = catalog; }
    @org.springframework.beans.factory.annotation.Autowired(required = false) void setStore(ConnectionStore store) { this.store = store; }
    public JdbcExplorer(@Value("${tableflow.jdbc.enabled:false}") boolean enabled,
                        @Value("${tableflow.jdbc.allowed-hosts:localhost,127.0.0.1}") String allowedHosts) {
        this.enabled = enabled;
        this.hosts = new HashSet<>(Arrays.stream(allowedHosts.split(",")).map(String::strip).map(s -> s.toLowerCase(Locale.ROOT)).toList());
    }
    public enum Dbms {
        oracle("oracle.jdbc.OracleDriver", 1521), postgresql("org.postgresql.Driver", 5432),
        mysql("com.mysql.cj.jdbc.Driver", 3306), mariadb("org.mariadb.jdbc.Driver", 3306), sqlserver("com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433),
        tibero("com.tmax.tibero.jdbc.TbDriver", 8629), cubrid("cubrid.jdbc.driver.CUBRIDDriver", 33000);
        final String driver; final int port;
        Dbms(String driver, int port) { this.driver = driver; this.port = port; }
    }
    public record Credentials(@NotNull Dbms dbms,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9.-]{0,252}") String host,
        @Min(1) @Max(65535) int port,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_][A-Za-z0-9_.$-]{0,127}") String database,
        @NotBlank @Size(max=128) String username, @NotNull @Size(max=1024) String password,
        boolean tls, @Pattern(regexp="[0-9a-fA-F-]{36}") String savedId,
        @Pattern(regexp="[0-9a-f-]{36}") String driverId) {
        public Credentials(Dbms dbms, String host, int port, String database, String username, String password, boolean tls, String savedId) {
            this(dbms, host, port, database, username, password, tls, savedId, null);
        }
        /** 저장된 연결을 쓰지 않는 일반 접속. */
        public Credentials(Dbms dbms, String host, int port, String database, String username, String password, boolean tls) {
            this(dbms, host, port, database, username, password, tls, null);
        }
        /** 비밀번호가 비어 있고 savedId가 있으면 저장된 연결의 비밀번호를 서버에서 복호화해 사용한다. */
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record DriverCapabilities(boolean browse, boolean load, boolean createTable, boolean preserveKeys, boolean experimental, boolean verifiedTls) {}
    public record DriverInfo(Dbms dbms, int defaultPort, boolean available, String version, DriverCapabilities capabilities) {}
    private DriverCapabilities capabilities(Dbms db) {
        return switch (db) {
            case oracle, postgresql, mysql, mariadb -> new DriverCapabilities(true, true, true, true, false, true);
            case sqlserver -> new DriverCapabilities(sqlserverEnabled, sqlserverEnabled, sqlserverEnabled, sqlserverEnabled, false, true);
            case tibero -> new DriverCapabilities(tiberoEnabled, tiberoEnabled, tiberoEnabled, false, true, false);
            case cubrid -> new DriverCapabilities(cubridEnabled, cubridEnabled, cubridEnabled, false, true, false);
        };
    }
    public record Registry(boolean enabled, List<DriverInfo> drivers) {}
    public Registry registry() {
        return new Registry(enabled, Arrays.stream(Dbms.values()).map(db -> {
            try {
                Driver driver = (Driver) Class.forName(db.driver).getDeclaredConstructor().newInstance();
                return new DriverInfo(db, db.port, true, driver.getMajorVersion() + "." + driver.getMinorVersion(), capabilities(db));
            } catch (ReflectiveOperationException | LinkageError e) { return new DriverInfo(db, db.port, false, "", capabilities(db)); }
        }).toList());
    }
    public java.util.List<DriverCatalog.Entry> installedDrivers() { return driverCatalog == null ? java.util.List.of() : driverCatalog.list(); }
    public record ServerInfo(String product, String serverVersion, String driver, String driverVersion) {}
    public record Items<T>(List<T> items, boolean truncated) {}
    public record ColumnInfo(String name, String type, int jdbcType, int size, int scale,
                             boolean nullable, boolean primaryKey, boolean autoIncrement, int position, boolean foreignKey) {}
    public ServerInfo test(Credentials credentials) {
        return connected(credentials, connection -> {
            var meta = connection.getMetaData();
            return new ServerInfo(meta.getDatabaseProductName(), meta.getDatabaseProductVersion(), meta.getDriverName(), meta.getDriverVersion());
        });
    }
    public Items<String> schemas(Credentials credentials) {
        return connected(credentials, connection -> {
            List<String> items = new ArrayList<>();
            if (credentials.dbms() == Dbms.cubrid) {
                items.add(credentials.username().toUpperCase(Locale.ROOT));
                try (var st = connection.createStatement()) {
                    st.setQueryTimeout(20);
                    try (var rows = st.executeQuery("SELECT DISTINCT owner_name FROM db_class ORDER BY owner_name")) {
                        while (rows.next()) { String owner = rows.getString(1); if (!items.contains(owner)) { if (items.size() == 200) return new Items<>(items, true); items.add(owner); } }
                    }
                }
                return new Items<>(items, false);
            }
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
        return cached(() -> connected(credentials, connection -> {
            var meta = connection.getMetaData();
            List<String> items = new ArrayList<>();
            try (var rows = meta.getTables(catalogBased(credentials.dbms()) ? schema : null,
                    catalogBased(credentials.dbms()) ? null : literalPattern(meta, schema),
                    credentials.dbms() == Dbms.cubrid ? SqlDialect.metadataTable(credentials.dbms(), literalPattern(meta, schema), "%") : "%",
                    new String[]{"TABLE", "VIEW"})) {
                while (rows.next()) {
                    if (items.size() == 200) return new Items<>(items, true);
                    items.add(SqlDialect.metadataName(credentials.dbms(), schema, rows.getString("TABLE_NAME")));
                }
            }
            return new Items<>(items, false);
        }), "tables", credentials, schema);
    }
    public Items<ColumnInfo> columns(Credentials credentials, String schema, String table) {
        requireName(schema); requireName(table);
        return cached(() -> connected(credentials, connection -> {
            var meta = connection.getMetaData();
            String catalog = catalogBased(credentials.dbms()) ? schema : null;
            String namespace = catalogBased(credentials.dbms()) ? null : schema;
            Set<String> keys = new HashSet<>();
            try (var rows = meta.getPrimaryKeys(catalog, namespace, SqlDialect.metadataTable(credentials.dbms(), schema, table))) {
                while (rows.next() && keys.size() < 1000) keys.add(rows.getString("COLUMN_NAME"));
            }
            Set<String> foreign = new HashSet<>();
            try (var rows = meta.getImportedKeys(catalog, namespace, SqlDialect.metadataTable(credentials.dbms(), schema, table))) { // 이 테이블이 다른 테이블을 참조하는 컬럼
                while (rows != null && rows.next() && foreign.size() < 1000) foreign.add(rows.getString("FKCOLUMN_NAME"));
            } catch (SQLException ignored) { /* 드라이버가 지원하지 않으면 FK 표시만 생략 */ }
            List<ColumnInfo> items = new ArrayList<>();
            Set<String> auto = JdbcMetadata.autoColumns(connection, credentials.dbms(), schema, table);
            try (var rows = meta.getColumns(catalog, namespace == null ? null : literalPattern(meta, namespace), literalPattern(meta, SqlDialect.metadataTable(credentials.dbms(), schema, table)), "%")) {
                while (rows.next()) {
                    if (items.size() == 500) return new Items<>(items, true);
                    String name = rows.getString("COLUMN_NAME");
                    items.add(new ColumnInfo(name, rows.getString("TYPE_NAME"), rows.getInt("DATA_TYPE"), rows.getInt("COLUMN_SIZE"),
                        rows.getInt("DECIMAL_DIGITS"), rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        keys.contains(name), (credentials.dbms() == Dbms.cubrid ? auto.contains(rows.getString("COLUMN_NAME")) : "YES".equals(rows.getString("IS_AUTOINCREMENT"))), rows.getInt("ORDINAL_POSITION"), foreign.contains(name)));
                }
            }
            return new Items<>(items, false);
        }), "columns", credentials, schema, table);
    }
    public record IndexInfo(String name, boolean unique, List<String> columns) {}
    public record ForeignKeyInfo(String name, List<String> columns, String parentSchema, String parentTable, List<String> parentColumns) {}
    /** 테이블의 보조 인덱스(기본 키 인덱스 제외), 외래 키, 컬럼 기본값(원문). 대상 테이블 생성 시 복제하는 데 쓴다. */
    public record TableExtras(List<IndexInfo> indexes, List<ForeignKeyInfo> foreignKeys, Map<String, String> defaults) {}
    public TableExtras extras(Credentials credentials, String schema, String table) {
        requireName(schema); requireName(table);
        return cached(() -> connected(credentials, connection -> {
            var meta = connection.getMetaData(); boolean catalogBased = catalogBased(credentials.dbms());
            String catalog = catalogBased ? schema : null, namespace = catalogBased ? null : schema;
            Set<String> pk = new HashSet<>();
            try (var rows = meta.getPrimaryKeys(catalog, namespace, SqlDialect.metadataTable(credentials.dbms(), schema, table))) { while (rows.next()) pk.add(rows.getString("COLUMN_NAME")); }
            // 인덱스: 이름별로 모으고 ORDINAL_POSITION 순으로 정렬
            Map<String, TreeMap<Integer, String>> cols = new LinkedHashMap<>(); Map<String, Boolean> unique = new HashMap<>();
            try (var rows = meta.getIndexInfo(catalog, namespace, SqlDialect.metadataTable(credentials.dbms(), schema, table), false, true)) {
                while (rows != null && rows.next()) {
                    String name = rows.getString("INDEX_NAME"), column = rows.getString("COLUMN_NAME");
                    if (name == null || column == null || rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                    cols.computeIfAbsent(name, k -> new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"), column);
                    unique.put(name, !rows.getBoolean("NON_UNIQUE"));
                }
            } catch (SQLException ignored) { /* 인덱스 정보를 못 읽으면 복제만 건너뛴다 */ }
            List<IndexInfo> indexes = new ArrayList<>();
            for (var e : cols.entrySet()) {
                List<String> list = new ArrayList<>(e.getValue().values());
                if (unique.get(e.getKey()) && new HashSet<>(list).equals(pk)) continue; // 기본 키가 만든 인덱스
                indexes.add(new IndexInfo(e.getKey(), unique.get(e.getKey()), list));
            }
            // 외래 키: FK 이름별로 KEY_SEQ 순서의 컬럼 쌍
            Map<String, TreeMap<Integer, String[]>> fks = new LinkedHashMap<>(); Map<String, String[]> parents = new HashMap<>();
            try (var rows = meta.getImportedKeys(catalog, namespace, SqlDialect.metadataTable(credentials.dbms(), schema, table))) {
                while (rows != null && rows.next()) {
                    String name = Optional.ofNullable(rows.getString("FK_NAME")).orElse("fk_" + rows.getString("PKTABLE_NAME"));
                    fks.computeIfAbsent(name, k -> new TreeMap<>()).put(rows.getInt("KEY_SEQ"), new String[]{rows.getString("FKCOLUMN_NAME"), rows.getString("PKCOLUMN_NAME")});
                    String parentSchema = catalogBased ? rows.getString("PKTABLE_CAT") : rows.getString("PKTABLE_SCHEM");
                    String parentTable = rows.getString("PKTABLE_NAME");
                    if (credentials.dbms() == Dbms.cubrid) {
                        int dot = parentTable.indexOf('.');
                        if (dot >= 0) { parentSchema = parentTable.substring(0, dot).toUpperCase(Locale.ROOT); parentTable = parentTable.substring(dot + 1); }
                        else if (parentSchema == null) parentSchema = schema;
                    }
                    parents.put(name, new String[]{parentSchema, parentTable});
                }
            } catch (SQLException ignored) { /* 외래 키를 못 읽으면 복제만 건너뛴다 */ }
            List<ForeignKeyInfo> foreign = new ArrayList<>();
            for (var e : fks.entrySet()) {
                var pair = e.getValue().values();
                foreign.add(new ForeignKeyInfo(e.getKey(), pair.stream().map(p -> p[0]).toList(), parents.get(e.getKey())[0], parents.get(e.getKey())[1], pair.stream().map(p -> p[1]).toList()));
            }
            // 기본값(원문). Oracle은 COLUMN_DEF가 LONG이라 컬럼 번호 순서대로 읽어야 한다.
            Map<String, String> defaults = new LinkedHashMap<>();
            try (var rows = meta.getColumns(catalog, namespace == null ? null : literalPattern(meta, namespace), literalPattern(meta, SqlDialect.metadataTable(credentials.dbms(), schema, table)), "%")) {
                while (rows.next()) { String name = rows.getString(4); String def = rows.getString(13); if (def != null && !def.isBlank()) defaults.put(name, def.strip()); }
            }
            return new TableExtras(indexes, foreign, defaults);
        }), "extras", credentials, schema, table);
    }
    private boolean catalogBased(Dbms dbms) { return SqlDialect.catalogBased(dbms); }
    private void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("조회할 스키마·테이블 이름을 선택해주세요.");
    }
    public static String literalPattern(DatabaseMetaData meta, String value) throws SQLException {
        String escape = meta.getSearchStringEscape();
        // CUBRID JDBC 11.2는 빈 문자열을 반환하지만 서버의 schema-info 패턴은 역슬래시 이스케이프를 지원한다.
        if ((escape == null || escape.isEmpty()) && "CUBRID".equalsIgnoreCase(meta.getDatabaseProductName())) escape = "\\";
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
            case tibero -> "jdbc:tibero:thin:@" + c.host() + ":" + c.port() + ":" + c.database();
            case cubrid -> "jdbc:cubrid:" + c.host() + ":" + c.port() + ":" + c.database() + ":::?connectTimeout=5&queryTimeout=30&charSet=utf-8";
            case sqlserver -> "jdbc:sqlserver://" + c.host() + ":" + c.port() + ";databaseName=" + c.database();
        };
    }
    private String password(Credentials c) {
        if (c.password() != null && !c.password().isEmpty()) return c.password();
        if (c.savedId() == null) return "";
        if (store == null) throw new Failure(503, "CONNECTIONS_DISABLED", "서버에서 연결 저장이 비활성화되어 있습니다. 비밀번호를 입력해주세요.");
        return store.passwordFor(c);
    }
    Properties properties(Credentials c) {
        Properties props = new Properties(); props.setProperty("user", c.username()); props.setProperty("password", password(c));
        if (c.dbms() == Dbms.oracle) {
            props.setProperty("oracle.net.CONNECT_TIMEOUT", "5000"); props.setProperty("oracle.net.OUTBOUND_CONNECT_TIMEOUT", "5000");
            props.setProperty("oracle.jdbc.ReadTimeout", "10000"); props.setProperty("oracle.net.ssl_server_dn_match", "true");
        } else if (c.dbms() == Dbms.tibero) {
            props.setProperty("login_timeout", "5000"); props.setProperty("read_timeout", "10000");
        } else if (c.dbms() == Dbms.cubrid) {
            props.setProperty("connectTimeout", "5"); props.setProperty("queryTimeout", "30");
            props.setProperty("charSet", "utf-8"); props.setProperty("logOnException", "false");
        } else if (c.dbms() == Dbms.sqlserver) {
            props.setProperty("encrypt", Boolean.toString(c.tls()));
            props.setProperty("trustServerCertificate", "false");
            props.setProperty("loginTimeout", "5"); props.setProperty("socketTimeout", "10000");
            props.setProperty("applicationName", "TableFlow"); props.setProperty("sendStringParametersAsUnicode", "true");
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
        boolean active = switch (credentials.dbms()) {
            case sqlserver -> sqlserverEnabled; case tibero -> tiberoEnabled; case cubrid -> cubridEnabled; default -> true;
        };
        if (!active) throw new Failure(503, "JDBC_EXPERIMENTAL_DISABLED", "이 DBMS는 서버에서 비활성화되어 있습니다. 해당 DBMS 활성화 설정을 확인해주세요.");
        if (credentials.tls() && (credentials.dbms() == Dbms.tibero || credentials.dbms() == Dbms.cubrid))
            throw new Failure(400, "JDBC_TLS_UNVERIFIED", "이 DBMS의 인증서·호스트 검증 TLS는 아직 지원하지 않습니다. 암호화 설정만으로 검증을 대체하지 않으며 현재는 루프백 테스트 연결만 허용합니다.");
        if (!enabled) throw new Failure(503, "JDBC_DISABLED", "서버에서 DB 탐색을 활성화해주세요. TABLEFLOW_JDBC_ENABLED=true 설정이 필요합니다.");
        if (!hosts.contains(credentials.host().toLowerCase(Locale.ROOT))) throw new Failure(403, "JDBC_HOST_DENIED", "서버의 DB 호스트 허용 목록에 등록되지 않았습니다.");
        if (!credentials.tls()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(credentials.host()))
                    if (!address.isLoopbackAddress()) throw new Failure(400, "JDBC_TLS_REQUIRED", "원격 DB 연결에는 인증서 검증 TLS가 필요합니다.");
            } catch (UnknownHostException e) { throw new Failure(400, "JDBC_HOST", "DB 호스트를 찾을 수 없습니다. 호스트 이름을 확인해주세요."); }
        }
        Session reuse = readOnly ? session.get() : null;
        try {
            if (reuse == null) try (Connection connection = open(credentials, readOnly)) { return work.run(connection); }
            Connection connection = reuse.connections.get(credentials);
            if (connection == null) { connection = open(credentials, true); reuse.connections.put(credentials, connection); }
            boolean healthy = false;
            try { T result = work.run(connection); healthy = true; return result; }
            // 실패한 연결은 상태를 믿을 수 없으므로 버리고 다음 조회에서 새로 연다.
            finally { if (!healthy && reuse.connections.remove(credentials, connection)) closeQuietly(connection); }
        } catch (ClassNotFoundException e) { throw new Failure(503, "JDBC_DRIVER", "DB 드라이버를 사용할 수 없습니다. 서버 빌드를 확인해주세요."); }
        catch (SQLException e) { throw classify(e); }
    }
    private Connection open(Credentials credentials, boolean readOnly) throws SQLException, ClassNotFoundException {
        Connection connection;
        if (credentials.driverId() != null) {
            if (driverCatalog == null) throw new Failure(503, "JDBC_DRIVER", "등록 드라이버 기능을 사용할 수 없습니다.");
            var loaded = driverCatalog.open(credentials.driverId(), credentials.dbms());
            connection = loaded.driver().connect(url(credentials), properties(credentials));
            if (connection == null) throw new Failure(503, "JDBC_DRIVER", "선택한 드라이버가 접속 URL을 지원하지 않습니다.");
        } else {
            // Explicit driver selection prevents MariaDB/MySQL URL ambiguity.
            Class.forName(credentials.dbms().driver);
            connection = DriverManager.getConnection(url(credentials), properties(credentials));
        }
        boolean ready = false;
        try { connection.setReadOnly(readOnly); ready = true; return connection; }
        finally { if (!ready) closeQuietly(connection); }
    }
    private static void closeQuietly(Connection connection) { try { connection.close(); } catch (SQLException ignored) { /* 닫기 실패는 조회 결과에 영향이 없다 */ } }

    private static final class Session { final Map<Credentials, Connection> connections = new HashMap<>(); final Map<List<Object>, Object> metadata = new HashMap<>(); }
    private final ThreadLocal<Session> session = new ThreadLocal<>();
    /**
     * 여러 테이블을 조회만 하는 작업(사전 점검·검증 등) 동안 읽기 전용 연결과 메타데이터(테이블·컬럼·인덱스/FK) 결과를 재사용한다.
     * 호출한 스레드에만 적용되고 작업이 끝나면 닫고 버린다. 그 사이에 DDL을 실행하는 작업에는 쓰지 않는다.
     */
    public <T> T reusing(java.util.function.Supplier<T> work) {
        if (session.get() != null) return work.get();
        Session current = new Session(); session.set(current);
        try { return work.get(); }
        finally { session.remove(); current.connections.values().forEach(JdbcExplorer::closeQuietly); }
    }
    @SuppressWarnings("unchecked")
    private <T> T cached(java.util.function.Supplier<T> load, Object... key) {
        Session current = session.get();
        if (current == null) return load.get();
        List<Object> id = Arrays.asList(key); T hit = (T) current.metadata.get(id);
        if (hit == null) { hit = load.get(); current.metadata.put(id, hit); }
        return hit;
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
