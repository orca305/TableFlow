package dev.tableflow.connection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer.Credentials;
import dev.tableflow.connection.JdbcExplorer.Dbms;
import dev.tableflow.connection.JdbcExplorer.Failure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * 저장된 DB 연결(주소·포트·DB·사용자)과, 사용자가 선택한 경우에만 암호화한 비밀번호를 서버 로컬 파일에 보관한다.
 * 비밀번호는 AES-256-GCM으로 암호화하며 브라우저로 다시 내려보내지 않는다. 저장된 연결의 접속 정보와 다른 대상으로는
 * 저장된 비밀번호를 사용할 수 없다(다른 서버로 비밀번호가 전송되는 것을 막기 위함).
 * 암호 키는 TABLEFLOW_SECRET_KEY(Base64 32바이트) 또는 처음 실행할 때 만들어지는 키 파일에서 읽는다. 두 파일 모두 git에서 제외된다.
 */
@Service
public class ConnectionStore {
    private static final Logger log = LoggerFactory.getLogger(ConnectionStore.class);
    private static final int MAX_ITEMS = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 브라우저에 보여주는 형태. 비밀번호는 포함하지 않고 저장 여부만 알린다. */
    public record Saved(String id, String name, Dbms dbms, String host, int port, String database, String username, boolean tls,
                        boolean passwordSaved, String savedAt, String driverId) {}
    /** 파일에 기록되는 형태. password는 Base64(IV+암호문)이며 비밀번호를 저장하지 않으면 null이다. */
    record Stored(String id, String name, Dbms dbms, String host, int port, String database, String username, boolean tls,
                  String password, String savedAt, String driverId) {}

    private final boolean enabled, passwordAllowed;
    private final Path file, keyFile; private final String envKey; private final ObjectMapper json;
    private byte[] key;

    public ConnectionStore(@Value("${tableflow.connections.enabled:true}") boolean enabled,
                           @Value("${tableflow.connections.save-password:true}") boolean passwordAllowed,
                           @Value("${tableflow.connections.file:data/connections.json}") String file,
                           @Value("${tableflow.connections.key-file:data/connections.key}") String keyFile,
                           @Value("${tableflow.connections.secret-key:}") String envKey, ObjectMapper json) {
        this.enabled = enabled; this.passwordAllowed = passwordAllowed; this.file = Path.of(file); this.keyFile = Path.of(keyFile);
        this.envKey = envKey == null ? "" : envKey.strip(); this.json = json;
    }
    public boolean enabled() { return enabled; }
    public boolean passwordAllowed() { return enabled && passwordAllowed; }

    public synchronized List<Saved> list() {
        if (!enabled) return List.of();
        return read().stream().map(ConnectionStore::view).toList();
    }

    /**
     * 연결을 저장하거나(같은 이름이 있으면 갱신) 비밀번호 저장 여부를 바꾼다.
     * savePassword가 true인데 password가 비어 있으면 이미 저장된 비밀번호를 유지한다.
     */
    public synchronized Saved save(String name, Credentials c, boolean savePassword) {
        requireEnabled();
        if (name == null || name.isBlank() || name.length() > 60) throw new IllegalArgumentException("연결 이름은 1~60자로 입력해주세요.");
        if (savePassword && !passwordAllowed) throw new Failure(403, "CONNECTION_PASSWORD_DISABLED", "서버에서 비밀번호 저장이 비활성화되어 있습니다.");
        List<Stored> items = new ArrayList<>(read());
        Stored old = items.stream().filter(s -> s.name().equals(name.strip())).findFirst().orElse(null);
        if (old == null && items.size() >= MAX_ITEMS) throw new IllegalArgumentException("저장할 수 있는 연결은 최대 " + MAX_ITEMS + "개입니다.");
        String id = old != null ? old.id() : UUID.randomUUID().toString();
        String secret = null;
        if (savePassword) {
            if (c.password() != null && !c.password().isEmpty()) secret = encrypt(id, c.password());
            else if (old != null && old.password() != null && old.dbms() == c.dbms()
                    && old.host().equalsIgnoreCase(c.host()) && old.port() == c.port() && old.database().equals(c.database())
                    && old.username().equals(c.username()) && old.tls() == c.tls() && Objects.equals(old.driverId(), c.driverId())) secret = old.password();
            else throw new IllegalArgumentException("비밀번호를 입력해야 저장할 수 있습니다.");
        }
        Stored next = new Stored(id, name.strip(), c.dbms(), c.host(), c.port(), c.database(), c.username(), c.tls(), secret, Instant.now().toString(), c.driverId());
        items.removeIf(s -> s.id().equals(id)); items.add(next);
        write(items);
        return view(next);
    }

    public synchronized boolean delete(String id) {
        requireEnabled();
        List<Stored> items = new ArrayList<>(read());
        boolean removed = items.removeIf(s -> s.id().equals(id));
        if (removed) write(items);
        return removed;
    }

