#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
3.8 新增：TextPicker 的纯逻辑镜像（收集 / 容差命中 / 拼装 / 缓存失效）。

项目里没有 JDK，跑不了 JUnit；verify_all.py 用 Python 镜像 Java 逻辑。
改了 TextPicker.java 就要同步改这里，且必须做**反向验证**：
把容差关掉，点偏用例必须拿不到目标段，证明测试真的在检验新逻辑。

跑法：python3 verify_picker.py   全 PASS 返回 0
"""
import sys

MAX_WALK = 20000
MAX_SEG = 800
MAX_DEPTH = 500
CACHE_TTL_MS = 8000
TOLERANCE_DP = 96.0
DENSITY = 3.0          # 测试用固定 density，96dp = 288px


# ---------------------------------------------------------------- 数据结构

class Node(object):
    def __init__(self, text, left, top, right, bottom, visible=True,
                 hint=None, tooltip=None, state=None, children=None, desc=None):
        self.text = text
        self.left, self.top, self.right, self.bottom = left, top, right, bottom
        self.visible = visible
        self.hint, self.tooltip, self.state = hint, tooltip, state
        self.desc = desc
        self.children = children or []


class Seg(object):
    def __init__(self, text, left, top, right, bottom):
        self.text = text
        self.left, self.top, self.right, self.bottom = left, top, right, bottom

    def __repr__(self):
        return 'Seg(%r,%d,%d,%d,%d)' % (self.text, self.left, self.top,
                                        self.right, self.bottom)


# ---------------------------------------------------------------- 纯字符串

def clean(s):
    if s is None:
        return None
    t = s.strip()
    return t if t else None


def scan_back(text, offset, puncts):
    if text is None or puncts is None:
        return 0
    if offset > len(text):
        offset = len(text)
    for i in range(offset - 1, -1, -1):
        if text[i] not in puncts:
            continue
        s = i + 1
        while s < len(text) and text[s].isspace():
            s += 1
        if s >= len(text):
            return 0
        return s
    return 0


def sentence_start(text, offset):
    if text is None:
        return 0
    if offset <= 0:
        return 0
    if offset > len(text):
        offset = len(text)
    s = scan_back(text, offset, '。！？；\n.!?')
    if s > 0:
        return s
    s = scan_back(text, offset, '，、：,:')
    if s > 0:
        return s
    return offset


def sort_reading_order(segs):
    """镜像 TextLayout.sortReadingOrder：按 top 排 → 纵向分带 → 行内按 left 排"""
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
    """镜像 TextPicker.nodeText：正文 → 描述 → hint → tooltip → state"""
    for v in (n.text, getattr(n, 'desc', None),
              n.hint, n.tooltip, n.state):
        c = clean(v)
        if c is not None:
            return c
    return None


def collect(root, screen=(0, 0, 1080, 2400)):
    out = []
    state = {'visited': 0}

    def intersects(r):
        return not (r[2] < screen[0] or r[0] > screen[2]
                    or r[3] < screen[1] or r[1] > screen[3])

    def walk(node, depth):
        if node is None or depth > MAX_DEPTH or state['visited'] >= MAX_WALK \
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
        if t is None or (child_texts is not None and t in child_texts):
            return
        r = (node.left, node.top, node.right, node.bottom)
        if r[2] <= r[0] or r[3] <= r[1]:
            return
        if not intersects(r):
            return          # 不做 isVisibleToUser 硬过滤
        out.append(Seg(t, *r))

    walk(root, 0)
    sort_reading_order(out)
    return out


# ---------------------------------------------------------------- 命中/拼装

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


def char_start(n, x, exact=True):
    if not exact or len(n.text) < 40:
        return 0
    w = n.right - n.left
    if w <= 0 or not n.text:
        return 0
    ratio = (x - n.left) / float(w)
    ratio = max(0.0, min(1.0, ratio))
    off = int(ratio * len(n.text))
    return sentence_start(n.text, off)


def append(sb, s):
    if not s:
        return
    if len(s) <= len(''.join(sb)) and ''.join(sb).endswith(s):
        return
    if sb:
        sb.append(' ')
    sb.append(s)


def join_from(segs, idx, x, y):
    if idx < 0:
        return join_below(segs, y)
    out = []
    head = segs[idx]
    exact = (head.left <= x <= head.right and head.top <= y <= head.bottom)
    t = head.text
    st = char_start(head, x, exact)
    if 0 < st < len(t):
        t = t[st:]
    append(out, t)
    for n in segs[idx + 1:]:
        append(out, n.text)
    s = ''.join(out).strip()
    return s or None


def join_below(segs, y):
    out = []
    for n in segs:
        if n.bottom <= y:
            continue
        append(out, n.text)
    s = ''.join(out).strip()
    return s or None


class Picker(object):
    """镜像 TextPicker 的缓存有效性（纯计算部分）"""
    def __init__(self):
        self.cache = None
        self.seq = -1
        self.time = 0

    def prepare(self, root, seq, now=1000):
        segs = collect(root)
        self.cache = segs or None
        self.seq = seq
        self.time = now

    def invalidate(self):
        self.cache, self.seq, self.time = None, -1, 0

    def pick(self, x, y, seq, now=1000):
        if self.cache is None or self.seq != seq or now - self.time > CACHE_TTL_MS:
            return None
        return join_from(self.cache, hit(self.cache, x, y), x, y)


# ---------------------------------------------------------------- 用例

def n(text, box, **kw):
    return Node(text, box[0], box[1], box[2], box[3], **kw)


def root_of(*children):
    return Node(None, 0, 0, 1080, 2400, children=list(children))


CASES = []


def case(fn):
    CASES.append(fn)
    return fn


@case
def case_exact_hit():
    root = root_of(
        n('第一段话', (10, 10, 300, 50)),
        n('第二段话', (10, 100, 300, 140)),
        n('第三段话', (10, 200, 300, 240)))
    got = join_from(collect(root), hit(collect(root), 100, 120), 100, 120)
    return '第二' in got and '第三' in got and '第一' not in got, got


@case
def case_tolerance_gap_hit():
    """点在两段之间的行间距，必须读到上面那一段（旧逻辑会整行跳过）"""
    segs = collect(root_of(
        n('上一行文字', (10, 10, 300, 50)),
        n('下一行文字', (10, 100, 300, 140))))
    idx = hit(segs, 100, 75)
    got = join_from(segs, idx, 100, 75)
    return idx >= 0 and '上一行' in got, got


@case
def case_tolerance_limit():
    segs = collect(root_of(n('远处一段', (10, 10, 300, 50))))
    idx = hit(segs, 900, 2000)
    return idx == -1, 'idx=%d' % idx


@case
def case_visibility_not_filtered():
    segs = collect(root_of(n('被误判不可见的字', (10, 10, 300, 50),
                             visible=False)))
    return len(segs) == 1 and '被误判' in segs[0].text, [x.text for x in segs]


@case
def case_parent_text_kept():
    """父文本与子文本不同 → 两段都保留（抓更多字）"""
    child = n('子文本', (10, 60, 200, 100))
    parent = Node('父说明', 0, 0, 300, 140, children=[child])
    segs = collect(root_of(parent))
    texts = [x.text for x in segs]
    return '父说明' in texts and '子文本' in texts, texts


@case
def case_parent_exact_dup_dropped():
    child = n('同一句', (10, 60, 200, 100))
    parent = Node('同一句', 0, 0, 300, 140, children=[child])
    texts = [x.text for x in collect(root_of(parent))]
    return texts == ['同一句'], texts


@case
def case_hint_tooltip_state():
    segs = collect(root_of(
        n(None, (10, 10, 200, 50), hint='请输入手机号'),
        n(None, (10, 60, 200, 100), tooltip='长按可编辑'),
        n(None, (10, 110, 200, 150), state='已选中')))
    texts = [x.text for x in segs]
    return ('请输入手机号' in texts and '长按可编辑' in texts
            and '已选中' in texts), texts


@case
def case_budget_wide():
    kids = [n('第%d段' % i, (10, i * 3, 300, i * 3 + 2))
            for i in range(600)]
    segs = collect(root_of(*kids))
    return any('第599段' in x.text for x in segs), len(segs)


@case
def case_charstart_sentence():
    long_text = '第一句。' + '第二句内容很长' * 10   # 超过 40 字才启用比例定位
    seg = Seg(long_text, 0, 0, 200, 40)
    # 点在 80% 宽度处，应往回找到句号后
    st = char_start(seg, 160, exact=True)
    return st == 4, 'start=%d len=%d' % (st, len(long_text))


@case
def case_short_segment_not_trimmed():
    """短段即使精确点中也不按比例切，必须整段读出（别丢第一个字）"""
    segs = collect(root_of(n('第二段话', (10, 100, 300, 140))))
    got = join_from(segs, 0, 100, 120)
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
    p = Picker()
    root = root_of(n('缓存内容', (10, 10, 300, 50)))
    p.prepare(root, seq=7, now=1000)
    ok1 = p.pick(50, 30, 7, now=1000) is not None
    bad_seq = p.pick(50, 30, 8, now=1000) is None
    bad_ttl = p.pick(50, 30, 7, now=1000 + CACHE_TTL_MS + 1) is None
    p.invalidate()
    bad_invalid = p.pick(50, 30, 7, now=1000) is None
    return ok1 and bad_seq and bad_ttl and bad_invalid, \
        (ok1, bad_seq, bad_ttl, bad_invalid)


@case
def case_reverse_no_tolerance_fails():
    """反向验证：容差关掉时，行间距那一点必须读不到上面那段"""
    segs = collect(root_of(
        n('上一行文字', (10, 10, 300, 50)),
        n('下一行文字', (10, 100, 300, 140))))
    idx = hit(segs, 100, 75, tolerance_dp=0)
    got = join_from(segs, idx, 100, 75)
    lost = (idx == -1) and ('上一行' not in (got or ''))
    return lost, 'idx=%d got=%r' % (idx, got)


def run_once():
    failed = 0
    for fn in CASES:
        try:
            ok, detail = fn()
        except Exception as e:
            ok, detail = False, '%s: %s' % (type(e).__name__, e)
        print(('PASS' if ok else 'FAIL'), '-', fn.__name__, '|', str(detail)[:140])
        if not ok:
            failed += 1
    print('verify_picker: %d/%d passed' % (len(CASES) - failed, len(CASES)))
    return failed


def main():
    return 1 if run_once() else 0


if __name__ == '__main__':
    sys.exit(main())
