package com.termux.forge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge AI, using Gemini with the user's own key.
 *
 * Chat mode: questions and answers; commands in answers get Copy / Insert / Run buttons.
 * Agent mode: the user describes a task and the AI works through it with tools (run a command,
 * read/write/list files, finish). By default every command and file write waits for the user's
 * Approve; in "auto" mode only risky commands wait. Chats are saved and can be reopened.
 */
public class AiActivity extends Activity {

    public static final String EXTRA_CONTEXT = "com.termux.forge.ai_context";
    public static final String EXTRA_CONTEXT_LABEL = "com.termux.forge.ai_context_label";
    public static final String EXTRA_QUESTION = "com.termux.forge.ai_question";
    public static final String EXTRA_AUTOSEND = "com.termux.forge.ai_autosend";
    /** True when opened from the terminal for a result: then Insert/Run buttons are shown. */
    public static final String EXTRA_CAN_INSERT = "com.termux.forge.ai_can_insert";
    /** Folder the agent works in (terminal's folder or the editor's project). */
    public static final String EXTRA_WORKDIR = "com.termux.forge.ai_workdir";
    /** "agent" to start in agent mode. */
    public static final String EXTRA_MODE = "com.termux.forge.ai_mode";
    /** True to show the saved chats list right away. */
    public static final String EXTRA_SHOW_HISTORY = "com.termux.forge.ai_show_history";

    /** Result extras: the command to put into the terminal, and whether to press Enter. */
    public static final String RESULT_COMMAND = "com.termux.forge.ai_command";
    public static final String RESULT_RUN = "com.termux.forge.ai_run";

    public static final String DEFAULT_MODEL = "gemini-flash-lite-latest";

    private static final int BG = 0xFF101118;
    private static final int CARD = 0xFF1A1C26;
    private static final int TEXT = 0xFFE8E8EE;
    private static final int MUTED = 0xFF9A9CAA;
    private static final int CODE_BG = 0xFF0B0C10;
    private static final int GREEN = 0xFF4ADE80;
    private static final int RED = 0xFFFF6B6B;

    private static final int MAX_AGENT_STEPS = 25;
    private static final int COMMAND_TIMEOUT_SECONDS = 120;
    private static final int MAX_TOOL_OUTPUT = 6000;

    private static final String ENVIRONMENT =
        "Environment: Forge, an Android terminal app based on Termux. No root. Shell is bash. Package manager is `pkg` " +
        "(apt underneath). Prefix /data/data/com.termux/files/usr, home ~ = /data/data/com.termux/files/home, shared phone " +
        "storage at ~/storage/shared after termux-setup-storage. Never use sudo. Use `pkg install` for system packages and " +
        "pip/npm/cargo/go for language packages.";

    private static final String CHAT_PROMPT =
        "You are Forge AI, the assistant inside Forge.\n" + ENVIRONMENT + "\n" +
        "The user may share the end of their terminal output or a source file.\n" +
        "Rules: be short and practical; explain errors in plain words, then give the fix. Put every command the user should run " +
        "in its own ```bash code block, one command per line, no $ prompts. Warn clearly before anything that deletes data.";

    private static final String AGENT_PROMPT =
        "You are Forge Agent, an autonomous coding agent inside Forge.\n" + ENVIRONMENT + "\n" +
        "You complete the user's task by calling tools. Work in small steps: run a command or write a file, look at the result, " +
        "then decide the next step. Rules:\n" +
        "- Commands run non-interactively with no input: always use -y / --yes / non-interactive flags.\n" +
        "- Never start a program that keeps running in the foreground (servers, watchers). Start it in the background with " +
        "`nohup CMD > LOG 2>&1 &`, wait briefly, then check it (for example with curl) and read the log.\n" +
        "- Prefer write_file over shell redirection for creating files. Paths may be relative to the working folder.\n" +
        "- If a step fails, read the error and fix it; do not repeat the same failing command.\n" +
        "- Do not delete files unless the task clearly requires it.\n" +
        "- When the task is done (or you need the user to decide something), call finish with a short summary of what you " +
        "did, where the files are and how to run the result.";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();

    private AiChats.Chat mChat;
    private LinearLayout mMessages;
    private ScrollView mScroll;
    private EditText mInput;
    private TextView mSend;
    private TextView mContextChip;
    private TextView mModeChip;
    private TextView mAutoChip;
    private TextView mFolderChip;
    private LinearLayout mQuick;

    private String mContext;
    private String mContextLabel;
    private boolean mIncludeContext;
    private boolean mCanInsert;
    private boolean mBusy;

    // Agent state.
    private boolean mStopRequested;
    private int mSteps;

