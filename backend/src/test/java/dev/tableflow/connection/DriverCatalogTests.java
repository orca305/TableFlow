package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.state.StateDbTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class DriverCatalogTests {
    @TempDir Path directory;
    static StateDbTestSupport db;
    @BeforeAll static void openDb() { db = new StateDbTestSupport(); }
    @AfterAll static void closeDb() throws Exception { db.close(); }
    @BeforeEach void empty() { JdbcClient.create(db.dataSource()).sql("DELETE FROM jdbc_driver").update(); }
    DriverCatalog catalog() { return new DriverCatalog(directory.toString(), db.dataSource()); }
    Path postgresJar() throws Exception {
        return Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    @Test void registersRealDriverAndLoadsIsolatedVersionWithoutNetwork() throws Exception {
        var catalog = catalog();
        try {
            DriverCatalog.Entry entry;
            try (var input = Files.newInputStream(postgresJar())) { entry = catalog.register(Dbms.postgresql, "42.7.14", input); }
            assertEquals(64, entry.sha256().length());
            assertEquals(entry, catalog().list().getFirst());
            var loaded = catalog.open(entry.id(), Dbms.postgresql);
            assertNotSame(org.postgresql.Driver.class.getClassLoader(), loaded.driver().getClass().getClassLoader());
            assertTrue(loaded.driver().acceptsURL("jdbc:postgresql://localhost/sample"));
            assertSame(loaded, catalog.open(entry.id(), Dbms.postgresql));
            try (var input = Files.newInputStream(postgresJar())) {
                assertThrows(IllegalArgumentException.class, () -> catalog.register(Dbms.postgresql, "duplicate", input));
            }
            assertThrows(Failure.class, () -> catalog.open(entry.id(), Dbms.mysql));
        } finally { catalog.close(); }
    }
    @Test void refusesTamperedFileAndTraversal() throws Exception {
        var catalog = catalog(); DriverCatalog.Entry entry;
        try (var input = Files.newInputStream(postgresJar())) { entry = catalog.register(Dbms.postgresql, "test", input); }
        Files.writeString(directory.resolve(entry.id() + ".jar"), "tampered");
        assertThrows(Failure.class, () -> catalog.open(entry.id(), Dbms.postgresql));
        assertThrows(IllegalArgumentException.class, () -> catalog.open("../other", Dbms.postgresql));
    }
    @Test void anotherServerRestoresTheDriverFromTheDatabaseAndAKeptDriverSurvivesAnOutage() throws Exception {
        var first = catalog(); DriverCatalog.Entry entry;
        try (var input = Files.newInputStream(postgresJar())) { entry = first.register(Dbms.postgresql, "shared", input); }
        try (var input = Files.newInputStream(postgresJar())) { assertEquals(entry, first.register(Dbms.postgresql, "shared", input), "같은 파일·버전은 기존 항목"); }
        var elsewhere = directory.resolve("other-server"); var pool = db.another();
        var second = new DriverCatalog(elsewhere.toString(), pool);
        try {
            assertFalse(Files.exists(elsewhere.resolve(entry.id() + ".jar")));
            var loaded = second.open(entry.id(), Dbms.postgresql);
            assertTrue(loaded.driver().acceptsURL("jdbc:postgresql://localhost/sample"));
            assertEquals(entry.sha256(), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(elsewhere.resolve(entry.id() + ".jar")))));
            pool.close();
            assertSame(loaded, second.open(entry.id(), Dbms.postgresql), "이미 불러온 드라이버는 상태 DB가 안 되어도 쓴다");
            assertThrows(org.springframework.dao.DataAccessException.class, second::list);
        } finally { second.close(); first.close(); }
    }
    @Test void refusesADriverWhoseStoredBytesDoNotMatchTheChecksum() throws Exception {
        var catalog = catalog(); DriverCatalog.Entry entry;
        try (var input = Files.newInputStream(postgresJar())) { entry = catalog.register(Dbms.postgresql, "corrupt", input); }
        JdbcClient.create(db.dataSource()).sql("UPDATE jdbc_driver SET jar = '\\x00'::bytea").update();
        Files.delete(directory.resolve(entry.id() + ".jar"));
        assertThrows(Failure.class, () -> catalog.open(entry.id(), Dbms.postgresql));
        assertFalse(Files.exists(directory.resolve(entry.id() + ".jar")), "맞지 않는 원본으로 사본을 만들지 않는다");
        assertThrows(Failure.class, () -> catalog.open(java.util.UUID.randomUUID().toString(), Dbms.postgresql));
    }

    @Test void rejectsWrongJarAndNeverPublishesPartialUpload() throws Exception {
        var catalog = catalog();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("not-a-driver.txt")); jar.write(1); jar.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () -> catalog.register(Dbms.postgresql, "test", new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(catalog.list().isEmpty());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void ignoresManifestClasspathAndDoesNotReadExternalResources() throws Exception {
        Path dependency = directory.resolve("dependency.jar"), driver = directory.resolve("driver.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(dependency))) {
            out.putNextEntry(new JarEntry("outside.txt")); out.write(1); out.closeEntry();
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Class-Path", dependency.toUri().toString());
        try (var out = new JarOutputStream(Files.newOutputStream(driver), manifest)) {
            out.putNextEntry(new JarEntry("inside.txt")); out.write(1); out.closeEntry();
        }
        try (var loader = new SingleJarClassLoader(driver)) {
            assertNotNull(loader.getResource("inside.txt"));
            assertNull(loader.getResource("outside.txt"));
        }
    }
}
