package com.floating.combo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * 颜色选择页里的三态预览。
 *
 * 一次把闲置实心 / 点一下空心 / 朗读空心加竖线摆齐；点色卡时三个球一起换色。
 * 每个球都调 BallArt.draw，和屏幕上真挂着的球是同一套画法。
 * 预览里给一层中性底色，只为让白色默认款在浅色界面上也看得见。
 */
public class BallPreview extends View {

    private static final int[] STATES = new int[]{
            BallArt.ST_NORMAL, BallArt.ST_PICKING, BallArt.ST_SPEAKING
    };
    private static final String[] LABELS = new String[]{
            "闲置 · 实心", "点一下 · 空心", "朗读中 · 空心加竖线"
    };

    private int color = Prefs.COLORS[Prefs.DEF_COLOR_INDEX];
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pad = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BallPreview(Context c) {
        super(c);
        label.setTypeface(Typeface.DEFAULT);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(11f * c.getResources().getDisplayMetrics().scaledDensity);
        pad.setColor(0xFF3C4655);
    }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        label.setColor(Theme.TEXT_2);
        float cell = w / 3f;
        float labelH = 16f * getResources().getDisplayMetrics().density;
        float cy = (h - labelH) / 2f;
        float r = Math.min(cell * 0.30f, (h - labelH) * 0.36f);
        float px = 10f * getResources().getDisplayMetrics().density;
        for (int i = 0; i < STATES.length; i++) {
            float cx = cell * (i + 0.5f);
            float half = Math.min(cell * 0.42f, (h - labelH) * 0.46f);
            c.drawRoundRect(cx - half, cy - half, cx + half, cy + half,
                    10f * getResources().getDisplayMetrics().density,
                    10f * getResources().getDisplayMetrics().density, pad);
            // 一小条内容，验证半透明叠底
            label.setColor(0xFF8A97A8);
            c.drawRect(cx - half + px, cy - 3f,
                    cx + half - px, cy + 3f, label);
            BallArt.draw(c, cx, cy, r, color, STATES[i], 1f);
            label.setColor(Theme.TEXT_2);
            c.drawText(LABELS[i], cx, h - labelH * 0.28f, label);
        }
    }
}
