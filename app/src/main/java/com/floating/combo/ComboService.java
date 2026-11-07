package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class ComboService extends AccessibilityService
        implements BallController.Callback {

    private static final int ST_IDLE = 0;
    private static final int ST_PICKING = 1;
    private static final int ST_SPEAKING = 2;

    private static ComboService instance = null;

    private Prefs prefs;
    private BallController ball;
    private ZoomController zoom;
    private TextGrabber grabber;

    private WindowManager wm;
    private View overlay = null;
    private WindowManager.LayoutParams overlayLp = null;
    private boolean overlayAdded = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private HandlerThread grabThread;
    private Handler grabH;
    private int state = ST_IDLE;
    private boolean zooming = false;
    private boolean waitingSpeak = false;

    private static final long PICK_DELAY_MS = 0;

    private int pickSeq = 0;

    private boolean pickAbortedByUser = true;

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean ballShown() {
        return instance != null && instance.ball != null && instance.ball.isShown();
    }

    public static void refresh() {
        if (instance != null) {
            instance.doRefresh();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.init(getApplicationContext());
        Log.i("svc", "无障碍服务已连接");
        initOnce();

        TtsManager.init(getApplicationContext());
        Haptics.init(getApplicationContext(), prefs.haptics());
        TtsManager.setSpeechListener(new TtsManager.SpeechListener() {
            @Override
            public void onChunk() {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (state != ST_SPEAKING) {
                            return;
                        }
                        h.removeCallbacks(speakWatchdog);
                        h.postDelayed(speakWatchdog, 120000);
                    }
                });
            }

            @Override
            public void onDone() {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (state == ST_SPEAKING) {
                            toIdle();
                        }
                    }
                });
            }

            @Override
            public void onError() {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (state == ST_SPEAKING) {
                            toIdle();
                        }
                    }
                });
            }
        });

        startFg();
        if (prefs.ballOn()) {
            Log.i("svc", "开关是开的，准备显示球");
            ball.show();
            if (ball.lastError() != null) {
                Log.e("svc", "球没出来：" + ball.lastError());
            } else if (ball.isShown()) {
                Log.i("svc", "球已显示");
            }
        } else {
            Log.i("svc", "开关是关的，不显示球");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || state != ST_PICKING) {
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg != null && getPackageName().contentEquals(pkg)) {
            return;
        }
        boolean systemUi = isSystemUiEvent(event, pkg);
        Log.i("pick", systemUi ? "系统弹窗，静默退出选字态" : "窗口切走了，退出选字态");
        abortPendingGrab(systemUi);
        removeOverlay();
        toIdle();
    }

    private static boolean isSystemUiEvent(AccessibilityEvent e, CharSequence pkg) {
        String p = (pkg == null) ? "" : pkg.toString();
        if (p.startsWith("com.android.systemui") || p.equals("android")) {
            return true;
        }
        CharSequence cls = e.getClassName();
        String c = (cls == null) ? "" : cls.toString().toLowerCase(java.util.Locale.ROOT);
        return c.contains("inputmethod") || c.contains("notification")
                || c.contains("statusbar") || c.contains("keyguard");
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (ball == null) {
            return;
        }
        ball.remeasure();
        refreshZoomScreen();
        ball.applyPrefs();
    }

    private void doRefresh() {
        if (prefs == null) {
            return;
        }
        Haptics.setEnabled(prefs.haptics());
        if (!prefs.ballOn()) {
            closeAll();
            return;
        }
        if (ball == null) {
            initOnce();
            if (ball == null) {
                return;
            }
        }
        ball.show();
        if (ball.lastError() != null) {
            toast("球显示失败：" + ball.lastError());
            return;
        }
        ball.applyPrefs();
    }

    private void initOnce() {
        if (prefs == null) {
            prefs = new Prefs(this);
        }
        if (wm == null) {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (zoom == null) {
            zoom = new ZoomController(this, prefs);
        }
        if (grabber == null) {
            grabber = new TextGrabber(this);
        }
        if (grabThread == null) {
            grabThread = new HandlerThread("grab");
            grabThread.start();
            grabH = new Handler(grabThread.getLooper());
        }
        if (ball == null) {
            ball = new BallController(this, prefs, zoom, this);
            refreshZoomScreen();
        }
    }

    private void refreshZoomScreen() {
        if (zoom == null || wm == null) {
            return;
        }
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        try {
            wm.getDefaultDisplay().getRealMetrics(dm);
        } catch (Throwable ignored) {
            try {
                wm.getDefaultDisplay().getMetrics(dm);
            } catch (Throwable ignored2) {
                return;
            }
        }
        zoom.setScreen(dm.widthPixels, dm.heightPixels);
    }

    public static String stateText() {
        if (instance == null) {
            return "服务未运行（去系统设置开启无障碍）";
        }
        ComboService s = instance;
        if (s.ball == null) {
            return "服务已运行，但球未初始化";
        }
        if (s.ball.lastError() != null) {
            return "球显示失败：" + s.ball.lastError();
        }
        if (!s.ball.isShown()) {
            return "球未显示" + (s.prefs != null && s.prefs.ballOn() ? "" : "（开关是关的）");
        }
        return "球已显示";
    }

    private void closeAll() {
        stopSpeakingInternal();
        abortPendingGrab(true);
        removeOverlay();
        if (zoom != null) {
            zoom.zoomOut();
        }
        zooming = false;
        state = ST_IDLE;
        if (ball != null) {
            ball.hide();
        }
    }

    @Override
    public void onZoomStart(float fx, float fy) {
        Haptics.zoom();

        Log.state(stName(state), stName(ST_IDLE), "要放大，先清干净");
        boolean wasSpeaking = (state == ST_SPEAKING);
        stopSpeakingInternal();
        if (wasSpeaking) {
            h.removeCallbacks(forceStopTask);
            h.postDelayed(forceStopTask, 800);
        }
        abortPendingGrab(true);
        removeOverlay();
        state = ST_IDLE;
        zooming = true;
        Log.i("zoom", "放大开始 (" + (int) fx + "," + (int) fy + ") 倍率=" + prefs.scale());

        if (zoom != null) {
            zoom.zoomInAt(fx, fy);
        }
        if (ball != null) {
            ball.setState(BallView.ST_ACTIVE);
        }
    }

    @Override
    public void onZoomMove(float fx, float fy) {
        if (zoom != null) {
            zoom.panTo(fx, fy);
        }
    }

    @Override
    public void onZoomEnd() {
        Log.i("zoom", "放大结束，球退回原位");
        zooming = false;
        if (zoom != null) {
            zoom.zoomOut();
        }
        if (ball != null) {
            ball.resetLook();
        }
    }

    @Override
    public void onTap() {
        if (zooming) {
            return;
        }
        abortPendingGrab(true);
        if (state == ST_SPEAKING) {
            Log.i("tts", "朗读中轻点球 → 停止");
            stopSpeaking();
            toast("已停止");
            return;
        }
        if (state == ST_PICKING) {
            Log.i("pick", "选取态再点球 → 取消");
            Haptics.tap();
            removeOverlay();
            toIdle();
            return;
        }
        if (!TtsManager.isReady()) {
            String why = TtsManager.isFailed() ? "初始化失败" : "还在初始化";
            Log.w("tts", "想进选取态但引擎不可用：" + why
                    + " pkg=" + TtsManager.getCurrentPkg());
            if (TtsManager.isFailed()) {
                TtsManager.retryIfFailed();
                toast("语音引擎初始化失败，正在重新连接…");
            } else {
                toast("语音引擎还在初始化，等一两秒");
            }
            Haptics.error();
            return;
        }
        Log.state(stName(state), stName(ST_PICKING), "轻点球");
        state = ST_PICKING;
        if (ball != null) {
            ball.setState(BallView.ST_PICKING);
        }
        addOverlay();
        h.removeCallbacks(pickTimeout);
        h.postDelayed(pickTimeout, 8000);
    }

    @Override
    public void onMoved(int x, int y) {
        if (prefs != null) {
            prefs.setBallPos(x, y);
        }
    }

    private void toIdle() {
        if (state != ST_IDLE) {
            Log.state(stName(state), stName(ST_IDLE), "toIdle");
        }
        h.removeCallbacks(speakWatchdog);
        h.removeCallbacks(pickTimeout);
        h.removeCallbacks(forceStopTask);
        waitingSpeak = false;
        state = ST_IDLE;
        if (ball != null) {
            ball.resetLook();
        }
    }

    private final Runnable speakWatchdog = new Runnable() {
        @Override
        public void run() {
            if (state != ST_SPEAKING) {
                return;
            }
            Log.w("tts", "朗读超时没有回调，强制回到待命");
            TtsManager.stop();
            toIdle();
        }
    };

    private int guardMs(int chars) {
        float rate = prefs.rate();
        if (rate < 0.1f) {
            rate = 1f;
        }
        if (rate > 2.0f) {
            rate = 2.0f;
        }
        long ms = (long) (chars * 300L / rate) + 8000L;
        if (ms > 30L * 60L * 1000L) {
            ms = 30L * 60L * 1000L;
        }
        return (int) ms;
    }

    private void stopSpeakingInternal() {
        TtsManager.stop();
        state = ST_IDLE;
    }

    private void stopSpeaking() {
        TtsManager.stop();
        toIdle();
        Haptics.tap();
        h.removeCallbacks(forceStopTask);
        h.postDelayed(forceStopTask, 1500);
    }

    private int ballStateForCurrent() {
        if (waitingSpeak) {
            return BallView.ST_LOADING;
        }
        switch (state) {
            case ST_PICKING:
                return BallView.ST_PICKING;
            case ST_SPEAKING:
                return BallView.ST_SPEAKING;
            default:
                return BallView.ST_NORMAL;
        }
    }

    private final Runnable forceStopTask = new Runnable() {
        @Override
        public void run() {
            if (state == ST_PICKING) {
                h.postDelayed(this, 1500);
                return;
            }
            TtsManager.forceStop();
        }
    };

    private final Runnable pickTimeout = new Runnable() {
        @Override
        public void run() {
            if (state != ST_PICKING) {
                return;
            }
            Log.w("pick", "选取态超时，强制退出");
            abortPendingGrab(true);
            removeOverlay();
            toIdle();
        }
    };

    private void readAt(final int x, final int y) {
        if (grabber == null || grabH == null) {
            return;
        }
        if (!TtsManager.isReady()) {
            toast("语音引擎还没准备好");
            Haptics.error();
            toIdle();
            return;
        }
        h.removeCallbacks(forceStopTask);

        waitingSpeak = true;
        if (ball != null) {
            ball.setState(BallView.ST_LOADING);
        }

        final int seq = pickSeq;
        grabH.post(new Runnable() {
            @Override
            public void run() {
                String got;
                try {
                    got = grabber.grabFrom(x, y);
                } catch (Throwable t) {
                    Log.e("grab", "抓字抛异常", t);
                    got = null;
                }
                final String text = got;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        startSpeak(text, seq);
                    }
                });
            }
        });
    }

    private void startSpeak(String text, int seq) {
        waitingSpeak = false;
        if (seq != pickSeq || state != ST_PICKING) {
            if (pickAbortedByUser) {
                Log.i("pick", "抓字回来时用户已取消，丢弃");
            } else {
                notifyGrabAborted("抓字途中");
            }
            return;
        }
        if (!TtsManager.isReady()) {
            toast("语音引擎还没准备好");
            Haptics.error();
            toIdle();
            return;
        }
        if (text == null || text.trim().length() == 0) {
            Log.w("grab", "什么都没抓到");
            toast("这里没读到文字");
            Haptics.error();
            toIdle();
            return;
        }
        Log.i("grab", "抓到 " + text.length() + " 字");
        boolean ok = TtsManager.speak(text, prefs.rate(), prefs.pitch());
        if (!ok) {
            toast("朗读失败");
            Haptics.error();
            toIdle();
            return;
        }
        Log.state(stName(state), stName(ST_SPEAKING), "开始朗读 " + text.length() + " 字");
        state = ST_SPEAKING;
        if (ball != null) {
            ball.setState(BallView.ST_SPEAKING);
        }
        h.removeCallbacks(speakWatchdog);
        h.postDelayed(speakWatchdog, guardMs(text.length()));
    }

    public static void stopBallSpeaking() {
        final ComboService s = instance;
        if (s == null) {
            return;
        }
        if (Looper.myLooper() == s.h.getLooper()) {
            if (s.state == ST_SPEAKING) {
                s.stopSpeaking();
            }
            return;
        }
        s.h.post(new Runnable() {
            @Override
            public void run() {
                if (s.state == ST_SPEAKING) {
                    s.stopSpeaking();
                }
            }
        });
    }

    private void createOverlay() {
        if (overlay != null) {
            return;
        }
        overlay = new View(this);
        overlay.setBackgroundColor(Color.parseColor("#00000000"));

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        overlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSPARENT);
        overlayLp.gravity = Gravity.TOP | Gravity.LEFT;
        overlayLp.x = 0;
        overlayLp.y = 0;

        overlay.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private final int slop = ViewConfiguration.get(ComboService.this)
                    .getScaledTouchSlop();

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - downX) > slop
                                || Math.abs(e.getRawY() - downY) > slop) {
                            Log.i("pick", "滑动，取消本次选取");
                            cancelPick();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        Log.w("pick", "触摸被系统取消，退出选字态");
                        cancelPick();
                        return true;

                    case MotionEvent.ACTION_UP: {
                        final int x = (int) e.getRawX();
                        final int y = (int) e.getRawY();
                        removeOverlay();
                        final int seq = pickSeq;
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != pickSeq || state != ST_PICKING) {
                                    if (pickAbortedByUser) {
                                        Log.i("pick", "用户自己取消了，跳过抓字");
                                    } else {
                                        notifyGrabAborted("抬手后抓字前");
                                    }
                                    return;
                                }
                                readAt(x, y);
                            }
                        }, PICK_DELAY_MS);
                        return true;
                    }

                    default:
                        return true;
                }
            }
        });
    }

    private void cancelPick() {
        abortPendingGrab(true);
        removeOverlay();
        toIdle();
    }

    private void abortPendingGrab(boolean byUser) {
        pickSeq++;
        pickAbortedByUser = byUser;
    }

    private void notifyGrabAborted(String where) {
        Log.w("pick", where + "：抓字被作废，不是用户操作的");
        Haptics.error();
        toast("这次没读成，再点一下");
    }

    private void addOverlay() {
        if (overlayAdded || wm == null) {
            return;
        }
        createOverlay();
        try {
            wm.addView(overlay, overlayLp);
            overlayAdded = true;
            if (ball != null) {
                ball.bringToFront();
                ball.reapplyState(ballStateForCurrent());
            }
            Log.i("pick", "覆盖层已加，球保持选取态在最上层");
        } catch (Exception e) {
            Log.e("pick", "覆盖层加不上", e);
            toIdle();
            if (ball != null && !ball.isShown()) {
                ball.show();
            }
        }
    }

    private void removeOverlay() {
        if (overlayAdded && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlayAdded = false;
        }
    }

    private void startFg() {
        try {
            Intent ni = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, ni,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification n;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String id = "combo_ball";
                NotificationChannel ch = new NotificationChannel(
                        id, "看得清", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(ch);
                }
                n = new Notification.Builder(this, id)
                        .setContentTitle("看得清运行中")
                        .setContentText("点球选字朗读 · 按住放大")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentIntent(pi)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setContentTitle("看得清运行中")
                        .setContentText("点球选字朗读 · 按住放大")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentIntent(pi)
                        .build();
            }
            startForeground(1, n);
        } catch (Exception ignored) {
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private static String stName(int s) {
        switch (s) {
            case ST_IDLE:
                return "待命";
            case ST_PICKING:
                return "选取态";
            case ST_SPEAKING:
                return "朗读中";
            default:
                return String.valueOf(s);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    private void teardown() {
        closeAll();
        TtsManager.setSpeechListener(null);
        TtsManager.release();
        if (grabThread != null) {
            grabThread.quit();
            grabThread = null;
            grabH = null;
        }
        instance = null;
    }
}
