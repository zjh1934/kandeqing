#!/usr/bin/env python3
"""
镜像 TextGrabber 的完整抓字流程：roots → walk → sortReadingOrder → join。

TextGrabber 自己依赖 Android API，没法在这里跑；但它的算法是确定的，
所以用一棵合成的假节点树把整个流水线复现一遍，验证：

  - 收录的是"最靠下那批"文字节点，父子不重复贴
  - 滚出屏幕的节点不读；isVisibleToUser 撒谎的 ROM 有兜底
  - 多列布局按【行】读，不会列间乱序
  - 命中段按句读切割，续读不重不漏

上限常量直接从 TextGrabber.java 里正则解析出来，
避免 Java 改了、这里的镜像还停留在旧值。
"""

import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_textlayout import (  # noqa: E402
    Node, RectF, clean, nearest_char, sentence_start,
    leading_whitespace, trimmed_length, sort_reading_order,
    fallback_start,
)

# 镜像 walk 是递归的，深树测试需要抬高上限
sys.setrecursionlimit(20000)

ROOT = os.path.dirname(os.path.abspath(__file__))
GRABBER = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "floating", "combo",
    "TextGrabber.java")

_src = io.open(GRABBER, encoding="utf-8").read()


def _const(name, default):
    m = re.search(
        r"private\s+static\s+final\s+int\s+%s\s*=\s*(\d+)\s*;" % name, _src)
    return int(m.group(1)) if m else default


MAX_WALK = _const("MAX_WALK", 10000)
MAX_SEG = _const("MAX_SEG", 400)
MAX_DEPTH = _const("MAX_DEPTH", 200)


# ---------- 假节点 ----------

class FakeNode(object):
    def __init__(self, text=None, desc=None, bounds=(0, 0, 0, 0),
                 visible=True, children=None, char_rects=None,
                 password=False):
        self._text = text
        self._desc = desc
        self.bounds = bounds
        self.visible = visible
        self.children = children if children is not None else []
        self.char_rects = char_rects
        self.password = password
        self.recycled = False
        # 3.9.7 新增：记录 getChild 调用次数，用于验证「可见性重算不重扫树」
        self.child_calls = 0
        # 预检 getAvailableExtraData() 是否含逐字位置 key
        self.extra_available = True
        # refreshWithExtraData 是否返回 false（节点 obsolete）
        self.extra_fails = False

    def getText(self):
        return self._text

    def getContentDescription(self):
        return self._desc

    def getBoundsInScreen(self, out):
        out.left, out.top, out.right, out.bottom = self.bounds
        return out

    def getChildCount(self):
        return len(self.children)

    def getChild(self, i):
        self.child_calls += 1
        return self.children[i]

    def isVisibleToUser(self):
        return self.visible

    def recycle(self):
        self.recycled = True


class Rect(object):
    def __init__(self):
        self.left = self.top = self.right = self.bottom = 0

    def width(self):
        return self.right - self.left

    def height(self):
        return self.bottom - self.top

    def isEmpty(self):
        return self.left >= self.right or self.top >= self.bottom

    def contains(self, x, y):
        return self.left <= x <= self.right and self.top <= y <= self.bottom


def intersects(a, b):
    return (a.left < b.right and b.left < a.right
            and a.top < b.bottom and b.top < a.bottom)


# ---------- 镜像 TextGrabber ----------

class Walk(object):
    def __init__(self, out, screen, px, py):
        self.out = out
        self.screen = screen
        self.px = px
        self.py = py
        self.requireVisible = True
        self.visited = 0
        self.truncated = False
        self.pick = None
        # 3.9.5：离触点最近的那段（不要求严格 contains），y 权重加倍
        self.nearest = None
        self.nearestDist = float('inf')
        self.nearestStart = 0
        self.nearTol = float('inf')
        self.visibleSkipped = 0
        # 3.9.7：被可见性软过滤的段收成纯数据，关过滤时合并重算，不重扫树
        self.hidden = []
        # 3.9.9：本窗口已发出的逐字位置请求数
        self.rectReq = 0


class Pick(object):
    def __init__(self, seg, area, char_start):
        self.seg = seg
        self.area = area
        self.charStart = char_start


def node_text(node):
    """
    3.9.7 镜像：返回 (text, body)。
    text / contentDescription → 正文或替代标签（body=True）；
    hint / tooltip / state / paneTitle → 官方语义各不相同（body=False）。
    """
    if node is None:
        return None, True
    try:
        t = node.getText()
        if t is not None:
            s = t.strip()
            if len(s) > 0:
                return s, True
    except Exception:
        pass
    try:
        d = node.getContentDescription()
        if d is not None:
            s = d.strip()
            if len(s) > 0:
                return s, True
    except Exception:
        pass
    for m in ('getHintText', 'getTooltipText',
              'getStateDescription', 'getPaneTitle'):
        try:
            fn = getattr(node, m, None)
            if fn is None:
                continue
            cs = fn()
            if cs is not None:
                s = cs.strip()
                if len(s) > 0:
                    return s, False
        except Exception:
            pass
    return None, True


