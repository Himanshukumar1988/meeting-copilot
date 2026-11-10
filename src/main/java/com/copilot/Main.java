package com.copilot;

import com.github.kwhat.jnativehook.NativeHookException;
import javax.swing.SwingUtilities;

/** Entry point: starts the overlay and registers global hotkeys. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        // Must be set before AWT initialises: hides the Dock icon so the app stays low-profile on macOS.
        System.setProperty("apple.awt.UIElement", "true");

        SwingUtilities.invokeLater(() -> {
            OverlayWindow overlay = new OverlayWindow();
            overlay.show();
            if (Config.ensureEnvFile() || Config.get("LLM_API_KEY", null) == null) {
                overlay.setStatus("First run: add your LLM_API_KEY to " + AppDirs.path(".env") + " and restart the app.");
            }

            Hotkeys hotkeys = new Hotkeys()
                .on(Hotkeys.Action.TOGGLE_VISIBLE, () -> SwingUtilities.invokeLater(overlay::toggleVisible))
                .on(Hotkeys.Action.INGEST_CHAT, () -> SwingUtilities.invokeLater(overlay::ingestChat))
                .on(Hotkeys.Action.SUGGEST, () -> SwingUtilities.invokeLater(overlay::suggest))
                .on(Hotkeys.Action.TOGGLE_MIC, () -> SwingUtilities.invokeLater(overlay::toggleMic))
                .on(Hotkeys.Action.TOGGLE_CLICK_THROUGH, () -> SwingUtilities.invokeLater(overlay::toggleClickThrough))
                .on(Hotkeys.Action.SNAP, overlay::snap);
            try {
                hotkeys.install();
                Runtime.getRuntime().addShutdownHook(new Thread(hotkeys::uninstall));
            } catch (NativeHookException e) {
                overlay.setStatus("Hotkeys need Accessibility permission (" + e.getMessage() + "). System Settings → Privacy & Security → "
                    + "Accessibility: if this app is listed, remove it with (−) and add it again, then restart. Buttons still work.");
            }
        });
    }
}
