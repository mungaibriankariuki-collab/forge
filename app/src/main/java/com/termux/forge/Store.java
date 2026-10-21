package com.termux.forge;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Forge Store catalog: coding agents, languages, editors, servers and tools that install with
 * one tap. Tools with an official Android (Termux) build install natively; the rest install inside
 * Forge's built-in Ubuntu (proot-distro) and get a small $PREFIX/bin command that runs them there,
 * in the current folder, so they feel like any other command.
 *
 * Each item has a bash install script (run in a visible terminal tab), a remove script, a check
 * for whether it is installed, a command that proves it works (used by `forge-store test`), and
 * the command that opens it.
 */
public final class Store {

    public static final String AI = "AI agents";
    public static final String LOCAL_AI = "Local AI";
    public static final String LANG = "Languages";
    public static final String EDITORS = "Editors";
    public static final String WEB = "Web & servers";
    public static final String SHARE = "Share online";
    public static final String DEPLOY = "Git & deploy";
    public static final String ANDROID = "Android";
    public static final String FILES = "Files & sync";
    public static final String MEDIA = "Media & docs";
    public static final String PRODUCTIVITY = "Productivity";
    public static final String TERMINAL = "Terminal";
    public static final String LINUX = "Linux & desktop";
    public static final String FUN = "Learn & fun";
    public static final String STARTERS = "Starters";

    public static final String[] CATEGORIES = {
        AI, LOCAL_AI, STARTERS, LANG, EDITORS, WEB, SHARE, DEPLOY, ANDROID, FILES, MEDIA, PRODUCTIVITY, TERMINAL, LINUX, FUN
    };

    public static final class Item {
        public final String id, name, category, description;
        public String note = "";
        /** A command name in $PREFIX/bin, or a path (may use $PREFIX/$HOME) when it contains a slash. */
        public String check;
        String install, remove;
        /** Shell command that must exit 0 (without error words) for the item to count as working. */
        String verify;
        public String open;
        public boolean ubuntu;

        Item(String id, String name, String category, String description) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.description = description;
        }

        Item note(String value) { note = value; return this; }
        Item open(String value) { open = value; return this; }
        Item verify(String value) { verify = value; return this; }
        Item check(String value) { check = value; return this; }

        public boolean isInstalled() {
            String path = check.contains("/")
                ? check.replace("$PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH).replace("$HOME", TermuxConstants.TERMUX_HOME_DIR_PATH)
                : TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + check;
            return new File(path).exists();
        }
    }

    // ---- Builders ----

    /** A tool from Termux's own packages. {@code cmd} is the command it provides. */
    private static Item pkg(String id, String name, String category, String description, String packages, String cmd) {
        Item item = new Item(id, name, category, description);
        item.check = cmd;
        item.install = "forge_upgrade\npkg install -y " + packages + "\n";
        item.remove = "pkg uninstall -y " + packages.split(" ")[packages.split(" ").length - 1];
        item.verify = cmd + " --version";
        item.open = cmd;
        return item;
    }

    /** A Node.js command-line tool installed natively with npm. */
    private static Item npm(String id, String name, String category, String description, String npmPackage, String cmd) {
        Item item = new Item(id, name, category, description);
        item.check = cmd;
        item.install = "pkg install -y nodejs-lts\nnpm install -g " + npmPackage + "\n";
        item.remove = "npm uninstall -g " + npmPackage;
        item.verify = cmd + " --version";
        item.open = cmd;
        return item;
    }

    /** A Python tool installed natively with pip. */
    private static Item pip(String id, String name, String category, String description, String pipPackage, String cmd) {
        Item item = new Item(id, name, category, description);
        item.check = cmd;
        item.install = "pkg install -y python\npip install " + pipPackage + "\n";
        item.remove = "pip uninstall -y " + pipPackage;
        item.verify = cmd + " --version";
        item.open = cmd;
        return item;
    }

    /**
     * A tool installed inside Forge's built-in Ubuntu. {@code inside} runs as root in Ubuntu with
     * helpers available (need_node, gh_get, $ARCH, $DARCH) and must put {@code commands} on
     * Ubuntu's PATH; each gets a $PREFIX/bin wrapper.
     */
    private static Item ub(String id, String name, String category, String description, String inside, String... commands) {
        Item item = new Item(id, name, category, description);
        item.ubuntu = true;
        item.check = commands[0];
        StringBuilder wrap = new StringBuilder();
        StringBuilder unwrap = new StringBuilder("rm -f");
        for (String c : commands) {
            wrap.append(' ').append(c);
            unwrap.append(" \"$PREFIX/bin/").append(c).append('"');
        }
        item.install = "ub_setup\n" +
            "cat > \"$PREFIX/tmp/forge-ub-" + id + ".sh\" <<'FORGE_UB'\n" + UB_PRELUDE + inside + "FORGE_UB\n" +
            "proot-distro login ubuntu --shared-tmp -- bash -e /tmp/forge-ub-" + id + ".sh\n" +
            "ub_wrap" + wrap + "\n";
        item.remove = unwrap.toString();
        item.verify = commands[0] + " --version";
        item.open = commands[0];
        return item;
    }

    /** Opens a non-interactive tool by showing its help, then waiting. */
    private static String help(String cmd) {
        return cmd + " --help 2>&1 | head -60\nread -r -p 'Press Enter to close. ' _";
    }

    private static final String WAIT = "\nread -r -p 'Press Enter to close. ' _";

    /** A one-tap starter project in ~/projects/NAME, opened in Forge Code. */
    private static Item starter(String id, String name, String description, String folder, String setup, String deps) {
        Item item = new Item(id, name, STARTERS, description);
        item.check = "$HOME/projects/" + folder;
        String dir = "\"$HOME/projects/" + folder + "\"";
        item.install = deps + "mkdir -p " + dir + "\ncd " + dir + "\n" + setup;
        item.remove = "rm -rf " + dir;
        item.verify = "test -d " + dir;
        item.open = "cd " + dir + " && code .";
        return item;
    }

    /** Ubuntu helpers, prepended to every script that runs inside Ubuntu. */
    private static final String UB_PRELUDE =
        "export DEBIAN_FRONTEND=noninteractive HOME=/root\n" +
        "export PATH=/root/.local/bin:/root/.cargo/bin:/root/go/bin:/usr/local/go/bin:/root/.bun/bin:/root/.deno/bin:/root/.juliaup/bin:/root/.dotnet:$PATH\n" +
        "if [ ! -f /root/.forge-base ]; then apt-get update && apt-get install -y curl ca-certificates git gnupg unzip xz-utils " +
        "build-essential python3 python3-pip python3-venv pipx ripgrep && touch /root/.forge-base; else apt-get update -qq || true; fi\n" +
        "ARCH=$(uname -m); DARCH=$(dpkg --print-architecture)\n" +
        "need_node() { if ! command -v node >/dev/null || [ \"$(node -v | cut -c2- | cut -d. -f1)\" -lt 22 ]; then " +
        "curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y nodejs; fi; }\n" +
        // Latest GitHub release asset matching $2, saved as $3. Uses the release web pages, not the
        // API, which only allows 60 anonymous requests an hour.
        "gh_get() { t=$(curl -fsSLI -o /dev/null -w '%{url_effective}' \"https://github.com/$1/releases/latest\" | sed 's|.*/tag/||'); " +
        "u=$(curl -fsSL \"https://github.com/$1/releases/expanded_assets/$t\" | grep -o 'href=\"[^\"]*/releases/download/[^\"]*\"' " +
        "| cut -d'\"' -f2 | grep -E \"$2\" | head -1); [ -n \"$u\" ] || { echo \"No download for $1 matching $2\"; return 1; }; " +
        "curl -fL \"https://github.com$u\" -o \"$3\"; }\n" +
        "charm_repo() { [ -f /etc/apt/sources.list.d/charm.list ] || { mkdir -p /etc/apt/keyrings; curl -fsSL https://repo.charm.sh/apt/gpg.key " +
        "| gpg --dearmor -o /etc/apt/keyrings/charm.gpg; echo 'deb [signed-by=/etc/apt/keyrings/charm.gpg] https://repo.charm.sh/apt/ * *' " +
        "> /etc/apt/sources.list.d/charm.list; apt-get update; }; }\n";

    // ---- Catalog ----

    public static final Item[] ITEMS = build();

