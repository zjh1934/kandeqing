package com.floating.combo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

public class BallController implements View.OnTouchListener {

    public interface Callback {
        void onZoomStart(float fx, float fy);

        void onZoomMove(float fx, float fy);

        void onZoomEnd();

        void onTap();

        void onMoved(int x, int y);
    }

    private static final int ST_IDLE = 0;
    private static final int ST_PENDING = 1;
    private static final int ST_DRAG_BALL = 2;
    private static final int ST_ZOOMING = 3;
    private static final long IDLE_MS = 2500;

    private final Context ctx;
    private final WindowManager wm;
    private final Prefs prefs;
    private final ZoomController zoom;
    private final Callback cb;
    private final Handler h = new Handler();

    private BallView view = null;
    private WindowManager.LayoutParams lp = null;

    private int state = ST_IDLE;
    private float offsetX, offsetY;
    private float downRawX, downRawY;
    private float lastFx, lastFy;
    private boolean faded = false;

    private int screenW = 0, screenH = 0;
    private ValueAnimator settleAnim = null;

    public BallController(Context c, Prefs p, ZoomController z, Callback cb) {
        ctx = c;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        prefs = p;
        zoom = z;
        this.cb = cb;
        measure();
    }

    public void remeasure() {
        measure();
        if (view == null || lp == null) {
            return;
        }
        lp.x = clampX(lp.x, lp.width);
        lp.y = clampY(lp.y, lp.height);
        commit();
        if (cb != null) {
            cb.onMoved(lp.x, lp.y);
        }
    }

