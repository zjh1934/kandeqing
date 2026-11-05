package com.floating.combo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 看得清 3.8 · 紧凑设置页。
 *
 * 按已经确认的 v3 预览稿落地：
 *   - 无页面大标题：底部选项卡已经标了当前页，状态栏下直接是全局主控条
 *   - 一个分组 = 一块圆角表面 + 48dp 行；无说明文字、无注释、无折叠块
 *   - 全 UI 没有 › 二级入口：动作一律右侧圆钮直接执行
 *   - 恢复默认重做：分组内一行 + ↺ 圆钮，点一下当场生效，不弹确认层
 *   - 二维码 + 微信号直接铺开；「查看源码」删除
 *   - 「更多 → 外观」的三段控件真正切换整套深 / 浅配色
 *
 * 界面层只负责显示状态。TTS、抓字、放大、悬浮球手势沿用既有实现，未改动。
 */
public class MainActivity extends Activity
        implements TtsManager.OnInitListener {

    private Prefs prefs;

    // ---- 全局主控条（正常态只有一行） ------------------------------------
    private Switch swBall;
    private TextView tvState;
    private Button bAcc;
    private boolean syncSwitch = false;

    // ---- 页面与导航 ------------------------------------------------------
    private ScrollView sv;
    private LinearLayout body;
    private LinearLayout navRow;
    private final LinearLayout[] pages = new LinearLayout[4];
    private final LinearLayout[] navItems = new LinearLayout[4];
    private int curTab = 0;
    private static int savedTab = 0;
    private static final String[] NAV_LABELS = {"朗读", "放大", "悬浮球", "更多"};
    private static final int[] NAV_GLYPHS = {
            Ui.GlyphView.NAV_READ, Ui.GlyphView.NAV_ZOOM,
            Ui.GlyphView.NAV_ORB, Ui.GlyphView.NAV_MORE
    };

    // ---- 朗读页 ----------------------------------------------------------
    private Ui.SelectRow spEngine, spVoice;
    private Button btnTry;
    private Runnable tryEnd;
    private boolean enginesLoaded = false;
    private List<TtsManager.Engine> engines = new ArrayList<TtsManager.Engine>();
    private List<TtsManager.Voice> voices = new ArrayList<TtsManager.Voice>();
    private String shownEngine = null;
    private String shownVoice = null;

    // ---- 放大 / 悬浮球页 --------------------------------------------------
    private ZoomPreview zoomPreview;
    private BallPreview ballPreview;
    private int colorIndex = 0;

    private static final String WECHAT_ID = "Vssfsg";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        colorIndex = prefs.colorIndex();
        // 必须在任何 View 被创建之前换好整套配色。
        // 默认跟随系统：系统浅色就浅色、深色就中性深色；手动选深色/浅色也照此。
        // setTheme 让 Spinner / Switch / Toast 这些原生控件也跟着走，
        // 否则系统浅色时手动切深色，原生控件还是白底黑字。
        Theme.apply(prefs.themeMode(), systemNight());
        setTheme(darkMode() ? R.style.AppThemeDark : R.style.AppTheme);

        Log.init(getApplicationContext());
        Haptics.init(getApplicationContext(), prefs.haptics());
        Log.i("ui", "主界面打开");

        setupWindow();

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
        int p = Ui.dp(this, 12);
        root.setPadding(p, Ui.dp(this, 8), p, p);
        sv.addView(root);

        root.addView(masterControl(), Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP));

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);

        screen.addView(bottomNav());

        pages[0] = pageRead();
        pages[1] = pageZoom();
        pages[2] = pageBall();
        pages[3] = pageMore();

        switchTab(savedTab);
        setContentView(screen);

        TtsManager.setListener(this);
        TtsManager.init(getApplicationContext());
    }

    /** 深 / 浅模式下系统栏与状态栏图标也得跟着换，不然浅底白字看不见。 */
    private void setupWindow() {
        Window win = getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Theme.BASE));
        win.setStatusBarColor(Theme.BASE);
        win.setNavigationBarColor(Theme.BASE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            win.setNavigationBarDividerColor(Theme.DIVIDER);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = win.getDecorView();
            int flags = decor.getSystemUiVisibility();
            boolean light = !darkMode();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private boolean systemNight() {
        int m = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private boolean darkMode() {
        int m = prefs.themeMode();
        return m == Theme.MODE_DARK
                || (m == Theme.MODE_SYSTEM && systemNight());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (swBall != null) {
            swBall.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshStatus();
                }
            }, 1200);
        }
        if (!enginesLoaded) {
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
    // 全局主控条
    // =====================================================================

    private View masterControl() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundDrawable(Theme.rounded(Theme.RAISED,
                Ui.dp(this, Theme.CARD_R), 0, 0));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(this, Theme.HIT)));
        int pad = Ui.dp(this, 12);
        row.setPadding(pad, 0, pad, 0);

        View dot = new View(this);
        int ds = Ui.dp(this, 10);
        dot.setBackgroundDrawable(Theme.circle(Theme.SUCCESS,
                Ui.dp(this, 5), 0, 0));
        row.addView(dot, new LinearLayout.LayoutParams(ds, ds));

        TextView t = Ui.body(this, "悬浮球");
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        swBall = new Switch(this);
        swBall.setShowText(false);
        swBall.setContentDescription("悬浮球开关");
        swBall.setMinHeight(Ui.dp(this, Theme.HIT));
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        swBall.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{Theme.ACCENT, Theme.TEXT_2}));
        swBall.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{Theme.ACCENT_SOFT, Theme.TRACK}));
        swBall.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (syncSwitch) {
                    return;
                }
                if (!checked) {
                    prefs.setBallOn(false);
                    ComboService.refresh();
                    postRefresh(b, 250);
                    return;
                }
                prefs.setBallOn(true);
                if (!accOn()) {
                    Ui.toast(MainActivity.this,
                            "球要挂在无障碍服务上，先去把它打开", false);
                    openAccSettings();
                    postRefresh(b, 250);
                    return;
                }
                if (!ComboService.isRunning()) {
                    Ui.toast(MainActivity.this,
                            "服务被系统停掉了，去无障碍里关一次再开", false);
                    openAccSettings();
                    postRefresh(b, 250);
                    return;
                }
                ComboService.refresh();
                postRefresh(b, 350);
            }
        });
        row.addView(swBall);
        c.addView(row);

        // 这两项只在服务异常时出现：正常态整条主控就是一行，没有解释文字
        tvState = Ui.caption(this, "");
        tvState.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 4));
        tvState.setVisibility(View.GONE);
        c.addView(tvState);

        bAcc = new Button(this);
        bAcc.setAllCaps(false);
        bAcc.setTextSize(13);
        bAcc.setTextColor(Theme.ON_ACCENT);
        bAcc.setBackgroundDrawable(Theme.rounded(Theme.ACCENT,
                Ui.dp(this, Theme.CTRL_R), 0, 0));
        bAcc.setMinimumHeight(Ui.dp(this, 40));
        bAcc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccSettings();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(pad, 0, pad, Ui.dp(this, 10));
        bAcc.setVisibility(View.GONE);
        c.addView(bAcc, blp);
        return c;
    }

    private void openAccSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Ui.toast(this, "打不开设置页，请手动去 设置→无障碍 里开启", false);
        }
    }

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
        Log.i("ui", "状态刷新 设置=" + prefs.ballOn() + " 无障碍=" + acc
                + " 服务=" + running);

        // 开关显示「想不想开」，实际开没开由异常态那一行说
        syncSwitch = true;
        swBall.setChecked(acc && prefs.ballOn());
        syncSwitch = false;

        if (acc && running) {
            tvState.setVisibility(View.GONE);
            bAcc.setVisibility(View.GONE);
            return;
        }
        tvState.setVisibility(View.VISIBLE);
        bAcc.setVisibility(View.VISIBLE);
        if (!acc) {
            tvState.setText("○ 无障碍服务未开启");
            bAcc.setText("去开启无障碍服务");
        } else {
            tvState.setText("○ 服务被系统停掉了");
            bAcc.setText("去无障碍里关一次再开");
        }
    }

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

        View line = new View(this);
        line.setBackgroundColor(Theme.DIVIDER);
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, Ui.dp(this, 1))));

        navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(Ui.dp(this, 8), Ui.dp(this, 6),
                Ui.dp(this, 8), Ui.dp(this, 6));
        rebuildNav();
        wrap.addView(navRow);
        return wrap;
    }

    private void rebuildNav() {
        navRow.removeAllViews();
        for (int i = 0; i < NAV_LABELS.length; i++) {
            final int idx = i;
            LinearLayout item = Ui.navItem(this, NAV_GLYPHS[i],
                    NAV_LABELS[i], i == curTab);
            item.setContentDescription(NAV_LABELS[i]);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(idx);
                }
            });
            navItems[i] = item;
            navRow.addView(item, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void switchTab(int idx) {
        curTab = idx;
        savedTab = idx;
        body.removeAllViews();
        body.addView(pages[idx]);
        rebuildNav();
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.scrollTo(0, 0);
            }
        });
        if (!Ui.reduceMotion(this)) {
            body.setAlpha(0f);
            body.setTranslationY(Ui.dp(this, 6));
            body.animate().alpha(1f).translationY(0f)
                    .setDuration(Theme.DUR_STANDARD).start();
        } else {
            body.setAlpha(1f);
            body.setTranslationY(0f);
        }
    }

    private LinearLayout pageColumn() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        return p;
    }

    private LinearLayout.LayoutParams groupLp() {
        return Ui.lp(this, 0, 0, 0, Theme.GAP_GROUP);
    }

    // =====================================================================
    // 朗读
    // =====================================================================

    private LinearLayout pageRead() {
        LinearLayout page = pageColumn();

        spEngine = Ui.selectRow(this, "语音引擎");
        spVoice = Ui.selectRow(this, "音色");
        page.addView(Ui.group(this, "声音", spEngine, spVoice), groupLp());

        page.addView(Ui.group(this, "调节",
                Ui.sliderRow(this, "语速", 50, 300,
                        (int) (prefs.rate() * 100),
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
                            }
                        }),
                Ui.sliderRow(this, "音调", 50, 200,
                        (int) (prefs.pitch() * 100),
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
                        })), groupLp());

        btnTry = Ui.primaryButton(this, "试听一句");
        btnTry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!TtsManager.isReady()) {
                    Ui.toast(MainActivity.this, "引擎还没准备好，等一两秒", false);
                    return;
                }
                // 球正在朗读时共用同一个引擎：先同步停掉，否则两段声音会叠
                ComboService.stopBallSpeaking();
                TtsManager.speak("这是一句试听，声音正常就可以用了",
                        prefs.rate(), prefs.pitch());
                btnTry.setEnabled(false);
                btnTry.setText("正在试听…");
                if (tryEnd != null) {
                    btnTry.removeCallbacks(tryEnd);
                }
                tryEnd = new Runnable() {
                    @Override
                    public void run() {
                        btnTry.setEnabled(true);
                        btnTry.setText("试听一句");
                    }
                };
                btnTry.postDelayed(tryEnd, 4000);
            }
        });
        page.addView(btnTry, Ui.lp(this, 0, 0, 0, 0));
        return page;
    }

    private void reloadEngines() {
        List<TtsManager.Engine> real = TtsManager.enumerate(this);
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
        // 空串 = 跟随系统默认，必须有这一项，否则显示与实际用的引擎对不上
        engines.add(new TtsManager.Engine(
                defLabel.length() > 0
                        ? "跟随系统默认（" + defLabel + "）" : "跟随系统默认", ""));
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
        spEngine.setItems(names);
        spEngine.setSelection(Math.min(sel, names.size() - 1));
        shownEngine = engines.get(Math.min(sel, engines.size() - 1)).pkg;
        spEngine.setOnSelected(new Ui.SelectRow.OnSelected() {
            @Override
            public void on(int pos) {
                if (engines == null || pos >= engines.size()) {
                    return;
                }
                String pkg = engines.get(pos).pkg;
                if (pkg.equals(shownEngine)) {
                    return;
                }
                shownEngine = pkg;
                if (pkg.equals(TtsManager.getSavedEngine(MainActivity.this))) {
                    return;
                }
                TtsManager.saveEngine(MainActivity.this, pkg);
                TtsManager.rebind(getApplicationContext());
                Ui.toast(MainActivity.this,
                        pkg.length() == 0 ? "正在切回系统默认引擎…"
                                : "正在切换引擎…", true);
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
        spVoice.setItems(labels);
        spVoice.setSelection(Math.min(sel, labels.size() - 1));
        shownVoice = (voices.isEmpty())
                ? null : voices.get(Math.min(sel, voices.size() - 1)).name;
        spVoice.setOnSelected(new Ui.SelectRow.OnSelected() {
            @Override
            public void on(int pos) {
                if (voices == null || pos >= voices.size()) {
                    return;
                }
                String name = voices.get(pos).name;
                if (name.equals(shownVoice)) {
                    return;
                }
                shownVoice = name;
                TtsManager.saveVoice(MainActivity.this, name);
                TtsManager.applyVoice(name);
            }
        });
    }

    @Override
    public void onEngineReady(boolean ready, boolean cnOk, String pkg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (TtsManager.isFailed()) {
                    Ui.toast(MainActivity.this, "引擎初始化失败，换一个试试", false);
                }
                if (voices.isEmpty()) {
                    reloadVoices();
                }
            }
        });
    }

    // =====================================================================
    // 放大
    // =====================================================================

    private LinearLayout pageZoom() {
        LinearLayout page = pageColumn();

        zoomPreview = new ZoomPreview(this);
        zoomPreview.apply(prefs.scale());
        page.addView(Ui.group(this, "实时预览",
                Ui.previewRow(this, zoomPreview)), groupLp());

        page.addView(Ui.group(this, "参数",
                Ui.sliderRow(this, "倍率", 15, 50,
                        (int) (prefs.scale() * 10),
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
                            }
                        }),
                Ui.sliderRow(this, "触发时长", 120, 600, prefs.holdMs(),
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
                        })), groupLp());

        page.addView(Ui.group(this, "行为",
                Ui.switchRow(this, "放大时悬浮球跟着变大",
                        !prefs.compensate(),
                        new OnToggle() {
                            @Override
                            public void on(boolean on) {
                                // compensate=true 表示球不变大，这里正向陈述取反
                                prefs.setCompensate(!on);
                                ComboService.refresh();
                            }
                        }),
                Ui.resetRow(this, new Runnable() {
                    @Override
                    public void run() {
                        prefs.setScale(2.5f);
                        prefs.setHoldMs(260);
                        prefs.setCompensate(true);
                        ComboService.refresh();
                        rebuild();
                        Ui.iconToast(MainActivity.this, "放大的设置已恢复默认");
                    }
                })), groupLp());
        return page;
    }

    // =====================================================================
    // 悬浮球
    // =====================================================================

    private LinearLayout pageBall() {
        LinearLayout page = pageColumn();

        page.addView(Ui.group(this, "外观",
                Ui.sliderRow(this, "大小", 36, 88, prefs.sizeDp(),
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
                            }
                        }),
                Ui.sliderRow(this, "不透明度", 20, 100,
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
                            }
                        }),
                Ui.sliderRow(this, "闲置时", 5, 100,
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
                            }
                        })), groupLp());

        ballPreview = new BallPreview(this);
        ballPreview.setColor(prefs.color());
        page.addView(Ui.group(this, "颜色",
                Ui.ballPreviewRow(this, ballPreview),
                Ui.chipsRow(this, prefs, colorIndex, new Runnable() {
                    @Override
                    public void run() {
                        colorIndex = prefs.colorIndex();
                        // 三个状态的预览球一起换色，真球也跟着换
                        if (ballPreview != null) {
                            ballPreview.setColor(prefs.color());
                        }
                        ComboService.refresh();
                    }
                })), groupLp());

        page.addView(Ui.group(this, "行为",
                Ui.switchRow(this, "松手后自动贴边", prefs.snapEdge(),
                        new OnToggle() {
                            @Override
                            public void on(boolean on) {
                                prefs.setSnapEdge(on);
                            }
                        }),
                Ui.switchRow(this, "震动反馈", prefs.haptics(),
                        new OnToggle() {
                            @Override
                            public void on(boolean on) {
                                prefs.setHaptics(on);
                                Haptics.setEnabled(on);
                                ComboService.refresh();
                            }
                        }),
                Ui.resetRow(this, new Runnable() {
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
                })), groupLp());
        return page;
    }

    // =====================================================================
    // 更多
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
            try {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("看得清下载", msg));
                Ui.toast(this, "分享没打开，内容已复制，粘贴给朋友就行", true);
            } catch (Exception e2) {
                Ui.toast(this, "分享失败，下载页：" + url, false);
            }
        }
    }

    private void copyWechat() {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("微信号", WECHAT_ID));
            Haptics.tap();
            Ui.iconToast(this, "微信号已复制：" + WECHAT_ID);
        } catch (Exception e) {
            Ui.toast(this, "复制失败，手动记一下：" + WECHAT_ID, false);
        }
    }

    private LinearLayout pageMore() {
        LinearLayout page = pageColumn();

        page.addView(Ui.group(this, "外观",
                Ui.segmentRow(this, prefs.themeMode(), new OnValue() {
                    @Override
                    public void on(int v) {
                        prefs.setThemeMode(v);
                        Theme.apply(v, systemNight());
                        rebuild();
                    }
                })), groupLp());

        page.addView(Ui.group(this, "隐私",
                Ui.plainRow(this, "本地处理 · 不联网 · 不上传"),
                Ui.switchRow(this, "密码框不朗读", prefs.passwordGuard(),
                        new OnToggle() {
                            @Override
                            public void on(boolean on) {
                                prefs.setPasswordGuard(on);
                                Ui.iconToast(MainActivity.this,
                                        on ? "已开启：密码框不再朗读"
                                                : "已关闭：恢复点哪读哪");
                            }
                        })), groupLp());

        page.addView(Ui.group(this, "分享",
                Ui.actionRow(this, "一键推荐给家人朋友", Ui.GlyphView.SHARE,
                        new Runnable() {
                            @Override
                            public void run() {
                                shareToFriend();
                            }
                        }),
                Ui.actionRow(this, "复制微信号  " + WECHAT_ID,
                        Ui.GlyphView.COPY,
                        new Runnable() {
                            @Override
                            public void run() {
                                copyWechat();
                            }
                        })), groupLp());

        page.addView(Ui.group(this, "联系作者",
                Ui.qrRow(this, R.drawable.wechat_qr, WECHAT_ID)), groupLp());
        return page;
    }

    // =====================================================================
    // 通用件
    // =====================================================================

    /** 重画整个界面：切换主题、恢复设置后把界面拉回与新值一致。 */
    private void rebuild() {
        recreate();
    }
}
