package com.termux.forge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal client for the Gemini API (generateContent and models.list), using the user's own key. */
public final class GeminiClient {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";

    /** One chat turn: role is "user" or "model". */
    public static final class Turn {
        public final String role, text;
        public Turn(String role, String text) { this.role = role; this.text = text; }
    }

    public static final class GeminiException extends IOException {
        public final int status;
        GeminiException(int status, String message) { super(message); this.status = status; }
    }

    private GeminiClient() {}

    /** Sends the conversation and returns the model's reply text. Blocking: call off the main thread. */
    public static String generate(String key, String model, String systemPrompt, List<Turn> turns) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))));
            JSONArray contents = new JSONArray();
            for (Turn turn : turns) {
                contents.put(new JSONObject().put("role", turn.role)
                    .put("parts", new JSONArray().put(new JSONObject().put("text", turn.text))));
            }
            body.put("contents", contents);
            body.put("generationConfig", new JSONObject().put("temperature", 0.3).put("maxOutputTokens", 2048));

            JSONObject response = request("POST", "models/" + encode(model) + ":generateContent", key, body.toString());
            JSONArray candidates = response.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                JSONObject feedback = response.optJSONObject("promptFeedback");
                String reason = feedback == null ? null : feedback.optString("blockReason", null);
                throw new GeminiException(200, reason != null ? "Gemini blocked this request (" + reason + ")." : "Gemini returned no answer.");
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            StringBuilder text = new StringBuilder();
            if (parts != null) for (int i = 0; i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text", ""));
            if (text.length() == 0) throw new GeminiException(200, "Gemini returned an empty answer.");
            return text.toString().trim();
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from Gemini: " + e.getMessage(), e);
        }
    }

    /**
     * Sends raw Gemini {@code contents} (with optional function {@code tools}) and returns the
     * model's reply content object as-is (role "model", parts with text and/or functionCall),
     * so it can be appended to the conversation unchanged. Blocking: call off the main thread.
     */
    public static JSONObject generateRaw(String key, String model, String systemPrompt, JSONArray contents,
                                         JSONArray functionDeclarations) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))));
            body.put("contents", contents);
            if (functionDeclarations != null) {
                body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", functionDeclarations)));
            }
            body.put("generationConfig", new JSONObject().put("temperature", 0.3).put("maxOutputTokens", 4096));

            JSONObject response = request("POST", "models/" + encode(model) + ":generateContent", key, body.toString());
            JSONArray candidates = response.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                JSONObject feedback = response.optJSONObject("promptFeedback");
                String reason = feedback == null ? null : feedback.optString("blockReason", null);
                throw new GeminiException(200, reason != null ? "Gemini blocked this request (" + reason + ")." : "Gemini returned no answer.");
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            if (content == null || content.optJSONArray("parts") == null || content.getJSONArray("parts").length() == 0) {
                String finish = candidates.getJSONObject(0).optString("finishReason", "");
                throw new GeminiException(200, "Gemini returned an empty answer" + (finish.isEmpty() ? "." : " (" + finish + ")."));
            }
            if (!content.has("role")) content.put("role", "model");
            return content;
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from Gemini: " + e.getMessage(), e);
        }
    }

    /** Model names (without "models/") that support generateContent, newest-looking first. */
    public static List<String> listModels(String key) throws IOException {
        List<String> names = new ArrayList<>();
        try {
            JSONObject response = request("GET", "models?pageSize=200", key, null);
            JSONArray models = response.optJSONArray("models");
            if (models != null) {
                for (int i = 0; i < models.length(); i++) {
                    JSONObject model = models.getJSONObject(i);
                    JSONArray methods = model.optJSONArray("supportedGenerationMethods");
                    boolean generates = false;
                    if (methods != null) for (int j = 0; j < methods.length(); j++)
                        if ("generateContent".equals(methods.optString(j))) generates = true;
                    String name = model.optString("name", "").replaceFirst("^models/", "");
                    if (generates && name.startsWith("gemini") && !name.contains("embedding") && !name.contains("image")
                        && !name.contains("tts") && !name.contains("audio")) names.add(name);
                }
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from Gemini: " + e.getMessage(), e);
        }
        return names;
    }

    private static JSONObject request(String method, String path, String key, String body) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        // The key goes in a header, not the URL.
        connection.setRequestProperty("x-goog-api-key", key);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            if (status >= 400) throw new GeminiException(status, friendlyError(status, text));
            return new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }

    private static String friendlyError(int status, String body) {
        String message = null;
        try {
            message = new JSONObject(body).getJSONObject("error").optString("message", null);
        } catch (JSONException ignored) {
        }
        switch (status) {
            case 400: return message != null && message.toLowerCase().contains("api key") ? "Your Gemini API key is not valid. Check it in ⚙." : "Gemini rejected the request: " + message;
            case 403: return "This API key is not allowed to use Gemini (" + message + ").";
            case 404: return "That model is not available to your key. Pick another one in ⚙.";
            case 429: return "Gemini's free limit was reached. Wait a minute and try again, or pick another model in ⚙.";
            default: return "Gemini error " + status + (message != null ? ": " + message : "");
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
