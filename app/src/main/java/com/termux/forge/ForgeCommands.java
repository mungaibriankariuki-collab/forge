package com.termux.forge;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Installs Forge's own terminal commands into $PREFIX/bin (currently: `code`). */
public final class ForgeCommands {

    private static final String LOG_TAG = "ForgeCommands";
    private static final String MARKER = "# forge-code";

    private ForgeCommands() {}

    public static void install() {
        File bin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        if (!bin.isDirectory()) return; // Bootstrap not installed yet.
        try {
            File code = new File(bin, "code");
            // Never overwrite a `code` command that some package installed.
            if (code.exists() && !new String(Files.readAllBytes(code.toPath()), StandardCharsets.UTF_8).contains(MARKER)) return;
            String script = "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh\n" +
                MARKER + ": open a file or folder in Forge's code editor.\n" +
                "p=\"${1:-.}\"\n" +
                "[ -e \"$p\" ] || touch \"$p\" || exit 1\n" +
                "p=\"$(realpath \"$p\")\"\n" +
                "am start -n " + TermuxConstants.TERMUX_PACKAGE_NAME + "/com.termux.forge.EditorActivity --es " +
                EditorActivity.EXTRA_PATH + " \"$p\" >/dev/null\n";
            try (FileOutputStream out = new FileOutputStream(code)) {
                out.write(script.getBytes(StandardCharsets.UTF_8));
            }
            code.setExecutable(true, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install the code command", e);
        }
    }
}
