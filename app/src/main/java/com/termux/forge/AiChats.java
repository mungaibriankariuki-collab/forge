package com.termux.forge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Saved Forge AI chats, one JSON file per chat in the app's private files (never in ~, so other
 * programs in the terminal cannot read them). A chat keeps the raw Gemini conversation
 * ({@code contents}) to continue it, and display {@code entries} to show it again.
 */
public final class AiChats {

    public static final class Chat {
        public String id;
        public String title = "";
        public String mode = "chat"; // "chat" or "agent"
        public String workdir;
        public long updated;
        public JSONArray contents = new JSONArray();
        public JSONArray entries = new JSONArray();
    }

    private AiChats() {}

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "forge/ai-chats");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static Chat create(String mode, String workdir) {
        Chat chat = new Chat();
        chat.id = String.valueOf(System.currentTimeMillis());
        chat.mode = mode;
        chat.workdir = workdir;
        return chat;
    }

    public static void save(Context context, Chat chat) {
        if (chat.entries.length() == 0) return; // Nothing worth keeping yet.
        chat.updated = System.currentTimeMillis();
        try {
            JSONObject json = new JSONObject()
                .put("id", chat.id).put("title", chat.title).put("mode", chat.mode)
                .put("workdir", chat.workdir).put("updated", chat.updated)
                .put("contents", chat.contents).put("entries", chat.entries);
            File file = new File(dir(context), chat.id + ".json");
            File tmp = new File(dir(context), chat.id + ".json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) throw new IOException("rename failed");
        } catch (Exception ignored) {
            // History is a convenience; never break the chat because saving failed.
        }
    }

    public static Chat load(Context context, String id) {
        try {
            File file = new File(dir(context), id + ".json");
            JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            Chat chat = new Chat();
            chat.id = json.getString("id");
            chat.title = json.optString("title", "");
            chat.mode = json.optString("mode", "chat");
            chat.workdir = json.optString("workdir", null);
            chat.updated = json.optLong("updated", 0);
            chat.contents = json.optJSONArray("contents") != null ? json.getJSONArray("contents") : new JSONArray();
            chat.entries = json.optJSONArray("entries") != null ? json.getJSONArray("entries") : new JSONArray();
            return chat;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** All saved chats, newest first, with only id/title/mode/updated filled in. */
    public static List<Chat> list(Context context) {
        File[] files = dir(context).listFiles((d, name) -> name.endsWith(".json"));
        List<Chat> chats = new ArrayList<>();
        if (files == null) return chats;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) {
            Chat chat = load(context, file.getName().replace(".json", ""));
            if (chat != null) {
                chat.contents = new JSONArray();
                chat.entries = new JSONArray();
                chats.add(chat);
            }
        }
        return chats;
    }

    public static void delete(Context context, String id) {
        new File(dir(context), id + ".json").delete();
    }

    public static void deleteAll(Context context) {
        File[] files = dir(context).listFiles();
        if (files != null) for (File file : files) file.delete();
    }
}