    private static Item[] build() {
        List<Item> l = new ArrayList<>();

        // AI agents
        l.add(ub("claude-code", "Claude Code", AI, "Anthropic's coding agent in the terminal: edits files, runs commands, explains code.",
            "curl -fsSL https://claude.ai/install.sh | bash\ncp -L /root/.local/bin/claude /usr/local/bin/claude\n", "claude")
            .note("Runs in the built-in Ubuntu (~300 MB the first time). Needs a Claude Pro/Max plan or an Anthropic API key."));
        l.add(pkg("codex", "OpenAI Codex CLI", AI, "OpenAI's coding agent for the terminal (official Termux build).", "codex", "codex")
            .note("Needs a ChatGPT plan or an OpenAI API key. Sign in on first start."));
        l.add(npm("gemini-cli", "Gemini CLI", AI, "Google's open-source coding agent. Free tier with a Google account.", "@google/gemini-cli", "gemini")
            .note("Sign in with Google, or use your Gemini API key (export GEMINI_API_KEY=...)."));
        l.add(npm("qwen-code", "Qwen Code", AI, "Alibaba's open-source coding agent (based on Gemini CLI).", "@qwen-code/qwen-code", "qwen")
            .note("Free sign-in with a Qwen account, or any OpenAI-compatible API key."));
        l.add(ub("opencode", "OpenCode", AI, "Open-source terminal coding agent that works with many AI providers.",
            "curl -fsSL https://opencode.ai/install | bash\ncp -L /root/.opencode/bin/opencode /usr/local/bin/opencode\n", "opencode")
            .note("Runs in the built-in Ubuntu. Bring your own API key (Anthropic, OpenAI, Gemini…)."));
        l.add(ub("copilot-cli", "GitHub Copilot CLI", AI, "GitHub's coding agent in the terminal.",
            "need_node\nnpm install -g @github/copilot\n", "copilot").note("Runs in the built-in Ubuntu. Needs a GitHub Copilot subscription."));
        l.add(pkg("crush", "Crush", AI, "Charm's glamorous terminal coding agent.", "tur-repo crush", "crush")
            .note("Bring your own API key (Anthropic, OpenAI, Gemini, OpenRouter…)."));
        l.add(ub("goose", "Goose", AI, "Block's open-source AI agent; works with any model and many tools.",
            "curl -fsSL https://github.com/block/goose/releases/download/stable/download_cli.sh | CONFIGURE=false bash\n", "goose")
            .note("Runs in the built-in Ubuntu. Run `goose configure` to pick a model and key."));
        l.add(ub("cline", "Cline CLI", AI, "The popular Cline coding agent, in the terminal.", "need_node\nnpm install -g cline\n", "cline")
            .note("Runs in the built-in Ubuntu. Bring your own API key."));
        l.add(ub("kilo", "Kilo Code CLI", AI, "Kilo Code's open-source coding agent for the terminal.", "need_node\nnpm install -g @kilocode/cli\n", "kilocode")
            .note("Runs in the built-in Ubuntu."));
        l.add(ub("amp", "Amp", AI, "Sourcegraph's coding agent, strong on big codebases.", "need_node\nnpm install -g @sourcegraph/amp\n", "amp")
            .note("Runs in the built-in Ubuntu. Needs an Amp account."));
        l.add(ub("aider", "Aider", AI, "AI pair programming in the terminal; works great with git.",
            "pipx install aider-chat\n", "aider").note("Runs in the built-in Ubuntu. Bring your own API key."));
        l.add(ub("plandex", "Plandex", AI, "AI agent for large, multi-file coding tasks.",
            "curl -sL https://plandex.ai/install.sh | bash\n", "plandex").note("Runs in the built-in Ubuntu.").verify("plandex version"));
        l.add(ub("openhands", "OpenHands CLI", AI, "Open-source autonomous software-engineer agent.",
            "curl -LsSf https://astral.sh/uv/install.sh | sh\nuv tool install openhands --python 3.12\n", "openhands")
            .note("Runs in the built-in Ubuntu. Big install. Bring your own API key."));
        l.add(ub("open-interpreter", "Open Interpreter", AI, "Lets an AI run code on your phone to get tasks done.",
            "pipx install open-interpreter\n", "interpreter").note("Runs in the built-in Ubuntu. Bring your own API key."));
        l.add(ub("mods", "Mods", AI, "Pipe anything into AI: cat error.log | mods \"why did this fail\".",
            "charm_repo\napt-get install -y mods\n", "mods").note("Runs in the built-in Ubuntu."));
        l.add(ub("shell-gpt", "ShellGPT", AI, "Ask AI for a shell command or answer: sgpt \"find big files\".",
            "pipx install shell-gpt\n", "sgpt").note("Runs in the built-in Ubuntu. Uses an OpenAI-compatible key."));
        l.add(ub("llm", "llm", AI, "One command for every AI model, with plugins (Simon Willison).", "pipx install llm\n", "llm")
            .note("Runs in the built-in Ubuntu."));
        l.add(ub("fabric", "Fabric", AI, "Hundreds of ready-made AI prompts: summarize, explain code, extract wisdom…",
            "gh_get danielmiessler/fabric \"fabric-linux-$( [ $DARCH = arm64 ] && echo arm64 || echo amd64 )$\" /usr/local/bin/fabric\nchmod 755 /usr/local/bin/fabric\n",
            "fabric").note("Runs in the built-in Ubuntu. Run `fabric --setup` first."));
        l.add(pkg("aichat", "AIChat", AI, "All-in-one chat, roles and RAG for every AI provider.", "aichat", "aichat"));

        // Local AI
        l.add(pkg("ollama", "Ollama", LOCAL_AI, "Run AI models on the phone, offline (small models only).", "ollama", "ollama")
            .note("Open starts the server and a tiny model chat (qwen2.5 0.5B, ~400 MB download).")
            .open("ollama serve > \"$TMPDIR/ollama.log\" 2>&1 &\nsleep 3\nollama run qwen2.5:0.5b"));
        l.add(pkg("llama-cpp", "llama.cpp", LOCAL_AI, "The fastest way to run small GGUF models on a phone.", "llama-cpp", "llama-cli")
            .note("Download a .gguf model, then: llama-cli -m model.gguf").open(help("llama-cli")));
        l.add(new Item("whisper-cpp", "Whisper.cpp", LOCAL_AI, "Offline speech-to-text (built from source, a few minutes).")
            .check("whisper-cli").verify("whisper-cli --help").open(help("whisper-cli"))
            .note("Includes the small English model (~75 MB)."));
        last(l).install = "pkg install -y git cmake clang make\n" +
            "rm -rf \"$TMPDIR/whisper.cpp\" && git clone --depth 1 https://github.com/ggml-org/whisper.cpp \"$TMPDIR/whisper.cpp\"\n" +
            "cd \"$TMPDIR/whisper.cpp\" && cmake -B build && cmake --build build -j4 --config Release\n" +
            "install -m755 build/bin/whisper-cli \"$PREFIX/bin/whisper-cli\"\n" +
            "mkdir -p \"$HOME/models\" && sh ./models/download-ggml-model.sh base.en \"$HOME/models\"\n";
        last(l).remove = "rm -f \"$PREFIX/bin/whisper-cli\"";
        l.add(ub("piper", "Piper voices", LOCAL_AI, "Offline text-to-speech with natural voices.", "pipx install piper-tts\n", "piper")
            .note("Runs in the built-in Ubuntu.").verify("piper --help"));
        l.add(new Item("sd-cpp", "Stable Diffusion (sd.cpp)", LOCAL_AI, "Generate images offline (slow on a phone, but works).")
            .check("sd").verify("sd --help").open(help("sd")).note("Built from source. Download a small model such as SD 1.5 (GGUF)."));
        last(l).install = "pkg install -y git cmake clang make\n" +
            "rm -rf \"$TMPDIR/sd.cpp\" && git clone --depth 1 --recursive https://github.com/leejet/stable-diffusion.cpp \"$TMPDIR/sd.cpp\"\n" +
            "cd \"$TMPDIR/sd.cpp\" && cmake -B build && cmake --build build -j4 --config Release\n" +
            "install -m755 build/bin/sd \"$PREFIX/bin/sd\"\n";
        last(l).remove = "rm -f \"$PREFIX/bin/sd\"";

        // Starters
        l.add(starter("starter-flask", "Python web app (Flask)", "A ready Flask site. Run it with ▶ in Forge Code, then open http://localhost:5000.",
            "flask-app",
            "cat > app.py <<'EOF'\nfrom flask import Flask\n\napp = Flask(__name__)\n\n\n@app.route(\"/\")\ndef home():\n    return \"<h1>Hello from Forge!</h1>\"\n\n\nif __name__ == \"__main__\":\n    app.run(host=\"127.0.0.1\", port=5000, debug=True)\nEOF\n",
            "pkg install -y python\npip install flask\n"));
        l.add(starter("starter-fastapi", "Python API (FastAPI)", "A ready FastAPI app with automatic docs at http://localhost:8000/docs.",
            "fastapi-app",
            "cat > main.py <<'EOF'\nfrom fastapi import FastAPI\nimport uvicorn\n\napp = FastAPI()\n\n\n@app.get(\"/\")\ndef home():\n    return {\"message\": \"Hello from Forge!\"}\n\n\nif __name__ == \"__main__\":\n    uvicorn.run(app, host=\"127.0.0.1\", port=8000)\nEOF\n",
            "pkg install -y python rust\npip install fastapi uvicorn\n"));
        l.add(starter("starter-express", "Node.js site (Express)", "A ready Express server on http://localhost:3000.", "express-app",
            "cat > index.js <<'EOF'\nconst express = require(\"express\");\nconst app = express();\n\napp.get(\"/\", (req, res) => res.send(\"<h1>Hello from Forge!</h1>\"));\n\napp.listen(3000, () => console.log(\"Running on http://localhost:3000\"));\nEOF\nnpm init -y >/dev/null\nnpm install express\nnpm pkg set scripts.start=\"node index.js\"\n",
            "pkg install -y nodejs-lts\n"));
        l.add(starter("starter-vite", "React website (Vite)", "A React + Vite site with live reload. Set ▶'s run command to: npm run dev",
            "react-app",
            "cd .. && rm -rf react-app && npm create vite@latest react-app -- --template react >/dev/null && cd react-app && npm install\n",
            "pkg install -y nodejs-lts\n"));
        l.add(starter("starter-telegram", "Telegram bot", "A Python Telegram bot that replies to messages. Put your bot token in bot.py.",
            "telegram-bot",
            "cat > bot.py <<'EOF'\n# Get a token from @BotFather in Telegram, then paste it below.\nfrom telegram import Update\nfrom telegram.ext import Application, MessageHandler, filters, ContextTypes\n\nTOKEN = \"PASTE-YOUR-BOT-TOKEN-HERE\"\n\n\nasync def echo(update: Update, context: ContextTypes.DEFAULT_TYPE):\n    await update.message.reply_text(\"You said: \" + update.message.text)\n\n\napp = Application.builder().token(TOKEN).build()\napp.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, echo))\napp.run_polling()\nEOF\n",
            "pkg install -y python\npip install python-telegram-bot\n"));
        l.add(starter("starter-discord", "Discord bot", "A Python Discord bot that answers !hello. Put your bot token in bot.py.", "discord-bot",
            "cat > bot.py <<'EOF'\n# Create a bot at https://discord.com/developers, enable Message Content intent, paste the token below.\nimport discord\n\nTOKEN = \"PASTE-YOUR-BOT-TOKEN-HERE\"\nintents = discord.Intents.default()\nintents.message_content = True\nclient = discord.Client(intents=intents)\n\n\n@client.event\nasync def on_message(message):\n    if message.author != client.user and message.content == \"!hello\":\n        await message.channel.send(\"Hello from Forge!\")\n\n\nclient.run(TOKEN)\nEOF\n",
            "pkg install -y python\npip install discord.py\n"));
        l.add(starter("starter-whatsapp", "WhatsApp bot (Baileys)", "A Node.js WhatsApp bot: scan the QR code with WhatsApp, then it answers \"ping\".",
            "whatsapp-bot",
            "cat > index.js <<'EOF'\n// Run, then scan the QR code in WhatsApp > Linked devices. Use a spare number: unofficial bots can get numbers banned.\nconst { default: makeWASocket, useMultiFileAuthState } = require(\"@whiskeysockets/baileys\");\nconst qrcode = require(\"qrcode-terminal\");\n\nasync function start() {\n  const { state, saveCreds } = await useMultiFileAuthState(\"auth\");\n  const sock = makeWASocket({ auth: state });\n  sock.ev.on(\"creds.update\", saveCreds);\n  sock.ev.on(\"connection.update\", ({ qr, connection }) => {\n    if (qr) qrcode.generate(qr, { small: true });\n    if (connection === \"close\") start();\n  });\n  sock.ev.on(\"messages.upsert\", async ({ messages }) => {\n    const m = messages[0];\n    const text = m.message?.conversation;\n    if (!m.key.fromMe && text === \"ping\") await sock.sendMessage(m.key.remoteJid, { text: \"pong\" });\n  });\n}\n\nstart();\nEOF\nnpm init -y >/dev/null\nnpm install @whiskeysockets/baileys qrcode-terminal\nnpm pkg set scripts.start=\"node index.js\"\n",
            "pkg install -y nodejs-lts\n")
            .note("Unofficial WhatsApp library: use a spare number, WhatsApp may ban numbers that run bots."));
        l.add(starter("starter-site", "Static website", "HTML/CSS/JS site with live preview; deploy later with the Vercel or Netlify item.",
            "my-site",
            "cat > index.html <<'EOF'\n<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>My site</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>Hello from Forge!</h1>\n  <script src=\"script.js\"></script>\n</body>\n</html>\nEOF\nprintf 'body { font-family: sans-serif; margin: 2rem; }\\n' > style.css\nprintf 'console.log(\"Hello\");\\n' > script.js\n",
            ""));
        l.add(starter("starter-notebook", "Python data notebook", "Jupyter-style data work: pandas + matplotlib, with an example script.",
            "data-notebook",
            "cat > analysis.py <<'EOF'\nimport pandas as pd\nimport matplotlib\nmatplotlib.use(\"Agg\")\nimport matplotlib.pyplot as plt\n\ndf = pd.DataFrame({\"day\": range(1, 8), \"sales\": [5, 8, 6, 10, 12, 9, 14]})\nprint(df.describe())\ndf.plot(x=\"day\", y=\"sales\", title=\"Sales\")\nplt.savefig(\"chart.png\")\nprint(\"Saved chart.png\")\nEOF\n",
            "pkg install -y python python-pandas matplotlib\n"));

        // Languages
        l.add(pkg("deno", "Deno", LANG, "Modern JavaScript/TypeScript runtime.", "deno", "deno"));
        l.add(pkg("bun", "Bun", LANG, "Very fast JavaScript runtime and package manager.", "bun", "bun"));
        l.add(npm("typescript", "TypeScript + tsx", LANG, "TypeScript compiler and the tsx runner.", "typescript tsx", "tsc"));
        l.add(npm("pnpm", "pnpm", LANG, "Fast, disk-saving Node.js package manager.", "pnpm", "pnpm"));
        l.add(pkg("yarn", "Yarn", LANG, "Node.js package manager.", "yarn", "yarn"));
        l.add(pkg("uv", "uv", LANG, "Extremely fast Python package and project manager.", "uv", "uv"));
        l.add(pip("pipx", "pipx", LANG, "Install Python apps in their own environments.", "pipx", "pipx"));
        l.add(ub("poetry", "Poetry", LANG, "Python dependency and packaging tool.", "pipx install poetry\n", "poetry").note("Runs in the built-in Ubuntu."));
        l.add(pip("ipython", "IPython", LANG, "A much nicer interactive Python shell.", "ipython", "ipython"));
        l.add(ub("jupyter", "Jupyter Lab", LANG, "Notebooks in your browser at http://localhost:8888.",
            "pipx install jupyterlab --include-deps\n", "jupyter-lab", "jupyter")
            .note("Runs in the built-in Ubuntu. Open starts it and your browser.")
            .open("(sleep 8; termux-open-url http://localhost:8888/lab) &\njupyter-lab --no-browser --ip 127.0.0.1 --port 8888 --IdentityProvider.token= --allow-root"));
        l.add(pkg("php", "PHP + Composer", LANG, "PHP and its package manager.", "php composer", "php"));
        l.add(pkg("ruby", "Ruby", LANG, "Ruby with gem and bundler.", "ruby", "ruby"));
        l.add(pkg("perl", "Perl", LANG, "The Perl language.", "perl", "perl"));
        l.add(pkg("lua", "Lua + LuaRocks", LANG, "Lua 5.4 and its package manager.", "lua54 luarocks", "lua5.4").verify("lua5.4 -v"));
        l.add(pkg("kotlin", "Kotlin", LANG, "Kotlin compiler (needs Java).", "openjdk-21 kotlin", "kotlinc").verify("kotlinc -version"));
        l.add(pkg("gradle", "Gradle", LANG, "Java/Kotlin build tool.", "openjdk-21 gradle", "gradle"));
        l.add(pkg("maven", "Maven", LANG, "Java build tool.", "openjdk-21 maven", "mvn"));
        l.add(ub("scala", "Scala", LANG, "Scala via Coursier (scala, scala-cli).",
            "apt-get install -y openjdk-21-jdk-headless\ncurl -fL \"https://github.com/coursier/coursier/releases/latest/download/cs-$( [ $DARCH = arm64 ] && echo aarch64 || echo x86_64 )-pc-linux.gz\" | gzip -d > /usr/local/bin/cs\nchmod 755 /usr/local/bin/cs\ncs setup --yes --install-dir /usr/local/bin\n",
            "scala", "scala-cli").note("Runs in the built-in Ubuntu."));
        l.add(pkg("zig", "Zig", LANG, "Zig compiler.", "zig", "zig").verify("zig version"));
        l.add(pkg("nim", "Nim", LANG, "Nim compiler.", "nim", "nim"));
        l.add(pkg("crystal", "Crystal", LANG, "Crystal compiler.", "crystal", "crystal"));
        l.add(pkg("elixir", "Elixir + Erlang", LANG, "Elixir on the Erlang VM.", "erlang elixir", "elixir"));
        l.add(pkg("haskell", "Haskell (GHC)", LANG, "The Glasgow Haskell Compiler.", "ghc", "ghc"));
        l.add(ub("ocaml", "OCaml", LANG, "OCaml compiler.", "apt-get install -y ocaml\n", "ocaml", "ocamlc", "ocamlopt")
            .note("Runs in the built-in Ubuntu.").verify("ocaml -version"));
        l.add(ub("r-lang", "R", LANG, "R for statistics and data.", "apt-get install -y r-base\n", "R", "Rscript")
            .note("Runs in the built-in Ubuntu.").verify("Rscript --version"));
        l.add(ub("julia", "Julia", LANG, "Julia for fast numeric computing.", "curl -fsSL https://install.julialang.org | sh -s -- --yes\n", "julia")
            .note("Runs in the built-in Ubuntu. Big download."));
        l.add(ub("dotnet", ".NET / C#", LANG, "The .NET SDK for C# and F#.", "apt-get install -y dotnet-sdk-8.0\n", "dotnet")
            .note("Runs in the built-in Ubuntu."));
        l.add(pkg("dart", "Dart", LANG, "The Dart language.", "dart", "dart"));
        l.add(ub("flutter", "Flutter SDK", LANG, "Flutter for building apps (web/Linux builds; very big).",
            "apt-get install -y clang cmake ninja-build pkg-config libgtk-3-dev\n[ -d /opt/flutter ] || git clone --depth 1 -b stable https://github.com/flutter/flutter.git /opt/flutter\nln -sf /opt/flutter/bin/flutter /usr/local/bin/flutter\nln -sf /opt/flutter/bin/dart /usr/local/bin/flutter-dart\nflutter --version\n",
            "flutter").note("Runs in the built-in Ubuntu. About 2 GB. Android app builds still need a PC or CI."));
        l.add(pkg("wasmtime", "wasmtime", LANG, "Run WebAssembly programs.", "wasmtime", "wasmtime"));
        l.add(pkg("wasmer", "wasmer", LANG, "WebAssembly runtime and package manager.", "wasmer", "wasmer"));

        // Editors
        l.add(neovimDistro("lazyvim", "Neovim + LazyVim", "A full IDE setup for Neovim, ready to use.", "https://github.com/LazyVim/starter"));
        l.add(neovimDistro("nvchad", "Neovim + NvChad", "A fast, pretty Neovim setup.", "https://github.com/NvChad/starter"));
        l.add(neovimDistro("astronvim", "Neovim + AstroNvim", "An all-in-one Neovim IDE setup.", "https://github.com/AstroNvim/template"));
        l.add(pkg("helix", "Helix", EDITORS, "Modern modal editor with no setup needed.", "helix", "hx"));
        l.add(pkg("emacs", "Emacs", EDITORS, "The extensible editor.", "emacs", "emacs"));
        l.add(new Item("doom-emacs", "Doom Emacs", EDITORS, "Emacs with a fast, modern, Vim-style setup.")
            .check("$HOME/.config/emacs/bin/doom").verify("$HOME/.config/emacs/bin/doom version").open("emacs")
            .note("Takes a while the first time. Backs up an existing ~/.config/emacs."));
        last(l).install = "pkg install -y emacs git ripgrep fd\n[ -d \"$HOME/.config/emacs\" ] && mv \"$HOME/.config/emacs\" \"$HOME/.config/emacs.bak.$(date +%s)\"\n" +
            "git clone --depth 1 https://github.com/doomemacs/doomemacs \"$HOME/.config/emacs\"\n\"$HOME/.config/emacs/bin/doom\" install --force\n";
        last(l).remove = "rm -rf \"$HOME/.config/emacs\" \"$HOME/.config/doom\"";
        l.add(pkg("kakoune", "Kakoune", EDITORS, "Selection-first modal editor.", "kakoune", "kak").verify("kak -version"));
        l.add(pkg("micro", "micro", EDITORS, "Easy terminal editor with mouse support and normal shortcuts.", "micro", "micro"));
        l.add(pkg("code-server", "VS Code (code-server)", EDITORS, "Full VS Code in your phone's browser at http://localhost:8080.",
            "tur-repo code-server", "code-server")
            .note("Open starts the server and your browser. Stop it with Ctrl+C in its tab.")
            .open("(sleep 4; termux-open-url http://localhost:8080) &\ncode-server --auth none --bind-addr 127.0.0.1:8080"));

        // Web & servers
        l.add(pkg("nginx", "nginx", WEB, "Fast web server on http://localhost:8080.", "nginx", "nginx").verify("nginx -v")
            .open("nginx\necho \"nginx is running on http://localhost:8080 (files in $PREFIX/share/nginx/html)\"\ntermux-open-url http://localhost:8080\nread -r -p 'Press Enter to stop nginx. ' _\nnginx -s stop"));
        l.add(pkg("apache", "Apache", WEB, "The classic web server on http://localhost:8080.", "apache2", "httpd").verify("httpd -v")
            .open("apachectl start\necho 'Apache is running on http://localhost:8080'\ntermux-open-url http://localhost:8080\nread -r -p 'Press Enter to stop Apache. ' _\napachectl stop"));
        l.add(pkg("caddy", "Caddy", WEB, "Simple web server; serves the current folder in one command.", "caddy", "caddy").verify("caddy version")
            .open("echo 'Serving ~ on http://localhost:8080'\n(sleep 2; termux-open-url http://localhost:8080) &\ncd ~ && caddy file-server --listen :8080 --browse"));
        l.add(pkg("postgresql", "PostgreSQL", WEB, "The powerful SQL database.", "postgresql", "psql")
            .open("[ -d \"$PREFIX/var/lib/postgresql\" ] || initdb \"$PREFIX/var/lib/postgresql\"\npg_ctl -D \"$PREFIX/var/lib/postgresql\" -l \"$TMPDIR/pg.log\" start\nsleep 2\npsql postgres\npg_ctl -D \"$PREFIX/var/lib/postgresql\" stop"));
        l.add(pkg("mariadb", "MariaDB (MySQL)", WEB, "MySQL-compatible database.", "mariadb", "mariadb")
            .open("mariadbd-safe > \"$TMPDIR/mariadb.log\" 2>&1 &\nsleep 6\nmariadb -u \"$(whoami)\"\nmariadb-admin -u \"$(whoami)\" shutdown"));
        l.add(pkg("redis", "Redis", WEB, "In-memory database and cache.", "redis", "redis-server")
            .open("redis-server --daemonize yes\nsleep 1\nredis-cli\nredis-cli shutdown"));
        l.add(pkg("sqlite", "SQLite", WEB, "Small file-based SQL database.", "sqlite", "sqlite3"));
        l.add(pkg("mongodb", "MongoDB", WEB, "Document database.", "tur-repo mongodb", "mongod")
            .open("mkdir -p \"$HOME/.mongodb\"\nmongod --dbpath \"$HOME/.mongodb\" --fork --logpath \"$TMPDIR/mongod.log\"\necho 'MongoDB is running on localhost:27017'" + WAIT));
        l.add(new Item("adminer", "Adminer", WEB, "Database manager in your browser (MySQL, PostgreSQL, SQLite).")
            .check("$HOME/adminer/index.php").verify("test -f $HOME/adminer/index.php")
            .open("(sleep 2; termux-open-url http://localhost:8081) &\ncd ~/adminer && php -S 127.0.0.1:8081"));
        last(l).install = "pkg install -y php\nmkdir -p \"$HOME/adminer\"\ncurl -fL https://www.adminer.org/latest.php -o \"$HOME/adminer/index.php\"\n";
        last(l).remove = "rm -rf \"$HOME/adminer\"";
        l.add(new Item("wordpress", "WordPress", WEB, "Your own WordPress site on http://localhost:8082 (PHP + MariaDB).")
            .check("$HOME/wordpress/wp-config-sample.php").verify("test -f $HOME/wordpress/index.php")
            .note("First open: the setup page asks for database \"wordpress\", user = your Termux user name, empty password, host 127.0.0.1.")
            .open("mariadbd-safe > \"$TMPDIR/mariadb.log\" 2>&1 &\nsleep 6\nmariadb -u \"$(whoami)\" -e 'CREATE DATABASE IF NOT EXISTS wordpress'\n(sleep 2; termux-open-url http://localhost:8082) &\ncd ~/wordpress && php -S 127.0.0.1:8082"));
        last(l).install = "pkg install -y php php-mysql mariadb\n[ -d \"$HOME/wordpress\" ] || { curl -fL https://wordpress.org/latest.tar.gz | tar -xz -C \"$HOME\"; }\n";
        last(l).remove = "rm -rf \"$HOME/wordpress\"";
        l.add(ub("minio", "MinIO", WEB, "Your own S3-compatible storage server.",
            "curl -fL \"https://dl.min.io/server/minio/release/linux-$DARCH/minio\" -o /usr/local/bin/minio\nchmod 755 /usr/local/bin/minio\n", "minio")
            .note("Runs in the built-in Ubuntu. Console on http://localhost:9001 (minioadmin / minioadmin).")
            .open("mkdir -p ~/minio-data\n(sleep 5; termux-open-url http://localhost:9001) &\nminio server ~/minio-data --console-address :9001"));
        l.add(ub("n8n", "n8n", WEB, "Workflow automation (like Zapier) on http://localhost:5678.", "need_node\nnpm install -g n8n\n", "n8n")
            .note("Runs in the built-in Ubuntu. Big install.")
            .open("(sleep 15; termux-open-url http://localhost:5678) &\nn8n start"));
        l.add(ub("uptime-kuma", "Uptime Kuma", WEB, "Monitor your websites with a nice dashboard on http://localhost:3001.",
            "need_node\n[ -d /opt/uptime-kuma ] || git clone --depth 1 https://github.com/louislam/uptime-kuma.git /opt/uptime-kuma\ncd /opt/uptime-kuma && npm run setup\nprintf '#!/bin/sh\\ncd /opt/uptime-kuma && exec node server/server.js \"$@\"\\n' > /usr/local/bin/uptime-kuma\nchmod 755 /usr/local/bin/uptime-kuma\n",
            "uptime-kuma").verify("test -f $PREFIX/bin/uptime-kuma").note("Runs in the built-in Ubuntu.")
            .open("(sleep 10; termux-open-url http://localhost:3001) &\nuptime-kuma"));

        // Share online
        l.add(pkg("cloudflared", "Cloudflare Tunnel", SHARE, "Share a server on your phone online, free, no account needed for quick tunnels.",
            "cloudflared", "cloudflared").open("read -r -p 'Local port to share (e.g. 8080): ' p\ncloudflared tunnel --url \"http://localhost:$p\""));
        l.add(ub("ngrok", "ngrok", SHARE, "Share a local server online with a public URL.",
            "curl -fL \"https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-$DARCH.tgz\" | tar -xz -C /usr/local/bin\n", "ngrok")
            .note("Runs in the built-in Ubuntu. Needs a free ngrok account: ngrok config add-authtoken TOKEN").verify("ngrok version"));
        l.add(pkg("bore", "bore", SHARE, "Tiny tunnel to share a local port: bore local 8080 --to bore.pub.", "bore", "bore"));
        l.add(ub("tailscale", "Tailscale", SHARE, "Private network between your devices (experimental: userspace mode).",
            "curl -fsSL https://tailscale.com/install.sh | sh\n", "tailscale", "tailscaled")
            .note("Runs in the built-in Ubuntu without a VPN device; only Tailscale's own SSH/HTTP features work.")
            .open("tailscaled --tun=userspace-networking --state=$HOME/.tailscaled.state > $TMPDIR/tailscaled.log 2>&1 &\nsleep 3\ntailscale up" + WAIT));

        // Git & deploy
        l.add(pkg("lazygit", "lazygit", DEPLOY, "A friendly full-screen git interface.", "git lazygit", "lazygit"));
        l.add(pkg("gitui", "gitui", DEPLOY, "Fast terminal UI for git.", "git gitui", "gitui"));
        l.add(pkg("tig", "tig", DEPLOY, "Browse git history in the terminal.", "git tig", "tig"));
        l.add(pkg("delta", "delta", DEPLOY, "Beautiful git diffs (set as git's pager).", "git git-delta", "delta")
            .open("git config --global core.pager delta\ngit config --global interactive.diffFilter 'delta --color-only'\necho 'delta is now your git pager.'" + WAIT));
        l.add(ub("glab", "GitLab CLI", DEPLOY, "Work with GitLab from the terminal.",
            "gh_get gitlab-org/cli \"glab_.*_linux_$DARCH.deb$\" /tmp/glab.deb || gh_get profclems/glab \"linux_$DARCH.deb$\" /tmp/glab.deb\napt-get install -y /tmp/glab.deb\n", "glab")
            .note("Runs in the built-in Ubuntu."));
        l.add(npm("vercel", "Vercel CLI", DEPLOY, "Deploy websites to Vercel.", "vercel", "vercel"));
        l.add(npm("netlify", "Netlify CLI", DEPLOY, "Deploy websites to Netlify.", "netlify-cli", "netlify"));
        l.add(npm("firebase", "Firebase CLI", DEPLOY, "Deploy to Firebase Hosting, Functions and more.", "firebase-tools", "firebase"));
        l.add(ub("supabase", "Supabase CLI", DEPLOY, "Manage Supabase projects and databases.",
            "gh_get supabase/cli \"supabase_.*_linux_$DARCH.deb$\" /tmp/supabase.deb\napt-get install -y /tmp/supabase.deb\n", "supabase")
            .note("Runs in the built-in Ubuntu."));
        l.add(ub("railway", "Railway CLI", DEPLOY, "Deploy apps to Railway.", "need_node\nnpm install -g @railway/cli\n", "railway")
            .note("Runs in the built-in Ubuntu."));
        l.add(ub("wrangler", "Cloudflare Wrangler", DEPLOY, "Deploy Cloudflare Workers and Pages.", "need_node\nnpm install -g wrangler\n", "wrangler")
            .note("Runs in the built-in Ubuntu."));
        l.add(ub("flyctl", "Fly.io CLI", DEPLOY, "Deploy apps to Fly.io.",
            "curl -L https://fly.io/install.sh | sh\nln -sf /root/.fly/bin/flyctl /usr/local/bin/flyctl\nln -sf /root/.fly/bin/flyctl /usr/local/bin/fly\n", "flyctl", "fly")
            .note("Runs in the built-in Ubuntu.").verify("flyctl version"));
        l.add(npm("heroku", "Heroku CLI", DEPLOY, "Deploy apps to Heroku.", "heroku", "heroku"));
        l.add(ub("aws", "AWS CLI", DEPLOY, "Amazon Web Services command line.",
            "curl -fL \"https://awscli.amazonaws.com/awscli-exe-linux-$ARCH.zip\" -o /tmp/aws.zip\ncd /tmp && rm -rf aws && unzip -q aws.zip && ./aws/install --update\n", "aws")
            .note("Runs in the built-in Ubuntu."));
        l.add(ub("gcloud", "Google Cloud CLI", DEPLOY, "Google Cloud command line.",
            "[ -f /etc/apt/sources.list.d/google-cloud-sdk.list ] || { curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg; echo 'deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main' > /etc/apt/sources.list.d/google-cloud-sdk.list; apt-get update; }\napt-get install -y google-cloud-cli\n",
            "gcloud").note("Runs in the built-in Ubuntu."));
        l.add(ub("azure", "Azure CLI", DEPLOY, "Microsoft Azure command line.", "curl -sL https://aka.ms/InstallAzureCLIDeb | bash\n", "az")
            .note("Runs in the built-in Ubuntu."));
        l.add(ub("terraform", "Terraform", DEPLOY, "Infrastructure as code.",
            "[ -f /etc/apt/sources.list.d/hashicorp.list ] || { curl -fsSL https://apt.releases.hashicorp.com/gpg | gpg --dearmor -o /usr/share/keyrings/hashicorp.gpg; echo \"deb [signed-by=/usr/share/keyrings/hashicorp.gpg] https://apt.releases.hashicorp.com $(. /etc/os-release && echo $VERSION_CODENAME) main\" > /etc/apt/sources.list.d/hashicorp.list; apt-get update; }\napt-get install -y terraform\n",
            "terraform").note("Runs in the built-in Ubuntu."));
        l.add(pkg("kubectl", "kubectl", DEPLOY, "Control Kubernetes clusters.", "kubectl", "kubectl").verify("kubectl version --client"));
        l.add(pkg("k9s", "k9s", DEPLOY, "Terminal dashboard for Kubernetes.", "k9s", "k9s").verify("k9s version"));
        l.add(pkg("helm", "Helm", DEPLOY, "Kubernetes package manager.", "helm", "helm").verify("helm version"));

        // Android
        l.add(pkg("adb", "ADB + fastboot", ANDROID, "Control other Android phones, or this one via wireless debugging.", "android-tools", "adb")
            .note("For this phone: Settings > Developer options > Wireless debugging > Pair, then adb pair / adb connect.")
            .open(help("adb")));
        l.add(pkg("apktool", "apktool", ANDROID, "Unpack and rebuild Android apps (your own or open-source ones).", "apktool", "apktool").verify("apktool --version"));
        l.add(pkg("jadx", "jadx", ANDROID, "Turn APKs back into readable Java source.", "jadx", "jadx"));

        // Files & sync
        l.add(pkg("rclone", "rclone", FILES, "Sync with Google Drive, OneDrive, Dropbox and 70+ clouds.", "rclone", "rclone").open("rclone config"));
        l.add(pkg("syncthing", "Syncthing", FILES, "Sync folders between your phone and PC, privately.", "syncthing", "syncthing")
            .open("(sleep 4; termux-open-url http://127.0.0.1:8384) &\nsyncthing serve --no-browser --gui-address=127.0.0.1:8384"));
        l.add(pkg("restic", "restic", FILES, "Encrypted backups of your projects.", "restic", "restic").verify("restic version"));
        l.add(pkg("7zip", "7-Zip", FILES, "Pack and unpack .7z, .zip, .tar and more.", "7zip", "7z").verify("7z i").open(help("7z")));
        l.add(pkg("unrar", "unrar", FILES, "Extract .rar files.", "unrar", "unrar").verify("unrar").open(help("unrar")));
        l.add(pkg("zstd", "zstd", FILES, "Very fast compression.", "zstd", "zstd"));
        l.add(pkg("ncdu", "ncdu", FILES, "See what is using your storage.", "ncdu", "ncdu").open("ncdu ~"));
        l.add(pkg("fd", "fd", FILES, "Fast, friendly file finder.", "fd", "fd"));
        l.add(pkg("ripgrep", "ripgrep", FILES, "Search inside files, very fast.", "ripgrep", "rg"));
        l.add(pkg("fzf", "fzf", FILES, "Fuzzy finder for files, history and anything else.", "fzf", "fzf"));
        l.add(pkg("yazi", "yazi", FILES, "Fast terminal file manager with previews.", "yazi", "yazi"));
        l.add(pkg("ranger", "ranger", FILES, "Vim-style terminal file manager.", "ranger", "ranger"));
        l.add(pkg("lf", "lf", FILES, "Small, fast terminal file manager.", "lf", "lf").verify("lf -version"));
        l.add(pkg("aria2", "aria2", FILES, "Fast downloads that resume after interruptions.", "aria2", "aria2c").open(help("aria2c")));

        // Media & docs
        l.add(pkg("yt-dlp", "yt-dlp", MEDIA, "Download videos and audio from YouTube and 1000+ sites.", "python-yt-dlp ffmpeg", "yt-dlp")
            .note("Example: yt-dlp -x --audio-format mp3 URL").open(help("yt-dlp")));
        l.add(pkg("ffmpeg", "FFmpeg", MEDIA, "Convert, cut and compress audio and video.", "ffmpeg", "ffmpeg").verify("ffmpeg -version")
            .note("Example: ffmpeg -i in.mp4 out.mp3").open("ffmpeg -version | head -3" + WAIT));
        l.add(pkg("imagemagick", "ImageMagick", MEDIA, "Resize, convert and edit images in bulk.", "imagemagick", "magick")
            .note("Example: magick photo.jpg -resize 50% small.jpg").open(help("magick")));
        l.add(pkg("pandoc", "pandoc", MEDIA, "Convert documents between Word, Markdown, HTML, PDF and more.", "pandoc", "pandoc").open(help("pandoc")));
        l.add(pkg("pdf-tools", "PDF tools", MEDIA, "Merge, split and compress PDFs (Ghostscript + qpdf).", "ghostscript qpdf", "qpdf")
            .note("Merge: qpdf --empty --pages a.pdf b.pdf -- out.pdf").open(help("qpdf")));
        l.add(pkg("tesseract", "Tesseract OCR", MEDIA, "Read text from photos and scans.", "tesseract", "tesseract")
            .note("Example: tesseract photo.jpg out").open(help("tesseract")));
        l.add(pkg("sox", "SoX", MEDIA, "Edit and convert audio.", "sox", "sox").open(help("sox")));
        l.add(pkg("mpv", "mpv", MEDIA, "Play audio and video from the terminal.", "mpv", "mpv").open(help("mpv")));

        // Productivity
        l.add(pkg("taskwarrior", "Taskwarrior", PRODUCTIVITY, "To-do lists in the terminal.", "taskwarrior", "task").open("task list" + WAIT));
        l.add(pkg("calcurse", "calcurse", PRODUCTIVITY, "Calendar and scheduling.", "calcurse", "calcurse"));
        l.add(pkg("newsboat", "newsboat", PRODUCTIVITY, "RSS / news reader.", "newsboat", "newsboat"));
        l.add(pkg("glow", "glow", PRODUCTIVITY, "Read Markdown beautifully in the terminal.", "glow", "glow").open("glow ~"));
        l.add(pip("jrnl", "jrnl", PRODUCTIVITY, "A simple journal: jrnl today was great.", "jrnl", "jrnl"));
        l.add(pkg("hledger", "hledger", PRODUCTIVITY, "Track money with plain-text accounting.", "hledger", "hledger").open(help("hledger")));
        l.add(pkg("ledger", "ledger", PRODUCTIVITY, "Plain-text accounting (the original).", "ledger", "ledger").open(help("ledger")));
        l.add(pkg("bc", "bc", PRODUCTIVITY, "Calculator: echo '2^10' | bc", "bc", "bc").open("bc -l"));
        l.add(ub("qalc", "Qalculate", PRODUCTIVITY, "Powerful calculator with units: qalc '5 km to miles'.", "apt-get install -y qalc\n", "qalc")
            .note("Runs in the built-in Ubuntu."));

        // Terminal
        l.add(pkg("zsh", "Zsh + Oh My Zsh", TERMINAL, "A friendlier shell with themes and plugins, set as the default shell.", "zsh git curl", "zsh")
            .note("Changes your login shell to zsh. Remove switches back to bash.").open("zsh"));
        last(l).install = "pkg install -y zsh git curl\n" +
            "[ -d ~/.oh-my-zsh ] || RUNZSH=no CHSH=no sh -c \"$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\"\nchsh -s zsh\n";
        last(l).remove = "chsh -s bash";
        l.add(pkg("fish", "Fish shell", TERMINAL, "Smart shell with suggestions as you type.", "fish", "fish"));
        l.add(pkg("starship", "Starship prompt", TERMINAL, "A fast, pretty prompt showing git branch, language versions and more.", "starship", "starship"));
        last(l).install = "pkg install -y starship\ngrep -q 'starship init bash' ~/.bashrc 2>/dev/null || echo 'eval \"$(starship init bash)\"' >> ~/.bashrc\n";
        last(l).open = "echo 'Starship is on in new tabs.'" + WAIT;
        l.add(pkg("tmux", "tmux", TERMINAL, "Split the terminal into panes and keep sessions running.", "tmux", "tmux").verify("tmux -V"));
        l.add(pkg("zellij", "zellij", TERMINAL, "Modern terminal workspace with panes and tabs.", "zellij", "zellij"));
        l.add(pkg("htop", "htop", TERMINAL, "See running programs and memory.", "htop", "htop"));
        l.add(ub("btop", "btop", TERMINAL, "Beautiful system monitor.", "apt-get install -y btop\n", "btop").note("Runs in the built-in Ubuntu."));
        l.add(pkg("modern-unix", "eza + bat + zoxide", TERMINAL, "Nicer ls (eza), cat (bat) and cd (z).", "eza bat zoxide", "eza"));
        last(l).install = "pkg install -y eza bat zoxide\ngrep -q 'zoxide init bash' ~/.bashrc 2>/dev/null || echo 'eval \"$(zoxide init bash)\"' >> ~/.bashrc\n";
        last(l).remove = "pkg uninstall -y eza bat zoxide";
        last(l).open = "eza -la --icons ~" + WAIT;
        l.add(pkg("tldr", "tldr", TERMINAL, "Short, practical examples for any command: tldr tar.", "tealdeer", "tldr")
            .open("tldr --update\ntldr tar" + WAIT));
        l.add(pkg("fastfetch", "fastfetch", TERMINAL, "Show your phone's system info with style.", "fastfetch", "fastfetch").open("fastfetch" + WAIT));
        l.add(ub("lolcat", "lolcat", TERMINAL, "Rainbow text: fastfetch | lolcat.", "apt-get install -y lolcat\nln -sf /usr/games/lolcat /usr/local/bin/lolcat\n", "lolcat")
            .note("Runs in the built-in Ubuntu."));
        l.add(pkg("cmatrix", "cmatrix", TERMINAL, "The Matrix rain effect.", "cmatrix", "cmatrix").verify("cmatrix -V"));
        l.add(pkg("neofetch", "neofetch", TERMINAL, "Classic system info display.", "neofetch", "neofetch").open("neofetch" + WAIT)
            .verify("test -x $PREFIX/bin/neofetch"));

        // Linux & desktop
        l.add(distro("ubuntu", "Ubuntu", "A full Ubuntu 24.04 system inside Forge (no root needed). Also used by the Ubuntu-based tools.", "ubuntu:24.04"));
        l.add(distro("debian", "Debian", "A full Debian system inside Forge (no root needed).", "debian:bookworm"));
        l.add(distro("fedora", "Fedora", "A full Fedora system inside Forge (no root needed).", "fedora:latest"));
        l.add(distro("alpine", "Alpine", "A tiny Alpine Linux system (very small download).", "alpine:latest"));
        l.add(distro("opensuse", "openSUSE", "openSUSE Tumbleweed inside Forge (no root needed).", "opensuse/tumbleweed:latest"));
        l.add(new Item("desktop", "Linux desktop (XFCE)", LINUX, "A full Linux desktop on your phone, viewed with any VNC app.")
            .check("startxfce4").verify("test -x $PREFIX/bin/startxfce4")
            .note("Install a VNC viewer app (for example AVNC), then connect to localhost:5901. The first Open asks you to set a VNC password.")
            .open("mkdir -p ~/.vnc\nprintf '#!/data/data/com.termux/files/usr/bin/sh\\nxfce4-session &\\n' > ~/.vnc/xstartup\nchmod +x ~/.vnc/xstartup\nvncserver -kill :1 >/dev/null 2>&1\nvncserver :1 -geometry 1280x720 -localhost yes\necho 'Desktop running. Connect your VNC app to localhost:5901.'\nread -r -p 'Press Enter to stop the desktop. ' _\nvncserver -kill :1"));
        last(l).install = "pkg install -y x11-repo\npkg install -y xfce4 xfce4-terminal tigervnc\n";
        last(l).remove = "pkg uninstall -y xfce4 xfce4-terminal tigervnc";
        l.add(x11("firefox", "Firefox", "The Firefox browser, for the Linux desktop.", "firefox", "firefox"));
        l.add(x11("chromium", "Chromium", "The Chromium browser, for the Linux desktop.", "tur-repo chromium", "chromium-browser"));
        l.add(x11("libreoffice", "LibreOffice", "Word, Excel and PowerPoint-compatible office suite, for the Linux desktop.", "libreoffice", "libreoffice"));
        l.add(x11("vscode-desktop", "VS Code (desktop)", "VS Code as a desktop app (Code - OSS), for the Linux desktop.", "code-oss", "code-oss"));

        // Learn & fun
        l.add(pkg("exercism", "Exercism", FUN, "Coding exercises with mentoring in ~70 languages.", "exercism", "exercism").verify("exercism version"));
        l.add(pkg("vim", "Vim + vimtutor", FUN, "Learn Vim in 30 minutes with vimtutor.", "vim", "vim").open("vimtutor"));
        l.add(pkg("nethack", "NetHack", FUN, "The classic dungeon adventure game.", "nethack", "nethack").verify("test -x $PREFIX/bin/nethack"));
        l.add(pkg("moon-buggy", "Moon Buggy", FUN, "Drive a car across the moon.", "moon-buggy", "moon-buggy").verify("moon-buggy -V"));
        l.add(pkg("bastet", "Bastet (Tetris)", FUN, "Tetris that gives you the worst possible pieces.", "bastet", "bastet").verify("test -x $PREFIX/bin/bastet"));

        return l.toArray(new Item[0]);
    }

