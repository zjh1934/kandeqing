package com.floating.combo;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class ClipReader {

    public interface Result {
        void onText(String text);
    }

    private static final String[] COPY_FEEDBACK = {
            "已复制", "复制成功", "已拷贝", "copied to clipboard", "copied"
    };

    private static final String[] EXACT_COPY_LABELS = {
            "复制", "拷贝", "copy", "copy text", "复制文字", "复制链接", "复制全部"
    };

    private static final Pattern CODE_LIKE = Pattern.compile(
            "(?i)^(验证码|校验码|code)[:：\\s]*\\d{4,8}$");
    private static final Pattern MOSTLY_DIGITS = Pattern.compile("^\\d{4,8}$");

    private final Context ctx;
    private final ClipboardManager cm;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private View focusProxy;
    private String lastKnown;
    private boolean baselineReady;
    private String lastSpoken;
    private long lastSpokenAt;

    public ClipReader(Context c) {
        ctx = c;
        cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\u00A0', ' ')
                .replaceAll("[\\r\\n]+", "。")
                .replaceAll("\\s+", " ")
                .trim();
        while (s.length() > 0 && "。，, . \t".indexOf(s.charAt(0)) >= 0) {
            s = s.substring(1);
        }
        while (s.length() > 0
                && "。，, . \t".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s.trim();
    }

    public static boolean shouldSkip(String raw) {
        String s = clean(raw);
        if (s.length() < 2 || s.length() > 4000) {
            return true;
        }
        return CODE_LIKE.matcher(s).matches() || MOSTLY_DIGITS.matcher(s).matches();
    }

    public static boolean isCopyFeedback(AccessibilityEvent e) {
        if (e == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = e.getText();
        if (texts != null) {
            for (CharSequence cs : texts) {
                if (cs != null) {
                    sb.append(cs).append(' ');
                }
            }
        }
        CharSequence cd = e.getContentDescription();
        if (cd != null) {
            sb.append(cd);
        }
        String t = sb.toString().toLowerCase(Locale.ROOT);
        for (String k : COPY_FEEDBACK) {
            if (t.contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCopyClick(AccessibilityEvent e) {
        if (e == null) {
            return false;
        }
        AccessibilityNodeInfo src = e.getSource();
        try {
            if (src != null && nodeIsCopy(src)) {
                return true;
            }
        } finally {
            if (src != null) {
                src.recycle();
            }
        }
        return isExactCopyLabel(eventText(e));
    }

    private static boolean nodeIsCopy(AccessibilityNodeInfo node) {
        String[] candidates = new String[]{
                textOf(node.getText()),
                textOf(node.getContentDescription()),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? textOf(node.getHintText()) : "",
                node.getViewIdResourceName() == null
                        ? "" : node.getViewIdResourceName()
        };
        for (String v : candidates) {
            if (isExactCopyLabel(v) || isCopyViewId(v)) {
                return true;
            }
        }
        int n = Math.min(node.getChildCount(), 6);
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                if (isExactCopyLabel(textOf(child.getText()))
                        || isExactCopyLabel(textOf(child.getContentDescription()))) {
                    return true;
                }
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private static String eventText(AccessibilityEvent e) {
        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = e.getText();
        if (texts != null) {
            for (CharSequence cs : texts) {
                if (cs != null) {
                    sb.append(cs).append(' ');
                }
            }
        }
        CharSequence cd = e.getContentDescription();
        if (cd != null) {
            sb.append(cd);
        }
        return sb.toString().trim();
    }

    private static String textOf(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static boolean isExactCopyLabel(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.length() == 0) {
            return false;
        }
        for (String k : EXACT_COPY_LABELS) {
            if (t.equals(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCopyViewId(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        return t.contains(":id/copy") || t.endsWith("/copy") || t.contains("copy_btn");
    }

    public void snapshotBaseline() {
        String raw = readText();
        lastKnown = raw == null ? "" : clean(raw);
        baselineReady = true;
    }

    public boolean shouldSpeakAuto(String raw) {
        String s = clean(raw);
        if (s.length() == 0) {
            return false;
        }
        if (!baselineReady) {
            lastKnown = s;
            baselineReady = true;
            return false;
        }
        if (s.equals(lastKnown)) {
            return false;
        }
        lastKnown = s;
        if (shouldSkip(s)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (s.equals(lastSpoken) && now - lastSpokenAt < 1200) {
            return false;
        }
        lastSpoken = s;
        lastSpokenAt = now;
        return true;
    }

    public void readOnce(final Result cb) {
        final String direct = readText();
        if (direct != null && direct.trim().length() > 0) {
            cb.onText(direct);
            return;
        }
        readWithFocusProxy(cb);
    }

    private String readText() {
        try {
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            ClipDescription desc = clip.getDescription();
            if (desc != null) {
                CharSequence label = desc.getLabel();
                if (label != null) {
                    String l = label.toString();
                    if (l.startsWith("看得清") || l.equals("微信号")) {
                        return null;
                    }
                }
            }
            CharSequence cs = clip.getItemAt(0).getText();
            if (cs == null) {
                cs = clip.getItemAt(0).coerceToText(ctx);
            }
            return cs == null ? null : cs.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void readWithFocusProxy(final Result cb) {
        removeFocusProxy();
        if (wm == null) {
            cb.onText(null);
            return;
        }
        try {
            View v = new View(ctx);
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1
                    ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            p.x = 0;
            p.y = 0;
            focusProxy = v;
            wm.addView(v, p);
            v.requestFocus();
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    String text = readText();
                    removeFocusProxy();
                    cb.onText(text);
                }
            }, 80);
        } catch (Throwable t) {
            Log.w("clip", "焦点窗读剪贴板失败");
            removeFocusProxy();
            cb.onText(null);
        }
    }

    private void removeFocusProxy() {
        View v = focusProxy;
        focusProxy = null;
        if (v != null && wm != null) {
            try {
                wm.removeView(v);
            } catch (Throwable ignored) {
            }
        }
    }

    public void release() {
        h.removeCallbacksAndMessages(null);
        removeFocusProxy();
    }
}
