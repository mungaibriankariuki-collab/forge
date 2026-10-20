package com.termux.forge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Runs a bash script in a new, visible terminal tab of Forge. */
public final class TerminalRunner {

    private TerminalRunner() {}

    /**
     * Writes {@code script} (a bash script body, without the #! line) to $PREFIX/tmp and runs it
     * in a new tab called {@code tabName}, starting in {@code workingDirectory}.
     */
    public static void runInNewTab(Context context, String script, String tabName, String workingDirectory) throws IOException {
        File file = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, "forge-" + tabName + ".sh");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(("#!" + bash + "\n" + script).getBytes(StandardCharsets.UTF_8));
        }
        file.setExecutable(true, false);

        Uri uri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(bash).build();
        Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri);
        intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME);
        intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{file.getAbsolutePath()});
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, workingDirectory);
        intent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_NAME, tabName);
        // The service reads the session action as a string.
        intent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
            String.valueOf(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    /** Quotes a string for bash using single quotes. */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
