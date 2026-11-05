package com.floating.combo;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * 球的统一画法：真球（BallView）和界面里的全状态预览（BallPreview）共用这一份，
 * 预览里看到的和屏幕上挂的就是同一个东西，不会出现「图好看、实物不一样」。
 *
 * 结构对齐 iOS「缩放控制器」：一个圆 + 白细边 + 正中一个小圆 +
 * 北 / 南 / 西 / 东四个小三角箭头（像十字架）。颜色可以整块换。
 *
 * 符号颜色按底色亮度自动取深灰或白：默认那颗「苹果白」在白底页面上
 * 也能看清四个箭头，不会糊成一片白。
 */
public final class BallArt {

    /** 业务状态：和 BallView 暴露的四个常量同值，外部代码不用改 */
    public static final int ST_NORMAL = 0;
    public static final int ST_ACTIVE = 1;
    public static final int ST_PICKING = 2;
    public static final int ST_SPEAKING = 3;
    /** 只用于预览：闲置 / 轻点两下 / 放大后拖移 */
    public static final int ST_IDLE = 4;
    public static final int ST_DOUBLE = 5;
    public static final int ST_PAN = 6;

    private static final Paint pBody = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pSym = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint pShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path tri = new Path();

    static {
        pEdge.setStyle(Paint.Style.STROKE);
        pRim.setStyle(Paint.Style.STROKE);
        pRing.setStyle(Paint.Style.STROKE);
        pSym.setStyle(Paint.Style.FILL);
    }

    private BallArt() {
    }

    /**
     * @param color ARGB 底色（用户选的颜色）
     * @param state BallArt.ST_*
     * @param alpha 整体不透明度 0~1，闲置淡出由调用方给
     */
    public static void draw(Canvas c, float cx, float cy, float r,
                            int color, int state, float alpha) {
        if (r <= 1f) {
            return;
        }
        alpha = Math.max(0.12f, Math.min(1f, alpha));
        int base = color & 0xFFFFFF;
        boolean lightOnLight = lum(base) > 168;
        // 符号：白球用深灰，深球用白 —— 任何底色都保证 4.5:1 以上的辨识度
        int sym = lightOnLight ? 0xFF4C5765 : 0xFFFFFFFF;

        float spread = 1f;
        boolean ring = false;
        boolean centerFill = false;
        boolean speaking = false;
        boolean idle = false;
        boolean dbl = false;
        boolean pan = false;
        switch (state) {
            case ST_ACTIVE:
                spread = 1.22f;
                ring = true;
                break;
            case ST_PICKING:
                spread = 0.90f;
                centerFill = true;
                ring = true;
                break;
            case ST_SPEAKING:
                spread = 0.88f;
                speaking = true;
                ring = true;
                break;
            case ST_IDLE:
                idle = true;
                spread = 0.86f;
                break;
            case ST_DOUBLE:
                dbl = true;
                spread = 1.10f;
                break;
            case ST_PAN:
                pan = true;
                spread = 1.16f;
                break;
            default:
                break;
        }

        // 1. 极淡外阴影：白球落在白页面上也有一条边界
        pShadow.setShader(null);
        pShadow.setColor(Color.argb((int) (46 * alpha), 0, 0, 0));
        c.drawCircle(cx, cy + r * 0.05f, r * 1.08f, pShadow);

        // 2. 圆体：上亮下暗的轻渐变，不再是高饱和发光球
        int top = shift(base, 1.07f);
        int bot = shift(base, 0.90f);
        LinearGradient lg = new LinearGradient(cx, cy - r, cx, cy + r,
                new int[]{argb(alpha, top), argb(alpha, bot)},
                null, Shader.TileMode.CLAMP);
        pBody.setShader(lg);
        c.drawCircle(cx, cy, r, pBody);
        pBody.setShader(null);

        // 3. 白细边（苹果那个「白边」）+ 外侧一层淡灰，浅色背景下不丢边界
        pEdge.setColor(Color.argb((int) (225 * alpha), 255, 255, 255));
        pEdge.setStrokeWidth(Math.max(1.5f, r * (ring ? 0.075f : 0.048f)));
        c.drawCircle(cx, cy, r - pEdge.getStrokeWidth() / 2f, pEdge);
        pRim.setColor(Color.argb((int) (110 * alpha), 110, 122, 138));
        pRim.setStrokeWidth(Math.max(1f, r * 0.018f));
        c.drawCircle(cx, cy, r, pRim);

        // 4. 按住 / 点一下 / 朗读中：外环亮起，状态一眼可分
        if (ring) {
            pRing.setColor(withAlpha(sym, (int) (185 * alpha)));
            pRing.setStrokeWidth(Math.max(1.5f, r * 0.075f));
            c.drawCircle(cx, cy, r * 1.13f, pRing);
        }
        // 轻点两下：右侧一道倍率弧
        if (dbl) {
            pRing.setColor(withAlpha(sym, (int) (220 * alpha)));
            pRing.setStrokeWidth(Math.max(2f, r * 0.085f));
            c.drawArc(new android.graphics.RectF(cx - r * 1.20f, cy - r * 1.20f,
                            cx + r * 1.20f, cy + r * 1.20f),
                    -58f, 116f, false, pRing);
        }

        // 5. 四向箭头十字
        int symA = withAlpha(sym, (int) (idle ? 150 * alpha : 245 * alpha));
        moveGlyph(c, cx, cy, r, symA, spread, centerFill, speaking, pan);
    }

