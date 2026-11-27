package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.Dbms;
import dev.tableflow.connection.JdbcExplorer.Failure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.jar.JarFile;

/** 관리자 반입 JAR. 원본은 상태 DB에, 각 서버의 폴더에는 사본을 둔다. 등록 시에는 코드를 실행하지 않으며 등록 버전마다 독립된 로더를 사용한다. */
@Service
public class DriverCatalog {
    public record Entry(String id, Dbms dbms, String version, String sha256, long bytes, String registeredAt) {}
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_DRIVERS = 32;
    private static final String SELECT = "SELECT id, dbms, version, sha256, size_bytes, registered_at FROM jdbc_driver";
    private final Path directory;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Map<String, Cached> loadedDrivers = new HashMap<>();
    private record Cached(Entry entry, Loaded loaded) {}

    public DriverCatalog(@Value("${tableflow.drivers.directory:data/drivers}") String directory, DataSource dataSource) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.jdbc = JdbcClient.create(dataSource); this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public List<Entry> list() { return jdbc.sql(SELECT + " ORDER BY id::text").query(DriverCatalog::entry).list(); }

    public synchronized Entry register(Dbms dbms, String version, InputStream input) {
        Objects.requireNonNull(dbms);
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))
            throw new IllegalArgumentException("드라이버 버전은 영문·숫자·점·밑줄·하이픈으로 입력해주세요.");
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".upload-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192]; long count = 0; int size;
                while ((size = input.read(buffer)) != -1) {
                    count += size;
                    if (count > MAX_BYTES) throw new IllegalArgumentException("드라이버 JAR는 최대 10MB입니다.");
                    out.write(buffer, 0, size);
                }
            }
            try (JarFile archive = new JarFile(temporary.toFile())) {
                var manifest = archive.getJarEntry("META-INF/MANIFEST.MF");
                if (manifest != null && (manifest.getSize() < 0 || manifest.getSize() > 128 * 1024))
                    throw new IllegalArgumentException("드라이버 Manifest 크기가 허용 범위를 초과했습니다.");
                if (archive.getJarEntry(dbms.driver.replace('.', '/') + ".class") == null)
                    throw new IllegalArgumentException("선택한 DBMS의 JDBC 드라이버 클래스가 없는 JAR입니다.");
            }
            byte[] content = Files.readAllBytes(temporary); String hash = hash(temporary);
            Entry fresh = new Entry(UUID.randomUUID().toString(), dbms, version, hash, content.length, Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
            Entry entry = transaction.execute(status -> store(fresh, content));
            if (entry == fresh) {
                try { Files.move(temporary, path(fresh.id(), ".jar"), StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException ignored) { /* 사본이 없으면 처음 사용할 때 DB에서 만든다 */ }
            }
            return entry;
        } catch (IOException e) {
            throw failure("드라이버 JAR를 등록하지 못했습니다. 파일 형식과 저장 경로를 확인해주세요.");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }
    /** 같은 파일을 같은 버전으로 다시 올리면 기존 항목을 돌려준다. 개수·중복 검사와 저장은 여러 서버 사이에서도 차례로 처리한다. */
    private Entry store(Entry fresh, byte[] content) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('tableflow.jdbc_driver'))").query((rs, i) -> 0).list();
        List<Entry> existing = list();
        Optional<Entry> identical = existing.stream().filter(e -> e.dbms() == fresh.dbms() && e.version().equals(fresh.version()) && e.sha256().equals(fresh.sha256())).findFirst();
        if (identical.isPresent()) return identical.get();
        if (existing.size() >= MAX_DRIVERS) throw new IllegalArgumentException("드라이버는 최대 " + MAX_DRIVERS + "개까지 등록할 수 있습니다.");
        if (existing.stream().anyMatch(e -> e.dbms() == fresh.dbms() && (e.version().equals(fresh.version()) || e.sha256().equals(fresh.sha256()))))
            throw new IllegalArgumentException("동일한 버전 또는 파일이 이미 등록되어 있습니다.");
        jdbc.sql("INSERT INTO jdbc_driver (id, dbms, version, sha256, size_bytes, jar, registered_at) VALUES (:id, :dbms, :version, :sha256, :bytes, :jar, :registeredAt)")
            .param("id", UUID.fromString(fresh.id())).param("dbms", fresh.dbms().name()).param("version", fresh.version()).param("sha256", fresh.sha256())
            .param("bytes", fresh.bytes()).param("jar", content, Types.BINARY).param("registeredAt", OffsetDateTime.ofInstant(Instant.parse(fresh.registeredAt()), ZoneOffset.UTC)).update();
        return fresh;
    }

    public synchronized Loaded open(String id, Dbms dbms) {
        Path jar = path(id, ".jar");
        Cached cached = loadedDrivers.get(id);
        // 이미 불러온 드라이버는 DB를 다시 조회하지 않는다(항목은 바뀌지 않으므로 상태 DB가 잠시 안 되어도 계속 쓴다).
        Entry entry = cached != null ? cached.entry() : jdbc.sql(SELECT + " WHERE id = :id").param("id", UUID.fromString(id)).query(DriverCatalog::entry).optional().orElse(null);
        if (entry == null || entry.dbms() != dbms) throw new Failure(404, "JDBC_DRIVER_NOT_FOUND", "선택한 DBMS의 등록 드라이버를 찾을 수 없습니다.");
        SingleJarClassLoader loader = null;
        try {
            if (!Files.exists(jar)) restore(entry, jar);
            if (!entry.sha256().equals(hash(jar))) throw failure("드라이버 파일의 체크섬이 변경되었습니다. 관리자에게 확인해주세요.");
            if (cached != null) return cached.loaded();
            loader = new SingleJarClassLoader(jar);
            Driver driver = (Driver) Class.forName(dbms.driver, true, loader).getDeclaredConstructor().newInstance();
            Loaded loaded = new Loaded(driver, loader);
            loadedDrivers.put(id, new Cached(entry, loaded));
            return loaded;
        } catch (IOException | ReflectiveOperationException | LinkageError | ClassCastException e) {
            if (loader != null) try { loader.close(); } catch (IOException ignored) {}
            throw failure("등록 드라이버를 로드하지 못했습니다. Java 버전과 JDBC JAR 호환성을 확인해주세요.");
        }
    }
    /** 이 서버에 사본이 없으면(다른 서버에서 등록했거나 폴더를 비운 경우) DB의 원본으로 만든다. */
    private void restore(Entry entry, Path jar) throws IOException {
        byte[] content = jdbc.sql("SELECT jar FROM jdbc_driver WHERE id = :id").param("id", UUID.fromString(entry.id())).query((rs, i) -> rs.getBytes(1)).single();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".restore-", ".tmp");
        try {
            Files.write(temporary, content);
            if (!entry.sha256().equals(hash(temporary))) throw failure("저장된 드라이버의 체크섬이 맞지 않습니다. 관리자에게 확인해주세요.");
            Files.move(temporary, jar, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static Entry entry(ResultSet rs, int index) throws SQLException {
        return new Entry(rs.getString("id"), Dbms.valueOf(rs.getString("dbms")), rs.getString("version"), rs.getString("sha256"), rs.getLong("size_bytes"),
            rs.getObject("registered_at", OffsetDateTime.class).toInstant().toString());
    }

    public record Loaded(Driver driver, SingleJarClassLoader loader) implements AutoCloseable {
        @Override public void close() throws IOException { loader.close(); }
    }
    @jakarta.annotation.PreDestroy
    public synchronized void close() {
        for (Cached cached : loadedDrivers.values()) try { cached.loaded().close(); } catch (IOException ignored) {}
        loadedDrivers.clear();
    }
    private Path path(String id, String suffix) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("드라이버 ID가 올바르지 않습니다.");
        return directory.resolve(id + suffix);
    }
    private static String hash(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int size;
            while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Failure failure(String message) { return new Failure(503, "JDBC_DRIVER_STORE", message); }
}
