package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 3.9 重做：低延迟、抓得全、断句准、失败有反馈。
 *
 * 3.8 的四个残留问题，根子都在同一处：
 *   ① 预扫 prepare() 和点字兜底 grabFrom() 排在同一个 grabH 上。
 *      用户点球后立刻点字，缓存还没好，兜底任务必须等整树预扫跑完才开始，
 *      自己再遍历一遍 —— 延迟翻倍，期间一个反馈都没有。
 *   ② collect() 只取「第一个收到文字的窗口」就 return。复杂界面
 *      （Dialog / WebView / 分屏 / 多浮层）正文常在后面的窗口，直接读不到。
 *   ③ 断句 charStart 只用 x / 段宽 × 全文长度，丢掉 y（行号）；
 *      长文自动换行时点在行首一律估到全文前 10%，再被 sentenceStart 推到篇头。
 *   ④ 缓存只绑 pickSeq + 8 秒 TTL，不认滚屏 / 内容变化，命中旧 bounds。
 *
 * 3.9 的做法：
 *   - prepareQuick()：点球时主线程做一次**极浅**扫描（预算 300 节点），
 *     立刻发布可用快照。用户多快点字都有缓存，不必等后台。
 *   - prepare()：独立后台线程做完整多窗口扫描 + 逐字位置预取，覆盖快照。
 *   - 逐字位置在**预扫阶段**取（后台，不占点字），转成纯数据 RectF[] 缓存；
 *     点字时 pick() 仍是纯计算，二维最近字符 → 句子起点，零 IPC、零线程切换。
 *   - 多窗口按 layer 分组收集，命中优先上层窗口，窗口之间不混排。
 *   - 拿不到逐字位置时按 y 比例估行（比 x 比例准一个数量级），
 *     且句起点只在命中字附近找，绝不静默退 0。
 */
public final class TextPicker {

    private static final int MAX_WALK = 30000;
    private static final int MAX_SEG = 1200;
    private static final int MAX_DEPTH = 500;
    /** 点球时同步浅扫的预算：小到不卡主线程，又保证缓存立刻可用 */
    private static final int QUICK_MAX_WALK = 300;
    private static final int QUICK_MAX_DEPTH = 6;
    /** 逐字位置请求预算：只对最长的几段取，别把预扫拖成几秒 */
    private static final int MAX_CHAR_NODES = 4;
    private static final int MAX_CHAR_REQ = 20000;
    private static final long CACHE_TTL_MS = 10000L;
    private static final float TOLERANCE_DP = 96f;
    /** 长于此长度才值得找句读 / 请求逐字位置 */
    private static final int LONG_TEXT = 40;

    private final AccessibilityService svc;
    private final float density;

    private Snapshot cache = null;

    public TextPicker(AccessibilityService svc) {
        this.svc = svc;
        float d = 1f;
        try {
            d = svc.getResources().getDisplayMetrics().density;
        } catch (Exception ignored) {
        }
        this.density = d > 0f ? d : 1f;
    }

    // ---------- 缓存数据结构（全是纯数据，不持有 AccessibilityNodeInfo） ----------

    /** 一个窗口里按阅读顺序排好的文字段 */
    private static final class WinSegs {
        final int layer;
        final List<TextLayout.Node> segs = new ArrayList<TextLayout.Node>();

        WinSegs(int layer) {
            this.layer = layer;
        }
    }

    /** 一段文字的逐字矩形；lead = 原文前导空白数（缓存里存的是 trim 后的串） */
    private static final class CharPos {
        final RectF[] rects;
        final int lead;

        CharPos(RectF[] rects, int lead) {
            this.rects = rects;
            this.lead = lead;
        }
    }

    private static final class Snapshot {
        final List<WinSegs> wins;
        final Map<TextLayout.Node, CharPos> chars;
        final int seq;
        final long time;
        final boolean enhanced;

        Snapshot(List<WinSegs> wins, Map<TextLayout.Node, CharPos> chars,
                 int seq, boolean enhanced) {
            this.wins = wins;
            this.chars = chars;
            this.seq = seq;
            this.time = System.currentTimeMillis();
            this.enhanced = enhanced;
        }

        boolean usable(int s) {
            return wins != null && !wins.isEmpty() && seq == s
                    && System.currentTimeMillis() - time <= CACHE_TTL_MS;
        }
    }

