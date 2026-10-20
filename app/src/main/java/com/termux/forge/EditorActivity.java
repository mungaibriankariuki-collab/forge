package com.termux.forge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorSearcher;
import io.github.rosemoe.sora.widget.SymbolInputView;

/**
 * Forge Code: a code editor with a file tree, open-file tabs, syntax highlighting for ~20
 * languages (TextMate grammars from VS Code) and a Run button that runs the file in a terminal tab.
 * Files are saved automatically when switching tabs, running, or leaving the editor.
 */
public class EditorActivity extends Activity {

    private static final String LOG_TAG = "ForgeCode";

    /** Extra: a file to open, or a folder to use as the project. */
    public static final String EXTRA_PATH = "com.termux.forge.path";

    private static final int BAR = 0xFF1E1F26;
    private static final int TREE_BG = 0xFF16171D;
    private static final int TEXT = 0xFFE8E8EE;
    private static final int MUTED = 0xFF8A8C99;
    private static final long MAX_FILE_SIZE = 4 * 1024 * 1024;

    private static boolean sTextMateReady;

    static final class OpenFile {
        final File file;
        final Content content;
        boolean dirty;
        OpenFile(File file, Content content) { this.file = file; this.content = content; }
    }

    static final class Node {
        final File file;
        final int depth;
        Node(File file, int depth) { this.file = file; this.depth = depth; }
    }

    private DrawerLayout mDrawer;
    private CodeEditor mEditor;
    private LinearLayout mTabs;
    private TextView mEmpty;
    private TextView mRootLabel;
    private TreeAdapter mTreeAdapter;

    private File mRoot;
    private final List<OpenFile> mOpen = new ArrayList<>();
    private int mCurrent = -1;
    private boolean mSwitching;
    private final Set<String> mExpanded = new HashSet<>();
    private final List<Node> mNodes = new ArrayList<>();

    public static void open(Context context, String path) {
        Intent intent = new Intent(context, EditorActivity.class);
        if (path != null) intent.putExtra(EXTRA_PATH, path);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BAR);
        getWindow().setNavigationBarColor(BAR);
        ensureTextMate(this);
        buildViews();

