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

    /**
     * True if OCR text should not stand in for a screenshot of code. OCR misreads code's symbols ({ as 1,
     * "" as "', dropped brackets), and the model then "fixes" bugs that are not there, so code goes to the
     * vision model as an image unless SNAP_MODE=ocr.
     */
    public static boolean shouldSendImageInstead(String text) {
        return !"ocr".equals(Config.get("SNAP_MODE", "auto")) && looksLikeCode(text);
    }

    private static final java.util.regex.Pattern CODE_LINE = java.util.regex.Pattern.compile(
        "^\\s*(\\d+\\s+)?(import|package|public|private|protected|static|final|class|interface|def|return|if|else|for|while|"
            + "switch|case|try|catch|throw|const|let|var|function|fn|func|#include|using|namespace|SELECT|FROM|WHERE)\\b.*"
            + "|.*([;{}]|\\)\\s*:?|=>|->)\\s*$"
            + "|.*\\w+\\s*\\(.*\\)\\s*[;{]?.*\\b(=|==|!=|&&|\\|\\|)\\b.*");

    /** At least 4 lines and 40% of them shaped like source code (keywords, trailing ; { } ), arrows). */
    static boolean looksLikeCode(String text) {
        if (text == null) return false;
        int lines = 0, code = 0;
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            lines++;
            if (CODE_LINE.matcher(line).matches()) code++;
        }
        return lines >= 4 && code * 10 >= lines * 4;
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
