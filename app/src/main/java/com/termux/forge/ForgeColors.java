package com.termux.forge;

import android.graphics.Color;

/** Forge's accent colour and small colour helpers. */
public final class ForgeColors {

    /** Forge orange, as in the launcher icon. */
    public static final int ACCENT = 0xFFFF7A1A;

    private ForgeColors() {}

    public static int blend(int from, int to, float amount) {
        float inv = 1f - amount;
        return Color.argb(255,
            Math.round(Color.red(from) * inv + Color.red(to) * amount),
            Math.round(Color.green(from) * inv + Color.green(to) * amount),
            Math.round(Color.blue(from) * inv + Color.blue(to) * amount));
    }

    public static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    public static boolean isDark(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance < 0.5;
    }
}
