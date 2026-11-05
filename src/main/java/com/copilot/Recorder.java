package com.copilot;

/** An audio source that delivers 16 kHz mono float chunks to a callback. */
public interface Recorder {
    void start() throws Exception;

    void stop();

    boolean isRunning();
}
