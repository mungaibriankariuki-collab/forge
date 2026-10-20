package com.termux.forge;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Forge's one-tap installer kits. A kit is a set of Termux packages plus a few follow-up steps.
 * Selected kits are turned into one bash script that runs in a new, visible terminal tab, so the
 * user can watch progress and answer any prompts.
 */
public final class Kits {

    public static final class Kit {
        public final String id, title, description, checkCommand;
        final String packages;
        final String after;

        Kit(String id, String title, String description, String checkCommand, String packages, String after) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.checkCommand = checkCommand;
            this.packages = packages;
            this.after = after;
        }

        public boolean isInstalled() {
            if ("storage".equals(id)) return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "storage").isDirectory();
            return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, checkCommand).exists();
        }
    }

    public static final Kit[] ALL = {
        new Kit("storage", "Phone storage", "Access Downloads, DCIM and shared files from ~/storage", null, "",
            "termux-setup-storage\necho 'Allow the permission pop-up; your files appear in ~/storage.'"),
        new Kit("essentials", "Essentials", "curl, wget, zip, unzip, htop, tree, jq, nano", "htop",
            "curl wget zip unzip htop tree jq nano", ""),
        new Kit("python", "Python", "Python 3 and pip", "python",
            "python python-pip", "python --version\npip --version"),
        new Kit("node", "Node.js", "Node.js LTS and npm", "node",
            "nodejs-lts", "echo \"node $(node -v), npm $(npm -v)\""),
        new Kit("git", "Git & GitHub", "git, GitHub CLI (gh) and SSH; sets your commit name and email", "gh",
            "git gh openssh",
            "if [ -z \"$(git config --global user.name)\" ]; then\n" +
            "  read -r -p 'Your name for git commits: ' n && [ -n \"$n\" ] && git config --global user.name \"$n\"\n" +
            "  read -r -p 'Your email for git commits: ' e && [ -n \"$e\" ] && git config --global user.email \"$e\"\n" +
            "fi\n" +
            "git --version\n" +
            "echo 'To sign in to GitHub later, run: gh auth login'"),
        new Kit("ssh", "SSH", "SSH client and server; creates your SSH key", "ssh",
            "openssh",
            "[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N '' -f ~/.ssh/id_ed25519 -q\n" +
            "echo 'Your public key (add it to GitHub or a server):'\n" +
            "cat ~/.ssh/id_ed25519.pub\n" +
            "echo 'To log in to this phone from a PC: run passwd, then sshd (port 8022).'"),
        new Kit("c", "C / C++", "clang, make and cmake", "clang",
            "clang make cmake", "clang --version | head -1"),
        new Kit("java", "Java", "OpenJDK 21", "java",
            "openjdk-21", "java -version 2>&1 | head -1"),
        new Kit("go", "Go", "The Go compiler", "go",
            "golang", "go version"),
        new Kit("rust", "Rust", "rustc and cargo", "rustc",
            "rust", "rustc --version"),
        new Kit("editors", "Editors", "Neovim, Vim and micro", "nvim",
            "neovim vim micro", ""),
    };

    private Kits() {}

    public static Kit byId(String id) {
        for (Kit kit : ALL) if (kit.id.equals(id)) return kit;
        return null;
    }

    /** Writes the install script for the given kits and runs it in a new terminal tab. */
    public static void install(Context context, List<Kit> kits) throws IOException {
        TerminalRunner.runInNewTab(context, buildScript(kits), "setup", TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    static String buildScript(List<Kit> kits) {
        StringBuilder packages = new StringBuilder();
        StringBuilder names = new StringBuilder();
        for (Kit kit : kits) {
            if (!kit.packages.isEmpty()) packages.append(' ').append(kit.packages);
            if (names.length() > 0) names.append(", ");
            names.append(kit.title);
        }

        StringBuilder s = new StringBuilder();
        s.append("# Written by Forge's setup screen.\n");
        s.append("export DEBIAN_FRONTEND=noninteractive\n");
        s.append("step() { printf '\\n\\033[1;38;5;208m== %s ==\\033[0m\\n' \"$1\"; }\n");
        s.append("fail=0\n");
        s.append("step 'Forge setup: ").append(names.toString().replace("'", "")).append("'\n");

        Kit storage = byId("storage");
        if (kits.contains(storage)) {
            s.append("step 'Phone storage'\n").append(storage.after).append("\n");
        }

        if (packages.length() > 0) {
            s.append("step 'Updating package lists and installed packages'\n");
            s.append("pkg upgrade -y -o Dpkg::Options::=--force-confnew || fail=1\n");
            s.append("step 'Installing:").append(packages).append("'\n");
            s.append("pkg install -y").append(packages).append(" || fail=1\n");
        }

        for (Kit kit : kits) {
            if (kit == storage || kit.after.isEmpty()) continue;
            s.append("step '").append(kit.title).append("'\n").append(kit.after).append("\n");
        }

        s.append("echo\n");
        s.append("if [ $fail = 0 ]; then printf '\\033[1;32mAll done.\\033[0m\\n'; ");
        s.append("else printf '\\033[1;31mSomething failed - scroll up to see why, then try again from Setup.\\033[0m\\n'; fi\n");
        s.append("read -r -p 'Press Enter to close this tab. ' _\n");
        s.append("exit $fail\n");
        return s.toString();
    }
}
