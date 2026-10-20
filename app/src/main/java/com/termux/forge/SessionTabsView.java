package com.termux.forge;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.core.view.GravityCompat;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * Forge's session tabs bar: one tab per terminal session, plus buttons for a new session, the
 * appearance screen and the sessions drawer. Tap a tab to switch, long-press it to rename or close.
 */
public class SessionTabsView extends LinearLayout {

    private TermuxActivity mActivity;
    private final HorizontalScrollView mScroll;
    private final LinearLayout mTabs;
    private final TextView mNewButton;
    private final TextView mAiButton;
    private final TextView mCodeButton;
    private final TextView mStyleButton;
    private final TextView mMenuButton;

    private int mBackground = 0xFF15161E;
    private int mForeground = 0xFFE6E6E6;

    public SessionTabsView(Context context) { this(context, null); }

    public SessionTabsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        mScroll = new HorizontalScrollView(context);
        mScroll.setHorizontalScrollBarEnabled(false);
        mScroll.setFillViewport(false);
        mTabs = new LinearLayout(context);
        mTabs.setOrientation(HORIZONTAL);
        mTabs.setGravity(Gravity.CENTER_VERTICAL);
        mScroll.addView(mTabs);
        addView(mScroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        mNewButton = makeIconButton("+", "New session");
        mCodeButton = makeIconButton("</>", "Code editor");
        mCodeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mStyleButton = makeIconButton("⋯", "Forge menu");
        mMenuButton = makeIconButton("☰", "Sessions and settings"); // ☰
        mAiButton = makeIconButton("✦", "Forge AI"); // ✦
        mAiButton.setTextColor(ForgeColors.ACCENT);
        addView(mNewButton);
        addView(mAiButton);
        addView(mCodeButton);
        addView(mStyleButton);
        addView(mMenuButton);
    }

    /** Bind to the activity once the service is connected. */
    public void attach(TermuxActivity activity) {
        mActivity = activity;
        mNewButton.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().addNewSession(false, null));
        mNewButton.setOnLongClickListener(v -> {
            mActivity.getTermuxTerminalSessionClient().addNewSession(true, "Failsafe");
            return true;
        });
        mAiButton.setOnClickListener(v -> mActivity.openForgeAi());
        mStyleButton.setOnClickListener(this::showForgeMenu);
        // Open the editor in the current session's folder when it is inside home, else the last project.
        mCodeButton.setOnClickListener(v -> {
            TerminalSession session = mActivity.getCurrentSession();
            String cwd = session == null ? null : session.getCwd();
            String home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH;
            EditorActivity.open(mActivity, cwd != null && cwd.startsWith(home) && !cwd.equals(home) ? cwd : null);
        });
        mMenuButton.setOnClickListener(v -> mActivity.getDrawer().openDrawer(GravityCompat.START));
        refresh();
    }

    /** The ⋯ menu: everything Forge adds, in one place. */
    private void showForgeMenu(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "🕘 Command history");
        menu.getMenu().add(0, 2, 1, "🤖 AI agent");
        menu.getMenu().add(0, 3, 2, "💬 AI chats");
        menu.getMenu().add(0, 4, 3, "🧩 Store (agents & tools)");
        menu.getMenu().add(0, 5, 4, "⬇ Setup kits");
        menu.getMenu().add(0, 6, 5, "◐ Appearance");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: mActivity.openCommandHistory(); break;
                case 2: mActivity.openForgeAgent(); break;
                case 3: mActivity.startActivity(new Intent(mActivity, AiActivity.class).putExtra(AiActivity.EXTRA_SHOW_HISTORY, true)); break;
                case 4: mActivity.startActivity(new Intent(mActivity, StoreActivity.class)); break;
                case 5: SetupActivity.open(mActivity, false); break;
                case 6: mActivity.startActivity(new Intent(mActivity, AppearanceActivity.class)); break;
            }
            return true;
        });
        menu.show();
    }

    /** Use the terminal's own colours so the bar matches the chosen theme. */
    public void setColors(int background, int foreground) {
        mBackground = background;
        mForeground = foreground;
        refresh();
    }

    public void refresh() {
        setBackgroundColor(ForgeColors.blend(mBackground, ForgeColors.isDark(mBackground) ? Color.WHITE : Color.BLACK, 0.07f));
        for (TextView button : new TextView[]{mNewButton, mCodeButton, mStyleButton, mMenuButton})
            button.setTextColor(ForgeColors.withAlpha(mForeground, 0.85f));

        mTabs.removeAllViews();
        if (mActivity == null) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession current = mActivity.getCurrentSession();
        List<TermuxSession> sessions = service.getTermuxSessions();
        View currentTab = null;
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession session = sessions.get(i).getTerminalSession();
            View tab = makeTab(i, session, session == current);
            mTabs.addView(tab);
            if (session == current) currentTab = tab;
        }
        if (currentTab != null) {
            final View target = currentTab;
            mScroll.post(() -> mScroll.smoothScrollTo(Math.max(0, target.getLeft() - dp(24)), 0));
        }
    }

    private View makeTab(int index, TerminalSession session, boolean selected) {
        TextView tab = new TextView(getContext());
        tab.setText(tabTitle(index, session));
        tab.setSingleLine(true);
        tab.setEllipsize(TextUtils.TruncateAt.END);
        tab.setMaxWidth(dp(160));
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setPadding(dp(12), 0, dp(12), 0);
        tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);

        boolean running = session.isRunning();
        int text = running || session.getExitStatus() == 0 ? mForeground : 0xFFFF5555;
        tab.setTextColor(selected ? text : ForgeColors.withAlpha(text, 0.6f));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(selected ? ForgeColors.withAlpha(ForgeColors.ACCENT, 0.22f) : Color.TRANSPARENT);
        if (selected) bg.setStroke(dp(1), ForgeColors.ACCENT);
        tab.setBackground(bg);

        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(28));
        lp.setMargins(dp(3), 0, dp(3), 0);
        tab.setLayoutParams(lp);

        tab.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().setCurrentSession(session));
        tab.setOnLongClickListener(v -> {
            showTabMenu(v, session);
            return true;
        });
        return tab;
    }

    private void showTabMenu(View anchor, TerminalSession session) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "Rename");
        menu.getMenu().add(0, 2, 1, "Close");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                mActivity.getTermuxTerminalSessionClient().renameSession(session);
            } else {
                closeSession(session);
            }
            return true;
        });
        menu.show();
    }

    private void closeSession(TerminalSession session) {
        if (!session.isRunning()) {
            mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
            return;
        }
        new AlertDialog.Builder(getContext())
            .setMessage("Close this session? Anything running in it will be stopped.")
            .setPositiveButton("Close", (d, w) -> {
                session.finishIfRunning();
                mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static String tabTitle(int index, TerminalSession session) {
        String name = !TextUtils.isEmpty(session.mSessionName) ? session.mSessionName : session.getTitle();
        if (TextUtils.isEmpty(name)) name = "shell";
        return (index + 1) + "  " + name;
    }

    private TextView makeIconButton(String text, String description) {
        TextView button = new TextView(getContext());
        button.setText(text);
        button.setContentDescription(description);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setGravity(Gravity.CENTER);
        TypedValue ripple = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
        button.setLayoutParams(new LayoutParams(dp(38), LayoutParams.MATCH_PARENT));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