    /**
     * 主线程调用、必须很快：只取活动窗口根节点，浅扫几层，
     * 让「点球 → 立刻点字」也有缓存可用。任何异常都吞掉，绝不影响点球。
     */
    public void prepareQuick(int seq) {
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) {
                return;
            }
            List<WinSegs> wins = new ArrayList<WinSegs>();
            try {
                WinSegs w = new WinSegs(0);
                Walk walk = new Walk(w.segs, screenRect(), QUICK_MAX_WALK,
                        QUICK_MAX_DEPTH, null, 0);
                walkNode(root, walk, 0);
                if (!w.segs.isEmpty()) {
                    TextLayout.sortReadingOrder(w.segs);
                    wins.add(w);
                }
            } finally {
                try {
                    root.recycle();
                } catch (Exception ignored) {
                }
            }
            if (!wins.isEmpty()) {
                publish(wins, new IdentityHashMap<TextLayout.Node, CharPos>(),
                        seq, false);
            }
        } catch (Throwable t) {
            Log.e("pick", "浅扫抛异常", t);
        }
    }

    /** 后台线程调用：完整多窗口扫描 + 逐字位置预取，覆盖粗快照 */
    public void prepare(int seq) {
        List<WinSegs> wins = null;
        Map<TextLayout.Node, CharPos> chars =
                new IdentityHashMap<TextLayout.Node, CharPos>();
        try {
            wins = collectFull(chars);
        } catch (Throwable t) {
            Log.e("pick", "预扫抛异常", t);
        }
        if (wins != null && !wins.isEmpty()) {
            publish(wins, chars, seq, true);
        }
    }

    public void invalidate() {
        synchronized (this) {
            cache = null;
        }
    }

    private void publish(List<WinSegs> wins,
                         Map<TextLayout.Node, CharPos> chars,
                         int seq, boolean enhanced) {
        Snapshot s = new Snapshot(wins, chars, seq, enhanced);
        synchronized (this) {
            Snapshot old = cache;
            // 只接受「更新或同代」的快照：
            //   - 上一轮选取态的后台任务迟到时，seq 更小，绝不能覆盖当前缓存；
            //   - 同一代里，增强快照优先，粗快照不回退增强快照。
            if (old == null || s.seq > old.seq
                    || (s.seq == old.seq && (enhanced || !old.enhanced))) {
                cache = s;
            }
        }
    }

    /** 主线程可调用：纯计算命中，返回要朗读的整串；无可用缓存返回 null */
    public String pick(int x, int y, int seq) {
        Snapshot s;
        synchronized (this) {
            s = cache;
        }
        if (s == null || !s.usable(seq)) {
            return null;
        }
        try {
            return readFrom(s, x, y);
        } catch (Throwable t) {
            Log.e("pick", "命中拼装抛异常", t);
            return null;
        }
    }

    /** 诊断用：只记结构数字，不记内容 */
    public String diagnose(int seq) {
        Snapshot s;
        synchronized (this) {
            s = cache;
        }
        if (s == null) {
            return "cache=null";
        }
        int segs = 0;
        for (int i = 0; i < s.wins.size(); i++) {
            segs += s.wins.get(i).segs.size();
        }
        return "cache wins=" + s.wins.size() + " segs=" + segs
                + " charNodes=" + s.chars.size()
                + " enhanced=" + s.enhanced + " seqOk=" + (s.seq == seq);
    }

    // ---------- 命中与拼装 ----------

    private String readFrom(Snapshot s, int x, int y) {
        // 按窗口层级从高到低找，第一个「点得到字」的窗口就是内容窗口。
        // 3.8 在这里只取第一个窗口就 return，复杂界面正文全丢 —— 改成
        // 所有窗口都收进来，命中时上层优先，但下层也能兜到。
        WinSegs bestWin = null;
        int bestIdx = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int w = 0; w < s.wins.size(); w++) {
            WinSegs win = s.wins.get(w);
            int idx = hit(win.segs, x, y);
            if (idx < 0) {
                continue;
            }
            TextLayout.Node n = win.segs.get(idx);
            boolean exact = x >= n.left && x <= n.right
                    && y >= n.top && y <= n.bottom;
            int score = (exact ? 0 : 1000) + w;
            if (score < bestScore) {
                bestScore = score;
                bestWin = win;
                bestIdx = idx;
            }
        }

        StringBuilder sb = new StringBuilder();
        if (bestWin == null) {
            // 一个都没点中：接点击高度以下的段，并给出明确标记让上层提示
            for (int w = 0; w < s.wins.size(); w++) {
                appendBelow(sb, s.wins.get(w).segs, y);
            }
            String out = sb.toString().trim();
            return out.length() == 0 ? null : out;
        }

        TextLayout.Node head = bestWin.segs.get(bestIdx);
        int start = startIn(s, head, x, y);
        String t = head.text;
        if (start > 0 && start < t.length()) {
            t = t.substring(start);
        }
        append(sb, t);
        for (int i = bestIdx + 1; i < bestWin.segs.size(); i++) {
            append(sb, bestWin.segs.get(i).text);
        }
        // 后续窗口的段也接上（分屏 / 弹层后面的正文）
        boolean after = false;
        for (int w = 0; w < s.wins.size(); w++) {
            if (s.wins.get(w) == bestWin) {
                after = true;
                continue;
            }
            if (!after) {
                continue;
            }
            for (int i = 0; i < s.wins.get(w).segs.size(); i++) {
                append(sb, s.wins.get(w).segs.get(i).text);
            }
        }
        String out = sb.toString().trim();
        return out.length() == 0 ? null : out;
    }

    /**
     * 三级命中：精确 contains（取面积最小）→ 容差最近段 → -1。
     * 点在行间距 / 字间空隙 / padding 上时取最近的一段，不再把上面整行跳过。
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
     * 3.9 断句核心：先定位「命中了第几个字」，再在该字附近找句读。
     *
     * 有逐字矩形（后台预取的纯数据）→ 二维最近字符，dx²+dy²，x 和 y 都用上；
     * 没有 → 按 y 比例估行号（多行文本里 y 才是行号，3.8 用 x 比例必然错位）。
     *
     * sentenceNear 只在命中字前后找标点，**绝不因为找不到就返回 0**——
     * 3.8 的 sentenceStart 一路扫到段首、失败退 0，才出现「从篇头读起」。
     */
    private int startIn(Snapshot s, TextLayout.Node n, int x, int y) {
        String text = n.text;
        if (text == null || text.length() == 0) {
            return 0;
        }
        int off = -1;
        CharPos cp = s.chars.get(n);
        if (cp != null && cp.rects != null) {
            int k = nearestChar(cp.rects, x, y);
            if (k >= 0) {
                off = k - cp.lead;
                if (off < 0) {
                    off = 0;
                }
                if (off > text.length()) {
                    off = text.length();
                }
            }
        }
        if (off < 0) {
            // 降级：y 比例 ≈ 行号比例，x 只做行内微调（权重 0.15）
            int h = n.bottom - n.top;
            int w = n.right - n.left;
            float ry = h > 0 ? (y - n.top) / (float) h : 0f;
            float rx = w > 0 ? (x - n.left) / (float) w : 0f;
            float ratio = Math.max(0f, Math.min(1f, ry * 0.85f + rx * 0.15f));
            // 短段（单行）不该按比例切掉首字
            if (text.length() < LONG_TEXT) {
                return 0;
            }
            off = (int) (ratio * text.length());
            if (off > text.length()) {
                off = text.length();
            }
        }
        return sentenceNear(text, off);
    }

    private static int nearestChar(RectF[] rects, float x, float y) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            RectF r = rects[i];
            if (r == null || r.isEmpty()) {
                continue;
            }
            double dx = 0, dy = 0;
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
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** 在命中字附近找句读；找不到就从命中字读起，绝不退到篇头 */
    private static int sentenceNear(String text, int off) {
        if (off <= 0) {
            return 0;
        }
        if (off >= text.length()) {
            off = text.length();
        }
        final int LOOK = 240;
        int floor = Math.max(0, off - LOOK);
        int s = scanNear(text, off, floor, "。！？；\n.!?");
        if (s > 0) {
            return s;
        }
        s = scanNear(text, off, floor, "，、：,:");
        if (s > 0) {
            return s;
        }
        return off;     // 从点的那个字读起，不是从整篇头读起
    }

    private static int scanNear(String text, int off, int floor, String puncts) {
        for (int i = off - 1; i >= floor; i--) {
            if (puncts.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            int s = i + 1;
            while (s < text.length() && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            if (s >= text.length()) {
                return Math.max(0, off - 1);    // 点在最后一句：从这句读，不退 0
            }
            return s;
        }
        return 0;
    }

    private void appendBelow(StringBuilder sb, List<TextLayout.Node> segs, int y) {
        for (int i = 0; i < segs.size(); i++) {
            TextLayout.Node n = segs.get(i);
            if (n.bottom <= y) {
                continue;
            }
            append(sb, n.text);
        }
    }

    private void append(StringBuilder sb, String s) {
        if (s == null || s.length() == 0) {
            return;
        }
        if (sb.length() >= s.length()
                && sb.substring(sb.length() - s.length()).equals(s)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(s);
    }

    // ---------- 预扫 ----------

    private static final class Walk {
        final List<TextLayout.Node> out;
        final Rect screen;
        final int maxWalk;
        final int maxDepth;
        final Map<TextLayout.Node, CharPos> chars;
        int charNodes;
        int visited = 0;

        Walk(List<TextLayout.Node> out, Rect screen, int maxWalk, int maxDepth,
             Map<TextLayout.Node, CharPos> chars, int charNodes) {
            this.out = out;
            this.screen = screen;
            this.maxWalk = maxWalk;
            this.maxDepth = maxDepth;
            this.chars = chars;
            this.charNodes = charNodes;
        }
    }

    private List<WinSegs> collectFull(Map<TextLayout.Node, CharPos> chars) {
        Rect screen = screenRect();
        List<WinSegs> out = new ArrayList<WinSegs>();
        List<Root> roots = roots();
        try {
            for (int r = 0; r < roots.size(); r++) {
                Root root = roots.get(r);
                WinSegs win = new WinSegs(root.layer);
                Walk w = new Walk(win.segs, screen, MAX_WALK, MAX_DEPTH,
                        chars, 0);
                walkNode(root.node, w, 0);
                if (!win.segs.isEmpty()) {
                    TextLayout.sortReadingOrder(win.segs);
                    out.add(win);   // 3.9：所有窗口都收，不再第一个就 return
                }
            }
        } finally {
            for (int i = 0; i < roots.size(); i++) {
                try {
                    roots.get(i).node.recycle();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** 后序遍历：拿最靠下的文字节点；父文本被已收子文本包含时跳过，避免整篇+分行重复 */
    private boolean walkNode(AccessibilityNodeInfo node, Walk w, int depth) {
        if (node == null || depth > w.maxDepth || w.visited >= w.maxWalk
                || w.out.size() >= MAX_SEG) {
            return false;
        }
        w.visited++;

        String t = nodeText(node);
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
            walkNode(c, w, depth + 1);
            try {
                c.recycle();
            } catch (Exception ignored) {
            }
        }

        if (t == null) {
            return false;
        }
        // 父节点带了整篇、子节点带分行：父文本包含任一子文本时丢父，
        // 保留分行段（更细的命中粒度）；否则父子都收。
        if (childTexts != null) {
            for (String ct : childTexts) {
                if (ct.length() > 0 && t.contains(ct)) {
                    return true;
                }
            }
        }
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return false;
        }
        if (r.isEmpty() || !Rect.intersects(r, w.screen)) {
            return false;   // 只靠 bounds 过滤，不硬信 isVisibleToUser
        }
        TextLayout.Node seg = new TextLayout.Node(
                t, r.left, r.top, r.right, r.bottom);
        w.out.add(seg);

        // 逐字位置在预扫阶段取（后台，不占点字路径），有总量预算
        if (w.chars != null && t.length() >= LONG_TEXT
                && w.charNodes < MAX_CHAR_NODES
                && Build.VERSION.SDK_INT >= 26) {
            CharPos cp = charPositions(node, t);
            if (cp != null) {
                w.chars.put(seg, cp);
                w.charNodes++;
            }
        }
        return true;
    }

    /** 取逐字矩形，转成纯数据缓存；nodeText 存的是 trim 后的串，记下 lead 校正 */
    private CharPos charPositions(AccessibilityNodeInfo node, String text) {
        try {
            try {
                node.refresh();
            } catch (Exception ignored) {
            }
            CharSequence rawCs = null;
            try {
                rawCs = node.getText();
            } catch (Exception ignored) {
            }
            if (rawCs == null) {
                return null;
            }
            String raw = rawCs.toString();
            int lead = TextLayout.leadingWhitespace(raw);
            String key = AccessibilityNodeInfo
                    .EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
            int[] tries = new int[]{
                    Math.min(raw.length(), MAX_CHAR_REQ),
                    Math.min(raw.length(), 2000),
                    Math.min(raw.length(), 500),
            };
            for (int i = 0; i < tries.length; i++) {
                if (tries[i] <= 0) {
                    continue;
                }
                RectF[] rects = requestRects(node, key, 0, tries[i]);
                if (rects != null && rects.length > 0) {
                    return new CharPos(rects, lead);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private RectF[] requestRects(AccessibilityNodeInfo node, String key,
                                 int start, int count) {
        try {
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo
                    .EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start);
            args.putInt(AccessibilityNodeInfo
                    .EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, count);
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

    /**
     * 正文 → 内容描述 → hintText(26) → tooltipText(28) → stateDescription(30)。
     * 顺手剥掉 WebView 常见的 HTML 标签，别把 "<p>" 念出来。
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
            s = clean(node.getHintText());
            if (s != null) {
                return s;
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            s = clean(node.getTooltipText());
            if (s != null) {
                return s;
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            s = clean(node.getStateDescription());
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private String clean(CharSequence cs) {
        if (cs == null) {
            return null;
        }
        try {
            String s = cs.toString();
            if (s.indexOf('<') >= 0 && s.indexOf('>') > s.indexOf('<')) {
                s = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ");
            }
            return TextLayout.clean(s);
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

    private static final class Root {
        final AccessibilityNodeInfo node;
        final int layer;

        Root(AccessibilityNodeInfo node, int layer) {
            this.node = node;
            this.layer = layer;
        }
    }

    private List<Root> roots() {
        List<Root> out = new ArrayList<Root>();
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
                        out.add(new Root(r, layerOf(w)));
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
                    out.add(new Root(r, 0));
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
