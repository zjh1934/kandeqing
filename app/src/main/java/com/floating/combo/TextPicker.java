package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityWindowInfo;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 3.8 新增：低延迟、抓得全的取词器。
 *
 * 旧的 TextGrabber.grabFrom(x,y) 是「点字之后才开始遍历整棵无障碍树」，
 * 一屏几千个节点、每节点数次跨进程调用，注释自己都写了「可能上百毫秒」；
 * 而且只有点击点严格落在某个文字节点 bounds 内才算命中，落在行间距 /
 * 字间空隙 / padding 就退回 joinBelow，点的那一行整个跳过 —— 低视力用户
 * 点不准，必然高发「有些字抓不到」。
 *
 * 这里把两件事拆开：
 *   1) prepare()：进选字态时就在后台线程把整屏文字预扫成**纯数据**缓存，
 *      不持有任何 AccessibilityNodeInfo；点字前的那一两秒已经把活干完。
 *   2) pick()：点字时只做纯计算（容差命中 + 拼串），主线程直接调、零 IPC、
 *      零线程切换，抓字延迟降到接近 0。
 *
 * 原 TextGrabber 一行未动，作为缓存未就绪 / 失效时的兜底，
 * 所以 verify_grabber.py 的镜像断言仍然成立，回归风险可控。
 */
public final class TextPicker {

    /** 预扫节点预算。比主路径宽一倍，长列表后半也能收进来 */
    private static final int MAX_WALK = 20000;
    /** 一屏最多收多少段 */
    private static final int MAX_SEG = 800;
    private static final int MAX_DEPTH = 500;
    /** 缓存有效期：窗口切走 / 滚屏后别拿旧内容糊弄 */
    private static final long CACHE_TTL_MS = 8000L;
    /** 容差命中半径（dp）。点不中任何 bounds 时，取最近的一段 */
    private static final float TOLERANCE_DP = 96f;

    private final AccessibilityService svc;
    private final float density;

    private List<TextLayout.Node> cache = null;
    private int cacheSeq = -1;
    private long cacheTime = 0L;

    public TextPicker(AccessibilityService svc) {
        this.svc = svc;
        float d = 1f;
        try {
            d = svc.getResources().getDisplayMetrics().density;
        } catch (Exception ignored) {
        }
        this.density = d > 0f ? d : 1f;
    }

    /**
     * 后台线程调用：预扫整屏，只把纯数据（文字 + 矩形）放进缓存。
     * 不加方法级锁，免得预扫的几百毫秒把主线程 pick() 堵住。
     */
    public void prepare(int seq) {
        List<TextLayout.Node> segs = null;
        try {
            segs = collect();
        } catch (Throwable t) {
            Log.e("pick", "预扫抛异常", t);
        }
        if (segs != null && !segs.isEmpty()) {
            TextLayout.sortReadingOrder(segs);
        } else {
            segs = null;
        }
        synchronized (this) {
            cache = segs;
            cacheSeq = seq;
            cacheTime = System.currentTimeMillis();
        }
    }

    /** 用户取消 / 放大 / 窗口切走：缓存立即作废 */
    public void invalidate() {
        synchronized (this) {
            cache = null;
            cacheSeq = -1;
            cacheTime = 0L;
        }
    }

    /**
     * 主线程可调用：纯计算命中，返回要朗读的整串；缓存不可用返回 null，
     * 调用方应退回 TextGrabber.grabFrom。
     */
    public String pick(int x, int y, int seq) {
        List<TextLayout.Node> segs;
        int cseq;
        long ctime;
        synchronized (this) {
            segs = cache;
            cseq = cacheSeq;
            ctime = cacheTime;
        }
        if (segs == null || segs.isEmpty() || cseq != seq
                || System.currentTimeMillis() - ctime > CACHE_TTL_MS) {
            return null;
        }
        try {
            return joinFrom(segs, hit(segs, x, y), x, y);
        } catch (Throwable t) {
            Log.e("pick", "命中拼装抛异常", t);
            return null;
        }
    }

    // ---------- 命中 ----------

