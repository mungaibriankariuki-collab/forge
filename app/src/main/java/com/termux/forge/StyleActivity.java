package com.termux.forge;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.AtomicFile;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Termux:Styling built into Forge. Colour schemes ship inside the APK; fonts (64 MB in total) are
 * downloaded from the termux-styling repository the first time one is picked, then cached.
 */
public class StyleActivity extends Activity {

    private static final String LOG_TAG = "ForgeStyle";
    private static final String DEFAULT = "Default";

    /** termux-styling commit the font files are fetched from. */
    private static final String FONTS_BASE_URL =
        "https://raw.githubusercontent.com/termux/termux-styling/79338cb19f4b86cae4d2e81e6de60ba7f613bb9b/app/src/main/assets/fonts/";

    /** Fonts available in termux-styling; the ones marked with a licence have a .txt next to them. */
    private static final String[] FONTS = {
        "Anonymous-Pro", "Bedstead-Condensed", "CascadiaCode", "Courier-Prime", "D2-Coding",
        "DejaVu-Sans-Mono", "Fantasque-Sans-Mono", "Fira-Code", "Fira-Mono", "GNU-FreeFont",
        "Go-Mono", "Hack", "Hermit", "Inconsolata", "Iosevka", "JetBrains-Mono", "Liberation-Mono",
        "Meslo", "Monofur", "Monoid", "OpenDyslexic", "Roboto-Mono", "Source-Code-Pro", "Terminus",
        "Ubuntu-Mono", "Victor-Mono"
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    static final class Item {
        final String fileName; // Without extension, or DEFAULT.
        Item(String fileName) { this.fileName = fileName; }
        @Override public String toString() { return displayName(fileName); }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showChooser();
    }

    private void showChooser() {
        new AlertDialog.Builder(this)
            .setTitle("Style")
            .setItems(new String[]{"Colours", "Font"}, (d, which) -> {
                if (which == 0) showColours(); else showFonts();
            })
            .setOnCancelListener(d -> finish())
            .show();
    }

    private void showColours() {
        List<Item> items = new ArrayList<>();
        items.add(new Item(DEFAULT));
        try {
            String[] files = getAssets().list("colors");
            if (files != null) {
                java.util.Arrays.sort(files);
                for (String f : files)
                    if (f.endsWith(".properties")) items.add(new Item(f.substring(0, f.length() - ".properties".length())));
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to list colour schemes", e);
        }
        showList("Colours (long-press for licence)", items, true);
    }

    private void showFonts() {
        List<Item> items = new ArrayList<>();
        items.add(new Item(DEFAULT));
        for (String f : FONTS) items.add(new Item(f));
        showList("Font (long-press for licence)", items, false);
    }

    private void showList(String title, List<Item> items, boolean colours) {
        ArrayAdapter<Item> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setAdapter(adapter, (d, which) -> {
                Item item = adapter.getItem(which);
                if (colours) applyColours(item); else applyFont(item);
            })
            .setOnCancelListener(d -> finish())
            .create();
        dialog.setOnShowListener(d -> dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            showLicence(adapter.getItem(position), colours);
            return true;
        }));
        dialog.show();
    }

    private void applyColours(Item item) {
        try {
            File out = termuxConfigFile("colors.properties");
            AtomicFile atomic = new AtomicFile(out);
            FileOutputStream stream = atomic.startWrite();
            if (DEFAULT.equals(item.fileName)) {
                stream.write("# Using default color theme.".getBytes(StandardCharsets.UTF_8));
            } else {
                try (InputStream in = getAssets().open("colors/" + item.fileName + ".properties")) {
                    copy(in, stream);
                }
            }
            atomic.finishWrite(stream);
            reloadAndFinish("Colours: " + item);
        } catch (Exception e) {
            fail("Could not apply colours", e);
        }
    }