def visible(node):
    try:
        return node.isVisibleToUser()
    except Exception:
        return True


def char_start_in(node, x, y, sdk=26):
    """镜像 TextGrabber.charStartIn（不含 Bundle/refresh，只算偏移）"""
    RECT_REQ_TOTAL[0] += 1
    if node is None:
        return 0
    try:
        cs = node.getText()
        if cs is None:
            return 0
        raw = cs if isinstance(cs, str) else str(cs)
        lead = leading_whitespace(raw)
        length = trimmed_length(raw)
        if length <= 0:
            return 0
        if sdk >= 26 and getattr(node, 'extra_available', True):
            rects = None if getattr(node, 'extra_fails', False) else node.char_rects
            if rects:
                offset = nearest_char(rects, x, y)
                if offset >= 0:
                    if offset > len(raw):
                        offset = len(raw)
                    rel = sentence_start(raw, offset) - lead
                    return max(0, min(length, rel))
        # 预检不通过 / 请求失败 / 全 null：按 y 估行，绝不退回整篇开头
        return fallback_start(node.bounds, x, y, raw, length, lead)
    except Exception:
        # 3.9.7：异常路径也降级，不再 return 0
        try:
            return fallback_start(node.bounds, x, y, str(node.getText()),
                                  trimmed_length(str(node.getText())),
                                  leading_whitespace(str(node.getText())))
        except Exception:
            return 0


def distance_to(seg_or_rect, x, y):
    """触点到矩形边界的距离，y 权重加倍（镜像 TextGrabber.distanceTo）"""
    l = seg_or_rect.left
    t = seg_or_rect.top
    r = seg_or_rect.right
    b = seg_or_rect.bottom
    dx = max(l - x, 0, x - r)
    dy = max(t - y, 0, y - b)
    return dy * 2 + dx


def consider(seg, w):
    """纯数据比较：最近段 + 最小面积命中。不碰任何节点对象。"""
    dist = distance_to(seg, w.px, w.py)
    if w.nearest is None or dist < w.nearestDist:
        w.nearest = seg
        w.nearestDist = dist
        w.nearestStart = seg.charStart
    if dist == 0:
        area = (seg.right - seg.left) * (seg.bottom - seg.top)
        if w.pick is None or area < w.pick.area:
            w.pick = Pick(seg, area, seg.charStart)


def fill_char_start(node, r, t, seg, w):
    """3.9.9 镜像：只给会成为 pick / nearest 的段发请求，且每窗口有硬上限。"""
    d = distance_to(r, w.px, w.py)
    area = (r.right - r.left) * (r.bottom - r.top)
    take_pick = (d == 0) and (w.pick is None or area < w.pick.area)
    take_near = (w.nearest is None or d < w.nearestDist)
    if d <= w.nearTol and (take_pick or take_near):
        if w.rectReq < MAX_RECT_REQ_PER_WINDOW:
            w.rectReq += 1
            seg.charStart = char_start_in(node, w.px, w.py)
        else:
            # 超上限：纯计算按行估算，绝不退回段首
            seg.charStart = fallback_start(node.bounds, w.px, w.py, t,
                                          trimmed_length(t), 0)


def walk(node, w, depth=0):
    """3.9.7 镜像：预算只截收录、可见性只作软标记、一趟遍历不重扫"""
    if node is None:
        return False
    if depth > MAX_DEPTH:
        w.truncated = True
        return True
    if w.visited >= MAX_WALK:
        w.truncated = True
        return True
    w.visited += 1

    child_yielded = False
    try:
        count = node.getChildCount()
    except Exception:
        count = 0
    for i in range(count):
        c = None
        try:
            c = node.getChild(i)
        except Exception:
            c = None
        if c is None:
            continue
        if walk(c, w, depth + 1):
            child_yielded = True
        try:
            c.recycle()
        except Exception:
            pass

    t, body = node_text(node)
    if t is None:
        return child_yielded
    if child_yielded:
        return True

    r = Rect()
    try:
        node.getBoundsInScreen(r)
    except Exception:
        return False
    if r.isEmpty():
        return False
    if not intersects(r, w.screen):
        return False

    touch_inside = r.contains(w.px, w.py)
    vis = visible(node)
    soft_hidden = w.requireVisible and not vis and not touch_inside
    if soft_hidden:
        w.visibleSkipped += 1

    # 预算只截「收录」，绝不截「遍历」：上面子节点已经全部走完
    if len(w.out) >= MAX_SEG and not touch_inside:
        w.truncated = True
        return True

    seg = Node(t, r.left, r.top, r.right, r.bottom, body, 0)
    fill_char_start(node, r, t, seg, w)
    if soft_hidden:
        w.hidden.append(seg)
        return True
    w.out.append(seg)
    consider(seg, w)
    return True


