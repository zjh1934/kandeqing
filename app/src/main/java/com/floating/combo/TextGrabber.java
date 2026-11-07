package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TextGrabber {

    private static final int MAX_WALK = 10000;
    private static final int MAX_SEG = 2000;

    private int readScope = Prefs.READ_SCREEN;

    private static final String[] EXTRA_GETTER_NAMES = {
            "getHintText", "getTooltipText", "getStateDescription", "getPaneTitle"
    };
    private static final java.lang.reflect.Method[] EXTRA_GETTERS = resolveExtraGetters();

    private static java.lang.reflect.Method[] resolveExtraGetters() {
        java.lang.reflect.Method[] out =
                new java.lang.reflect.Method[EXTRA_GETTER_NAMES.length];
        try {
            for (int i = 0; i < out.length; i++) {
                try {
                    out[i] = AccessibilityNodeInfo.class.getMethod(EXTRA_GETTER_NAMES[i]);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
    private static final int MAX_DEPTH = 500;
    private static final int MAX_CHAR_REQ = 20000;

    private final AccessibilityService svc;

    public TextGrabber(AccessibilityService svc) {
        this.svc = svc;
    }

    public String grabFrom(int x, int y) {
        if (magnified()) {
            Log.w("grab", "系统处于放大态，坐标不自洽，本次不抓字");
            return null;
        }
        long t0 = System.nanoTime();
        readScope = new Prefs(svc).readScope();
        String out = grabFromWindows(x, y);
        if (out != null && out.length() > 0) {
            Log.i("grab", "抓字 " + ((System.nanoTime() - t0) / 1000000L)
                    + "ms / " + out.length() + " 字");
            return out;
        }
        return grabAt(x, y);
    }

    private boolean magnified() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            return svc.getMagnificationController().getScale() > 1.0f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String grabFromWindows(int x, int y) {
        List<AccessibilityNodeInfo> roots = roots();
        if (roots.isEmpty()) {
            return null;
        }
        try {
            Rect screen = screenRect();
            List<TextLayout.Node> fallback = null;
            for (int r = 0; r < roots.size(); r++) {
                List<TextLayout.Node> segs = new ArrayList<TextLayout.Node>();
                Walk w = new Walk(segs, screen, x, y);
                walk(roots.get(r), w, 0);
                if (w.truncated) {
                    Log.w("grab", "节点太多，收够上限就停了 walk=" + w.visited
                            + " seg=" + segs.size());
                }
                if (segs.isEmpty()) {
                    continue;
                }
                TextLayout.sortReadingOrder(segs);
                if (fallback == null) {
                    fallback = segs;
                }
                if (w.pick != null) {
                    return join(segs, w.pick);
                }
            }
            if (fallback == null) {
                return null;
            }
            return joinBelow(fallback, y);
        } finally {
            for (int r = 0; r < roots.size(); r++) {
                try {
                    roots.get(r).recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public String grabAt(int x, int y) {
        List<AccessibilityNodeInfo> roots = roots();
        if (roots.isEmpty()) {
            return null;
        }
        Best best = new Best();
        try {
            for (int r = 0; r < roots.size(); r++) {
                findDeepest(roots.get(r), x, y, best);
                if (best.text != null) {
                    break;
                }
            }
        } finally {
            for (int r = 0; r < roots.size(); r++) {
                try {
                    roots.get(r).recycle();
                } catch (Exception ignored) {
                }
            }
        }
        return best.text;
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();

        List<AccessibilityWindowInfo> wins = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                wins = svc.getWindows();
            } catch (Exception e) {
                wins = null;
            }
        }

        if (wins != null && wins.size() > 0) {
            Collections.sort(wins, new Comparator<AccessibilityWindowInfo>() {
                @Override
                public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                    return layerOf(b) - layerOf(a);
                }
            });
            for (int i = 0; i < wins.size(); i++) {
                AccessibilityWindowInfo w = wins.get(i);
                if (w == null || isChrome(w)) {
                    continue;
                }
                AccessibilityNodeInfo r = null;
                try {
                    r = w.getRoot();
                } catch (Exception e) {
                    r = null;
                }
                if (r != null) {
                    out.add(r);
                }
            }
            for (int i = 0; i < wins.size(); i++) {
                try {
                    if (wins.get(i) != null) {
                        wins.get(i).recycle();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (out.isEmpty()) {
            AccessibilityNodeInfo r = null;
            try {
                r = svc.getRootInActiveWindow();
            } catch (Exception e) {
                r = null;
            }
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static int layerOf(AccessibilityWindowInfo w) {
        try {
            return w.getLayer();
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isChrome(AccessibilityWindowInfo w) {
        try {
            int t = w.getType();
            return t == AccessibilityWindowInfo.TYPE_SYSTEM
                    || t == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    || t == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                    || t == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;
        } catch (Exception e) {
            return false;
        }
    }

    private Rect screenRect() {
        Rect r = new Rect();
        try {
            android.util.DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            r.set(0, 0, dm.widthPixels, dm.heightPixels);
        } catch (Exception e) {
            r.set(-1000000, -1000000, 1000000, 1000000);
        }
        return r;
    }

    private static final class Walk {
        final List<TextLayout.Node> out;
        final Rect screen;
        final int px;
        final int py;
        int visited = 0;
        boolean truncated = false;
        Pick pick = null;

        Walk(List<TextLayout.Node> out, Rect screen, int px, int py) {
            this.out = out;
            this.screen = screen;
            this.px = px;
            this.py = py;
        }
    }

    private static final class Pick {
        final TextLayout.Node seg;
        final int area;
        final int charStart;

        Pick(TextLayout.Node seg, int area, int charStart) {
            this.seg = seg;
            this.area = area;
            this.charStart = charStart;
        }
    }

    private boolean walk(AccessibilityNodeInfo node, Walk w, int depth) {
        if (node == null) {
            return false;
        }
        if (depth > MAX_DEPTH) {
            w.truncated = true;
            return true;
        }
        if (w.visited >= MAX_WALK) {
            w.truncated = true;
            return true;
        }
        w.visited++;

        boolean childYielded = false;
        int count = 0;
        try {
            count = node.getChildCount();
        } catch (Exception e) {
            count = 0;
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
            } catch (Exception e) {
                c = null;
            }
            if (c == null) {
                continue;
            }
            if (walk(c, w, depth + 1)) {
                childYielded = true;
            }
            try {
                c.recycle();
            } catch (Exception ignored) {
            }
        }

        String t = nodeText(node);
        if (t == null) {
            return childYielded;
        }
        if (childYielded) {
            return true;
        }

        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return false;
        }
        if (r.isEmpty()) {
            return false;
        }
        if (!Rect.intersects(r, w.screen)) {
            return false;
        }

        if (w.out.size() >= MAX_SEG) {
            w.truncated = true;
            if (!r.contains(w.px, w.py)) {
                return true;
            }
        }

        TextLayout.Node seg = new TextLayout.Node(t, r.left, r.top, r.right, r.bottom);
        w.out.add(seg);

        if (r.contains(w.px, w.py)) {
            int area = r.width() * r.height();
            if (w.pick == null || area < w.pick.area) {
                w.pick = new Pick(seg, area, charStartIn(node, w.px, w.py));
            }
        }
        return true;
    }

    private String join(List<TextLayout.Node> segs, Pick pick) {
        int idx = indexOf(segs, pick.seg);
        if (idx < 0) {
            return joinBelow(segs, pick.seg.top);
        }
        Sink s = new Sink();
        String head = pick.seg.text;
        if (pick.charStart > 0 && pick.charStart < head.length()) {
            head = head.substring(pick.charStart);
        }
        if (readScope == Prefs.READ_ONE) {
            head = firstSentence(head);
        }
        s.addHead(head);
        if (readScope != Prefs.READ_SCREEN) {
            return s.text();
        }
        for (int i = idx + 1; i < segs.size(); i++) {
            s.add(segs.get(i).text);
        }
        return s.text();
    }

    private String joinBelow(List<TextLayout.Node> segs, int y) {
        Sink s = new Sink();
        for (int i = 0; i < segs.size(); i++) {
            TextLayout.Node n = segs.get(i);
            if (n.bottom <= y) {
                continue;
            }
            s.add(n.text);
            if (readScope != Prefs.READ_SCREEN) {
                break;
            }
        }
        String out = s.text();
        if (readScope == Prefs.READ_ONE) {
            return firstSentence(out);
        }
        return out;
    }

    private static String firstSentence(String s) {
        if (s == null || s.length() == 0) {
            return s;
        }
        final String puncts = "。！？；\n.!?";
        for (int i = 0; i < s.length(); i++) {
            if (puncts.indexOf(s.charAt(i)) >= 0) {
                return s.substring(0, i + 1);
            }
        }
        return s;
    }

    private static int indexOf(List<TextLayout.Node> segs, TextLayout.Node target) {
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static final class Sink {
        final StringBuilder sb = new StringBuilder();
        String prev = null;

        void addHead(String raw) {
            String t = TextLayout.clean(raw);
            if (t == null) {
                return;
            }
            sb.append(t);
            prev = t;
        }

        void add(String raw) {
            String t = TextLayout.clean(raw);
            if (t == null) {
                return;
            }
            if (prev != null && TextCompare.sameLine(prev, t)) {
                return;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(t);
            prev = t;
        }

        String text() {
            return sb.toString().trim();
        }
    }

    private int charStartIn(AccessibilityNodeInfo node, int x, int y) {
        if (node == null || Build.VERSION.SDK_INT < 26) {
            return 0;
        }
        try {
            try {
                node.refresh();
            } catch (Exception ignored) {
            }
            CharSequence cs = null;
            try {
                cs = node.getText();
            } catch (Exception ignored) {
            }
            if (cs == null) {
                return 0;
            }
            String raw = cs.toString();
            int lead = TextLayout.leadingWhitespace(raw);
            int len = TextLayout.trimmedLength(raw);
            if (len <= 0) {
                return 0;
            }

            String key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
            int[] tries = new int[]{
                    Math.min(raw.length(), MAX_CHAR_REQ),
                    Math.min(raw.length(), 2000),
                    Math.min(raw.length(), 500),
            };
            for (int t = 0; t < tries.length; t++) {
                int count = tries[t];
                if (count <= 0) {
                    continue;
                }
                RectF[] rects = requestRects(node, key, 0, count);
                if (rects == null) {
                    continue;
                }
                int offset = nearestChar(rects, x, y);
                if (offset < 0) {
                    continue;
                }
                if (offset > raw.length()) {
                    offset = raw.length();
                }
                int rel = TextLayout.sentenceStart(raw, offset) - lead;
                if (rel < 0) {
                    rel = 0;
                }
                if (rel > len) {
                    rel = len;
                }
                return rel;
            }
            return 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private RectF[] requestRects(AccessibilityNodeInfo node, String key, int start, int count) {
        try {
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start);
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, count);
            if (!node.refreshWithExtraData(key, args)) {
                return null;
            }
            Bundle extras = node.getExtras();
            if (extras == null) {
                return null;
            }
            Object raw = extras.get(key);
            if (raw instanceof RectF[] && ((RectF[]) raw).length > 0) {
                return (RectF[]) raw;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int nearestChar(RectF[] rects, float x, float y) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            RectF r = rects[i];
            if (r == null || r.isEmpty()) {
                continue;
            }
            float dx = 0f;
            float dy = 0f;
            if (x < r.left) {
                dx = r.left - x;
            } else if (x > r.right) {
                dx = x - r.right;
            }
            if (y < r.top) {
                dy = r.top - y;
            } else if (y > r.bottom) {
                dy = y - r.bottom;
            }
            double d = (double) dx * dx + (double) dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private void findDeepest(AccessibilityNodeInfo node, int x, int y, Best best) {
        if (node == null) {
            return;
        }
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return;
        }

        if (r.contains(x, y)) {
            String t = nodeText(node);
            if (t != null) {
                int area = r.width() * r.height();
                if (best.text == null || area < best.area) {
                    best.text = t;
                    best.area = area;
                }
            }
        }

        int count;
        try {
            count = node.getChildCount();
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                continue;
            }
            if (child != null) {
                findDeepest(child, x, y, best);
                try {
                    child.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static class Best {
        String text = null;
        int area = Integer.MAX_VALUE;
    }

    private String nodeText(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        try {
            CharSequence t = node.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (s.length() > 0) {
                    return s;
                }
            }
            CharSequence d = node.getContentDescription();
            if (d != null) {
                String s = d.toString().trim();
                if (s.length() > 0) {
                    return s;
                }
            }
        } catch (Exception e) {
            return null;
        }
        for (int i = 0; i < EXTRA_GETTERS.length; i++) {
            java.lang.reflect.Method m = EXTRA_GETTERS[i];
            if (m == null) {
                continue;
            }
            try {
                CharSequence cs = (CharSequence) m.invoke(node);
                if (cs != null) {
                    String s = cs.toString().trim();
                    if (s.length() > 0) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
