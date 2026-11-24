package dev.tableflow.connection;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** 반입한 파일만 읽는다. Manifest Class-Path를 따라 외부 파일이나 URL에 접근하지 않는다. */
final class SingleJarClassLoader extends SecureClassLoader implements Closeable {
    private final JarFile jar;
    private final URL url;
    private final Attributes attributes;
    SingleJarClassLoader(Path path) throws IOException {
        super(ClassLoader.getPlatformClassLoader());
        jar = new JarFile(path.toFile()); url = path.toUri().toURL();
        Manifest manifest = jar.getManifest();
        attributes = manifest == null ? new Attributes() : manifest.getMainAttributes();
    }
    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        JarEntry entry = jar.getJarEntry(name.replace('.', '/') + ".class");
        if (entry == null) throw new ClassNotFoundException(name);
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length > 10 * 1024 * 1024) throw new ClassNotFoundException(name);
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String pkg = name.substring(0, dot);
                if (getDefinedPackage(pkg) == null) definePackage(pkg,
                    attributes.getValue("Specification-Title"), attributes.getValue("Specification-Version"), attributes.getValue("Specification-Vendor"),
                    attributes.getValue("Implementation-Title"), attributes.getValue("Implementation-Version"), attributes.getValue("Implementation-Vendor"), null);
            }
            return defineClass(name, bytes, 0, bytes.length, new CodeSource(url, entry.getCertificates()));
        } catch (IOException e) { throw new ClassNotFoundException(name, e); }
    }
    @Override protected URL findResource(String name) {
        if (jar.getJarEntry(name) == null) return null;
        try {
            String location = "jar:" + url.toExternalForm() + "!" + new URI(null, null, "/" + name, null).toASCIIString();
            // JarURLConnection의 전역 캐시는 Windows에서 파일 잠금을 남긴다. 현재 로더의 JarFile만 사용한다.
            return URL.of(URI.create(location), new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL resource) {
                    return new URLConnection(resource) {
                        @Override public void connect() { connected = true; }
                        @Override public InputStream getInputStream() throws IOException { return jar.getInputStream(jar.getJarEntry(name)); }
                    };
                }
            });
        }
        catch (URISyntaxException | MalformedURLException e) { return null; }
    }
    @Override protected Enumeration<URL> findResources(String name) {
        URL resource = findResource(name);
        return Collections.enumeration(resource == null ? List.of() : List.of(resource));
    }
    @Override public void close() throws IOException { jar.close(); }
}
