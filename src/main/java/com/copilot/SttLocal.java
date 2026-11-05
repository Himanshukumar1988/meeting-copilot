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
import java.util.regex.Pattern;

/** On-device speech-to-text using whisper.cpp (via whisper-jni). No audio leaves the machine. */
public final class SttLocal {
    private static final String MODEL_FILE = Config.get("STT_LOCAL_MODEL", "ggml-base.en.bin");
    private static final String MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + MODEL_FILE;
    private static final Path MODEL_PATH = Path.of(".models", MODEL_FILE);
    // Whisper emits tags like [BLANK_AUDIO] or (music) for silence/noise; they are not speech.
    private static final Pattern NON_SPEECH = Pattern.compile("^\\[.*]$|^\\(.*\\)$");

    private static WhisperJNI whisper;
    private static WhisperContext ctx;

    private SttLocal() {}

    // Lazily loads the native library and model, downloading the model once into .models/.
    private static synchronized void init() throws IOException {
        if (ctx != null) return;
        WhisperJNI.loadLibrary();
        whisper = new WhisperJNI();
        if (!Files.exists(MODEL_PATH)) download();
        ctx = whisper.init(MODEL_PATH);
    }

    // Downloads the model; if Java's trust store rejects the host (e.g. corporate TLS proxy), retries with curl.
    private static void download() throws IOException {
        Files.createDirectories(MODEL_PATH.getParent());
        Path tmp = MODEL_PATH.resolveSibling(MODEL_FILE + ".part");
        try {
            try {
                downloadWithJava(tmp);
            } catch (javax.net.ssl.SSLException e) {
                downloadWithCurl(tmp);
            }
            Files.move(tmp, MODEL_PATH, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void downloadWithJava(Path tmp) throws IOException {
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpResponse<InputStream> res = http.send(
                HttpRequest.newBuilder(URI.create(MODEL_URL)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("Model download failed: HTTP " + res.statusCode());
            try (InputStream in = res.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Model download interrupted", e);
        }
    }

    private static void downloadWithCurl(Path tmp) throws IOException {
        try {
            Process p = new ProcessBuilder("curl", "-fsSL", "-o", tmp.toString(), MODEL_URL).inheritIO().start();
            if (p.waitFor() != 0) {
                throw new IOException("Model download failed. Download " + MODEL_URL + " to " + MODEL_PATH + " manually.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Model download interrupted", e);
        }
    }

    /** Transcribes 16 kHz mono float samples; returns trimmed text ("" for silence). */
    public static synchronized String transcribe(float[] samples) throws IOException {
        init();
        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.printProgress = false;
        params.printRealtime = false;
        params.printTimestamps = false;
        params.printSpecial = false;
        if (whisper.full(ctx, params, samples, samples.length) != 0) throw new IOException("Whisper inference failed");

        StringBuilder text = new StringBuilder();
        for (int i = 0, n = whisper.fullNSegments(ctx); i < n; i++) text.append(whisper.fullGetSegmentText(ctx, i));
        String out = text.toString().trim();
        return NON_SPEECH.matcher(out).matches() ? "" : out;
    }
}
