package com.floating.combo;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 看得清 3.7 · 组件工厂（QC/MasterControl、QC/SectionCard、QC/ValueSlider、
 * QC/TokenChip、QC/PreviewCanvas 的落地）。
 *
 * 全部是「造 View」的静态纯函数：不读 Prefs、不碰 TTS / 抓字 / 悬浮球服务。
 * 回调由调用方传进来，组件本身不知道业务。
 *
 * 字体一律用 sp（setTextSize(float) 就是 sp），跟随系统字号缩放；
 * 数值用等宽数字，拖动时不抖动；可点区域不小于 48dp。
 */
public final class Ui {

    private Ui() {
    }

    // ---- 基础 ------------------------------------------------------------

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 遵循系统「减少动画」开关：开了就把所有动效降级为淡入淡出 / 直接切换。 */
    public static boolean reduceMotion(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /** 等宽数字：滑块当前值、倍率大数用，拖动时不抖、不换行。 */
    public static void tnum(TextView t) {
        try {
            t.setFontFeatureSettings("tnum");
        } catch (Throwable ignored) {
            // 少数 ROM 不支持 fontFeature，退化成普通数字也不影响功能
        }
    }

    public static TextView text(Context c, String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    /** Display 28/600：页面主标题、当前倍率大数 */
    public static TextView display(Context c, String s) {
        return text(c, s, 28, Theme.TEXT, true);
    }

    /** Title 20/600：卡片标题 */
    public static TextView title(Context c, String s) {
        return text(c, s, 20, Theme.TEXT, true);
    }

    /** Body 16/400：正文、设置项标签 */
    public static TextView body(Context c, String s) {
        return text(c, s, 16, Theme.TEXT, false);
    }

    /** Caption 14/400：说明、单位、脚注 */
    public static TextView caption(Context c, String s) {
        return text(c, s, 14, Theme.TEXT_2, false);
    }

    /** Value 20/600 等宽数字：滑块当前值 */
    public static TextView value(Context c, String s) {
        TextView t = text(c, s, 20, Theme.ACCENT, true);
        tnum(t);
        return t;
    }

    public static LinearLayout.LayoutParams lp(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    // ---- QC/SectionCard -------------------------------------------------

    /** 一组设置 = 一张卡片：20dp 圆角、24dp 留白、无描边，靠底色差分层。 */
    public static LinearLayout card(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundDrawable(Theme.rounded(Theme.RAISED,
                dp(c, Theme.CARD_R), 0, 0));
        int p = dp(c, Theme.CARD_PAD);
        v.setPadding(p, p, p, p);
        return v;
    }

    /** 卡片 + 标题 + 最多两行说明。组间距由调用方给 32dp，不画组间分割线。 */
    public static LinearLayout cardSection(Context c, String heading, String desc) {
        LinearLayout v = card(c);
        v.addView(title(c, heading));
        if (desc != null && desc.length() > 0) {
            TextView d = caption(c, desc);
            d.setMaxLines(2);
            v.addView(d, lp(c, 0, 6, 0, 0));
        }
        return v;
    }

    /** 卡内极弱分隔，只用于把标题区与内容区错开，不是组间线。 */
    public static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.DIVIDER);
        return v;
    }

    // ---- QC/ValueSlider -------------------------------------------------

    /**
     * 四页共用的唯一滑块形态：
     * 标签 + 实时数值 + 单项重置 + 滑杆 + 最小/默认/最大刻度 + 一句结果式说明。
     * 数值变化即时作用，不需要「应用」。
     */
    public static LinearLayout valueSlider(Context c, String name,
                                           int min, int max, int cur,
                                           final Fmt fmt, final OnValue cb,
                                           final Runnable onReset,
                                           String minLabel, String defLabel,
                                           String maxLabel, String hint) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(body(c, name), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView val = value(c, fmt.text(cur));
        head.addView(val);

        if (onReset != null) {
            TextView reset = text(c, "↺", 20, Theme.TEXT_2, false);
            reset.setContentDescription(name + "恢复默认");
            reset.setPadding(dp(c, 14), dp(c, 8), dp(c, 4), dp(c, 8));
            reset.setMinHeight(dp(c, Theme.HIT));
            reset.setMinimumWidth(dp(c, Theme.HIT));
            reset.setGravity(Gravity.CENTER);
            reset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onReset.run();
                }
            });
            head.addView(reset);
        }
        r.addView(head);

        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        try {
            sb.getProgressDrawable().setColorFilter(Theme.ACCENT, PorterDuff.Mode.SRC_IN);
            if (sb.getThumb() != null) {
                sb.getThumb().setColorFilter(Theme.ACCENT, PorterDuff.Mode.SRC_IN);
            }
        } catch (Exception ignored) {
        }
        sb.setMinimumHeight(dp(c, Theme.HIT));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                int v = prog + min;
                val.setText(fmt.text(v));
                if (fromUser && cb != null) {
                    cb.on(v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        r.addView(sb, lp(c, 0, 6, 0, 0));

        LinearLayout ticks = new LinearLayout(c);
        ticks.setOrientation(LinearLayout.HORIZONTAL);
        TextView a = text(c, minLabel, 14, Theme.TEXT_3, false);
        TextView b = text(c, defLabel, 14, Theme.TEXT_3, false);
        TextView d = text(c, maxLabel, 14, Theme.TEXT_3, false);
        b.setGravity(Gravity.CENTER);
        d.setGravity(Gravity.RIGHT);
        ticks.addView(a, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ticks.addView(b, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ticks.addView(d, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(ticks);

        if (hint != null && hint.length() > 0) {
            r.addView(caption(c, hint), lp(c, 0, 8, 0, 0));
        }
        return r;
    }

    // ---- Switch / 状态 --------------------------------------------------

    /** 正向陈述的一行开关。状态由 Switch 本身表达，不在文字里写「开/关」。 */
    public static LinearLayout switchRow(Context c, String label, String desc,
                                         boolean checked, final OnToggle cb) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, Theme.HIT));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(body(c, label));
        if (desc != null && desc.length() > 0) {
            texts.addView(caption(c, desc));
        }
        r.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(c);
        sw.setShowText(false);
        sw.setChecked(checked);
        sw.setContentDescription(label);
        sw.setMinHeight(dp(c, Theme.HIT));
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{Theme.ACCENT, Theme.TEXT_2}));
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{0x664AA8FF, Theme.OVERLAY}));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (cb != null) {
                    cb.on(on);
                }
            }
        });
        r.addView(sw);
        return r;
    }

    /** 状态标签：只用于状态，不承载装饰。 */
    public static TextView statusTag(Context c, String label, int color) {
        TextView t = text(c, label, 14, color, true);
        int h = dp(c, 12);
        int v = dp(c, 6);
        t.setPadding(h, v, h, v);
        t.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY, dp(c, 999), 0, 0));
        return t;
    }

    // ---- QC/TokenChip ---------------------------------------------------

    /** 颜色选择器：名称 + 勾选 + 预览圆点，三重编码；每项 ≥48dp。 */
    public static View tokenChip(Context c, String name, int color,
                                 boolean selected, final Runnable onClick) {
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinimumHeight(dp(c, Theme.HIT));
        chip.setBackgroundDrawable(Theme.rounded(
                selected ? Theme.OVERLAY : Theme.RAISED,
                dp(c, Theme.CTRL_R),
                selected ? Theme.ACCENT : Theme.TRANSPARENT,
                selected ? dp(c, 1.5f) : 0));
        int p = dp(c, 12);
        chip.setPadding(p, dp(c, 8), p, dp(c, 8));

        chip.addView(text(c, selected ? "◉" : "○", 16,
                selected ? Theme.ACCENT : Theme.TEXT_3, false));
        TextView n = text(c, name, 14, selected ? Theme.TEXT : Theme.TEXT_2, selected);
        n.setPadding(dp(c, 6), 0, dp(c, 10), 0);
        chip.addView(n);

        View dot = new View(c);
        int d = dp(c, 24);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(d, d);
        dot.setLayoutParams(dlp);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(d / 2f);
        g.setStroke(dp(c, 1), Theme.DIVIDER);
        dot.setBackgroundDrawable(g);
        chip.addView(dot);

        chip.setContentDescription(name + (selected ? "，已选中" : ""));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onClick != null) {
                    onClick.run();
                }
            }
        });
        return chip;
    }

    // ---- 按钮层级 -------------------------------------------------------

    /** Primary：单屏唯一渐变主操作，高 56dp。 */
    public static Button primaryButton(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Theme.ON_ACCENT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundDrawable(Theme.brandGradient(dp(c, Theme.CTRL_R)));
        b.setMinimumHeight(dp(c, 56));
        return b;
    }

    /** Secondary：overlay 填充，每屏 ≤2。 */
    public static Button secondaryButton(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Theme.TEXT);
        b.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY,
                dp(c, Theme.CTRL_R), 0, 0));
        b.setMinimumHeight(dp(c, Theme.HIT));
        return b;
    }

    /** Tertiary：纯文字 + 图标，无底色。 */
    public static TextView tertiaryButton(Context c, String label) {
        TextView t = text(c, "›  " + label, 15, Theme.ACCENT, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(c, Theme.HIT));
        t.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));
        return t;
    }

    // ---- 反馈 -----------------------------------------------------------

    /** 底部带图标的 Toast，2s 自动消失。成功用 ✓，失败用 !。 */
    public static void toast(Context c, String msg, boolean ok) {
        try {
            TextView t = text(c, (ok ? "✓  " : "!  ") + msg, 14, Theme.TEXT, false);
            int h = dp(c, 16);
            t.setPadding(h, dp(c, 10), h, dp(c, 10));
            t.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY, dp(c, 14), 0, 0));
            Toast toast = new Toast(c);
            toast.setView(t);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.BOTTOM, 0, dp(c, 72));
            toast.show();
        } catch (Exception ignored) {
        }
    }

    /** 成功反馈的简写。 */
    public static void iconToast(Context c, String msg) {
        toast(c, msg, true);
    }

    /**
     * Confirm Sheet：恢复默认一律底部确认，写明影响范围与撤销方式。
     */
    public static void confirmSheet(Context c, String heading, String message,
                                    final Runnable onConfirm) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY, dp(c, 20), 0, 0));
        int p = dp(c, 24);
        box.setPadding(p, p, p, p);
        box.addView(title(c, heading));
        TextView m = body(c, message);
        m.setTextSize(15);
        m.setLineSpacing(dp(c, 4), 1f);
        box.addView(m, lp(c, 0, 12, 0, 20));

        final Button no = secondaryButton(c, "先不恢复");
        final Button ok = primaryButton(c, "恢复默认");
        final AlertDialog[] holder = new AlertDialog[1];

        no.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (onConfirm != null) {
                    onConfirm.run();
                }
            }
        });
        box.addView(ok);
        box.addView(no, lp(c, 0, 10, 0, 0));

        AlertDialog.Builder b = new AlertDialog.Builder(c);
        b.setView(box);
        AlertDialog d = b.create();
        holder[0] = d;
        if (d.getWindow() != null) {
            d.getWindow().setGravity(Gravity.BOTTOM);
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Theme.TRANSPARENT));
        }
        d.show();
    }

    // ---- 导航 -----------------------------------------------------------

    /** 底部导航一格：图标 + 文字双编码；选中态用 overlay 底色 + accent 图标。 */
    public static LinearLayout navItem(Context c, String icon, String label, boolean active) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundDrawable(Theme.rounded(
                active ? Theme.OVERLAY : Theme.TRANSPARENT, dp(c, Theme.CTRL_R), 0, 0));
        int p = dp(c, 6);
        v.setPadding(p, dp(c, 8), p, dp(c, 8));
        TextView i = text(c, icon, 20, active ? Theme.ACCENT : Theme.TEXT_3, false);
        i.setGravity(Gravity.CENTER);
        TextView l = text(c, label, 12, active ? Theme.ACCENT : Theme.TEXT_2, active);
        l.setGravity(Gravity.CENTER);
        v.addView(i);
        v.addView(l);
        v.setMinimumHeight(dp(c, 56));
        return v;
    }

    // ---- Spinner 深色适配 -----------------------------------------------

    /** 原生下拉项在深色主题下会是黑字，这里统一染成浅字 + overlay 底。 */
    private static void styleDropdown(View v, Context c) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTextColor(Theme.TEXT);
            t.setTextSize(15);
            t.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        }
        v.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY, dp(c, 10), 0, 0));
    }

    public static ArrayAdapter<String> darkAdapter(Context c, List<String> items) {
        ArrayAdapter<String> ad = new ArrayAdapter<String>(c,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                Ui.styleDropdown(v, getContext());
                return v;
            }

            @Override
            public View getDropDownView(int pos, View cv, ViewGroup parent) {
                View v = super.getDropDownView(pos, cv, parent);
                Ui.styleDropdown(v, getContext());
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return ad;
    }
}

/** 滑块把整数值交给页面的回调。 */
interface OnValue {
    void on(int v);
}

/** Switch 状态回调。 */
interface OnToggle {
    void on(boolean on);
}

/** 滑块数值的显示格式。 */
interface Fmt {
    String text(int v);
}
