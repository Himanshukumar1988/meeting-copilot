package com.copilot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Locates the small native helper programs written in Swift (system-audio capture, OCR).
 * A packaged app ships them prebuilt next to the jar; in development they are compiled on first use
 * from the Swift source bundled in the jar (needs the Xcode command line tools).
 */
public final class Helpers {
    private Helpers() {}

    /** Returns an executable helper by name (e.g. "ocr"), building it if necessary. */
    public static synchronized Path ensure(String name) throws IOException, InterruptedException {
        Path prebuilt = jarDir().resolve(name);
        if (Files.isExecutable(prebuilt) && !Files.isDirectory(prebuilt)) return prebuilt;

        Path dir = AppDirs.path(".bin");
        Path src = dir.resolve(name + ".swift"), bin = dir.resolve(name);
        Files.createDirectories(dir);
        byte[] bundled;
        try (InputStream in = Helpers.class.getResourceAsStream("/" + name + ".swift")) {
            if (in == null) throw new IOException(name + ".swift is missing from the jar");
            bundled = in.readAllBytes();
        }
        if (Files.exists(bin) && Files.exists(src) && Arrays.equals(Files.readAllBytes(src), bundled)) return bin;
        Files.write(src, bundled);
        Process p = new ProcessBuilder("swiftc", "-O", "-swift-version", "5", src.toString(), "-o", bin.toString())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            Files.deleteIfExists(bin);
            throw new IOException("Could not build the " + name + " helper (needs the Xcode command line tools: "
                + "xcode-select --install). " + out.strip());
        }
        return bin;
    }

    // Directory containing the running jar (where a packaged app keeps its prebuilt helpers).
    private static Path jarDir() {
        try {
            Path loc = Path.of(Helpers.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(loc) ? loc : loc.getParent();
        } catch (Exception e) {
            return Path.of("");
        }
    }
}
