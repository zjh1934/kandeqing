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

/**
 * 抓屏幕文字。
 *
 *   1) 取窗口 —— getRootInActiveWindow() 只在窗口内容变化时才有数据，经常拿 null，
 *      也拿不到弹窗 / 输入法 / 分屏的另一半。改走 getWindows() 按层级从上往下扫，
 *      哪个窗口里点得到字就用哪个，拿不到再退回 getRootInActiveWindow()。
 *   2) 收节点 —— 一趟后序遍历收完（旧实现每个节点把子树递归两遍，O(n²) + 双倍 IPC），
 *      顺手记下点中的那一段；带可见性过滤（不读滚出屏的）和节点数上限（防病态树卡死主线程）。
 *   3) 排顺序 —— 先按 top 纵向分带成行，行内按 left 排。只按 (top,left) 会把多列布局读乱。
 *   4) 精确到句 —— 拿这段每个字的位置，算点中的是第几个字，回退到最近的句读。
 *   5) 续读 —— 把阅读顺序里排在这段之后的都接上，读完一屏。
 *
 * 只在【未放大】时调用 —— 放大状态下不抓字，避免坐标体系混淆。
 */
public class TextGrabber {

    /** 一趟最多走多少个节点。超长列表之类的病态树会把主线程拖死（同行方案经验值 10000） */
    private static final int MAX_WALK = 10000;
    /**
     * 兜底路径的节点预算。3.5 给兜底也套了 MAX_WALK，结果是主路径被截断后
     * 兜底同样读不全；3.6 放宽三倍，深度上限仍保留，防爆栈的底线不动。
     */
    private static final int FALLBACK_MAX_WALK = 30000;
    /**
     * 一屏最多收多少段。3.9.7 起它【只截收录、不截遍历】：
     * 旧版在 walk() 入口一到 400 就 return true，整棵子树不再访问，
     * 长列表（>400 段）点后半段必然丢失。AOSP 源码里预取上限
     * （MAX_NUMBER_OF_PREFETCHED_NODES=50 / 旧版 BATCH_SIZE）从来只约束
     * 一次请求附带的预取批次，不是应用层的树遍历预算，不能照搬成访问闸。
     * 真正的资源闸是 MAX_WALK / MAX_DEPTH。长列表一屏常见 500+ 段，
     * 这里放宽到 2000，同时保证触段永远豁免（见 walk()）。
     */
    private static final int MAX_SEG = 2000;
    /**
     * 递归深度上限。正常界面撑死几十层，这里给到 500 很宽裕；
     * 卡住是为了防病态树（几万层的链表式布局）把栈打爆 ——
     * 触顶时这一趟收不到东西，grabFrom 会退回 grabAt 兜底。
     */
    private static final int MAX_DEPTH = 500;
    /**
     * 一次最多取多少个字符位置。
     * 官方上限 EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH = 20000，
     * 超了请求直接失败 —— 旧实现把整段长度原样传进去，长文章必然拿不到位置。
     */
    private static final int MAX_CHAR_REQ = 20000;

    private final AccessibilityService svc;

    /**
     * 3.7 密码保护开关的当前值。每次 grabFrom 开头重读一次 ——
     * 界面上刚拨完开关，下一次点字就生效，不用重启无障碍服务。
     */
    private boolean skipPassword = false;

    /**
     * 3.9.4「读多少」。跟 skipPassword 一样每次 grabFrom 开头重读，
     * 界面上刚改完选项，下一次点字就生效，不用重启无障碍服务。
     */
    private int readScope = Prefs.DEF_READ_SCOPE;

    public TextGrabber(AccessibilityService svc) {
        this.svc = svc;
    }

    /** 从点击处开始，往后续读 */
    public String grabFrom(int x, int y) {
        // 官方契约：getBoundsInScreen 与逐字位置 RectF 都会被放大倍率缩放、
        // 按视口偏移调整；不受放大影响的 IN_WINDOW 逐字位置是 API 36，
        // 本工程 compileSdk 30 够不着。所以【只在未放大态抓字】不是权宜之计，
        // 是唯一自洽解。旧版只写在类注释里，没有断言。
        if (magnified()) {
            Log.w("grab", "系统处于放大态，坐标不自洽，本次不抓字");
            return null;
        }
        Prefs p = new Prefs(svc);
        skipPassword = p.passwordGuard();
        readScope = p.readScope();
        String out = grabFromWindows(x, y);
        if (out != null && out.length() > 0) {
            return out;
        }
        return grabAt(x, y);
    }

