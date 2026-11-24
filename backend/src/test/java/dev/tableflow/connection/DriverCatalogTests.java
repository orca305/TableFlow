package dev.tableflow.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class DriverCatalogTests {
    @TempDir Path directory;
    DriverCatalog catalog() { return new DriverCatalog(directory.toString(), new ObjectMapper()); }
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