    /** Opens the AI with the given context, for use without a terminal result (e.g. from the editor). */
    public static void open(Context context, String contextText, String contextLabel, String question) {
        Intent intent = new Intent(context, AiActivity.class)
            .putExtra(EXTRA_CONTEXT, contextText)
            .putExtra(EXTRA_CONTEXT_LABEL, contextLabel)
            .putExtra(EXTRA_QUESTION, question);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        Intent intent = getIntent();
        mContext = intent.getStringExtra(EXTRA_CONTEXT);
        mContextLabel = intent.getStringExtra(EXTRA_CONTEXT_LABEL);
        mIncludeContext = mContext != null && !mContext.trim().isEmpty();
        mCanInsert = intent.getBooleanExtra(EXTRA_CAN_INSERT, false);
        String workdir = intent.getStringExtra(EXTRA_WORKDIR);
        if (workdir == null || !new File(workdir).isDirectory()) workdir = TermuxConstants.TERMUX_HOME_DIR_PATH;
        mChat = AiChats.create("agent".equals(intent.getStringExtra(EXTRA_MODE)) ? "agent" : "chat", workdir);

        if (apiKey().isEmpty()) showKeySetup(); else showChat();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AiChats.save(this, mChat);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mStopRequested = true;
        mWorker.shutdownNow();
    }

    private boolean isAgent() {
        return "agent".equals(mChat.mode);
    }

    // ---- Key setup ----

    private String apiKey() {
        return ForgeSettings.getString(this, "gemini_key", "");
    }

    private String model() {
        return ForgeSettings.getString(this, "gemini_model", DEFAULT_MODEL);
    }

