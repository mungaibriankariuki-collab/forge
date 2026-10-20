package com.termux.api;

import android.app.Application;
import android.content.Context;

import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Forge bundles Termux:API into the main app. The `termux-api` package in apt still targets the
 * separate `com.termux.api` app, so this class starts the API side inside Forge and rewrites those
 * package files to target `com.termux` instead.
 */
public final class ForgeApi {

    private static final String LOG_TAG = "ForgeApi";

    /** Component used by the stock `termux-api` binary, and the same-length one Forge uses (both 33 bytes). */
    private static final byte[] OLD_RECEIVER = "com.termux.api/.TermuxApiReceiver".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NEW_RECEIVER = "com.termux/.api.TermuxApiReceiver".getBytes(StandardCharsets.US_ASCII);

    private ForgeApi() {}

    /** Called from the application's onCreate. */
    public static void init(Application context) {
        ResultReturner.setContext(context);
        SocketListener.createSocketListener(context);
        new Thread(() -> fixupPackages(), "forge-api-fixup").start();
    }

    /** Rewrite the termux-api package files (if installed) and install the apt hook that repeats it. */
    public static synchronized void fixupPackages() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        if (!new File(prefix, "bin").isDirectory()) return; // Bootstrap not installed yet.
        try {
            installAptHook(prefix);
            patchBinary(new File(prefix, "libexec/termux-api"));
            patchScript(new File(prefix, "bin/termux-api-start"));
            patchScript(new File(prefix, "bin/termux-api-stop"));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to fix up termux-api package files", e);
        }
    }

    private static void installAptHook(String prefix) throws Exception {
        // Re-run the same rewrite with sed after every apt/dpkg run, so `pkg install termux-api`
        // works immediately without restarting Forge. Both strings are 33 bytes long, so the binary keeps its layout.
        File script = new File(prefix, "libexec/forge-api-fixup");
        String body = "#!" + prefix + "/bin/sh\n" +
            "# Written by Forge: point termux-api at the API built into Forge.\n" +
            "f=" + prefix + "/libexec/termux-api\n" +
            "[ -f \"$f\" ] && grep -q 'com.termux.api/.TermuxApiReceiver' \"$f\" && " +
            "sed -i 's|com\\.termux\\.api/\\.TermuxApiReceiver|com.termux/.api.TermuxApiReceiver|' \"$f\"\n" +
            "for s in termux-api-start termux-api-stop; do\n" +
            "  f=" + prefix + "/bin/$s\n" +
            "  [ -f \"$f\" ] && sed -i 's|com\\.termux\\.api/\\.KeepAliveService|com.termux/com.termux.api.KeepAliveService|' \"$f\"\n" +
            "done\n" +
            "exit 0\n";
        writeIfChanged(script, body);
        script.setExecutable(true, false);

        File conf = new File(prefix, "etc/apt/apt.conf.d/99forge-api");
        writeIfChanged(conf, "DPkg::Post-Invoke { \"" + script.getAbsolutePath() + " || true\"; };\n");
    }

    private static void patchBinary(File file) throws Exception {
        if (!file.isFile()) return;
        byte[] data = Files.readAllBytes(file.toPath());
        int index = indexOf(data, OLD_RECEIVER);
        if (index < 0) return;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(index);
            raf.write(NEW_RECEIVER);
        }
        Logger.logInfo(LOG_TAG, "Patched " + file);
    }

    private static void patchScript(File file) throws Exception {
        if (!file.isFile()) return;
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String fixed = text.replace("com.termux.api/.KeepAliveService", "com.termux/com.termux.api.KeepAliveService");
        if (!fixed.equals(text)) writeIfChanged(file, fixed);
    }

    private static void writeIfChanged(File file, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (file.isFile() && java.util.Arrays.equals(Files.readAllBytes(file.toPath()), bytes)) return;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
