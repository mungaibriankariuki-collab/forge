package com.termux.forge;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Installs Forge's own terminal commands into $PREFIX/bin: `code` and `ai`. */
public final class ForgeCommands {

    private static final String LOG_TAG = "ForgeCommands";

    private ForgeCommands() {}

    public static void install() {
        File bin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        if (!bin.isDirectory()) return; // Bootstrap not installed yet.
        String sh = "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh\n";
        String am = "am start -n " + TermuxConstants.TERMUX_PACKAGE_NAME;

        writeCommand(new File(bin, "code"), "# forge-code",
            sh + "# forge-code: open a file or folder in Forge's code editor.\n" +
            "p=\"${1:-.}\"\n" +
            "[ -e \"$p\" ] || touch \"$p\" || exit 1\n" +
            "p=\"$(realpath \"$p\")\"\n" +
            am + "/com.termux.forge.EditorActivity --es " + EditorActivity.EXTRA_PATH + " \"$p\" >/dev/null\n");

        writeCommand(new File(bin, "ai"), "# forge-ai",
            sh + "# forge-ai: ask Forge AI. Usage: ai how do I unzip a file\n" +
            am + "/com.termux.forge.AiActivity --es " + AiActivity.EXTRA_QUESTION + " \"$*\" --ez " +
            AiActivity.EXTRA_AUTOSEND + " true >/dev/null\n");
    }

    /** Writes the command unless a file of that name exists that Forge did not write (marker missing). */
    private static void writeCommand(File file, String marker, String script) {
        try {
            if (file.exists() && !new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains(marker)) return;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(script.getBytes(StandardCharsets.UTF_8));
            }
            file.setExecutable(true, false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install " + file.getName(), e);
        }
    }
}