    private static Item last(List<Item> list) {
        return list.get(list.size() - 1);
    }

    private static Item neovimDistro(String id, String name, String description, String repo) {
        Item item = new Item(id, name, EDITORS, description);
        item.check = "$HOME/.config/nvim/.forge-" + id;
        item.install = "pkg install -y neovim git ripgrep fd nodejs-lts clang make\n" +
            "[ -d \"$HOME/.config/nvim\" ] && mv \"$HOME/.config/nvim\" \"$HOME/.config/nvim.bak.$(date +%s)\"\n" +
            "git clone --depth 1 " + repo + " \"$HOME/.config/nvim\"\nrm -rf \"$HOME/.config/nvim/.git\"\ntouch \"$HOME/.config/nvim/.forge-" + id + "\"\n" +
            "nvim --headless '+Lazy! sync' +qa >/dev/null 2>&1 || true\n";
        item.remove = "rm -rf \"$HOME/.config/nvim\" \"$HOME/.local/share/nvim\"";
        item.verify = "nvim --version";
        item.open = "nvim";
        item.note = "Backs up an existing Neovim setup to ~/.config/nvim.bak.*";
        return item;
    }

    private static Item distro(String id, String name, String description, String image) {
        Item item = new Item(id, name, LINUX, description);
        item.check = "$PREFIX/var/lib/proot-distro/containers/" + id;
        item.install = "pkg install -y proot-distro\npd_has " + id + " || proot-distro install " + image + " --name " + id + "\n";
        item.remove = "proot-distro remove " + id;
        item.verify = "proot-distro login " + id + " -- true";
        item.open = "proot-distro login " + id;
        return item;
    }

