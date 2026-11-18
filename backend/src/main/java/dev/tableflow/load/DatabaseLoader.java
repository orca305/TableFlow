package dev.tableflow.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.transform.TableEngine;
import dev.tableflow.export.TableExporter.InvalidTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class DatabaseLoader {
    private final boolean enabled;
    private final String url, user, password, sslMode;
    private final List<String> allowed;
    private final TableEngine engine;
    private final ObjectMapper json;
    private static final String RECEIPTS = "tableflow_load_receipts";
    public DatabaseLoader(@Value("${tableflow.load.enabled:false}") boolean enabled,
        @Value("${tableflow.load.url:}") String url, @Value("${tableflow.load.user:}") String user,
        @Value("${tableflow.load.password:}") String password, @Value("${tableflow.load.tables:}") String tables,
        @Value("${tableflow.load.ssl-mode:VERIFY_IDENTITY}") String sslMode, TableEngine engine, ObjectMapper json) {
        this.enabled = enabled; this.url = url; this.user = user; this.password = password; this.engine = engine; this.json = json;
        if (!Set.of("VERIFY_IDENTITY", "VERIFY_CA", "REQUIRED").contains(sslMode)) throw new IllegalArgumentException("Unsupported DB TLS mode");
        this.sslMode = sslMode;
        allowed = Arrays.stream(tables.split(",")).map(String::strip).filter(TableEngine::identifier)
            .filter(name -> !name.equalsIgnoreCase(RECEIPTS)).distinct().toList();
    }
    public record Target(boolean enabled, String dbms, List<String> tables) {}
    public record Plan(String tableName, int rows, List<String> columns) {}
    public record Result(String requestId, String tableName, int insertedRows, boolean replayed) {}
    public Target target() { return new Target(enabled && !allowed.isEmpty(), "mysql", enabled ? allowed : List.of()); }
    private void authorize(String tableName) {
        if (!enabled) throw new LoadFailure(503, "LOAD_DISABLED", "DB 적재가 서버에서 활성화되지 않았습니다.");
        if (!allowed.contains(tableName)) throw new IllegalArgumentException("서버에서 허용한 대상 테이블을 선택해주세요.");
    }
    private Connection connect() throws SQLException {
        // Only server configuration controls the destination; JDBC options are never accepted from HTTP.
        if (!url.startsWith("jdbc:mysql://") || url.contains("?") || url.contains("#"))
            throw new LoadFailure(503, "LOAD_CONFIG", "MySQL 연결 설정을 확인해주세요. URL 옵션은 지원하지 않습니다.");
        Properties properties = new Properties(); properties.setProperty("user", user); properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "30000");
        properties.setProperty("useServerPrepStmts", "true"); properties.setProperty("allowLoadLocalInfile", "false");
        properties.setProperty("autoReconnect", "false"); properties.setProperty("sslMode", sslMode);
        properties.setProperty("allowPublicKeyRetrieval", "false");
        return DriverManager.getConnection(url, properties);
    }
    private List<Column> validate(Table table) {
        ValidationResult validation = engine.validate(table);
        if (!validation.valid()) throw new InvalidTable(validation);
        if (table.rows().isEmpty()) throw new IllegalArgumentException("적재할 데이터 행이 없습니다.");
        return table.columns().stream().filter(Column::included).toList();
    }
    public Plan check(Table table, String name) {
        authorize(name); List<Column> columns = validate(table);
        try (Connection connection = connect()) { inspect(connection, name, columns, table); return new Plan(name, table.rows().size(), columns.stream().map(Column::targetName).toList()); }
        catch (SQLException e) { throw failure(e); }
    }
    public Result load(Table table, String name, String requestId) {
        authorize(name); List<Column> columns = validate(table);
        String hash = fingerprint(table, name);
        boolean committing = false;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                Result previous = receipt(connection, requestId, hash);
                if (previous != null) { connection.rollback(); return previous; }
                try (Statement settings = connection.createStatement()) {
                    settings.execute("SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'");
                    settings.execute("SET SESSION innodb_lock_wait_timeout=10");
                    settings.execute("SET SESSION lock_wait_timeout=10");
                    // Hold a metadata lock until commit: the engine/schema must not change after inspection.
                    try (ResultSet lock = settings.executeQuery("SELECT 1 FROM " + quote(name) + " LIMIT 0")) { }
                }
                inspect(connection, name, columns, table);
                String names = String.join(",", columns.stream().map(column -> quote(column.targetName())).toList());
                String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + quote(name) + " (" + names + ") VALUES (" + placeholders + ")")) {
                    statement.setQueryTimeout(20);
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
                    int batch = 0;
                    for (Table.Row row : table.rows()) {
                        for (int c = 0; c < columns.size(); c++) {
                            Column column = columns.get(c);
                            bind(statement, c + 1, engine.normalize(row.cells().get(column.id()).value(), column.dataType()), column.dataType());
                        }
                        statement.addBatch();
                        if (++batch % 250 == 0) { batch(statement, deadline); statement.clearBatch(); }
                    }
                    if (batch % 250 != 0) batch(statement, deadline);
                }
                try (PreparedStatement receipt = connection.prepareStatement("INSERT INTO " + RECEIPTS + " (request_id,payload_hash,table_name,inserted_rows) VALUES (?,?,?,?)")) {
                    receipt.setString(1, requestId); receipt.setString(2, hash); receipt.setString(3, name); receipt.setInt(4, table.rows().size()); receipt.executeUpdate();
                }
                committing = true; connection.commit();
                return new Result(requestId, name, table.rows().size(), false);
            } catch (SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) { /* A lost connection can leave commit outcome unknown. */ }
                if (committing) throw new LoadFailure(503, "LOAD_OUTCOME_UNKNOWN", "커밋 결과를 확인하지 못했습니다. 같은 요청 ID로 재시도하면 적재 결과를 확인합니다.");
                // Concurrent retries can race on the receipt primary key. Their target writes roll back together.
                if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                    Result previous = receipt(connection, requestId, hash);
                    if (previous != null) return previous;
                }
                throw e;
            } catch (RuntimeException e) { connection.rollback(); throw e; }
        } catch (SQLException e) { throw failure(e); }
    }
    private Result receipt(Connection connection, String id, String hash) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT payload_hash,table_name,inserted_rows FROM " + RECEIPTS + " WHERE request_id=?")) {
            query.setString(1, id);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                if (!hash.equals(result.getString(1))) throw new LoadFailure(409, "LOAD_REQUEST_CONFLICT", "같은 요청 ID에 다른 데이터가 전달되었습니다.");
                return new Result(id, result.getString(2), result.getInt(3), true);
            }
        }
    }
    private void inspect(Connection connection, String name, List<Column> columns, Table table) throws SQLException {
        try (PreparedStatement triggers = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE IN (?,?)")) {
            triggers.setString(1, name); triggers.setString(2, RECEIPTS);
            try (ResultSet found = triggers.executeQuery()) {
                found.next(); if (found.getInt(1) != 0) throw new LoadFailure(422, "LOAD_SCHEMA", "트리거가 있는 테이블은 적재 대상으로 지원하지 않습니다.");
            }
        }
        for (String target : List.of(name, RECEIPTS)) {
            try (PreparedStatement query = connection.prepareStatement("SELECT ENGINE,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                query.setString(1, target);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next() || !"InnoDB".equalsIgnoreCase(result.getString(1)) || !"BASE TABLE".equals(result.getString(2)))
                        throw new LoadFailure(422, "LOAD_SCHEMA", "대상 테이블과 적재 영수증 테이블을 InnoDB로 준비해주세요.");
                }
            }
        }
        Map<String, Column> mapped = new HashMap<>(); columns.forEach(column -> mapped.put(column.targetName().toUpperCase(Locale.ROOT), column));
        Set<String> found = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT COLUMN_NAME,DATA_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_MAXIMUM_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String columnName = result.getString(1); String extra = result.getString(5).toLowerCase(Locale.ROOT);
                    Column column = mapped.get(columnName.toUpperCase(Locale.ROOT));
                    if (column == null) {
                        if ("NO".equals(result.getString(3)) && result.getString(4) == null && !extra.contains("auto_increment") && !extra.contains("generated"))
                            throw new LoadFailure(422, "LOAD_SCHEMA", "필수 대상 컬럼의 매핑이 누락되었습니다: " + columnName);
                        continue;
                    }
                    found.add(column.targetName());
                    if (extra.contains("virtual generated") || extra.contains("stored generated") || !compatible(column.dataType(), result.getString(2)))
                        throw new LoadFailure(422, "LOAD_SCHEMA", "대상 컬럼 자료형과 매핑을 확인해주세요: " + columnName);
                    long length = result.getLong(6); int precision = result.getInt(7), scale = result.getInt(8), fraction = result.getInt(9);
                    for (Table.Row row : table.rows()) {
                        String value = engine.normalize(row.cells().get(column.id()).value(), column.dataType());
                        if (value == null) {
                            if ("NO".equals(result.getString(3))) throw new LoadFailure(422, "LOAD_SCHEMA", "대상 DB의 필수값이 비어 있습니다: " + columnName);
                            continue;
                        }
                        if (column.dataType() == DataType.string && value.codePointCount(0, value.length()) > length)
                            throw new LoadFailure(422, "LOAD_SCHEMA", "대상 DB 문자열 길이를 초과했습니다: " + columnName);
                        if ((column.dataType() == DataType.decimal || column.dataType() == DataType.integer) && result.getString(2).equals("decimal")) {
                            BigDecimal number = new BigDecimal(value).stripTrailingZeros();
                            int integral = number.signum() == 0 ? 0 : Math.max(0, number.precision() - number.scale());
                            if (Math.max(0, number.scale()) > scale || integral > precision - scale)
                                throw new LoadFailure(422, "LOAD_SCHEMA", "대상 DB 숫자 정밀도를 초과했습니다: " + columnName);
                        }
                        if (column.dataType() == DataType.datetime && java.time.LocalDateTime.parse(value).getNano() % (long)Math.pow(10, 9 - fraction) != 0)
                            throw new LoadFailure(422, "LOAD_SCHEMA", "대상 DB 일시 정밀도를 초과했습니다: " + columnName);
                        if ((column.dataType() == DataType.datetime || column.dataType() == DataType.date) && Integer.parseInt(value.substring(0, 4)) < 1000)
                            throw new LoadFailure(422, "LOAD_SCHEMA", "MySQL 날짜는 1000년 이상이어야 합니다.");
                    }
                }
            }
        }
        if (found.size() != columns.size()) throw new LoadFailure(422, "LOAD_SCHEMA", "대상 테이블에 없는 컬럼이 매핑되어 있습니다.");
    }
    private boolean compatible(DataType source, String target) {
        return switch (source) {
            case string -> Set.of("char", "varchar", "text", "tinytext", "mediumtext", "longtext").contains(target);
            case integer -> Set.of("tinyint", "smallint", "mediumint", "int", "bigint", "decimal").contains(target);
            case decimal -> target.equals("decimal");
            case date -> target.equals("date");
            case datetime -> target.equals("datetime");
            case bool -> Set.of("tinyint", "bit").contains(target);
        };
    }
    private void bind(PreparedStatement statement, int index, String value, DataType type) throws SQLException {
        if (value == null) { statement.setNull(index, switch (type) { case string -> Types.VARCHAR; case integer, decimal -> Types.DECIMAL; case date -> Types.DATE; case datetime -> Types.TIMESTAMP; case bool -> Types.BOOLEAN; }); return; }
        switch (type) {
            case string -> statement.setString(index, value);
            case integer, decimal -> statement.setBigDecimal(index, new BigDecimal(value));
            case date -> statement.setObject(index, java.time.LocalDate.parse(value));
            case datetime -> {
                var date = java.time.LocalDateTime.parse(value);
                if (date.getNano() % 1000 != 0) throw new IllegalArgumentException("MySQL 일시는 소수 초 6자리까지 지원합니다.");
                statement.setObject(index, date);
            }
            case bool -> statement.setBoolean(index, value.equals("true"));
        }
    }
    private void rejectWarnings(Statement statement) throws SQLException {
        if (statement.getWarnings() != null) throw new SQLException("Value conversion warning", "22000");
    }
    private void batch(PreparedStatement statement, long deadline) throws SQLException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new LoadFailure(503, "LOAD_TIMEOUT", "적재 제한 시간을 초과해 전체 작업을 취소했습니다.");
        statement.setQueryTimeout((int)Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(remaining)));
        statement.executeBatch(); rejectWarnings(statement);
    }
    private String fingerprint(Table table, String name) {
        try {
            var values = table.rows().stream().map(row -> table.columns().stream().filter(Column::included)
                .map(column -> engine.normalize(row.cells().get(column.id()).value(), column.dataType())).toList()).toList();
            var schema = table.columns().stream().filter(Column::included).map(column -> List.of(column.targetName(), column.dataType().name())).toList();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of(name, schema, values))));
        } catch (Exception e) { throw new IllegalStateException("Unable to fingerprint load"); }
    }
    private String quote(String identifier) { return "`" + identifier + "`"; }
    private LoadFailure failure(SQLException e) {
        String state = e.getSQLState();
        if (e.getErrorCode() == 3819 || (state != null && (state.startsWith("23") || state.startsWith("22"))))
            return new LoadFailure(422, "LOAD_REJECTED", "DB 제약조건 또는 값 변환에 실패해 전체 적재를 취소했습니다.");
        return new LoadFailure(503, "LOAD_FAILED", "DB 적재에 실패했습니다. 연결·권한·테이블 설정을 확인해주세요.");
    }
    public static class LoadFailure extends RuntimeException {
        public final int status; public final String code;
        public LoadFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
