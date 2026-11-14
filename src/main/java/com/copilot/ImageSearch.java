package com.copilot;

import java.awt.Desktop;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Reverse image search: sends a snapped screenshot to Google Lens and shows the results in the browser.
 *
 * The browser itself must do the upload. Lens ties a search to the session that uploaded the image, so a
 * results link obtained by uploading from this app opens in the browser as "expired visual search". Instead a
 * small temporary page is opened that posts the image to Lens from the browser (the same way Lens's own
 * upload form does). If that page can't be written, the image goes on the clipboard and Lens is opened so the
 * user can paste it (Cmd+V).
 */
public final class ImageSearch {
    private static final String UPLOAD_URL = "https://lens.google.com/v3/upload?hl=en&ep=ccm&st=";
    private static final String FALLBACK_URL = "https://lens.google.com/";
    private static final long PAGE_LIFETIME_MS = 120_000; // the browser reads the page within moments

    /** How the search was opened, for the status line. */
    public enum Result { OPENED, PASTE_FALLBACK }

    private ImageSearch() {}

    /** Searches for the image in a "data:image/jpeg;base64,..." URL and opens the results in the browser. */
    public static Result search(String dataUrl) throws IOException {
        byte[] jpeg = decodeDataUrl(dataUrl);
        try {
            Path page = writeUploadPage(jpeg);
            browse(page.toUri());
            return Result.OPENED;
        } catch (Exception e) {
            System.err.println("Lens upload page failed, falling back to paste: " + e);
            copyImageToClipboard(jpeg);
            browse(URI.create(FALLBACK_URL));
            return Result.PASTE_FALLBACK;
        }
    }

    // A page that, once opened, puts the image into a file field and submits it to Lens.
    static Path writeUploadPage(byte[] jpeg) throws IOException {
        Path page = Files.createTempFile("copilot-lens-", ".html");
        Files.writeString(page, uploadPageHtml(Base64.getEncoder().encodeToString(jpeg),
            UPLOAD_URL + System.currentTimeMillis()), StandardCharsets.UTF_8);
        page.toFile().deleteOnExit();
        Thread cleanup = new Thread(() -> {
            try { Thread.sleep(PAGE_LIFETIME_MS); Files.deleteIfExists(page); } catch (Exception ignored) {}
        }, "lens-page-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        return page;
    }

    static String uploadPageHtml(String base64Jpeg, String action) {
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>Searching with Google Lens…</title></head>
            <body style="font:15px -apple-system,sans-serif;margin:40px;color:#333">
            <p id="msg">Sending the screenshot to Google Lens…</p>
            <form id="f" method="POST" enctype="multipart/form-data" action="%s">
              <input type="file" name="encoded_image" id="img" hidden>
            </form>
            <script>
            try {
              const bin = atob("%s");
              const bytes = new Uint8Array(bin.length);
              for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
              const dt = new DataTransfer();
              dt.items.add(new File([bytes], "snap.jpg", {type: "image/jpeg"}));
              document.getElementById("img").files = dt.files;
              document.getElementById("f").submit();
            } catch (e) {
              document.getElementById("msg").textContent =
                "This browser could not send the screenshot (" + e + "). Open lens.google.com and paste the image instead.";
            }
            </script>
            </body></html>
            """.formatted(action, base64Jpeg);
    }

    static byte[] decodeDataUrl(String dataUrl) throws IOException {
        if (dataUrl == null) throw new IOException("no screenshot attached");
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:") || comma < 0) throw new IOException("not an image data URL");
        return Base64.getDecoder().decode(dataUrl.substring(comma + 1));
    }

    private static void browse(URI uri) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri);
        } else {
            new ProcessBuilder("open", uri.toString()).start();
        }
    }

    private static void copyImageToClipboard(byte[] jpeg) throws IOException {
        Image img = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (img == null) throw new IOException("could not decode the screenshot");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {DataFlavor.imageFlavor}; }
            @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }
            @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
                return img;
            }
        }, null);
    }
}