    private static Item x11(String id, String name, String description, String packages, String cmd) {
        Item item = new Item(id, name, LINUX, description);
        item.check = cmd;
        item.install = "pkg install -y x11-repo\npkg install -y " + packages + "\n";
        item.remove = "pkg uninstall -y " + packages.split(" ")[packages.split(" ").length - 1];
        item.verify = "test -x $PREFIX/bin/" + cmd;
        item.note = "Opens inside the Linux desktop item: install that too, then start it from the desktop menu.";
        item.open = "echo 'Start the Linux desktop, then open " + name + " from its menu.'" + WAIT;
        return item;
    }

    public static Item byId(String id) {
        for (Item item : ITEMS) if (item.id.equals(id)) return item;
        return null;
    }

    /** Categories that have at least one item, in display order. */
    public static List<String> categories() {
        Set<String> used = new LinkedHashSet<>();
        for (String c : CATEGORIES) for (Item item : ITEMS) if (item.category.equals(c)) { used.add(c); break; }
        return new ArrayList<>(used);
    }

    // ---- Scripts ----

    /** Output words that mean a tool did not really start, even if it exited with 0. */
    private static final String FAILURE_WORDS = "error|not found|cannot|unsupported|exec format|no platform package|not installed|failed";

    private static final String HELPERS =
        "export DEBIAN_FRONTEND=noninteractive\n" +
        "step() { printf '\\n\\033[1;38;5;208m== %s ==\\033[0m\\n' \"$1\"; }\n" +
        // `proot-distro list` prints its table to stderr; --quiet prints bare names to stdout.
        "pd_has() { proot-distro list --quiet 2>/dev/null | grep -qx \"$1\" || " +
        "[ -d \"$PREFIX/var/lib/proot-distro/containers/$1\" ] || [ -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/$1\" ]; }\n" +
        // Termux needs installed packages up to date before installing new ones (else apt exit 100).
        // Done at most once a day.
        "forge_upgrade() { m=\"$PREFIX/var/lib/forge-upgraded\"; [ \"$(cat \"$m\" 2>/dev/null)\" = \"$(date +%F)\" ] && return 0; " +
        "pkg upgrade -y -o Dpkg::Options::=--force-confnew && date +%F > \"$m\"; }\n" +
        "ub_setup() { forge_upgrade; pkg install -y proot-distro && { pd_has ubuntu || proot-distro install ubuntu:24.04 --name ubuntu; }; }\n" +
        // Never replace a native command of the same name; wrappers carry a marker.
        "ub_wrap() { for c in \"$@\"; do f=\"$PREFIX/bin/$c\"; " +
        "if [ -e \"$f\" ] && ! grep -q forge-ubuntu-wrapper \"$f\" 2>/dev/null; then echo \"Keeping the native $c\"; continue; fi; " +
        // Remove our old wrapper or a broken leftover link (e.g. from an earlier npm attempt) first.
        "rm -f \"$f\"; " +
        "printf '#!%s/bin/sh\\n# forge-ubuntu-wrapper\\nexec forge-ubuntu %s \"$@\"\\n' \"$PREFIX\" \"$c\" > \"$f\"; chmod 755 \"$f\"; done; }\n";

