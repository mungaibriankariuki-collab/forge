package com.termux.forge;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a bash command in the background with the same environment as Forge's terminal, and
 * captures its combined output. Used by the AI agent and the editor's git panel.
 * Blocking: call off the main thread.
 */
public final class ShellExec {

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final boolean timedOut;
        Result(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }
    }

    /** Output longer than this is cut in the middle. */
    private static final int MAX_OUTPUT = 64 * 1024;

    private ShellExec() {}

    public static Result run(Context context, String command, File workingDirectory, int timeoutSeconds) throws IOException {
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).canExecute()) throw new IOException("The Linux environment is not installed yet. Open the terminal first.");

        ProcessBuilder builder = new ProcessBuilder(bash, "-c", command);
        Map<String, String> env = builder.environment();
        env.clear();
        env.putAll(new TermuxShellEnvironment().getEnvironment(context, false));
        // Non-interactive: no pager, no prompts waiting forever.
        env.put("PAGER", "cat");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("DEBIAN_FRONTEND", "noninteractive");
        File dir = workingDirectory != null && workingDirectory.isDirectory() ? workingDirectory : new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        builder.directory(dir);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        process.getOutputStream().close(); // No stdin: interactive commands get EOF instead of hanging.

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    synchronized (buffer) {
                        if (buffer.size() < MAX_OUTPUT * 2) buffer.write(chunk, 0, n);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "forge-shell-reader");
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Stopped");
        }

        String output;
        synchronized (buffer) {
            output = buffer.toString("UTF-8");
        }
        if (output.length() > MAX_OUTPUT) {
            int half = MAX_OUTPUT / 2;
            output = output.substring(0, half) + "\n… (" + (output.length() - MAX_OUTPUT) + " characters cut) …\n" + output.substring(output.length() - half);
        }
        return new Result(finished ? process.exitValue() : -1, output, !finished);
    }
}
