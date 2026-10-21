package com.termux.forge;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * The Forge Store catalog: coding agents and tools that install with one tap. Each item has a
 * bash install script (run in a visible terminal tab), an optional remove script, a check for
 * whether it is installed, and the command that opens it.
 *
 * Agents published only as Linux builds are installed with npm's --os/--cpu/--libc flags so
 * npm fetches their static (musl) Linux binary, which is the variant most likely to run on Android.
 */
public final class Store {

    public static final String AI = "AI agents";
    public static final String DEV = "Dev tools";
    public static final String LINUX = "Linux";
    public static final String MEDIA = "Media";

    public static final class Item {
        public final String id, name, category, description, note, check, open;
        final String install, remove;

        Item(String id, String name, String category, String description, String note,
             String check, String install, String remove, String open) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.description = description;
            this.note = note;
            this.check = check;
            this.install = install;
            this.remove = remove;
            this.open = open;
        }

        /** {@code check} is a command name in $PREFIX/bin, or a path when it contains a slash. */
        public boolean isInstalled() {
            File file = check.contains("/")
                ? new File(check.replace("$PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH))
                : new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, check);
            return file.exists();
        }
    }

    private static String node(String pkgs) {
        return "pkg install -y nodejs-lts ripgrep\nnpm install -g " + pkgs + "\n";
    }

