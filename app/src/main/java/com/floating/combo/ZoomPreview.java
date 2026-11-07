package com.floating.combo;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public class ZoomPreview extends FrameLayout {

    private final TextView sample;

    public ZoomPreview(Context c) {
        super(c);
        sample = new TextView(c);
        sample.setText("清晨的阳光落在书页上");
        sample.setTextColor(Theme.TEXT);
        sample.setTextSize(14);
        sample.setGravity(Gravity.CENTER_VERTICAL);
        sample.setSingleLine(true);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL);
        slp.leftMargin = Ui.dp(c, 12);
        slp.rightMargin = Ui.dp(c, 44);
        addView(sample, slp);

        View finger = new View(c);
        int d = Ui.dp(c, 18);
        finger.setBackgroundDrawable(Theme.circle(Theme.TRANSPARENT,
                d / 2f, Theme.ACCENT, Ui.dp(c, 2)));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                d, d, Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        flp.rightMargin = Ui.dp(c, 12);
        addView(finger, flp);
    }

    public void apply(float scale) {
        float k = scale / 2.5f;
        if (k < 0.6f) {
            k = 0.6f;
        }
        sample.setScaleX(k);
        sample.setScaleY(k);
    }
}