    /** 中心小圆 + 四根细杆 + 四个朝外的小三角。 */
    private static void moveGlyph(Canvas c, float cx, float cy, float r,
                                  int sym, float spread, boolean centerFill,
                                  boolean speaking, boolean pan) {
        float cr = r * 0.165f;
        float arm = r * 0.63f * spread;
        float stem = Math.max(1.5f, r * 0.055f);
        float tw = r * 0.145f * spread;   // 三角半宽
        float tl = r * 0.175f * spread;   // 三角高

        pSym.setColor(sym);
        pSym.setStrokeWidth(stem);

        // 十字杆
        pSym.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 4; i++) {
            float dx = (i == 0) ? -1f : (i == 1) ? 1f : 0f;
            float dy = (i == 2) ? -1f : (i == 3) ? 1f : 0f;
            float sx = cx + dx * cr * 1.7f;
            float sy = cy + dy * cr * 1.7f;
            float ex = cx + dx * (arm - tl * 0.85f);
            float ey = cy + dy * (arm - tl * 0.85f);
            c.drawLine(sx, sy, ex, ey, pSym);
        }

        // 四个小三角
        pSym.setStyle(Paint.Style.FILL);
        int[][] dirs = new int[][]{{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] d : dirs) {
            float dx = d[0], dy = d[1];
            float tipX = cx + dx * arm, tipY = cy + dy * arm;
            float bx = cx + dx * (arm - tl), by = cy + dy * (arm - tl);
            tri.reset();
            tri.moveTo(tipX, tipY);
            if (dx != 0) {
                tri.lineTo(bx, by - tw);
                tri.lineTo(bx, by + tw);
            } else {
                tri.lineTo(bx - tw, by);
                tri.lineTo(bx + tw, by);
            }
            tri.close();
            c.drawPath(tri, pSym);
        }

        // 中心小圆：常态空心，点一下填实，朗读中变两竖
        pSym.setStyle(Paint.Style.STROKE);
        pSym.setStrokeWidth(Math.max(1.8f, r * 0.065f));
        if (speaking) {
            float bw = cr * 0.24f;
            float bh = cr * 1.05f;
            pSym.setStyle(Paint.Style.FILL);
            c.drawRect(cx - cr * 0.52f - bw, cy - bh, cx - cr * 0.52f + bw, cy + bh, pSym);
            c.drawRect(cx + cr * 0.52f - bw, cy - bh, cx + cr * 0.52f + bw, cy + bh, pSym);
        } else if (centerFill) {
            pSym.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, cr * 0.92f, pSym);
        } else {
            c.drawCircle(cx, cy, cr, pSym);
        }

        // 放大后拖移：球内底部一条短轨迹
        if (pan) {
            pSym.setStyle(Paint.Style.FILL);
            for (int k = 0; k < 3; k++) {
                float x = cx - r * 0.20f + k * r * 0.20f;
                c.drawCircle(x, cy + r * 0.72f, r * 0.035f, pSym);
            }
        }
    }

    private static int argb(float a, int rgb) {
        int v = (int) (255 * Math.max(0f, Math.min(1f, a)));
        return Color.argb(v, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int withAlpha(int argb, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)),
                Color.red(argb), Color.green(argb), Color.blue(argb));
    }

    private static int shift(int rgb, float f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    public static int lum(int rgb) {
        return (int) (0.299 * ((rgb >> 16) & 0xFF)
                + 0.587 * ((rgb >> 8) & 0xFF)
                + 0.114 * (rgb & 0xFF));
    }
}
