package com.termux.forge;

import java.io.File;
import java.util.Locale;

import static com.termux.forge.TerminalRunner.quote;

/** Works out how to run a source file, and builds the script for Forge's "run" tab. */
public final class CodeRunner {

    private CodeRunner() {}

    /** Shell command that runs {@code file}, or null if Forge does not know how to run it. */
    public static String commandFor(File file) {
        String name = file.getName();
        String f = quote(file.getName());
        String out = "\"$TMPDIR/forge-run\"";
        if (name.equals("Makefile") || name.equals("makefile")) return need("make", "C / C++") + "make";
        if (name.equals("Cargo.toml")) return need("cargo", "Rust") + "cargo run";
        if (name.equals("package.json")) return need("npm", "Node.js") + "npm start";

        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "py": return need("python", "Python") + "python " + f;
            case "js": case "mjs": case "cjs": return need("node", "Node.js") + "node " + f;
            case "ts": return need("npx", "Node.js") + "npx --yes tsx " + f;
            case "sh": case "bash": return "bash " + f;
            case "c": return need("clang", "C / C++") + "clang " + f + " -o " + out + " -lm && " + out;
            case "cpp": case "cc": case "cxx": return need("clang++", "C / C++") + "clang++ " + f + " -o " + out + " && " + out;
            case "go": return need("go", "Go") + "go run " + f;
            case "rs": return need("rustc", "Rust") + "rustc " + f + " -o " + out + " && " + out;
            case "java": return need("java", "Java") + "java " + f;
            case "rb": return need("ruby", null) + "ruby " + f;
            case "php": return need("php", null) + "php " + f;
            case "lua": return need("lua", null) + "lua " + f;
            case "pl": return need("perl", null) + "perl " + f;
            case "html": case "htm": return "termux-open " + f;
            default: return null;
        }
    }

    /** A guard that explains how to get a missing tool instead of a bare "command not found". */
    private static String need(String command, String kit) {
        String hint = kit != null
            ? "'" + command + " is not installed. Open Setup (menu) and install the " + kit + " kit.'"
            : "'" + command + " is not installed. Install it with: pkg install " + command + "'";
        return "command -v " + command + " >/dev/null 2>&1 || { echo " + hint + "; exit 127; } && ";
    }

    /** Script for the run tab: shows the command, runs it, shows the exit code and waits. */
    public static String script(String command) {
        // Show only the real command, not the "is the tool installed" guard in front of it.
        int guardEnd = command.startsWith("command -v ") ? command.indexOf("} && ") : -1;
        String shown = guardEnd >= 0 ? command.substring(guardEnd + "} && ".length()) : command;
        return "clear\n" +
            "printf '\\033[1;38;5;208m▶ %s\\033[0m\\n\\n' " + quote(shown) + "\n" +
            "( " + command + " )\n" +
            "code=$?\n" +
            "printf '\\n\\033[2m[exit code %s - press Enter to close]\\033[0m' \"$code\"\n" +
            "read -r _\n" +
            "exit 0\n";
    }
}
