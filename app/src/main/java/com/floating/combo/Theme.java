package com.floating.combo;

import android.graphics.drawable.GradientDrawable;

public final class Theme {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_LIGHT = 2;
    public static final int MODE_DEFAULT = MODE_SYSTEM;

    public static int BASE = 0xFF121212;
    public static int RAISED = 0xFF1E1E1E;
    public static int OVERLAY = 0xFF262626;
    public static int PRESSED = 0xFF2C2C2C;
    public static int DIVIDER = 0xFF333333;
    public static int ACCENT = 0xFF4AA8FF;
    public static int ACCENT_SOFT = 0xFF2E2E2E;
    public static int TRACK = 0xFF3A3A3A;
    public static int BUTTON = 0xFF2C2C2C;
    public static int SUCCESS = 0xFF3ECF8E;
    public static int WARN = 0xFFF5A524;
    public static int DANGER = 0xFFF2545B;
    public static int TEXT = 0xFFF2F2F2;
    public static int TEXT_2 = 0xFFA8A8A8;
    public static int TEXT_3 = 0xFF777777;
    public static int GRADIENT_FROM = 0xFF4AA8FF;
    public static int GRADIENT_TO = 0xFF4AA8FF;

    public static final int TRANSPARENT = 0x00000000;
    public static final int ON_ACCENT = 0xFFFFFFFF;
    public static final int QR_WHITE = 0xFFFFFFFF;

    public static int BG = 0xFF121212;
    public static int SURFACE = 0xFF1E1E1E;
    public static int SURFACE_2 = 0xFF262626;
    public static int BORDER = 0xFF333333;
    public static int TEAL = 0xFF4AA8FF;
    public static int BLUE = 0xFF4AA8FF;

    public static final int GRID = 4;
    public static final int GAP_GROUP = 12;
    public static final int CARD_PAD = 16;
    public static final int CARD_R = 14;
    public static final int CTRL_R = 10;
    public static final int HIT = 48;
    public static final int ROW_SELECT = 48;
    public static final int ROW_SWITCH = 48;
    public static final int ROW_ACTION = 48;
    public static final int ROW_PLAIN = 44;
    public static final int ROW_SLIDER = 56;
    public static final int ROW_CHIPS = 64;
    public static final int ROW_BALL_PREVIEW = 96;
    public static final int ROW_SEGMENT = 52;
    public static final int ROW_PREVIEW = 60;
    public static final int ROW_RESET = 48;
    public static final int ROW_QR = 148;

    public static final int DUR_MICRO = 120;
    public static final int DUR_STANDARD = 240;

    private Theme() {
    }

    public static void apply(int mode, boolean systemNight) {
        boolean dark = (mode == MODE_DARK)
                || (mode == MODE_SYSTEM && systemNight);
        if (dark) {
            BASE = 0xFF121212;
            RAISED = 0xFF1E1E1E;
            OVERLAY = 0xFF262626;
            PRESSED = 0xFF2C2C2C;
            DIVIDER = 0xFF333333;
            ACCENT = 0xFF4AA8FF;
            ACCENT_SOFT = 0xFF2E2E2E;
            TRACK = 0xFF3A3A3A;
            BUTTON = 0xFF2C2C2C;
            SUCCESS = 0xFF3ECF8E;
            WARN = 0xFFF5A524;
            DANGER = 0xFFF2545B;
            TEXT = 0xFFF2F2F2;
            TEXT_2 = 0xFFA8A8A8;
            TEXT_3 = 0xFF777777;
            GRADIENT_FROM = 0xFF4AA8FF;
            GRADIENT_TO = 0xFF4AA8FF;
        } else {
            BASE = 0xFFF6F8FB;
            RAISED = 0xFFFFFFFF;
            OVERLAY = 0xFFEFF2F7;
            PRESSED = 0xFFE7EAF0;
            DIVIDER = 0xFFE7EAF0;
            ACCENT = 0xFF2F6BFF;
            ACCENT_SOFT = 0xFFE7EEFF;
            TRACK = 0xFFD8DDE7;
            BUTTON = 0xFFF0F3F8;
            SUCCESS = 0xFF16A34A;
            WARN = 0xFFD97706;
            DANGER = 0xFFDC2626;
            TEXT = 0xFF1A1F27;
            TEXT_2 = 0xFF6B7280;
            TEXT_3 = 0xFF9AA3AF;
            GRADIENT_FROM = 0xFF2F6BFF;
            GRADIENT_TO = 0xFF2F6BFF;
        }
        BG = BASE;
        SURFACE = RAISED;
        SURFACE_2 = OVERLAY;
        BORDER = DIVIDER;
        TEAL = GRADIENT_FROM;
        BLUE = GRADIENT_TO;
    }

    public static GradientDrawable rounded(int color, float radiusPx,
                                           int strokeColor, float strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0f) {
            g.setStroke((int) (strokePx + 0.5f), strokeColor);
        }
        return g;
    }

    public static GradientDrawable circle(int color, float radiusPx,
                                          int strokeColor, float strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize((int) (radiusPx * 2), (int) (radiusPx * 2));
        if (strokePx > 0f) {
            g.setStroke((int) (strokePx + 0.5f), strokeColor);
        }
        return g;
    }

    public static GradientDrawable brandGradient(float radiusPx) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{GRADIENT_FROM, GRADIENT_TO});
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable brandGradientPill(float radiusPx) {
        return brandGradient(radiusPx);
    }

    public static GradientDrawable focusSurface(float radiusPx, float strokePx) {
        return rounded(RAISED, radiusPx, ACCENT, strokePx);
    }
}