    /**
     * 三级命中：精确 contains（取面积最小的）→ 容差最近段 → -1（交给 joinBelow）。
     * 容差是「抓到更多字」的关键：点在行间距、字间空隙、padding 上时，
     * 取距离最近的那一段，而不是把点击点上方整行丢掉。
     */
    private int hit(List<TextLayout.Node> segs, int x, int y) {
        int exact = -1;
        int exactArea = Integer.MAX_VALUE;
        for (int i = 0; i < segs.size(); i++) {
            TextLayout.Node n = segs.get(i);
            if (x >= n.left && x <= n.right && y >= n.top && y <= n.bottom) {
                int area = (n.right - n.left) * (n.bottom - n.top);
                if (area < exactArea) {
                    exactArea = area;
                    exact = i;
                }
            }
        }
        if (exact >= 0) {
            return exact;
        }
        float tol = TOLERANCE_DP * density;
        double limit = (double) tol * tol;
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < segs.size(); i++) {
            TextLayout.Node n = segs.get(i);
            double dx = 0, dy = 0;
            if (x < n.left) {
                dx = n.left - x;
            } else if (x > n.right) {
                dx = x - n.right;
            }
            if (y < n.top) {
                dy = n.top - y;
            } else if (y > n.bottom) {
                dy = y - n.bottom;
            }
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return bestD <= limit ? best : -1;
    }

    /**
     * 从命中段开始，接上阅读顺序里后面的所有段。
     * charStart 用「点击点在段宽里的比例」估算字符下标，再往回找句读 ——
     * 全部是纯计算，不发 refreshWithExtraData（那是一次同步跨进程调用）。
     */
    private String joinFrom(List<TextLayout.Node> segs, int idx, int x, int y) {
        if (idx < 0) {
            return joinBelow(segs, y);
        }
        StringBuilder sb = new StringBuilder();
        TextLayout.Node head = segs.get(idx);
        // 这一段是精确点中的，还是容差找来的最近段
        boolean exact = x >= head.left && x <= head.right
                && y >= head.top && y <= head.bottom;
        String t = head.text;
        int start = charStart(head, x, exact);
        if (start > 0 && start < t.length()) {
            t = t.substring(start);
        }
        append(sb, t);
        for (int i = idx + 1; i < segs.size(); i++) {
            append(sb, segs.get(i).text);
        }
        String out = sb.toString().trim();
        return out.length() == 0 ? null : out;
    }

    private int charStart(TextLayout.Node n, int x, boolean exact) {
        // 缓存里没有逐字位置（那要一次同步 IPC），按比例估算本来就不精确。
        // 短段或容差命中一律从段首读，否则会把第一个字切掉 ——
        // 那是「抓到更多字」的倒退。只有精确点中的长段才按比例找句读。
        if (!exact || n.text.length() < 40) {
            return 0;
        }
        int w = n.right - n.left;
        if (w <= 0 || n.text.length() == 0) {
            return 0;
        }
        float ratio = (x - n.left) / (float) w;
        if (ratio < 0f) {
            ratio = 0f;
        }
        if (ratio > 1f) {
            ratio = 1f;
        }
        int off = (int) (ratio * n.text.length());
        if (off > n.text.length()) {
            off = n.text.length();
        }
        return TextLayout.sentenceStart(n.text, off);
    }