    /** 系统当前是否处于显示放大态（ API 24+ 的 MagnificationController ） */
    private boolean magnified() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            return svc.getMagnificationController().getScale() > 1.0f;
        } catch (Throwable ignored) {
            return false;   // 查不到就按未放大处理，别把正常抓字挡掉
        }
    }

    /**
     * 3.9.5：按【触点坐标】在全部窗口里选段，而不是「第一个命中的窗口赢」。
     *
     * 旧实现两层毛病，正好对上「点这里读那里」和「分层多的页面经常抓不到字」：
     *   1) 每扫一个窗口，只要 w.pick != null 就立刻 return —— 上层窗口哪怕只是
     *      个包裹型大节点（整张卡片 / 标题栏）也直接胜出，下层真正的正文没机会。
     *   2) 命中只认 r.contains(px,py) 严格包含。多层分级页面里文字节点之间
     *      全是行间距 / padding / 卡片留白，点在空隙里一个都命中不了，
     *      最后 fallback 用的是「最上层那个有内容的窗口」，从不相干的地方读起。
     *
     * 现在把所有窗口都扫完，按触点坐标全局比较：
     *   精确命中 → 跨窗口取面积最小的那段（离字最近的那一小块）
     *   无精确命中 → 跨窗口取离触点最近的一段，距离在 48dp 容差内即命中
     *   两遍都没命中 → 关掉 isVisibleToUser 过滤再扫一遍（救 ROM 误报），
     *                  仍不行才用「最近段所在窗口」从点击高度往下接。
     */
    private String grabFromWindows(int x, int y) {
        List<AccessibilityNodeInfo> roots = roots();
        if (roots.isEmpty()) {
            return null;
        }
        try {
            Rect screen = screenRect();
            float dens = 3f;
            try {
                dens = svc.getResources().getDisplayMetrics().density;
            } catch (Exception ignored) {
            }
            final long tol = (long) (48 * dens);   // 48dp 容差，约 144px @3x

            // 单次扫描即可：可见性误判由 walk 里「触点落在块内就收下」兜住，
            // 不再整窗重扫一遍（重复 getChild 有回收风险，也会拖慢点字）。
            return scanWindows(x, y, true, roots, screen, tol, true);
        } finally {
            for (int r = 0; r < roots.size(); r++) {
                try {
                    roots.get(r).recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 扫一遍所有窗口，返回按触点坐标选出的结果；没选中返回 null。 */
    private String scanWindows(int x, int y, boolean requireVisible,
                               List<AccessibilityNodeInfo> roots, Rect screen,
                               long tol, boolean allowFallback) {
        int bestArea = Integer.MAX_VALUE;
        Pick bestPick = null;
        List<TextLayout.Node> bestSegs = null;

        long bestNearDist = Long.MAX_VALUE;
        TextLayout.Node bestNearNode = null;
        int bestNearStart = 0;
        List<TextLayout.Node> bestNearSegs = null;

        List<TextLayout.Node> firstSegs = null;

        for (int r = 0; r < roots.size(); r++) {
            List<TextLayout.Node> segs = new ArrayList<TextLayout.Node>();
            Walk w = new Walk(segs, screen, x, y);
            w.requireVisible = requireVisible;
            w.nearTol = tol;
            walk(roots.get(r), w, 0);
            if (w.truncated) {
                Log.w("grab", "节点太多，收够上限就停了 walk=" + w.visited
                        + " seg=" + segs.size());
            }
            if (w.requireVisible
                    && (segs.isEmpty() || w.visibleSkipped > 0)) {
                // 3.9.7：官方 AccessibilityNodeInfo.java:2662-2675 写明
                // 「API 16-29 且放大启用时 isVisibleToUser 可能错误返回 false」。
                // 这里【重算快照，不重扫树】：被软过滤的段在遍历时已收进
                // w.hidden（纯数据，含 charStart），合并后再比一次即可。
                // 旧版对同一根再跑一遍 walk()，重复 getChild()、多一轮 IPC、
                // 还有节点回收风险，正是「点字延迟」和「读一会儿抓不到字」的来源。
                for (int h = 0; h < w.hidden.size(); h++) {
                    TextLayout.Node hidden = w.hidden.get(h);
                    segs.add(hidden);
                    consider(hidden, w);
                }
                w.requireVisible = false;
            }
            if (segs.isEmpty()) {
                continue;
            }
            TextLayout.sortReadingOrder(segs);
            if (firstSegs == null) {
                firstSegs = segs;
            }
            if (w.pick != null && w.pick.area < bestArea) {
                bestArea = w.pick.area;
                bestPick = w.pick;
                bestSegs = segs;
            }
            if (w.nearest != null && w.nearestDist < bestNearDist) {
                bestNearDist = w.nearestDist;
                bestNearNode = w.nearest;
                bestNearStart = w.nearestStart;
                bestNearSegs = segs;
            }
        }

        // 精确命中：跨窗口比面积，最小那块就是触点真正落在的那行字
        if (bestPick != null) {
            return join(bestSegs, bestPick);
        }
        // 点在行间 / 留白：取离触点最近的一段（y 权重已加倍）
        if (bestNearNode != null && bestNearDist <= tol) {
            int area = (bestNearNode.right - bestNearNode.left)
                    * (bestNearNode.bottom - bestNearNode.top);
            return join(bestNearSegs,
                    new Pick(bestNearNode, area, bestNearStart));
        }
        if (allowFallback) {
            // 连容差内都没有：用「离触点最近的那个窗口」，而不是最上层窗口
            List<TextLayout.Node> fb = (bestNearSegs != null)
                    ? bestNearSegs : firstSegs;
            if (fb != null) {
                return joinBelow(fb, y);
            }
        }
        return null;
    }

    /** 兜底：直接读命中的那一块，不切句 */
    public String grabAt(int x, int y) {
        List<AccessibilityNodeInfo> roots = roots();
        if (roots.isEmpty()) {
            return null;
        }
        Best best = new Best();
        try {
            for (int r = 0; r < roots.size(); r++) {
                findDeepest(roots.get(r), x, y, best);
                // 3.9.7：与主路径一致，所有窗口都扫完再按面积取最小那块，
                // 不再「第一个命中的窗口赢」—— 上层包裹型大节点会压住下层正文。
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

    // ---------- 窗口 ----------

    /**
     * 取所有可遍历的窗口根节点，按层级从高到低（上层优先）。
     *
     * getWindows() 需要 accessibilityFlags 里有 flagRetrieveInteractiveWindows
     * （配置里已经加了）。拿不到再退回 getRootInActiveWindow()。
     */
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
            // getWindows() 的返回顺序不保证是层级顺序，自己排
            Collections.sort(wins, new Comparator<AccessibilityWindowInfo>() {
                @Override
                public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                    return layerOf(b) - layerOf(a);
                }
            });
            for (int i = 0; i < wins.size(); i++) {
                AccessibilityWindowInfo w = wins.get(i);
                if (w == null || isChrome(w)) {
                    continue;   // 状态栏 / 导航栏 / 输入法 / 分屏条 / 我们自己的浮层
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
            // 根的生命周期和窗口信息无关，但窗口信息本身也要还回去
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

    /**
     * 系统装饰窗口 / 我们自己的浮层：一律不当内容窗口。
     *
     * 注意：Android 只有 TYPE_SYSTEM 一个类型同时代表状态栏和导航栏，
     * 没有 TYPE_STATUS_BAR / TYPE_NAVIGATION_BAR（这两个名字不存在，写了编译不过）。
     * TYPE_MAGNIFICATION_OVERLAY 是 API 33 才有的，compileSdk 30 引不到，
     * 它只是放大后的位图、没有文字节点，不排除也不影响抓字。
     */
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
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            // 3.9.5：触点是 getRawX/Y() 的物理屏幕坐标，这里必须也用物理尺寸。
            // 原来用 getResources().getDisplayMetrics()（不含虚拟导航栏），
            // 分屏 / 底部弹层 / 带导航栏的机型上，Rect.intersects 会把
            // 底部那块内容当成「滚出屏幕」直接丢掉。
            android.view.WindowManager wm = (android.view.WindowManager)
                    svc.getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
            } else {
                dm = svc.getResources().getDisplayMetrics();
            }
            r.set(0, 0, dm.widthPixels, dm.heightPixels);
        } catch (Exception e) {
            // 量不到就当无限大，等于不做屏幕过滤，别把内容筛没了
            r.set(-1000000, -1000000, 1000000, 1000000);
        }
        return r;
    }

    // ---------- 收集 ----------

    private static final class Walk {
        final List<TextLayout.Node> out;
        final Rect screen;
        final int px;
        final int py;
        boolean requireVisible = true;
        int visited = 0;
        boolean truncated = false;
        Pick pick = null;

        /**
         * 3.9.5：离触点最近的那一段（不要求严格落在 bounds 内）。
         * 多层分级页面里文字节点之间全是行间距 / 卡片留白 / padding，
         * 只认 r.contains(px,py) 会一个都命中不了 —— 这正是「经常抓不到字」。
         * 距离里 y 权重加倍：阅读是按行走的，上下偏一点比左右偏一点更贵。
         */
        TextLayout.Node nearest = null;
        long nearestDist = Long.MAX_VALUE;
        int nearestStart = 0;
        /**
         * 只有离触点在容差内的段才去取逐字位置。否则一屏 400 段每段
         * 都 refreshWithExtraData 一遍，点字延迟会回到 3.8 那个坑。
         */
        long nearTol = Long.MAX_VALUE;
        /** 被 isVisibleToUser 过滤掉的节点数：大于 0 才需要关过滤重算 */
        int visibleSkipped = 0;
        /**
         * 3.9.7：可见性只是软标记。被过滤的段照样收成纯数据放这里，
         * 关过滤时直接合并进快照重算，不再二次遍历节点树。
         * 每个元素都带 charStart，所以重算阶段完全不碰 AccessibilityNodeInfo。
         */
        final List<TextLayout.Node> hidden = new ArrayList<TextLayout.Node>();

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

    /**
     * 一趟后序遍历：返回这个子树有没有交出文字。
     *
     * 只在「有文字且子节点没交出文字」时才收录当前节点 ——
     * 这样拿到的是最靠下的那批文字节点，父节点不会和子节点重复贴一遍。
     *
     * 预算耗尽时返回 true（而不是 false）：让父节点知道"下面已经有内容了"，
     * 别把父节点整段再贴上来造成重复。
     */
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

        // 3.7：密码保护做成开关，默认关。
        // 关（默认）→ 3.6 行为：节点照收、照排序、照断句，验证码 / 金额 /
        //   自定义输入框不会被 ROM 误报成密码而读不到。
        // 开 → 3.5 行为：整段跳过密码框，公共场合不念明文。
        if (skipPassword && isPassword(node)) {
            Log.i("grab", "密码保护已开，跳过密码框");
            return childYielded;
        }
        // 3.9.7：按官方文本字段语义分层。body=true 才进连续正文串。
        boolean[] body = new boolean[1];
        String t = nodeText(node, body);
        if (t == null) {
            return childYielded;
        }
        if (childYielded) {
            return true;    // 文字在子节点里，当前节点这段就不重复了
        }

        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return false;
        }
        if (r.isEmpty()) {
            return false;   // 量不到位置，没法排序，宁可不收
        }
        if (!Rect.intersects(r, w.screen)) {
            return false;   // 整块都在屏幕外（滚出去的），不读
        }

        boolean touchInside = r.contains(w.px, w.py);
        boolean vis = visible(node);
        // 官方源码 AccessibilityNodeInfo.java:2662-2675：API 16-29 放大时
        // isVisibleToUser 可能误报 false。可见性只作软标记，遍历里绝不丢节点。
        boolean softHidden = w.requireVisible && !vis && !touchInside;
        if (softHidden) {
            w.visibleSkipped++;
        }

        // 预算只截「收录」，绝不截「遍历」：子节点上面已经全部走完了，
        // 这里到上限也只是不再往 out 放，命中段永远豁免。
        // 旧版在【入口】一到 400 就 return true，整棵子树不再访问 ——
        // 500 段的长列表点第 450 段会直接读不到，正是「经常抓不到字」的大头。
        if (w.out.size() >= MAX_SEG && !touchInside) {
            w.truncated = true;
            return true;
        }

        // 逐字位置在树遍历阶段一次算好、存成纯数据；后续重算命中/可见性
        // 都不再碰 AccessibilityNodeInfo（节点交给服务后即不可变，且随时 obsolete）。
        long d = distanceTo(r, w.px, w.py);
        int charStart = (d <= w.nearTol) ? charStartIn(node, w.px, w.py) : 0;
        TextLayout.Node seg = new TextLayout.Node(
                t, r.left, r.top, r.right, r.bottom, body[0], charStart);

        if (softHidden) {
            w.hidden.add(seg);      // 保留快照，关过滤时合并重算，不重扫树
            return true;
        }
        w.out.add(seg);
        consider(seg, w);
        return true;
    }

    /** 触点到矩形边界的距离，y 权重加倍（阅读是按行走的） */
    private static long distanceTo(Rect r, int x, int y) {
        long dx = 0, dy = 0;
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
        return dy * 2L + dx;
    }

    /** 纯数据比较：最近段 + 最小面积命中。不碰任何节点对象。 */
    private void consider(TextLayout.Node seg, Walk w) {
        Rect r = new Rect(seg.left, seg.top, seg.right, seg.bottom);
        long dist = distanceTo(r, w.px, w.py);
        if (w.nearest == null || dist < w.nearestDist) {
            w.nearest = seg;
            w.nearestDist = dist;
            w.nearestStart = seg.charStart;
        }
        if (dist == 0) {
            int area = (seg.right - seg.left) * (seg.bottom - seg.top);
            if (w.pick == null || area < w.pick.area) {
                w.pick = new Pick(seg, area, seg.charStart);
            }
        }
    }

    private static boolean visible(AccessibilityNodeInfo node) {
        try {
            return node.isVisibleToUser();
        } catch (Exception e) {
            return true;    // 拿不到就当可见，别把内容筛没了
        }
    }

    // ---------- 拼装 ----------

    /** 点中了某一段：从这段的句读处开始，接上阅读顺序里后面的所有段 */
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
        // 3.9.4「读多少」：只读一句时，连命中段本身也截到第一个句末标点。
        if (readScope == Prefs.READ_ONE) {
            head = firstSentence(head);
        }
        s.addHead(head);
        // 只有「读到屏底」才接后续段；段末 / 一句到此为止。
        if (readScope != Prefs.READ_SCREEN) {
            return s.text();
        }
        for (int i = idx + 1; i < segs.size(); i++) {
            TextLayout.Node n = segs.get(i);
            // 3.9.7：只有 BODY/LABEL 能进连续正文。hint / tooltip /
            // stateDescription / paneTitle 各有官方语义，命中时可读，
            // 但拼进正文串会把「请输入手机号」这类提示文字念成文章。
            if (!n.body) {
                continue;
            }
            s.add(n.text);
        }
        return s.text();
    }

    /** 没点中任何一段：把点击高度以下的都接上（同样尊重「读多少」） */
    private String joinBelow(List<TextLayout.Node> segs, int y) {
        Sink s = new Sink();
        for (int i = 0; i < segs.size(); i++) {
            TextLayout.Node n = segs.get(i);
            // 用 bottom 判：整段都在点击点上方才跳过。
            // 用 top 判会把"点击点落在这段中间"的那一段整个漏掉
            if (n.bottom <= y) {
                continue;
            }
            if (!n.body) {
                continue;
            }
            s.add(n.text);
            if (readScope != Prefs.READ_SCREEN) {
                break;      // 只取命中高度以下的第一段
            }
        }
        String out = s.text();
        if (readScope == Prefs.READ_ONE) {
            return firstSentence(out);
        }
        return out;
    }

    /**
     * 截到第一个句末标点（含）。找不到标点就整段返回 ——
     * 宁可多念一点，也不能把内容切成空串导致「这里没读到文字」。
     */
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

    /** 拼字符串的容器，顺手干掉连续重复段 */
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
                return;     // 只去「连续且完全相等」，绝不比包含 —— 否则会吞掉正文
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

    // ---------- 字符级定位 ----------

    /**
     * 应该从第几个字开始读。返回的下标是相对【trim 之后】的串。
     *
     * 注意：nodeText() 收录的是 trim 过的文本，而字符位置是按原文排的，
     * 有前导空白时两者下标差了一个偏移量 —— 不减掉就会切错位置（旧实现的 bug）。
     */
    private int charStartIn(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) {
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

            // 官方契约：EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY 是昂贵数据，
            // 请求前先查 getAvailableExtraData()；ARG_LENGTH 必须为正且 <= 20000；
            // RectF 可为 null（字符不存在或离屏）；refreshWithExtraData 返回
            // false 表示该 View 已不在视图树、节点 obsolete。旧版 20000/2000/500
            // 三级退让没有官方依据，还把「catch 里 return 0」留着 —— 那个 0
            // 就是「点中间一段却从整篇头开始读」的直接来源。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
                if (supportsExtraData(node, key)) {
                    int count = Math.min(raw.length(), MAX_CHAR_REQ);
                    if (count > 0) {
                        RectF[] rects = requestRects(node, key, 0, count);
                        if (rects == null) {
                            // false = 节点 obsolete / 数据不可用。不猜、不重试，
                            // 直接降级到按行估算，绝不返回 0。
                            Log.w("grab", "逐字位置不可用（节点可能已失效），按行估算起点");
                        } else {
                            int offset = nearestChar(rects, x, y);
                            if (offset >= 0) {
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
                        }
                    }
                }
            }
            return fallbackStart(node, x, y, raw, len, lead);
        } catch (Throwable ignored) {
            // 任何异常都走降级估算，不再静默退回篇头。
            return fallbackStartSafe(node, x, y);
        }
    }

    /** 预检节点是否支持该额外数据 key；列表拿不到就按支持处理，别一刀切 */
    private boolean supportsExtraData(AccessibilityNodeInfo node, String key) {
        try {
            java.util.List<String> keys = node.getAvailableExtraData();
            if (keys == null) {
                return true;
            }
            return keys.contains(key);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 异常路径的降级：重新取一次文本再按行估，绝不把 0 当答案 */
    private int fallbackStartSafe(AccessibilityNodeInfo node, int x, int y) {
        try {
            CharSequence cs = node.getText();
            String raw = (cs == null) ? "" : cs.toString();
            int lead = TextLayout.leadingWhitespace(raw);
            int len = TextLayout.trimmedLength(raw);
            if (len <= 0) {
                return 0;   // 空文本：本来就没内容可读，不是「从篇头读」
            }
            return fallbackStart(node, x, y, raw, len, lead);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 逐字位置拿不到时的降级估算。3.9.5 改成【按行落位】：
     *
     * 旧版直接拿 ry×0.85 + rx×0.15 当全文比例，多层分级页面里一个节点
     * 常是整张卡片 / 整块 WebView 文本，bounds 很大、内部还有子块；
     * 同一行里手指偏上偏下几像素，估出来的下标都会差出去 —— 这就是
     * 「点这里读那里」。现在先用 y 判断落在第几行，行内再按 x 估字，
     * 单行时 y 完全不参与，同一行上下点都落到同一个字附近。
     */
    private int fallbackStart(AccessibilityNodeInfo node, int x, int y,
                              String raw, int len, int lead) {
        if (raw == null || len < 40) {
            return 0;   // 短段单行：从段首读，别把第一个字切掉
        }
        Rect b = new Rect();
        try {
            node.getBoundsInScreen(b);
        } catch (Exception ignored) {
            return 0;
        }
        if (b.isEmpty() || raw.length() == 0) {
            return 0;
        }
        // 行数：文本里有换行就按换行数，否则按「高度 / 48px 行高」估自动换行
        int breaks = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\n') {
                breaks++;
            }
        }
        int lines = Math.max(1, Math.max(breaks + 1, b.height() / 48));
        float rx = Math.max(0f, Math.min(1f,
                (x - b.left) / (float) b.width()));
        float ratio;
        if (lines <= 1) {
            ratio = rx;                     // 单行：只用 x，y 抖动不再改下标
        } else {
            float ry = Math.max(0f, Math.min(0.999f,
                    (y - b.top) / (float) b.height()));
            int line = (int) (ry * lines);
            ratio = (line + rx) / lines;
        }
        ratio = Math.max(0f, Math.min(1f, ratio));
        int off = (int) (ratio * raw.length());
        if (off > raw.length()) {
            off = raw.length();
        }
        int rel = TextLayout.sentenceStart(raw, off) - lead;
        if (rel < 0) {
            rel = 0;
        }
        if (rel > len) {
            rel = len;
        }
        return rel;
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

    /** 起点固定是 0，所以下标就是第几个字 */
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

    // ---------- 兜底用的最深命中 ----------

    private void findDeepest(AccessibilityNodeInfo node, int x, int y, Best best) {
        findDeepest(node, x, y, best, 0);
    }

    /**
     * 兜底用的最深命中。
     *
     * 以前这里既没有深度上限也没有节点预算，只有递归：
     * 病态深的树（嵌套滚动容器、某些网页转成的节点树）会一路递归到
     * StackOverflowError。而调用方把 Throwable 一律吞成"这里没读到文字"，
     * 用户看到的就是"点了没反应"，日志里也只留一句抓字抛异常。
     *
     * 3.5 给兜底补了闸；3.6 保留 MAX_DEPTH 防爆栈，把节点预算放宽到
     * FALLBACK_MAX_WALK —— 偏偏正是主路径被闸断掉的时候才会走到兜底，
     * 不能再让兜底也读不全。
     */
    private void findDeepest(AccessibilityNodeInfo node, int x, int y,
                             Best best, int depth) {
        if (node == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            Log.w("grab", "兜底路径超深，不再往下找 depth=" + depth);
            return;
        }
        if (best.visited >= FALLBACK_MAX_WALK) {
            Log.w("grab", "兜底路径节点太多，收工 visited=" + best.visited);
            return;
        }
        best.visited++;
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return;
        }

        if (r.contains(x, y) && !(skipPassword && isPassword(node))) {
            String t = nodeText(node, new boolean[1]);
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
                findDeepest(child, x, y, best, depth + 1);
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
        /** 兜底路径也要有节点预算，否则深树会递归到爆栈 */
        int visited = 0;
    }

    // ---------- 取文本 ----------

    /** 3.7：密码保护开关打开时才用得到。读不到就当不是密码，别整屏读不出东西。 */
    private static boolean isPassword(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            return node.isPassword();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 节点的文字 + 它在官方语义里的角色。返回的一律是 trim 过的。
     *
     * 官方 AccessibilityNodeInfo 把文本分成七种不同语义，不是七种「正文」：
     *   getText()              节点正文                         → body=true
     *   getContentDescription() 节点内容描述（图标按钮替代文本） → body=true
     *   getHintText()          只对可输入文字的节点有效，是空输入框的标签
     *   getTooltipText()       工具提示
     *   getStateDescription()  节点状态描述
     *   getPaneTitle()         窗格标题，不代表窗格则为 null
     * 3.9.5 把后四类一律当正文收进连续朗读串，会念出「请输入手机号」这种
     * 提示文字。3.9.7 起：命中该节点时照读（用户确实点了它），但 join() /
     * joinBelow() 只接 body=true 的段。官方还明确给 EditText 设
     * contentDescription 会干扰服务描述与导航，所以这里不把 desc 当正文之外的特殊值。
     *
     * @param body 出参，[0] 表示这段能不能进连续正文串
     */
    private String nodeText(AccessibilityNodeInfo node, boolean[] body) {
        if (body == null || body.length == 0) {
            body = new boolean[1];
        }
        body[0] = true;
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
        } catch (Exception ignored) {
        }
        try {
            CharSequence d = node.getContentDescription();
            if (d != null) {
                String s = d.toString().trim();
                if (s.length() > 0) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        // 多层分级页面里的自定义控件（输入提示、悬浮说明、状态标签、
        // 分栏标题）常把屏幕上明明看得见的字放在这几个字段里。
        // 用反射取，低版本没有的方法自动跳过，不引入高 API 编译依赖。
        body[0] = false;
        String[] extra = {"getHintText", "getTooltipText",
                "getStateDescription", "getPaneTitle"};
        for (int i = 0; i < extra.length; i++) {
            try {
                CharSequence cs = (CharSequence) AccessibilityNodeInfo.class
                        .getMethod(extra[i]).invoke(node);
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
