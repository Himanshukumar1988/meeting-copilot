package com.copilot;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the app keeps its settings (.env), downloaded models, helper programs and logs.
 * Running from the project folder (development) uses that folder; running as an installed app uses the
 * user's application-support directory, because the working directory of a double-clicked app is meaningless.
 */
public final class AppDirs {
    private static final Path HOME = resolveHome();

    private AppDirs() {}

    private static Path resolveHome() {
        String override = System.getProperty("copilot.home", System.getenv("COPILOT_HOME"));
        if (override != null && !override.isBlank()) return Path.of(override);
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve("pom.xml")) || Files.exists(cwd.resolve(".env"))) return cwd;
        String user = System.getProperty("user.home");
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        return mac ? Path.of(user, "Library", "Application Support", "Meeting Copilot") : Path.of(user, ".meeting-copilot");
    }

    public static Path home() { return HOME; }

    /** A file or directory inside the app's data folder (not created). */
    public static Path path(String first, String... more) { return HOME.resolve(Path.of(first, more)); }
}
