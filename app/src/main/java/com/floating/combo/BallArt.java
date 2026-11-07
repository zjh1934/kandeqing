package com.floating.combo;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;

public final class BallArt {

    public static final int ST_NORMAL = 0;
    public static final int ST_ACTIVE = 1;
    public static final int ST_PICKING = 2;
    public static final int ST_SPEAKING = 3;
    public static final int ST_IDLE = 4;
    public static final int ST_DOUBLE = 5;
    public static final int ST_PAN = 6;
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
                return 1;
            case ST_SPEAKING:
                return 2;
            default:
                return 0;
        }
    }

    public static void draw(Canvas c, float cx, float cy, float r,
                            int color, int state, float alpha) {
        if (r <= 1f) {
            return;
        }
        alpha = Math.max(0.12f, Math.min(1f, alpha));
        int rgb = color & 0xFFFFFF;
        int shape = shape(state);

        p.setShader(new RadialGradient(cx, cy, r * 1.10f,
                new int[]{Color.argb((int) (16 * alpha), 0, 0, 0),
                        Color.argb((int) (22 * alpha), 0, 0, 0),
                        Color.argb(0, 0, 0, 0)},
                new float[]{0.35f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.10f, p);

        if (shape == 1) {
            p.setShader(new RadialGradient(cx, cy, r,
                    new int[]{argb(0.90f * alpha, rgb),
                            argb(0.90f * alpha, rgb),
                            argb(0.46f * alpha, rgb),
                            argb(0f, rgb)},
                    new float[]{0f, 0.72f, 0.90f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
        } else {
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

        if (state == ST_LOADING) {
            drawDots(c, cx, cy, r, rgb, alpha);
        }
    }

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
