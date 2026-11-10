package com.copilot;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Reads the text in a screenshot on-device (macOS Vision), so a fast text model can answer instead of a slow vision model. */
public final class Ocr {
    /** Below this many characters a screenshot is mostly graphics, so the vision model is used instead. */
    public static final int MIN_USEFUL_CHARS = 40;

    private Ocr() {}

    /** True unless disabled with SNAP_MODE=vision (or not on macOS). */
    public static boolean enabled() {
        return MacWindow.isMac() && !"vision".equals(Config.get("SNAP_MODE", "auto"));
    }

    /** Returns the recognised text ("" if none). Throws if the helper can't be built or run. */
    public static String read(BufferedImage image) throws IOException, InterruptedException {
        Path helper = Helpers.ensure("ocr");
        Path tmp = Files.createTempFile("snap-ocr", ".png");
        try {
            ImageIO.write(image, "png", tmp.toFile()); // full resolution: OCR accuracy matters more than size
            Process p = new ProcessBuilder(helper.toString(), tmp.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            if (p.waitFor() != 0) throw new IOException("OCR failed: " + out);
            return out;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
