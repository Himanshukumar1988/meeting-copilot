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
    private final ExecutorService llmWorker = Executors.newSingleThreadExecutor(daemon("llm"));

    private final JButton snapBtn = new JButton("Snap");
    private volatile ScreenSnap.Snap snapshot;
    private volatile boolean refreshPending;
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
            java.nio.file.Path f = java.nio.file.Path.of(".logs", "copilot.log");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, java.time.LocalTime.now().withNano(0) + "  " + msg + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
            // logging is best-effort
        }
    }

    // ---- actions --------------------------------------------------------------------------

    /** Sends the current context to the LLM and shows the suggestion. A request already in flight is cancelled. */
    public void suggest() {
        cancelSuggest();
        String selectedMode = (String) mode.getSelectedItem();
        int lines = "reply".equals(selectedMode) ? REPLY_LINES : MAX_LINES;
        String selection = takeSelection();
        Llm.Input in = new Llm.Input(selectedMode, tail(transcript.lines(), lines), tail(chat, lines), question.getText(),
            snapshot == null ? null : snapshot.dataUrl(), selection);
        boolean sendingImage = snapshot != null;
        setStatus(selection == null ? "Thinking…" : "Thinking about the selected text (" + selection.length() + " chars)…");
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
                if (sendingImage) clearSnap(); // the image belongs to this request only
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
            ScreenSnap.Snap s = ScreenSnap.encode(shot.getSubimage(r.x, r.y, r.width, r.height));
            snapshot = s;
            SwingUtilities.invokeLater(() -> snapBtn.setText("Snap ✓"));
            setStatus("Screenshot attached (" + s.width() + "x" + s.height() + "). Sent with the next Suggest; right-click Snap to drop it.");
        } catch (Exception e) {
            setStatus("Screenshot failed: " + e.getMessage());
        }
    }

    private void clearSnap() {
        snapshot = null;
        SwingUtilities.invokeLater(() -> snapBtn.setText("Snap"));
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
        sttWorker.submit(() -> {
            try {
                String text = SttLocal.transcribe(samples);
                // add() returns false for a mic line that merely echoes the other side; it may also remove
                // an earlier echo, so refresh either way.
                if (!text.isEmpty()) {
                    transcript.add(speaker, text, System.currentTimeMillis());
                    SwingUtilities.invokeLater(this::refresh);
                }
            } catch (Exception e) {
                if (!sttFailed) { sttFailed = true; setStatus("Voice-to-text failed: " + e.getMessage()); }
            }
        });
    }

    // ---- UI helpers -----------------------------------------------------------------------

    /** Combo entry for an audio input; a null Mixer.Info means the system default. */
    private enum Source { MIC, SYSTEM, BOTH }

    private record DeviceItem(javax.sound.sampled.Mixer.Info info, Source source) {
        DeviceItem(javax.sound.sampled.Mixer.Info info) { this(info, Source.MIC); }

        @Override public String toString() {
            return switch (source) {
                case SYSTEM -> "System audio (other side)";
                case BOTH -> "Mic + system audio";
                case MIC -> info == null ? "Default mic" : info.getName();
            };
        }
    }

    private javax.sound.sampled.Mixer.Info selectedDevice() {
        DeviceItem d = (DeviceItem) inputBox.getSelectedItem();
        return d == null ? null : d.info(); // null for the default mic and for system-only
    }

    // Lists the available inputs, keeping the current choice; re-run when the popup opens to pick up new devices.
    private void refreshInputs() {
        DeviceItem current = (DeviceItem) inputBox.getSelectedItem();
        inputBox.removeAllItems();
        inputBox.addItem(new DeviceItem(null));
        if (MacWindow.isMac()) {
            inputBox.addItem(new DeviceItem(null, Source.SYSTEM));
            inputBox.addItem(new DeviceItem(null, Source.BOTH));
        }
        for (javax.sound.sampled.Mixer.Info mi : MicRecorder.inputDevices()) {
            if (!mi.getName().equals("Default Audio Device")) inputBox.addItem(new DeviceItem(mi));
        }
        if (current != null) inputBox.setSelectedItem(current);
        else if (MacWindow.isMac()) inputBox.setSelectedIndex(2); // Mic + system audio: hears the caller even with headphones
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

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.add(mode); buttons.add(mic); buttons.add(snapBtn); buttons.add(chatBtn); buttons.add(goBtn);
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
        tRight.add(copyTranscript);
        tRight.add(inputBox);
        tHead.add(tRight, BorderLayout.EAST);
        add(panel, tHead, 0, 1, 0);
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