    private void measure() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        } catch (Exception e) {
            screenW = 1080;
            screenH = 1920;
        }
    }

    public int screenW() {
        return screenW;
    }

    public int screenH() {
        return screenH;
    }

    public float centerX() {
        return lp == null ? screenW / 2f : lp.x + lp.width / 2f;
    }

    public float centerY() {
        return lp == null ? screenH / 2f : lp.y + lp.height / 2f;
    }

    public String lastError() {
        return lastErr;
    }

    private String lastErr = null;

    public void show() {
        if (view != null && view.getParent() != null) {
            lastErr = null;
            applyPrefs();
            return;
        }
        if (view != null) {
            Log.w("ball", "view 已脱离窗口，丢弃后重建");
            view = null;
            lp = null;
            Haptics.attach(null);
        }
        measure();
        int size = dp(prefs.sizeDp());
        lastErr = null;

        view = new BallView(ctx);
        Haptics.attach(view);
        view.setBallColor(prefs.color());
        view.setBallAlpha(prefs.alpha());

        int[] types;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            types = new int[]{
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.TYPE_PHONE,
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            types = new int[]{
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.TYPE_PHONE,
            };
        } else {
            types = new int[]{WindowManager.LayoutParams.TYPE_PHONE};
        }

        int sx = prefs.ballX();
        int sy = prefs.ballY();
        if (!prefs.hasBallPos()) {
            sx = screenW - size - dp(8);
            sy = screenH / 3;
        }

        boolean added = false;
        for (int i = 0; i < types.length; i++) {
            lp = new WindowManager.LayoutParams(
                    size, size, types[i],
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = clampX(sx, size);
            lp.y = clampY(sy, size);
            try {
                wm.addView(view, lp);
                added = true;
                Log.i("ball", "加窗口成功 type=" + types[i] + " size=" + size
                        + " at (" + lp.x + "," + lp.y + ")");
                break;
            } catch (Exception e) {
                lastErr = e.getClass().getSimpleName()
                        + ": " + String.valueOf(e.getMessage());
                Log.e("ball", "加窗口失败 type=" + types[i] + " → " + lastErr);
                lp = null;
            }
        }

        if (!added) {
            view = null;
            Log.e("ball", "三种窗口类型全失败，球出不来。最后错误：" + lastErr);
            return;
        }
        view.setOnTouchListener(this);
        scheduleIdle();
    }

    public boolean isShown() {
        return view != null && view.getParent() != null;
    }

    public void bringToFront() {
        if (view == null || lp == null || wm == null) {
            return;
        }
        if (view.getParent() == null) {
            return;
        }
        try {
            wm.removeView(view);
            wm.addView(view, lp);
        } catch (Exception e) {
            Log.e("ball", "球提到上层失败", e);
            view = null;
            lp = null;
            show();
        }
    }

    public void reapplyState(int s) {
        if (view != null) {
            view.setState(s);
        }
    }

    public void hide() {
        cancelAnim();
        h.removeCallbacks(idleTask);
        h.removeCallbacks(holdTask);
        state = ST_IDLE;
        faded = false;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
        }
        view = null;
        lp = null;
        Haptics.attach(null);
    }

    public void applyPrefs() {
        if (view == null || lp == null) {
            return;
        }
        int size = dp(prefs.sizeDp());
        view.setBallColor(prefs.color());
        view.setBallAlpha(faded ? prefs.idleAlpha() : prefs.alpha());
        lp.width = size;
        lp.height = size;
        lp.x = clampX(lp.x, size);
        lp.y = clampY(lp.y, size);
        commit();
    }

    public void setState(int s) {
        if (view != null) {
            view.setState(s);
        }
    }

    public void resetLook() {
        if (view != null) {
            view.setDrawScale(1f);
            view.setState(BallView.ST_NORMAL);
        }
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
                cancelAnim();
                h.removeCallbacks(idleTask);
                if (faded) {
                    faded = false;
                    view.setBallAlpha(prefs.alpha());
                }
                downRawX = fx;
                downRawY = fy;
                lastFx = fx;
                lastFy = fy;
                offsetX = fx - lp.x;
                offsetY = fy - lp.y;
                view.setState(BallView.ST_ACTIVE);
                state = ST_PENDING;
                h.removeCallbacks(holdTask);
                h.postDelayed(holdTask, prefs.holdMs());
                Log.gesture("按下 (" + (int) fx + "," + (int) fy + ")"
                        + " 等待 " + prefs.holdMs() + "ms 判定");
                return true;

            case MotionEvent.ACTION_MOVE:
                lastFx = fx;
                lastFy = fy;

                if (state == ST_PENDING && overSlop(fx - downRawX, fy - downRawY)) {
                    h.removeCallbacks(holdTask);
                    state = ST_DRAG_BALL;
                    Log.gesture("手指动了 → 判定为拖球");
                }

                if (state == ST_DRAG_BALL) {
                    placeBallKeepOffset(fx, fy);
                } else if (state == ST_ZOOMING) {
                    if (cb != null) {
                        cb.onZoomMove(fx, fy);
                    }
                    placeBallAtFinger(fx, fy);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                h.removeCallbacks(holdTask);
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                int st = state;
                state = ST_IDLE;
                Log.gesture("抬手 原状态=" + stName(st)
                        + " 位移=" + (int) Math.sqrt((fx - downRawX) * (fx - downRawX)
                        + (fy - downRawY) * (fy - downRawY)) + "px"
                        + (cancel ? " (被系统取消)" : ""));

                view.setState(BallView.ST_NORMAL);

                if (st == ST_PENDING) {
                    if (!cancel && cb != null) {
                        cb.onTap();
                    }
                } else if (st == ST_ZOOMING) {
                    if (cb != null) {
                        cb.onZoomEnd();
                    }
                }

                view.setDrawScale(1f);

                if (st == ST_DRAG_BALL) {
                    if (prefs.snapEdge()) {
                        animateToEdge((int) (fx - offsetX), (int) (fy - offsetY));
                    } else {
                        lp.x = clampX((int) (fx - offsetX), lp.width);
                        lp.y = clampY((int) (fy - offsetY), lp.height);
                        commit();
                        if (cb != null) {
                            cb.onMoved(lp.x, lp.y);
                        }
                    }
                } else if (st == ST_ZOOMING) {
                    lp.x = clampX((int) (fx - lp.width / 2f), lp.width);
                    lp.y = clampY((int) (fy - lp.height / 2f), lp.height);
                    commit();
                    if (cb != null) {
                        cb.onMoved(lp.x, lp.y);
                    }
                }
                scheduleIdle();
                return true;
            }

            default:
                return false;
        }
    }

    private boolean zooming() {
        return zoom != null && zoom.isOn() && zoom.scale() > 1.01f;
    }

    private void placeBallAtFinger(float fx, float fy) {
        if (lp == null || view == null) {
            return;
        }
        lp.x = (int) (fx - lp.width / 2f);
        lp.y = (int) (fy - lp.height / 2f);
        if (zooming() && prefs.compensate()) {
            view.setDrawScale(1f / zoom.scale());
        } else {
            view.setDrawScale(1f);
        }
        commit();
    }

    private void placeBallKeepOffset(float fx, float fy) {
        if (lp == null || view == null) {
            return;
        }
        lp.x = (int) (fx - offsetX);
        lp.y = (int) (fy - offsetY);
        view.setDrawScale(1f);
        commit();
    }

    private void commit() {
        if (view == null || lp == null || view.getParent() == null) {
            return;
        }
        try {
            wm.updateViewLayout(view, lp);
        } catch (Exception ignored) {
        }
    }

    private void animateToEdge(int fromX, int fromY) {
        cancelAnim();
        final int from = fromX;
        int size = lp.width;
        int to = (from + size / 2 < screenW / 2)
                ? (int) (-size * 0.35f)
                : (int) (screenW - size * 0.65f);

        settleAnim = ValueAnimator.ofInt(from, to);
        settleAnim.setDuration(320);
        settleAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        settleAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (lp == null) {
                    return;
                }
                lp.x = (Integer) a.getAnimatedValue();
                lp.y = clampY(lp.y, lp.height);
                commit();
            }
        });
        settleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cb != null && lp != null) {
                    cb.onMoved(lp.x, lp.y);
                }
            }
        });
        settleAnim.start();
    }

    private void cancelAnim() {
        if (settleAnim != null) {
            try {
                settleAnim.cancel();
            } catch (Exception ignored) {
            }
            settleAnim = null;
        }
    }

    private void scheduleIdle() {
        h.removeCallbacks(idleTask);
        h.postDelayed(idleTask, IDLE_MS);
    }

    private final Runnable holdTask = new Runnable() {
        @Override
        public void run() {
            if (state != ST_PENDING || view == null || lp == null) {
                return;
            }
            state = ST_ZOOMING;
            Log.gesture("按住够久 → 判定为放大");
            if (cb != null) {
                cb.onZoomStart(lastFx, lastFy);
            }
            view.setState(BallView.ST_ACTIVE);
            placeBallAtFinger(lastFx, lastFy);
        }
    };

    private final Runnable idleTask = new Runnable() {
        @Override
        public void run() {
            if (view == null || state != ST_IDLE) {
                return;
            }
            faded = true;
            view.setBallAlpha(prefs.idleAlpha());
        }
    };

    private static String stName(int s) {
        switch (s) {
            case ST_IDLE:
                return "待命";
            case ST_PENDING:
                return "待判定";
            case ST_DRAG_BALL:
                return "拖球";
            case ST_ZOOMING:
                return "放大中";
            default:
                return String.valueOf(s);
        }
    }

    private boolean overSlop(float dx, float dy) {
        int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        return dx * dx + dy * dy > (float) slop * slop;
    }

    private int clampX(int x, int size) {
        int min = (int) (-size * 0.5f);
        int max = screenW - size / 2;
        return Math.max(min, Math.min(max, x));
    }

    private int clampY(int y, int size) {
        return Math.max(0, Math.min(screenH - size, y));
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
