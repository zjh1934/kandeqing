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

/**
 * 唯一的服务：画球 + 抓字 + 控制放大。
 *
 * ── 核心原则：朗读和放大不交叉 ──
 *   放大期间就是放大，不存在选取态，也不朗读。
 *   一进放大，立刻撤掉覆盖层、停掉朗读。
 *   反过来，选取态/朗读中一旦按住要放大，先收干净再放大。
 *
 * 手势：
 *   轻点球     → 进入选取态（绿）→ 点屏幕上的字 → 从那句开始读
 *   朗读中点球 → 停止
 *   按住球     → 放大，手指挪画面跟着走，松手恢复
 *   拖动球     → 挪球
 */
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
    /** 3.8：进选字态就预扫整屏，点字时纯计算命中，抓字延迟接近 0 */
    private TextPicker picker;

    private WindowManager wm;
    private View overlay = null;
    private WindowManager.LayoutParams overlayLp = null;
    private boolean overlayAdded = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    /** 抓字放在后台线程：一屏几千个节点，主线程做会直接 ANR */
    private HandlerThread grabThread;
    private Handler grabH;
    /**
     * 3.9：预扫专用线程。3.8 把 prepare() 和点字兜底 grabFrom() 都投到 grabH，
     * 用户点球后马上点字时，兜底必须排队等整树预扫跑完、自己再遍历一遍，
     * 延迟翻倍。拆开后两条路互不阻塞。
     */
    private HandlerThread prepThread;
    private Handler prepH;
    private long lastReprepareMs = 0L;
    private int state = ST_IDLE;
    private boolean zooming = false;

    /**
     * 抬手后到开始抓字之间的等待。3.5 设成 250ms「等窗口恢复常态」，
     * 每次点字都固定多等四分之一秒；3.6 按维护者要求回到早期版本的「抬手即抓」。
     * 覆盖层是同步撤掉的，不必再等。极少数机型若出现抓到浮层，把它调回 60~120 即可。
     */
    private static final long PICK_DELAY_MS = 0;

    /**
     * 选取态的「代号」。每次状态一变就 +1，用来作废还在排队的抓字任务。
     *
     * 抬手到任务真正跑起来之间用户完全可能又点了球：那一下要么是取消选取、
     * 要么是要放大。作废掉排队任务，否则就出现「点了取消它还在读」。
     */
    private int pickSeq = 0;

    /**
     * 作废排队抓字的那一下，是不是用户自己干的。
     *
     * 只有"不是他干的"才需要专门告诉他 —— 最常见的是抬手后窗口切走了
     * （弹输入法 / 来通知 / 切 App），抓字被静默丢掉，用户点了个字却什么都没发生。
     * 他看不见球变没变色，只能以为软件坏了。
     *
     * 反过来，点球取消（有轻震）、按住放大（有强震）都是他自己按的，
     * 那一刻已经有反馈了，再补一记长震反而像报错，会让人以为操作失败了。
     *
     * 取值时机：作废动作发生时写入，抓字任务跑到时读。全程主线程，无竞态。
     */
    private boolean pickAbortedByUser = true;

    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 球现在真的挂在屏幕上吗。
     * 界面上的"开启/关闭"按钮按它来显示，而不是按设置项 ——
     * 设置是"想不想开"，这里是"实际开没开"，两者经常不一致。
     */
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
                        // 还在念就续命：长文章靠这个一直续下去，不会被硬超时截断
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

    /**
     * 选字态只是"点一下屏幕"的临时状态，不该跨 App 存活。
     * 用户切走 / 锁屏 / 弹系统框时把覆盖层收掉，
     * 否则一层透明窗口会一直挂在那儿吃掉所有触摸。
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || state != ST_PICKING) {
            return;
        }
        int et = event.getEventType();
        if (et == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && getPackageName().contentEquals(pkg)) {
                return;     // 是我们自己的界面（比如打开了 App），不动
            }
            Log.i("pick", "窗口切走了，退出选字态");
            abortPendingGrab(false);  // 不是用户点的，得让他知道这次没读成
            removeOverlay();
            toIdle();
            return;
        }
        // 3.9：滚屏 / 内容变了，旧缓存的 bounds 已经对不上新画面。
        // 不清空缓存（清空会让点字瞬间退回慢兜底），而是节流后重扫覆盖，
        // 期间旧快照仍在，点字不会突然变慢。
        if (et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || et == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || et == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            long now = System.currentTimeMillis();
            if (now - lastReprepareMs < 300) {
                return;
            }
            lastReprepareMs = now;
            scheduleReprepare();
        }
    }

    /** 内容变了：后台重扫一遍，新快照按同一 seq 覆盖旧的 */
    private void scheduleReprepare() {
        if (picker == null || prepH == null) {
            return;
        }
        final int seq = pickSeq;
        prepH.post(new Runnable() {
            @Override
            public void run() {
                if (picker != null) {
                    picker.prepare(seq);
                }
            }
        });
    }

    @Override
    public void onInterrupt() {
    }

    /** 旋转 / 分屏 / 折叠屏展开：球和放大的坐标系都得重算 */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (ball == null) {
            return;
        }
        ball.remeasure();
        if (zoom != null) {
            zoom.setScreen(ball.screenW(), ball.screenH());
        }
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
            // 服务没真正连上时不能自己调 onServiceConnected()，
            // 那是系统回调。这里只做安全初始化。
            initOnce();
            if (ball == null) {
                return;
            }
        }
        ball.show();
        if (ball.lastError() != null) {
            // 加窗口失败，把原因吐出来，别让用户干瞪眼
            toast("球显示失败：" + ball.lastError());
            return;
        }
        ball.applyPrefs();
    }

    /**
     * 从 onServiceConnected 里拆出来，供重复调用。
     * 只在 ball 还没建时真正执行。
     */
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
        if (picker == null) {
            picker = new TextPicker(this);
        }
        if (grabThread == null) {
            grabThread = new HandlerThread("grab");
            grabThread.start();
            grabH = new Handler(grabThread.getLooper());
        }
        if (prepThread == null) {
            prepThread = new HandlerThread("prep");
            prepThread.start();
            prepH = new Handler(prepThread.getLooper());
        }
        if (ball == null) {
            ball = new BallController(this, prefs, zoom, this);
            zoom.setScreen(ball.screenW(), ball.screenH());
        }
    }

    /** 给界面显示当前到底卡在哪一步 */
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
        abortPendingGrab(true);     // 界面里关开关 / 服务重刷，属于用户自己的操作
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

    // ---------- 手势回调 ----------

    /**
     * 按住够久了 —— 要放大。
     * 关键：先把朗读/选取全部收干净，放大期间不干别的。
     */
    @Override
    public void onZoomStart(float fx, float fy) {
        // 触感排在最前面：这是用户唯一的"放大已生效"信号，
        // 排在末尾的话，前面任何一步出问题它就永远震不到
        Haptics.zoom();

        Log.state(stName(state), stName(ST_IDLE), "要放大，先清干净");
        boolean wasSpeaking = (state == ST_SPEAKING);
        stopSpeakingInternal();
        if (wasSpeaking) {
            // 只改状态不够：有的引擎会吞掉 stop()，得补一次兜底
            h.removeCallbacks(forceStopTask);
            h.postDelayed(forceStopTask, 800);
        }
        abortPendingGrab(true);     // 用户自己要放大，已经有 Haptics.zoom() 了
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
        // 这里刻意不震：缩放结束是"回到原状"，不需要再确认一次
        Log.i("zoom", "放大结束，球退回原位");
        zooming = false;
        if (zoom != null) {
            zoom.zoomOut();
        }
        if (ball != null) {
            ball.resetLook();
        }
    }

    /**
     * 轻点球。三态循环：
     *   待命 → 选取态 →（点屏幕上的字）朗读中 →（点球）停止回待命
     *   选取态再点球 = 取消
     */
    @Override
    public void onTap() {
        if (zooming) {
            return;   // 放大中不响应轻点
        }
        // 点球本身就是在改状态，先作废排队中的抓字任务
        //
        // 算"用户自己干的"：不管是取消（下面有 Haptics.tap()）还是停止朗读（有 toast），
        // 这一下都是他主动按的，不该再报一记"没读成"的长震。
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
                // 失败之后原来只能重启 App，这里给一次自愈的机会
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

        // 3.9：① 主线程做一次极浅扫（预算 300 节点），缓存**立刻**可用 ——
        //      用户点球后多快点字都不必等后台，快路径不再 miss；
        //      ② 独立 prepH 做完整多窗口扫描 + 逐字位置预取，覆盖粗快照。
        //      拆线程是关键：3.8 把预扫和点字兜底都投到 grabH，用户快点字时
        //      兜底要排队等整树预扫跑完再自己遍历一遍，延迟翻倍。
        if (picker != null) {
            picker.prepareQuick(pickSeq);
            if (prepH != null) {
                final int seq = pickSeq;
                prepH.post(new Runnable() {
                    @Override
                    public void run() {
                        if (picker != null) {
                            picker.prepare(seq);
                        }
                    }
                });
            }
        }

        // 3.2：进选取态不震。柔边环填实成实心球就是信号（见 Haptics 里"取词全程不震"的说明）
        h.removeCallbacks(pickTimeout);
        h.postDelayed(pickTimeout, 8000);
    }

    @Override
    public void onMoved(int x, int y) {
        if (prefs != null) {
            prefs.setBallPos(x, y);
        }
    }

    // ---------- 朗读 ----------

    private void toIdle() {
        if (state != ST_IDLE) {
            Log.state(stName(state), stName(ST_IDLE), "toIdle");
        }
        h.removeCallbacks(speakWatchdog);
        h.removeCallbacks(pickTimeout);
        h.removeCallbacks(forceStopTask);
        state = ST_IDLE;
        if (ball != null) {
            ball.resetLook();
        }
    }

    /**
     * 朗读看门狗。
     *
     * 球的"框里两条竖线"= 朗读中。这个状态只有引擎回调 onDone 才会退出。
     * 但引擎是会失约的：被系统冻结、语音数据没装、第三方引擎吞掉回调……
     * 一旦不回调，球就永远停在朗读态，用户点它也没用（点了还是在朗读态里打转）。
     * 所以给每次朗读按字数配一个超时，到点强制归位。
     *
     * 估计得宽一点：宁可晚几十秒，也不能把正在读的长文章截断。
     */
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

    /** 按字数和语速估算"这句最多该读多久" */
    private int guardMs(int chars) {
        float rate = prefs.rate();
        if (rate < 0.1f) {
            rate = 1f;
        }
        // 3.3 新增：算超时用的 rate 夹到 2.0 以内。
        //
        // 语速滑杆已经能拉到 3.0，但外部核查确认 —— framework 不做上限 clamp，
        // setSpeechRate(3.0) 返回 SUCCESS，而**引擎实际行为未定义**：
        // 可能真的 3 倍速，也可能内部 clamp 回 2.0 甚至 1.0，
        // 我们从任何回调都感知不到。
        //
        // 若按 3.0 算：超时 = 字数 × 300 / 3.0 = 100ms/字。
        // 可引擎要是实际按 1.0 在念（200~250ms/字），
        // 长文读到 1/3 就被判定超时切断 —— 用户听到的是"念一半停了"。
        // 宁可超时宽松一点，不可误杀。
        if (rate > 2.0f) {
            rate = 2.0f;
        }
        // 300ms/字：中文正常语速 4~5 字/秒，给足余量；
        // 上限 30 分钟只是防"永远不回调"，真正的长文靠 onChunk 每段续命
        long ms = (long) (chars * 300L / rate) + 8000L;
        if (ms > 30L * 60L * 1000L) {
            ms = 30L * 60L * 1000L;
        }
        return (int) ms;
    }

    /** 只读内部状态归位，不碰引擎 */
    private void stopSpeakingInternal() {
        TtsManager.stop();
        state = ST_IDLE;
    }

    /**
     * 停止朗读。
     *
     * 兜底重建（forceStop）只在"确实还在响"时才做，
     * 而且要等久一点 —— 重建引擎期间 isReady() 会是 false，
     * 用户这时点球会被告知"引擎还在初始化"，
     * 感觉就是"切换不丝滑"。所以能不重建就不重建。
     */
    private void stopSpeaking() {
        TtsManager.stop();
        toIdle();
        Haptics.tap();
        h.removeCallbacks(forceStopTask);
        h.postDelayed(forceStopTask, 1500);
    }

    /** 球当前该是什么外观，重建或提到上层后用来还原 */
    private int ballStateForCurrent() {
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
            // 用户正在选字（停完又点了球），这时候重建引擎会让 isReady() 变 false，
            // 他点字就只听到"引擎还没准备好"。往后挪，等他不选了再说。
            if (state == ST_PICKING) {
                h.postDelayed(this, 1500);
                return;
            }
            TtsManager.forceStop();
        }
    };

    /**
     * 选取态超时兜底。
     * 覆盖层是全屏的，只要它还在，用户点哪儿都没反应。
     * 万一系统把 UP 事件吞了（滑动返回、锁屏、来电），必须自己退出来。
     */
    private final Runnable pickTimeout = new Runnable() {
        @Override
        public void run() {
            if (state != ST_PICKING) {
                return;
            }
            Log.w("pick", "选取态超时，强制退出");
            // 超时这一下刻意不惊动用户：8 秒早过去了，他多半已经走神在做别的，
            // 这时候补一记长震只会让人莫名其妙。日志里留痕就够了。
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
        // 上一次"停止"排队的兜底重建会在 1.5 秒后跑：如果这中间用户又点了一句，
        // 它会把刚开始的朗读直接掐掉（release + bind），听着就是"读两个字就没声了"。
        h.removeCallbacks(forceStopTask);

        final int seq = pickSeq;

        // 3.8 快路径：进选字态时已把整屏预扫成纯数据缓存，
        // pick() 只做容差命中 + 拼串，是纯计算，主线程直接调、零 IPC、
        // 零线程切换。点字到出声的延迟从这里降到接近 0。
        if (picker != null) {
            String quick = picker.pick(x, y, seq);
            if (quick != null && quick.trim().length() > 0) {
                Log.i("pick", "预扫命中，主线程直接朗读 | " + picker.diagnose(seq));
                startSpeak(quick, seq);
                return;
            }
            // 只记结构数字，不记内容：真机看一眼就知道是「缓存没好」
            // 还是「扫到了但这段没点中」，不用再猜延迟来自哪一段。
            Log.i("pick", "快路径未命中，走兜底 | " + picker.diagnose(seq));
        }

        // 缓存还没就绪 / 已失效：退回后台整树遍历（独立 grabH，不与预扫排队）
        // 抓字是一屏几千个节点、每个节点好几次跨进程调用，放主线程会 ANR
        grabH.post(new Runnable() {
            @Override
            public void run() {
                String got;
                try {
                    // grabFrom 内部已经兜过 grabAt
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

    /** 抓完回到主线程：复核状态没变，再交给引擎 */
    private void startSpeak(String text, int seq) {
        if (seq != pickSeq || state != ST_PICKING) {
            // 抓字是在后台线程跑的（一屏几千个节点，可能上百毫秒），
            // 这段路比抬手到排队那一小段更长，同样会被窗口切换打断。
            // 原来这里也是静默丢弃 —— 用户点完字要等更久才发现"没读"。
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
            // 3.9：抓不到字必须让人知道。低视力用户看不清 Toast，
            // 所以长震放在最前面（不依赖视觉），文案说清该做什么，
            // 球回待命让状态可见；日志只留结构数字，不记内容。
            Log.w("grab", "什么都没抓到 | "
                    + (picker != null ? picker.diagnose(seq) : "picker=null"));
            Haptics.error();
            toast("这里没读到文字，再点一次");
            toIdle();
            return;
        }
        // 这里只记【字数】，绝不记内容。
        //
        // 日志会被导出成 .md 存进「下载」，界面上还有「复制日志」（见 Log.export /
        // MainActivity 的诊断区）。抓到的东西可能是验证码、金额、药名、私聊，
        // 只要写进来就永久留在手机里，还能被一键复制出去 ——
        // 那跟我们对外说的"不记你读到的任何文字"是直接冲突的。
        //
        // 调试抓字逻辑要看内容的话，临时在 logcat 里打，别写进这个文件。
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
        // 3.2：开始朗读不震。球退回柔边框 + 框里两竖、出声，就是信号
        // 引擎万一不回调，也要能从"框里两竖"里退出来
        h.removeCallbacks(speakWatchdog);
        h.postDelayed(speakWatchdog, guardMs(text.length()));
    }

    /**
     * 界面上的"试听"要占用引擎，先把球这边的朗读停掉，别两边抢。
     *
     * 以前无脑 post：调用方（按钮 onClick，本来就在主线程）紧接着就 speak()，
     * 而 post 出去的要等当前这轮回调走完才轮到 —— 于是先发声、后停止，
     * 试听被自己掐掉，一声都听不见。
     *
     * 已经在主线程就直接干；不在主线程才 post（保持原来的线程安全）。
     */
    public static void stopBallSpeaking() {
        final ComboService s = instance;
        if (s == null) {
            return;
        }
        if (Looper.myLooper() == s.h.getLooper()) {
            // 就在主线程：立刻停，别等
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

    // ---------- 选取态覆盖层 ----------

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
                        // 手指划开了 = 他想滚屏，不是想读这里
                        if (Math.abs(e.getRawX() - downX) > slop
                                || Math.abs(e.getRawY() - downY) > slop) {
                            Log.i("pick", "滑动，取消本次选取");
                            cancelPick();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        // 滑动返回 / 锁屏 / 来电 / 分屏：系统只发 CANCEL 不发 UP。
                        // 老代码只认 UP，一旦 CANCEL 就再也收不到 UP，
                        // 全屏覆盖层会一直挂在那儿，整块屏幕都点不动。
                        Log.w("pick", "触摸被系统取消，退出选字态");
                        cancelPick();
                        return true;

                    case MotionEvent.ACTION_UP: {
                        final int x = (int) e.getRawX();
                        final int y = (int) e.getRawY();
                        // 3.9：抬手立刻一记轻震 ——「这一下收到了」的即时反馈，
                        // 不依赖抓字成功与否。低视力用户不用盯着 Toast 等结果。
                        Haptics.tap();
                        // wm.removeView 是主线程跨进程事务，别让它占着抬手到
                        // readAt 这一段。先入队，Looper 按入队顺序保证它在抓字前执行。
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                removeOverlay();
                            }
                        });
                        final int seq = pickSeq;
                        // 3.6：覆盖层是同步撤掉的，抬手后直接排抓字，不再空等 250ms。
                        // 排队这一小段里球可能又被点了（取消选取 / 要放大），
                        // 那这次屏幕点击就作废，别再读出来。
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (seq != pickSeq || state != ST_PICKING) {
                                    if (pickAbortedByUser) {
                                        Log.i("pick", "用户自己取消了，跳过抓字");
                                    } else {
                                        // 不是他取消的：多半是窗口切走了。
                                        // 必须告诉他 —— 否则"点了字，什么都没发生"。
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

    /** 取消选字：作废排队中的抓字任务，收掉覆盖层，回待命 */
    private void cancelPick() {
        abortPendingGrab(true);
        removeOverlay();
        toIdle();
    }

    /**
     * 作废还在排队的抓字任务。**所有 pickSeq++ 都必须走这里**，
     * 顺带记下"这次是不是用户自己造成的"—— 漏了这一步，
     * 抓字任务就分不清该沉默还是该提醒，又会退回"点了字没反应"。
     *
     * @param byUser true = 用户自己按的（点球取消 / 按住放大 / 界面里关开关），
     *               那些动作各自已有震动或 toast，不该再报一记失败；
     *               false = 环境变的（窗口切走最常见），必须提醒。
     */
    private void abortPendingGrab(boolean byUser) {
        pickSeq++;
        pickAbortedByUser = byUser;
        // 状态一变，上一轮预扫的内容就可能过期（窗口切走 / 用户取消 / 放大）
        if (picker != null) {
            picker.invalidate();
        }
    }

    /**
     * 抓字被作废了，而且不是用户自己造成的 —— 得让他知道。
     *
     * 不提醒的后果很实在：他明明点了那个字，球没变色、没声音、没震动，
     * 三样全无。低视力用户看不清球的状态，只能判定"软件坏了"。
     *
     * 反馈沿用「读不到字」那一记长震（Haptics.error）：语义一致，
     * 都是"这次没读成"，用户不用重新学一种震动。
     */
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
            // 3.8：bringToFront 是 removeView + addView 两次跨进程事务，
            // 旧版同步放在 onTap() 里，点完球要等它做完才能点屏幕 ——
            // 这就是「点球进选字延迟高」剩下的主要固定开销。
            // 挪到下一轮消息循环：覆盖层先就位，用户立刻能点字；
            // 球稍后重挂到上层，期间覆盖层全透明，视觉无损。
            final int frontSeq = pickSeq;
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (!overlayAdded || frontSeq != pickSeq || ball == null) {
                        return;
                    }
                    // 不能走 hide()+show()，那会重建 BallView 把选取态冲掉
                    ball.bringToFront();
                    ball.reapplyState(ballStateForCurrent());
                }
            });
            Log.i("pick", "覆盖层已加，球稍后提到最上层（不再同步阻塞）");
        } catch (Exception e) {
            Log.e("pick", "覆盖层加不上", e);
            toIdle();
            // bringToFront 里是 remove+add，add 失败球就不在窗口上了，
            // 用户会看到"球凭空消失"，这里补一次
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

    // ---------- 保活通知 ----------

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

    /** 状态名，日志里用。静态方法不能加 @Override */
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

    /** 收尾：窗口、引擎、线程、静态引用全部断开 */
    private void teardown() {
        closeAll();
        // 静态的监听器攥着 this，不清掉整个 Service 都回收不了
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
