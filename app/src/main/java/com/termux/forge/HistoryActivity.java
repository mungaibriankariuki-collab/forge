package com.termux.forge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Searchable list of commands from ~/.bash_history, newest first and without duplicates.
 * Tap: insert into the terminal. Long-press: run, copy, or ask Forge AI to explain.
 * Returns the chosen command with the same result extras as {@link AiActivity}.
 */
public class HistoryActivity extends Activity {

    private static final int BG = 0xFF101118;
    private static final int TEXT = 0xFFE8E8EE;
    private static final int MUTED = 0xFF9A9CAA;

    private final List<String> mAll = new ArrayList<>();
    private final List<String> mShown = new ArrayList<>();
    private Adapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(14), dp(12), dp(14), 0);

        TextView title = text("Command history", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView hint = text("Tap to put a command in the terminal. Long-press to run, copy or explain it.", 13, MUTED);
        hint.setPadding(0, dp(4), 0, dp(6));
        root.addView(hint);

        EditText search = new EditText(this);
        search.setHint("Search…");
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        root.addView(search);

        ListView list = new ListView(this);
        list.setDivider(null);
        mAdapter = new Adapter();
        list.setAdapter(mAdapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        load();
        filter("");
        if (mAll.isEmpty()) {
            hint.setText("No history yet. Commands appear here as you run them in the terminal " +
                "(from new terminal tabs, after this update).");
        }

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        list.setOnItemClickListener((parent, view, position, id) -> finishWith(mShown.get(position), false));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            String command = mShown.get(position);
            new AlertDialog.Builder(this)
                .setTitle(command)
                .setItems(new String[]{"▶ Run it", "Put in terminal", "Copy", "✦ Explain with AI"}, (d, which) -> {
                    switch (which) {
                        case 0: finishWith(command, true); break;
                        case 1: finishWith(command, false); break;
                        case 2: {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("command", command));
                            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case 3: {
                            Intent intent = new Intent(this, AiActivity.class)
                                .putExtra(AiActivity.EXTRA_QUESTION, "Explain this command, part by part:\n" + command)
                                .putExtra(AiActivity.EXTRA_AUTOSEND, true);
                            startActivity(intent);
                            break;
                        }
                    }
                })
                .show();
            return true;
        });
    }

    private void load() {
        File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".bash_history");
        if (!file.isFile()) return;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.matches("#\\d+")) continue; // Skip timestamp lines.
                unique.add(line);
                if (unique.size() >= 2000) break;
            }
            mAll.addAll(unique);
        } catch (Exception e) {
            Toast.makeText(this, "Could not read history: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void filter(String query) {
        mShown.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (String command : mAll) if (q.isEmpty() || command.toLowerCase(Locale.ROOT).contains(q)) mShown.add(command);
        mAdapter.notifyDataSetChanged();
    }

    private void finishWith(String command, boolean run) {
        setResult(RESULT_OK, new Intent().putExtra(AiActivity.RESULT_COMMAND, command).putExtra(AiActivity.RESULT_RUN, run));
        finish();
    }

    class Adapter extends BaseAdapter {
        @Override public int getCount() { return mShown.size(); }
        @Override public Object getItem(int position) { return mShown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : new TextView(HistoryActivity.this);
            row.setText(mShown.get(position));
            row.setTypeface(Typeface.MONOSPACE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setTextColor(TEXT);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(4), dp(11), dp(4), dp(11));
            return row;
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