    /**
     * Some agents only ship glibc Linux builds that do not run on Android directly. They are
     * installed inside a proot Ubuntu (the same one as the "Ubuntu" store item), and Forge adds a
     * small $PREFIX/bin/NAME command that runs them there with ~ shared, starting in the matching
     * folder, so for the user it works like any other command.
     */
    private static String ubuntuInstall(String name, String insideUbuntu) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        return "pkg install -y proot-distro\n" +
            "proot-distro list 2>/dev/null | grep -qw ubuntu || proot-distro install ubuntu:24.04 --name ubuntu\n" +
            "proot-distro login ubuntu -- bash -ec '\n" +
            "export DEBIAN_FRONTEND=noninteractive\n" +
            "apt-get update\n" +
            "apt-get install -y curl ca-certificates git ripgrep\n" +
            insideUbuntu.replace("'", "'\\''") +
            "'\n" +
            "cat > \"$PREFIX/bin/" + name + "\" <<'FORGE_WRAPPER'\n" +
            "#!" + prefix + "/bin/bash\n" +
            "# forge-store wrapper: runs " + name + " inside the built-in Ubuntu.\n" +
            "# Forge's home folder is visible inside Ubuntu at the same path, so start in the current folder.\n" +
            "exec proot-distro login ubuntu --shared-tmp --work-dir \"$PWD\" -- " +
            "env TERM=\"$TERM\" COLORTERM=\"$COLORTERM\" LANG=C.UTF-8 DISABLE_AUTOUPDATER=1 /usr/local/bin/" + name + " \"$@\"\n" +
            "FORGE_WRAPPER\n" +
            "chmod 755 \"$PREFIX/bin/" + name + "\"\n";
    }

    private static String ubuntuRemove(String name) {
        return "rm -f \"$PREFIX/bin/" + name + "\"\nproot-distro login ubuntu -- rm -f /usr/local/bin/" + name;
    }

    public static final Item[] ITEMS = {
        new Item("claude-code", "Claude Code", AI,
            "Anthropic's coding agent in the terminal: edits files, runs commands, explains code.",
            "Runs inside a built-in Ubuntu 24.04 (installed automatically, ~300 MB). Needs a Claude Pro/Max plan or an Anthropic API key.",
            "claude",
            ubuntuInstall("claude",
                "curl -fsSL https://claude.ai/install.sh | bash\n" +
                "cp -L /root/.local/bin/claude /usr/local/bin/claude\n"),
            ubuntuRemove("claude"), "claude"),
        new Item("codex", "OpenAI Codex CLI", AI,
            "OpenAI's coding agent for the terminal (official Termux build).",
            "Needs a ChatGPT plan or an OpenAI API key. Sign in on first start.",
            "codex", "pkg install -y codex\n", "pkg uninstall -y codex", "codex"),
        new Item("gemini-cli", "Gemini CLI", AI,
            "Google's open-source coding agent. Free tier with a Google account.",
            "Sign in with Google, or use your Gemini API key (export GEMINI_API_KEY=...).",
            "gemini", node("@google/gemini-cli"), "npm uninstall -g @google/gemini-cli", "gemini"),
        new Item("qwen-code", "Qwen Code", AI,
            "Alibaba's open-source coding agent (based on Gemini CLI).",
            "Free sign-in with a Qwen account, or any OpenAI-compatible API key.",
            "qwen", node("@qwen-code/qwen-code"), "npm uninstall -g @qwen-code/qwen-code", "qwen"),
        new Item("opencode", "OpenCode", AI,
            "Open-source terminal coding agent that works with many AI providers.",
            "Runs inside a built-in Ubuntu (installed automatically, ~150 MB). Bring your own API key.",
            "opencode",
            ubuntuInstall("opencode",
                "curl -fsSL https://opencode.ai/install | bash\n" +
                "cp -L /root/.opencode/bin/opencode /usr/local/bin/opencode\n"),
            ubuntuRemove("opencode"), "opencode"),
        new Item("copilot-cli", "GitHub Copilot CLI", AI,
            "GitHub's coding agent in the terminal.",
            "Runs inside a built-in Ubuntu (installed automatically). Needs a GitHub Copilot subscription.",
            "copilot",
            ubuntuInstall("copilot",
                "curl -fsSL https://deb.nodesource.com/setup_22.x | bash -\n" +
                "apt-get install -y nodejs\n" +
                "npm install -g @github/copilot\n" +
                "ln -sf \"$(command -v copilot)\" /usr/local/bin/copilot\n"),
            ubuntuRemove("copilot"), "copilot"),
        new Item("crush", "Crush", AI,
            "Charm's glamorous terminal coding agent. Built from source with Go (takes a few minutes).",
            "Bring your own API key (Anthropic, OpenAI, Gemini, OpenRouter…).",
            "crush",
            "pkg install -y golang git\n" +
            "go install github.com/charmbracelet/crush@latest\n" +
            "ln -sf \"$(go env GOPATH)/bin/crush\" \"$PREFIX/bin/crush\"\n",
            "rm -f \"$PREFIX/bin/crush\" \"$(go env GOPATH)/bin/crush\"", "crush"),
        new Item("aider", "Aider", AI,
            "AI pair programming in the terminal; works great with git.",
            "Experimental: big install. Bring your own API key.",
            "aider",
            "pkg install -y python python-pip git rust binutils python-numpy\n" +
            "pip install aider-chat\n",
            "pip uninstall -y aider-chat", "aider"),

        new Item("code-server", "VS Code (code-server)", DEV,
            "Full VS Code in your phone's browser at http://localhost:8080.",
            "Open starts the server and your browser. Stop it with Ctrl+C in its tab.",
            "code-server",
            "pkg install -y tur-repo\npkg install -y code-server\n",
            "pkg uninstall -y code-server",
            "(sleep 4; termux-open-url http://localhost:8080) &\ncode-server --auth none --bind-addr 127.0.0.1:8080"),
        new Item("ollama", "Ollama", DEV,
            "Run AI models locally on the phone, offline (small models only).",
            "Open starts the server and a tiny model chat (qwen2.5 0.5B, ~400 MB download).",
            "ollama", "pkg install -y ollama\n", "pkg uninstall -y ollama",
            "ollama serve > \"$TMPDIR/ollama.log\" 2>&1 &\nsleep 3\nollama run qwen2.5:0.5b"),
        new Item("lazygit", "lazygit", DEV,
            "A friendly full-screen git interface.", "", "lazygit",
            "pkg install -y git lazygit\n", "pkg uninstall -y lazygit", "lazygit"),
        new Item("tmux", "tmux", DEV,
            "Split the terminal into panes and keep sessions running.", "", "tmux",
            "pkg install -y tmux\n", "pkg uninstall -y tmux", "tmux"),
        new Item("zsh", "Zsh + Oh My Zsh", DEV,
            "A friendlier shell with themes and plugins, set as the default shell.",
            "Changes your login shell to zsh. Remove switches back to bash.",
            "$PREFIX/bin/zsh",
            "pkg install -y zsh git curl\n" +
            "[ -d ~/.oh-my-zsh ] || RUNZSH=no CHSH=no sh -c \"$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\"\n" +
            "chsh -s zsh\n",
            "chsh -s bash", "zsh"),
        new Item("fastfetch", "fastfetch", DEV,
            "Show your phone's system info with style.", "", "fastfetch",
            "pkg install -y fastfetch\n", "pkg uninstall -y fastfetch", "fastfetch; read -r -p 'Press Enter to close. ' _"),

        new Item("ubuntu", "Ubuntu", LINUX,
            "A full Ubuntu system inside Forge (proot, no root needed). Uses apt.",
            "About 100 MB download. Open logs you in as root inside Ubuntu.",
            "$PREFIX/var/lib/proot-distro/containers/ubuntu",
            "pkg install -y proot-distro\nproot-distro list 2>/dev/null | grep -qw ubuntu || proot-distro install ubuntu:24.04 --name ubuntu\n",
            "proot-distro remove ubuntu", "proot-distro login ubuntu"),
        new Item("debian", "Debian", LINUX,
            "A full Debian system inside Forge (proot, no root needed).",
            "About 50 MB download.",
            "$PREFIX/var/lib/proot-distro/containers/debian",
            "pkg install -y proot-distro\nproot-distro list 2>/dev/null | grep -qw debian || proot-distro install debian:bookworm --name debian\n",
            "proot-distro remove debian", "proot-distro login debian"),

        new Item("yt-dlp", "yt-dlp", MEDIA,
            "Download videos and audio from YouTube and 1000+ sites.",
            "Example: yt-dlp -x --audio-format mp3 URL", "yt-dlp",
            "pkg install -y python-yt-dlp ffmpeg\n", "pkg uninstall -y python-yt-dlp",
            "yt-dlp --help | head -40; read -r -p 'Press Enter to close. ' _"),
        new Item("ffmpeg", "FFmpeg", MEDIA,
            "Convert, cut and compress audio and video.", "Example: ffmpeg -i in.mp4 out.mp3", "ffmpeg",
            "pkg install -y ffmpeg\n", "pkg uninstall -y ffmpeg",
            "ffmpeg -version | head -3; read -r -p 'Press Enter to close. ' _"),
    };

    private Store() {}

    public static Item byId(String id) {
        for (Item item : ITEMS) if (item.id.equals(id)) return item;
        return null;
    }

    /** Output words that mean a tool did not really start, even if it exited with 0. */
    private static final String FAILURE_WORDS = "error|not found|cannot|unsupported|exec format|no platform package|not installed|failed";

    private static final String HELPERS =
        "export DEBIAN_FRONTEND=noninteractive\n" +
        "step() { printf '\\n\\033[1;38;5;208m== %s ==\\033[0m\\n' \"$1\"; }\n" +
        "forge_cpu() { case \"$(uname -m)\" in aarch64|arm64) echo arm64 ;; x86_64) echo x64 ;; armv7l|armv8l) echo arm ;; *) uname -m ;; esac; }\n";

    /** Script for the install tab: installs, checks that the command works, then waits for Enter. */
    public static String installScript(Item item) {
        return HELPERS +
            "step 'Installing " + item.name + "'\n" +
            "( set -e\n" + item.install + ")\n" +
            "ok=$?\n" +
            verify(item) +
            "if [ $ok = 0 ] && [ $works = 1 ]; then printf '\\n\\033[1;32m" + item.name + " is installed. Open it from the Store, or type: " +
            escapeSingle(firstWord(item.open)) + "\\033[0m\\n'; " +
            "else printf '\\n\\033[1;31mInstall failed - scroll up to see why.\\033[0m\\n'; fi\n" +
            "read -r -p 'Press Enter to close this tab. ' _\n";
    }

    public static String removeScript(Item item) {
        return HELPERS + "step 'Removing " + item.name + "'\n" + item.remove + "\n" +
            "read -r -p 'Done. Press Enter to close this tab. ' _\n";
    }

    /** Sets works=1 if the installed thing actually starts (prints a version), else 0. */
    private static String verify(Item item) {
        if (item.check.contains("/")) return "works=0; [ -e \"" + item.check + "\" ] && works=1\n";
        return "works=0; if command -v " + item.check + " >/dev/null 2>&1; then " +
            "v=$(timeout 120 " + item.check + " --version 2>&1); vrc=$?; echo \"$v\" | head -3; " +
            "[ $vrc = 0 ] && ! echo \"$v\" | grep -qiE '" + FAILURE_WORDS + "' && works=1; fi\n";
    }

    /**
     * The `forge-store` terminal command: list, install, remove and test items from the shell.
     * `forge-store test ID…` installs each item and prints PASS/FAIL with its version, for checking
     * which agents work on a device.
     */
    public static String cliScript() {
        StringBuilder s = new StringBuilder();
        s.append("#!").append(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH).append("/bash\n");
        s.append("# forge-store: install coding agents and tools. Usage: forge-store list | install ID | remove ID | test ID...\n");
        s.append(HELPERS);
        s.append("install_item() {\n  case \"$1\" in\n");
        for (Item item : ITEMS) s.append("    ").append(item.id).append(") ( set -e\n").append(item.install).append(") ;;\n");
        s.append("    *) echo \"Unknown item: $1\"; return 2 ;;\n  esac\n}\n");
        s.append("remove_item() {\n  case \"$1\" in\n");
        for (Item item : ITEMS) s.append("    ").append(item.id).append(") ").append(item.remove.replace("\n", "; ")).append(" ;;\n");
        s.append("    *) echo \"Unknown item: $1\"; return 2 ;;\n  esac\n}\n");
        s.append("check_cmd() {\n  case \"$1\" in\n");
        for (Item item : ITEMS) s.append("    ").append(item.id).append(") echo '").append(escapeSingle(item.check)).append("' ;;\n");
        s.append("  esac\n}\n");
        s.append("case \"$1\" in\n");
        s.append("  list)\n");
        for (Item item : ITEMS) s.append("    printf '%-12s %s\\n' '").append(item.id).append("' '").append(escapeSingle(item.name)).append("'\n");
        s.append("    ;;\n");
        s.append("  install) shift; for i in \"$@\"; do step \"Installing $i\"; install_item \"$i\"; done ;;\n");
        s.append("  remove) shift; for i in \"$@\"; do remove_item \"$i\"; done ;;\n");
        s.append("  test)\n    shift\n    report=\"$HOME/forge-store-test.txt\"; : > \"$report\"\n");
        s.append("    for i in \"$@\"; do\n");
        s.append("      step \"Testing $i\"\n");
        s.append("      install_item \"$i\" > \"$TMPDIR/forge-store-$i.log\" 2>&1; rc=$?\n");
        s.append("      c=$(check_cmd \"$i\")\n");
        s.append("      if [ \"${c#*/}\" != \"$c\" ]; then [ -e \"$(eval echo \"$c\")\" ] && v=installed || v=missing\n");
        s.append("      else v=$(timeout 120 \"$c\" --version 2>&1); vrc=$?; command -v \"$c\" >/dev/null || { v=\"not found\"; vrc=127; }\n");
        s.append("        [ $vrc = 0 ] || v=\"exit $vrc: $v\"; v=$(echo \"$v\" | head -1); fi\n");
        s.append("      if [ $rc = 0 ] && [ \"${v#exit }\" = \"$v\" ] && [ \"$v\" != missing ] && [ \"$v\" != 'not found' ] && ! echo \"$v\" | grep -qiE '" + FAILURE_WORDS + "'; then r=PASS; else r=FAIL; fi\n");
        s.append("      line=\"$r $i: $(echo \"$v\" | cut -c1-60)\"\n");
        s.append("      echo \"$line\" | tee -a \"$report\"\n");
        s.append("      [ $r = FAIL ] && tail -5 \"$TMPDIR/forge-store-$i.log\" | sed 's/^/    /' | tee -a \"$report\"\n");
        s.append("    done\n    step 'Summary'; grep -E '^(PASS|FAIL)' \"$report\" ;;\n");
        s.append("  report) grep -E '^(PASS|FAIL)' \"$HOME/forge-store-test.txt\" ;;\n");
        s.append("  *) echo 'Usage: forge-store list | install ID... | remove ID... | test ID... | report' ;;\n");
        s.append("esac\n");
        return s.toString();
    }

    private static String firstWord(String command) {
        String line = command.split("\n")[command.split("\n").length - 1].trim();
        return line.startsWith("(") ? line : line.split("[ ;]")[0];
    }

    private static String escapeSingle(String value) {
        return value.replace("'", "'\\''");
    }
}
