#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
3.9：TextPicker 的纯逻辑镜像（多窗口收集 / 容差命中 / 二维断句 / 缓存）。

项目里没有 JDK，跑不了 JUnit；verify_all.py 用 Python 镜像 Java 逻辑。
改了 TextPicker.java 就要同步改这里，且必须做**反向验证**：
把 START_USE_Y 关掉（退回 3.8 的 x-only 估算），断句用例必须 FAIL，
证明测试真的在检验「用 y 定位」这件事，而不是碰巧全绿。

跑法：python3 verify_picker.py   全 PASS 返回 0
"""
import sys

MAX_WALK = 30000
MAX_SEG = 1200
MAX_DEPTH = 500
QUICK_MAX_WALK = 300
QUICK_MAX_DEPTH = 6
MAX_CHAR_NODES = 4
CACHE_TTL_MS = 10000
TOLERANCE_DP = 96.0
DENSITY = 3.0
LONG_TEXT = 40
LOOK = 240

# 反向验证开关：False = 模拟 3.8 的「只用 x 比例」缺陷
START_USE_Y = True


# ---------------------------------------------------------------- 数据结构

class Node(object):
    def __init__(self, text, left, top, right, bottom, visible=True,
                 hint=None, tooltip=None, state=None, children=None, desc=None,
                 rects=None, lead=0):
        self.text = text
        self.left, self.top, self.right, self.bottom = left, top, right, bottom
        self.visible = visible
        self.hint, self.tooltip, self.state = hint, tooltip, state
        self.desc = desc
        self.children = children or []
        self.rects = rects      # 模拟逐字 RectF 列表：(l,t,r,b)
        self.lead = lead


class Seg(object):
    def __init__(self, text, left, top, right, bottom, rects=None, lead=0):
        self.text = text
        self.left, self.top, self.right, self.bottom = left, top, right, bottom
        self.rects = rects
        self.lead = lead

    def __repr__(self):
        return 'Seg(%r,%d,%d,%d,%d)' % (self.text[:12], self.left, self.top,
                                        self.right, self.bottom)


class WinSegs(object):
    def __init__(self, layer, segs):
        self.layer = layer
        self.segs = segs


# ---------------------------------------------------------------- 纯字符串

def clean(s):
    if s is None:
        return None
    t = s.strip()
    return t if t else None


def tag_strip(s):
    if '<' in s and '>' in s:
        import re
        s = re.sub(r'<[^>]*>', ' ', s)
        s = re.sub(r'\s+', ' ', s)
    return s


def scan_near(text, off, floor, puncts):
    for i in range(off - 1, floor - 1, -1):
        if text[i] not in puncts:
            continue
        s = i + 1
        while s < len(text) and text[s].isspace():
            s += 1
        if s >= len(text):
            return max(0, off - 1)
        return s
    return 0


def sentence_near(text, off):
    """3.9：只在命中字附近找句读，找不到就从命中字读起，绝不退 0"""
    if off <= 0:
        return 0
    if off >= len(text):
        off = len(text)
    floor = max(0, off - LOOK)
    s = scan_near(text, off, floor, '。！？；\n.!?')
    if s > 0:
        return s
    s = scan_near(text, off, floor, '，、：,:')
    if s > 0:
        return s
    return off


def sort_reading_order(segs):
    if len(segs) < 2:
        return
    segs.sort(key=lambda n: (n.top, n.left))
    bands = []

    def same_row(a, b):
        overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        if overlap <= 0:
            return False
        min_h = min(a.bottom - a.top, b.bottom - b.top)
        return min_h > 0 and overlap * 2 >= min_h

    for n in segs:
        if bands and same_row(bands[-1][0], n):
            bands[-1].append(n)
        else:
            bands.append([n])
    segs[:] = [n for b in bands for n in sorted(b, key=lambda x: x.left)]


# ---------------------------------------------------------------- 收集

def node_text(n):
    for v in (n.text, getattr(n, 'desc', None), n.hint, n.tooltip, n.state):
        c = clean(tag_strip(v)) if isinstance(v, str) else clean(v)
        if c is not None:
            return c
    return None


def collect_window(root, screen=(0, 0, 1080, 2400),
                   max_walk=MAX_WALK, max_depth=MAX_DEPTH, want_rects=True):
    out = []
    state = {'visited': 0, 'char_nodes': 0}

    def intersects(r):
        return not (r[2] < screen[0] or r[0] > screen[2]
                    or r[3] < screen[1] or r[1] > screen[3])

    def walk(node, depth):
        if node is None or depth > max_depth or state['visited'] >= max_walk \
                or len(out) >= MAX_SEG:
            return
        state['visited'] += 1
        t = node_text(node)
        child_texts = set() if t is not None else None
        for c in node.children:
            if child_texts is not None:
                ct = node_text(c)
                if ct is not None:
                    child_texts.add(ct)
            walk(c, depth + 1)
        if t is None:
            return
        # 父文本包含任一直接子文本 → 丢父，保留更细的分行段
        if child_texts:
            for ct in child_texts:
                if ct and ct in t:
                    return
        r = (node.left, node.top, node.right, node.bottom)
        if r[2] <= r[0] or r[3] <= r[1] or not intersects(r):
            return      # 不做 isVisibleToUser 硬过滤
        rects = None
        lead = 0
        if want_rects and t and len(t) >= LONG_TEXT and node.rects is not None \
                and state['char_nodes'] < MAX_CHAR_NODES:
            rects = node.rects
            lead = node.lead
            state['char_nodes'] += 1
        out.append(Seg(t, r[0], r[1], r[2], r[3], rects, lead))

    walk(root, 0)
    sort_reading_order(out)
    return out


def collect_windows(roots, want_rects=True):
    """3.9：所有窗口都收，按 layer 从高到低；3.8 只取第一个就 return"""
    wins = []
    for layer, root in roots:
        segs = collect_window(root, want_rects=want_rects)
        if segs:
            wins.append(WinSegs(layer, segs))
    return wins


class Snapshot(object):
    def __init__(self, wins, seq, now=1000, enhanced=True):
        self.wins = wins
        self.seq = seq
        self.time = now
        self.enhanced = enhanced

    def usable(self, seq, now=1000):
        return bool(self.wins) and self.seq == seq \
            and now - self.time <= CACHE_TTL_MS

    def diagnose(self, seq):
        return 'wins=%d segs=%d char=%d seqOk=%s' % (
            len(self.wins), sum(len(w.segs) for w in self.wins),
            sum(1 for w in self.wins for s in w.segs if s.rects),
            self.seq == seq)


# ---------------------------------------------------------------- 命中/断句

def hit(segs, x, y, tolerance_dp=TOLERANCE_DP):
    exact, exact_area = -1, 1 << 62
    for i, n in enumerate(segs):
        if n.left <= x <= n.right and n.top <= y <= n.bottom:
            area = (n.right - n.left) * (n.bottom - n.top)
            if area < exact_area:
                exact_area, exact = area, i
    if exact >= 0:
        return exact
    tol = tolerance_dp * DENSITY
    limit = tol * tol
    best, best_d = -1, float('inf')
    for i, n in enumerate(segs):
        dx = max(n.left - x, 0, x - n.right)
        dy = max(n.top - y, 0, y - n.bottom)
        d = dx * dx + dy * dy
        if d < best_d:
            best_d, best = d, i
    return best if best_d <= limit else -1


def nearest_char(rects, x, y):
    best, best_d = -1, float('inf')
    for i, r in enumerate(rects):
        if r is None:
            continue
        l, t, rr, b = r
        if rr <= l or b <= t:
            continue
        dx = max(l - x, 0, x - rr)
        dy = max(t - y, 0, y - b)
        d = dx * dx + dy * dy
        if d < best_d:
            best_d, best = d, i
    return best


def start_in(seg, x, y):
    """镜像 TextPicker.startIn：二维逐字 → y 比例降级 → sentenceNear"""
    text = seg.text
    if not text:
        return 0
    off = -1
    if seg.rects is not None:
        k = nearest_char(seg.rects, x, y)
        if k >= 0:
            off = max(0, min(len(text), k - seg.lead))
    if off < 0:
        h = seg.bottom - seg.top
        w = seg.right - seg.left
        ry = (y - seg.top) / float(h) if h > 0 else 0.0
        rx = (x - seg.left) / float(w) if w > 0 else 0.0
        if START_USE_Y:
            ratio = max(0.0, min(1.0, ry * 0.85 + rx * 0.15))
        else:
            ratio = max(0.0, min(1.0, rx))   # 3.8 缺陷：只看 x
        if len(text) < LONG_TEXT:
            return 0
        off = int(ratio * len(text))
    return sentence_near(text, off)


def append(sb, s):
    if not s:
        return
    joined = ''.join(sb)
    if len(joined) >= len(s) and joined.endswith(s):
        return
    if sb:
        sb.append(' ')
    sb.append(s)


def read_from(snap, x, y, tolerance_dp=TOLERANCE_DP):
    best_win, best_idx, best_score = None, -1, 1 << 62
    for wi, win in enumerate(snap.wins):
        idx = hit(win.segs, x, y, tolerance_dp)
        if idx < 0:
            continue
        n = win.segs[idx]
        exact = n.left <= x <= n.right and n.top <= y <= n.bottom
        score = (0 if exact else 1000) + wi
        if score < best_score:
            best_score, best_win, best_idx = score, win, idx
    out = []
    if best_win is None:
        for win in snap.wins:
            for n in win.segs:
                if n.bottom > y:
                    append(out, n.text)
        v = ''.join(out).strip()
        return v or None
    head = best_win.segs[best_idx]
    st = start_in(head, x, y)
    t = head.text[st:] if 0 < st < len(head.text) else head.text
    append(out, t)
    for n in best_win.segs[best_idx + 1:]:
        append(out, n.text)
    after = False
    for win in snap.wins:
        if win is best_win:
            after = True
            continue
        if after:
            for n in win.segs:
                append(out, n.text)
    v = ''.join(out).strip()
    return v or None


# ---------------------------------------------------------------- 用例

def n(text, box, **kw):
    return Node(text, box[0], box[1], box[2], box[3], **kw)


def root_of(*children):
    return Node(None, 0, 0, 1080, 2400, children=list(children))


def snap_one(root, seq=7, now=1000, want_rects=True):
    return Snapshot(collect_windows([(0, root)], want_rects), seq, now)


CASES = []


def case(fn):
    CASES.append(fn)
    return fn


@case
def case_exact_hit():
    s = snap_one(root_of(
        n('第一段话', (10, 10, 300, 50)),
        n('第二段话', (10, 100, 300, 140)),
        n('第三段话', (10, 200, 300, 240))))
    got = read_from(s, 100, 120) or ''
    return '第二' in got and '第三' in got and '第一' not in got, got


@case
def case_tolerance_gap_hit():
    s = snap_one(root_of(
        n('上一行文字', (10, 10, 300, 50)),
        n('下一行文字', (10, 100, 300, 140))))
    got = read_from(s, 100, 75) or ''
    return '上一行' in got, got


@case
def case_tolerance_limit():
    s = snap_one(root_of(n('远处一段', (10, 10, 300, 50))))
    return hit(s.wins[0].segs, 900, 2000, tolerance_dp=0) == -1, 'ok'


@case
def case_visibility_not_filtered():
    s = snap_one(root_of(n('被误判不可见的字', (10, 10, 300, 50),
                           visible=False)))
    return len(s.wins[0].segs) == 1, s.diagnose(7)


@case
def case_parent_text_kept():
    child = n('子文本', (10, 60, 200, 100))
    parent = Node('父说明', 0, 0, 300, 140, children=[child])
    s = snap_one(root_of(parent))
    texts = [x.text for w in s.wins for x in w.segs]
    return '父说明' in texts and '子文本' in texts, texts


@case
def case_parent_containing_child_dropped():
    child = n('分行文本', (10, 60, 200, 100))
    parent = Node('这是一整篇文本，其中分行文本只是其中一句。',
                  0, 0, 300, 140, children=[child])
    s = snap_one(root_of(parent))
    texts = [x.text for w in s.wins for x in w.segs]
    return '分行文本' in texts and '这是一整篇文本' not in texts, texts


@case
def case_hint_tooltip_state():
    s = snap_one(root_of(
        n(None, (10, 10, 200, 50), hint='请输入手机号'),
        n(None, (10, 60, 200, 100), tooltip='长按可编辑'),
        n(None, (10, 110, 200, 150), state='已选中')))
    texts = [x.text for w in s.wins for x in w.segs]
    return all(k in texts for k in ('请输入手机号', '长按可编辑', '已选中')), texts


@case
def case_budget_wide():
    kids = [n('第%d段' % i, (10, i * 2, 300, i * 2 + 1))
            for i in range(1100)]
    s = snap_one(root_of(*kids))
    texts = [x.text for w in s.wins for x in w.segs]
    return '第1099段' in texts, len(texts)


@case
def case_html_stripped():
    s = snap_one(root_of(n('<p>正文内容</p>', (10, 10, 300, 50))))
    texts = [x.text for w in s.wins for x in w.segs]
    return texts == ['正文内容'], texts


@case
def case_multi_window_keeps_body():
    """3.8 只取第一个窗口就 return，正文（第二窗口）全丢；3.9 必须读到"""
    toolbar = root_of(n('工具栏', (10, 10, 200, 50)))
    body = root_of(n('这是文章正文内容', (10, 100, 600, 200)))
    s = Snapshot(collect_windows([(9, toolbar), (3, body)]), 7)
    got = read_from(s, 300, 150) or ''
    return '文章正文' in got and '工具栏' not in got, got


@case
def case_y_ratio_not_from_head():
    """断句核心：点长文第 20 行行首，起点必须落在中段，不能回到篇头"""
    lines = 40
    text = ''.join('第%d行的内容写在这里' % i
                   + ('。' if i % 3 == 2 else '，') for i in range(lines))
    seg = Seg(text, 0, 0, 1000, 1600)
    s = Snapshot([WinSegs(0, [seg])], 7)
    x = int(3 / 30 * 1000)          # 行首（第 3 个字）
    y = int(20 / 40 * 1600)         # 第 20 行
    st = start_in(seg, x, y)
    ratio = st / float(len(text))
    ok = 0.25 < ratio < 0.65
    return ok, 'start=%d len=%d ratio=%.3f' % (st, len(text), ratio)


@case
def case_sentence_near_no_zero():
    text = '完全没有任何标点的一长串中文内容' * 12
    seg = Seg(text, 0, 0, 1000, 1600)
    x, y = 100, 800
    st = start_in(seg, x, y)
    return st > 0.2 * len(text), 'start=%d len=%d' % (st, len(text))


@case
def case_char_rects_two_dimensional():
    """有逐字矩形时，x 和 y 都要参与，点第 5 行不能落到第 0 行"""
    per_line = 10
    rects = []
    for i in range(60):
        row, col = divmod(i, per_line)
        rects.append((col * 100, row * 40, col * 100 + 100, row * 40 + 40))
    text = '字' * 60
    seg = Seg(text, 0, 0, 1000, 240, rects=rects, lead=0)
    st = start_in(seg, 250, 5 * 40 + 20)   # 第 5 行第 2 字
    return 45 <= st <= 55, 'start=%d (应≈50)' % st


@case
def case_short_segment_not_trimmed():
    s = snap_one(root_of(n('第二段话', (10, 100, 300, 140))))
    got = read_from(s, 100, 120)
    return got == '第二段话', got


@case
def case_dedup_consecutive():
    out = []
    append(out, '重复')
    append(out, '重复')
    append(out, '不重复')
    return ''.join(out) == '重复 不重复', ''.join(out)


@case
def case_cache_ttl():
    root = root_of(n('缓存内容', (10, 10, 300, 50)))
    s = Snapshot(collect_windows([(0, root)]), seq=7, now=1000)
    ok1 = s.usable(7, 1000)
    bad_seq = not s.usable(8, 1000)
    bad_ttl = not s.usable(7, 1000 + CACHE_TTL_MS + 1)
    return ok1 and bad_seq and bad_ttl, (ok1, bad_seq, bad_ttl)


@case
def case_reverse_no_tolerance_fails():
    s = snap_one(root_of(
        n('上一行文字', (10, 10, 300, 50)),
        n('下一行文字', (10, 100, 300, 140))))
    idx = hit(s.wins[0].segs, 100, 75, tolerance_dp=0)
    got = read_from(s, 100, 75, tolerance_dp=0) or ''
    return idx == -1 and '上一行' not in got, 'idx=%d got=%r' % (idx, got)


@case
def case_reverse_x_only_fails_y_case():
    """反向验证：退回 3.8 的 x-only 估算时，断句用例必须 FAIL"""
    global START_USE_Y
    old = START_USE_Y
    START_USE_Y = False
    try:
        lines = 40
        text = ''.join('第%d行的内容写在这里' % i
                       + ('。' if i % 3 == 2 else '，') for i in range(lines))
        seg = Seg(text, 0, 0, 1000, 1600)
        st = start_in(seg, int(3 / 30 * 1000), int(20 / 40 * 1600))
        # 3.8 会估到全文前 10% 再被推到篇头，必须不在中段
        failed = not (0.25 < st / float(len(text)) < 0.65)
        return failed, 'x-only start=%d ratio=%.3f (应落在前段)' % (
            st, st / float(len(text)))
    finally:
        START_USE_Y = old


def run_once():
    failed = 0
    for fn in CASES:
        try:
            ok, detail = fn()
        except Exception as e:
            ok, detail = False, '%s: %s' % (type(e).__name__, e)
        print(('PASS' if ok else 'FAIL'), '-', fn.__name__, '|', str(detail)[:150])
        if not ok:
            failed += 1
    print('verify_picker: %d/%d passed' % (len(CASES) - failed, len(CASES)))
    return failed


def main():
    return 1 if run_once() else 0


if __name__ == '__main__':
    sys.exit(main())
