package com.termux.forge;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.termux.TermuxConstants;

import java.io.IOException;
import java.util.Locale;

/**
 * The Forge Store: coding agents (Claude Code, Codex, Gemini CLI…) and tools that install with
 * one tap. Installing runs in a visible terminal tab; Open starts the tool in a new tab.
 */
public class StoreActivity extends Activity {

    private static final int BG = 0xFF101118;
    private static final int CARD = 0xFF1A1C26;
    private static final int TEXT = 0xFFE8E8EE;
    private static final int MUTED = 0xFF9A9CAA;
    private static final int GREEN = 0xFF4ADE80;

    private LinearLayout mList;
    private LinearLayout mCategories;
    private String mCategory = "All";
    private String mQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);

        TextView title = text("Forge Store", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView subtitle = text("170+ coding agents, languages, servers and tools. One tap to install; each install runs in its own tab. Items marked Ubuntu run in the built-in Ubuntu.", 14, MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        root.addView(subtitle);

        EditText search = new EditText(this);
        search.setHint("Search…");
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { mQuery = s.toString().trim().toLowerCase(Locale.ROOT); buildList(); }
        });
        root.addView(search);

        HorizontalScrollView catScroll = new HorizontalScrollView(this);
        catScroll.setHorizontalScrollBarEnabled(false);
        mCategories = new LinearLayout(this);
        mCategories.setPadding(0, dp(8), 0, dp(4));
        catScroll.addView(mCategories);
        root.addView(catScroll);

        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mList);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildCategories();
        buildList(); // Refresh installed marks after returning from an install tab.
    }

    private void buildCategories() {
        mCategories.removeAllViews();
        java.util.List<String> categories = new java.util.ArrayList<>();
        categories.add("All");
        categories.add("Installed");
        categories.addAll(Store.categories());
        for (String category : categories) {
            boolean selected = category.equals(mCategory);
            TextView chip = text(category, 13, selected ? Color.BLACK : TEXT);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setBackground(rounded(selected ? ForgeColors.ACCENT : 0xFF2A2C38, 16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(6), 0);
            chip.setOnClickListener(v -> { mCategory = category; buildCategories(); buildList(); });
            mCategories.addView(chip, lp);
        }
    }

    private void buildList() {
        mList.removeAllViews();
        String lastCategory = null;
        for (Store.Item item : Store.ITEMS) {
            boolean installed = item.isInstalled();
            if ("Installed".equals(mCategory) ? !installed : !"All".equals(mCategory) && !item.category.equals(mCategory)) continue;
            if (!mQuery.isEmpty() && !(item.name + " " + item.description + " " + item.id).toLowerCase(Locale.ROOT).contains(mQuery)) continue;

            if ("All".equals(mCategory) && !item.category.equals(lastCategory)) {
                TextView header = text(item.category.toUpperCase(Locale.ROOT), 12, ForgeColors.ACCENT);
                header.setTypeface(Typeface.DEFAULT_BOLD);
                header.setPadding(dp(4), dp(18), 0, dp(2));
                mList.addView(header);
                lastCategory = item.category;
            }
            mList.addView(card(item, installed));
        }
        if (mList.getChildCount() == 0) {
            TextView none = text("Nothing here yet.", 14, MUTED);
            none.setPadding(dp(4), dp(20), 0, 0);
            mList.addView(none);
        }
    }

    private LinearLayout card(Store.Item item, boolean installed) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(CARD, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(item.name, 17, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (item.ubuntu && !installed) {
            TextView badge = text("Ubuntu", 11, MUTED);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            badge.setBackground(rounded(0xFF2A2C38, 10));
            top.addView(badge);
        }
        if (installed) top.addView(text("Installed ✓", 12, GREEN));
        card.addView(top);

        TextView description = text(item.description, 13, 0xFFC9CAD4);
        description.setPadding(0, dp(3), 0, 0);
        card.addView(description);
        if (!item.note.isEmpty()) {
            TextView note = text(item.note, 12, MUTED);
            note.setPadding(0, dp(3), 0, 0);
            card.addView(note);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(10), 0, 0);
        if (installed) {
            buttons.addView(button("Remove", 0xFF2A2C38, TEXT, () -> confirmRemove(item)));
            buttons.addView(button("Reinstall", 0xFF2A2C38, TEXT, () -> run(item.name, Store.installScript(item), "store")));
            buttons.addView(button("▶ Open", ForgeColors.ACCENT, Color.BLACK, () -> run(item.name, item.open + "\n", item.id)));
        } else {
            buttons.addView(button("Install", ForgeColors.ACCENT, Color.BLACK, () -> run(item.name, Store.installScript(item), "store")));
        }
        card.addView(buttons);
        return card;
    }

    private void confirmRemove(Store.Item item) {
        new AlertDialog.Builder(this)
            .setTitle("Remove " + item.name + "?")
            .setPositiveButton("Remove", (d, w) -> run(item.name, Store.removeScript(item), "store"))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void run(String name, String script, String tabName) {
        try {
            TerminalRunner.runInNewTab(this, script, tabName, TermuxConstants.TERMUX_HOME_DIR_PATH);
        } catch (IOException e) {
            Toast.makeText(this, "Could not start " + name + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView button(String label, int background, int color, Runnable action) {
        TextView view = text(label, 14, color);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setBackground(rounded(background, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        view.setLayoutParams(lp);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radiusDp));
        shape.setColor(color);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
