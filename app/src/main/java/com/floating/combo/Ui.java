package com.floating.combo;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static boolean reduceMotion(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    public static void tnum(TextView t) {
        try {
            t.setFontFeatureSettings("tnum");
        } catch (Throwable ignored) {
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

    public static TextView body(Context c, String s) {
        return text(c, s, 14, Theme.TEXT, false);
    }

    public static TextView caption(Context c, String s) {
        return text(c, s, 12, Theme.TEXT_2, false);
    }

    public static TextView value(Context c, String s) {
        TextView t = text(c, s, 14, Theme.ACCENT, true);
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

    private static LinearLayout.LayoutParams rowLp(Context c, int hDp) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, hDp));
    }

    private static LinearLayout newRow(Context c, int hDp) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(rowLp(c, hDp));
        int p = dp(c, 12);
        r.setPadding(p, 0, p, 0);
        return r;
    }

    public static LinearLayout group(Context c, String title, View... rows) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(c);
        bar.setBackgroundDrawable(Theme.rounded(Theme.ACCENT,
                dp(c, 1.5f), 0, 0));
        head.addView(bar, new LinearLayout.LayoutParams(dp(c, 3), dp(c, 14)));
        TextView h = text(c, title, 13, Theme.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(c, 8);
        head.addView(h, tlp);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = dp(c, 4);
        hlp.topMargin = dp(c, 8);
        hlp.bottomMargin = dp(c, 6);
        wrap.addView(head, hlp);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundDrawable(Theme.rounded(Theme.RAISED,
                dp(c, Theme.CARD_R), 0, 0));
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                View line = new View(c);
                line.setBackgroundColor(Theme.DIVIDER);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        Math.max(1, dp(c, 1)));
                llp.setMargins(dp(c, 12), 0, dp(c, 12), 0);
                box.addView(line, llp);
            }
            box.addView(rows[i]);
        }
        wrap.addView(box);
        return wrap;
    }

    public static SelectRow selectRow(Context c, String label) {
        return new SelectRow(c, label);
    }

    public static final class SelectRow extends LinearLayout {

        public interface OnSelected {
            void on(int pos);
        }

        private final Context c;
        private final LinearLayout row;
        private final View mark;
        private final TextView value;
        private final GlyphView chevron;
        private final LinearLayout menu;
        private final List<String> items = new ArrayList<String>();
        private int selected = 0;
        private boolean expanded = false;
        private OnSelected cb;

        private static java.lang.ref.WeakReference<SelectRow> sOpen;

        public SelectRow(Context c, String labelText) {
            super(c);
            this.c = c;
            setOrientation(LinearLayout.VERTICAL);
            setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(rowLp(c, Theme.ROW_SELECT));
            int p = dp(c, 12);
            row.setPadding(dp(c, 5), 0, p, 0);

            mark = new View(c);
            mark.setBackgroundDrawable(Theme.rounded(Theme.ACCENT,
                    dp(c, 1.5f), 0, 0));
            mark.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    dp(c, 3), dp(c, 32));
            mlp.rightMargin = dp(c, 4);
            row.addView(mark, mlp);

            TextView label = body(c, labelText);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            value = text(c, "", 14, Theme.ACCENT, true);
            value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            value.setSingleLine(true);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(value, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            chevron = new GlyphView(c, GlyphView.CHEVRON);
            chevron.setTint(Theme.TEXT_2);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    dp(c, 18), dp(c, 18));
            glp.leftMargin = dp(c, 8);
            row.addView(chevron, glp);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle();
                }
            });
            addView(row);

            menu = new LinearLayout(c);
            menu.setOrientation(LinearLayout.VERTICAL);
            menu.setVisibility(View.GONE);
            menu.setBackgroundDrawable(Theme.rounded(Theme.SURFACE_2,
                    dp(c, 10), 0, 0));
            menu.setPadding(dp(c, 14), dp(c, 4), dp(c, 14), dp(c, 8));
            LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            mlp2.setMargins(dp(c, 14), dp(c, 4), dp(c, 14), 0);
            addView(menu, mlp2);
        }

        public void setItems(List<String> list) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            if (selected >= items.size()) {
                selected = Math.max(0, items.size() - 1);
            }
            refreshValue();
            if (expanded) {
                rebuildMenu();
            }
        }

        public void setSelection(int pos) {
            selected = items.isEmpty() ? 0
                    : Math.max(0, Math.min(items.size() - 1, pos));
            refreshValue();
            if (expanded) {
                rebuildMenu();
            }
        }

        public void setOnSelected(OnSelected l) {
            cb = l;
        }

        private void refreshValue() {
            value.setText(items.isEmpty() ? "" : items.get(selected));
        }

        private void toggle() {
            if (!expanded && sOpen != null) {
                SelectRow prev = sOpen.get();
                if (prev != null && prev != this && prev.expanded) {
                    prev.setExpanded(false);
                }
            }
            setExpanded(!expanded);
        }

        private void setExpanded(boolean on) {
            expanded = on;
            menu.setVisibility(on ? View.VISIBLE : View.GONE);
            chevron.setRotation(on ? 180f : 0f);
            row.setBackgroundDrawable(on
                    ? new android.graphics.drawable.InsetDrawable(
                            Theme.rounded(Theme.ACCENT_SOFT, dp(c, 8), 0, 0),
                            dp(c, 4), 0, dp(c, 4), 0)
                    : null);
            mark.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (on) {
                rebuildMenu();
                sOpen = new java.lang.ref.WeakReference<SelectRow>(this);
            } else if (sOpen != null && sOpen.get() == this) {
                sOpen = null;
            }
        }

        private void rebuildMenu() {
            menu.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                final int pos = i;
                boolean on = (i == selected);
                LinearLayout item = new LinearLayout(c);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 44)));

                android.widget.FrameLayout rail = new android.widget.FrameLayout(c);
                rail.setLayoutParams(new LinearLayout.LayoutParams(
                        dp(c, 30), dp(c, 44)));
                View line = new View(c);
                line.setBackgroundColor(Theme.DIVIDER);
                android.widget.FrameLayout.LayoutParams llp =
                        new android.widget.FrameLayout.LayoutParams(
                                dp(c, 2),
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER_HORIZONTAL);
                rail.addView(line, llp);
                if (on) {
                    View seg = new View(c);
                    seg.setBackgroundDrawable(Theme.rounded(Theme.ACCENT,
                            dp(c, 1.5f), 0, 0));
                    android.widget.FrameLayout.LayoutParams slp =
                            new android.widget.FrameLayout.LayoutParams(
                                    dp(c, 3), dp(c, 18), Gravity.CENTER);
                    rail.addView(seg, slp);
                }
                item.addView(rail);

                TextView t = text(c, items.get(i), 13,
                        on ? Theme.ACCENT : Theme.TEXT_2, on);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                item.addView(t, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int old = selected;
                        setExpanded(false);
                        setSelection(pos);
                        if (cb != null && pos != old) {
                            cb.on(pos);
                        }
                    }
                });
                menu.addView(item);
            }
        }
    }

    public static LinearLayout sliderRow(Context c, String label,
                                         int min, int max, int cur,
                                         final Fmt fmt, final OnValue cb) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setLayoutParams(rowLp(c, Theme.ROW_SLIDER));
        int p = dp(c, 12);
        r.setPadding(p, dp(c, 4), p, 0);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(body(c, label), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = value(c, fmt.text(cur));
        head.addView(val);
        r.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

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
        sb.setMinimumHeight(dp(c, 32));
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
        r.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 30)));
        return r;
    }

    public static LinearLayout switchRow(Context c, String label,
                                         boolean checked, final OnToggle cb) {
        LinearLayout r = newRow(c, Theme.ROW_SWITCH);
        r.addView(body(c, label), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(c);
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
                new int[]{Theme.ACCENT_SOFT, Theme.TRACK}));
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

    public static LinearLayout actionRow(Context c, String label, int glyph,
                                         final Runnable onClick) {
        LinearLayout r = newRow(c, Theme.ROW_ACTION);
        r.addView(body(c, label), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        GlyphView g = new GlyphView(c, glyph);
        r.addView(g, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onClick != null) {
                    onClick.run();
                }
            }
        });
        return r;
    }

    public static LinearLayout resetRow(Context c, final Runnable onClick) {
        return actionRow(c, "恢复默认", GlyphView.RESET, onClick);
    }

    public static LinearLayout plainRow(Context c, String label) {
        LinearLayout r = newRow(c, Theme.ROW_PLAIN);
        View dot = new View(c);
        int sz = dp(c, 8);
        dot.setBackgroundDrawable(Theme.circle(Theme.SUCCESS, dp(c, 4), 0, 0));
        r.addView(dot, new LinearLayout.LayoutParams(sz, sz));
        TextView t = body(c, label);
        t.setPadding(dp(c, 10), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    public static LinearLayout chipsRow(Context c, final Prefs prefs,
                                        int selected, final Runnable onChange) {
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.setLayoutParams(rowLp(c, Theme.ROW_CHIPS));
        int p = dp(c, 8);
        outer.setPadding(p, 0, p, 0);

        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        buildChips(c, row, prefs, selected, onChange);
        android.widget.HorizontalScrollView hs =
                new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        outer.addView(hs, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return outer;
    }

    public static LinearLayout ballPreviewRow(Context c, BallPreview preview) {
        LinearLayout r = newRow(c, Theme.ROW_BALL_PREVIEW);
        LinearLayout box = new LinearLayout(c);
        box.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY,
                dp(c, Theme.CTRL_R), 0, 0));
        box.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        r.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(c, Theme.ROW_BALL_PREVIEW - 16)));
        return r;
    }

    public static void buildChips(final Context c, LinearLayout row,
                                  final Prefs prefs, final int selected,
                                  final Runnable onChange) {
        row.removeAllViews();
        for (int i = 0; i < Prefs.COLORS.length; i++) {
            final int idx = i;
            boolean sel = (i == selected);
            LinearLayout chip = new LinearLayout(c);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_HORIZONTAL);
            chip.setMinimumHeight(dp(c, Theme.HIT));
            chip.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 2));
            chip.setContentDescription(Prefs.COLOR_NAMES[i]
                    + (sel ? "，已选中" : ""));

            BallChipView ball = new BallChipView(c);
            ball.setColor(Prefs.COLORS[i]);
            ball.setChecked(sel);
            chip.addView(ball, new LinearLayout.LayoutParams(
                    dp(c, 38), dp(c, 38)));

            TextView n = text(c, Prefs.COLOR_NAMES[i], 11,
                    sel ? Theme.ACCENT : Theme.TEXT_2, sel);
            n.setPadding(0, dp(c, 2), 0, 0);
            chip.addView(n);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setColorIndex(idx);
                    buildChips(c, row, prefs, idx, onChange);
                    if (onChange != null) {
                        onChange.run();
                    }
                }
            });
            row.addView(chip);
        }
    }

    public static LinearLayout segmentRow(Context c, int selected,
                                          final OnValue cb) {
        LinearLayout r = newRow(c, Theme.ROW_SEGMENT);
        String[] items = new String[]{"跟随系统", "深色", "浅色"};
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 36)));
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean on = (i == selected);
            TextView seg = text(c, items[i], 12,
                    on ? Theme.ACCENT : Theme.TEXT_2, on);
            seg.setGravity(Gravity.CENTER);
            seg.setBackgroundDrawable(Theme.rounded(
                    on ? Theme.ACCENT_SOFT : Theme.OVERLAY,
                    dp(c, Theme.CTRL_R), 0, 0));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) {
                slp.leftMargin = dp(c, 4);
            }
            seg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cb != null) {
                        cb.on(idx);
                    }
                }
            });
            box.addView(seg, slp);
        }
        r.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 36)));
        return r;
    }

    public static LinearLayout previewRow(Context c, View preview) {
        LinearLayout r = newRow(c, Theme.ROW_PREVIEW);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY,
                dp(c, Theme.CTRL_R), 0, 0));
        box.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(c, Theme.ROW_PREVIEW - 16)));
        r.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(c, Theme.ROW_PREVIEW - 16)));
        return r;
    }

    public static LinearLayout qrRow(Context c, int qrResId, String wxId) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setLayoutParams(rowLp(c, Theme.ROW_QR));
        r.setPadding(0, dp(c, 8), 0, dp(c, 6));

        int size = dp(c, 96);
        LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setBackgroundDrawable(Theme.rounded(Theme.QR_WHITE,
                dp(c, 12), 0, 0));
        holder.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        holder.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView qr = new ImageView(c);
        qr.setImageResource(qrResId);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setContentDescription("作者的微信二维码");
        holder.addView(qr, new LinearLayout.LayoutParams(size, size));
        r.addView(holder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView id = text(c, "微信号  " + wxId, 13, Theme.TEXT, true);
        id.setGravity(Gravity.CENTER);
        id.setPadding(0, dp(c, 12), 0, 0);
        r.addView(id);
        return r;
    }

    public static Button primaryButton(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Theme.ON_ACCENT);
        b.setBackgroundDrawable(Theme.rounded(Theme.ACCENT,
                dp(c, Theme.CTRL_R), 0, 0));
        b.setMinimumHeight(dp(c, 48));
        return b;
    }

    public static void toast(Context c, String msg, boolean ok) {
        try {
            TextView t = text(c, (ok ? "✓  " : "!  ") + msg,
                    13, Theme.TEXT, false);
            int h = dp(c, 14);
            t.setPadding(h, dp(c, 9), h, dp(c, 9));
            t.setBackgroundDrawable(Theme.rounded(Theme.OVERLAY,
                    dp(c, 12), 0, 0));
            Toast toast = new Toast(c);
            toast.setView(t);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.BOTTOM, 0, dp(c, 72));
            toast.show();
        } catch (Exception ignored) {
        }
    }

    public static void iconToast(Context c, String msg) {
        toast(c, msg, true);
    }

    public static LinearLayout navItem(Context c, int glyph, String label,
                                       boolean active) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundDrawable(Theme.rounded(
                active ? Theme.ACCENT_SOFT : Theme.TRANSPARENT,
                dp(c, 12), 0, 0));
        v.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        GlyphView g = new GlyphView(c, glyph);
        g.setBackgroundDrawable(null);
        g.setTint(active ? Theme.ACCENT : Theme.TEXT_3);
        v.addView(g, new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
        TextView l = text(c, label, 11,
                active ? Theme.ACCENT : Theme.TEXT_2, active);
        l.setGravity(Gravity.CENTER);
        v.addView(l);
        v.setMinimumHeight(dp(c, 52));
        return v;
    }

    public static final class GlyphView extends View {
        public static final int SHARE = 1;
        public static final int COPY = 2;
        public static final int RESET = 3;
        public static final int NAV_READ = 4;
        public static final int NAV_ZOOM = 5;
        public static final int NAV_ORB = 6;
        public static final int NAV_MORE = 7;
        public static final int CHEVRON = 8;
        public static final int CHECK = 9;

        private final int kind;
        private final Paint paint;
        private int tint = 0;

        public GlyphView(Context c, int kind) {
            super(c);
            this.kind = kind;
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1.4f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            if (kind <= RESET) {
                setBackgroundDrawable(Theme.rounded(Theme.BUTTON,
                        Ui.dp(c, 14), 0, 0));
            }
        }

        public void setTint(int color) {
            tint = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(tint != 0 ? tint : Theme.ACCENT);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = getWidth() * 0.22f;
            Paint fill = new Paint(paint);
            fill.setStyle(Paint.Style.FILL);

            if (kind == RESET) {
                RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(box, 35f, 290f, false, paint);
                double a = Math.toRadians(35);
                float px = (float) (cx + r * Math.cos(a));
                float py = (float) (cy + r * Math.sin(a));
                canvas.drawCircle(px, py, Ui.dp(getContext(), 1.6f), fill);
            } else if (kind == SHARE) {
                canvas.drawLine(cx - r, cy + r * 0.7f, cx + r, cy - r * 0.7f, paint);
                float ax = cx + r * 0.35f;
                float ay = cy - r * 0.9f;
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(ax, ay - r * 0.45f);
                p.lineTo(ax + r * 0.7f, ay);
                p.lineTo(ax + r * 0.15f, ay + r * 0.55f);
                p.close();
                canvas.drawPath(p, fill);
            } else if (kind == COPY) {
                float s = r * 0.85f;
                RectF a = new RectF(cx - s, cy - s, cx + s * 0.25f, cy + s * 0.25f);
                RectF b = new RectF(cx - s * 0.25f, cy - s * 0.25f, cx + s, cy + s);
                canvas.drawRoundRect(a, Ui.dp(getContext(), 2), Ui.dp(getContext(), 2), paint);
                canvas.drawRoundRect(b, Ui.dp(getContext(), 2), Ui.dp(getContext(), 2), paint);
            } else if (kind == NAV_READ) {
                float[] hs = new float[]{r * 0.9f, r * 1.8f, r * 1.3f};
                for (int k = 0; k < 3; k++) {
                    float x = cx - r + k * r;
                    canvas.drawRect(x - Ui.dp(getContext(), 1), cy - hs[k] / 2,
                            x + Ui.dp(getContext(), 1), cy + hs[k] / 2, fill);
                }
            } else if (kind == NAV_ZOOM) {
                canvas.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.8f, paint);
                canvas.drawLine(cx + r * 0.3f, cy + r * 0.3f,
                        cx + r * 1.05f, cy + r * 1.05f, paint);
            } else if (kind == NAV_ORB) {
                canvas.drawCircle(cx, cy, r * 1.05f, paint);
                canvas.drawCircle(cx, cy, r * 0.38f, fill);
            } else if (kind == CHEVRON) {
                float a = getWidth() * 0.24f;
                canvas.drawLine(cx - a, cy - a * 0.45f, cx, cy + a * 0.45f, paint);
                canvas.drawLine(cx, cy + a * 0.45f, cx + a, cy - a * 0.45f, paint);
            } else if (kind == CHECK) {
                canvas.drawLine(cx - r * 0.8f, cy, cx - r * 0.1f, cy + r * 0.6f, paint);
                canvas.drawLine(cx - r * 0.1f, cy + r * 0.6f,
                        cx + r * 0.9f, cy - r * 0.65f, paint);
            } else {
                for (int k = 0; k < 3; k++) {
                    canvas.drawCircle(cx - r + k * r, cy,
                            Ui.dp(getContext(), 1.5f), fill);
                }
            }
        }
    }

    public static final class BallChipView extends View {
        private int color = 0xFFFAFAFA;
        private boolean checked = false;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        public BallChipView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            fill.setStyle(Paint.Style.FILL);
        }

        public void setColor(int c) {
            color = c;
            invalidate();
        }

        public void setChecked(boolean v) {
            checked = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            float pad = Ui.dp(getContext(), 3f);
            if (checked) {
                line.setColor(Theme.ACCENT);
                line.setStrokeWidth(Ui.dp(getContext(), 2f));
                c.drawCircle(cx, cy, r - pad, line);
            }
            fill.setColor(0xFF3C4655);
            c.drawCircle(cx, cy, r * 0.88f, fill);
            BallArt.draw(c, cx, cy, r * 0.78f, color,
                    BallArt.ST_NORMAL, 1f);
            if (checked) {
                float bx = cx + r * 0.58f;
                float by = cy + r * 0.58f;
                float br = Ui.dp(getContext(), 8f);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(bx, by, br, fill);
                line.setColor(Theme.ACCENT);
                line.setStrokeWidth(Ui.dp(getContext(), 2f));
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(bx - br * 0.45f, by);
                p.lineTo(bx - br * 0.08f, by + br * 0.38f);
                p.lineTo(bx + br * 0.48f, by - br * 0.42f);
                c.drawPath(p, line);
            }
        }
    }
}

interface OnValue {
    void on(int v);
}

interface OnToggle {
    void on(boolean on);
}

interface Fmt {
    String text(int v);
}
