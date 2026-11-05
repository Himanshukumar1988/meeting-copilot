package com.copilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Client for the Paytm inference gateway chat endpoint. */
public final class Llm {
    /** What the user wants suggested, plus the context gathered so far. */
    public record Input(String mode, String transcript, String chat, String question, String imageDataUrl) {}

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
        append(user, "# Meeting chat", in.chat());
        append(user, "# Live transcript (most recent last)", in.transcript());
        append(user, "# User request", in.question());
        boolean hasImage = in.imageDataUrl() != null;
        if (user.isEmpty() && hasImage) user.append("# User request\nSolve or answer what the attached screenshot shows.");
        else if (user.isEmpty()) user.append("# User request\nNo context yet. Say what you need.");

        JsonArray messages = new JsonArray();
        messages.add(message("system", PROMPTS.getOrDefault(in.mode(), PROMPTS.get("reply"))));
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
        Config.Settings cfg = Config.load();
        if (in.imageDataUrl() != null) { // the default text model rejects image content; use a vision model
            cfg = new Config.Settings(cfg.apiKey(), cfg.baseUrl(), Config.get("LLM_VISION_MODEL", "al-gpt-5"));
        }
        return chat(cfg, buildMessages(in));
    }

    /** Low-level call shared with the standalone sample. */
    public static String chat(Config.Settings cfg, JsonArray messages) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        body.add("messages", messages);
        body.addProperty("max_tokens", 4096);
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