        mRoot = new File(ForgeSettings.editorRoot(this));
        if (!mRoot.isDirectory()) mRoot = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        handleIntent(getIntent());
        refreshTree();
        showCurrent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        refreshTree();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveAll(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mEditor != null) mEditor.release();
    }

    @Override
    public void onBackPressed() {
        if (mDrawer.isDrawerOpen(GravityCompat.START)) mDrawer.closeDrawer(GravityCompat.START);
        else super.onBackPressed();
    }

    private void handleIntent(Intent intent) {
        String path = intent == null ? null : intent.getStringExtra(EXTRA_PATH);
        if (path == null) return;
        File target = new File(path);
        if (target.isDirectory()) {
            setRoot(target);
        } else {
            File parent = target.getParentFile();
            // Keep the current project if the file is inside it, otherwise use the file's folder.
            if (parent != null && !isInside(target, mRoot)) setRoot(parent);
            openFile(target);
        }
    }

    // ---- Views ----

    private void buildViews() {
        mDrawer = new DrawerLayout(this);
        mDrawer.setFitsSystemWindows(true);

        // Main content: top bar, editor, symbol bar.
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(BAR);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(BAR);
        top.addView(iconButton("☰", "Files", v -> mDrawer.openDrawer(GravityCompat.START)));
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        mTabs = new LinearLayout(this);
        mTabs.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(mTabs);
        top.addView(tabScroll, new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView run = iconButton("▶", "Run", v -> runCurrent());
        run.setTextColor(0xFF4ADE80);
        top.addView(run);
        top.addView(iconButton("⋮", "More", this::showMoreMenu));
        main.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        android.widget.FrameLayout editorFrame = new android.widget.FrameLayout(this);
        mEditor = new ForgeCodeEditor(this);
        mEditor.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()));
        mEditor.setTypefaceText(codeTypeface());
        mEditor.setTextSize(14);
        mEditor.setWordwrap(ForgeSettings.getFlag(this, "editor_wrap", false));
        mEditor.setPinLineNumber(true);
        mEditor.subscribeAlways(ContentChangeEvent.class, event -> {
            if (mSwitching || event.getAction() == ContentChangeEvent.ACTION_SET_NEW_TEXT) return;
            if (mCurrent >= 0 && !mOpen.get(mCurrent).dirty) {
                mOpen.get(mCurrent).dirty = true;
                refreshTabs();
            }
        });
        editorFrame.addView(mEditor);
        mEmpty = new TextView(this);
        mEmpty.setText("Open a file from ☰ Files,\nor create one with + File.\n\nTip: in the terminal, type  code hello.py");
        mEmpty.setTextColor(MUTED);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mEmpty.setBackgroundColor(0xFF2B2B2B);
        editorFrame.addView(mEmpty);
        main.addView(editorFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        SymbolInputView symbols = new SymbolInputView(this);
        symbols.setBackgroundColor(BAR);
        symbols.setTextColor(TEXT);
        symbols.bindEditor(mEditor);
        symbols.addSymbols(
            new String[]{"⇥", "{", "}", "(", ")", "[", "]", ";", "\"", "'", "=", ":", "<", ">", "/", "\\", "#", "_", "|", "&", "!", "$", "."},
            new String[]{"\t", "{}", "}", "()", ")", "[]", "]", ";", "\"", "'", "=", ":", "<", ">", "/", "\\", "#", "_", "|", "&", "!", "$", "."});
        HorizontalScrollView symbolScroll = new HorizontalScrollView(this);
        symbolScroll.setHorizontalScrollBarEnabled(false);
        symbolScroll.addView(symbols);
        main.addView(symbolScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        mDrawer.addView(main, new DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Drawer: project header and file tree.
        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(TREE_BG);
        drawer.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), dp(4), dp(6));
        header.addView(iconButton("⬆", "Parent folder", v -> {
            File parent = mRoot.getParentFile();
            if (parent != null && parent.canRead()) { setRoot(parent); refreshTree(); }
        }));
        mRootLabel = new TextView(this);
        mRootLabel.setTextColor(TEXT);
        mRootLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mRootLabel.setSingleLine(true);
        mRootLabel.setEllipsize(TextUtils.TruncateAt.START);
        header.addView(mRootLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        drawer.addView(header);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(8), 0, dp(8), dp(6));
        actions.addView(chip("+ File", v -> askNewFile(mRoot)));
        actions.addView(chip("+ Folder", v -> askNewFolder(mRoot)));
        actions.addView(chip("Terminal", v -> openTerminalIn(mRoot)));
        drawer.addView(actions);

        ListView tree = new ListView(this);
        tree.setDivider(null);
        mTreeAdapter = new TreeAdapter();
        tree.setAdapter(mTreeAdapter);
        tree.setOnItemClickListener((parent, view, position, id) -> {
            File file = mNodes.get(position).file;
            if (file.isDirectory()) {
                String path = file.getAbsolutePath();
                if (!mExpanded.remove(path)) mExpanded.add(path);
                refreshTree();
            } else {
                openFile(file);
                mDrawer.closeDrawer(GravityCompat.START);
            }
        });
        tree.setOnItemLongClickListener((parent, view, position, id) -> {
            showFileMenu(view, mNodes.get(position).file);
            return true;
        });
        drawer.addView(tree, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        DrawerLayout.LayoutParams lp = new DrawerLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = GravityCompat.START;
        mDrawer.addView(drawer, lp);

        setContentView(mDrawer);
    }

    // ---- Open files and tabs ----

    private void openFile(File file) {
        for (int i = 0; i < mOpen.size(); i++) {
            if (mOpen.get(i).file.equals(file)) { switchTo(i); return; }
        }
        try {
            if (file.length() > MAX_FILE_SIZE) throw new IOException("File is larger than 4 MB");
            byte[] bytes = readFile(file);
            for (int i = 0; i < Math.min(bytes.length, 8192); i++)
                if (bytes[i] == 0) throw new IOException("This looks like a binary file");
            mOpen.add(new OpenFile(file, new Content(new String(bytes, StandardCharsets.UTF_8))));
            switchTo(mOpen.size() - 1);
        } catch (IOException e) {
            toast("Cannot open " + file.getName() + ": " + e.getMessage());
        }
    }

    private void switchTo(int index) {
        saveCurrent(false);
        mCurrent = index;
        showCurrent();
    }

    private void showCurrent() {
        if (mCurrent < 0 || mCurrent >= mOpen.size()) {
            mCurrent = -1;
            mEmpty.setVisibility(View.VISIBLE);
            mEditor.setVisibility(View.INVISIBLE);
            refreshTabs();
            return;
        }
        OpenFile open = mOpen.get(mCurrent);
        mSwitching = true;
        mEditor.setEditorLanguage(languageFor(open.file));
        mEditor.setText(open.content, true, null);
        mSwitching = false;
        mEmpty.setVisibility(View.GONE);
        mEditor.setVisibility(View.VISIBLE);
        mEditor.requestFocus(); // Typing (and Enter) must go to the editor, not to a toolbar button.
        refreshTabs();
    }

    private void closeTab(int index) {
        OpenFile open = mOpen.get(index);
        if (open.dirty) save(open, false);
        mOpen.remove(index);
        if (mCurrent >= mOpen.size()) mCurrent = mOpen.size() - 1;
        else if (index < mCurrent) mCurrent--;
        showCurrent();
    }

    private void refreshTabs() {
        mTabs.removeAllViews();
        for (int i = 0; i < mOpen.size(); i++) {
            final int index = i;
            OpenFile open = mOpen.get(i);
            boolean selected = i == mCurrent;
            TextView tab = new TextView(this);
            tab.setText((open.dirty ? "● " : "") + open.file.getName() + "  ✕");
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tab.setSingleLine(true);
            tab.setTextColor(selected ? TEXT : MUTED);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPadding(dp(10), 0, dp(10), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(selected ? ForgeColors.withAlpha(ForgeColors.ACCENT, 0.2f) : Color.TRANSPARENT);
            if (selected) bg.setStroke(dp(1), ForgeColors.ACCENT);
            tab.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.setMargins(dp(2), 0, dp(2), 0);
            // Tap: switch; tap again on the selected tab: close it.
            tab.setOnClickListener(v -> { if (index == mCurrent) closeTab(index); else switchTo(index); });
            mTabs.addView(tab, lp);
        }
    }

    // ---- Saving ----

    private void saveCurrent(boolean announce) {
        if (mCurrent >= 0 && mCurrent < mOpen.size()) save(mOpen.get(mCurrent), announce);
    }

    private void saveAll(boolean announce) {
        for (OpenFile open : mOpen) if (open.dirty) save(open, false);
        if (announce) toast("Saved");
        refreshTabs();
    }

    private void save(OpenFile open, boolean announce) {
        if (!open.dirty && !announce) return;
        try (FileOutputStream out = new FileOutputStream(open.file)) {
            out.write(open.content.toString().getBytes(StandardCharsets.UTF_8));
            open.dirty = false;
            if (announce) toast("Saved " + open.file.getName());
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save " + open.file, e);
            toast("Could not save " + open.file.getName() + ": " + e.getMessage());
        }
        refreshTabs();
    }

    // ---- Run ----

    private void runCurrent() {
        saveAll(false);
        String custom = ForgeSettings.runCommand(this, mRoot.getAbsolutePath());
        String command;
        String workdir;
        if (custom != null) {
            command = custom;
            workdir = mRoot.getAbsolutePath();
        } else if (mCurrent >= 0) {
            File file = mOpen.get(mCurrent).file;
            command = CodeRunner.commandFor(file);
            workdir = file.getParent();
            if (command == null) {
                toast("Forge doesn't know how to run ." + extension(file) + " files. Use ⋮ → Set run command.");
                return;
            }
        } else {
            toast("Open a file first, or set a run command in ⋮");
            return;
        }
        try {
            TerminalRunner.runInNewTab(this, CodeRunner.script(command), "run", workdir);
        } catch (IOException e) {
            toast("Could not run: " + e.getMessage());
        }
    }

    // ---- Menus ----

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Save");
        menu.getMenu().add(0, 2, 1, "Undo");
        menu.getMenu().add(0, 3, 2, "Redo");
        menu.getMenu().add(0, 4, 3, "Find…");
        menu.getMenu().add(0, 5, 4, "Go to line…");
        menu.getMenu().add(0, 6, 5, (ForgeSettings.getFlag(this, "editor_wrap", false) ? "✓ " : "") + "Word wrap");
        menu.getMenu().add(0, 7, 6, (ForgeSettings.getFlag(this, "editor_hidden", false) ? "✓ " : "") + "Show hidden files");
        menu.getMenu().add(0, 8, 7, "Set run command…");
        menu.getMenu().add(0, 9, 8, "Open folder…");
        menu.getMenu().add(0, 10, 9, "Terminal here");
        menu.getMenu().add(0, 11, 10, "✦ Ask AI about this file");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: saveAll(true); break;
                case 2: mEditor.undo(); break;
                case 3: mEditor.redo(); break;
                case 4: askFind(); break;
                case 5: askGoToLine(); break;
                case 6: {
                    boolean wrap = !ForgeSettings.getFlag(this, "editor_wrap", false);
                    ForgeSettings.setFlag(this, "editor_wrap", wrap);
                    mEditor.setWordwrap(wrap);
                    break;
                }
                case 7: {
                    ForgeSettings.setFlag(this, "editor_hidden", !ForgeSettings.getFlag(this, "editor_hidden", false));
                    refreshTree();
                    break;
                }
                case 8: askRunCommand(); break;
                case 9: askOpenFolder(); break;
                case 10: openTerminalIn(mCurrent >= 0 ? mOpen.get(mCurrent).file.getParentFile() : mRoot); break;
                case 11: askAiAboutFile(); break;
            }
            return true;
        });
        menu.show();
    }

    private void askAiAboutFile() {
        if (mCurrent < 0) { AiActivity.open(this, null, null, null); return; }
        OpenFile open = mOpen.get(mCurrent);
        String text = open.content.toString();
        if (text.length() > 12000) text = text.substring(0, 12000) + "\n… (file truncated)";
        AiActivity.open(this, text, open.file.getName(), null);
    }

    private void showFileMenu(View anchor, File file) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean dir = file.isDirectory();
        if (dir) {
            menu.getMenu().add(0, 1, 0, "New file here");
            menu.getMenu().add(0, 2, 1, "New folder here");
            menu.getMenu().add(0, 3, 2, "Open as project");
            menu.getMenu().add(0, 4, 3, "Terminal here");
        } else {
            menu.getMenu().add(0, 5, 0, "Run");
        }
        menu.getMenu().add(0, 6, 4, "Rename…");
        menu.getMenu().add(0, 7, 5, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: askNewFile(file); break;
                case 2: askNewFolder(file); break;
                case 3: setRoot(file); refreshTree(); break;
                case 4: openTerminalIn(file); break;
                case 5: openFile(file); mDrawer.closeDrawer(GravityCompat.START); runCurrent(); break;
                case 6: askRename(file); break;
                case 7: askDelete(file); break;
            }
            return true;
        });
        menu.show();
    }

    private void askNewFile(File dir) {
        prompt("New file", "hello.py", "", name -> {
            File file = new File(dir, name);
            try {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                if (!file.exists() && !file.createNewFile()) throw new IOException("Cannot create file");
                mExpanded.add(dir.getAbsolutePath());
                refreshTree();
                openFile(file);
                mDrawer.closeDrawer(GravityCompat.START);
            } catch (IOException e) {
                toast("Could not create " + name + ": " + e.getMessage());
            }
        });
    }

    private void askNewFolder(File dir) {
        prompt("New folder", "my-project", "", name -> {
            File folder = new File(dir, name);
            if (!folder.mkdirs() && !folder.isDirectory()) { toast("Could not create " + name); return; }
            mExpanded.add(dir.getAbsolutePath());
            mExpanded.add(folder.getAbsolutePath());
            refreshTree();
        });
    }

    private void askRename(File file) {
        prompt("Rename", null, file.getName(), name -> {
            File target = new File(file.getParentFile(), name);
            if (target.exists()) { toast(name + " already exists"); return; }
            saveAll(false);
            if (!file.renameTo(target)) { toast("Could not rename"); return; }
            // Close tabs of the old path; reopen the renamed file if it was open.
            boolean wasOpen = false;
            for (int i = mOpen.size() - 1; i >= 0; i--) {
                if (isInside(mOpen.get(i).file, file) || mOpen.get(i).file.equals(file)) {
                    if (mOpen.get(i).file.equals(file)) wasOpen = true;
                    mOpen.remove(i);
                }
            }
            mCurrent = Math.min(mCurrent, mOpen.size() - 1);
            if (wasOpen) openFile(target); else showCurrent();
            refreshTree();
        });
    }

    private void askDelete(File file) {
        new AlertDialog.Builder(this)
            .setTitle("Delete " + file.getName() + "?")
            .setMessage(file.isDirectory() ? "The folder and everything inside it will be deleted. This cannot be undone." : "This cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                for (int i = mOpen.size() - 1; i >= 0; i--) {
                    File open = mOpen.get(i).file;
                    if (open.equals(file) || isInside(open, file)) mOpen.remove(i);
                }
                mCurrent = Math.min(mCurrent, mOpen.size() - 1);
                if (!deleteRecursively(file)) toast("Some files could not be deleted");
                showCurrent();
                refreshTree();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void askFind() {
        prompt("Find", "text to find", "", query -> {
            EditorSearcher searcher = mEditor.getSearcher();
            searcher.search(query, new EditorSearcher.SearchOptions(true, false));
            mEditor.postDelayed(() -> {
                if (searcher.hasQuery() && searcher.getMatchedPositionCount() > 0) {
                    searcher.gotoNext();
                    toast(searcher.getMatchedPositionCount() + " matches. Use Find again to clear.");
                } else {
                    searcher.stopSearch();
                    toast("No matches");
                }
            }, 300);
        });
    }

    private void askGoToLine() {
        if (mCurrent < 0) return;
        prompt("Go to line", "1", "", value -> {
            try {
                int line = Integer.parseInt(value.trim()) - 1;
                line = Math.max(0, Math.min(line, mEditor.getText().getLineCount() - 1));
                mEditor.setSelection(line, 0);
                mEditor.ensureSelectionVisible();
            } catch (NumberFormatException e) {
                toast("Not a line number");
            }
        });
    }

    private void askRunCommand() {
        String current = ForgeSettings.runCommand(this, mRoot.getAbsolutePath());
        prompt("Run command for " + mRoot.getName(), "e.g. npm start, python main.py, make run",
            current == null ? "" : current, value -> {
                ForgeSettings.setRunCommand(this, mRoot.getAbsolutePath(), value);
                toast(value.trim().isEmpty() ? "▶ now runs the open file" : "▶ now runs: " + value.trim());
            });
    }

    private void askOpenFolder() {
        prompt("Open folder", TermuxConstants.TERMUX_HOME_DIR_PATH, mRoot.getAbsolutePath(), value -> {
            File folder = new File(value.trim().replaceFirst("^~", TermuxConstants.TERMUX_HOME_DIR_PATH));
            if (!folder.isDirectory()) { toast("Not a folder: " + value); return; }
            setRoot(folder);
            refreshTree();
            mDrawer.openDrawer(GravityCompat.START);
        });
    }

    private void openTerminalIn(File dir) {
        if (dir == null) dir = mRoot;
        try {
            TerminalRunner.runInNewTab(this, "exec \"$SHELL\" -l\n", "shell", dir.getAbsolutePath());
        } catch (IOException e) {
            toast("Could not open a terminal: " + e.getMessage());
        }
    }

    interface Callback { void onValue(String value); }

    private void prompt(String title, String hint, String value, Callback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (hint != null) input.setHint(hint);
        input.setText(value);
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String text = input.getText().toString();
                if (!text.trim().isEmpty() || title.startsWith("Run command")) callback.onValue(text.trim());
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- File tree ----

    private void setRoot(File root) {
        mRoot = root;
        ForgeSettings.setEditorRoot(this, root.getAbsolutePath());
    }

    private void refreshTree() {
        mNodes.clear();
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String path = mRoot.getAbsolutePath();
        mRootLabel.setText(path.startsWith(home) ? "~" + path.substring(home.length()) : path);
        addChildren(mRoot, 0);
        mTreeAdapter.notifyDataSetChanged();
    }

    private void addChildren(File dir, int depth) {
        File[] children = dir.listFiles();
        if (children == null) return;
        boolean hidden = ForgeSettings.getFlag(this, "editor_hidden", false);
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File child : children) {
            if (!hidden && child.getName().startsWith(".")) continue;
            mNodes.add(new Node(child, depth));
            if (child.isDirectory() && mExpanded.contains(child.getAbsolutePath())) addChildren(child, depth + 1);
        }
    }

    class TreeAdapter extends BaseAdapter {
        @Override public int getCount() { return mNodes.size(); }
        @Override public Object getItem(int position) { return mNodes.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : new TextView(EditorActivity.this);
            Node node = mNodes.get(position);
            boolean dir = node.file.isDirectory();
            String icon = dir ? (mExpanded.contains(node.file.getAbsolutePath()) ? "▾ " : "▸ ") : "   ";
            row.setText(icon + node.file.getName());
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            boolean open = false;
            if (mCurrent >= 0 && mOpen.get(mCurrent).file.equals(node.file)) open = true;
            row.setTextColor(open ? ForgeColors.ACCENT : (dir ? TEXT : 0xFFC9CAD4));
            row.setTypeface(dir ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            row.setPadding(dp(12 + node.depth * 14), dp(9), dp(8), dp(9));
            return row;
        }
    }

    // ---- Helpers ----

    private static synchronized void ensureTextMate(Context context) {
        if (sTextMateReady) return;
        FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(context.getApplicationContext().getAssets()));
        try {
            ThemeRegistry themes = ThemeRegistry.getInstance();
            String path = "textmate/darcula.json";
            ThemeModel model = new ThemeModel(IThemeSource.fromInputStream(
                FileProviderRegistry.getInstance().tryGetInputStream(path), path, null), "darcula");
            model.setDark(true);
            themes.loadTheme(model);
            themes.setTheme("darcula");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load editor theme", e);
        }
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");
        sTextMateReady = true;
    }

    private static io.github.rosemoe.sora.lang.Language languageFor(File file) {
        String scope = scopeFor(file);
        if (scope == null) return new EmptyLanguage();
        try {
            return TextMateLanguage.create(scope, true);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "No grammar for " + scope, e);
            return new EmptyLanguage();
        }
    }

    static String scopeFor(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.equals("makefile") || name.equals("gnumakefile")) return "source.makefile";
        if (name.equals(".bashrc") || name.equals(".profile") || name.equals(".bash_profile")) return "source.shell";
        switch (extension(file)) {
            case "py": return "source.python";
            case "js": case "mjs": case "cjs": case "jsx": return "source.js";
            case "ts": case "tsx": return "source.ts";
            case "c": case "h": return "source.c";
            case "cpp": case "cc": case "cxx": case "hpp": case "hh": return "source.cpp";
            case "go": return "source.go";
            case "rs": return "source.rust";
            case "sh": case "bash": case "zsh": return "source.shell";
            case "json": return "source.json";
            case "css": return "source.css";
            case "html": case "htm": return "text.html.basic";
            case "md": case "markdown": return "text.html.markdown";
            case "java": return "source.java";
            case "yml": case "yaml": return "source.yaml";
            case "php": return "text.html.php";
            case "rb": return "source.ruby";
            case "xml": case "svg": return "text.xml";
            case "sql": return "source.sql";
            case "lua": return "source.lua";
            case "mk": return "source.makefile";
            case "ini": case "cfg": case "conf": case "properties": case "toml": return "source.ini";
            default: return null;
        }
    }

    private static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isInside(File file, File dir) {
        String d = dir.getAbsolutePath() + File.separator;
        return file.getAbsolutePath().startsWith(d);
    }

    private static boolean deleteRecursively(File file) {
        boolean ok = true;
        // Do not follow symlinks into other folders (e.g. ~/storage).
        try {
            if (file.isDirectory() && file.getCanonicalPath().equals(file.getAbsolutePath())) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) ok &= deleteRecursively(child);
            }
        } catch (IOException e) {
            ok = false;
        }
        return file.delete() && ok;
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private Typeface codeTypeface() {
        File font = TermuxConstants.TERMUX_FONT_FILE;
        if (font.isFile() && font.length() > 0) {
            try {
                return Typeface.createFromFile(font);
            } catch (Exception ignored) {
            }
        }
        return Typeface.MONOSPACE;
    }

    private TextView iconButton(String text, String description, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setContentDescription(description);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setTextColor(TEXT);
        button.setGravity(Gravity.CENTER);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        button.setFocusable(false); // Keep keyboard focus in the editor.
        button.setOnClickListener(listener);
        return button;
    }

    private TextView chip(String text, View.OnClickListener listener) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTextColor(TEXT);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0xFF2A2C38);
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(listener);
        return chip;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
