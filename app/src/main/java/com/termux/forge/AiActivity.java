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
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge AI: a chat with Gemini (the user's own API key) that can see the end of the terminal or
 * the open file, explains errors, and suggests commands. Commands in the reply get Insert / Run
 * buttons that put them into the terminal; nothing ever runs without a tap.
 */
public class AiActivity extends Activity {

    public static final String EXTRA_CONTEXT = "com.termux.forge.ai_context";
    public static final String EXTRA_CONTEXT_LABEL = "com.termux.forge.ai_context_label";
    public static final String EXTRA_QUESTION = "com.termux.forge.ai_question";
    public static final String EXTRA_AUTOSEND = "com.termux.forge.ai_autosend";
    /** True when opened from the terminal for a result: then Insert/Run buttons are shown. */
    public static final String EXTRA_CAN_INSERT = "com.termux.forge.ai_can_insert";

    /** Result extras: the command to put into the terminal, and whether to press Enter. */
    public static final String RESULT_COMMAND = "com.termux.forge.ai_command";
    public static final String RESULT_RUN = "com.termux.forge.ai_run";

    public static final String DEFAULT_MODEL = "gemini-flash-lite-latest";

    private static final int BG = 0xFF101118;
    private static final int CARD = 0xFF1A1C26;
    private static final int TEXT = 0xFFE8E8EE;
    private static final int MUTED = 0xFF9A9CAA;
    private static final int CODE_BG = 0xFF0B0C10;

    private static final String SYSTEM_PROMPT =
        "You are Forge AI, the assistant inside Forge, an Android terminal app based on Termux.\n" +
        "Environment: Termux on Android, no root, shell is bash, package manager is `pkg` (apt underneath), " +
        "prefix /data/data/com.termux/files/usr, home ~ = /data/data/com.termux/files/home, shared phone storage at ~/storage/shared " +
        "after termux-setup-storage. Never suggest sudo. Use `pkg install` for system packages and pip/npm/cargo for language packages. " +
        "The user may share the end of their terminal output or a source file.\n" +
        "Rules: be short and practical; explain errors in plain words, then give the fix. Put every command the user should run " +
        "in its own ```bash code block with one command per line and no prompt symbols ($). Warn clearly before anything that deletes data.";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<GeminiClient.Turn> mTurns = new ArrayList<>();
    private LinearLayout mMessages;
    private ScrollView mScroll;
    private EditText mInput;
    private TextView mSend;
    private TextView mContextChip;
    private TextView mModelChip;
    private String mContext;
    private String mContextLabel;
    private boolean mIncludeContext;
    private boolean mCanInsert;
    private boolean mBusy;

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

        if (apiKey().isEmpty()) showKeySetup(); else showChat();
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
        root.addView(spaced(text("Explains errors, suggests commands and answers coding questions, using Google Gemini.\n\n" +
            "It uses your own free Gemini API key, which stays on this phone. What you send (your question and, if you allow it, " +
            "the end of your terminal or the open file) goes to Google to get the answer.", 14, MUTED), 8));

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
            new Thread(() -> {
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
            }, "forge-ai-key-test").start();
        });
        setContentView(scroll);
    }

    private static String pickDefault(List<String> models) {
        for (String m : models) if (m.equals(DEFAULT_MODEL)) return m;
        for (String m : models) if (m.contains("flash-lite") && m.contains("latest")) return m;
        for (String m : models) if (m.contains("flash")) return m;
        return models.isEmpty() ? DEFAULT_MODEL : models.get(0);
    }

    // ---- Chat ----

    private void showChat() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(8), dp(6));
        TextView title = text("✦ Forge AI", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mModelChip = chip(model(), v -> chooseModel());
        header.addView(mModelChip);
        header.addView(chip("New chat", v -> { mTurns.clear(); mMessages.removeAllViews(); mIncludeContext = mContext != null; updateContextChip(); }));
        root.addView(header);

        if (mContext != null && !mContext.trim().isEmpty()) {
            mContextChip = chip("", v -> { mIncludeContext = !mIncludeContext; updateContextChip(); });
            LinearLayout row = new LinearLayout(this);
            row.setPadding(dp(16), 0, dp(16), dp(6));
            row.addView(mContextChip);
            root.addView(row);
            updateContextChip();
        }

        mScroll = new ScrollView(this);
        mMessages = column();
        mMessages.setPadding(dp(12), dp(4), dp(12), dp(12));
        mScroll.addView(mMessages);
        root.addView(mScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Quick questions.
        HorizontalScrollView quickScroll = new HorizontalScrollView(this);
        quickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout quick = new LinearLayout(this);
        quick.setPadding(dp(12), dp(4), dp(12), dp(4));
        boolean terminal = "terminal".equals(mContextLabel);
        String[] questions = terminal
            ? new String[]{"Explain the last error", "How do I fix it?", "Explain this output", "What should I run next?"}
            : mContext != null
            ? new String[]{"Explain this code", "Find bugs", "How do I run this?", "Add comments"}
            : new String[]{"How do I install Python packages?", "Start a web server", "Use git with GitHub"};
        for (String q : questions) quick.addView(chip(q, v -> send(q)));
        quickScroll.addView(quick);
        root.addView(quickScroll);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(12), dp(4), dp(8), dp(10));
        mInput = new EditText(this);
        mInput.setHint("Ask anything, e.g. \"how do I unzip a file?\"");
        mInput.setTextColor(TEXT);
        mInput.setHintTextColor(MUTED);
        mInput.setMaxLines(5);
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        inputRow.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mSend = button("Send", ForgeColors.ACCENT, Color.BLACK);
        mSend.setOnClickListener(v -> send(mInput.getText().toString()));
        inputRow.addView(mSend, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(inputRow);

        setContentView(root);

        if (mMessages.getChildCount() == 0) {
            addNote(mContext != null && terminal
                ? "Tap a question below, or type your own. I can see the end of your terminal."
                : mContext != null ? "Ask about " + mContextLabel + ", or tap a question below."
                : "Ask anything about the terminal, Linux or code.");
        }

        String question = getIntent().getStringExtra(EXTRA_QUESTION);
        if (question != null && !question.trim().isEmpty()) {
            if (getIntent().getBooleanExtra(EXTRA_AUTOSEND, false)) send(question);
            else mInput.setText(question);
        }
    }

    private void updateContextChip() {
        if (mContextChip == null) return;
        String what = "terminal".equals(mContextLabel) ? "the end of the terminal" : mContextLabel;
        mContextChip.setText(mIncludeContext ? "✓ Sharing " + what : "Not sharing " + what + " (tap to share)");
        mContextChip.setTextColor(mIncludeContext ? ForgeColors.ACCENT : MUTED);
    }

    private void chooseModel() {
        String key = apiKey();
        toast("Loading models…");
        new Thread(() -> {
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
                            mModelChip.setText(items.get(which));
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
        }, "forge-ai-models").start();
    }

    private void send(String question) {
        question = question.trim();
        if (question.isEmpty() || mBusy) return;
        mInput.setText("");
        addUserMessage(question);

        String prompt = question;
        if (mIncludeContext && mContext != null) {
            String label = "terminal".equals(mContextLabel) ? "the end of my terminal" : "the file " + mContextLabel;
            prompt = "Here is " + label + ":\n```\n" + mContext + "\n```\n\n" + question;
            mIncludeContext = false; // Shared once; later questions refer back to it.
            updateContextChip();
        }
        mTurns.add(new GeminiClient.Turn("user", prompt));

        mBusy = true;
        mSend.setText("…");
        TextView thinking = addNote("Thinking…");
        String key = apiKey();
        String model = model();
        List<GeminiClient.Turn> turns = new ArrayList<>(mTurns);
        new Thread(() -> {
            try {
                String answer = GeminiClient.generate(key, model, SYSTEM_PROMPT, turns);
                mHandler.post(() -> {
                    mMessages.removeView(thinking);
                    mTurns.add(new GeminiClient.Turn("model", answer));
                    addAiMessage(answer);
                    done();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mMessages.removeView(thinking);
                    mTurns.remove(mTurns.size() - 1);
                    addNote("⚠ " + e.getMessage());
                    done();
                });
            }
        }, "forge-ai").start();
    }

    private void done() {
        mBusy = false;
        mSend.setText("Send");
        scrollToEnd();
    }

    // ---- Messages ----

    private void addUserMessage(String text) {
        TextView bubble = text(text, 15, Color.BLACK);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.setBackground(rounded(ForgeColors.ACCENT, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(dp(48), dp(8), 0, 0);
        bubble.setTextIsSelectable(true);
        mMessages.addView(bubble, lp);
        scrollToEnd();
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
        if (shell && mCanInsert) {
            actions.addView(chip("Insert", v -> returnCommand(code, false)));
            TextView run = chip("▶ Run", v -> confirmAndRun(code));
            run.setTextColor(0xFF4ADE80);
            actions.addView(run);
        }
        box.addView(actions);
        card.addView(spaced(box, 8));
    }

    private static final Pattern DANGEROUS = Pattern.compile(
        "rm\\s+-[a-zA-Z]*[rf]|\\bmkfs|\\bdd\\s+if=|>\\s*/dev/|:\\(\\)\\s*\\{|chmod\\s+-R|\\bpkg\\s+(uninstall|remove)|\\bapt(-get)?\\s+(remove|purge)");

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

    // ---- Helpers ----

    private void scrollToEnd() {
        if (mScroll != null) mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
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
