package com.floating.combo;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * 球本体。3.10 按用户最终方案：半透明柔边矢量环，三态同构。
 *   ST_NORMAL    闲置 / 默认 —— 半透明柔边环，中间透明
 *   ST_PICKING   点一下选字 —— 填实成实心球
 *   ST_SPEAKING  朗读中 —— 退回框，框里两条同色竖线
 *   ST_ACTIVE    按住放大 —— 复用「实心」这一态
 * 换颜色只改渐变里的颜色值，形态不变。
 *
 * 具体画法统一放在 BallArt，界面里的三态预览和色卡也调同一份。
 *
 * drawScale 仍在：系统全屏放大时悬浮窗会被一起放大，按 1/s 画才能
 * 让球保持设定大小。这段是已验证过的逻辑，未改动。
 */
public class BallView extends View {

    public static final int ST_NORMAL = BallArt.ST_NORMAL;
    public static final int ST_ACTIVE = BallArt.ST_ACTIVE;
    public static final int ST_PICKING = BallArt.ST_PICKING;
    public static final int ST_SPEAKING = BallArt.ST_SPEAKING;

    /** 默认「苹果白」，与 iOS 缩放控制器的白色款一致 */
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
            invalidate();
        }
    }

    private float effAlpha() {
        float a = alpha;
        // 选取 / 朗读是「正在干活」的状态，别被闲置淡出盖掉
        if (state == ST_PICKING || state == ST_SPEAKING) {
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
    }
}
