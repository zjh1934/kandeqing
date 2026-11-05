package com.floating.combo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * QC/PreviewCanvas · 放大页的实时预览画布（160dp 高）。
 *
 * 把「倍率」这个抽象参数变成看得见的结果：调滑块时示例文字同步放大，
 * 不需要「应用」，也不跳转、不弹窗。圆点是模拟手指位置。
 */
public class ZoomPreview extends FrameLayout {

    private final TextView sample;
    private final View finger;

    public ZoomPreview(Context c) {
        super(c);
        setBackgroundDrawable(Theme.rounded(Theme.OVERLAY,
                Ui.dp(c, Theme.CARD_R), 0, 0));

        sample = new TextView(c);
        sample.setText("清晨的阳光\n落在书页上");
        sample.setTextColor(Theme.TEXT);
        sample.setTextSize(16);
        sample.setGravity(Gravity.CENTER);
        sample.setLineSpacing(Ui.dp(c, 5), 1f);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        addView(sample, slp);

        finger = new View(c);
        int d = Ui.dp(c, 22);
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Theme.TRANSPARENT);
        ring.setStroke(Ui.dp(c, 2), Theme.ACCENT);
        ring.setCornerRadius(d / 2f);
        finger.setBackgroundDrawable(ring);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(d, d, Gravity.CENTER);
        flp.leftMargin = Ui.dp(c, 40);
        flp.topMargin = Ui.dp(c, 28);
        addView(finger, flp);

        apply(2.5f);
    }

    /** 倍率变化即时作用于画面：用缩放而非改字号，字号改大会撑破画布。 */
    public void apply(float scale) {
        float k = scale / 2.5f;
        if (k < 0.6f) {
            k = 0.6f;
        }
        sample.setScaleX(k);
        sample.setScaleY(k);
    }
}
