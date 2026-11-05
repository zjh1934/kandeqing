package com.floating.combo;

import android.graphics.drawable.GradientDrawable;

/**
 * 看得清 3.7 · Design Tokens。
 *
 * Modern Dark / Calm Tech / System-first：三层表面高程、语义色、字体阶梯、
 * 8pt 栅格、单屏唯一渐变主操作。所有色值只在这里定义一次。
 *
 * 纯展示层 —— 不引用 TTS / 抓字 / 日志 / 手势等任何核心逻辑。
 * 圆角/描边参数一律用 px 传入，由调用方用 dp() 换算。
 *
 * 兼容：旧版本里曾被 MainActivity 引用的常量名（BG/SURFACE/...）保留为别名，
 * 避免任何遗留引用在编译期炸掉；新代码一律用 3.7 的名字。
 */
public final class Theme {

    // ---- 3.1 表面与高程 -------------------------------------------------
    public static final int BASE     = 0xFF0B0E13; // surface/base 页面底
    public static final int RAISED   = 0xFF141922; // surface/raised 卡片
    public static final int OVERLAY  = 0xFF1C2330; // surface/overlay 浮层 / 下拉 / 预览画布
    public static final int PRESSED  = 0xFF222B3A; // surface/pressed 按压态
    public static final int DIVIDER  = 0xFF2A3342; // 卡内极弱分隔（不是组间线）

    // ---- 3.2 语义色 -----------------------------------------------------
    public static final int GRADIENT_FROM = 0xFF2BD4C4; // accent/gradient 起点
    public static final int GRADIENT_TO   = 0xFF3B82F6; // accent/gradient 终点
    public static final int ACCENT        = 0xFF4AA8FF; // accent/solid 选中 / 滑块 / 聚焦环
    public static final int SUCCESS       = 0xFF3ECF8E;
    public static final int WARN          = 0xFFF5A524;
    public static final int DANGER        = 0xFFF2545B;
    public static final int TEXT          = 0xFFF2F5FA; // text/primary
    public static final int TEXT_2        = 0xFF9AA6B8; // text/secondary 对比度 ≥ 4.5:1
    public static final int TEXT_3        = 0xFF6B7789; // text/tertiary 仅单位与脚注
    public static final int TRANSPARENT   = 0x00000000;
    public static final int ON_ACCENT     = 0xFFFFFFFF;

    // ---- 3.4 间距与圆角（dp，调用方换算） --------------------------------
    public static final int GRID      = 4;
    public static final int GAP_GROUP = 32; // 组间距，不画分割线
    public static final int CARD_PAD  = 24;
    public static final int CARD_R    = 20;
    public static final int CTRL_R    = 14;
    public static final int HIT       = 48; // 最小触控目标

    // ---- 3.5 动效（ms） -------------------------------------------------
    public static final int DUR_MICRO    = 120;
    public static final int DUR_STANDARD = 240;
    public static final int DUR_COMPLEX  = 400;

    // ---- 旧名别名：只做兼容，新代码不要用 --------------------------------
    public static final int BG        = BASE;
    public static final int SURFACE   = RAISED;
    public static final int SURFACE_2 = OVERLAY;
    public static final int BORDER    = DIVIDER;
    public static final int TEAL      = GRADIENT_FROM;
    public static final int BLUE      = GRADIENT_TO;

    private Theme() {
    }

    /** 圆角矩形：填充 + 可选描边（strokePx<=0 时不描边）。 */
    public static GradientDrawable rounded(int color, float radiusPx, int strokeColor, float strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0f) {
            g.setStroke((int) (strokePx + 0.5f), strokeColor);
        }
        return g;
    }

    /**
     * 唯一主操作的线性渐变（135°：左上 → 右下）。
     * 配额：单屏只允许出现 1 次；导航选中态、状态标签一律不用渐变。
     */
    public static GradientDrawable brandGradient(float radiusPx) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{GRADIENT_FROM, GRADIENT_TO});
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** 旧调用点保留的别名。 */
    public static GradientDrawable brandGradientPill(float radiusPx) {
        return brandGradient(radiusPx);
    }

    /** 带聚焦环的表面：主控条等需要 TalkBack 焦点可读的容器。 */
    public static GradientDrawable focusSurface(float radiusPx, float strokePx) {
        return rounded(RAISED, radiusPx, ACCENT, strokePx);
    }
}