    /** 最后兜底：一个段都没点时，接点击高度以下的段 */
    private String joinBelow(List<TextLayout.Node> segs, int y) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            TextLayout.Node n = segs.get(i);
            if (n.bottom <= y) {
                continue;
            }
            append(sb, n.text);
        }
        String out = sb.toString().trim();
        return out.length() == 0 ? null : out;
    }

    /** 顺手干掉连续重复段，避免父/容器把同一句话贴两遍 */
    private void append(StringBuilder sb, String s) {
        if (s == null || s.length() == 0) {
            return;
        }
        int tail = sb.length() - s.length();
        if (tail >= 0 && sb.substring(tail).equals(s)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(s);
    }

    // ---------- 预扫 ----------

    private List<TextLayout.Node> collect() {
        Rect screen = screenRect();
        List<AccessibilityNodeInfo> roots = roots();
        List<TextLayout.Node> out = new ArrayList<TextLayout.Node>();
        try {
            // 取第一个真正收到文字的窗口（层级最高），不把底层 Activity
            // 和 Dialog 的文字混成一锅粥。
            for (int r = 0; r < roots.size(); r++) {
                out.clear();
                Walk w = new Walk(out, screen);
                walk(roots.get(r), w, 0);
                if (!out.isEmpty()) {
                    return out;
                }
            }
        } finally {
            for (int i = 0; i < roots.size(); i++) {
                try {
                    roots.get(i).recycle();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private static final class Walk {
        final List<TextLayout.Node> out;
        final Rect screen;
        int visited = 0;

        Walk(List<TextLayout.Node> out, Rect screen) {
            this.out = out;
            this.screen = screen;
        }
    }

    /** 后序遍历：拿最靠下的文字节点；父文本只在与直接子文本完全相同时才去重 */
    private boolean walk(AccessibilityNodeInfo node, Walk w, int depth) {
        if (node == null || depth > MAX_DEPTH || w.visited >= MAX_WALK
                || w.out.size() >= MAX_SEG) {
            return false;
        }
        w.visited++;

        String t = nodeText(node);
        // 只有自己有文字时才需要跟直接子节点去重，省掉大量 HashSet 分配
        Set<String> childTexts = t == null ? null : new HashSet<String>();
        int count = 0;
        try {
            count = node.getChildCount();
        } catch (Exception ignored) {
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
            } catch (Exception ignored) {
            }
            if (c == null) {
                continue;
            }
            if (childTexts != null) {
                String ct = nodeText(c);
                if (ct != null) {
                    childTexts.add(ct);
                }
            }
            walk(c, w, depth + 1);
            try {
                c.recycle();
            } catch (Exception ignored) {
            }
        }

        if (t == null || (childTexts != null && childTexts.contains(t))) {
            return false;
        }
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return false;
        }
        if (r.isEmpty() || !Rect.intersects(r, w.screen)) {
            return false;   // 只靠 bounds 过滤，不信任 isVisibleToUser
        }
        w.out.add(new TextLayout.Node(t, r.left, r.top, r.right, r.bottom));
        return true;
    }

    /**
     * 3.8 抓得更全：正文 → 内容描述 → hintText(26) → tooltipText(28)
     * → stateDescription(30)。旧实现只读前两个，图标按钮、空输入框、
     * 自定义控件常常一个字都收不到。
     */
    private String nodeText(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        String s = clean(node.getText());
        if (s != null) {
            return s;
        }
        s = clean(node.getContentDescription());
        if (s != null) {
            return s;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                s = clean(node.getHintText());
                if (s != null) {
                    return s;
                }
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                s = clean(node.getTooltipText());
                if (s != null) {
                    return s;
                }
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                s = clean(node.getStateDescription());
                if (s != null) {
                    return s;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String clean(CharSequence cs) {
        if (cs == null) {
            return null;
        }
        try {
            return TextLayout.clean(cs.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Rect screenRect() {
        Rect r = new Rect();
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager)
                    svc.getSystemService(Context.WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                    && wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
            } else {
                dm.setTo(svc.getResources().getDisplayMetrics());
            }
            r.set(0, 0, dm.widthPixels, dm.heightPixels);
        } catch (Exception e) {
            r.set(-1000000, -1000000, 1000000, 1000000);
        }
        return r;
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
                public int compare(AccessibilityWindowInfo a,
                                   AccessibilityWindowInfo b) {
                    return layerOf(b) - layerOf(a);
                }
            });
            for (int i = 0; i < wins.size(); i++) {
                AccessibilityWindowInfo w = wins.get(i);
                if (w == null || isChrome(w)) {
                    continue;
                }
                try {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) {
                        out.add(r);
                    }
                } catch (Exception ignored) {
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
            try {
                AccessibilityNodeInfo r = svc.getRootInActiveWindow();
                if (r != null) {
                    out.add(r);
                }
            } catch (Exception ignored) {
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
}
