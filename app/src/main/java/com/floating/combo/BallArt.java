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
 * 3.9.16 按用户给的四种状态，原样：
 *   闲置                → 实心球
 *   点一下（选字）      → 空心柔边环，球心透明
 *   还没读出来          → 空心环 + 环内 8 个小点转圈
 *   朗读中              → 空心柔边环 + 环里两条同色竖线
 *   按住球放大          → 保持实心，球中心贴着触点
 *
 * 形态只有三种，全是同一份柔边矢量图形的 alpha / 局部笔画变化，
 * 切态不换素材、不跳形状。换颜色只改渐变里的颜色值。
 * 3.9.15 把闲置做成空心、朗读竖线删了，是理解反了，本版改回。
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

    private static int shape(int state) {
        switch (state) {
            case ST_NORMAL:
            case ST_ACTIVE:
            case ST_DOUBLE:
            case ST_PAN:
            case ST_IDLE:
                return 1;   // 实心：闲置 / 按住放大
            case ST_SPEAKING:
                return 2;   // 空心环 + 两条竖线
            default:
                return 0;   // 空心环：点一下选字 / 加载转圈
        }
    }

    /**
     * @param color ARGB 颜色（环 / 球 / 竖线全部用它）
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
                // 朗读中：空心环里两条同色竖线，球心仍然透明
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
