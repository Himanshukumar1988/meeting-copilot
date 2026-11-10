package com.copilot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.html.HTMLEditorKit;

/** Always-on-top, translucent, borderless panel showing transcript, chat and the model's suggestion. */
public final class OverlayWindow {
    private static final int PANE_BASE_HEIGHT = 0;
    // Transcript weight when the suggestion is hidden (80% of the pane space) vs shown (40%).
    private static final double TRANSCRIPT_WEIGHT_ALONE = 8, TRANSCRIPT_WEIGHT_SHARED = 4;
    private static final int CHUNK_SECONDS = 3;
    private static final int MAX_LINES = 40;
    private static final int MAX_SHOWN_LINES = 300; // the transcript box scrolls, so keep history to copy from
    private static final int REPLY_LINES = 15; // quick replies only need the recent conversation
    private static final Color FG = new Color(0xE8E8EA);
    private static final Color BOX_BG = new Color(0, 0, 0, 64);
    private static final Color ON_GREEN = new Color(0x1F7A4D);

    private final JFrame frame = new JFrame("Copilot");
    private final JComboBox<String> mode = new JComboBox<>(new String[] {"reply", "code", "design"});
    private final JButton mic = new JButton("Mic: off");
    private final JTextArea transcriptBox = box(110);
    private final JTextArea chatBox = box(80);
    private final JEditorPane answer = new JEditorPane();
    private final JTextArea question = new JTextArea(3, 20);
    private final JLabel status = new JLabel();

    private final Transcript transcript = new Transcript();
    private final List<String> chat = new ArrayList<>();
    // One worker keeps transcription in recording order; LLM calls get their own so they never wait behind STT.
    private final ExecutorService sttWorker = Executors.newSingleThreadExecutor(daemon("stt"));
    private final EchoGate echoGate = new EchoGate();
    private static final long ECHO_WAIT_MS = 1500;
    private final java.util.concurrent.ScheduledExecutorService echoDelay =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(daemon("echo-wait"));
    private final ExecutorService translateWorkers = Executors.newFixedThreadPool(3, daemon("translate"));
    private final java.util.concurrent.atomic.AtomicInteger queuedChunks = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_QUEUED_CHUNKS = 4;          // ~12 s of audio waiting for the local speech model
    private static final boolean TRANSLATE_MIC = "on".equalsIgnoreCase(Config.get("TRANSLATE_MIC", "off"));
    private volatile long lastBehindNoticeMs;
    private final Object deliveryLock = new Object();
    private CompletableFuture<Void> deliveryTail = CompletableFuture.completedFuture(null);
    private final ExecutorService llmWorker = Executors.newSingleThreadExecutor(daemon("llm"));

    private final JButton snapBtn = new JButton("Snap");
    private final JButton lensBtn = small("Lens"); // reverse image search of the attached snap
    /** One attached screenshot. Its text is filled in by OCR shortly after it is taken. */
    private static final class Shot {
        final ScreenSnap.Snap snap;
        volatile String text;       // OCR text; null while reading, or when the image itself must be sent
        volatile boolean imageOnly; // code or graphics: the image is sent even if text was read
        Shot(ScreenSnap.Snap snap) { this.snap = snap; }
        boolean usableAsText() { return !imageOnly && text != null && text.length() >= Ocr.MIN_USEFUL_CHARS; }
    }

    private static final int MAX_SNAPS = 5; // more images make the request large and the vision model slow
    // Snaps waiting for the next Suggest, oldest first. Snap adds one; Suggest uses them all; right-click Snap drops them.
    private final java.util.List<Shot> snaps = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean refreshPending;
    private volatile boolean translateToEnglish;
    private volatile boolean autoSuggest;
    private final SpeakerId speakerId = new SpeakerId();
    private final boolean speakersEnabled = !"off".equalsIgnoreCase(Config.get("SPEAKERS", "on"));
    private int lastThemVoice = -1; // touched only by the STT worker thread
    private volatile boolean speakerModelFailed;
    private volatile long lastAutoSuggestMs;
    private static final long AUTO_COOLDOWN_MS = 12_000;
    private final JButton autoBtn = small("Auto");
    private final JButton translateBtn = small("→EN");
    private volatile String lastAnswer = "";
    private JScrollPane chatScroll;
    private JScrollPane transcriptScroll;
    private JScrollPane answerScroll;
    private JButton answerToggle;
    private JPanel columnPanel;
    private JButton chatToggle;
    private final JButton goBtn = new JButton("Suggest");
    private final JButton answerGoBtn = small("Suggest"); // same action, next to the suggestion area
    private volatile int suggestId;
    private java.util.concurrent.Future<?> pending;
    private final JComboBox<DeviceItem> inputBox = new JComboBox<>();
    private final List<Recorder> recorders = new ArrayList<>();
    private boolean sttFailed;
    private boolean clickThrough;

