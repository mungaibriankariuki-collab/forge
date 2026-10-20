package com.termux.forge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

/**
 * Forge's Appearance screen: colour theme (with previews), font, text size, cursor, keyboard bar
 * presets, the tabs bar and full screen, all in one place. Changes are written to the same files
 * Termux reads (colors.properties, font.ttf, termux.properties), so they stay compatible.
 */
public class AppearanceActivity extends Activity {

    private static final String LOG_TAG = "ForgeAppearance";

    private static final int BG = 0xFF101118;
    private static final int CARD = 0xFF1A1C26;
    private static final int TEXT = 0xFFE8E8EE;
    private static final int MUTED = 0xFF9A9CAA;

    /** Keyboard bar presets: name, description, extra-keys value (null = Termux default). */
    private static final String[][] KEY_PRESETS = {
        {"Minimal", "One row: ESC TAB CTRL ALT - arrows",
            "[['ESC','TAB','CTRL','ALT',{key: '-', popup: '|'},'LEFT','DOWN','UP','RIGHT']]"},
        {"Standard", "Termux's two rows (default)", null},
        {"Coder", "Three rows with brackets and symbols for coding",
            "[['ESC','TAB','CTRL','ALT',{key: '-', popup: '_'},{key: '/', popup: '?'},{key: '|', popup: '&'},'HOME','UP','END'], " +
            "['{','}','[',']','(',')',{key: '=', popup: '+'},{key: ';', popup: ':'},'LEFT','DOWN','RIGHT'], " +
            "[{key: '~', popup: '`'},'$','*','#','<','>','!','PGUP','PGDN','DEL','ENTER']]"},
    };

    private LinearLayout mRoot;
    private TextView mPreview;
    private TextView mFontSizeValue;
    private TermuxAppSharedPreferences mPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        mPreferences = TermuxAppSharedPreferences.build(this, true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(mRoot);
        setContentView(scroll);

        TextView title = text("Appearance", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        mRoot.addView(title);

        mPreview = text("", 14, TEXT);
        mPreview.setTypeface(Typeface.MONOSPACE);
        mPreview.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, dp(4));
        mRoot.addView(mPreview, lp);
        updatePreview();

        buildTextSize();
        buildFont();
        buildCursor();
        buildKeyboardBar();
        buildToggles();
        buildColours();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePreview(); // The font may have changed in the font picker.
    }

    // ---- Sections ----

