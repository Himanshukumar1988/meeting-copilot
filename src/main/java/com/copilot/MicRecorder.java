package com.copilot;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/** Captures the default microphone as 16 kHz mono PCM (what Whisper expects) in fixed-length chunks. */
public final class MicRecorder implements Recorder {
    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    private static final int BYTES_PER_SECOND = 16000 * 2;
    private static final int MIN_CHUNK_SECONDS = 1;

    private final Mixer.Info device; // null = system default input
    private final int chunkSeconds;
    private final Consumer<float[]> onChunk;
    private volatile boolean running;
    private TargetDataLine line;

    public MicRecorder(Mixer.Info device, int chunkSeconds, Consumer<float[]> onChunk) {
        this.device = device;
        this.chunkSeconds = chunkSeconds;
        this.onChunk = onChunk;
    }

    /** Input devices that can deliver 16 kHz mono PCM (includes virtual loopback devices such as BlackHole). */
    public static java.util.List<Mixer.Info> inputDevices() {
        DataLine.Info want = new DataLine.Info(TargetDataLine.class, FORMAT);
        java.util.List<Mixer.Info> out = new java.util.ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (!mi.getName().startsWith("Port ") && AudioSystem.getMixer(mi).isLineSupported(want)) out.add(mi);
        }
        return out;
    }

    @Override
    public boolean isRunning() { return running; }

    /** Opens the mic and starts the capture thread; throws if no input device is available. */
    @Override
    public void start() throws LineUnavailableException {
        line = device == null ? AudioSystem.getTargetDataLine(FORMAT)
            : (TargetDataLine) AudioSystem.getMixer(device).getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
        line.open(FORMAT);
        line.start();
        running = true;
        Thread t = new Thread(this::loop, "mic-recorder");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void stop() {
        running = false;
        if (line != null) {
            line.stop();
            line.close();
        }
    }

    // Reads until a chunk is full, hands it off, repeats; flushes a final partial chunk on stop.
    private void loop() {
        int chunkBytes = chunkSeconds * BYTES_PER_SECOND;
        byte[] buf = new byte[4096];
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(chunkBytes);
        while (running) {
            int n = line.read(buf, 0, buf.length);
            if (n <= 0) break;
            chunk.write(buf, 0, n);
            if (chunk.size() >= chunkBytes) {
                emit(chunk.toByteArray());
                chunk.reset();
            }
        }
        if (chunk.size() >= MIN_CHUNK_SECONDS * BYTES_PER_SECOND) emit(chunk.toByteArray());
    }

    private void emit(byte[] pcm) {
        float[] samples = new float[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((pcm[2 * i + 1] << 8) | (pcm[2 * i] & 0xff)) / 32768f;
        }
        onChunk.accept(samples);
    }
}