    public OverlayWindow() {
        frame.setUndecorated(true);
        frame.setBackground(new Color(0, 0, 0, 0));
        frame.setAlwaysOnTop(true);
        frame.setAutoRequestFocus(false);
        // A focusable window makes macOS activate this app on click, which can bring the launching app
        // (e.g. VS Code) in front of Chrome. Stay non-focusable except while typing in the question box.
        frame.setFocusableWindowState(false);
        frame.setType(java.awt.Window.Type.UTILITY);
        frame.setSize(460, 640);
        frame.setResizable(true);
        int screenW = Toolkit.getDefaultToolkit().getScreenSize().width;
        frame.setLocation(screenW - 480, 40);
        frame.setContentPane(buildPanel());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    /** Shows the window and applies the macOS screen-share protection once the native window exists. */
    public void show() {
        frame.setVisible(true);
        boolean hidden = MacWindow.setContentProtection(true);
        MacWindow.setVisibleOnAllSpaces();
        setStatus(hidden ? defaultStatus() : "Warning: could not hide window from screen share. " + defaultStatus());
    }

    public void toggleVisible() {
        if (frame.isVisible()) frame.setVisible(false);
        else frame.setVisible(true);
    }

    public void setStatus(String msg) {
        log(msg);
        SwingUtilities.invokeLater(() -> status.setText("<html>" + esc(msg) + "</html>"));
    }

    // Appends every status message to .logs/copilot.log so errors can be read even when the overlay is hidden from capture.
    private static synchronized void log(String msg) {
        try {
            java.nio.file.Path f = AppDirs.path(".logs", "copilot.log");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, java.time.LocalTime.now().withNano(0) + "  " + msg + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
            // logging is best-effort
        }
    }

    // ---- actions --------------------------------------------------------------------------

    /** Sends the current context to the LLM and shows the suggestion. A request already in flight is cancelled. */
    public void suggest() { suggest(null); }

    /**
     * @param autoQuestion non-null for an automatic suggestion: a reply to this question from the other side,
     *                     ignoring the typed question, highlighted text and screenshot (those stay for a manual press)
     */
    private void suggest(String autoQuestion) {
        boolean auto = autoQuestion != null;
        cancelSuggest();
        String selectedMode = (String) mode.getSelectedItem();
        int lines = "reply".equals(selectedMode) ? REPLY_LINES : MAX_LINES;
        String selection = auto ? null : takeSelection();
        String asked = auto ? "Reply to the latest question or request from the other side: " + autoQuestion : question.getText();
        java.util.List<Shot> shots = auto ? java.util.List.of() : java.util.List.copyOf(snaps);
        // Every snap has enough plain text: send just the text to the fast model. Otherwise (code, graphics, or
        // text still being read) send all the images to the vision model, so the snaps are read the same way.
        boolean useOcr = !shots.isEmpty() && shots.stream().allMatch(Shot::usableAsText);
        java.util.List<String> images = useOcr ? java.util.List.of() : shots.stream().map(s -> s.snap.dataUrl()).toList();
        Llm.Input in = new Llm.Input(selectedMode, tail(transcript.lines(), lines), tail(chat, lines), asked,
            images, selection, useOcr ? screenText(shots) : null);
        setStatus(auto ? "Auto-suggestion for: " + autoQuestion
            : selection == null ? "Thinking…" : "Thinking about the selected text (" + selection.length() + " chars)…");
        setSuggestionVisible(true); // expand the suggestion area (transcript shrinks to 40%)
        setSuggestRunning(true);
        final int id = ++suggestId;
        pending = llmWorker.submit(() -> {
            try {
                StringBuffer acc = new StringBuffer(); // appended on the worker thread, read on the EDT
                java.util.concurrent.atomic.AtomicBoolean renderPending = new java.util.concurrent.atomic.AtomicBoolean();
                String text = Llm.stream(in, piece -> {
                    if (id != suggestId) return;
                    acc.append(piece);
                    // Repaint at most once per EDT turn so fast streams don't flood the UI.
                    if (renderPending.compareAndSet(false, true)) {
                        SwingUtilities.invokeLater(() -> {
                            renderPending.set(false);
                            if (id == suggestId) renderAnswer(acc.toString());
                        });
                    }
                });
                if (id != suggestId) return; // cancelled or superseded while the reply was arriving
                SwingUtilities.invokeLater(() -> renderAnswer(text.isEmpty() ? "(empty response)" : text));
                setStatus("Done.");
                // The snaps belong to this request only. Snaps taken while it was running stay for the next one.
                if (!shots.isEmpty()) { snaps.removeAll(shots); updateSnapButton(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // cancelled: cancelSuggest() already updated the UI
            } catch (Exception e) {
                if (id != suggestId) return;
                SwingUtilities.invokeLater(() -> renderAnswer("Error: " + e.getMessage()));
                setStatus("LLM call failed.");
            } finally {
                if (id == suggestId) setSuggestRunning(false);
            }
        });
    }

    /** The Suggest buttons' action: cancels a running request, otherwise starts a new one. */
    private void suggestOrCancel() {
        if ("Cancel".equals(goBtn.getText())) cancelSuggest(); else suggest();
    }

    /**
     * Turns detect-language-and-translate-to-English on or off for speech-to-text. The first time, the larger
     * multilingual model is downloaded, which takes a while, so that happens off the UI thread with progress shown.
     */
    public void toggleTranslate() {
        if (translateToEnglish) {
            translateToEnglish = false;
            setTranslateButton(false);
            setStatus("Translation off: English-only transcription.");
            return;
        }
        setStatus(SttLocal.multilingualReady() ? "Loading the multilingual model…"
            : "Downloading the multilingual model " + SttLocal.multilingualModelName() + " (one time, up to 470 MB)…");
        translateBtn.setEnabled(false);
        new Thread(() -> {
            try {
                SttLocal.preloadMultilingual();
                translateToEnglish = true;
                setTranslateButton(true);
                setStatus(SttLocal.isIntel()
                    ? "Translation on (Intel Mac: lighter model " + SttLocal.multilingualModelName() + ", so expect lower accuracy and some lag)."
                    : "Translation on: any language is detected and shown in English.");
            } catch (Exception e) {
                setStatus("Could not enable translation: " + e.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> translateBtn.setEnabled(true));
            }
        }, "stt-multilingual-load").start();
    }

    // "Them" when one voice has been heard on the system audio, "Them 1/2/…" once several have. Runs on the
    // STT worker thread. If the speaker model is unavailable the lines simply stay "Them".
    private String themLabel(float[] samples) {
        if (!speakersEnabled || speakerModelFailed) return "Them";
        try {
            if (!SpeakerId.modelReady()) setStatus("Downloading the speaker model (one time, about 26 MB)…");
            int before = speakerId.speakers();
            int voice = speakerId.identify(samples);
            if (voice < 0) voice = lastThemVoice; // too short/quiet to tell: assume the previous speaker
            else lastThemVoice = voice;
            if (speakerId.speakers() >= 2 && before < 2) transcript.relabel("Them", "Them 1"); // the earlier lines were voice 1
            return speakerId.speakers() >= 2 && voice >= 0 ? "Them " + (voice + 1) : "Them";
        } catch (Exception e) {
            speakerModelFailed = true;
            setStatus("Speaker labels unavailable (" + e.getMessage() + "); using \"Them\" for everyone else.");
            return "Them";
        }
    }

    // When the other side asks a question or makes a request, suggest a reply without being asked.
    // Skipped while a request is running, within the cooldown, or while the user is typing a question of their own.
    private void maybeAutoSuggest(String text) {
        if (!autoSuggest) return;
        String q = Questions.find(text);
        long now = System.currentTimeMillis();
        if (q == null || now - lastAutoSuggestMs < AUTO_COOLDOWN_MS) return;
        SwingUtilities.invokeLater(() -> {
            if (!"Suggest".equals(goBtn.getText()) || question.hasFocus()) return; // busy, or the user is typing
            lastAutoSuggestMs = now;
            suggest(q);
        });
    }

    /** Turns automatic suggestions for questions from the other side on or off. */
    public void toggleAuto() {
        autoSuggest = !autoSuggest;
        autoBtn.setBackground(autoSuggest ? ON_GREEN : null);
        autoBtn.setOpaque(autoSuggest);
        autoBtn.setForeground(autoSuggest ? Color.WHITE : null);
        setStatus(autoSuggest ? "Auto-suggest on: a reply is drafted when the other side asks a question or makes a request."
            : "Auto-suggest off.");
    }

    private void setTranslateButton(boolean on) {
        SwingUtilities.invokeLater(() -> {
            translateBtn.setBackground(on ? ON_GREEN : null);
            translateBtn.setOpaque(on);
            translateBtn.setForeground(on ? Color.WHITE : null);
        });
    }

    /** Cancels the in-flight suggestion request, if any. The attached screenshot is kept for a retry. */
    public void cancelSuggest() {
        java.util.concurrent.Future<?> f = pending;
        if (f == null || f.isDone()) return;
        suggestId++; // invalidates the running request's result
        f.cancel(true);
        setSuggestRunning(false);
        setStatus("Suggestion cancelled.");
    }

    private void setSuggestRunning(boolean running) {
        SwingUtilities.invokeLater(() -> {
            goBtn.setText(running ? "Cancel" : "Suggest");
            answerGoBtn.setText(running ? "Cancel" : "Suggest");
        });
    }

    /**
     * Lets the user select a screen region (or the whole screen) and attaches it to the next Suggest.
     * On macOS this uses the system's own selector (screencapture -i), which works over any app, including
     * full-screen ones; set SNAP_PICKER=builtin to use the in-app selector instead (also used off macOS).
     */
    public void snap() {
        if (!MacWindow.hasScreenCaptureAccess()) {
            MacWindow.requestScreenCaptureAccess();
            setStatus("No Screen Recording permission: other apps (e.g. Chrome) would be missing from the shot. "
                + "System Settings → Privacy & Security → Screen Recording → enable the terminal/IDE you launched this from, then restart it and the app.");
            return;
        }
        setStatus("Select the area to capture… (Esc cancels)");
        new Thread(() -> {
            try {
                if (MacWindow.isMac() && !"builtin".equals(Config.get("SNAP_PICKER", "native"))) {
                    if (snapNative()) return;
                }
                java.awt.image.BufferedImage shot = ScreenSnap.grab();
                SwingUtilities.invokeLater(() -> RegionPicker.pick(shot, region -> {
                    if (region == null) { setStatus("Snap cancelled."); return; }
                    new Thread(() -> attach(shot, region), "snap-encode").start();
                }));
            } catch (Exception e) {
                setStatus("Screenshot failed: " + e.getMessage() + " (macOS: allow Screen Recording for your terminal)");
            }
        }, "snap").start();
    }

    // macOS built-in interactive capture: drag a region (Space toggles window mode, Esc cancels).
    // Returns false when screencapture itself failed (so the caller can fall back to the in-app selector).
    private boolean snapNative() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("snap", ".png");
        java.nio.file.Files.delete(tmp); // screencapture only writes the file if the user completes a selection
        try {
            Process p = new ProcessBuilder("screencapture", "-i", "-x", "-t", "png", tmp.toString())
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();
            if (code != 0) {
                System.err.println("screencapture exit=" + code + " output=" + out);
                setStatus("System selector failed (exit " + code + (out.isEmpty() ? "" : ": " + out) + "); using built-in selector.");
                return false;
            }
            if (!java.nio.file.Files.exists(tmp) || java.nio.file.Files.size(tmp) == 0) {
                setStatus("Snap cancelled (no area was selected).");
                return true;
            }
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(tmp.toFile());
            attach(img, new java.awt.Rectangle(0, 0, img.getWidth(), img.getHeight()));
            return true;
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    private void attach(java.awt.image.BufferedImage shot, java.awt.Rectangle region) {
        try {
            java.awt.Rectangle r = region.intersection(new java.awt.Rectangle(0, 0, shot.getWidth(), shot.getHeight()));
            java.awt.image.BufferedImage crop = shot.getSubimage(r.x, r.y, r.width, r.height);
            ScreenSnap.Snap s = ScreenSnap.encode(crop);
            Shot shotEntry = new Shot(s);
            boolean droppedOldest = false;
            synchronized (snaps) {
                if (snaps.size() >= MAX_SNAPS) { snaps.remove(0); droppedOldest = true; }
                snaps.add(shotEntry);
            }
            updateSnapButton();
            String which = "Screenshot " + snaps.size() + " of max " + MAX_SNAPS
                + (droppedOldest ? " (the oldest was dropped)" : "") + " attached (" + s.width() + "x" + s.height() + ")";
            String tail = " Snap again to add more; right-click Snap to drop them.";
            setStatus(which + ". Reading its text…");
            if (!Ocr.enabled()) {
                shotEntry.imageOnly = true;
                setStatus(which + ". Sent with the next Suggest." + tail);
                return;
            }
            // Reading the text now means it is ready (and fast to use) by the time Suggest is pressed.
            try {
                long t0 = System.nanoTime();
                String text = Ocr.read(crop);
                if (Ocr.shouldSendImageInstead(text)) {
                    // Code: OCR mangles symbols and the model then reports bugs that aren't there. Send the image.
                    shotEntry.imageOnly = true;
                    setStatus(which + ": looks like code, so the image itself will be sent for an exact reading "
                        + "(slower than fast mode)." + tail);
                    return;
                }
                shotEntry.text = text;
                if (text.length() < Ocr.MIN_USEFUL_CHARS) shotEntry.imageOnly = true;
                setStatus(text.length() >= Ocr.MIN_USEFUL_CHARS
                    ? which + ": " + text.length() + " characters of text read in "
                        + Math.round((System.nanoTime() - t0) / 1e8) / 10.0 + "s (fast mode)." + tail
                    : which + " (little text found, so the image itself will be sent)." + tail);
            } catch (Exception e) {
                shotEntry.imageOnly = true;
                setStatus(which + " (text reading unavailable: " + e.getMessage() + "); the image will be sent." + tail);
            }
        } catch (Exception e) {
            setStatus("Screenshot failed: " + e.getMessage());
        }
    }

    // The OCR text of several snaps, each under its own heading so the model knows where one ends.
    private static String screenText(java.util.List<Shot> shots) {
        if (shots.size() == 1) return shots.get(0).text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shots.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append("## Screenshot ").append(i + 1).append(" of ").append(shots.size()).append('\n').append(shots.get(i).text);
        }
        return sb.toString();
    }

    // "Snap" with nothing attached, "Snap ✓" with one, "Snap ✓ 3" with several. Lens shows while any is attached.
    private void updateSnapButton() {
        int n = snaps.size();
        SwingUtilities.invokeLater(() -> {
            snapBtn.setText(n == 0 ? "Snap" : n == 1 ? "Snap ✓" : "Snap ✓ " + n);
            lensBtn.setVisible(n > 0);
            lensBtn.getParent().revalidate();
        });
    }

    private void clearSnap() {
        snaps.clear();
        updateSnapButton();
    }

    /** Reverse-searches the latest attached screenshot with Google Lens, in the browser. Uploads the image to Google. */
    public void searchSnap() {
        java.util.List<Shot> current = java.util.List.copyOf(snaps);
        ScreenSnap.Snap s = current.isEmpty() ? null : current.get(current.size() - 1).snap;
        if (s == null) { setStatus("Snap a region first, then press Lens to search for it."); return; }
        setStatus("Searching the screenshot with Google Lens…");
        new Thread(() -> {
            try {
                ImageSearch.Result r = ImageSearch.search(s.dataUrl());
                setStatus(r == ImageSearch.Result.OPENED
                    ? "Google Lens results opened in your browser."
                    : "Lens upload failed; opened Google Lens with the screenshot on the clipboard. Paste it there (Cmd+V).");
            } catch (Exception e) {
                setStatus("Image search failed: " + e.getMessage());
            }
        }, "image-search").start();
    }

    /** Adds the clipboard text (copied meeting chat) to the chat context. */
    public void ingestChat() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            String text = data == null ? "" : data.toString().strip();
            if (text.isEmpty()) { setStatus("Clipboard is empty."); return; }
            chat.add(text);
            refresh();
            setStatus("Chat added from clipboard.");
        } catch (Exception e) {
            setStatus("Clipboard is empty.");
        }
    }

    /** Toggles microphone capture on/off. */
    public void toggleMic() {
        if (!recorders.isEmpty()) {
            recorders.forEach(Recorder::stop);
            recorders.clear();
            setMicButton(false);
            return;
        }
        DeviceItem choice = (DeviceItem) inputBox.getSelectedItem();
        Source source = choice == null ? Source.MIC : choice.source();
        setStatus("Starting audio…");
        prefetchModels(); // downloads (if needed) happen now, not in the middle of the first chunks
        // Starting the system-audio helper can compile it and wait for it, so keep that off the UI thread.
        new Thread(() -> {
            try {
                sttFailed = false;
                List<Recorder> started = new ArrayList<>();
                if (source != Source.SYSTEM) {
                    String who = source == Source.BOTH ? "Me" : null;
                    started.add(new MicRecorder(selectedDevice(), CHUNK_SECONDS, s -> onAudioChunk(s, who)));
                }
                if (source != Source.MIC) {
                    started.add(new SystemAudioRecorder(CHUNK_SECONDS, s -> onAudioChunk(s, "Them"), this::setStatus));
                }
                List<Recorder> running = new ArrayList<>();
                String problem = null;
                for (Recorder r : started) {
                    try {
                        r.start();
                        running.add(r);
                    } catch (Exception e) {
                        // With "mic + system", keep the mic going if only the system-audio half fails.
                        if (source != Source.BOTH || r instanceof MicRecorder) throw e;
                        problem = e.getMessage();
                    }
                }
                SwingUtilities.invokeLater(() -> recorders.addAll(running));
                setMicButton(true);
                setStatus(problem != null ? "Mic only — system audio failed: " + problem
                    : source == Source.MIC ? defaultStatus()
                    : "Listening to " + (source == Source.SYSTEM ? "system audio" : "mic + system audio") + "…");
            } catch (Exception e) {
                setStatus("Audio unavailable: " + e.getMessage());
            }
        }, "audio-start").start();
    }

    public void toggleClickThrough() {
        clickThrough = !clickThrough;
        boolean ok = MacWindow.setClickThrough(clickThrough);
        setStatus(!ok ? "Click-through is only supported on macOS."
            : clickThrough ? "Click-through ON (Cmd+Shift+X to interact)" : "Click-through OFF");
    }

    // speaker is a transcript label ('Me'/'Them'), or null when there is a single unlabelled source.
    private void onAudioChunk(float[] samples, String speaker) {
        if (rms(samples) < 0.003) return; // near-silence: skip so Whisper doesn't hallucinate text
        final long heardAt = System.currentTimeMillis(); // the chunk has just ended: this is when its speech finished
        if ("Them".equals(speaker)) echoGate.observeSystem(samples, heardAt);
        if ("Me".equals(speaker) && recorders.stream().anyMatch(r -> r instanceof SystemAudioRecorder)) {
            // The system-audio chunk covering the same moment arrives up to ~1 s after this mic chunk, so wait for it
            // before deciding whether the mic is only re-hearing the speakers.
            echoDelay.schedule(() -> handleChunk(samples, speaker, heardAt), ECHO_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        handleChunk(samples, speaker, heardAt);
    }

    private void handleChunk(float[] samples, String speaker, long heardAt) {
        if ("Me".equals(speaker) && echoGate.isEcho(samples, heardAt)) {
            log("TIMING Me skipped: the mic is re-hearing the system audio (speakers instead of headphones)");
            return;
        }
        // If transcription can't keep up, skip the chunk rather than let the delay grow without limit.
        if (queuedChunks.incrementAndGet() > MAX_QUEUED_CHUNKS) {
            queuedChunks.decrementAndGet();
            if (System.currentTimeMillis() - lastBehindNoticeMs > 10_000) {
                lastBehindNoticeMs = System.currentTimeMillis();
                setStatus("Transcription is behind, so some audio was skipped. Turn off →EN or pick the base model to speed up.");
                log("TIMING skipped a chunk: " + MAX_QUEUED_CHUNKS + " chunks were already waiting");
            }
            return;
        }
        sttWorker.submit(() -> {
            try {
                String label = "Them".equals(speaker) ? themLabel(samples) : speaker;
                // Translate mode hears the speech in its own language; the slow LLM step then runs in parallel
                // (translateAsync) so it never holds up the next chunk. Your own mic stays on the fast English
                // model unless TRANSLATE_MIC=on, since translation is meant for the other side.
                boolean translate = translateToEnglish && (speaker == null || !"Me".equals(label) || TRANSLATE_MIC);
                String heard = translate ? SttLocal.transcribeNative(samples) : SttLocal.transcribe(samples);
                queuedChunks.decrementAndGet(); // the heavy local step is done; the queue length only counts that step
                if (heard.isEmpty()) return;
                long sttDoneAt = System.currentTimeMillis();
                Timing timing = new Timing(heardAt, sttDoneAt, heard, translate);
                if (translate && !Translator.looksEnglish(heard)) translateAsync(label, heard, timing);
                else deliverInOrder(label, CompletableFuture.completedFuture(heard), timing);
            } catch (Throwable e) { // Throwable: an Error thrown inside an executor task would otherwise vanish silently
                queuedChunks.decrementAndGet();
                if (!sttFailed) { sttFailed = true; setStatus("Voice-to-text failed: " + e); }
            }
        });
    }

    // Loads the speech models in the background when the mic starts, so a first-time download (148 MB English model,
    // 26 MB speaker model, 470 MB multilingual model) overlaps with startup instead of stalling live transcription.
    private void prefetchModels() {
        new Thread(() -> {
            try {
                if (!SttLocal.englishReady()) setStatus("Downloading the speech model (one time, about 150 MB)…");
                SttLocal.preloadEnglish();
                if (speakersEnabled && !SpeakerId.modelReady()) setStatus("Downloading the speaker model (one time, about 26 MB)…");
                if (speakersEnabled) speakerId.load();
            } catch (Exception e) {
                setStatus("Could not prepare the speech models: " + e.getMessage());
            }
        }, "model-prefetch").start();
    }

    // Translates on a separate pool, but lines are still added to the transcript in the order they were heard.
    private void translateAsync(String label, String heard, Timing timing) {
        deliverInOrder(label, CompletableFuture.supplyAsync(() -> Translator.toEnglish(heard), translateWorkers), timing);
    }

    /** When a chunk's speech ended, when Whisper finished it, and what it heard: for the latency log. */
    private record Timing(long heardAt, long sttDoneAt, String heard, boolean translating) {}

    private void deliverInOrder(String label, CompletableFuture<String> text, Timing timing) {
        synchronized (deliveryLock) {
            deliveryTail = deliveryTail.<String, Void>thenCombine(text, (done, t) -> {
                // add() returns false for a mic line that merely echoes the other side; it may also remove an
                // earlier echo, so refresh either way.
                long now = System.currentTimeMillis();
                boolean kept = transcript.add(label, t, now);
                // Latency log (in the log file only): speech-end → line on screen, split into Whisper and translation.
                log(String.format("TIMING %s total=%.1fs (whisper=%.1fs, translate+wait=%.1fs)%s | heard: %s | shown: %s",
                    label, (now - timing.heardAt()) / 1000.0, (timing.sttDoneAt() - timing.heardAt()) / 1000.0,
                    (now - timing.sttDoneAt()) / 1000.0, kept ? "" : " [dropped as echo]", clip(timing.heard()), clip(t)));
                SwingUtilities.invokeLater(this::refresh);
                if (kept && !"Me".equals(label)) maybeAutoSuggest(t);
                return null;
            }).exceptionally(ex -> { // one failed line must not block the ones after it
                setStatus("Could not add a transcript line: " + ex);
                return null;
            });
        }
    }

    // ---- UI helpers -----------------------------------------------------------------------

    /** Combo entry for an audio input; a null Mixer.Info means the system default. */
    private enum Source { MIC, SYSTEM, BOTH }

    private record DeviceItem(javax.sound.sampled.Mixer.Info info, Source source) {
        DeviceItem(javax.sound.sampled.Mixer.Info info) { this(info, Source.MIC); }

        @Override public String toString() {
            String mic = info == null ? "default mic" : info.getName();
            return switch (source) {
                case SYSTEM -> "System audio (other side)";
                case BOTH -> "Mic + system audio (" + mic + ")";
                case MIC -> info == null ? "Default mic" : info.getName();
            };
        }

        // Mixer.Info objects are not reused between listings, so compare by name to keep the selection on refresh.
        @Override public boolean equals(Object o) {
            return o instanceof DeviceItem d && d.source == source && java.util.Objects.equals(name(d.info), name(info));
        }

        @Override public int hashCode() { return java.util.Objects.hash(source, name(info)); }

        private static String name(javax.sound.sampled.Mixer.Info i) { return i == null ? null : i.getName(); }
    }

    private javax.sound.sampled.Mixer.Info selectedDevice() {
        DeviceItem d = (DeviceItem) inputBox.getSelectedItem();
        return d == null ? null : d.info(); // null = the system default mic (or no mic at all for system-only)
    }

    private static boolean isBuiltIn(javax.sound.sampled.Mixer.Info mi) {
        String n = mi.getName();
        return n.contains("MacBook") || n.contains("Built-in") || n.contains("Internal");
    }

    // Lists the available inputs, keeping the current choice; re-run when the popup opens to pick up new devices.
    // "Mic + system audio" is offered per microphone, so a Bluetooth headset can stay on high-quality output
    // while the built-in mic is used for your voice.
    private void refreshInputs() {
        DeviceItem current = (DeviceItem) inputBox.getSelectedItem();
        java.util.List<javax.sound.sampled.Mixer.Info> mics = new ArrayList<>();
        for (javax.sound.sampled.Mixer.Info mi : MicRecorder.inputDevices()) {
            if (!mi.getName().equals("Default Audio Device")) mics.add(mi);
        }
        inputBox.removeAllItems();
        inputBox.addItem(new DeviceItem(null));
        DeviceItem preferred = null;
        if (MacWindow.isMac()) {
            inputBox.addItem(new DeviceItem(null, Source.SYSTEM));
            DeviceItem bothDefault = new DeviceItem(null, Source.BOTH);
            inputBox.addItem(bothDefault);
            preferred = bothDefault;
            for (javax.sound.sampled.Mixer.Info mi : mics) {
                DeviceItem both = new DeviceItem(mi, Source.BOTH);
                inputBox.addItem(both);
                if (isBuiltIn(mi) && preferred == bothDefault) preferred = both; // avoid Bluetooth call mode by default
            }
        }
        for (javax.sound.sampled.Mixer.Info mi : mics) inputBox.addItem(new DeviceItem(mi));
        if (current != null) inputBox.setSelectedItem(current);
        else if (preferred != null) inputBox.setSelectedItem(preferred);
    }

    private void setMicButton(boolean on) {
        SwingUtilities.invokeLater(() -> {
            mic.setText(on ? "Mic: on" : "Mic: off");
            mic.setBackground(on ? ON_GREEN : null);
            mic.setOpaque(on);
        });
    }

    /**
     * The overlay is non-focusable so clicking its buttons never activates this app (which could bring another
     * app in front of Chrome). A text component is the exception: the window becomes focusable while the pointer
     * is over it or it has focus, so clicking works normally for typing, selecting and copying.
     */
    private void focusOnHover(javax.swing.text.JTextComponent c) {
        c.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (c.getMousePosition() == null) frame.setFocusableWindowState(false);
            }
        });
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { frame.setFocusableWindowState(true); }
            @Override public void mouseExited(MouseEvent e) { if (!c.hasFocus()) frame.setFocusableWindowState(false); }
            @Override public void mousePressed(MouseEvent e) {
                frame.setFocusableWindowState(true);
                MacWindow.activateApp();
                c.requestFocusInWindow();
                // Activation is asynchronous; retry the focus request once the app is active.
                new javax.swing.Timer(150, ev -> c.requestFocusInWindow()) {{ setRepeats(false); start(); }};
                if (c == question) {
                    new javax.swing.Timer(400, ev -> {
                        if (!c.hasFocus()) { frame.toFront(); c.requestFocus(); } // last resort
                        log("FOCUS question click: focusable=" + frame.getFocusableWindowState() + " windowFocused="
                            + frame.isFocused() + " boxFocused=" + c.hasFocus() + " editable=" + c.isEditable()
                            + " " + MacWindow.activationState());
                    }) {{ setRepeats(false); start(); }};
                }
            }
        });
    }

    /**
     * Returns the text the user highlighted in the transcript, chat or suggestion (null if none) and clears the
     * highlight so live transcript updates resume. Reading it here, before the answer box is rewritten, matters.
     */
    private String takeSelection() {
        StringBuilder out = new StringBuilder();
        for (javax.swing.text.JTextComponent t : new javax.swing.text.JTextComponent[] {transcriptBox, chatBox, answer}) {
            String sel = t.getSelectedText();
            if (sel != null && !sel.isBlank()) {
                if (!out.isEmpty()) out.append('\n');
                out.append(sel.strip());
                t.setCaretPosition(t.getSelectionEnd()); // clears the highlight
            }
        }
        if (out.isEmpty()) return null;
        return out.toString();
    }

    private boolean hasSelection() {
        return transcriptBox.getSelectionStart() != transcriptBox.getSelectionEnd()
            || chatBox.getSelectionStart() != chatBox.getSelectionEnd();
    }

    private void copyToClipboard(String text, String what) {
        if (text == null || text.isBlank()) { setStatus("Nothing to copy."); return; }
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new java.awt.datatransfer.StringSelection(text), null);
        setStatus(what + " copied.");
    }

    private void refresh() {
        // Rewriting the text would wipe what the user is selecting, so wait until the selection is gone.
        if (hasSelection()) { refreshPending = true; return; }
        refreshPending = false;
        transcriptBox.setText(String.join("\n", lastN(transcript.lines(), MAX_SHOWN_LINES)));
        chatBox.setText(String.join("\n", lastN(chat, 6)));
        transcriptBox.setCaretPosition(transcriptBox.getDocument().getLength());
    }

    // Renders text with ``` fenced blocks as <pre>, everything else escaped.
    private void renderAnswer(String text) {
        lastAnswer = text;
        String[] parts = text.split("```(?:\\w+)?\\n?", -1);
        StringBuilder html = new StringBuilder("<html><body style='color:#E8E8EA;font-family:sans-serif;font-size:12px'>");
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1) html.append("<pre style='background:#0B0B0E'>").append(esc(parts[i])).append("</pre>");
            else html.append(esc(parts[i]).replace("\n", "<br>"));
        }
        answer.setText(html.append("</body></html>").toString());
        answer.setCaretPosition(0);
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static String defaultStatus() {
        String k = MacWindow.isMac() ? "Cmd" : "Ctrl";
        return "Hotkeys (" + k + "+Shift+): Space hide · C paste chat · Enter suggest · M mic · S snap · X click-through";
    }

    private JPanel buildPanel() {
        JPanel panel = new JPanel(new WeightedColumn()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(18, 18, 22, 224));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
                g2.setColor(new Color(255, 255, 255, 31));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        makeDraggable(panel);

        JLabel title = new JLabel("Copilot");
        title.setForeground(FG);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JButton chatBtn = new JButton("Paste chat");
        chatBtn.addActionListener(e -> ingestChat());
        goBtn.addActionListener(e -> suggestOrCancel());
        mic.addActionListener(e -> toggleMic());
        snapBtn.addActionListener(e -> snap());
        snapBtn.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) { clearSnap(); setStatus("Screenshot dropped."); }
            }
        });

        lensBtn.setVisible(false); // only shown while a screenshot is attached, to keep the header narrow
        lensBtn.setToolTipText("Reverse image search of the latest snap in Google Lens (uploads it to Google)");
        lensBtn.addActionListener(e -> searchSnap());

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.add(mode); buttons.add(mic); buttons.add(snapBtn); buttons.add(lensBtn); buttons.add(chatBtn); buttons.add(goBtn);
        header.add(title, BorderLayout.WEST);
        header.add(buttons, BorderLayout.EAST);

        answer.setEditorKit(new HTMLEditorKit());
        answer.setEditable(false);
        answer.setOpaque(false);
        // Text areas the user can click into (type, select, copy) need keyboard focus; see focusOnHover.
        focusOnHover(question);
        focusOnHover(transcriptBox);
        focusOnHover(chatBox);
        focusOnHover(answer);
        for (javax.swing.text.JTextComponent t : new javax.swing.text.JTextComponent[] {transcriptBox, chatBox}) {
            // Live updates pause while text is selected; apply the pending update once the selection is gone.
            t.addCaretListener(e -> { if (refreshPending && !hasSelection()) refresh(); });
        }
        question.setLineWrap(true);
        question.setWrapStyleWord(true);
        question.setForeground(Color.BLACK);
        question.setCaretColor(Color.BLACK);
        question.setBackground(new Color(245, 245, 247));
        question.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "suggest");
        question.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        question.getActionMap().put("cancel", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { cancelSuggest(); }
        });
        question.getActionMap().put("suggest", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { suggest(); }
        });
        status.setForeground(new Color(232, 232, 234, 153));
        status.setFont(status.getFont().deriveFont(10.5f));
        status.setText(defaultStatus());

        add(panel, header, 0, 0, 0);
        refreshInputs();
        inputBox.setFont(inputBox.getFont().deriveFont(10f));
        inputBox.setToolTipText("Audio source. 'System audio' hears the other side of the call (no driver needed); 'Mic + system audio' labels you Me and the caller Them.");
        inputBox.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { refreshInputs(); }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        JPanel tHead = new JPanel(new BorderLayout(6, 0));
        tHead.setOpaque(false);
        tHead.add(label("Live transcript"), BorderLayout.WEST);
        JButton copyTranscript = small("Copy");
        copyTranscript.addActionListener(e -> copyToClipboard(String.join("\n", transcript.lines()), "Transcript"));
        JPanel tRight = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        tRight.setOpaque(false);
        translateBtn.setToolTipText("Detect the spoken language and show the transcript in English (multilingual model)");
        translateBtn.addActionListener(e -> toggleTranslate());
        JButton clearTranscript = small("Clear");
        clearTranscript.addActionListener(e -> clearTranscript());
        tRight.add(translateBtn);
        tRight.add(copyTranscript);
        tRight.add(clearTranscript);
        tHead.add(tRight, BorderLayout.EAST);
        add(panel, tHead, 0, 1, 0);
        add(panel, inputBox, 0, 1, 0); // audio source on its own row: the header has no room for its long names
        // Heights share the spare space by weight: transcript 40%, suggestion 40%, chat 10%, input 10%.
        // Preferred heights are equal and small so only the weights decide the split.
        JScrollPane questionScroll = scroll(question, PANE_BASE_HEIGHT);
        questionScroll.setBorder(BorderFactory.createEmptyBorder());
        columnPanel = panel;
        transcriptScroll = scroll(transcriptBox, PANE_BASE_HEIGHT);
        add(panel, transcriptScroll, 0, 2, TRANSCRIPT_WEIGHT_ALONE);
        chatScroll = scroll(chatBox, PANE_BASE_HEIGHT);
        add(panel, chatHeader(), 0, 3, 0);
        add(panel, chatScroll, 0, 4, 1);
        answerScroll = scroll(answer, PANE_BASE_HEIGHT);
        answerToggle = small("Show");
        answerToggle.addActionListener(e -> setSuggestionVisible(!answerScroll.isVisible()));
        JPanel answerHead = new JPanel(new BorderLayout());
        answerHead.setOpaque(false);
        answerHead.add(label("Suggestion"), BorderLayout.WEST);
        JButton copyAnswer = small("Copy");
        copyAnswer.addActionListener(e -> copyToClipboard(lastAnswer, "Suggestion"));
        JPanel aRight = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        aRight.setOpaque(false);
        answerGoBtn.addActionListener(e -> suggestOrCancel());
        autoBtn.setToolTipText("Automatically draft a reply when the other side asks a question");
        autoBtn.addActionListener(e -> toggleAuto());
        aRight.add(autoBtn);
        aRight.add(answerGoBtn);
        aRight.add(copyAnswer);
        aRight.add(answerToggle);
        answerHead.add(aRight, BorderLayout.EAST);
        add(panel, answerHead, 0, 5, 0);
        add(panel, answerScroll, 0, 6, 4);
        answerScroll.setVisible(false); // collapsed until the first Suggest; the transcript gets the room
        add(panel, questionScroll, 0, 7, 1);
        add(panel, status, 0, 8, 0);
        return panel;
    }

    // Header row for the chat section: label plus Clear and minimize/expand buttons.
    private JPanel chatHeader() {
        JButton clear = small("Clear");
        clear.addActionListener(e -> clearChat());
        chatToggle = small("Hide");
        chatToggle.addActionListener(e -> toggleChatVisible());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(clear);
        buttons.add(chatToggle);
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(label("Meeting chat"), BorderLayout.WEST);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    private static JButton small(String text) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(10f));
        b.setMargin(new Insets(0, 4, 0, 4));
        return b;
    }

    /** Removes the whole live transcript, so it is no longer sent to the model either. */
    public void clearTranscript() {
        transcript.clear();
        speakerId.reset();
        lastThemVoice = -1;
        refreshPending = false;
        transcriptBox.setText("");
        setStatus("Transcript cleared.");
    }

    /** Removes all pasted meeting chat from the context. */
    public void clearChat() {
        chat.clear();
        refresh();
        setStatus("Chat cleared.");
    }

    /** Collapses or expands the meeting chat box (the chat is still sent to the model while hidden). */
    /** Shows or hides the suggestion area; hidden, the live transcript takes 80% of the pane space, shown 40/40. */
    public void setSuggestionVisible(boolean show) {
        answerScroll.setVisible(show);
        answerToggle.setText(show ? "Hide" : "Show");
        ((WeightedColumn) columnPanel.getLayout()).weights.put(transcriptScroll,
            show ? TRANSCRIPT_WEIGHT_SHARED : TRANSCRIPT_WEIGHT_ALONE);
        columnPanel.revalidate();
        columnPanel.repaint();
    }

    public void toggleChatVisible() {
        boolean show = !chatScroll.isVisible();
        chatScroll.setVisible(show);
        chatToggle.setText(show ? "Hide" : "Show");
        chatScroll.getParent().revalidate();
        chatScroll.getParent().repaint();
    }

    // Stacks components vertically. Weight 0 = natural height; weighted components split the remaining
    // height in proportion to their weights (a hidden component gives its share to the others).
    private static final class WeightedColumn implements java.awt.LayoutManager {
        private static final int GAP = 4, MIN_PANE = 30;
        private final java.util.Map<java.awt.Component, Double> weights = new java.util.IdentityHashMap<>();

        @Override public void addLayoutComponent(String name, java.awt.Component c) {}
        @Override public void removeLayoutComponent(java.awt.Component c) { weights.remove(c); }

        @Override public Dimension preferredLayoutSize(java.awt.Container p) { return size(p, 100); }
        @Override public Dimension minimumLayoutSize(java.awt.Container p) { return size(p, MIN_PANE); }

        private Dimension size(java.awt.Container p, int weightedHeight) {
            int w = 0, h = 0, n = 0;
            for (java.awt.Component c : p.getComponents()) {
                if (!c.isVisible()) continue;
                n++;
                h += weights.getOrDefault(c, 0.0) > 0 ? weightedHeight : c.getPreferredSize().height;
                w = Math.max(w, c.getPreferredSize().width);
            }
            Insets in = p.getInsets();
            return new Dimension(w + in.left + in.right, h + Math.max(0, n - 1) * GAP + in.top + in.bottom);
        }

        @Override public void layoutContainer(java.awt.Container p) {
            Insets in = p.getInsets();
            int width = p.getWidth() - in.left - in.right;
            int fixed = 0, n = 0;
            double totalWeight = 0;
            for (java.awt.Component c : p.getComponents()) {
                if (!c.isVisible()) continue;
                n++;
                double wt = weights.getOrDefault(c, 0.0);
                if (wt > 0) totalWeight += wt; else fixed += c.getPreferredSize().height;
            }
            int spare = p.getHeight() - in.top - in.bottom - fixed - Math.max(0, n - 1) * GAP;
            int y = in.top;
            for (java.awt.Component c : p.getComponents()) {
                if (!c.isVisible()) continue;
                double wt = weights.getOrDefault(c, 0.0);
                int h = wt > 0 ? Math.max(MIN_PANE, (int) Math.round(spare * wt / totalWeight)) : c.getPreferredSize().height;
                c.setBounds(in.left, y, width, h);
                y += h + GAP;
            }
        }
    }

    private static void add(JPanel p, JComponent c, int x, int y, double weight) {
        ((WeightedColumn) p.getLayout()).weights.put(c, weight);
        p.add(c);
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setForeground(new Color(232, 232, 234, 140));
        l.setFont(l.getFont().deriveFont(10f));
        return l;
    }

    private static JTextArea box(int height) {
        JTextArea t = new JTextArea();
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setForeground(FG);
        t.setOpaque(false);
        return t;
    }

    private static JScrollPane scroll(JComponent c, int height) {
        JScrollPane s = new JScrollPane(c);
        s.setPreferredSize(new Dimension(100, height));
        s.setMinimumSize(new Dimension(50, 30));
        s.setOpaque(false);
        s.getViewport().setOpaque(false);
        s.setBackground(BOX_BG);
        s.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        return s;
    }

    // Drag the panel background to move the borderless window; drag within GRIP px of an edge/corner to resize.
    private void makeDraggable(JComponent c) {
        final int grip = 10;
        final int minW = 320, minH = 360;
        final java.awt.Point[] start = new java.awt.Point[1];   // mouse position (screen coords) at press
        final java.awt.Rectangle[] orig = new java.awt.Rectangle[1];
        final int[] edge = new int[1];                           // bit mask: 1=left 2=right 4=top 8=bottom

        java.util.function.Function<Point, Integer> edgeAt = p -> {
            int e = 0;
            if (p.x < grip) e |= 1;
            if (p.x > c.getWidth() - grip) e |= 2;
            if (p.y < grip) e |= 4;
            if (p.y > c.getHeight() - grip) e |= 8;
            return e;
        };
        c.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) { c.setCursor(cursorFor(edgeAt.apply(e.getPoint()))); }
            @Override public void mouseDragged(MouseEvent e) {
                Point now = e.getLocationOnScreen();
                int dx = now.x - start[0].x, dy = now.y - start[0].y;
                java.awt.Rectangle r = new java.awt.Rectangle(orig[0]);
                if (edge[0] == 0) { r.translate(dx, dy); }
                else {
                    if ((edge[0] & 1) != 0) { r.x += dx; r.width -= dx; }
                    if ((edge[0] & 2) != 0) r.width += dx;
                    if ((edge[0] & 4) != 0) { r.y += dy; r.height -= dy; }
                    if ((edge[0] & 8) != 0) r.height += dy;
                    if (r.width < minW) { if ((edge[0] & 1) != 0) r.x -= minW - r.width; r.width = minW; }
                    if (r.height < minH) { if ((edge[0] & 4) != 0) r.y -= minH - r.height; r.height = minH; }
                }
                frame.setBounds(r);
            }
        });
        c.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                start[0] = e.getLocationOnScreen();
                orig[0] = frame.getBounds();
                edge[0] = edgeAt.apply(e.getPoint());
            }
        });
    }

    private static java.awt.Cursor cursorFor(int edge) {
        return switch (edge) {
            case 1 -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.W_RESIZE_CURSOR);
            case 2 -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR);
            case 4 -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR);
            case 8 -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.S_RESIZE_CURSOR);
            case 5 -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.NW_RESIZE_CURSOR);
            case 6 -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.NE_RESIZE_CURSOR);
            case 9 -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.SW_RESIZE_CURSOR);
            case 10 -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.SE_RESIZE_CURSOR);
            default -> java.awt.Cursor.getDefaultCursor();
        };
    }

    private static String clip(String s) { return s.length() <= 70 ? s : s.substring(0, 67) + "…"; }

    private static String tail(List<String> lines, int n) { return String.join("\n", lastN(lines, n)); }

    private static List<String> lastN(List<String> l, int n) { return l.subList(Math.max(0, l.size() - n), l.size()); }

    private static double rms(float[] s) {
        double sum = 0;
        for (float v : s) sum += v * v;
        return Math.sqrt(sum / Math.max(1, s.length));
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }
}
