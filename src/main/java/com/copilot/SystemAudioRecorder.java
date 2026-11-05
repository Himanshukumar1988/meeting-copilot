package com.copilot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * Captures everything the Mac is playing (e.g. the other side of a Meet call) with no virtual audio driver,
 * using a tiny Swift helper built on a Core Audio process tap (macOS 14.2+). The helper is compiled once into .bin/ with swiftc
 * (Xcode command line tools) and needs the System Audio Recording permission for the app that launched this one.
 */
public final class SystemAudioRecorder implements Recorder {
    private static final Path DIR = Path.of(".bin");
    private static final Path SRC = DIR.resolve("system-audio.swift");
    private static final Path BIN = DIR.resolve("system-audio");
    private static final int TARGET_RATE = 16000; // the helper already delivers 16 kHz mono float32

    private final int chunkSeconds;
    private final Consumer<float[]> onChunk;
    private final Consumer<String> onWarning;
    private volatile boolean running;
    private volatile long bytesReceived;
    private Process process;

    public SystemAudioRecorder(int chunkSeconds, Consumer<float[]> onChunk, Consumer<String> onWarning) {
        this.chunkSeconds = chunkSeconds;
        this.onChunk = onChunk;
        this.onWarning = onWarning;
    }

    @Override public boolean isRunning() { return running; }

    // Builds the helper on first use (or when the bundled source changed).
    private static synchronized void ensureBinary() throws IOException, InterruptedException {
        Files.createDirectories(DIR);
        byte[] bundled;
        try (InputStream in = SystemAudioRecorder.class.getResourceAsStream("/system-audio.swift")) {
            if (in == null) throw new IOException("system-audio.swift missing from the jar");
            bundled = in.readAllBytes();
        }
        boolean upToDate = Files.exists(BIN) && Files.exists(SRC) && java.util.Arrays.equals(Files.readAllBytes(SRC), bundled);
        if (upToDate) return;
        Files.write(SRC, bundled);
        Process p = new ProcessBuilder("swiftc", "-O", "-swift-version", "5", SRC.toString(), "-o", BIN.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            Files.deleteIfExists(BIN);
            throw new IOException("Could not build the system-audio helper (need Xcode command line tools: "
                + "xcode-select --install). " + out.strip());
        }
    }

    @Override
    public void start() throws IOException, InterruptedException {
        if (!MacWindow.isMac()) throw new IOException("System audio capture is only supported on macOS");
        ensureBinary();
        process = new ProcessBuilder(BIN.toString()).start();
        StringBuilder err = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try { err.append(new String(process.getErrorStream().readAllBytes())); } catch (IOException ignored) { }
        }, "system-audio-stderr");
        errReader.setDaemon(true);
        errReader.start();
        Thread.sleep(800);
        if (!process.isAlive()) {
            throw new IOException("System audio capture failed: " + err.toString().strip()
                + " (allow System Audio Recording for the app you launched this from)");
        }
        running = true;
        bytesReceived = 0;
        Thread t = new Thread(this::loop, "system-audio");
        t.setDaemon(true);
        t.start();
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(6000); } catch (InterruptedException e) { return; }
            if (running && bytesReceived == 0) {
                onWarning.accept("No system audio is arriving. Check System Settings → Privacy & Security → "
                    + "System Audio Recording for the app you launched this from, then restart both. "
                    + err.toString().strip());
            }
        }, "system-audio-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @Override
    public void stop() {
        running = false;
        if (process != null) process.destroy();
    }

    // Reads 16 kHz mono float32 PCM from the helper and emits fixed-length chunks.
    private void loop() {
        int chunkSamples = chunkSeconds * TARGET_RATE;
        float[] chunk = new float[chunkSamples];
        int filled = 0;
        byte[] buf = new byte[16384];
        int carryLen = 0; // bytes left over from a read that ended mid-float
        try (InputStream in = process.getInputStream()) {
            int n;
            while (running && (n = in.read(buf, carryLen, buf.length - carryLen)) > 0) {
                bytesReceived += n;
                int total = carryLen + n, usable = total - total % 4;
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, usable).order(ByteOrder.LITTLE_ENDIAN);
                while (bb.remaining() >= 4) {
                    chunk[filled++] = bb.getFloat();
                    if (filled == chunkSamples) { onChunk.accept(chunk.clone()); filled = 0; }
                }
                carryLen = total - usable;
                System.arraycopy(buf, usable, buf, 0, carryLen);
            }
        } catch (IOException ignored) {
            // process was stopped
        }
        if (filled >= TARGET_RATE) onChunk.accept(java.util.Arrays.copyOf(chunk, filled));
    }
}
