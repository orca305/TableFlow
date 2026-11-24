package dev.tableflow.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer.Dbms;
import dev.tableflow.connection.JdbcExplorer.Failure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.Driver;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarFile;

/** 관리자 반입 JAR. 등록 시에는 코드를 실행하지 않으며 등록 버전마다 독립된 로더를 사용한다. */
@Service
public class DriverCatalog {
    public record Entry(String id, Dbms dbms, String version, String sha256, long bytes, String registeredAt) {}
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final Path directory;
    private final ObjectMapper json;
    private final Map<String, Loaded> loadedDrivers = new HashMap<>();

    public DriverCatalog(@Value("${tableflow.drivers.directory:data/drivers}") String directory, ObjectMapper json) {
        this.directory = Path.of(directory).toAbsolutePath().normalize(); this.json = json;
    }

    public synchronized List<Entry> list() {
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            List<Entry> entries = new ArrayList<>();
            for (Path path : files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).sorted().toList())
                entries.add(json.readValue(Files.readAllBytes(path), Entry.class));
            return List.copyOf(entries);
        } catch (IOException e) { throw failure("드라이버 목록을 읽지 못했습니다."); }
    }

    public synchronized Entry register(Dbms dbms, String version, InputStream input) {
        Objects.requireNonNull(dbms);
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))
            throw new IllegalArgumentException("드라이버 버전은 영문·숫자·점·밑줄·하이픈으로 입력해주세요.");
        Path temporary = null, jar = null;
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
            String hash = hash(temporary);
            Optional<Entry> identical = list().stream().filter(e -> e.dbms() == dbms && e.version().equals(version) && e.sha256().equals(hash)).findFirst();
            if (identical.isPresent()) return identical.get();
            if (list().size() >= 32) throw new IllegalArgumentException("드라이버는 최대 32개까지 등록할 수 있습니다.");
            if (list().stream().anyMatch(e -> e.dbms() == dbms && (e.version().equals(version) || e.sha256().equals(hash))))
                throw new IllegalArgumentException("동일한 버전 또는 파일이 이미 등록되어 있습니다.");
            String id = UUID.randomUUID().toString();
            Entry entry = new Entry(id, dbms, version, hash, Files.size(temporary), Instant.now().toString());
            jar = path(id, ".jar");
            Files.move(temporary, jar, StandardCopyOption.ATOMIC_MOVE);
            // 메타데이터를 마지막에 기록한다. 중단된 업로드는 목록에 노출하지 않는다.
            Path metadata = path(id, ".json");
            Path staged = path(id, ".json.tmp");
            Files.write(staged, json.writeValueAsBytes(entry));
            Files.move(staged, metadata, StandardCopyOption.ATOMIC_MOVE);
            return entry;
        } catch (IOException e) {
            if (jar != null) try { Files.deleteIfExists(jar); } catch (IOException ignored) {}
            throw failure("드라이버 JAR를 등록하지 못했습니다. 파일 형식과 저장 경로를 확인해주세요.");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    public synchronized Loaded open(String id, Dbms dbms) {
        Path jar = path(id, ".jar");
        Entry entry = list().stream().filter(e -> e.id().equals(id) && e.dbms() == dbms).findFirst()
            .orElseThrow(() -> new Failure(404, "JDBC_DRIVER_NOT_FOUND", "선택한 DBMS의 등록 드라이버를 찾을 수 없습니다."));
        SingleJarClassLoader loader = null;
        try {
            if (!entry.sha256().equals(hash(jar))) throw failure("드라이버 파일의 체크섬이 변경되었습니다. 관리자에게 확인해주세요.");
            if (loadedDrivers.containsKey(id)) return loadedDrivers.get(id);
            loader = new SingleJarClassLoader(jar);
            Driver driver = (Driver) Class.forName(dbms.driver, true, loader).getDeclaredConstructor().newInstance();
            Loaded loaded = new Loaded(driver, loader);
            loadedDrivers.put(id, loaded);
            return loaded;
        } catch (IOException | ReflectiveOperationException | LinkageError | ClassCastException e) {
            if (loader != null) try { loader.close(); } catch (IOException ignored) {}
            throw failure("등록 드라이버를 로드하지 못했습니다. Java 버전과 JDBC JAR 호환성을 확인해주세요.");
        }
    }

    public record Loaded(Driver driver, SingleJarClassLoader loader) implements AutoCloseable {
        @Override public void close() throws IOException { loader.close(); }
    }
    @jakarta.annotation.PreDestroy
    public synchronized void close() {
        for (Loaded loaded : loadedDrivers.values()) try { loaded.close(); } catch (IOException ignored) {}
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
