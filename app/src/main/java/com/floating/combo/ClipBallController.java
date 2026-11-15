package com.floating.combo;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

public class ClipBallController implements View.OnTouchListener {

    public interface Callback {
        void onClipTap();

        void onClipMoved(int x, int y);
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Prefs prefs;
    private final Callback cb;
    private final int slop;

    private ClipBallView view;
    private WindowManager.LayoutParams lp;
    private int screenW, screenH;
    private int sizePx;
    private float downRawX, downRawY, offsetX, offsetY;
    private boolean dragging;

    public ClipBallController(Context c, Prefs p, Callback cb) {
        ctx = c;
        prefs = p;
        this.cb = cb;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        measure();
    }

    private void measure() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        } catch (Throwable ignored) {
            screenW = 1080;
            screenH = 1920;
        }
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public boolean isShown() {
        return view != null && view.getParent() != null;
    }

    public void show() {
        if (isShown()) {
            return;
        }
        if (view != null) {
            view = null;
            lp = null;
        }
        measure();
        sizePx = dp(48);

        view = new ClipBallView(ctx);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                sizePx, sizePx, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;

        int sx, sy;
        if (prefs.hasClipBallPos()) {
            sx = prefs.clipBallX();
            sy = prefs.clipBallY();
        } else {
            sx = dp(8);
            sy = screenH / 2;
        }
        lp.x = clampX(sx);
        lp.y = clampY(sy);

        try {
            wm.addView(view, lp);
        } catch (Throwable t) {
            Log.e("clipball", "第二球加窗口失败：" + t);
            view = null;
            lp = null;
            return;
        }
        view.setOnTouchListener(this);
        Log.i("clipball", "复制朗读球已显示 (" + lp.x + "," + lp.y + ")");
    }

    public void hide() {
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
        }
        view = null;
        lp = null;
        dragging = false;
    }

    public void remeasure() {
        measure();
        if (!isShown()) {
            return;
        }
        lp.x = clampX(lp.x);
        lp.y = clampY(lp.y);
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable ignored) {
        }
    }

    public void setSpeaking(boolean v) {
        if (view != null) {
            view.setSpeaking(v);
        }
    }

    private int clampX(int x) {
        if (x < 0) {
            return 0;
        }
        if (x > screenW - sizePx) {
            return Math.max(0, screenW - sizePx);
        }
        return x;
    }

    private int clampY(int y) {
        if (y < 0) {
            return 0;
        }
        if (y > screenH - sizePx) {
            return Math.max(0, screenH - sizePx);
        }
        return y;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        if (lp == null) {
            return false;
        }
        float fx = e.getRawX();
        float fy = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = fx;
                downRawY = fy;
                offsetX = fx - lp.x;
                offsetY = fy - lp.y;
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragging
                        && (Math.abs(fx - downRawX) > slop
                        || Math.abs(fy - downRawY) > slop)) {
                    dragging = true;
                }
                if (dragging) {
                    lp.x = clampX((int) (fx - offsetX));
                    lp.y = clampY((int) (fy - offsetY));
                    try {
                        wm.updateViewLayout(view, lp);
                    } catch (Throwable ignored) {
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (!cancel && !dragging && cb != null) {
                    cb.onClipTap();
                }
                if (dragging) {
                    lp.x = clampX((int) (fx - offsetX));
                    lp.y = clampY((int) (fy - offsetY));
                    try {
                        wm.updateViewLayout(view, lp);
                    } catch (Throwable ignored) {
                    }
                    if (cb != null) {
                        cb.onClipMoved(lp.x, lp.y);
                    }
                }
                dragging = false;
                return true;
            }

            default:
                return true;
        }
    }
}
