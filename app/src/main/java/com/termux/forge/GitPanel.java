package com.termux.forge;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.termux.forge.TerminalRunner.quote;

/**
 * Version history for the editor's project, using git underneath but in plain words:
 * start history, save a version, see what changed, and restore a file or the whole project.
 */
final class GitPanel {

    private final EditorActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final File mRoot;

    GitPanel(EditorActivity activity) {
        mActivity = activity;
        mRoot = activity.getProjectRoot();
    }

    void show() {
        if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "git").exists()) {
            new AlertDialog.Builder(mActivity)
                .setTitle("Git is not installed")
                .setMessage("Version history uses git. Install the \"Git & GitHub\" kit from Setup, then try again.")
                .setPositiveButton("Open Setup", (d, w) -> SetupActivity.open(mActivity, false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        mActivity.saveAllFiles();
        git("rev-parse --show-toplevel", result -> {
            if (result.exitCode != 0) offerInit();
            else showMenu(result.output.trim());
        });
    }

    private void offerInit() {
        new AlertDialog.Builder(mActivity)
            .setTitle("Start version history?")
            .setMessage("Forge will keep versions of everything in " + mRoot.getName() + " (a git repository), " +
                "so you can see what changed and go back to any saved version.")
            .setPositiveButton("Start", (d, w) -> git("init -q && " + identity() + " add -A && " + identity() +
                " commit -q -m 'First version' --allow-empty && echo ok", r -> {
                toast(r.exitCode == 0 ? "Version history started" : "Could not start: " + r.output.trim());
                if (r.exitCode == 0) show();
            }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showMenu(String top) {
        File current = mActivity.getCurrentFile();
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add("💾 Save a version…"); actions.add(this::askCommit);
        items.add("📝 What changed since the last version"); actions.add(() -> showOutput("Changes since last version",
            "status --short && echo && git --no-pager diff --stat HEAD && echo && git --no-pager diff HEAD"));
        if (current != null) {
            items.add("📄 Changes in " + current.getName()); actions.add(() -> showOutput("Changes in " + current.getName(),
                "--no-pager diff HEAD -- " + quote(current.getAbsolutePath())));
            items.add("🕘 Versions of " + current.getName()); actions.add(() -> showVersions(current));
        }
        items.add("🕘 All versions of the project"); actions.add(() -> showVersions(null));
        new AlertDialog.Builder(mActivity)
            .setTitle("Version history · " + new File(top).getName())
            .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    private void askCommit() {
        EditText input = new EditText(mActivity);
        input.setSingleLine(true);
        input.setHint("What did you change? e.g. Added login page");
        LinearLayout box = new LinearLayout(mActivity);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(mActivity)
            .setTitle("Save a version")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String message = input.getText().toString().trim();
                if (message.isEmpty()) message = "Saved from Forge";
                git("add -A && " + identity() + " commit -q -m " + quote(message) + " && git log -1 --format='%h %s'", r -> {
                    if (r.exitCode == 0) toast("Saved version " + r.output.trim());
                    else if (r.output.contains("nothing to commit")) toast("Nothing changed since the last version");
                    else toast("Could not save: " + r.output.trim());
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showVersions(File file) {
        String path = file == null ? "" : " -- " + quote(file.getAbsolutePath());
        git("--no-pager log -50 --format='%h|%ar|%s'" + path, r -> {
            if (r.exitCode != 0 || r.output.trim().isEmpty()) { toast("No saved versions yet"); return; }
            List<String> hashes = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (String line : r.output.trim().split("\n")) {
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) continue;
                hashes.add(parts[0]);
                labels.add(parts[2] + "\n" + parts[1] + " · " + parts[0]);
            }
            new AlertDialog.Builder(mActivity)
                .setTitle(file == null ? "Project versions" : "Versions of " + file.getName())
                .setItems(labels.toArray(new String[0]), (d, which) -> versionActions(hashes.get(which), labels.get(which), file))
                .show();
        });
    }

    private void versionActions(String hash, String label, File file) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add("Show what this version changed");
        actions.add(() -> showOutput(label.split("\n")[0], "--no-pager show --stat --patch " + hash +
            (file == null ? "" : " -- " + quote(file.getAbsolutePath()))));
        if (file != null) {
            items.add("Restore " + file.getName() + " to this version");
            actions.add(() -> confirmRestore("Restore " + file.getName() + "?",
                "The file will go back to how it was in this version. Its current content is saved as a new version first, so nothing is lost.",
                "checkout " + hash + " -- " + quote(file.getAbsolutePath())));
        }
        items.add("Restore the whole project to this version");
        actions.add(() -> confirmRestore("Restore the whole project?",
            "All files will go back to how they were in this version. Your current state is saved as a new version first, so you can come back to it.",
            "checkout " + hash + " -- ."));
        new AlertDialog.Builder(mActivity)
            .setTitle(label.split("\n")[0])
            .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    private void confirmRestore(String title, String message, String restoreCommand) {
        new AlertDialog.Builder(mActivity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Restore", (d, w) -> {
                mActivity.saveAllFiles();
                // Save the current state first (if anything changed), then restore.
                git("add -A && (" + identity() + " commit -q -m 'Before restore' || true) && git " + restoreCommand, r -> {
                    if (r.exitCode == 0) {
                        toast("Restored");
                        mActivity.reloadOpenFilesFromDisk();
                    } else {
                        toast("Could not restore: " + r.output.trim());
                    }
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showOutput(String title, String gitArgs) {
        git(gitArgs, r -> {
            String text = r.output.trim().isEmpty() ? "No changes." : r.output;
            TextView view = new TextView(mActivity);
            view.setTypeface(Typeface.MONOSPACE);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            view.setTextIsSelectable(true);
            view.setPadding(dp(14), dp(10), dp(14), dp(10));
            view.setText(colorDiff(text));
            HorizontalScrollView horizontal = new HorizontalScrollView(mActivity);
            horizontal.addView(view);
            ScrollView vertical = new ScrollView(mActivity);
            vertical.addView(horizontal);
            new AlertDialog.Builder(mActivity).setTitle(title).setView(vertical).setPositiveButton(android.R.string.ok, null).show();
        });
    }

    /** Green for added lines, red for removed ones, orange for file/hunk headers. */
    private static CharSequence colorDiff(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (String line : text.split("\n", -1)) {
            int start = out.length();
            out.append(line).append('\n');
            int color = 0;
            if (line.startsWith("+") && !line.startsWith("+++")) color = 0xFF2E9E57;
            else if (line.startsWith("-") && !line.startsWith("---")) color = 0xFFD0453A;
            else if (line.startsWith("@@") || line.startsWith("diff ") || line.startsWith("commit ")) color = 0xFFE07B1A;
            if (color != 0) out.setSpan(new ForegroundColorSpan(color), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** Uses the user's git name/email if set, otherwise a local fallback so saving always works. */
    private static String identity() {
        return "git -c user.name=\"$(git config user.name || echo Forge)\" -c user.email=\"$(git config user.email || echo forge@localhost)\"";
    }

    interface Callback { void onResult(ShellExec.Result result); }

    /** Runs `git ARGS` (ARGS may chain more commands) in the project folder, off the main thread. */
    private void git(String args, Callback callback) {
        new Thread(() -> {
            ShellExec.Result result;
            try {
                result = ShellExec.run(mActivity, "git " + args, mRoot, 60);
            } catch (Exception e) {
                result = null;
                String message = e.getMessage();
                mHandler.post(() -> toast("Git failed: " + message));
            }
            ShellExec.Result r = result;
            if (r != null) mHandler.post(() -> callback.onResult(r));
        }, "forge-git").start();
    }

    private void toast(String message) {
        Toast.makeText(mActivity, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