class Sink(object):
    def __init__(self):
        self.parts = []
        self.prev = None

    def add_head(self, raw):
        t = clean(raw)
        if t is None:
            return
        self.parts.append(t)
        self.prev = t

    def add(self, raw):
        t = clean(raw)
        if t is None:
            return
        if self.prev is not None and self.prev == t:
            return
        self.parts.append(t)
        self.prev = t

    def text(self):
        return "\n".join(self.parts).strip()


# 3.9.4「读多少」，镜像 Prefs 常量与 TextGrabber.readScope 字段
READ_ONE, READ_SEG, READ_SCREEN = 0, 1, 2
READ_SCOPE = READ_SCREEN        # 用例里临时改，跑完恢复


def first_sentence(s):
    """镜像 TextGrabber.firstSentence：截到第一个句末标点（含），没有就整段"""
    if not s:
        return s
    puncts = "。！？；\n.!?"
    for i, ch in enumerate(s):
        if ch in puncts:
            return s[:i + 1]
    return s


def join(segs, pick):
    idx = -1
    for i, s in enumerate(segs):
        if s is pick.seg:
            idx = i
            break
    if idx < 0:
        return join_below(segs, pick.seg.top)
    sink = Sink()
    head = pick.seg.text
    if 0 < pick.charStart < len(head):
        head = head[pick.charStart:]
    if READ_SCOPE == READ_ONE:
        head = first_sentence(head)
    sink.add_head(head)
    if READ_SCOPE != READ_SCREEN:
        return sink.text()
    for i in range(idx + 1, len(segs)):
        if not segs[i].body:
            continue
        sink.add(segs[i].text)
    return sink.text()


def join_below(segs, y):
    sink = Sink()
    for n in segs:
        if n.bottom <= y:
            continue
        if not n.body:
            continue
        sink.add(n.text)
        if READ_SCOPE != READ_SCREEN:
            break
    out = sink.text()
    if READ_SCOPE == READ_ONE:
        return first_sentence(out)
    return out


TOL = 144      # 48dp @3x，镜像 TextGrabber 的容差
MAX_RECT_REQ_PER_WINDOW = 3   # 3.9.9：每窗口逐字位置请求硬上限
RECT_REQ_TOTAL = [0]          # 本次抓字实际发出的请求数，用于断言上界


def scan_windows(roots, x, y, screen):
    """3.9.10 镜像：一趟扫完，全部决策复用同一份快照，不再二次遍历"""
    best_area = float('inf')
    best_pick = None
    best_segs = None
    best_near = float('inf')
    best_near_node = None
    best_near_start = 0
    best_near_segs = None
    first_segs = None

    for root in roots:
        segs = []
        w = Walk(segs, screen, x, y)
        w.nearTol = TOL
        walk(root, w, 0)
        if w.requireVisible and (not segs or w.visibleSkipped > 0):
            # 3.9.7：重算快照，不重扫树（getChild 调用次数不增加）
            for h in w.hidden:
                segs.append(h)
                consider(h, w)
            w.requireVisible = False
        if not segs:
            continue
        segs = sort_reading_order(segs)
        if first_segs is None:
            first_segs = segs
        if w.pick is not None and w.pick.area < best_area:
            best_area = w.pick.area
            best_pick = w.pick
            best_segs = segs
        if w.nearest is not None and w.nearestDist < best_near:
            best_near = w.nearestDist
            best_near_node = w.nearest
            best_near_start = w.nearestStart
            best_near_segs = segs

    if best_pick is not None:
        return join(best_segs, best_pick)
    if best_near_node is not None and best_near <= TOL:
        area = ((best_near_node.right - best_near_node.left)
                * (best_near_node.bottom - best_near_node.top))
        return join(best_near_segs,
                    Pick(best_near_node, area, best_near_start))
    fb = best_near_segs if best_near_segs is not None else first_segs
    if fb is not None:
        return join_below(fb, y)
    return None


def grab_from_windows(roots, x, y, screen, magnified=False):
    """镜像 TextGrabber.grabFromWindows（含 finally 里的根回收）"""
    # 3.9.7：官方契约 —— 放大态下 bounds 与逐字位置都会被缩放，坐标不自洽
    if magnified:
        return None
    if not roots:
        return None
    try:
        return scan_windows(roots, x, y, screen)
    finally:
        for r in roots:
            r.recycle()


def _tall_screen():
    r = Rect()
    r.left, r.top, r.right, r.bottom = 0, 0, 1080, 24000
    return r


def all_nodes(node, acc=None):
    if acc is None:
        acc = []
    if node is None:
        return acc
    acc.append(node)
    for c in node.children:
        all_nodes(c, acc)
    return acc


# ---------- 用例 ----------

SCREEN = Rect()
SCREEN.left, SCREEN.top, SCREEN.right, SCREEN.bottom = 0, 0, 1080, 1920