    private void buildTextSize() {
        section("Text size");
        LinearLayout row = card(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = pill("A−");
        TextView plus = pill("A+");
        mFontSizeValue = text("", 16, TEXT);
        mFontSizeValue.setGravity(Gravity.CENTER);
        row.addView(minus);
        row.addView(mFontSizeValue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus);
        minus.setOnClickListener(v -> changeFontSize(false));
        plus.setOnClickListener(v -> changeFontSize(true));
        updateFontSizeLabel();
        TextView hint = text("Tip: pinch the terminal with two fingers to zoom too.", 12, MUTED);
        hint.setPadding(dp(4), dp(6), 0, 0);
        mRoot.addView(hint);
    }

    private void buildFont() {
        section("Font");
        LinearLayout row = card(LinearLayout.VERTICAL);
        TextView choose = text("Choose a font…", 16, TEXT);
        TextView sub = text("26 coding fonts (Fira Code, JetBrains Mono, Hack…). Downloaded once, then saved.", 12, MUTED);
        row.addView(choose);
        row.addView(sub);
        row.setOnClickListener(v -> startActivity(new Intent(this, StyleActivity.class)
            .putExtra(StyleActivity.EXTRA_MODE, StyleActivity.MODE_FONT)));
    }

    private void buildCursor() {
        section("Cursor");
        LinearLayout row = card(LinearLayout.HORIZONTAL);
        String current = ForgeSettings.getProperty(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE);
        if (current == null) current = "block";
        String[][] styles = {{"block", "█ Block"}, {"underline", "▁ Line"}, {"bar", "▏ Bar"}};
        TextView[] pills = new TextView[styles.length];
        for (int i = 0; i < styles.length; i++) {
            final String value = styles[i][0];
            pills[i] = pill(styles[i][1]);
            row.addView(pills[i], new LinearLayout.LayoutParams(0, dp(40), 1f));
            pills[i].setOnClickListener(v -> {
                setProperty(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, "block".equals(value) ? null : value);
                for (int j = 0; j < styles.length; j++) selectPill(pills[j], styles[j][0].equals(value));
            });
            selectPill(pills[i], value.equals(current));
        }

        String blink = ForgeSettings.getProperty(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE);
        boolean blinking = blink != null && !blink.trim().equals("0");
        addSwitch("Blinking cursor", "Blink every half second", blinking, (b, on) ->
            setProperty(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, on ? "500" : null));
    }

    private void buildKeyboardBar() {
        section("Keyboard bar");
        TermuxAppSharedPreferences prefs = mPreferences;
        addSwitch("Show the extra keys bar", "ESC, CTRL, arrows… above the keyboard",
            prefs == null || prefs.shouldShowTerminalToolbar(), (b, on) -> {
                if (prefs != null) prefs.setShowTerminalToolbar(on);
                ForgeSettings.markStyleChanged(this);
            });

        String current = ForgeSettings.getProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS);
        TextView[] rows = new TextView[KEY_PRESETS.length];
        for (int i = 0; i < KEY_PRESETS.length; i++) {
            final String[] preset = KEY_PRESETS[i];
            LinearLayout card = card(LinearLayout.VERTICAL);
            TextView name = text(preset[0], 16, TEXT);
            card.addView(name);
            card.addView(text(preset[1], 12, MUTED));
            rows[i] = name;
            boolean selected = preset[2] == null ? current == null : preset[2].equals(current);
            markCard(card, selected);
            card.setOnClickListener(v -> {
                setProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS, preset[2]);
                for (TextView r : rows) markCard((LinearLayout) r.getParent(), r == name);
                toast(preset[0] + " keyboard bar");
            });
        }
        TextView hint = text("Swipe up on a key for its second symbol. Advanced: edit extra-keys in ~/.termux/termux.properties.", 12, MUTED);
        hint.setPadding(dp(4), dp(6), 0, 0);
        mRoot.addView(hint);
    }

    private void buildToggles() {
        section("Screen");
        addSwitch("Tabs bar", "Session tabs at the top of the terminal", ForgeSettings.showTabs(this), (b, on) -> {
            ForgeSettings.setShowTabs(this, on);
            ForgeSettings.markStyleChanged(this);
        });
        String fullscreen = ForgeSettings.getProperty(TermuxPropertyConstants.KEY_USE_FULLSCREEN);
        addSwitch("Full screen", "Hide the status and navigation bars", "true".equalsIgnoreCase(fullscreen), (b, on) ->
            setProperty(TermuxPropertyConstants.KEY_USE_FULLSCREEN, on ? "true" : null));
    }

    private void buildColours() {
        section("Colour theme");
        String[] files;
        try {
            files = getAssets().list("colors");
        } catch (IOException e) {
            files = new String[0];
        }
        if (files == null) files = new String[0];
        Arrays.sort(files);

        addColourRow("Default", null);
        for (String f : files) {
            if (!f.endsWith(".properties")) continue;
            addColourRow(StyleActivity.displayName(f.substring(0, f.length() - ".properties".length())), "colors/" + f);
        }
    }

    // ---- Colour themes ----

    private void addColourRow(String name, String asset) {
        Properties props = new Properties();
        if (asset != null) {
            try (InputStream in = getAssets().open(asset)) {
                props.load(in);
            } catch (IOException e) {
                return;
            }
        }
        int bg = color(props, "background", 0xFF000000);
        int fg = color(props, "foreground", 0xFFFFFFFF);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(bg);
        shape.setStroke(dp(1), ForgeColors.withAlpha(fg, 0.15f));
        card.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        mRoot.addView(card, lp);

        TextView label = text(name, 14, fg);
        label.setTypeface(Typeface.MONOSPACE);
        card.addView(label);

        LinearLayout swatches = new LinearLayout(this);
        swatches.setPadding(0, dp(6), 0, 0);
        for (int i = 1; i <= 6; i++) { // red, green, yellow, blue, magenta, cyan
            View swatch = new View(this);
            GradientDrawable dot = new GradientDrawable();
            dot.setCornerRadius(dp(3));
            dot.setColor(color(props, "color" + i, DEFAULT_ANSI[i]));
            swatch.setBackground(dot);
            LinearLayout.LayoutParams s = new LinearLayout.LayoutParams(dp(22), dp(10));
            s.setMargins(0, 0, dp(5), 0);
            swatches.addView(swatch, s);
        }
        card.addView(swatches);

        card.setOnClickListener(v -> applyColours(name, asset));
    }

    private static final int[] DEFAULT_ANSI = {
        0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF6495ED, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5
    };

    private static int color(Properties props, String key, int fallback) {
        String value = props.getProperty(key);
        if (value == null) return fallback;
        try {
            return Color.parseColor(value.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void applyColours(String name, String asset) {
        try {
            File out = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
            AtomicFile atomic = new AtomicFile(out.getCanonicalFile());
            FileOutputStream stream = atomic.startWrite();
            if (asset == null) {
                stream.write("# Using default color theme.".getBytes(StandardCharsets.UTF_8));
            } else {
                try (InputStream in = getAssets().open(asset)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) stream.write(buffer, 0, n);
                }
            }
            atomic.finishWrite(stream);
            ForgeSettings.markStyleChanged(this);
            updatePreview();
            toast("Theme: " + name);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply colours", e);
            toast("Could not apply theme: " + e.getMessage());
        }
    }

    // ---- Preview, font size ----

    private void updatePreview() {
        Properties props = new Properties();
        File colours = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
        if (colours.isFile()) {
            try (InputStream in = new FileInputStream(colours)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }
        int bg = color(props, "background", 0xFF000000);
        int fg = color(props, "foreground", 0xFFFFFFFF);
        int green = color(props, "color2", DEFAULT_ANSI[2]);
        int blue = color(props, "color4", DEFAULT_ANSI[4]);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(bg);
        mPreview.setBackground(shape);
        mPreview.setTextColor(fg);

        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder();
        append(s, "~/projects", blue);
        append(s, " $ ", fg);
        append(s, "python app.py\n", fg);
        append(s, "Server running on ", fg);
        append(s, "http://localhost:8000", green);
        mPreview.setText(s);

        File font = TermuxConstants.TERMUX_FONT_FILE;
        Typeface typeface = Typeface.MONOSPACE;
        if (font.isFile() && font.length() > 0) {
            try {
                typeface = Typeface.createFromFile(font);
            } catch (Exception ignored) {
            }
        }
        mPreview.setTypeface(typeface);
        if (mPreferences != null) mPreview.setTextSize(TypedValue.COMPLEX_UNIT_PX, mPreferences.getFontSize());
    }

    private static void append(android.text.SpannableStringBuilder s, String text, int color) {
        int start = s.length();
        s.append(text);
        s.setSpan(new android.text.style.ForegroundColorSpan(color), start, s.length(), 0);
    }

    private void changeFontSize(boolean increase) {
        if (mPreferences == null) return;
        mPreferences.changeFontSize(increase);
        ForgeSettings.markStyleChanged(this);
        updateFontSizeLabel();
        updatePreview();
    }

    private void updateFontSizeLabel() {
        if (mPreferences == null) return;
        float density = getResources().getDisplayMetrics().density;
        mFontSizeValue.setText(Math.round(mPreferences.getFontSize() / density) + " dp");
    }

    // ---- Helpers ----

    private void setProperty(String key, String value) {
        try {
            ForgeSettings.setProperty(key, value);
            ForgeSettings.markStyleChanged(this);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write " + key, e);
            toast("Could not save: " + e.getMessage());
        }
    }

    private void section(String name) {
        TextView view = text(name.toUpperCase(), 12, ForgeColors.ACCENT);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLetterSpacing(0.08f);
        view.setPadding(dp(4), dp(22), 0, dp(4));
        mRoot.addView(view);
    }

    private LinearLayout card(int orientation) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(orientation);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        markCard(card, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        mRoot.addView(card, lp);
        return card;
    }

    private void markCard(LinearLayout card, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(selected ? ForgeColors.blend(CARD, ForgeColors.ACCENT, 0.18f) : CARD);
        if (selected) shape.setStroke(dp(1), ForgeColors.ACCENT);
        card.setBackground(shape);
    }

    private TextView pill(String label) {
        TextView pill = text(label, 15, TEXT);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(16), dp(8), dp(16), dp(8));
        selectPill(pill, false);
        return pill;
    }

    private void selectPill(TextView pill, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(20));
        shape.setColor(selected ? ForgeColors.ACCENT : 0xFF262836);
        pill.setBackground(shape);
        pill.setTextColor(selected ? Color.BLACK : TEXT);
        if (pill.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) pill.getLayoutParams()).setMargins(dp(3), 0, dp(3), 0);
        }
    }

    private void addSwitch(String label, String description, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout card = card(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 16, TEXT));
        texts.addView(text(description, 12, MUTED));
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        card.addView(toggle);
        card.setOnClickListener(v -> toggle.toggle());
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
