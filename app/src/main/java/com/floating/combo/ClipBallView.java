package com.floating.combo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

public final class ClipBallView extends View {

    private static final int GREEN = 0xFF0F7A4F;
    private static final int GREEN_DEEP = 0xFF0A5A3A;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean speaking = false;

    public ClipBallView(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        shade.setStyle(Paint.Style.FILL);
        glyph.setStyle(Paint.Style.FILL);
        glyph.setColor(Color.WHITE);
        wave.setStyle(Paint.Style.STROKE);
        wave.setColor(Color.WHITE);
        wave.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setSpeaking(boolean v) {
        if (speaking != v) {
            speaking = v;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - Math.max(2f, w * 0.045f);

        shade.setColor(Color.argb(38, 0, 0, 0));
        c.drawCircle(cx, cy + r * 0.06f, r * 1.02f, shade);

        fill.setColor(speaking ? GREEN_DEEP : GREEN);
        c.drawCircle(cx, cy, r, fill);

        float s = (r * 2f * 0.50f) / 24f;
        float ox = cx - 12f * s;
        float oy = cy - 12f * s;

        Path p = new Path();
        p.moveTo(ox + 3f * s, oy + 9f * s);
        p.lineTo(ox + 7f * s, oy + 9f * s);
        p.lineTo(ox + 12f * s, oy + 4f * s);
        p.lineTo(ox + 12f * s, oy + 20f * s);
        p.lineTo(ox + 7f * s, oy + 15f * s);
        p.lineTo(ox + 3f * s, oy + 15f * s);
        p.close();
        c.drawPath(p, glyph);

        wave.setStrokeWidth(1.7f * s);
        RectF a1 = new RectF(ox + 12.5f * s, oy + 8f * s,
                ox + 20.5f * s, oy + 16f * s);
        c.drawArc(a1, -50f, 100f, false, wave);
        RectF a2 = new RectF(ox + 11f * s, oy + 5f * s,
                ox + 23f * s, oy + 19f * s);
        c.drawArc(a2, -50f, 100f, false, wave);
    }
}
