package com.floating.combo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * 颜色选择页里的「全部状态预览」。
 *
 * 一次把球在常态 / 按住 / 点一下 / 朗读中 / 放大后拖移 / 闲置六个样子
 * 全摆出来；点色卡时六个球一起换色，不是只变一个小圆点。
 * 每个球都调 BallArt.draw，和屏幕上真挂着的球是同一套画法。
 */
public class BallPreview extends View {

    private static final int[] STATES = new int[]{
            BallArt.ST_NORMAL, BallArt.ST_ACTIVE, BallArt.ST_PICKING,
            BallArt.ST_SPEAKING, BallArt.ST_PAN, BallArt.ST_IDLE
    };
    private static final String[] LABELS = new String[]{
            "常态", "按住", "点一下", "朗读中", "拖动", "闲置"
    };

    private int color = Prefs.COLORS[Prefs.DEF_COLOR_INDEX];
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BallPreview(Context c) {
        super(c);
        label.setTypeface(Typeface.DEFAULT);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(11f * c.getResources().getDisplayMetrics().scaledDensity);
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
        float cell = w / 6f;
        float labelH = 16f * getResources().getDisplayMetrics().density;
        float cy = (h - labelH) / 2f;
        float r = Math.min(cell * 0.34f, (h - labelH) * 0.36f);
        for (int i = 0; i < STATES.length; i++) {
            float cx = cell * (i + 0.5f);
            float a = (STATES[i] == BallArt.ST_IDLE) ? 0.42f : 1f;
            BallArt.draw(c, cx, cy, r, color, STATES[i], a);
            c.drawText(LABELS[i], cx, h - labelH * 0.28f, label);
        }
    }
}
