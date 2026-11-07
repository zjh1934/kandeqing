package com.floating.combo;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

public class BallView extends View {

    public static final int ST_NORMAL = BallArt.ST_NORMAL;
    public static final int ST_ACTIVE = BallArt.ST_ACTIVE;
    public static final int ST_PICKING = BallArt.ST_PICKING;
    public static final int ST_SPEAKING = BallArt.ST_SPEAKING;
    public static final int ST_LOADING = BallArt.ST_LOADING;

    private int color = Prefs.COLORS[Prefs.DEF_COLOR_INDEX];
    private float alpha = 1.0f;
    private int state = ST_NORMAL;
    private float drawScale = 1f;

    public BallView(Context c) {
        super(c);
    }

    public void setBallColor(int c) {
        color = c;
        invalidate();
    }

    public void setBallAlpha(float a) {
        alpha = a;
        invalidate();
    }

    public void setDrawScale(float s) {
        if (s <= 0f) {
            s = 1f;
        }
        if (Math.abs(drawScale - s) > 0.001f) {
            drawScale = s;
            invalidate();
        }
    }

    public void setState(int s) {
        if (state != s) {
            state = s;
            if (state == ST_LOADING) {
                postInvalidateDelayed(48);
            }
            invalidate();
        }
    }

    private float effAlpha() {
        float a = alpha;
        if (state == ST_PICKING || state == ST_SPEAKING
                || state == ST_LOADING) {
            a = Math.max(a, 0.94f);
        }
        return Math.max(0.12f, Math.min(1f, a));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float r = Math.min(w, h) / 2f * 0.80f * drawScale;
        BallArt.draw(c, w / 2f, h / 2f, r, color, state, effAlpha());
        if (state == ST_LOADING) {
            postInvalidateDelayed(48);
        }
    }
}