    /** Script for the install tab: installs, checks that the command works, then waits for Enter. */
    public static String installScript(Item item) {
        return HELPERS +
            "step 'Installing " + escapeSingle(item.name) + "'\n" +
            "( set -e\nforge_upgrade\n" + item.install + ")\n" +
            "ok=$?\n" +
            "v=$(timeout 180 sh -c '" + escapeSingle(item.verify) + "' 2>&1); vrc=$?; echo \"$v\" | head -3\n" +
            "if [ $ok = 0 ] && [ $vrc = 0 ] && ! echo \"$v\" | grep -qiE '" + FAILURE_WORDS + "'; then " +
            "printf '\\n\\033[1;32m%s is installed. Open it from the Store.\\033[0m\\n' '" + escapeSingle(item.name) + "'; " +
            "else printf '\\n\\033[1;31mInstall failed - scroll up to see why.\\033[0m\\n'; fi\n" +
            "read -r -p 'Press Enter to close this tab. ' _\n";
    }

    public static String removeScript(Item item) {
        return HELPERS + "step 'Removing " + escapeSingle(item.name) + "'\n" + item.remove + "\n" +
            "read -r -p 'Done. Press Enter to close this tab. ' _\n";
    }

    /** $PREFIX/bin/forge-ubuntu: runs a command inside the built-in Ubuntu, in the current folder. */
    public static String ubuntuRunner() {
        return "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n" +
            "# forge-ubuntu: run a command inside Forge's built-in Ubuntu, starting in the current folder.\n" +
            "# Forge's home folder is visible inside Ubuntu at the same path.\n" +
            "[ $# -eq 0 ] && exec proot-distro login ubuntu --shared-tmp\n" +
            "exec proot-distro login ubuntu --shared-tmp --work-dir \"$PWD\" -- env TERM=\"$TERM\" COLORTERM=\"$COLORTERM\" LANG=C.UTF-8 " +
            "DISABLE_AUTOUPDATER=1 PATH=/root/.local/bin:/root/.cargo/bin:/root/go/bin:/usr/local/go/bin:/root/.bun/bin:/root/.deno/bin:/root/.juliaup/bin:/root/.dotnet:" +
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games \"$@\"\n";
    }

