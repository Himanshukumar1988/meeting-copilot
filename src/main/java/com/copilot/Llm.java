package com.copilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Client for the Paytm inference gateway chat endpoint. */
public final class Llm {
    /** What the user wants suggested, plus the context gathered so far. */
    /**
     * @param selection text the user highlighted in the transcript/chat/suggestion; when present it is the
     *                  main subject and the transcript and chat are only background
     */
    public record Input(String mode, String transcript, String chat, String question, String imageDataUrl, String selection,
                        String screenText) {
        /** Convenience for callers without a screenshot text. */
        public Input(String mode, String transcript, String chat, String question, String imageDataUrl, String selection) {
            this(mode, transcript, chat, question, imageDataUrl, selection, null);
        }

        /** Convenience for callers without a selection. */
        public Input(String mode, String transcript, String chat, String question, String imageDataUrl) {
            this(mode, transcript, chat, question, imageDataUrl, null, null);
        }
    }

    private static final Map<String, String> PROMPTS = Map.of(
        "reply",
        "You are a discreet real-time meeting copilot. Given the meeting transcript and chat, "
            + "write the reply the user should say next: 1-3 short, natural, first-person sentences. "
            + "No preamble, no markdown headings.",
        "code",
        "You are a senior software engineer helping live. Solve the coding problem described. "
            + "Give: 1) one-line approach, 2) complete runnable code in a fenced block, 3) time/space complexity. Be concise.",
        "design",
        "You are a staff engineer helping with a live system-design discussion. Give: requirements/assumptions, "
            + "high-level components, data model, key trade-offs, and scaling/failure points as tight bullets. Be concise.");

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private Llm() {}

    // Builds the chat messages for a mode from chat, transcript and an optional explicit question.
    static JsonArray buildMessages(Input in) {
        StringBuilder user = new StringBuilder();
        boolean hasSelection = in.selection() != null && !in.selection().isBlank();
        if (hasSelection) append(user, "# Selected text (work on THIS)", in.selection());
        String background = hasSelection ? " (background only)" : "";
        append(user, "# Meeting chat" + background, in.chat());
        append(user, "# Live transcript (most recent last)" + background, in.transcript());
        boolean hasScreenText = in.screenText() != null && !in.screenText().isBlank();
        if (hasScreenText) append(user, "# Text read from the user's screenshot (OCR, may contain small errors)", in.screenText());
        append(user, "# User request", in.question());
        boolean hasImage = in.imageDataUrl() != null;
        if (user.isEmpty() && hasScreenText) user.append("# User request\nSolve or answer what the screenshot text shows.");
        else if (user.isEmpty() && hasImage) user.append("# User request\nSolve or answer what the attached screenshot shows.");
        else if (user.isEmpty()) user.append("# User request\nNo context yet. Say what you need.");

        JsonArray messages = new JsonArray();
        String system = PROMPTS.getOrDefault(in.mode(), PROMPTS.get("reply"));
        if (hasSelection) {
            system += " The user highlighted a passage under 'Selected text': base your answer on that passage, "
                + "and use the other sections only as background context.";
        }
        messages.add(message("system", system));
        if (hasImage) {
            // OpenAI-style multimodal content: a text part plus an image_url part (data URL).
            JsonArray parts = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", user.toString());
            JsonObject url = new JsonObject();
            url.addProperty("url", in.imageDataUrl());
            JsonObject image = new JsonObject();
            image.addProperty("type", "image_url");
            image.add("image_url", url);
            parts.add(text);
            parts.add(image);
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            m.add("content", parts);
            messages.add(m);
        } else {
            messages.add(message("user", user.toString()));
        }
        return messages;
    }

    private static void append(StringBuilder sb, String heading, String body) {
        if (body == null || body.isBlank()) return;
        if (!sb.isEmpty()) sb.append("\n\n");
        sb.append(heading).append('\n').append(body.strip());
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** Sends one non-streaming request and returns the assistant text ("" if the reply has none). */
    public static String complete(Input in) throws IOException, InterruptedException {
        return chat(settingsFor(in), buildMessages(in)); // the default text model rejects images, so settingsFor swaps it
    }

    /**
     * Like {@link #complete} but streams: {@code onDelta} receives each new piece of answer text as it is
     * generated (reasoning tokens are skipped). Returns the full text. Interrupting the thread cancels it.
     */
    public static String stream(Input in, Consumer<String> onDelta) throws IOException, InterruptedException {
        Config.Settings cfg = settingsFor(in);
        JsonObject body = requestBody(cfg, buildMessages(in));
        body.addProperty("stream", true);
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/ai/playground"))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer " + cfg.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            String text = new String(res.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("LLM " + res.statusCode() + ": " + text.substring(0, Math.min(300, text.length())));
        }
        StringBuilder all = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) break;
                JsonArray choices = JsonParser.parseString(data).getAsJsonObject().getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) continue;
                JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                if (delta == null || !delta.has("content") || delta.get("content").isJsonNull()) continue;
                String piece = delta.get("content").getAsString();
                if (piece.isEmpty()) continue;
                all.append(piece);
                onDelta.accept(piece);
            }
        } catch (InterruptedIOException e) {
            throw new InterruptedException();
        } catch (IOException e) {
            // HttpClient reports a thread interrupt during a body read as an IOException with this cause.
            if (e.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            throw e;
        }
        return all.toString();
    }

    // Text requests use the normal model; image requests need a vision-capable one.
    private static Config.Settings settingsFor(Input in) {
        Config.Settings cfg = Config.load();
        if (in.imageDataUrl() == null) return cfg;
        return new Config.Settings(cfg.apiKey(), cfg.baseUrl(), Config.get("LLM_VISION_MODEL", "al-gpt-5"));
    }

    private static JsonObject requestBody(Config.Settings cfg, JsonArray messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        body.add("messages", messages);
        body.addProperty("max_tokens", 4096);
        return body;
    }

    /**
     * Translates one transcript line to English with the text model. Meant for speech-to-text output, so it
     * copes with code-mixed speech (Hinglish) and recognition errors, and returns English input unchanged.
     */
    public static String translateToEnglish(String text) throws IOException, InterruptedException {
        JsonArray messages = new JsonArray();
        messages.add(message("system", "You translate speech-to-text transcript lines into natural English. "
            + "The input may be Hindi (Devanagari or romanised), Hinglish (Hindi mixed with English), or any other language, "
            + "and may contain recognition errors: infer the intended meaning. Keep names, product names and technical terms "
            + "(e.g. QA, API, release) as they are. If the line is already English, return it unchanged. "
            + "Output ONLY the English text, with no quotes or explanations."));
        messages.add(message("user", text));
        return chat(Config.load(), messages).strip();
    }

    /** Low-level call shared with the standalone sample. */
    public static String chat(Config.Settings cfg, JsonArray messages) throws IOException, InterruptedException {
        JsonObject body = requestBody(cfg, messages);
        body.addProperty("stream", false);

        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/ai/playground"))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer " + cfg.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            String text = res.body();
            throw new IOException("LLM " + res.statusCode() + ": " + text.substring(0, Math.min(300, text.length())));
        }
        JsonArray choices = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) return "";
        JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        return msg == null || msg.get("content").isJsonNull() ? "" : msg.get("content").getAsString();
    }
}
