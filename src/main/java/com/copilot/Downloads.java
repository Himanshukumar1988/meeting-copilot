package com.copilot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Downloads model files. Falls back to curl when Java's trust store rejects the host (e.g. a corporate TLS proxy). */
public final class Downloads {
    private Downloads() {}

    public static void fetch(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            try {
                withJava(url, tmp);
            } catch (javax.net.ssl.SSLException e) {
                withCurl(url, tmp, target);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void withJava(String url, Path tmp) throws IOException {
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpResponse<InputStream> res = http.send(
                HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("Download failed: HTTP " + res.statusCode() + " for " + url);
            try (InputStream in = res.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private static void withCurl(String url, Path tmp, Path target) throws IOException {
        try {
            Process p = new ProcessBuilder("curl", "-fsSL", "-o", tmp.toString(), url).inheritIO().start();
            if (p.waitFor() != 0) throw new IOException("Download failed. Download " + url + " to " + target + " manually.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }
}
