package com.copilot;

import java.awt.Desktop;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Reverse image search: uploads a snapped screenshot to Google Lens and opens the results in the browser.
 * Lens has no official API; its web upload endpoint answers with a redirect to the results page. If that
 * fails, the image is put on the clipboard and Lens is opened so the user can paste it (Cmd+V).
 */
public final class ImageSearch {
    private static final String UPLOAD_URL = "https://lens.google.com/v3/upload?hl=en&ep=ccm&st=";
    private static final String FALLBACK_URL = "https://lens.google.com/";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER) // we want the redirect target, not the page itself
        .build();

    /** How the search was opened, for the status line. */
    public enum Result { OPENED, PASTE_FALLBACK }

    private ImageSearch() {}

    /** Searches for the image in a "data:image/jpeg;base64,..." URL and opens the results in the browser. */
    public static Result search(String dataUrl) throws IOException {
        byte[] jpeg = decodeDataUrl(dataUrl);
        try {
            browse(uploadToLens(jpeg));
            return Result.OPENED;
        } catch (Exception e) {
            System.err.println("Lens upload failed, falling back to paste: " + e);
            copyImageToClipboard(jpeg);
            browse(FALLBACK_URL);
            return Result.PASTE_FALLBACK;
        }
    }

    // Posts the image as multipart form data and returns the results URL from the redirect.
    static String uploadToLens(byte[] jpeg) throws IOException, InterruptedException {
        String boundary = "----copilot" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"encoded_image\"; filename=\"snap.jpg\"\r\n"
            + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(jpeg);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder(URI.create(UPLOAD_URL + System.currentTimeMillis()))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
        HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        String location = res.headers().firstValue("location").orElse(null);
        if (res.statusCode() / 100 != 3 || location == null || !location.startsWith("https://")) {
            throw new IOException("unexpected Lens response: HTTP " + res.statusCode());
        }
        return location;
    }

    static byte[] decodeDataUrl(String dataUrl) throws IOException {
        if (dataUrl == null) throw new IOException("no screenshot attached");
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:") || comma < 0) throw new IOException("not an image data URL");
        return Base64.getDecoder().decode(dataUrl.substring(comma + 1));
    }

    private static void browse(String url) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
        } else {
            new ProcessBuilder("open", url).start();
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
