package com.floating.combo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * QC/PreviewCanvas · 悬浮球页的球预览（120dp 高）。
 *
 * 浅底 / 深底两种场景一起给，避免白色球在浅色页上消失。
 * 大小、颜色、不透明度都随下方设置实时变化。
 */
public class OrbPreview extends FrameLayout {

    private final View ball;
    private final TextView meta;

    private int sizeDp = 56;
    private int color = 0xFF1565C0;
    private float alpha = 1.0f;
    private boolean light = false;
    private String colorName = "海蓝";

    public OrbPreview(Context c) {
        super(c);
        ball = new View(c);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                Ui.dp(c, 56), Ui.dp(c, 56), Gravity.CENTER);
        addView(ball, blp);

        meta = new TextView(c);
        meta.setTextColor(Theme.TEXT_2);
        meta.setTextSize(14);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlp.bottomMargin = Ui.dp(c, 10);
        addView(meta, mlp);

        apply(sizeDp, color, alpha, light, colorName);
    }

    public void apply(int size, int col, float a, boolean lightBg, String name) {
        sizeDp = size;
        color = col;
        alpha = a;
        light = lightBg;
        colorName = name;

        setBackgroundDrawable(Theme.rounded(
                light ? 0xFFEEF2F8 : Theme.BASE,
                Ui.dp(c(), Theme.CARD_R), 0, 0));

        int px = Ui.dp(c(), sizeDp);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ball.getLayoutParams();
        lp.width = px;
        lp.height = px;
        ball.setLayoutParams(lp);

        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px / 2f);
        g.setStroke(Ui.dp(c(), 1), Theme.DIVIDER);
        ball.setBackgroundDrawable(g);
        ball.setAlpha(Math.max(0.2f, Math.min(1f, alpha)));

        meta.setTextColor(light ? 0xFF5B6675 : Theme.TEXT_2);
        meta.setText(sizeDp + "dp · " + colorName
                + " · " + Math.round(alpha * 100) + "%");
    }

    public void toggleLight() {
        apply(sizeDp, color, alpha, !light, colorName);
    }

    public boolean isLight() {
        return light;
    }

    private Context c() {
        return getContext();
    }
}
