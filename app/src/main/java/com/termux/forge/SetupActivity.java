package com.termux.forge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Forge's setup screen: pick kits (Python, Node.js, Git…) and install them with one tap. Shown
 * automatically once after the first install, and from the drawer afterwards.
 */
public class SetupActivity extends Activity {

    private static final String LOG_TAG = "ForgeSetup";

    /** Extra: true when opened automatically after the first install. */
    public static final String EXTRA_WELCOME = "com.termux.forge.setup_welcome";

    private static final int BG = 0xFF101118;
    private static final int CARD = 0xFF1A1C26;
    private static final int TEXT = 0xFFE8E8EE;
    private static final int MUTED = 0xFF9A9CAA;
    private static final int GREEN = 0xFF4ADE80;

    private final Set<Kits.Kit> mSelected = new LinkedHashSet<>();
    private LinearLayout mList;
    private TextView mInstallButton;

    public static void open(Context context, boolean welcome) {
        Intent intent = new Intent(context, SetupActivity.class).putExtra(EXTRA_WELCOME, welcome);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        boolean welcome = getIntent().getBooleanExtra(EXTRA_WELCOME, false);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(96));
        scroll.addView(root);
        frame.addView(scroll);

        TextView title = text(welcome ? "Welcome to Forge" : "Setup", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView subtitle = text(welcome
            ? "Your Linux terminal is ready. Pick what you want to code with and Forge installs it for you. You can change this any time from the menu → Setup."
            : "Pick kits to install. Each one runs in a new tab so you can watch it.", 14, MUTED);
        subtitle.setPadding(0, dp(6), 0, dp(10));
        root.addView(subtitle);

        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mList);

        if (welcome) {
            // Sensible first choices: phone storage, essentials and Python.
            for (String id : new String[]{"storage", "essentials", "python"}) {
                Kits.Kit kit = Kits.byId(id);
                if (kit != null && !kit.isInstalled()) mSelected.add(kit);
            }
        }

        mInstallButton = text("", 16, Color.BLACK);
        mInstallButton.setTypeface(Typeface.DEFAULT_BOLD);
        mInstallButton.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM);
        lp.setMargins(dp(16), 0, dp(16), dp(16));
        frame.addView(mInstallButton, lp);
        mInstallButton.setOnClickListener(v -> install());

        if (welcome) {
            TextView skip = text("Skip for now", 14, MUTED);
            skip.setGravity(Gravity.CENTER);
            skip.setPadding(0, dp(14), 0, 0);
            skip.setOnClickListener(v -> finish());
            root.addView(skip);
        }

        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildList(); // Refresh "installed" marks after returning from an install.
    }

    private void buildList() {
        mList.removeAllViews();
        for (Kits.Kit kit : Kits.ALL) {
            boolean installed = kit.isInstalled();
            if (installed) mSelected.remove(kit);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(12), dp(10), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, 0);
            mList.addView(card, lp);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(text(kit.title, 16, TEXT));
            texts.addView(text(kit.description, 12, MUTED));
            card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (installed) {
                TextView done = text("Installed ✓", 13, GREEN);
                done.setPadding(dp(8), 0, dp(6), 0);
                card.addView(done);
                styleCard(card, false);
                // Allow reinstalling (e.g. to repair) with a long press.
                card.setOnLongClickListener(v -> {
                    List<Kits.Kit> one = new ArrayList<>();
                    one.add(kit);
                    run(one);
                    return true;
                });
            } else {
                CheckBox box = new CheckBox(this);
                box.setChecked(mSelected.contains(kit));
                box.setClickable(false);
                card.addView(box);
                styleCard(card, box.isChecked());
                card.setOnClickListener(v -> {
                    if (mSelected.contains(kit)) mSelected.remove(kit); else mSelected.add(kit);
                    box.setChecked(mSelected.contains(kit));
                    styleCard(card, box.isChecked());
                    updateButton();
                });
            }
        }
        updateButton();
    }

    private void updateButton() {
        int count = mSelected.size();
        mInstallButton.setText(count == 0 ? "Select something to install" : "Install " + count + (count == 1 ? " kit" : " kits"));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(26));
        shape.setColor(count == 0 ? 0xFF3A3C4A : ForgeColors.ACCENT);
        mInstallButton.setBackground(shape);
        mInstallButton.setEnabled(count > 0);
    }

    private void install() {
        if (mSelected.isEmpty()) return;
        run(new ArrayList<>(mSelected));
    }

    private void run(List<Kits.Kit> kits) {
        try {
            Kits.install(this, kits);
            finish();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start setup", e);
            Toast.makeText(this, "Could not start setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void styleCard(LinearLayout card, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(selected ? ForgeColors.blend(CARD, ForgeColors.ACCENT, 0.18f) : CARD);
        if (selected) shape.setStroke(dp(1), ForgeColors.ACCENT);
        card.setBackground(shape);
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