def n(text=None, desc=None, bounds=(0, 0, 0, 0), visible=True, children=None,
      char_rects=None, password=False):
    return FakeNode(text, desc, bounds, visible, children, char_rects,
                    password)


def case_two_columns_continue():
    """
    两列布局，点左一。
    旧排序按 (top,left)：左一 左二 左三 右一 右二 —— 列间乱序。
    新排序分带后：左一 右一 左二 右二 左三。
    """
    root = n(children=[
        n(children=[
            n("左一", bounds=(0, 0, 100, 40)),
            n("左二", bounds=(0, 40, 100, 80)),
            n("左三", bounds=(0, 80, 100, 120)),
        ]),
        n(children=[
            n("右一", bounds=(110, 0, 210, 40)),
            n("右二", bounds=(110, 40, 210, 80)),
        ]),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "左一\n右一\n左二\n右二\n左三", got


def case_parent_child_no_dup():
    """
    容器有 contentDescription，子节点是真正的正文。
    只应收子节点，不能把容器的描述再贴一遍。
    """
    root = n(children=[
        n(desc="卡片", bounds=(0, 0, 200, 200), children=[
            n("第一行", bounds=(0, 0, 200, 40)),
            n("第二行", bounds=(0, 40, 200, 80)),
        ]),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "第一行\n第二行", got


def case_offscreen_skipped():
    """滚出屏幕的节点整块在屏外，不该读出来。"""
    root = n(children=[
        n("屏内的", bounds=(0, 0, 100, 40)),
        n("滚出去了", bounds=(0, 2400, 100, 2440)),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "屏内的", got


def case_visibility_lie_fallback():
    """ROM 的 isVisibleToUser 全报 false —— 兜底那趟要把内容捞回来。"""
    root = n(bounds=(0, 0, 1080, 1920), children=[
        n("文本甲", bounds=(0, 0, 200, 40), visible=False),
        n("文本乙", bounds=(0, 40, 200, 80), visible=False),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "文本甲\n文本乙", got


def case_consecutive_dedup():
    """连续且完全相等的两段只保留一段。"""
    root = n(children=[
        n("重复", bounds=(0, 0, 200, 40)),
        n("重复", bounds=(0, 40, 200, 80)),
        n("收尾", bounds=(0, 80, 200, 120)),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "重复\n收尾", got


def case_substring_not_swallowed():
    """
    关键回归：后一段是命中段的子串时，绝不能当重复跳掉。
    （旧实现用过 indexOf 判包含，会把"世界"这种正文吞掉）
    """
    root = n(children=[
        n("你好世界", bounds=(0, 0, 200, 40)),
        n("世界", bounds=(0, 40, 200, 80)),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "你好世界\n世界", got


def case_sentence_cut():
    """点中"光"，从最近的句读（逗号）后开始：欢迎光临。"""
    raw = "你好世界，欢迎光临。"
    rects = []
    for i in range(len(raw)):
        rects.append(RectF(i * 20, 0, (i + 1) * 20, 40))
    root = n(children=[
        n(raw, bounds=(0, 0, len(raw) * 20, 40), char_rects=rects),
    ])
    x = 7 * 20 + 10          # "光"
    got = grab_from_windows([root], x, 20, SCREEN)
    return got == "欢迎光临。", got


def case_leading_ws_sentence_cut():
    """前导空白版本：偏移要减掉前导空白，不能切错。"""
    raw = "  你好世界，欢迎光临。"
    rects = []
    for i in range(len(raw)):
        rects.append(RectF(i * 20, 0, (i + 1) * 20, 40))
    root = n(children=[
        n(raw, bounds=(0, 0, len(raw) * 20, 40), char_rects=rects),
    ])
    x = 7 * 20 + 10          # "欢"（原文下标 7）
    got = grab_from_windows([root], x, 20, SCREEN)
    return got == "欢迎光临。", got


def case_no_hit_reads_below():
    """点在空白处（没命中任何段）：从点击高度往下接。"""
    root = n(children=[
        n("上面", bounds=(0, 0, 200, 40)),
        n("中间", bounds=(0, 100, 200, 140)),
        n("下面", bounds=(0, 200, 200, 240)),
    ])
    got = grab_from_windows([root], 50, 100, SCREEN)
    return got == "中间\n下面", got


def case_budget_does_not_explode():
    """超宽树（>10000 节点）要在上限处停住，不能卡死。"""
    leaf = n("叶", bounds=(0, 0, 10, 10))
    mids = [n(children=[FakeNode("叶", bounds=(0, 0, 10, 10))
                        for _ in range(60)]) for _ in range(200)]
    root = n(children=mids)
    got = grab_from_windows([root], 5, 5, SCREEN)
    return got is not None and len(got) > 0, (len(got) if got else None)


def _chain(depth, leaf_text):
    node = n(leaf_text, bounds=(0, 0, 10, 10))
    for _ in range(depth):
        node = n(children=[node])
    return node


def case_deep_tree_ok():
    """正常深度的深树要能正常收到内容，不能被深度上限误伤。"""
    got = grab_from_windows([_chain(300, "最底")], 5, 5, SCREEN)
    return got == "最底", got


def case_too_deep_guarded():
    """
    超深树（远超 MAX_DEPTH）在深度上限处干净停住，返回空且不崩、不二次遍历。
    真机上 4000 层递归几乎必然 StackOverflowError，被吞成「点了没反应」；
    真实界面深度常见 <100，不受影响。
    """
    tree = _chain(4000, "最底")
    try:
        got = grab_from_windows([tree], 5, 5, SCREEN)
    except RecursionError:
        return False, "主路径递归爆了 —— 深度上限没生效"
    # 到不了底，但也不能崩；且只走一趟，getChild 次数不因兜底而翻倍
    ok = (got is None) and tree.child_calls <= MAX_WALK
    return ok, (got, tree.child_calls)


def case_no_second_walk_on_miss():
    """
    3.9.10：整屏一个字都没有时，只走一趟树就返回空。

    旧版主路径返回 None 后会再 roots()+findDeepest 全树重扫一遍，
    「抓不到字」反而最慢。现在没有第二套遍历，getChild 次数恒定。
    """
    leaf = n(bounds=(0, 0, 100, 40))
    mid = n(children=[leaf, n(bounds=(0, 40, 100, 80))])
    root = n(children=[mid, n(bounds=(100, 0, 200, 80))])
    got = grab_from_windows([root], 50, 20, SCREEN)
    # root 1 次 + mid 1 次 = 2；二次遍历会变成 4
    ok = (got is None) and root.child_calls == 2
    return ok, (got, root.child_calls)


def case_all_nodes_recycled():
    """
    AccessibilityNodeInfo 是有上限的系统资源（一般同时只能有几十个），
    漏 recycle 会让后续 getChild() 直接抛异常 —— 表现为"读一会儿就抓不到字了"。
    这里把整棵树收一遍，确认每个节点都被回收了。
    """
    root = n(children=[
        n(children=[
            n("甲", bounds=(0, 0, 100, 40)),
            n("乙", bounds=(0, 40, 100, 80)),
        ]),
        n(children=[
            n("丙", bounds=(0, 80, 100, 120)),
        ]),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    leaked = [x for x in all_nodes(root) if not x.recycled]
    ok = (got is not None) and not leaked
    return ok, (got, len(leaked))


# ---------- 3.9.9：快而深 ----------

def case_rect_requests_hard_cap():
    """密集页面逐字位置请求有硬上界，不随容差内段数线性增长。"""
    RECT_REQ_TOTAL[0] = 0
    kids = [n("第%d段文字" % i, bounds=(i * 10, 0, i * 10 + 10, 40))
            for i in range(30)]
    root = n(children=kids)
    got = grab_from_windows([root], 100, 20, SCREEN)
    used = RECT_REQ_TOTAL[0]
    ok = got is not None and len(got) > 0 and used <= MAX_RECT_REQ_PER_WINDOW
    return ok, "requests=%d got=%s" % (used, got)


def case_over_budget_still_not_paragraph_head():
    """请求打满上限后改用按行估算，长文仍从中间句读，不退到段首。"""
    raw = "第一句内容写在这里。" * 12      # 带句读的长文，120 字
    node = n(raw, bounds=(0, 0, 1000, 800))
    w = Walk([], SCREEN, 500, 400)
    w.nearTol = TOL
    w.rectReq = MAX_RECT_REQ_PER_WINDOW   # 额度已用尽
    r = Rect()
    r.left, r.top, r.right, r.bottom = 0, 0, 1000, 800
    seg = Node(raw, 0, 0, 1000, 800, True, 0)
    fill_char_start(node, r, raw, seg, w)
    ok = 0 < seg.charStart < len(raw)
    return ok, "charStart=%d" % seg.charStart


# ---------- 3.9.4「读多少」 ----------

SCOPE_RAW = "第一句。第二句。第三句。"
SCOPE_EXPECT = {
    READ_SCREEN: "第二句。第三句。\n另一段的第一句。另一段第二句。\n最后一段。",
    READ_SEG: "第二句。第三句。",
    READ_ONE: "第二句。",
}


def _scope_grab(scope, x, y):
    """临时切到某一档跑一次真实流程，跑完恢复默认，避免污染其它用例"""
    global READ_SCOPE
    old = READ_SCOPE
    READ_SCOPE = scope
    try:
        rects = [RectF(i * 20, 0, (i + 1) * 20, 40)
                 for i in range(len(SCOPE_RAW))]
        root = n(children=[
            n(SCOPE_RAW, bounds=(0, 0, len(SCOPE_RAW) * 20, 40),
              char_rects=rects),
            n("另一段的第一句。另一段第二句。", bounds=(0, 40, 600, 80)),
            n("最后一段。", bounds=(0, 80, 300, 120)),
        ])
        return grab_from_windows([root], x, y, SCREEN)
    finally:
        READ_SCOPE = old


def case_scope_screen_unchanged():
    """默认「读到屏底」= 早期版本行为，老用户升级不会突然变短"""
    got = _scope_grab(READ_SCREEN, 5 * 20 + 10, 20)
    return got == SCOPE_EXPECT[READ_SCREEN], got


def case_scope_seg_only_head():
    """读到段末：命中段从点的那句起读完，不接后续段"""
    got = _scope_grab(READ_SEG, 5 * 20 + 10, 20)
    return got == SCOPE_EXPECT[READ_SEG], got


def case_scope_one_only_first_sentence():
    """只读一句：连命中段也截到第一个句号"""
    got = _scope_grab(READ_ONE, 5 * 20 + 10, 20)
    return got == SCOPE_EXPECT[READ_ONE], got


def case_scope_three_lengths_distinct():
    """三档输出必须逐档变短，不能有两档其实一样"""
    a = _scope_grab(READ_SCREEN, 5 * 20 + 10, 20)
    b = _scope_grab(READ_SEG, 5 * 20 + 10, 20)
    c = _scope_grab(READ_ONE, 5 * 20 + 10, 20)
    return len(a) > len(b) > len(c), (len(a), len(b), len(c))


def case_scope_reverse_old_behavior_detected():
    """反向验证：关掉裁切（旧行为）时，READ_ONE 与旧输出必须不同"""
    global READ_SCOPE
    old = READ_SCOPE
    try:
        # 旧实现：无论选项是什么都一路接到底
        READ_SCOPE = READ_ONE
        rects = [RectF(i * 20, 0, (i + 1) * 20, 40)
                 for i in range(len(SCOPE_RAW))]
        root = n(children=[
            n(SCOPE_RAW, bounds=(0, 0, len(SCOPE_RAW) * 20, 40),
              char_rects=rects),
            n("另一段的第一句。另一段第二句。", bounds=(0, 40, 600, 80)),
            n("最后一段。", bounds=(0, 80, 300, 120)),
        ])
        got = grab_from_windows([root], 5 * 20 + 10, 20, SCREEN)
        # 模拟旧行为：手动把 head 与后续段拼回去
        old_out = SCOPE_EXPECT[READ_SCREEN]
        return got != old_out and got == SCOPE_EXPECT[READ_ONE], (got, old_out)
    finally:
        READ_SCOPE = old


def case_scope_no_hit_join_below():
    """点在空白处（没命中段）时，三档也要生效"""
    global READ_SCOPE
    old = READ_SCOPE
    try:
        READ_SCOPE = READ_ONE
        root = n(children=[
            n("第一段第一句。第一段第二句。", bounds=(0, 40, 400, 80)),
            n("第二段。", bounds=(0, 80, 200, 120)),
        ])
        got = grab_from_windows([root], 50, 10, SCREEN)
        return got == "第一段第一句。", got
    finally:
        READ_SCOPE = old


def _nd(text, bounds, children=None, visible=True):
    """注意：模块里的 n() 第二参数是 desc，不是 bounds。
    多层用例必须用显式 bounds，否则节点 bounds 全 (0,0,0,0) 被 walk 丢掉。"""
    return FakeNode(text=text, bounds=bounds,
                    children=children, visible=visible)


# ---------- 3.9.5 多层分级页面：触点坐标定位 ----------

def _screen():
    r = Rect()
    r.left, r.top, r.right, r.bottom = 0, 0, 1080, 2000
    return r


def _layered_gap():
    """上层标题栏 + 下层四行正文，触点落在第 3/4 行之间的空隙"""
    top = _nd('', (0, 0, 1080, 200), children=[
        _nd('订单详情', (0, 0, 1080, 120)),
        _nd('点任意处继续', (0, 120, 1080, 180)),
    ])
    bottom = _nd('', (0, 0, 1080, 2000), children=[
        _nd('第一句：您购买的保温杯已发货。', (0, 400, 1080, 460)),
        _nd('第二句：预计明天下午送达。', (0, 480, 1080, 540)),
        _nd('第三句：收货地址是大理古城。', (0, 560, 1080, 620)),
        _nd('第四句：如有问题联系客服。', (0, 640, 1080, 700)),
    ])
    return [top, bottom]


def _layered_wrap():
    """上层一张包裹型大卡片覆盖触点，正文在下层且面积更小"""
    top = _nd('', (0, 0, 1080, 2000), children=[
        _nd('订单卡片（整张卡片文本）', (0, 400, 1080, 800)),
    ])
    bottom = _nd('', (0, 0, 1080, 2000), children=[
        _nd('第三句：收货地址是大理古城。', (0, 560, 1080, 620)),
        _nd('第四句：如有问题联系客服。', (0, 640, 1080, 700)),
    ])
    return [top, bottom]


def case_layered_gap_hit():
    """点在行间留白：必须命中最近那行，不能返回空（旧版「经常抓不到字」）"""
    got = grab_from_windows(_layered_gap(), 540, 630, _screen())
    return got is not None and got.startswith('第三句'), got


def case_layered_exact_smallest_across_windows():
    """跨窗口比面积：上层大卡片不能压住下层真正那行（旧版「点这里读那里」）"""
    got = grab_from_windows(_layered_wrap(), 540, 590, _screen())
    ok = got is not None and got.startswith('第三句') and '订单卡片' not in got
    return ok, got


def case_layered_invisible_rescanned():
    """ROM 误报 invisible：全窗口没命中时关过滤重扫，正文要能读出来"""
    top = _nd('顶部提示', (0, 0, 1080, 100))
    bottom = _nd('', (0, 0, 1080, 2000), children=[
        _nd('可见的标题', (0, 300, 1080, 360)),
        _nd('真正想读的这段被误报不可见', (0, 560, 1080, 620), visible=False),
    ])
    got = grab_from_windows([top, bottom], 540, 590, _screen())
    return got is not None and '误报不可见' in got, got


def case_layered_hint_text():
    """自定义控件只有 hint 文字：不能判定「这里没读到文字」"""
    hint = _nd(None, (0, 560, 1080, 620))
    hint.getHintText = lambda: '请输入手机号'
    got = grab_from_windows([hint], 540, 590, _screen())
    return got == '请输入手机号', got


def _old_grab_from_windows(roots, x, y, screen):
    """3.9.4 及之前的行为：严格 contains + 第一个命中的窗口立刻 return"""
    fallback = None
    for root in roots:
        segs = []
        w = Walk(segs, screen, x, y)
        walk(root, w, 0)
        if not segs and w.requireVisible:
            w.requireVisible = False
            w.visited = 0
            walk(root, w, 0)
        if not segs:
            continue
        segs = sort_reading_order(segs)
        if fallback is None:
            fallback = segs
        if w.pick is not None:
            return join(segs, w.pick)
    if fallback is None:
        return None
    return join_below(fallback, y)


def case_layered_reverse_old_behavior():
    """反向验证：两个场景旧行为都必须被抓出来（结果不同且明显不对）"""
    s = _screen()
    a_old = _old_grab_from_windows(_layered_gap(), 540, 630, s)
    a_new = grab_from_windows(_layered_gap(), 540, 630, s)
    b_old = _old_grab_from_windows(_layered_wrap(), 540, 590, s)
    b_new = grab_from_windows(_layered_wrap(), 540, 590, s)
    ok = (not a_old and a_new and a_new.startswith('第三句')
          and b_old and '订单卡片' in b_old
          and b_new and '订单卡片' not in b_new)
    return ok, (a_old, a_new, b_old, b_new)


# ---------- 3.9.7 官方契约适配：验收用例 ----------

def case_budget_hit_late_segment():
    """500 段长列表点第 450 段：预算不再截断遍历，必须命中该段。"""
    segs = []
    for i in range(500):
        y = i * 40
        segs.append(n("第%d段的文字内容。" % i, bounds=(0, y, 1000, y + 40)))
    root = n(children=segs)
    got = grab_from_windows([root], 500, 450 * 40 + 20, _tall_screen())
    ok = got is not None and got.startswith('第450段')
    return ok, (got[:50] if got else got)


def case_hint_not_in_body():
    """hint / state 不进连续正文串；但直接点到它本身仍照读。"""
    body = _nd('正文第一段。', (0, 0, 1000, 40))
    hint = _nd(None, (0, 40, 1000, 80))
    hint.getHintText = lambda: '请输入手机号'
    root = n(children=[body, hint])
    got = grab_from_windows([root], 500, 20, _screen())
    got_hint = grab_from_windows([root], 500, 60, _screen())
    ok = (got == '正文第一段。' and '请输入手机号' not in got
          and got_hint == '请输入手机号')
    return ok, (got, got_hint)


def case_extra_data_paths_not_zero():
    """预检不可用 / 节点失效：起点都不退 0，绝不从篇头读。"""
    raw = ''.join('第%d行内容写在这里。' % i for i in range(40))
    rects = [RectF(i * 20, 0, (i + 1) * 20, 40) for i in range(len(raw))]
    for avail, fails in ((False, False), (True, True)):
        node = n(raw, bounds=(0, 0, 1000, 1600), char_rects=rects)
        node.extra_available = avail
        node.extra_fails = fails
        got = grab_from_windows([n(children=[node])], 100, 800, _screen())
        if not got or got.startswith(raw[:6]):
            return False, (avail, fails, got[:30] if got else got)
    return True, None


def case_visibility_no_rescan():
    """可见性误报时重算快照，getChild 调用次数恒定（旧版二次 walk 会翻倍）。"""
    root = n(bounds=(0, 0, 1080, 1920), children=[
        n("甲", bounds=(0, 0, 200, 40), visible=False),
        n("乙", bounds=(0, 40, 200, 80), visible=False),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    ok = got == "甲\n乙" and root.child_calls == 2
    return ok, (got, root.child_calls)


def case_fallback_all_windows():
    """主路径跨窗口全局比面积：上层大节点不能压住下层真正那行。"""
    top = _nd('整张卡片', (0, 0, 1080, 800))
    bottom = _nd('真正那行', (0, 560, 1080, 620))
    got = grab_from_windows([top, bottom], 540, 590, SCREEN)
    return got == '真正那行', got


def case_magnified_rejected():
    """放大态断言：bounds 与逐字位置会被缩放，直接拒绝抓字。"""
    root = n(children=[n("正文", bounds=(0, 0, 200, 40))])
    got = grab_from_windows([root], 50, 20, SCREEN, magnified=True)
    return got is None, got


def case_long_text_mid_not_head():
    """长文单节点、逐字位置拿不到：点中段不能从篇头读。"""
    raw = ''.join('第%d行内容写在这里，' % i for i in range(40))
    node = n(raw, bounds=(0, 0, 1000, 1600))
    got = grab_from_windows([n(children=[node])], 100, 800, _screen())
    ok = got is not None and not got.startswith(raw[:8])
    return ok, (got[:30] if got else got)


CASES = [
    ("3.9.7 长列表预算不截遍历", case_budget_hit_late_segment),
    ("3.9.7 提示文字不进正文串", case_hint_not_in_body),
    ("3.9.7 逐字位置失效都不退篇头", case_extra_data_paths_not_zero),
    ("3.9.7 可见性重算不重扫树", case_visibility_no_rescan),
    ("3.9.7 主路径跨窗口全局比较", case_fallback_all_windows),
    ("3.9.7 放大态拒绝抓字", case_magnified_rejected),
    ("3.9.7 长文中段不从篇头读", case_long_text_mid_not_head),
    ("3.9.5 多层留白命中最近行", case_layered_gap_hit),
    ("3.9.5 跨窗口取最小命中段", case_layered_exact_smallest_across_windows),
    ("3.9.5 误报不可见时重扫", case_layered_invisible_rescanned),
    ("3.9.5 hint 文字也能抓到", case_layered_hint_text),
    ("3.9.5 反向验证：旧行为必被抓出", case_layered_reverse_old_behavior),
    ("3.9.4 默认读到屏底（升级不变）", case_scope_screen_unchanged),
    ("3.9.4 读到段末不接后续段", case_scope_seg_only_head),
    ("3.9.4 只读一句截到句末", case_scope_one_only_first_sentence),
    ("3.9.4 三档逐档变短", case_scope_three_lengths_distinct),
    ("3.9.4 反向验证：旧行为必被抓出", case_scope_reverse_old_behavior_detected),
    ("3.9.4 未命中时读多少也生效", case_scope_no_hit_join_below),
    ("两列布局续读不乱序", case_two_columns_continue),
    ("父子节点不重复收录", case_parent_child_no_dup),
    ("滚出屏的节点不读", case_offscreen_skipped),
    ("可见性撒谎时的兜底", case_visibility_lie_fallback),
    ("连续重复段去重", case_consecutive_dedup),
    ("子串不能被当重复吞掉（关键回归）", case_substring_not_swallowed),
    ("命中段按句读切割", case_sentence_cut),
    ("前导空白下的句读切割（关键回归）", case_leading_ws_sentence_cut),
    ("未命中时从点击高度续读", case_no_hit_reads_below),
    ("超宽树在上限处停住", case_budget_does_not_explode),
    ("正常深树不被误伤", case_deep_tree_ok),
    ("超深树不爆栈、干净返回空", case_too_deep_guarded),
    ("没抓到时也不二次遍历整棵树", case_no_second_walk_on_miss),
    ("节点全部回收无泄漏", case_all_nodes_recycled),
    ("密集页面逐字请求有硬上界", case_rect_requests_hard_cap),
    ("超上限后起点仍不退段首", case_over_budget_still_not_paragraph_head),
]


def run_once():
    failed = 0
    for name, fn in CASES:
        ok, detail = fn()
        failed += 0 if ok else 1
        print(("PASS" if ok else "FAIL"), "-", name,
              "" if ok else ("→ %r" % (detail,)))
    return failed


def main():
    total = 0
    for i in range(1, 3):
        print("===== 第 %d 遍 =====" % i)
        total += run_once()
    if total:
        raise SystemExit("TextGrabber 累计 %d 个用例失败" % total)
    print("TextGrabber 两遍全部通过 ✅")


if __name__ == "__main__":
    main()
