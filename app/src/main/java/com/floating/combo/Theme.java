package com.floating.combo;

import android.graphics.drawable.GradientDrawable;

/**
 * 看得清 3.8 · 可切换的 Design Tokens。
 *
 * 3.7 那套是「一套深色写死」。3.8 按实测反馈补了正式的深 / 浅主题：
 * 「更多 → 外观」里的三段控件真正切换整套配色，不是只换一块球预览。
 *
 * 色值改成运行时可写的静态字段：所有界面代码都是「用的时候才读 Theme.XXX」，
 * 所以 apply() 换掉一套值 + recreate() 重画，就能整屏跟着变，不必逐个控件改引用。
 *
 * 旧名（BG / SURFACE / ...）保留为别名并在 apply() 里同步赋值，避免遗留引用拿到旧色。
 * 纯展示层，不引用 TTS / 抓字 / 日志 / 手势。
 */
public final class Theme {

    /** 0=跟随系统，1=深色，2=浅色。默认深色，与 3.7 观感一致，不突然变白。 */
    public static final int MODE_SYSTEM = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_LIGHT = 2;
    public static final int MODE_DEFAULT = MODE_DARK;

    // ---- 运行时调色板（apply 里整体替换；逐行给初值，apply 前也读得到） ----
    public static int BASE = 0xFF0B0E13;
    public static int RAISED = 0xFF151B25;
    public static int OVERLAY = 0xFF1C2330;
    public static int PRESSED = 0xFF1E2836;
    public static int DIVIDER = 0xFF242C39;
    public static int ACCENT = 0xFF4AA8FF;
    public static int ACCENT_SOFT = 0xFF1B2C40;
    public static int TRACK = 0xFF2C3544;
    public static int BUTTON = 0xFF1E2836;
    public static int SUCCESS = 0xFF3ECF8E;
    public static int WARN = 0xFFF5A524;
    public static int DANGER = 0xFFF2545B;
    public static int TEXT = 0xFFF2F5FA;
    public static int TEXT_2 = 0xFF8E9AAE;
    public static int TEXT_3 = 0xFF6B7789;
    public static int GRADIENT_FROM = 0xFF2BD4C4;
    public static int GRADIENT_TO = 0xFF3B82F6;

    // ---- 两套都不变的常量 -------------------------------------------------
    public static final int TRANSPARENT = 0x00000000;
    public static final int ON_ACCENT = 0xFFFFFFFF;
    public static final int QR_WHITE = 0xFFFFFFFF;

    // ---- 旧名别名 --------------------------------------------------------
    public static int BG = 0xFF0B0E13;
    public static int SURFACE = 0xFF151B25;
    public static int SURFACE_2 = 0xFF1C2330;
    public static int BORDER = 0xFF242C39;
    public static int TEAL = 0xFF2BD4C4;
    public static int BLUE = 0xFF3B82F6;

    // ---- 间距 / 圆角（dp，调用方换算） ------------------------------------
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
    public static final int ROW_CHIPS = 48;
    public static final int ROW_SEGMENT = 52;
    public static final int ROW_PREVIEW = 60;
    public static final int ROW_RESET = 48;
    public static final int ROW_QR = 148;

    // ---- 动效（ms） -------------------------------------------------------
    public static final int DUR_MICRO = 120;
    public static final int DUR_STANDARD = 240;

    private Theme() {
    }

    /** 换整套配色。必须在任何 View 被创建之前调用。 */
    public static void apply(int mode, boolean systemNight) {
        boolean dark = (mode == MODE_DARK)
                || (mode == MODE_SYSTEM && systemNight);
        if (dark) {
            BASE = 0xFF0B0E13;
            RAISED = 0xFF151B25;
            OVERLAY = 0xFF1C2330;
            PRESSED = 0xFF1E2836;
            DIVIDER = 0xFF242C39;
            ACCENT = 0xFF4AA8FF;
            ACCENT_SOFT = 0xFF1B2C40;
            TRACK = 0xFF2C3544;
            BUTTON = 0xFF1E2836;
            SUCCESS = 0xFF3ECF8E;
            WARN = 0xFFF5A524;
            DANGER = 0xFFF2545B;
            TEXT = 0xFFF2F5FA;
            TEXT_2 = 0xFF8E9AAE;
            TEXT_3 = 0xFF6B7789;
            GRADIENT_FROM = 0xFF2BD4C4;
            GRADIENT_TO = 0xFF3B82F6;
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

    /** 圆角矩形：填充 + 可选描边。 */
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

    /** 圆形纯色（状态点、颜色 chip）。 */
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
