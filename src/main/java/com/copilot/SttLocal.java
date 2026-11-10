package com.copilot;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * On-device speech-to-text using whisper.cpp (via whisper-jni). No audio leaves the machine.
 * Two models: a fast English-only one for normal use, and a multilingual one that detects the spoken
 * language and translates it to English.
 */
public final class SttLocal {
    private static final String ENGLISH_MODEL = Config.get("STT_LOCAL_MODEL", "ggml-base.en.bin");
    // Multilingual models are weaker than English-only ones at the same size; "small" is the practical minimum
    // for languages like Hindi. Override with e.g. ggml-base.bin (faster, lower quality) or ggml-medium.bin.
    // Intel Macs have no Apple GPU/Neural hardware behind Whisper, so the "small" model cannot keep up in real time
    // there; default them to "base" (override with STT_MULTILINGUAL_MODEL).
    private static final String MULTILINGUAL_MODEL =
        Config.get("STT_MULTILINGUAL_MODEL", isIntel() ? "ggml-base.bin" : "ggml-small.bin");
    private static final String MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
    private static final Path MODEL_DIR = AppDirs.path(".models");
    // Whisper emits tags like [BLANK_AUDIO] or (music) for silence/noise; they are not speech.
    private static final Pattern NON_SPEECH = Pattern.compile("^\\[.*]$|^\\(.*\\)$");

    // A word (or token) repeated four or more times in a row is a Whisper decoding loop, not speech.
    private static final Pattern REPEATED_WORD = Pattern.compile("(\\S+)(?:\\s+\\1){3,}");

    private static WhisperJNI whisper;
    private static final Map<String, WhisperContext> CONTEXTS = new HashMap<>();

    private SttLocal() {}

    /** True on an Intel (x86_64) Mac or PC, false on Apple Silicon. */
    public static boolean isIntel() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("amd64") || arch.contains("x86_64");
    }

    /** True once the English-only model is on disk. */
    public static boolean englishReady() { return Files.exists(MODEL_DIR.resolve(ENGLISH_MODEL)); }

    /** Loads (and downloads, if needed) the English-only model ahead of time. */
    public static synchronized void preloadEnglish() throws IOException { context(ENGLISH_MODEL); }

    /** True once the multilingual model is on disk (so enabling translation won't trigger a big download). */
    public static boolean multilingualReady() { return Files.exists(MODEL_DIR.resolve(MULTILINGUAL_MODEL)); }

    public static String multilingualModelName() { return MULTILINGUAL_MODEL; }

    /** Loads (and downloads, if needed) the multilingual model ahead of time. */
    public static synchronized void preloadMultilingual() throws IOException { context(MULTILINGUAL_MODEL); }

    // Lazily loads the native library and a model, downloading the model once into .models/.
    private static synchronized WhisperContext context(String modelFile) throws IOException {
        WhisperContext ctx = CONTEXTS.get(modelFile);
        if (ctx != null) return ctx;
        if (whisper == null) {
            WhisperJNI.loadLibrary();
            whisper = new WhisperJNI();
        }
        Path path = MODEL_DIR.resolve(modelFile);
        if (!Files.exists(path)) download(modelFile, path);
        ctx = whisper.init(path);
        CONTEXTS.put(modelFile, ctx);
        warmUp(ctx);
        return ctx;
    }

    // The first inference after loading a model is several times slower (GPU kernels are compiled on first use).
    // Run one throw-away pass on silence now, in the background, so live audio doesn't pay for it.
    private static void warmUp(WhisperContext ctx) {
        try {
            WhisperFullParams p = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
            p.printProgress = false;
            p.printRealtime = false;
            p.printTimestamps = false;
            p.printSpecial = false;
            p.audioCtx = 250; // the size used for 3-second chunks
            whisper.full(ctx, p, new float[48000], 48000);
        } catch (RuntimeException ignored) {
            // warming up is only an optimisation
        }
    }

    // Downloads the model; if Java's trust store rejects the host (e.g. corporate TLS proxy), retries with curl.
    private static void download(String modelFile, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(modelFile + ".part");
        String url = MODEL_URL + modelFile;
        try {
            try {
                downloadWithJava(url, tmp);
            } catch (javax.net.ssl.SSLException e) {
                downloadWithCurl(url, tmp, target);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void downloadWithJava(String url, Path tmp) throws IOException {
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpResponse<InputStream> res = http.send(
                HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("Model download failed: HTTP " + res.statusCode());
            try (InputStream in = res.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Model download interrupted", e);
        }
    }

    private static void downloadWithCurl(String url, Path tmp, Path target) throws IOException {
        try {
            Process p = new ProcessBuilder("curl", "-fsSL", "-o", tmp.toString(), url).inheritIO().start();
            if (p.waitFor() != 0) throw new IOException("Model download failed. Download " + url + " to " + target + " manually.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Model download interrupted", e);
        }
    }

    /** Transcribes 16 kHz mono float samples with the English-only model; returns trimmed text ("" for silence). */
    public static String transcribe(float[] samples) throws IOException {
        return run(ENGLISH_MODEL, samples, false);
    }

    /** Detects the spoken language and returns the speech translated to English ("" for silence). */
    public static String transcribeToEnglish(float[] samples) throws IOException {
        return run(MULTILINGUAL_MODEL, samples, true);
    }

    /** Transcribes in whatever language is spoken (auto-detected), without translating. Needs the multilingual model. */
    public static String transcribeNative(float[] samples) throws IOException {
        return run(MULTILINGUAL_MODEL, samples, false, true);
    }

    private static String run(String modelFile, float[] samples, boolean translateToEnglish) throws IOException {
        return run(modelFile, samples, translateToEnglish, translateToEnglish);
    }

    private static synchronized String run(String modelFile, float[] samples, boolean translateToEnglish, boolean autoLanguage)
            throws IOException {
        WhisperContext ctx = context(modelFile);
        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.printProgress = false;
        params.printRealtime = false;
        params.printTimestamps = false;
        params.printSpecial = false;
        // Whisper normally pads every clip to 30 s and runs the whole encoder on it. Limiting the encoder to the
        // length of this clip (+2 s margin; 50 frames per second = 1 per 320 samples) halves the time on short
        // chunks, which is what keeps non-English transcription real-time. Retries on bad decodes stay enabled.
        params.audioCtx = Math.min(1500, ((samples.length / 320 + 100 + 49) / 50) * 50); // rounded up to a multiple of 50: few distinct sizes
        if (autoLanguage) params.language = "auto";   // detect the spoken language per chunk
        if (translateToEnglish) params.translate = true; // Whisper's own (weaker) translation to English
        if (whisper.full(ctx, params, samples, samples.length) != 0) throw new IOException("Whisper inference failed");

        StringBuilder text = new StringBuilder();
        for (int i = 0, n = whisper.fullNSegments(ctx); i < n; i++) text.append(whisper.fullGetSegmentText(ctx, i));
        String out = REPEATED_WORD.matcher(text.toString().trim()).replaceAll("$1"); // "वो वो वो वो…" loops
        return NON_SPEECH.matcher(out).matches() ? "" : out;
    }
}