    /**
     * The `forge-store` terminal command: list, install, remove and test items from the shell.
     * `forge-store test ID…` installs each item and prints PASS/FAIL, for checking what works on a device.
     */
    public static String cliScript() {
        StringBuilder s = new StringBuilder();
        s.append("#!").append(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH).append("/bash\n");
        s.append("# forge-store: install coding agents and tools. Usage: forge-store list | install ID... | remove ID... | test ID... | report\n");
        s.append(HELPERS);
        s.append("install_item() {\n  case \"$1\" in\n");
        for (Item item : ITEMS) s.append("    ").append(item.id).append(") ( set -e\nforge_upgrade\n").append(item.install).append(") ;;\n");
        s.append("    *) echo \"Unknown item: $1\"; return 2 ;;\n  esac\n}\n");
        s.append("remove_item() {\n  case \"$1\" in\n");
        for (Item item : ITEMS) s.append("    ").append(item.id).append(") ").append(item.remove.replace("\n", "; ")).append(" ;;\n");
        s.append("    *) echo \"Unknown item: $1\"; return 2 ;;\n  esac\n}\n");
        s.append("verify_cmd() {\n  case \"$1\" in\n");
        for (Item item : ITEMS) s.append("    ").append(item.id).append(") echo '").append(escapeSingle(item.verify)).append("' ;;\n");
        s.append("  esac\n}\n");
        s.append("case \"$1\" in\n");
        s.append("  list)\n");
        for (Item item : ITEMS) {
            s.append("    printf '%-18s %-26s %s\\n' '").append(item.id).append("' '").append(escapeSingle(item.name))
                .append("' '").append(escapeSingle(item.category)).append(item.ubuntu ? " (Ubuntu)" : "").append("'\n");
        }
        s.append("    ;;\n");
        s.append("  ids) shift; c=\"$*\"; ");
        s.append("case \"$c\" in '') printf '%s\\n'");
        for (Item item : ITEMS) s.append(" '").append(item.id).append("'");
        s.append(" ;; esac ;;\n");
        s.append("  install) shift; for i in \"$@\"; do step \"Installing $i\"; install_item \"$i\"; done ;;\n");
        s.append("  remove) shift; for i in \"$@\"; do remove_item \"$i\"; done ;;\n");
        s.append("  test)\n    shift\n    report=\"$HOME/forge-store-test.txt\"; : > \"$report\"\n");
        // Tests use Termux's main package server instead of a random mirror (mirrors can rate-limit).
        s.append("    [ -f \"$PREFIX/etc/termux/mirrors/default\" ] && ln -sfn \"$PREFIX/etc/termux/mirrors/default\" \"$PREFIX/etc/termux/chosen_mirrors\"\n");
        s.append("    for i in \"$@\"; do\n");
        s.append("      step \"Testing $i\"\n");
        s.append("      install_item \"$i\" > \"$TMPDIR/forge-store-$i.log\" 2>&1; rc=$?\n");
        s.append("      v=$(timeout 180 sh -c \"$(verify_cmd \"$i\")\" 2>&1); vrc=$?\n");
        s.append("      if [ $rc = 0 ] && [ $vrc = 0 ] && ! echo \"$v\" | grep -qiE '" + FAILURE_WORDS + "'; then r=PASS; else r=FAIL; fi\n");
        s.append("      line=\"$r $i: $(echo \"$v\" | head -1 | cut -c1-50)\"; [ $rc != 0 ] && line=\"$line (install exit $rc)\"\n");
        s.append("      echo \"$line\" | tee -a \"$report\"\n");
        s.append("      [ $r = FAIL ] && tail -4 \"$TMPDIR/forge-store-$i.log\" | cut -c1-90 | sed 's/^/    /' | tee -a \"$report\"\n");
        s.append("    done\n    step 'Summary'; forge-store report\n");
        // Copy logs to shared storage so an automated test can collect them.
        s.append("    mkdir -p /sdcard/forge-test 2>/dev/null && cp \"$report\" \"$TMPDIR\"/forge-store-*.log /sdcard/forge-test/ 2>/dev/null\n");
        // Tell an automated test that the run is over: the tab title shows in Forge's tabs bar.
        s.append("    printf '\\033]0;store-done\\007' ;;\n");
        s.append("  report) f=\"$HOME/forge-store-test.txt\"; grep -E '^FAIL' \"$f\" | cut -c1-70; ");
        s.append("echo \"PASS ($(grep -c '^PASS' \"$f\")): $(grep '^PASS' \"$f\" | cut -d' ' -f2 | tr -d ':' | tr '\\n' ' ')\" ;;\n");
        s.append("  *) echo 'Usage: forge-store list | install ID... | remove ID... | test ID... | report' ;;\n");
        s.append("esac\n");
        return s.toString();
    }

    private static String escapeSingle(String value) {
        return value.replace("'", "'\\''");
    }
}
