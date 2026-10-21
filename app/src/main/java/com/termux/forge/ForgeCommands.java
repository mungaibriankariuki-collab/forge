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

        writeCommand(new File(bin, "forge-store"), "# forge-store", Store.cliScript());
        writeCommand(new File(bin, "forge-ubuntu"), "# forge-ubuntu", Store.ubuntuRunner());

        // Save each command to ~/.bash_history as soon as it runs, so the history panel is current.
        File profileD = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/profile.d");
        if (profileD.isDirectory() || profileD.mkdirs()) {
            writeCommand(new File(profileD, "forge-history.sh"), "# forge-history",
                "# forge-history: save each command right away so Forge's history panel sees it.\n" +
                "if [ -n \"$BASH_VERSION\" ]; then\n" +
                "  shopt -s histappend\n" +
                "  case \";$PROMPT_COMMAND;\" in *\"history -a\"*) ;; *) PROMPT_COMMAND=\"history -a${PROMPT_COMMAND:+;$PROMPT_COMMAND}\" ;; esac\n" +
                "  [ \"${HISTSIZE:-0}\" -lt 5000 ] 2>/dev/null && HISTSIZE=5000\n" +
                "  [ \"${HISTFILESIZE:-0}\" -lt 10000 ] 2>/dev/null && HISTFILESIZE=10000\n" +
                "fi\n");
        }
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
