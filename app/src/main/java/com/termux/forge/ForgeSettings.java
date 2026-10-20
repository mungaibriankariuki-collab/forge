package com.termux.forge;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge's own preferences, plus small edits to termux.properties so settings changed in the
 * Appearance screen stay compatible with how Termux reads them.
 */
public final class ForgeSettings {

    private static final String PREFS = "forge";
    private static final String KEY_SHOW_TABS = "show_tabs";
    private static final String KEY_STYLE_CHANGED = "style_changed";
    private static final String KEY_WELCOME_SHOWN = "welcome_shown";

    private ForgeSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean showTabs(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_TABS, true);
    }

    public static void setShowTabs(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_SHOW_TABS, show).apply();
    }

    /** Returns true the first time it is called, so the welcome setup is shown only once. */
    public static boolean consumeFirstRunSetup(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_WELCOME_SHOWN, false)) return false;
        prefs.edit().putBoolean(KEY_WELCOME_SHOWN, true).apply();
        return true;
    }

    /** Marks that the terminal must reload its styling when it is next shown. */
    public static void markStyleChanged(Context context) {
        prefs(context).edit().putBoolean(KEY_STYLE_CHANGED, true).apply();
    }

    /** Returns true once after {@link #markStyleChanged}. */
    public static boolean consumeStyleChanged(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_STYLE_CHANGED, false)) return false;
        prefs.edit().putBoolean(KEY_STYLE_CHANGED, false).apply();
        return true;
    }

    /** The termux.properties file Termux will read: the primary one, else the secondary one if present. */
    public static File propertiesFile() {
        File primary = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        File secondary = TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE;
        if (!primary.exists() && secondary.exists()) return secondary;
        return primary;
    }

    /** Current value of {@code key} in termux.properties, or null when unset. */
    public static String getProperty(String key) {
        for (String line : readLines(propertiesFile())) {
            Matcher m = linePattern(key).matcher(line);
            if (m.matches()) return m.group(1).trim();
        }
        return null;
    }

    /**
     * Sets {@code key = value} in termux.properties, replacing an existing uncommented line or
     * appending one. A null value removes the line so Termux uses its default.
     */
    public static void setProperty(String key, String value) throws IOException {
        File file = propertiesFile();
        List<String> lines = readLines(file);
        List<String> out = new ArrayList<>();
        boolean done = false;
        for (String line : lines) {
            if (linePattern(key).matcher(line).matches()) {
                if (!done && value != null) out.add(key + " = " + value);
                done = true;
            } else {
                out.add(line);
            }
        }
        if (!done && value != null) out.add(key + " = " + value);

        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write((String.join("\n", out) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Pattern linePattern(String key) {
        return Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*[=:](.*)$");
    }

    private static List<String> readLines(File file) {
        try {
            if (file.isFile()) return new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        return new ArrayList<>();
    }
}
