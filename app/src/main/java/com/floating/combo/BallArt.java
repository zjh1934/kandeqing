package com.floating.combo;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * 球的统一画法：真球（BallView）、界面预览（BallPreview）、颜色色卡
 * （Ui.BallChipView）都调这一份，保证看到的就是挂到屏幕上的那个。
 *
 * 3.10 按用户最终方案重做，之前几版全部作废：
 *   · 形态是**半透明矢量感图形**，边缘用径向渐变淡出（模糊柔边），
 *     不是硬描边、不是位图色块、不是四箭头十字。
 *   · 三态是同一份图形的三种 alpha 分布，切态不换素材、不跳形状：
 *       闲置 / 默认  → 半透明柔边环，中间一笔不填，底下内容透得过来，静止
 *       点一下（选字）→ 同一个图形填实，变成实心球
 *       再点（朗读）  → 退回框（还是环，球心仍透明），框里两条同色竖线
 *   · 换颜色只改渐变里的颜色值，环 / 球 / 框 / 竖线全是同一个颜色，
 *     形态完全不变 —— 白色是默认，其他颜色同一套逻辑。
 *
 * 按住放大（ST_ACTIVE）复用「实心」这一态：正在操作 = 填实，
 * 不额外造第四种形态。闲置淡出由调用方给的 alpha 决定。
 *
 * 3.9.2：新增 ST_LOADING —— 点字后到出声前，球里一排小点转圈，
 * 表示「正在找字 / 正在等引擎」，不是呆在那里。仍是同一个柔边环，
 * 只在环内多画转圈的点，不换形态、不换素材。
 */
public final class BallArt {

    /** 业务状态：和 BallView 暴露的四个常量同值，外部代码不用改 */
    public static final int ST_NORMAL = 0;
    public static final int ST_ACTIVE = 1;
    public static final int ST_PICKING = 2;
    public static final int ST_SPEAKING = 3;
    /** 预览用：闲置（与 ST_NORMAL 同形态，只是更淡） */
    public static final int ST_IDLE = 4;
    public static final int ST_DOUBLE = 5;
    public static final int ST_PAN = 6;
    /** 点字后、出声前：环内一排小点转圈 */
    public static final int ST_LOADING = 7;

    private static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        p.setStyle(Paint.Style.FILL);
        pBar.setStyle(Paint.Style.FILL);
    }

    private BallArt() {
    }

    /** 把业务状态收敛到三种形态。 */
    private static int shape(int state) {
        switch (state) {
            case ST_PICKING:
            case ST_ACTIVE:
            case ST_DOUBLE:
            case ST_PAN:
                return 1;   // 实心
            case ST_SPEAKING:
                return 2;   // 框 + 两条竖线
            default:
                return 0;   // 柔边环（闲置 / 常态）
        }
    }

    /**
     * @param color ARGB 颜色（环 / 球 / 框 / 竖线全部用它）
     * @param state BallArt.ST_*
     * @param alpha 整体不透明度 0~1，闲置淡出由调用方给
     */
    public static void draw(Canvas c, float cx, float cy, float r,
                            int color, int state, float alpha) {
        if (r <= 1f) {
            return;
        }
        alpha = Math.max(0.12f, Math.min(1f, alpha));
        int rgb = color & 0xFFFFFF;
        int shape = shape(state);

        // 极淡暗衬：不改变三态形态，只保证白色款在浅色内容上也有轮廓。
        // 这也是「周围渐变模糊」的一部分，不是硬边。
        p.setShader(new RadialGradient(cx, cy, r * 1.10f,
                new int[]{Color.argb((int) (16 * alpha), 0, 0, 0),
                        Color.argb((int) (22 * alpha), 0, 0, 0),
                        Color.argb(0, 0, 0, 0)},
                new float[]{0.35f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.10f, p);

        if (shape == 1) {
            // 实心球：中心平实，只有外缘一圈渐变柔化
            p.setShader(new RadialGradient(cx, cy, r,
                    new int[]{argb(0.90f * alpha, rgb),
                            argb(0.90f * alpha, rgb),
                            argb(0.46f * alpha, rgb),
                            argb(0f, rgb)},
                    new float[]{0f, 0.72f, 0.90f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
        } else {
            // 柔边环：中间全透明，只在环处亮，向内外都渐变淡出
            float ringA = (shape == 2 ? 0.94f : 0.82f) * alpha;
            p.setShader(new RadialGradient(cx, cy, r,
                    new int[]{argb(0f, rgb),
                            argb(0f, rgb),
                            argb(ringA, rgb),
                            argb(ringA * 0.55f, rgb),
                            argb(0f, rgb),
                            argb(0f, rgb)},
                    new float[]{0f, 0.50f, 0.70f, 0.81f, 0.93f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);

            if (shape == 2) {
                // 框里两条同色竖线，带一点柔边，球心仍是透明的
                float bw = r * 0.13f;
                float gap = r * 0.29f;
                float bh = r * 0.46f;
                pBar.setColor(argb(0.95f * alpha, rgb));
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    float x = cx + sgn * gap;
                    c.drawRoundRect(x - bw / 2f, cy - bh,
                            x + bw / 2f, cy + bh,
                            bw / 2f, bw / 2f, pBar);
                }
            }
        }
        p.setShader(null);

        // 加载态：环内一圈小点转圈。必须是纯色画（pBar 从没设过 shader），
        // 且放在 setShader(null) 之后，避免被环的渐变染上。
        if (state == ST_LOADING) {
            drawDots(c, cx, cy, r, rgb, alpha);
        }
    }

    /** 8 个小点绕球心转，亮度按相位拖尾递减，约 1.1 秒一圈 */
    private static void drawDots(Canvas c, float cx, float cy, float r,
                                 int rgb, float alpha) {
        final int N = 8;
        float phase = (SystemClock.uptimeMillis() % 1100L) / 1100f;
        float orbit = r * 0.52f;
        float dot = r * 0.105f;
        for (int i = 0; i < N; i++) {
            float age = (i / (float) N + phase) % 1f;
            float a = (1f - age) * 0.88f + 0.12f;
            double ang = (i / (float) N) * Math.PI * 2
                    - Math.PI / 2 - phase * Math.PI * 2;
            float x = cx + (float) Math.cos(ang) * orbit;
            float y = cy + (float) Math.sin(ang) * orbit;
            pBar.setColor(argb(a * alpha, rgb));
            c.drawCircle(x, y, dot * (0.62f + 0.38f * a), pBar);
        }
    }

    private static int argb(float a, int rgb) {
        int v = (int) (255 * Math.max(0f, Math.min(1f, a)));
        return Color.argb(v, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
