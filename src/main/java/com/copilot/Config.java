package com.copilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads settings from real environment variables, falling back to a KEY=VALUE .env file. */
public final class Config {
    public record Settings(String apiKey, String baseUrl, String model) {}

    private static final Pattern LINE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");
    private static final Map<String, String> DOTENV = loadEnv(AppDirs.path(".env"));

    private Config() {}

    // Parses a .env file; comments and malformed lines are ignored, surrounding quotes are stripped.
    static Map<String, String> loadEnv(Path file) {
        Map<String, String> out = new HashMap<>();
        if (!Files.exists(file)) return out;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.trim().startsWith("#")) continue;
                Matcher m = LINE.matcher(line);
                if (m.matches()) out.put(m.group(1), m.group(2).replaceAll("^(['\"])(.*)\\1$", "$2"));
            }
        } catch (IOException e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
        return out;
    }

    /** Returns the setting, or the default when it is unset or blank. Real env vars win over .env. */
    public static String get(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) v = DOTENV.get(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static final String ENV_TEMPLATE = String.join("\n",
        "# Meeting Copilot settings. Set LLM_API_KEY, then restart the app.",
        "LLM_API_KEY=",
        "LLM_BASE_URL=https://api.inference.paytm.com/v1",
        "LLM_MODEL=al-agentic-gpt-oss-120b",
        "# Used only when a screenshot has too little text to read and the image itself is sent.",
        "LLM_VISION_MODEL=al-gpt-5",
        "", "");

    /**
     * Creates a settings template at the app's data folder if there is none yet (first run of an installed app).
     * @return true if it was just created, so the caller can tell the user where to put their API key
     */
    public static boolean ensureEnvFile() {
        Path file = AppDirs.path(".env");
        if (Files.exists(file)) return false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ENV_TEMPLATE);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Returns validated LLM settings; fails loudly when the API key is missing. */
    public static Settings load() {
        String apiKey = get("LLM_API_KEY", null);
        if (apiKey == null) throw new IllegalStateException("LLM_API_KEY is not set. Add it to " + AppDirs.path(".env") + " and restart the app.");
        String baseUrl = get("LLM_BASE_URL", "https://api.inference.paytm.com/v1").replaceAll("/$", "");
        return new Settings(apiKey, baseUrl, get("LLM_MODEL", "al-agentic-gpt-oss-120b"));
    }
}
