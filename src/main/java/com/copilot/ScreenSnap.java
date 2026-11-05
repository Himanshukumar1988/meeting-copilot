package com.copilot;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Captures the primary screen and encodes it as a JPEG data URL for vision-capable models. */
public final class ScreenSnap {
    private static final int MAX_WIDTH = 1600;

    /** A captured image ready to send, plus its size for display. */
    public record Snap(String dataUrl, int width, int height) {}

    private ScreenSnap() {}

    /**
     * Grabs the primary display. On macOS this needs Screen Recording permission for the launching terminal.
     * The overlay itself is excluded from capture when screen-share protection is active.
     */
    public static BufferedImage grab() throws Exception {
        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
            .getDefaultConfiguration().getBounds();
        return new Robot().createScreenCapture(bounds);
    }

    /** Captures the whole primary display, downscaled and encoded. */
    public static Snap capture() throws Exception { return encode(grab()); }

    /** Downscales (max 1600px wide) and encodes an image as a JPEG data URL. */
    public static Snap encode(BufferedImage img) throws IOException {
        BufferedImage small = downscale(img);
        return new Snap("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(toJpeg(small)),
            small.getWidth(), small.getHeight());
    }

    private static BufferedImage downscale(BufferedImage src) {
        if (src.getWidth() <= MAX_WIDTH) return toRgb(src, src.getWidth(), src.getHeight());
        int h = Math.round(src.getHeight() * (MAX_WIDTH / (float) src.getWidth()));
        return toRgb(src, MAX_WIDTH, h);
    }

    // JPEG has no alpha channel, so draw onto an opaque RGB image.
    private static BufferedImage toRgb(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] toJpeg(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "jpg", out)) throw new IOException("No JPEG encoder available");
        return out.toByteArray();
    }
}