    /** 접속 정보가 같고 비밀번호가 저장된 연결이 있으면 그 ID(재개 때 비밀번호를 다시 묻지 않기 위해). 없으면 null. */
    public synchronized String findSavedId(Credentials c) {
        if (!enabled) return null;
        return read().stream().filter(s -> s.password() != null && s.dbms() == c.dbms() && s.host().equalsIgnoreCase(c.host()) && s.port() == c.port()
            && s.database().equals(c.database()) && s.username().equals(c.username()) && s.tls() == c.tls() && Objects.equals(s.driverId(), c.driverId())).map(Stored::id).findFirst().orElse(null);
    }
    /** 요청의 접속 정보가 저장된 연결과 일치할 때만 저장된 비밀번호를 돌려준다. */
    public synchronized String passwordFor(Credentials c) {
        requireEnabled();
        Stored s = read().stream().filter(x -> x.id().equals(c.savedId())).findFirst()
            .orElseThrow(() -> new Failure(404, "CONNECTION_NOT_FOUND", "저장된 연결을 찾을 수 없습니다. 비밀번호를 입력해주세요."));
        if (s.password() == null) throw new Failure(401, "JDBC_AUTH", "이 연결은 비밀번호를 저장하지 않았습니다. 비밀번호를 입력해주세요.");
        if (s.dbms() != c.dbms() || !s.host().equalsIgnoreCase(c.host()) || s.port() != c.port() || !s.database().equals(c.database())
                || !s.username().equals(c.username()) || s.tls() != c.tls() || !Objects.equals(s.driverId(), c.driverId()))
            throw new Failure(403, "CONNECTION_MISMATCH", "저장된 연결과 접속 정보가 달라 저장된 비밀번호를 사용할 수 없습니다. 비밀번호를 입력해주세요.");
        return decrypt(s.id(), s.password());
    }

    // ---------------------------------------------------------------- 내부
    private static Saved view(Stored s) {
        return new Saved(s.id(), s.name(), s.dbms(), s.host(), s.port(), s.database(), s.username(), s.tls(), s.password() != null, s.savedAt(), s.driverId());
    }
    private void requireEnabled() {
        if (!enabled) throw new Failure(503, "CONNECTIONS_DISABLED", "서버에서 연결 저장이 비활성화되어 있습니다.");
    }
    private List<Stored> read() {
        if (!Files.exists(file)) return List.of();
        try { return json.readValue(Files.readAllBytes(file), new TypeReference<List<Stored>>() {}); }
        catch (IOException e) { throw new Failure(500, "CONNECTION_STORE", "저장된 연결 파일을 읽지 못했습니다."); }
    }
    private void write(List<Stored> items) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(items));
            restrict(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new Failure(500, "CONNECTION_STORE", "연결을 저장하지 못했습니다."); }
    }
    /** 현재 사용자만 읽고 쓰도록 제한한다(지원하지 않는 파일 시스템에서는 가능한 범위에서만). */
    private static void restrict(Path path) {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException | IOException e) {
            var f = path.toFile(); f.setReadable(false, false); f.setWritable(false, false); f.setReadable(true, true); f.setWritable(true, true);
        }
    }
    private byte[] key() {
        if (key != null) return key;
        try {
            byte[] raw;
            if (!envKey.isEmpty()) raw = Base64.getDecoder().decode(envKey);
            else if (Files.exists(keyFile)) raw = Base64.getDecoder().decode(Files.readString(keyFile).strip());
            else {
                raw = new byte[32]; RANDOM.nextBytes(raw);
                if (keyFile.getParent() != null) Files.createDirectories(keyFile.getParent());
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw)); restrict(keyFile);
                log.info("연결 저장용 암호 키 파일을 새로 만들었습니다.");
            }
            if (raw.length != 32) throw new IllegalStateException("키 길이가 32바이트가 아닙니다.");
            return key = raw;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw new Failure(500, "CONNECTION_KEY", "연결 저장용 암호 키를 읽지 못했습니다. TABLEFLOW_SECRET_KEY(Base64 32바이트)를 확인하세요.");
        }
    }
    private String encrypt(String id, String plain) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8)); // 다른 연결 항목으로 옮겨 붙인 암호문은 복호화되지 않는다
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length]; System.arraycopy(iv, 0, out, 0, iv.length); System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (java.security.GeneralSecurityException e) { throw new Failure(500, "CONNECTION_KEY", "비밀번호를 암호화하지 못했습니다."); }
    }
    private String decrypt(String id, String stored) {
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(in, 0, 12)));
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new Failure(500, "CONNECTION_KEY", "저장된 비밀번호를 복호화하지 못했습니다. 키가 바뀌었다면 비밀번호를 다시 저장해주세요.");
        }
    }
}
