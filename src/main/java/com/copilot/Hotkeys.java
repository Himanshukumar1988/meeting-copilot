package com.copilot;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * System-wide hotkeys (Cmd+Shift+key on macOS, Ctrl+Shift+key elsewhere) so the app never needs focus.
 * On macOS this requires Accessibility permission for the terminal/JVM that launches the app.
 */
public final class Hotkeys {
    public enum Action { TOGGLE_VISIBLE, INGEST_CHAT, SUGGEST, TOGGLE_MIC, TOGGLE_CLICK_THROUGH, SNAP, DOUBLE_SPACE }

    private static final long DOUBLE_TAP_MS = 350;
    private final Map<Action, Runnable> handlers = new EnumMap<>(Action.class);
    // Double-tap Space detection. Holding Space repeats key presses, so a press only counts after a release.
    private boolean spaceDown;
    private long lastSpaceTap;

    private static final int ANY_MODIFIER = NativeKeyEvent.SHIFT_MASK | NativeKeyEvent.CTRL_MASK
        | NativeKeyEvent.ALT_MASK | NativeKeyEvent.META_MASK;

    public Hotkeys on(Action action, Runnable handler) {
        handlers.put(action, handler);
        return this;
    }

    /** Installs the global hook; throws if the OS refuses (e.g. Accessibility permission missing). */
    public void install() throws NativeHookException {
        // JNativeHook unpacks its native library next to the jar by default. Inside the installed .app that
        // writes into the signed bundle, which breaks the signature, and macOS then drops the Accessibility grant.
        // Unpack it into the app's data folder instead. Must be set before GlobalScreen is first initialised.
        if (System.getProperty("jnativehook.lib.path") == null) {
            java.nio.file.Path libDir = AppDirs.path(".bin");
            try {
                java.nio.file.Files.createDirectories(libDir);
                System.setProperty("jnativehook.lib.path", libDir.toString());
            } catch (java.io.IOException e) {
                System.setProperty("jnativehook.lib.path", System.getProperty("java.io.tmpdir"));
            }
        }
        Logger log = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        log.setLevel(Level.WARNING);
        log.setUseParentHandlers(false);
        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
            @Override
            public void nativeKeyPressed(NativeKeyEvent e) {
                int mods = e.getModifiers();
                if (e.getKeyCode() == NativeKeyEvent.VC_SPACE && (mods & ANY_MODIFIER) == 0) {
                    if (spaceDown) return; // key repeat
                    spaceDown = true;
                    long now = System.currentTimeMillis();
                    if (now - lastSpaceTap <= DOUBLE_TAP_MS) {
                        lastSpaceTap = 0;
                        Runnable h = handlers.get(Action.DOUBLE_SPACE);
                        if (h != null) h.run();
                    } else {
                        lastSpaceTap = now;
                    }
                    return;
                }
                lastSpaceTap = 0; // any other key in between: not a double tap
                int main = MacWindow.isMac() ? NativeKeyEvent.META_MASK : NativeKeyEvent.CTRL_MASK;
                if ((mods & main) == 0 || (mods & NativeKeyEvent.SHIFT_MASK) == 0) return;
                Action action = switch (e.getKeyCode()) {
                    case NativeKeyEvent.VC_SPACE -> Action.TOGGLE_VISIBLE;
                    case NativeKeyEvent.VC_C -> Action.INGEST_CHAT;
                    case NativeKeyEvent.VC_ENTER -> Action.SUGGEST;
                    case NativeKeyEvent.VC_M -> Action.TOGGLE_MIC;
                    case NativeKeyEvent.VC_X -> Action.TOGGLE_CLICK_THROUGH;
                    case NativeKeyEvent.VC_S -> Action.SNAP;
                    default -> null;
                };
                Runnable h = action == null ? null : handlers.get(action);
                if (h != null) h.run();
            }

            @Override
            public void nativeKeyReleased(NativeKeyEvent e) {
                if (e.getKeyCode() == NativeKeyEvent.VC_SPACE) spaceDown = false;
            }
        });
    }

    public void uninstall() {
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException ignored) {
            // Shutting down; nothing useful to do.
        }
    }
}