    private void applyFont(Item item) {
        if (DEFAULT.equals(item.fileName)) {
            try {
                File out = termuxConfigFile("font.ttf");
                if (out.exists() && !out.delete()) throw new IOException("Cannot delete " + out);
                reloadAndFinish("Font: Default");
            } catch (Exception e) {
                fail("Could not reset font", e);
            }
            return;
        }

        File cached = new File(new File(getFilesDir(), "forge/fonts"), item.fileName + ".ttf");
        if (cached.isFile()) {
            installFont(cached, item);
            return;
        }

        AlertDialog progress = new AlertDialog.Builder(this)
            .setMessage("Downloading " + item + "…")
            .setCancelable(false)
            .show();
        new Thread(() -> {
            try {
                download(FONTS_BASE_URL + item.fileName + ".ttf", cached);
                mHandler.post(() -> { progress.dismiss(); installFont(cached, item); });
            } catch (Exception e) {
                mHandler.post(() -> { progress.dismiss(); fail("Could not download font (check internet)", e); });
            }
        }, "forge-font-download").start();
    }

    private void installFont(File source, Item item) {
        try {
            File out = termuxConfigFile("font.ttf");
            AtomicFile atomic = new AtomicFile(out);
            FileOutputStream stream = atomic.startWrite();
            try (InputStream in = new FileInputStream(source)) {
                copy(in, stream);
            }
            atomic.finishWrite(stream);
            reloadAndFinish("Font: " + item);
        } catch (Exception e) {
            fail("Could not apply font", e);
        }
    }

    private void showLicence(Item item, boolean colours) {
        if (DEFAULT.equals(item.fileName)) return;
        if (colours) {
            try (InputStream in = getAssets().open("colors/" + item.fileName + ".txt")) {
                showText(item.toString(), new String(readAll(in), StandardCharsets.UTF_8));
            } catch (IOException e) {
                Toast.makeText(this, "No licence file for " + item, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "font-licence.txt");
                download(FONTS_BASE_URL + item.fileName + ".txt", tmp);
                String text = new String(readAll(new FileInputStream(tmp)), StandardCharsets.UTF_8);
                mHandler.post(() -> showText(item.toString(), text));
            } catch (Exception e) {
                mHandler.post(() -> Toast.makeText(this, "No licence file for " + item, Toast.LENGTH_SHORT).show());
            }
        }, "forge-font-licence").start();
    }

    private void showText(String title, String text) {
        SpannableString message = new SpannableString(text);
        Linkify.addLinks(message, Linkify.ALL);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        TextView view = dialog.findViewById(android.R.id.message);
        if (view != null) view.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private File termuxConfigFile(String name) throws IOException {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux");
        if (!(dir.isDirectory() || dir.mkdirs())) throw new IOException("Cannot create " + dir);
        // Use the canonical file to follow a possible symlink, and repair permissions if the user changed them.
        File file = new File(dir, name).getCanonicalFile();
        file.setWritable(true);
        File parent = file.getParentFile();
        if (parent != null) { parent.setWritable(true); parent.setExecutable(true); }
        return file;
    }

    private void reloadAndFinish(String message) {
        TermuxActivity.updateTermuxActivityStyling(this, false);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void fail(String message, Exception e) {
        Logger.logStackTraceWithMessage(LOG_TAG, message, e);
        Toast.makeText(this, message + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        finish();
    }

    private static void download(String url, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            File partial = new File(target.getPath() + ".part");
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(partial)) {
                copy(in, out);
            }
            if (!partial.renameTo(target)) throw new IOException("Cannot save " + target);
        } finally {
            connection.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            copy(input, out);
            return out.toByteArray();
        }
    }

    /** "base16-default-dark" -> "Base16 Default Dark". */
    static String displayName(String fileName) {
        char[] chars = fileName.replace('-', ' ').toCharArray();
        boolean start = true;
        for (int i = 0; i < chars.length; i++) {
            if (Character.isLetter(chars[i])) {
                if (start) chars[i] = Character.toUpperCase(chars[i]);
                start = false;
            } else {
                start = Character.isWhitespace(chars[i]);
            }
        }
        return new String(chars);
    }
}
