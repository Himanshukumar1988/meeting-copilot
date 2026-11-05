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
    public enum Action { TOGGLE_VISIBLE, INGEST_CHAT, SUGGEST, TOGGLE_MIC, TOGGLE_CLICK_THROUGH, SNAP }

    private final Map<Action, Runnable> handlers = new EnumMap<>(Action.class);

    public Hotkeys on(Action action, Runnable handler) {
        handlers.put(action, handler);
        return this;
    }

    /** Installs the global hook; throws if the OS refuses (e.g. Accessibility permission missing). */
    public void install() throws NativeHookException {
        Logger log = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        log.setLevel(Level.WARNING);
        log.setUseParentHandlers(false);
        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
            @Override
            public void nativeKeyPressed(NativeKeyEvent e) {
                int mods = e.getModifiers();
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
