package com.floating.combo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 看得清 3.7 · 轻量系统级控制台。
 *
 * 一个常驻的球、三种能力、一处收纳：
 *   [全局主控条] 常驻每页顶部，悬浮球显隐是全局主控，不随 Tab 消失
 *   [朗读] 引擎与音色 / 语速音调 / 试听台
 *   [放大] 实时预览画布 / 倍率 / 手感
 *   [悬浮球] 球预览 / 外观 / 颜色 / 行为 / 其他
 *   [更多] 隐私与安全 / 分享 / 关于 / 支持
 *
 * 全部用 Java 写布局，不用 XML。界面层只负责「把状态显示出来」：
 * 开关逻辑、TTS 引擎加载、抓字、放大、悬浮球手势一律沿用既有实现，
 * 本轮重设计没有改动这些已验证的行为。
 *
 * 文案原则：说结果、说动作、说状态；不解释控件、不出现工程术语、不用双重否定。
 */
public class MainActivity extends Activity
        implements TtsManager.OnInitListener {

    private Prefs prefs;

    // ---- 全局主控条 ------------------------------------------------------
    private View masterCard;
    private Switch swBall;
    private TextView tvMasterStatus;
    private Button bAcc;
    /** 程序在刷开关状态时置 true，避免 setChecked 反过来触发用户操作 */
    private boolean syncSwitch = false;

    // ---- 页面与导航 ------------------------------------------------------
    private FrameLayout host;
    private ScrollView sv;
    private LinearLayout body;
    private final LinearLayout[] pages = new LinearLayout[4];
    private final LinearLayout[] navItems = new LinearLayout[4];
    private int curTab = 0;
    /** 跨重建记住当前 Tab，rebuild() 后恢复，不踢回第一页 */
    private static int savedTab = 0;
    private static final String[] NAV_ICONS = {"♫", "⊕", "◉", "⋯"};
    private static final String[] NAV_LABELS = {"朗读", "放大", "悬浮球", "更多"};

    // ---- 朗读页 ----------------------------------------------------------
    private Spinner spEngine, spVoice;
    private TextView tvEngineState, tvRateHint;
    private TextView tvEngineDetail, tvVoiceDetail;
    private boolean enginesLoaded = false;
    private List<TtsManager.Engine> engines = new ArrayList<TtsManager.Engine>();
    private List<TtsManager.Voice> voices = new ArrayList<TtsManager.Voice>();
    /** 界面上当前显示的是哪个引擎 / 音色，用来识别「程序设置」引起的选择回调 */
    private String shownEngine = null;
    private String shownVoice = null;
    private TextView tvTryState, wave;
    private Button btnTry, btnStop;
    private Runnable tryEnd;

    // ---- 放大页 ----------------------------------------------------------
    private ZoomPreview zoomPreview;
    private TextView tvScaleBig;

    // ---- 悬浮球页 --------------------------------------------------------
    private OrbPreview orbPreview;
    private LinearLayout chipRow;
    private View effectDot;
    private TextView tvIdleNote;
    private Switch swHaptic;
    private TextView tvHapticDiag;
    private int colorIndex = 0;

    // ---- 更多页 ----------------------------------------------------------
    private LinearLayout qrWrap;
    private TextView btnQr;
    private boolean qrOpen = false;

    // ---- 诊断与日志（折叠二级入口） ---------------------------------------
    private TextView tvLog;
    private LinearLayout logBody;
    private TextView btnLogToggle;
    private boolean logOpen = false;
    private boolean logFull = false;

    // ---- 首次引导 --------------------------------------------------------
    private FrameLayout guideOverlay;
    private TextView tvGuideStep, tvGuideTitle, tvGuideDesc;
    private View guideOrb;
    private Button btnGuideNext;
    private int guideStep = 0;

    private static final String WECHAT_ID = "Vssfsg";
    private static final int REQ_SAVE = 72;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        colorIndex = prefs.colorIndex();

        Log.init(getApplicationContext());
        Haptics.init(getApplicationContext(), prefs.haptics());
        Log.i("ui", "主界面打开");

        // 系统栏跟三层表面同色，去掉「被框在黑盒子里」的接缝
        Window win = getWindow();
        win.setStatusBarColor(Theme.BASE);
        win.setNavigationBarColor(Theme.BASE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            win.setNavigationBarDividerColor(Theme.DIVIDER);
        }

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Theme.BASE);

        sv = new ScrollView(this);
        sv.setBackgroundColor(Theme.BASE);
        sv.setFillViewport(true);
        screen.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, Ui.dp(this, 12));
        sv.addView(root);

        // 全局主控条：常驻每页顶部，全 App 唯一实例，不随 Tab 切换消失
        root.addView(masterControl(), Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);
        root.addView(footer(), Ui.lp(this, 0, Theme.GAP_GROUP, 0, 0));

        // 底部固定导航，四格等宽，图标 + 文字双编码
        screen.addView(bottomNav());

        pages[0] = pageRead();
        pages[1] = pageZoom();
        pages[2] = pageBall();
        pages[3] = pageMore();

        host = new FrameLayout(this);
        host.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        switchTab(savedTab);
        setContentView(host);

        if (!prefs.guideDone()) {
            showGuide();
        }

        TtsManager.setListener(this);
        TtsManager.init(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        // 从无障碍设置页回来时系统重绑是异步的，再刷一次免得显示「未运行」
        if (swBall != null) {
            swBall.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshStatus();
                }
            }, 1200);
        }
        if (!enginesLoaded) {
            // 每次 onResume 都重建会 new 一个 TTS 探针并同步 IPC，回到前台会卡一下
            enginesLoaded = true;
            reloadEngines();
        }
    }

    @Override
    protected void onDestroy() {
        TtsManager.setListener(null);
        if (btnTry != null && tryEnd != null) {
            btnTry.removeCallbacks(tryEnd);
        }
        super.onDestroy();
    }

    // =====================================================================
    // 全局主控条 · QC/MasterControl
    // =====================================================================

    /**
     * 替代旧版「看不出能点的灰圆点」：全 App 唯一实例，常驻每页顶部。
     * 右侧标准 Switch + 文字，状态由控件本身表达；下方一行只说服务状态，
     * 不再解释「开关是关的」。
     */
    private View masterControl() {
        LinearLayout c = Ui.card(this);
        c.setBackgroundDrawable(Theme.focusSurface(
                Ui.dp(this, Theme.CARD_R), Ui.dp(this, 2)));
        masterCard = c;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView orb = Ui.text(this, "◯", 26, Theme.ACCENT, false);
        orb.setPadding(0, 0, Ui.dp(this, 12), 0);
        row.addView(orb);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.title(this, "悬浮球"));
        TextView sub = Ui.caption(this, "轻点读字 · 按住放大 · 拖动挪位");
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        swBall = new Switch(this);
        swBall.setShowText(false);
        swBall.setContentDescription("悬浮球开关");
        swBall.setMinHeight(Ui.dp(this, Theme.HIT));
        tintSwitch(swBall);
        swBall.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (syncSwitch) {
                    return;     // 界面在同步状态，不是用户点的
                }
                if (!checked) {
                    prefs.setBallOn(false);
                    ComboService.refresh();
                    postRefresh(b, 250);
                    return;
                }
                // 开：先把「想开」记下来，无障碍一旦打开服务会自己把球挂上
                prefs.setBallOn(true);
                if (!accOn()) {
                    toastFail("球要挂在无障碍服务上，先去把它打开");
                    openAccSettings();
                    postRefresh(b, 250);
                    return;
                }
                if (!ComboService.isRunning()) {
                    toastFail("服务被系统停掉了，去无障碍里关一次再开");
                    openAccSettings();
                    postRefresh(b, 250);
                    return;
                }
                ComboService.refresh();
                postRefresh(b, 350);
            }
        });
        // 开关永远可点：禁用后「点了去设置页」的引导就永远触发不到
        row.addView(swBall);
        c.addView(row);

        View line = Ui.hairline(this);
        c.addView(line, Ui.lp(this, 0, 16, 0, 12));

        tvMasterStatus = Ui.caption(this, "正在检查服务状态…");
        c.addView(tvMasterStatus);

        bAcc = Ui.secondaryButton(this, "去开启无障碍服务");
        bAcc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccSettings();
            }
        });
        bAcc.setVisibility(View.GONE);
        c.addView(bAcc, Ui.lp(this, 0, 12, 0, 0));
        return c;
    }

    /** 系统 Switch 默认用主题青绿色，手动染成 accent/solid。 */
    private void tintSwitch(Switch sw) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        sw.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{Theme.ACCENT, Theme.TEXT_2}));
        sw.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{0x664AA8FF, Theme.OVERLAY}));
    }

    private void openAccSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toastFail("打不开设置页，请手动去 设置→无障碍 里开启");
        }
    }

    /** 延后按事实再刷一次状态：挂窗口、重绑服务都不是同步完成的 */
    private void postRefresh(View v, int ms) {
        v.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, ms);
    }

    private void refreshStatus() {
        boolean acc = accOn();
        boolean running = ComboService.isRunning();
        boolean shown = ComboService.ballShown();
        Log.i("ui", "状态刷新 设置=" + prefs.ballOn() + " 无障碍=" + acc
                + " 服务=" + running + " 球=" + shown);

        // 开关显示「想不想开」(prefs)，实际开没开由下面状态行说。
        // 按事实显示会让球因别的原因没挂上时「来回点都无效」，看着像开关坏了。
        syncSwitch = true;
        swBall.setChecked(acc && prefs.ballOn());
        syncSwitch = false;

        if (masterCard != null) {
            masterCard.setContentDescription("悬浮球，"
                    + (acc && prefs.ballOn() ? "已开启" : "已关闭") + "，双击切换");
        }

        if (!acc) {
            tvMasterStatus.setText("○ 无障碍服务未开启");
            bAcc.setVisibility(View.VISIBLE);
            bAcc.setText("去开启无障碍服务");
        } else if (!running) {
            tvMasterStatus.setText("○ 服务被系统停掉了");
            bAcc.setVisibility(View.VISIBLE);
            bAcc.setText("去无障碍里关一次再开");
        } else {
            tvMasterStatus.setText("● 无障碍服务已就绪");
            bAcc.setVisibility(View.GONE);
            if (prefs.ballOn() && !shown) {
                tvMasterStatus.append(" · 球还没出现，点一下开关重试");
            }
        }
    }

    /** 粗略判断：本包名的服务有没有出现在已启用列表里 */
    private boolean accOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) {
                return false;
            }
            return s.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    // =====================================================================
    // 底部导航
    // =====================================================================

    private View bottomNav() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(Theme.RAISED);

        View line = Ui.hairline(this);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        for (int i = 0; i < NAV_LABELS.length; i++) {
            final int idx = i;
            LinearLayout item = Ui.navItem(this, NAV_ICONS[i], NAV_LABELS[i], false);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(idx);
                }
            });
            item.setContentDescription(NAV_LABELS[i]);
            navItems[i] = item;
            nav.addView(item, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        wrap.addView(nav);
        return wrap;
    }

    private void updateNav() {
        for (int i = 0; i < navItems.length; i++) {
            boolean active = (i == curTab);
            LinearLayout item = navItems[i];
            item.setBackgroundDrawable(Theme.rounded(
                    active ? Theme.OVERLAY : Theme.TRANSPARENT,
                    Ui.dp(this, Theme.CTRL_R), 0, 0));
            TextView icon = (TextView) item.getChildAt(0);
            TextView label = (TextView) item.getChildAt(1);
            icon.setTextColor(active ? Theme.ACCENT : Theme.TEXT_3);
            label.setTextColor(active ? Theme.ACCENT : Theme.TEXT_2);
            label.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void switchTab(int idx) {
        curTab = idx;
        savedTab = idx;
        body.removeAllViews();
        body.addView(pages[idx]);
        updateNav();

        // 四页高度差很大：不回顶部，从长页滚到底再切会落在新页面中段
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.scrollTo(0, 0);
            }
        });

        // 240ms 交叉淡入；系统「减少动画」开启时直接呈现
        if (!reduce()) {
            body.setAlpha(0f);
            body.setTranslationY(Ui.dp(this, 8));
            body.animate().alpha(1f).translationY(0f)
                    .setDuration(Theme.DUR_STANDARD).start();
        } else {
            body.setAlpha(1f);
            body.setTranslationY(0f);
        }
    }

    private boolean reduce() {
        return Ui.reduceMotion(this);
    }

    // =====================================================================
    // 朗读 · Voice
    // =====================================================================

    private LinearLayout pageColumn() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        return p;
    }

    /** 统一样式的下拉框，深色适配交给 Ui.darkAdapter */
    private Spinner spinner() {
        Spinner sp = new Spinner(this);
        sp.setMinimumHeight(Ui.dp(this, Theme.HIT));
        return sp;
    }

    /** 带箭头的盒子：换掉原生背景后，得补一个「可以点」的视觉提示 */
    private View spinnerBox(Spinner sp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY,
                Ui.dp(this, Theme.CTRL_R), 0, 0));
        box.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        box.setMinimumHeight(Ui.dp(this, Theme.HIT));
        sp.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        sp.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        box.addView(sp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = Ui.text(this, "▾", 14, Theme.TEXT_2, false);
        box.addView(arrow);
        return box;
    }

    private LinearLayout pageRead() {
        LinearLayout page = pageColumn();

        // 卡 1：声音
        LinearLayout c1 = Ui.cardSection(this, "声音", "引擎与音色，选好就能用");
        tvEngineState = Ui.statusTag(this, "引擎初始化中…", Theme.TEXT_2);
        c1.addView(tvEngineState, Ui.lp(this, 0, 12, 0, 16));

        c1.addView(Ui.body(this, "语音引擎"), Ui.lp(this, 0, 0, 0, 6));
        spEngine = spinner();
        c1.addView(spinnerBox(spEngine), Ui.lp(this, 0, 0, 0, 16));

        c1.addView(Ui.body(this, "音色"), Ui.lp(this, 0, 0, 0, 6));
        spVoice = spinner();
        c1.addView(spinnerBox(spVoice), Ui.lp(this, 0, 0, 0, 12));

        // 工程信息收进二级折叠，主界面零术语
        tvEngineDetail = Ui.caption(this, "");
        tvEngineDetail.setVisibility(View.GONE);
        c1.addView(tvEngineDetail);
        final TextView engineMore = Ui.tertiaryButton(this, "引擎详情");
        engineMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = tvEngineDetail.getVisibility() != View.VISIBLE;
                tvEngineDetail.setVisibility(show ? View.VISIBLE : View.GONE);
                engineMore.setText("›  " + (show ? "收起引擎详情" : "引擎详情"));
            }
        });
        c1.addView(engineMore);
        tvVoiceDetail = Ui.caption(this, "");
        tvVoiceDetail.setVisibility(View.GONE);
        c1.addView(tvVoiceDetail);
        final TextView voiceMore = Ui.tertiaryButton(this, "音色详情");
        voiceMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = tvVoiceDetail.getVisibility() != View.VISIBLE;
                tvVoiceDetail.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    tvVoiceDetail.setText("当前音色：" + (shownVoice == null ? "默认音色" : shownVoice));
                }
                voiceMore.setText("›  " + (show ? "收起音色详情" : "音色详情"));
            }
        });
        c1.addView(voiceMore);
        page.addView(c1, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 卡 2：语速与音调
        LinearLayout c2 = Ui.cardSection(this, "语速与音调", "拖动即时生效，不用点应用");
        c2.addView(Ui.valueSlider(this, "语速", 50, 300, (int) (prefs.rate() * 100),
                new Fmt() {
                    @Override
                    public String text(int v) {
                        return String.format("%.2f", v / 100f);
                    }
                },
                new OnValue() {
                    @Override
                    public void on(int v) {
                        prefs.setRate(v / 100f);
                        updateRateHint();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setRate(2.0f);
                        rebuild();
                        Ui.iconToast(MainActivity.this, "语速已恢复 2.00");
                    }
                },
                "0.50", "默认 2.00", "3.00",
                "正常 1.00，调得再快部分引擎可能跟不上"),
                Ui.lp(this, 0, 16, 0, 16));

        tvRateHint = Ui.caption(this, "");
        updateRateHint();
        c2.addView(tvRateHint, Ui.lp(this, 0, 0, 0, 16));

        c2.addView(Ui.valueSlider(this, "音调", 50, 200, (int) (prefs.pitch() * 100),
                new Fmt() {
                    @Override
                    public String text(int v) {
                        return String.format("%.2f", v / 100f);
                    }
                },
                new OnValue() {
                    @Override
                    public void on(int v) {
                        prefs.setPitch(v / 100f);
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setPitch(1.0f);
                        rebuild();
                        Ui.iconToast(MainActivity.this, "音调已恢复 1.00");
                    }
                },
                "0.50", "默认 1.00", "2.00",
                "调高声音更亮，调低更沉"));
        page.addView(c2, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 卡 3：试听台 —— 有状态的控制台，不是两个并列按钮
        LinearLayout c3 = Ui.cardSection(this, "试听台", "先听一句，声音正常就可以用了");
        wave = Ui.text(this, "▂▄▆▃▁▄▆▂", 20, Theme.ACCENT, false);
        wave.setGravity(Gravity.CENTER);
        c3.addView(wave, Ui.lp(this, 0, 12, 0, 8));

        tvTryState = Ui.caption(this, "按下试听，听一句就够了");
        tvTryState.setGravity(Gravity.CENTER);
        c3.addView(tvTryState, Ui.lp(this, 0, 0, 0, 16));

        btnTry = Ui.primaryButton(this, "▷  试听一句");
        btnTry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!TtsManager.isReady()) {
                    toastFail("引擎还没准备好，等一两秒");
                    return;
                }
                // 球正在朗读时共用同一个引擎：先同步停掉，否则两段声音会叠在一起
                ComboService.stopBallSpeaking();
                TtsManager.speak("这是一句试听，声音正常就可以用了",
                        prefs.rate(), prefs.pitch());
                setTrying(true);
                if (tryEnd != null) {
                    btnTry.removeCallbacks(tryEnd);
                }
                tryEnd = new Runnable() {
                    @Override
                    public void run() {
                        setTrying(false);
                    }
                };
                btnTry.postDelayed(tryEnd, 4000);
            }
        });
        c3.addView(btnTry);

        btnStop = Ui.secondaryButton(this, "停止");
        btnStop.setVisibility(View.GONE);   // 非播放态不显示停止
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TtsManager.stop();
                if (tryEnd != null) {
                    btnTry.removeCallbacks(tryEnd);
                }
                setTrying(false);
            }
        });
        c3.addView(btnStop, Ui.lp(this, 0, 10, 0, 0));
        page.addView(c3);
        return page;
    }

    private void setTrying(boolean on) {
        if (on) {
            tvTryState.setText("正在朗读这一句…");
            btnTry.setText("正在试听 · ▂▄▆▃");
            btnTry.setEnabled(false);
            btnStop.setVisibility(View.VISIBLE);
            if (!reduce()) {
                wave.animate().alpha(0.4f).setDuration(Theme.DUR_MICRO).start();
            }
        } else {
            tvTryState.setText("按下试听，听一句就够了");
            btnTry.setText("▷  试听一句");
            btnTry.setEnabled(true);
            btnStop.setVisibility(View.GONE);
            wave.setAlpha(1f);
        }
    }

    /** 超过 2.0 的区间引擎不一定买账，当场说结果，不写工程术语 */
    private void updateRateHint() {
        if (tvRateHint == null) {
            return;
        }
        if (prefs.rate() > 2.0f) {
            tvRateHint.setVisibility(View.VISIBLE);
            tvRateHint.setText("调得再快，部分引擎可能跟不上，听着不对就往回调一点");
        } else {
            tvRateHint.setVisibility(View.GONE);
        }
    }

    private void reloadEngines() {
        List<TtsManager.Engine> real = TtsManager.enumerate(this);

        // 第 0 项固定「跟随系统默认」，pkg 用空串表示（与 getSavedEngine 约定一致）。
        // 没这一项时，系统默认会显示成列表第一台引擎的名字，显示与实际对不上。
        engines = new ArrayList<TtsManager.Engine>();
        String def = TtsManager.defaultEnginePkg();
        String defLabel = "";
        for (int i = 0; i < real.size(); i++) {
            TtsManager.Engine e = real.get(i);
            if (e.pkg != null && e.pkg.equals(def)) {
                defLabel = e.name;
                break;
            }
        }
        engines.add(new TtsManager.Engine(
                defLabel.length() > 0 ? "跟随系统默认（" + defLabel + "）" : "跟随系统默认",
                ""));
        engines.addAll(real);

        List<String> names = new ArrayList<String>();
        int sel = 0;
        String saved = TtsManager.getSavedEngine(this);
        for (int i = 0; i < engines.size(); i++) {
            TtsManager.Engine e = engines.get(i);
            names.add(e.name);
            if (saved != null && saved.length() > 0 && saved.equals(e.pkg)) {
                sel = i;
            }
        }
        spEngine.setAdapter(Ui.darkAdapter(this, names));
        spEngine.setSelection(Math.min(sel, names.size() - 1));
        shownEngine = engines.get(Math.min(sel, engines.size() - 1)).pkg;
        spEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (engines == null || pos >= engines.size()) {
                    return;
                }
                String pkg = engines.get(pos).pkg;
                if (pkg.equals(shownEngine)) {
                    return;     // 程序设置引起的，不是用户改的
                }
                shownEngine = pkg;
                if (pkg.equals(TtsManager.getSavedEngine(MainActivity.this))) {
                    return;
                }
                TtsManager.saveEngine(MainActivity.this, pkg);
                TtsManager.rebind(getApplicationContext());
                Ui.toast(MainActivity.this,
                        pkg.length() == 0 ? "正在切回系统默认引擎…" : "正在切换引擎…", true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        reloadVoices();
    }

    private void reloadVoices() {
        voices = TtsManager.listVoicesZhFirst();
        List<String> labels = new ArrayList<String>();
        int sel = 0;
        String saved = TtsManager.getSavedVoice(this);
        for (int i = 0; i < voices.size(); i++) {
            TtsManager.Voice v = voices.get(i);
            labels.add(v.label);
            if (saved != null && saved.length() > 0 && saved.equals(v.name)) {
                sel = i;
            }
        }
        if (labels.isEmpty()) {
            labels.add("默认音色");
        }
        spVoice.setAdapter(Ui.darkAdapter(this, labels));
        spVoice.setSelection(Math.min(sel, labels.size() - 1));
        shownVoice = (voices.isEmpty())
                ? null : voices.get(Math.min(sel, voices.size() - 1)).name;
        spVoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (voices == null || pos >= voices.size()) {
                    return;
                }
                String name = voices.get(pos).name;
                if (name.equals(shownVoice)) {
                    return;     // 程序设置引起的
                }
                shownVoice = name;
                TtsManager.saveVoice(MainActivity.this, name);
                TtsManager.applyVoice(name);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    public void onEngineReady(boolean ready, boolean cnOk, String pkg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (TtsManager.isFailed()) {
                    tvEngineState.setText("引擎初始化失败 · 换一个试试");
                    tvEngineState.setTextColor(Theme.DANGER);
                } else if (!TtsManager.isReady()) {
                    tvEngineState.setText("引擎初始化中…");
                    tvEngineState.setTextColor(Theme.TEXT_2);
                } else {
                    tvEngineState.setText(TtsManager.isCnOk()
                            ? "● 引擎就绪 · 中文可用" : "● 引擎就绪 · 中文可能不支持");
                    tvEngineState.setTextColor(Theme.SUCCESS);
                    if (voices.isEmpty()) {
                        reloadVoices();
                    }
                }
                // 完整包名只放在「引擎详情」里，主界面不出现工程术语
                String req = TtsManager.getCurrentPkg();
                if (req.length() == 0) {
                    String d = TtsManager.defaultEnginePkg();
                    tvEngineDetail.setText("实际请求：系统默认（"
                            + (d.length() == 0 ? "未知" : d) + "）");
                } else {
                    tvEngineDetail.setText("实际请求：" + req);
                }
            }
        });
    }

    // =====================================================================
    // 放大 · Magnify
    // =====================================================================

    private LinearLayout pageZoom() {
        LinearLayout page = pageColumn();

        // 卡 1：实时预览画布 + 倍率，抽象参数变成看得见的结果
        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.caption(this, "实时预览"));
        zoomPreview = new ZoomPreview(this);
        c1.addView(zoomPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 160)));

        tvScaleBig = Ui.display(this, String.format("%.1f×", prefs.scale()));
        tvScaleBig.setGravity(Gravity.CENTER);
        Ui.tnum(tvScaleBig);
        c1.addView(tvScaleBig, Ui.lp(this, 0, 16, 0, 8));

        c1.addView(Ui.valueSlider(this, "倍率", 15, 50, (int) (prefs.scale() * 10),
                new Fmt() {
                    @Override
                    public String text(int v) {
                        return String.format("%.1f×", v / 10f);
                    }
                },
                new OnValue() {
                    @Override
                    public void on(int v) {
                        float s = v / 10f;
                        prefs.setScale(s);
                        zoomPreview.apply(s);
                        tvScaleBig.setText(String.format("%.1f×", s));
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setScale(2.5f);
                        zoomPreview.apply(2.5f);
                        tvScaleBig.setText("2.5×");
                        Ui.iconToast(MainActivity.this, "倍率已恢复 2.5×");
                    }
                },
                "1.5×", "默认 2.5×", "5×",
                "按住球放大，手指挪到哪就看到哪"),
                Ui.lp(this, 0, 0, 0, 0));
        page.addView(c1, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 卡 2：手感
        LinearLayout c2 = Ui.cardSection(this, "手感", "按住久一点才触发，更防误触");
        c2.addView(Ui.valueSlider(this, "触发时长", 120, 600, prefs.holdMs(),
                new Fmt() {
                    @Override
                    public String text(int v) {
                        return v + "ms";
                    }
                },
                new OnValue() {
                    @Override
                    public void on(int v) {
                        prefs.setHoldMs(v);
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setHoldMs(260);
                        rebuild();
                        Ui.iconToast(MainActivity.this, "触发时长已恢复 260ms");
                    }
                },
                "120ms", "默认 260ms", "600ms",
                "调长更不容易误触发，调短放大更灵敏"),
                Ui.lp(this, 0, 16, 0, 16));

        c2.addView(Ui.switchRow(this, "放大时悬浮球跟着变大",
                "关掉后球保持原大小", !prefs.compensate(),
                new OnToggle() {
                    @Override
                    public void on(boolean on) {
                        // compensate=true 表示球不变大，这里正向陈述取反
                        prefs.setCompensate(!on);
                        ComboService.refresh();
                    }
                }), Ui.lp(this, 0, 0, 0, 12));

        TextView resetZoom = Ui.tertiaryButton(this, "恢复默认");
        resetZoom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.confirmSheet(MainActivity.this, "恢复放大的设置",
                        "会把倍率、触发时长、球是否跟着变大一并恢复。\n"
                                + "恢复后仍可随时手动调回。",
                        new Runnable() {
                            @Override
                            public void run() {
                                prefs.setScale(2.5f);
                                prefs.setHoldMs(260);
                                prefs.setCompensate(true);
                                rebuild();
                                Ui.iconToast(MainActivity.this, "放大的设置已恢复默认");
                            }
                        });
            }
        });
        c2.addView(resetZoom);
        page.addView(c2);
        return page;
    }

    // =====================================================================
    // 悬浮球 · Orb
    // =====================================================================

    private LinearLayout pageBall() {
        LinearLayout page = pageColumn();

        // 卡 1：球预览 —— 这一页的主角
        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.caption(this, "预览"));
        orbPreview = new OrbPreview(this);
        c1.addView(orbPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 120)));

        LinearLayout pvRow = new LinearLayout(this);
        pvRow.setOrientation(LinearLayout.HORIZONTAL);
        pvRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView lightBtn = Ui.tertiaryButton(this, "浅底 / 深底切换");
        lightBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                orbPreview.toggleLight();
                Ui.iconToast(MainActivity.this,
                        orbPreview.isLight() ? "已切到浅底" : "已切到深底");
            }
        });
        pvRow.addView(lightBtn);
        c1.addView(pvRow, Ui.lp(this, 0, 8, 0, 0));
        page.addView(c1, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 卡 2：外观
        LinearLayout c2 = Ui.cardSection(this, "外观", "数值调小后，球在闲置时会变淡");
        c2.addView(Ui.valueSlider(this, "大小", 36, 88, prefs.sizeDp(),
                new Fmt() {
                    @Override
                    public String text(int v) {
                        return v + "dp";
                    }
                },
                new OnValue() {
                    @Override
                    public void on(int v) {
                        prefs.setSizeDp(v);
                        ComboService.refresh();
                        updateOrbPreview();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setSizeDp(Prefs.DEF_SIZE_DP);
                        rebuild();
                    }
                },
                "36dp", "默认 56dp", "88dp", null),
                Ui.lp(this, 0, 16, 0, 16));

        c2.addView(Ui.valueSlider(this, "不透明度", 20, 100,
                (int) (prefs.alpha() * 100),
                new Fmt() {
                    @Override
                    public String text(int v) {
                        return v + "%";
                    }
                },
                new OnValue() {
                    @Override
                    public void on(int v) {
                        prefs.setAlpha(v / 100f);
                        ComboService.refresh();
                        updateOrbPreview();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setAlpha(Prefs.DEF_ALPHA);
                        rebuild();
                    }
                },
                "20%", "默认 100%", "100%", null),
                Ui.lp(this, 0, 0, 0, 16));

        c2.addView(Ui.valueSlider(this, "闲置时", 5, 100,
                (int) (prefs.idleAlpha() * 100),
                new Fmt() {
                    @Override
                    public String text(int v) {
                        return v + "%";
                    }
                },
                new OnValue() {
                    @Override
                    public void on(int v) {
                        prefs.setIdleAlpha(v / 100f);
                        ComboService.refresh();
                        updateOrbPreview();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setIdleAlpha(Prefs.DEF_IDLE_ALPHA);
                        rebuild();
                    }
                },
                "5%", "默认 100%", "100%", null));

        tvIdleNote = Ui.caption(this, "数值调小后，闲置时会变淡");
        c2.addView(tvIdleNote, Ui.lp(this, 0, 10, 0, 0));
        page.addView(c2, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 卡 3：颜色 —— 带名称的 token chip + 在当前背景下的效果
        LinearLayout c3 = Ui.cardSection(this, "颜色", "浅底 / 深底都已在上方预览里给出");
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chipRow);
        c3.addView(hs, Ui.lp(this, 0, 12, 0, 12));
        buildChips();

        c3.addView(Ui.caption(this, "在当前背景下的效果"), Ui.lp(this, 0, 0, 0, 6));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        View lightHalf = new View(this);
        lightHalf.setBackgroundColor(0xFFEEF2F8);
        View darkHalf = new View(this);
        darkHalf.setBackgroundColor(Theme.BASE);
        bar.addView(lightHalf, new LinearLayout.LayoutParams(0, Ui.dp(this, 24), 1f));
        bar.addView(darkHalf, new LinearLayout.LayoutParams(0, Ui.dp(this, 24), 1f));

        effectDot = new View(this);
        int ed = Ui.dp(this, 16);
        effectDot.setBackgroundDrawable(Theme.rounded(
                prefs.color(), ed / 2f, Theme.DIVIDER, Ui.dp(this, 1)));
        // 浅底 / 深底对比条 + 居中的当前颜色圆点，一起叠在 FrameLayout 里
        FrameLayout barHost = new FrameLayout(this);
        barHost.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)));
        barHost.addView(effectDot,
                new FrameLayout.LayoutParams(ed, ed, Gravity.CENTER));
        c3.addView(barHost);
        page.addView(c3, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 卡 4：行为
        LinearLayout c4 = Ui.cardSection(this, "行为", null);
        c4.addView(Ui.switchRow(this, "松手后自动贴边", null, prefs.snapEdge(),
                new OnToggle() {
                    @Override
                    public void on(boolean on) {
                        prefs.setSnapEdge(on);
                    }
                }), Ui.lp(this, 0, 12, 0, 8));

        // 震动反馈：状态由 Switch 本身表达，不再用按钮文字写「开/关」
        LinearLayout hapticRowView = Ui.switchRow(this, "震动反馈", null,
                prefs.haptics(),
                new OnToggle() {
                    @Override
                    public void on(boolean on) {
                        prefs.setHaptics(on);
                        Haptics.setEnabled(on);
                        if (on) {
                            Haptics.zoom();
                        }
                        ComboService.refresh();
                    }
                });
        c4.addView(hapticRowView, Ui.lp(this, 0, 0, 0, 8));
        swHaptic = findSwitch(hapticRowView);
        TextView testBtn = Ui.tertiaryButton(this, "试一下");
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String err = Haptics.test();
                String d = Haptics.diag();
                if (err.length() == 0) {
                    tvHapticDiag.setText(d + "\n震动指令已发出。\n"
                            + "如果还是没感觉，查这三处：\n"
                            + "1) 设置→声音与触感→触感反馈（强度别拉到 0）\n"
                            + "2) 应用管理→看得清→通知管理→振动\n"
                            + "3) 关掉省电模式");
                    Ui.iconToast(MainActivity.this, "震动指令已发出，结果写在下面");
                } else {
                    tvHapticDiag.setText(d + "\n失败：" + err + "\n把这行截图发我");
                    toastFail("震动失败：" + err);
                }
            }
        });
        c4.addView(testBtn);
        if (!Haptics.available()) {
            swHaptic.setEnabled(false);
            swHaptic.setAlpha(0.45f);
        }
        tvHapticDiag = Ui.caption(this, Haptics.diag());
        tvHapticDiag.setLineSpacing(Ui.dp(this, 2), 1f);
        c4.addView(tvHapticDiag, Ui.lp(this, 0, 10, 0, 0));
        page.addView(c4, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 卡 5：其他
        LinearLayout c5 = Ui.cardSection(this, "其他", null);
        TextView resetBall = Ui.tertiaryButton(this, "恢复球相关设置");
        resetBall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.confirmSheet(MainActivity.this, "恢复球相关设置",
                        "会把大小、不透明度、闲置淡出、贴边和颜色一并恢复。\n"
                                + "恢复后仍可随时手动调回。",
                        new Runnable() {
                            @Override
                            public void run() {
                                // 一律从 Prefs 的共享常量取，别在这里另写一套数字
                                prefs.setSizeDp(Prefs.DEF_SIZE_DP);
                                prefs.setAlpha(Prefs.DEF_ALPHA);
                                prefs.setIdleAlpha(Prefs.DEF_IDLE_ALPHA);
                                prefs.setSnapEdge(Prefs.DEF_SNAP_EDGE);
                                prefs.setColorIndex(Prefs.DEF_COLOR_INDEX);
                                colorIndex = Prefs.DEF_COLOR_INDEX;
                                ComboService.refresh();
                                rebuild();
                                Ui.iconToast(MainActivity.this, "球的设置已恢复默认");
                            }
                        });
            }
        });
        c5.addView(resetBall, Ui.lp(this, 0, 12, 0, 0));
        c5.addView(logSection(), Ui.lp(this, 0, 12, 0, 0));
        page.addView(c5);
        return page;
    }

    /** 从 switchRow 里把 Switch 取出来，供「拿不到马达」时禁用。 */
    private Switch findSwitch(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v instanceof LinearLayout) {
                LinearLayout sub = (LinearLayout) v;
                for (int j = 0; j < sub.getChildCount(); j++) {
                    if (sub.getChildAt(j) instanceof Switch) {
                        return (Switch) sub.getChildAt(j);
                    }
                }
            }
        }
        return new Switch(this);
    }

    private void buildChips() {
        chipRow.removeAllViews();
        for (int i = 0; i < Prefs.COLORS.length; i++) {
            final int idx = i;
            View chip = Ui.tokenChip(this, Prefs.COLOR_NAMES[i], Prefs.COLORS[i],
                    i == colorIndex, new Runnable() {
                        @Override
                        public void run() {
                            colorIndex = idx;
                            prefs.setColorIndex(idx);
                            buildChips();
                            ComboService.refresh();
                            updateOrbPreview();
                            effectDot.setBackgroundDrawable(Theme.rounded(
                                    Prefs.COLORS[idx], Ui.dp(MainActivity.this, 8),
                                    Theme.DIVIDER, Ui.dp(MainActivity.this, 1)));
                        }
                    });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 10);
            chipRow.addView(chip, clp);
        }
    }

    private void updateOrbPreview() {
        if (orbPreview == null) {
            return;
        }
        orbPreview.apply(prefs.sizeDp(), prefs.color(), prefs.alpha(),
                orbPreview.isLight(), Prefs.COLOR_NAMES[colorIndex]);
    }

    // =====================================================================
    // 更多 · More
    // =====================================================================

    private void shareToFriend() {
        final String url = getString(R.string.share_download_url);
        final String msg = "看得清 —— 手机上的字看不清？按住小球放大，"
                + "点一下屏幕上的字就念给你听。\n"
                + "不联网、不上传，给爸妈装一个。\n"
                + "下载：" + url;
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "看得清 · 给长辈装一个");
            i.putExtra(Intent.EXTRA_TEXT, msg);
            startActivity(Intent.createChooser(i, "推荐给朋友"));
        } catch (Exception e) {
            // 个别机型没有可用分享目标，退一步放进剪贴板
            try {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("看得清下载", msg));
                Ui.toast(this, "分享没打开，内容已复制，粘贴给朋友就行", true);
            } catch (Exception e2) {
                toastFail("分享失败，下载页：" + url);
            }
        }
    }

    private LinearLayout pageMore() {
        LinearLayout page = pageColumn();

        // 隐私信任前置：从页脚小字变成第一屏第一张卡
        LinearLayout c1 = Ui.cardSection(this, "隐私与安全", null);
        TextView trust = Ui.text(this, "◉  本地处理 · 不联网 · 不上传",
                16, Theme.SUCCESS, true);
        c1.addView(trust, Ui.lp(this, 0, 16, 0, 8));
        c1.addView(Ui.caption(this, "屏幕上读到的字只在内存里停留几秒"),
                Ui.lp(this, 0, 0, 0, 16));
        c1.addView(Ui.switchRow(this, "密码框不朗读",
                "打开后不再念出密码框里的内容", prefs.passwordGuard(),
                new OnToggle() {
                    @Override
                    public void on(boolean on) {
                        prefs.setPasswordGuard(on);
                        Ui.iconToast(MainActivity.this,
                                on ? "已开启：密码框不再朗读" : "已关闭：恢复点哪读哪");
                    }
                }));
        page.addView(c1, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 分享
        LinearLayout c2 = Ui.cardSection(this, "分享", null);
        Button share = Ui.secondaryButton(this, "一键推荐给家人朋友");
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareToFriend();
            }
        });
        c2.addView(share, Ui.lp(this, 0, 12, 0, 8));
        TextView copy = Ui.tertiaryButton(this, "复制微信号 " + WECHAT_ID);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("微信号", WECHAT_ID));
                    Haptics.tap();
                    Ui.iconToast(MainActivity.this, "微信号已复制：" + WECHAT_ID);
                } catch (Exception e) {
                    toastFail("复制失败，手动记一下：" + WECHAT_ID);
                }
            }
        });
        c2.addView(copy);
        page.addView(c2, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 关于
        LinearLayout c3 = Ui.cardSection(this, "关于", null);
        TextView ver = Ui.display(this, "看得清 " + versionName());
        c3.addView(ver, Ui.lp(this, 0, 12, 0, 8));
        c3.addView(Ui.caption(this, "不联网、不上传 —— 让界面退到背景，把清晰留给用户。"),
                Ui.lp(this, 0, 0, 0, 12));
        TextView src = Ui.tertiaryButton(this, "查看源码 ↗");
        src.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW,
                            Uri.parse(getString(R.string.share_download_url)));
                    startActivity(i);
                } catch (Exception e) {
                    toastFail("打不开浏览器，地址：github.com/zjh1934/qiuqiu");
                }
            }
        });
        c3.addView(src);
        page.addView(c3, Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        // 支持：二维码默认折叠，点开 240ms 展开，不再一进页面占满半屏
        LinearLayout c4 = Ui.cardSection(this, "支持", null);
        btnQr = Ui.tertiaryButton(this, "联系作者（微信二维码）");
        btnQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                qrOpen = !qrOpen;
                btnQr.setText("›  " + (qrOpen ? "收起二维码" : "联系作者（微信二维码）"));
                if (qrOpen) {
                    qrWrap.setVisibility(View.VISIBLE);
                    if (!reduce()) {
                        qrWrap.setAlpha(0f);
                        qrWrap.animate().alpha(1f)
                                .setDuration(Theme.DUR_STANDARD).start();
                    } else {
                        qrWrap.setAlpha(1f);
                    }
                } else {
                    qrWrap.setVisibility(View.GONE);
                }
            }
        });
        c4.addView(btnQr, Ui.lp(this, 0, 12, 0, 0));

        qrWrap = new LinearLayout(this);
        qrWrap.setOrientation(LinearLayout.VERTICAL);
        qrWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        qrWrap.setVisibility(View.GONE);
        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.wechat_qr);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackgroundDrawable(Theme.rounded(0xFFFFFFFF, Ui.dp(this, 12), 0, 0));
        qr.setContentDescription("作者的微信二维码");
        int qs = Ui.dp(this, 196);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qs, qs);
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        qrWrap.addView(qr, qlp);
        TextView wx = Ui.body(this, "微信号  " + WECHAT_ID);
        wx.setGravity(Gravity.CENTER);
        qrWrap.addView(wx, Ui.lp(this, 0, 16, 0, 0));
        c4.addView(qrWrap, Ui.lp(this, 0, 12, 0, 0));
        page.addView(c4);
        return page;
    }

    // =====================================================================
    // 诊断与日志（折叠二级入口，主界面零术语）
    // =====================================================================

    private LinearLayout logSection() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        View line = Ui.hairline(this);
        c.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));

        btnLogToggle = Ui.tertiaryButton(this, "诊断与日志  ▸");
        btnLogToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logOpen = !logOpen;
                btnLogToggle.setText("›  " + (logOpen ? "诊断与日志  ▾" : "诊断与日志  ▸"));
                logBody.setVisibility(logOpen ? View.VISIBLE : View.GONE);
                if (logOpen) {
                    refreshLog();
                }
            }
        });
        c.addView(btnLogToggle, Ui.lp(this, 0, 12, 0, 0));

        logBody = new LinearLayout(this);
        logBody.setOrientation(LinearLayout.VERTICAL);
        logBody.setVisibility(View.GONE);
        c.addView(logBody);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button bExport = Ui.secondaryButton(this, "导出日志");
        bExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    it.setType("text/markdown");
                    it.putExtra(Intent.EXTRA_TITLE, Log.defaultName());
                    startActivityForResult(it, REQ_SAVE);
                } catch (Exception e) {
                    doExport();
                }
            }
        });
        row.addView(bExport, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button bCopyLog = Ui.secondaryButton(this, "复制日志");
        bCopyLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = Log.content();
                if (t.length() == 0) {
                    toastFail("还没有产生日志");
                    return;
                }
                try {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("看得清日志", t));
                    Haptics.tap();
                    Ui.iconToast(MainActivity.this,
                            "日志已复制（" + t.length() + " 字），可直接粘贴给我");
                } catch (Exception e) {
                    toastFail("复制失败");
                }
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = Ui.dp(this, 6);
        cl.rightMargin = Ui.dp(this, 6);
        row.addView(bCopyLog, cl);

        Button bRefresh = Ui.secondaryButton(this, "刷新");
        bRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLog();
            }
        });
        row.addView(bRefresh, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        logBody.addView(row, Ui.lp(this, 0, 10, 0, 8));

        tvLog = Ui.text(this, "", 11, Theme.TEXT_2, false);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setSingleLine(false);
        logBody.addView(tvLog, Ui.lp(this, 0, 0, 0, 8));

        final Button bMore = Ui.secondaryButton(this, "显示全部");
        bMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logFull = !logFull;
                bMore.setText(logFull ? "只看最近 8 条" : "显示全部");
                refreshLog();
            }
        });
        logBody.addView(bMore, Ui.lp(this, 0, 0, 0, 8));

        Button bClear = Ui.secondaryButton(this, "清空日志");
        bClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.clear();
                refreshLog();
                Ui.iconToast(MainActivity.this, "已清空");
            }
        });
        logBody.addView(bClear);
        return c;
    }

    private void refreshLog() {
        if (tvLog == null) {
            return;
        }
        java.util.ArrayList<String> lines = Log.recent();
        StringBuilder sb = new StringBuilder();
        if (lines.isEmpty()) {
            sb.append("（暂无日志）");
        } else {
            int show = logFull ? Log.MAX_RENDER : 8;
            show = Math.min(show, lines.size());
            for (int k = 0; k < show; k++) {
                sb.append(lines.get(lines.size() - 1 - k));
                if (k < show - 1) {
                    sb.append("\n");
                }
            }
            if (show < lines.size()) {
                sb.append("\n… 共 ").append(lines.size()).append(" 条，仅显示最近 ")
                        .append(show).append(" 条");
            }
        }
        sb.append("\n\n只记手势、状态、错误和字数，不记你读的内容。");
        tvLog.setText(sb.toString());
    }

    // =====================================================================
    // 首次使用：可跳过的 3 步引导
    // =====================================================================

    private void showGuide() {
        guideOverlay = new FrameLayout(this);
        guideOverlay.setBackgroundColor(0xCC05070B);
        guideOverlay.setClickable(true);

        LinearLayout box = Ui.card(this);
        box.setBackgroundColor(Theme.OVERLAY);
        box.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY,
                Ui.dp(this, 24), 0, 0));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        int m = Ui.dp(this, 24);
        blp.leftMargin = m;
        blp.rightMargin = m;

        tvGuideStep = Ui.caption(this, "1 / 3");
        box.addView(tvGuideStep);
        tvGuideTitle = Ui.display(this, "显示球");
        box.addView(tvGuideTitle, Ui.lp(this, 0, 12, 0, 8));
        tvGuideDesc = Ui.body(this, "打开上面的开关，球会一直待在屏幕边上。");
        box.addView(tvGuideDesc, Ui.lp(this, 0, 0, 0, 20));

        guideOrb = new View(this);
        int d = Ui.dp(this, 56);
        guideOrb.setBackgroundDrawable(Theme.rounded(Theme.ACCENT, d / 2f, 0, 0));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(d, d);
        olp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(guideOrb, olp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView skip = Ui.tertiaryButton(this, "跳过");
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setGuideDone();
                host.removeView(guideOverlay);
            }
        });
        actions.addView(skip, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnGuideNext = Ui.primaryButton(this, "下一步");
        btnGuideNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nextGuide();
            }
        });
        actions.addView(btnGuideNext, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(actions, Ui.lp(this, 0, 24, 0, 0));

        guideOverlay.addView(box, blp);
        host.addView(guideOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        updateGuide();
    }

    private void nextGuide() {
        guideStep++;
        if (guideStep >= 3) {
            prefs.setGuideDone();
            host.removeView(guideOverlay);
            return;
        }
        updateGuide();
    }

    private void updateGuide() {
        String[] titles = {"显示球", "轻点读字", "按住放大"};
        String[] descs = {
                "打开上面的开关，球会一直待在屏幕边上。",
                "轻点球变绿，再点屏幕上的那段字，就从这句开始念。",
                "按住球不动放大，手指挪到哪就看到哪，松手还原。"
        };
        tvGuideStep.setText((guideStep + 1) + " / 3");
        tvGuideTitle.setText(titles[guideStep]);
        tvGuideDesc.setText(descs[guideStep]);
        btnGuideNext.setText(guideStep == 2 ? "开始用" : "下一步");
        if (!reduce()) {
            guideOrb.animate().cancel();
            guideOrb.setScaleX(1f);
            guideOrb.setScaleY(1f);
            guideOrb.animate().scaleX(guideStep == 2 ? 1.6f : 1.25f)
                    .scaleY(guideStep == 2 ? 1.6f : 1.25f)
                    .setDuration(1500).start();
        }
    }

    // =====================================================================
    // 通用件
    // =====================================================================

    private void doExport() {
        String p = Log.export(MainActivity.this);
        if (p == null) {
            toastFail("导出失败，可能还没产生日志");
        } else {
            Ui.iconToast(this, "已导出到：" + p);
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_SAVE || result != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        String where = uri.getLastPathSegment();
        if (Log.exportTo(this, uri)) {
            Ui.iconToast(this, "已存到你选的位置 " + (where == null ? "" : where));
        } else {
            toastFail("保存失败，换一个位置试试");
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 重画整个界面，把新设置反映出来 */
    private void rebuild() {
        recreate();
    }

    private View footer() {
        TextView t = Ui.caption(this,
                "看得清 " + versionName() + " · 不联网，不上传");
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void toast(String m) {
        Ui.toast(this, m, true);
    }

    private void toastFail(String m) {
        Ui.toast(this, m, false);
    }

    private boolean isEmpty(CharSequence c) {
        return TextUtils.isEmpty(c);
    }
}