    private void showKeySetup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);

        TextView title = text("✦ Forge AI", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(spaced(text("Explains errors, suggests commands, answers coding questions, and in Agent mode builds " +
            "things for you step by step, using Google Gemini.\n\n" +
            "It uses your own free Gemini API key, which stays on this phone. What you send (your question and, if you allow it, " +
            "the end of your terminal, the open file, or in Agent mode the results of its commands) goes to Google to get the answer.", 14, MUTED), 8));

        TextView get = button("1. Get a free key (Google AI Studio)", ForgeColors.ACCENT, Color.BLACK);
        get.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))));
        root.addView(spaced(get, 20));

        root.addView(spaced(text("2. Paste the key here:", 14, TEXT), 18));
        EditText key = new EditText(this);
        key.setHint("AIza…");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTextColor(TEXT);
        key.setHintTextColor(MUTED);
        root.addView(key);

        TextView save = button("Save and test", 0xFF2A2C38, TEXT);
        root.addView(spaced(save, 12));
        save.setOnClickListener(v -> {
            String value = key.getText().toString().trim();
            if (value.isEmpty()) { toast("Paste your key first"); return; }
            save.setText("Testing…");
            save.setEnabled(false);
            mWorker.execute(() -> {
                try {
                    List<String> models = GeminiClient.listModels(value);
                    String chosen = models.contains(model()) ? model() : pickDefault(models);
                    mHandler.post(() -> {
                        ForgeSettings.setString(this, "gemini_key", value);
                        ForgeSettings.setString(this, "gemini_model", chosen);
                        toast("Key works. Model: " + chosen);
                        showChat();
                    });
                } catch (Exception e) {
                    mHandler.post(() -> {
                        save.setText("Save and test");
                        save.setEnabled(true);
                        toast(e.getMessage());
                    });
                }
            });
        });
        setContentView(scroll);
    }

    private static String pickDefault(List<String> models) {
        for (String m : models) if (m.equals(DEFAULT_MODEL)) return m;
        for (String m : models) if (m.contains("flash-lite") && m.contains("latest")) return m;
        for (String m : models) if (m.contains("flash")) return m;
        return models.isEmpty() ? DEFAULT_MODEL : models.get(0);
    }

    // ---- Layout ----

    private void showChat() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(10), dp(8), dp(4));
        TextView title = text("✦ Forge AI", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(chip("History", v -> showHistory()));
        header.addView(chip("New", v -> newChat(mChat.mode)));
        root.addView(header);

        HorizontalScrollView optionsScroll = new HorizontalScrollView(this);
        optionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout options = new LinearLayout(this);
        options.setPadding(dp(11), 0, dp(11), dp(6));
        mModeChip = chip("", v -> toggleMode());
        options.addView(mModeChip);
        mAutoChip = chip("", v -> {
            ForgeSettings.setFlag(this, "agent_auto", !ForgeSettings.getFlag(this, "agent_auto", false));
            updateChips();
        });
        options.addView(mAutoChip);
        mFolderChip = chip("", v -> askFolder());
        options.addView(mFolderChip);
        options.addView(chip(model(), this::chooseModel));
        if (mContext != null && !mContext.trim().isEmpty()) {
            mContextChip = chip("", v -> { mIncludeContext = !mIncludeContext; updateChips(); });
            options.addView(mContextChip);
        }
        optionsScroll.addView(options);
        root.addView(optionsScroll);

        mScroll = new ScrollView(this);
        mMessages = column();
        mMessages.setPadding(dp(12), dp(4), dp(12), dp(12));
        mScroll.addView(mMessages);
        root.addView(mScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView quickScroll = new HorizontalScrollView(this);
        quickScroll.setHorizontalScrollBarEnabled(false);
        mQuick = new LinearLayout(this);
        mQuick.setPadding(dp(12), dp(4), dp(12), dp(4));
        quickScroll.addView(mQuick);
        root.addView(quickScroll);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(12), dp(4), dp(8), dp(10));
        mInput = new EditText(this);
        mInput.setTextColor(TEXT);
        mInput.setHintTextColor(MUTED);
        mInput.setMaxLines(5);
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        inputRow.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mSend = button("Send", ForgeColors.ACCENT, Color.BLACK);
        mSend.setOnClickListener(v -> {
            if (mBusy && isAgent()) { mStopRequested = true; mSend.setText("Stopping…"); return; }
            send(mInput.getText().toString());
        });
        inputRow.addView(mSend, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(inputRow);

        setContentView(root);
        updateChips();
        renderAll();

        if (getIntent().getBooleanExtra(EXTRA_SHOW_HISTORY, false)) {
            getIntent().removeExtra(EXTRA_SHOW_HISTORY);
            showHistory();
        }
        String question = getIntent().getStringExtra(EXTRA_QUESTION);
        if (question != null && !question.trim().isEmpty()) {
            getIntent().removeExtra(EXTRA_QUESTION);
            if (getIntent().getBooleanExtra(EXTRA_AUTOSEND, false)) send(question);
            else mInput.setText(question);
        }
    }

    private void updateChips() {
        boolean agent = isAgent();
        mModeChip.setText(agent ? "🤖 Agent mode" : "💬 Chat mode");
        mModeChip.setTextColor(agent ? ForgeColors.ACCENT : TEXT);
        boolean auto = ForgeSettings.getFlag(this, "agent_auto", false);
        mAutoChip.setVisibility(agent ? View.VISIBLE : View.GONE);
        mAutoChip.setText(auto ? "⚡ Auto-run safe steps" : "✋ Ask before each step");
        mAutoChip.setTextColor(auto ? 0xFFFFD166 : TEXT);
        mFolderChip.setVisibility(agent ? View.VISIBLE : View.GONE);
        mFolderChip.setText("📁 " + shortPath(mChat.workdir));
        if (mContextChip != null) {
            String what = "terminal".equals(mContextLabel) ? "terminal" : mContextLabel;
            mContextChip.setText(mIncludeContext ? "✓ Share " + what : "Share " + what + "?");
            mContextChip.setTextColor(mIncludeContext ? ForgeColors.ACCENT : MUTED);
        }
        mInput.setHint(agent ? "Describe a task, e.g. \"make a Flask app that shows the time\"" : "Ask anything, e.g. \"how do I unzip a file?\"");
        if (!mBusy) mSend.setText(agent ? "Start" : "Send");

        mQuick.removeAllViews();
        String[] questions;
        if (agent) {
            questions = new String[]{"Make a Python script that renames photos by date", "Create a Node.js web server on port 8080 and test it",
                "Set up a new git repo here with a README", "Find what is using the most space in ~"};
        } else if ("terminal".equals(mContextLabel)) {
            questions = new String[]{"Explain the last error", "How do I fix it?", "Explain this output", "What should I run next?"};
        } else if (mContext != null) {
            questions = new String[]{"Explain this code", "Find bugs", "How do I run this?", "Add comments"};
        } else {
            questions = new String[]{"How do I install Python packages?", "Start a web server", "Use git with GitHub"};
        }
        for (String q : questions) mQuick.addView(chip(q, v -> send(q)));
    }

    private void toggleMode() {
        if (mBusy) return;
        String mode = isAgent() ? "chat" : "agent";
        if (mChat.entries.length() == 0) {
            mChat.mode = mode;
            updateChips();
            renderAll();
        } else {
            newChat(mode); // Modes use different instructions, so a switch starts a new conversation.
        }
    }

    private void newChat(String mode) {
        if (mBusy) return;
        AiChats.save(this, mChat);
        mChat = AiChats.create(mode, mChat.workdir);
        mIncludeContext = mContext != null && !mContext.trim().isEmpty();
        updateChips();
        renderAll();
    }

    private void askFolder() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(mChat.workdir);
        new AlertDialog.Builder(this)
            .setTitle("Agent works in folder")
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                File dir = new File(input.getText().toString().trim().replaceFirst("^~", TermuxConstants.TERMUX_HOME_DIR_PATH));
                if (!dir.isDirectory() && !dir.mkdirs()) { toast("Cannot use " + dir); return; }
                mChat.workdir = dir.getAbsolutePath();
                updateChips();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void chooseModel(View chipView) {
        String key = apiKey();
        toast("Loading models…");
        mWorker.execute(() -> {
            try {
                List<String> models = GeminiClient.listModels(key);
                mHandler.post(() -> {
                    List<String> items = new ArrayList<>(models);
                    items.add("Change API key…");
                    new AlertDialog.Builder(this)
                        .setTitle("Gemini model (lite = fastest)")
                        .setItems(items.toArray(new String[0]), (d, which) -> {
                            if (which == items.size() - 1) {
                                ForgeSettings.setString(this, "gemini_key", "");
                                showKeySetup();
                                return;
                            }
                            ForgeSettings.setString(this, "gemini_model", items.get(which));
                            ((TextView) chipView).setText(items.get(which));
                        })
                        .show();
                });
            } catch (Exception e) {
                mHandler.post(() -> new AlertDialog.Builder(this)
                    .setMessage(e.getMessage())
                    .setPositiveButton("Change API key", (d, w) -> { ForgeSettings.setString(this, "gemini_key", ""); showKeySetup(); })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            }
        });
    }

    // ---- History ----

    private void showHistory() {
        AiChats.save(this, mChat);
        List<AiChats.Chat> chats = AiChats.list(this);
        if (chats.isEmpty()) { toast("No saved chats yet"); return; }
        List<String> labels = new ArrayList<>();
        for (AiChats.Chat c : chats) {
            labels.add(("agent".equals(c.mode) ? "🤖 " : "💬 ") + (c.title.isEmpty() ? "(untitled)" : c.title) + "\n" +
                DateUtils.getRelativeTimeSpanString(c.updated));
        }
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Saved chats (long-press to delete)")
            .setView(list)
            .setNeutralButton("Delete all", (d, w) -> new AlertDialog.Builder(this)
                .setMessage("Delete all saved chats?")
                .setPositiveButton("Delete", (d2, w2) -> { AiChats.deleteAll(this); toast("Deleted"); })
                .setNegativeButton(android.R.string.cancel, null).show())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            AiChats.Chat chat = AiChats.load(this, chats.get(position).id);
            if (chat == null) { toast("Could not open that chat"); return; }
            mChat = chat;
            if (mChat.workdir == null || !new File(mChat.workdir).isDirectory()) mChat.workdir = TermuxConstants.TERMUX_HOME_DIR_PATH;
            updateChips();
            renderAll();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            AiChats.delete(this, chats.get(position).id);
            dialog.dismiss();
            toast("Deleted");
            showHistory();
            return true;
        });
    }

    // ---- Entries (what is shown) ----

    private void addEntry(JSONObject entry) {
        mChat.entries.put(entry);
        renderEntry(entry);
        AiChats.save(this, mChat);
        scrollToEnd();
    }

    private void renderAll() {
        mMessages.removeAllViews();
        if (mChat.entries.length() == 0) {
            addNote(isAgent()
                ? "Agent mode: describe a task and Forge AI will do it step by step in " + shortPath(mChat.workdir) +
                  ". It shows each command and file before running it" +
                  (ForgeSettings.getFlag(this, "agent_auto", false) ? " (auto-run is on for safe steps)." : ", and waits for your Approve.")
                : mContext != null && "terminal".equals(mContextLabel) ? "Tap a question below, or type your own. I can see the end of your terminal."
                : mContext != null ? "Ask about " + mContextLabel + ", or tap a question below."
                : "Ask anything about the terminal, Linux or code. Switch to 🤖 Agent mode to let the AI do a task for you.");
        }
        for (int i = 0; i < mChat.entries.length(); i++) renderEntry(mChat.entries.optJSONObject(i));
        scrollToEnd();
    }

    private void renderEntry(JSONObject entry) {
        if (entry == null) return;
        switch (entry.optString("type")) {
            case "user": addUserBubble(entry.optString("text")); break;
            case "ai": addAiMessage(entry.optString("text")); break;
            case "note": addNote(entry.optString("text")); break;
            case "step": mMessages.addView(stepCard(entry, null)); break;
        }
    }

    // ---- Sending ----

    private void send(String question) {
        question = question.trim();
        if (question.isEmpty() || mBusy) return;
        mInput.setText("");
        if (mChat.title.isEmpty()) mChat.title = question.length() > 70 ? question.substring(0, 70) + "…" : question;
        if (mChat.entries.length() == 0) mMessages.removeAllViews(); // Remove the intro note.
        addEntry(entry("user", question));

        String prompt = question;
        if (mIncludeContext && mContext != null) {
            String label = "terminal".equals(mContextLabel) ? "the end of my terminal" : "the file " + mContextLabel;
            prompt = "Here is " + label + ":\n```\n" + mContext + "\n```\n\n" + question;
            mIncludeContext = false; // Shared once; later questions refer back to it.
        }
        if (isAgent()) prompt = "Working folder: " + mChat.workdir + "\n\nTask: " + prompt;
        mChat.contents.put(userText(prompt));

        setBusy(true);
        updateChips();
        if (isAgent()) {
            mSteps = 0;
            mStopRequested = false;
            agentTurn();
        } else {
            chatTurn();
        }
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        mSend.setText(busy ? (isAgent() ? "■ Stop" : "…") : (isAgent() ? "Start" : "Send"));
    }

    private void chatTurn() {
        TextView thinking = addNote("Thinking…");
        String key = apiKey(), model = model();
        JSONArray contents = copy(mChat.contents);
        mWorker.execute(() -> {
            try {
                JSONObject reply = GeminiClient.generateRaw(key, model, CHAT_PROMPT, contents, null);
                mHandler.post(() -> {
                    mMessages.removeView(thinking);
                    mChat.contents.put(reply);
                    String text = textOf(reply);
                    addEntry(entry("ai", text.isEmpty() ? "(no answer)" : text));
                    setBusy(false);
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mMessages.removeView(thinking);
                    mChat.contents.remove(mChat.contents.length() - 1); // So the question can be asked again.
                    addEntry(entry("note", "⚠ " + e.getMessage()));
                    setBusy(false);
                });
            }
        });
    }

    // ---- Agent ----

    private static final JSONArray TOOLS = buildTools();

    private static JSONArray buildTools() {
        try {
            return new JSONArray()
                .put(tool("run_command", "Run a bash command in the working folder and get its exit code and output (stdout+stderr). Times out after 120 s.",
                    prop("command", "The bash command to run."), prop("reason", "Why, in a few words, shown to the user.")))
                .put(tool("read_file", "Read a text file.", prop("path", "File path, absolute or relative to the working folder.")))
                .put(tool("write_file", "Create or overwrite a text file with the given content (creates folders as needed).",
                    prop("path", "File path, absolute or relative to the working folder."), prop("content", "The full file content.")))
                .put(tool("list_files", "List the files in a folder.", prop("path", "Folder path, absolute or relative to the working folder; use . for the working folder.")))
                .put(tool("finish", "Finish the task: give the user a short summary of what was done, where the files are and how to run it, or ask them a question.",
                    prop("summary", "The message for the user.")));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject tool(String name, String description, JSONObject... props) throws JSONException {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (JSONObject p : props) {
            properties.put(p.getString("name"), new JSONObject().put("type", "string").put("description", p.getString("description")));
            if (!p.getString("name").equals("reason")) required.put(p.getString("name"));
        }
        return new JSONObject().put("name", name).put("description", description)
            .put("parameters", new JSONObject().put("type", "object").put("properties", properties).put("required", required));
    }

    private static JSONObject prop(String name, String description) {
        try {
            return new JSONObject().put("name", name).put("description", description);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private void agentTurn() {
        if (mStopRequested) { agentStopped(); return; }
        if (mSteps >= MAX_AGENT_STEPS) {
            addEntry(entry("note", "Paused after " + MAX_AGENT_STEPS + " steps. Send \"continue\" to let it keep going."));
            setBusy(false);
            return;
        }
        TextView thinking = addNote("Thinking…");
        String key = apiKey(), model = model();
        JSONArray contents = copy(mChat.contents);
        mWorker.execute(() -> {
            try {
                JSONObject reply = GeminiClient.generateRaw(key, model, AGENT_PROMPT, contents, TOOLS);
                mHandler.post(() -> {
                    mMessages.removeView(thinking);
                    mChat.contents.put(reply);
                    String text = textOf(reply);
                    if (!text.isEmpty()) addEntry(entry("ai", text));
                    List<JSONObject> calls = callsOf(reply);
                    if (calls.isEmpty()) { setBusy(false); return; } // The model answered without tools: done.
                    processCalls(calls, 0, new JSONArray());
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mMessages.removeView(thinking);
                    addEntry(entry("note", "⚠ " + e.getMessage() + " Send \"continue\" to try again."));
                    setBusy(false);
                });
            }
        });
    }

    /** Handles the model's function calls one by one, then sends all results back in one turn. */
    private void processCalls(List<JSONObject> calls, int index, JSONArray responses) {
        if (index >= calls.size()) {
            JSONObject turn = new JSONObject(map("role", "user"));
            putQuietly(turn, "parts", responses);
            mChat.contents.put(turn);
            AiChats.save(this, mChat);
            if (mFinished) { mFinished = false; setBusy(false); return; }
            mSteps++;
            agentTurn();
            return;
        }
        JSONObject call = calls.get(index);
        String name = call.optString("name");
        JSONObject args = call.optJSONObject("args") != null ? call.optJSONObject("args") : new JSONObject();

        if (mStopRequested) {
            responses.put(functionResponse(name, result("error", "The user stopped the agent.")));
            processCalls(calls, index + 1, responses);
            return;
        }

        if ("finish".equals(name)) {
            addEntry(entry("ai", "✅ " + args.optString("summary", "Done.")));
            responses.put(functionResponse(name, result("ok", "true")));
            mFinished = true;
            processCalls(calls, index + 1, responses);
            return;
        }

        JSONObject step = new JSONObject(map("type", "step"));
        putQuietly(step, "tool", name);
        putQuietly(step, "title", stepTitle(name, args));
        putQuietly(step, "detail", "write_file".equals(name) ? preview(args.optString("content")) : args.optString("reason", ""));
        boolean needsApproval = needsApproval(name, args);
        putQuietly(step, "status", needsApproval ? "waiting" : "running");

        View[] cardHolder = new View[1];
        Runnable execute = () -> {
            putQuietly(step, "status", "running");
            replaceCard(cardHolder, step);
            mWorker.execute(() -> {
                JSONObject result = executeTool(name, args, step);
                mHandler.post(() -> {
                    mChat.entries.put(step);
                    replaceCard(cardHolder, step);
                    responses.put(functionResponse(name, result));
                    processCalls(calls, index + 1, responses);
                });
            });
        };
        Runnable skip = () -> {
            putQuietly(step, "status", "skipped");
            mChat.entries.put(step);
            replaceCard(cardHolder, step);
            responses.put(functionResponse(name, result("error", "The user chose not to run this step. Try a different approach or ask the user.")));
            processCalls(calls, index + 1, responses);
        };
        Runnable stop = () -> {
            mStopRequested = true;
            putQuietly(step, "status", "stopped");
            mChat.entries.put(step);
            replaceCard(cardHolder, step);
            responses.put(functionResponse(name, result("error", "The user stopped the agent.")));
            processCalls(calls, index + 1, responses);
        };

        cardHolder[0] = stepCard(step, needsApproval ? new Runnable[]{execute, skip, stop} : null);
        mMessages.addView(cardHolder[0]);
        scrollToEnd();
        if (!needsApproval) execute.run();
    }

    private boolean mFinished;

    private void agentStopped() {
        addEntry(entry("note", "Stopped. Send a message to continue or change direction."));
        setBusy(false);
    }

    private boolean needsApproval(String tool, JSONObject args) {
        boolean auto = ForgeSettings.getFlag(this, "agent_auto", false);
        if ("run_command".equals(tool)) return !auto || DANGEROUS.matcher(args.optString("command")).find();
        if ("write_file".equals(tool)) {
            if (!auto) return true;
            return resolve(args.optString("path")).exists(); // Overwriting an existing file always asks.
        }
        return false; // Reading and listing never change anything.
    }

    private static String stepTitle(String tool, JSONObject args) {
        switch (tool) {
            case "run_command": return "$ " + args.optString("command");
            case "read_file": return "Read " + args.optString("path");
            case "write_file": return "Write " + args.optString("path");
            case "list_files": return "List " + args.optString("path", ".");
            default: return tool;
        }
    }

    /** Runs one tool on the worker thread. Updates the step's output/status and returns the function result. */
    private JSONObject executeTool(String tool, JSONObject args, JSONObject step) {
        try {
            switch (tool) {
                case "run_command": {
                    ShellExec.Result r = ShellExec.run(this, args.optString("command"), new File(mChat.workdir), COMMAND_TIMEOUT_SECONDS);
                    String output = tail(r.output, MAX_TOOL_OUTPUT);
                    putQuietly(step, "output", output);
                    putQuietly(step, "status", r.timedOut ? "timed out" : (r.exitCode == 0 ? "done" : "exit " + r.exitCode));
                    JSONObject result = new JSONObject();
                    result.put("exit_code", r.exitCode);
                    result.put("output", output);
                    if (r.timedOut) result.put("note", "Timed out after " + COMMAND_TIMEOUT_SECONDS + " s and was killed. Long-running programs must be started in the background.");
                    return result;
                }
                case "read_file": {
                    File file = resolve(args.optString("path"));
                    if (!file.isFile()) throw new Exception("No such file: " + file);
                    if (file.length() > 200_000) throw new Exception("File is too large to read (" + file.length() + " bytes).");
                    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    putQuietly(step, "output", preview(content));
                    putQuietly(step, "status", "done");
                    return new JSONObject().put("content", tail(content, 60_000));
                }
                case "write_file": {
                    File file = resolve(args.optString("path"));
                    File parent = file.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new Exception("Cannot create folder " + parent);
                    byte[] bytes = args.optString("content").getBytes(StandardCharsets.UTF_8);
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(bytes);
                    }
                    putQuietly(step, "status", "done");
                    return new JSONObject().put("ok", true).put("bytes", bytes.length).put("path", file.getAbsolutePath());
                }
                case "list_files": {
                    File dir = resolve(args.optString("path", "."));
                    File[] files = dir.listFiles();
                    if (files == null) throw new Exception("Not a readable folder: " + dir);
                    Arrays.sort(files);
                    StringBuilder list = new StringBuilder();
                    int count = 0;
                    for (File f : files) {
                        if (count++ >= 300) { list.append("… more\n"); break; }
                        list.append(f.getName()).append(f.isDirectory() ? "/" : "").append('\n');
                    }
                    putQuietly(step, "output", list.toString());
                    putQuietly(step, "status", "done");
                    return new JSONObject().put("path", dir.getAbsolutePath()).put("entries", list.toString());
                }
                default:
                    throw new Exception("Unknown tool " + tool);
            }
        } catch (Exception e) {
            putQuietly(step, "status", "error");
            putQuietly(step, "output", String.valueOf(e.getMessage()));
            return result("error", String.valueOf(e.getMessage()));
        }
    }

    private File resolve(String path) {
        if (path == null || path.isEmpty()) path = ".";
        path = path.replaceFirst("^~", TermuxConstants.TERMUX_HOME_DIR_PATH);
        File file = new File(path);
        return file.isAbsolute() ? file : new File(mChat.workdir, path);
    }

    // ---- Step cards ----

    private void replaceCard(View[] holder, JSONObject step) {
        View old = holder[0];
        int index = mMessages.indexOfChild(old);
        View fresh = stepCard(step, null);
        if (index >= 0) {
            mMessages.removeViewAt(index);
            mMessages.addView(fresh, index);
        } else {
            mMessages.addView(fresh);
        }
        holder[0] = fresh;
        AiChats.save(this, mChat);
        scrollToEnd();
    }

    /** A card for one agent step; {@code actions} = {approve, skip, stop} while waiting for the user. */
    private View stepCard(JSONObject step, Runnable[] actions) {
        LinearLayout card = column();
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(rounded(CODE_BG, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), dp(16), 0);
        card.setLayoutParams(lp);

        String status = step.optString("status");
        String icon = "running".equals(status) ? "⏳" : "waiting".equals(status) ? "✋" : "done".equals(status) ? "✓"
            : "skipped".equals(status) || "stopped".equals(status) ? "–" : "✗";
        int color = "done".equals(status) ? GREEN : "waiting".equals(status) || "running".equals(status) ? 0xFFFFD166 : RED;
        if ("skipped".equals(status) || "stopped".equals(status)) color = MUTED;

        TextView title = text(icon + "  " + step.optString("title"), 13, TEXT);
        title.setTypeface(Typeface.MONOSPACE);
        card.addView(title);
        TextView state = text(status.equals("waiting") ? "Waiting for your OK" : status, 11, color);
        card.addView(state);

        String detail = step.optString("detail");
        if (!detail.isEmpty()) {
            TextView d = text(detail, 12, MUTED);
            if ("write_file".equals(step.optString("tool"))) d.setTypeface(Typeface.MONOSPACE);
            card.addView(spaced(d, 4));
        }

        String output = step.optString("output");
        if (!output.trim().isEmpty()) {
            String[] lines = output.split("\n");
            boolean longOutput = lines.length > 10;
            TextView out = text(longOutput ? lastLines(lines, 10) : output.trim(), 12, 0xFFC9D1D9);
            out.setTypeface(Typeface.MONOSPACE);
            out.setTextIsSelectable(true);
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.addView(out);
            card.addView(spaced(scroll, 6));
            if (longOutput) {
                TextView more = text("Show all " + lines.length + " lines", 12, ForgeColors.ACCENT);
                more.setOnClickListener(v -> { out.setText(output.trim()); more.setVisibility(View.GONE); });
                card.addView(spaced(more, 4));
            }
        }

        if (actions != null) {
            LinearLayout buttons = new LinearLayout(this);
            buttons.setGravity(Gravity.END);
            buttons.setPadding(0, dp(8), 0, 0);
            TextView approve = chip("run_command".equals(step.optString("tool")) ? "▶ Run" : "✓ Allow", v -> actions[0].run());
            approve.setTextColor(GREEN);
            buttons.addView(chip("Stop", v -> actions[2].run()));
            buttons.addView(chip("Skip", v -> actions[1].run()));
            buttons.addView(approve);
            card.addView(buttons);
        }
        return card;
    }

    // ---- Chat rendering ----

    private void addUserBubble(String text) {
        TextView bubble = text(text, 15, Color.BLACK);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.setBackground(rounded(ForgeColors.ACCENT, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(dp(48), dp(10), 0, 0);
        bubble.setTextIsSelectable(true);
        mMessages.addView(bubble, lp);
    }

    private TextView addNote(String text) {
        TextView note = text(text, 13, MUTED);
        note.setPadding(dp(4), dp(8), dp(4), dp(4));
        mMessages.addView(note);
        scrollToEnd();
        return note;
    }

    private static final Pattern CODE_BLOCK = Pattern.compile("```([\\w+-]*)[ \\t]*\\n(.*?)```", Pattern.DOTALL);

    private void addAiMessage(String answer) {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(rounded(CARD, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), dp(16), 0);
        mMessages.addView(card, lp);

        Matcher m = CODE_BLOCK.matcher(answer);
        int last = 0;
        while (m.find()) {
            addProse(card, answer.substring(last, m.start()));
            addCode(card, m.group(1), m.group(2).replaceAll("\\s+$", ""));
            last = m.end();
        }
        addProse(card, answer.substring(last));
    }

    private void addProse(LinearLayout card, String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        TextView view = text("", 15, TEXT);
        view.setText(formatInline(text));
        view.setTextIsSelectable(true);
        view.setLineSpacing(0, 1.15f);
        card.addView(spaced(view, 4));
    }

    /** Handles **bold** and `code` in prose; strips leading markdown heading/bullet symbols. */
    private static CharSequence formatInline(String text) {
        text = text.replaceAll("(?m)^#{1,6}\\s*", "").replaceAll("(?m)^\\s*[*-]\\s+", "• ");
        SpannableStringBuilder out = new SpannableStringBuilder();
        Matcher m = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`").matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            int start = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.append(m.group(2));
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFB347), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        out.append(text.substring(last));
        return out;
    }

    private void addCode(LinearLayout card, String language, String code) {
        LinearLayout box = column();
        box.setBackground(rounded(CODE_BG, 10));
        box.setPadding(dp(10), dp(8), dp(10), dp(6));
        TextView view = text(code, 13, 0xFFD7FFD9);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.addView(view);
        box.addView(scroll);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(6), 0, 0);
        boolean shell = language.isEmpty() || language.matches("(?i)bash|sh|shell|console|zsh|termux");
        actions.addView(chip("Copy", v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("code", code));
            toast("Copied");
        }));
        if (shell && mCanInsert && !isAgent()) {
            actions.addView(chip("Insert", v -> returnCommand(code, false)));
            TextView run = chip("▶ Run", v -> confirmAndRun(code));
            run.setTextColor(GREEN);
            actions.addView(run);
        }
        box.addView(actions);
        card.addView(spaced(box, 8));
    }

    static final Pattern DANGEROUS = Pattern.compile(
        "rm\\s+-[a-zA-Z]*[rf]|\\brm\\s+[^|;&]*\\*|\\bmkfs|\\bdd\\s+if=|>\\s*/dev/|:\\(\\)\\s*\\{|chmod\\s+-R|chown\\s+-R|" +
        "\\bpkg\\s+(uninstall|remove)|\\bapt(-get)?\\s+(remove|purge|autoremove)|\\bgit\\s+(reset\\s+--hard|clean\\s+-[a-z]*f|push\\s+.*--force)|" +
        "\\bcurl\\b[^|]*\\|\\s*(ba)?sh|\\bwget\\b[^|]*\\|\\s*(ba)?sh|\\bshred\\b|\\btruncate\\b|termux-reset");

    private void confirmAndRun(String code) {
        if (DANGEROUS.matcher(code).find()) {
            new AlertDialog.Builder(this)
                .setTitle("Run this?")
                .setMessage("This command can delete or change files:\n\n" + code + "\n\nOnly run it if you are sure.")
                .setPositiveButton("Run", (d, w) -> returnCommand(code, true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } else {
            returnCommand(code, true);
        }
    }

    private void returnCommand(String code, boolean run) {
        setResult(RESULT_OK, new Intent().putExtra(RESULT_COMMAND, code).putExtra(RESULT_RUN, run));
        finish();
    }

    // ---- JSON helpers ----

    private static JSONObject userText(String text) {
        try {
            return new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", text)));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject functionResponse(String name, JSONObject response) {
        try {
            return new JSONObject().put("functionResponse", new JSONObject().put("name", name).put("response", response));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject result(String key, String value) {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String textOf(JSONObject content) {
        StringBuilder text = new StringBuilder();
        JSONArray parts = content.optJSONArray("parts");
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && part.has("text") && !part.optBoolean("thought", false)) text.append(part.optString("text"));
        }
        return text.toString().trim();
    }

    private static List<JSONObject> callsOf(JSONObject content) {
        List<JSONObject> calls = new ArrayList<>();
        JSONArray parts = content.optJSONArray("parts");
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && part.optJSONObject("functionCall") != null) calls.add(part.optJSONObject("functionCall"));
        }
        return calls;
    }

    private static JSONObject entry(String type, String text) {
        try {
            return new JSONObject().put("type", type).put("text", text);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.Map<String, Object> map(String key, Object value) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put(key, value);
        return map;
    }

    private static void putQuietly(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    private static JSONArray copy(JSONArray array) {
        try {
            return new JSONArray(array.toString());
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String tail(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : "… (" + (text.length() - max) + " characters cut)\n" + text.substring(text.length() - max);
    }

    private static String preview(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        if (lines.length <= 8) return text.length() > 800 ? text.substring(0, 800) + "…" : text;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) out.append(lines[i]).append('\n');
        return out.append("… (").append(lines.length).append(" lines)").toString();
    }

    private static String lastLines(String[] lines, int count) {
        StringBuilder out = new StringBuilder("…\n");
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) out.append(lines[i]).append(i < lines.length - 1 ? "\n" : "");
        return out.toString();
    }

    private static String shortPath(String path) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (path == null) return "~";
        return path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

    // ---- View helpers ----

    private void scrollToEnd() {
        if (mScroll != null) mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private View padded(View view) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private <T extends View> T spaced(T view, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topDp), 0, 0);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private TextView button(String label, int background, int textColor) {
        TextView view = text(label, 15, textColor);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setBackground(rounded(background, 24));
        return view;
    }

    private TextView chip(String label, View.OnClickListener listener) {
        TextView chip = text(label, 13, TEXT);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        chip.setSingleLine(true);
        chip.setBackground(rounded(0xFF2A2C38, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(listener);
        return chip;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radiusDp));
        shape.setColor(color);
        return shape;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
