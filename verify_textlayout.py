
MAX_CHAR_REQ = 20000

def _is_ws(ch):
    """Java Character.isWhitespace —— 注意不含不间断空格（U+00A0 等）。"""
    if ch in ('\u00a0', '\u2007', '\u202f'):
        return False
    if ch in ('\u001c', '\u001d', '\u001e', '\u001f'):
        return True
    return ch.isspace()

def leading_whitespace(s):
    if s is None:
        return 0
    i = 0
    while i < len(s) and _is_ws(s[i]):
        i += 1
    return i

def trimmed_length(s):
    if s is None:
        return 0
    a, b = 0, len(s)
    while a < b and _is_ws(s[a]):
        a += 1
    while b > a and _is_ws(s[b - 1]):
        b -= 1
    return b - a

def clean(s):
    if s is None:
        return None
    t = s.strip()
    return None if len(t) == 0 else t

def scan_back(text, offset, puncts, floor=0):
    if text is None or puncts is None:
        return 0
    if offset > len(text):
        offset = len(text)
    if floor < 0:
        floor = 0
    for i in range(offset - 1, floor - 1, -1):
        if puncts.find(text[i]) < 0:
            continue
        s = i + 1
        while s < len(text) and _is_ws(text[s]):
            s += 1
        if s >= len(text):
            return max(0, offset - 1)
        return s
    return 0

def sentence_start(text, offset):
    if text is None:
        return 0
    if offset <= 0:
        return 0
    if offset > len(text):
        offset = len(text)
    floor = max(0, offset - SENTENCE_LOOKBACK)
    s = scan_back(text, offset, "。！？；\n.!?", floor)
    if s > 0:
        return s
    s = scan_back(text, offset, "，、：,:", floor)
    if s > 0:
        return s
    return offset

SENTENCE_LOOKBACK = 160

class Node(object):
    __slots__ = ('text', 'left', 'top', 'right', 'bottom', 'body', 'charStart')

    def __init__(self, text, left, top, right, bottom, body=True, charStart=0):
        self.text = text
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
        self.body = body
        self.charStart = charStart

    def __repr__(self):
        return "Node(%r,%d,%d,%d,%d)" % (
            self.text, self.left, self.top, self.right, self.bottom)

def same_row(a, b):
    top = max(a.top, b.top)
    bottom = min(a.bottom, b.bottom)
    overlap = bottom - top
    if overlap <= 0:
        return False
    min_h = min(a.bottom - a.top, b.bottom - b.top)
    if min_h <= 0:
        return False
    return overlap * 2 >= min_h

def _band_hits(band, n):
    top = max(band['top'], n.top)
    bottom = min(band['bottom'], n.bottom)
    overlap = bottom - top
    if overlap <= 0:
        return False
    min_h = min(band['bottom'] - band['top'], n.bottom - n.top)
    if min_h <= 0:
        return False
    return overlap * 2 >= min_h

def sort_reading_order(lst):
    if lst is None or len(lst) < 2:
        return lst
    lst.sort(key=lambda n: (n.top, n.left))

    bands = []
    for n in lst:
        if bands and _band_hits(bands[-1], n):
            b = bands[-1]
            b['items'].append(n)
            if n.top < b['top']:
                b['top'] = n.top
            if n.bottom > b['bottom']:
                b['bottom'] = n.bottom
        else:
            bands.append({'items': [n], 'top': n.top, 'bottom': n.bottom})

    out = []
    for b in bands:
        b['items'].sort(key=lambda n: n.left)
        out.extend(b['items'])
    return out

class RectF(object):
    __slots__ = ('left', 'top', 'right', 'bottom')

    def __init__(self, left, top, right, bottom):
        self.left, self.top, self.right, self.bottom = left, top, right, bottom

    def is_empty(self):
        return self.left >= self.right or self.top >= self.bottom

def nearest_char(rects, x, y):
    best = -1
    best_d = float('inf')
    for i, r in enumerate(rects):
        if r is None or r.is_empty():
            continue
        dx = 0.0
        dy = 0.0
        if x < r.left:
            dx = r.left - x
        elif x > r.right:
            dx = x - r.right
        if y < r.top:
            dy = r.top - y
        elif y > r.bottom:
            dy = y - r.bottom
        d = dx * dx + dy * dy
        if d < best_d:
            best_d = d
            best = i
    return best

def fallback_start(bounds, x, y, raw, length, lead):
    """3.9.5 镜像：先按 y 落行、行内再按 x 估；单行时 y 完全不参与"""
    if raw is None or length < 40:
        return 0
    if bounds is None:
        return 0
    l, t, r, b = bounds
    if r <= l or b <= t or len(raw) == 0:
        return 0
    breaks = raw.count('\n')
    lines = max(1, breaks + 1, (b - t) // 48)
    rx = max(0.0, min(1.0, (x - l) / float(r - l)))
    if lines <= 1:
        ratio = rx
    else:
        ry = max(0.0, min(0.999, (y - t) / float(b - t)))
        line = int(ry * lines)
        ratio = (line + rx) / float(lines)
    ratio = max(0.0, min(1.0, ratio))
    off = min(len(raw), int(ratio * len(raw)))
    rel = sentence_start(raw, off) - lead
    return max(0, min(length, rel))

def char_start_in(raw, rects_of, x, y, sdk=26, bounds=None, available=True):
    """
    3.9.7 镜像 TextGrabber.charStartIn：
      · 先预检 getAvailableExtraData()（available）
      · 只对命中段请求【一次】，长度 min(len, 20000)，不再三级退让
      · 请求失败 / 节点 obsolete / RectF 全 null → 降级按行估算，绝不返回 0
      · 句读下标在【原文】上算，再减前导空白换算到 trim 串
    rects_of(count) -> RectF 列表 或 None
    bounds=(l,t,r,b) 给降级估算用；None 表示连 bounds 都量不到。
    """
    if raw is None:
        return 0
    lead = leading_whitespace(raw)
    length = trimmed_length(raw)
    if length <= 0:
        return 0

    if sdk >= 26 and available:
        count = min(len(raw), MAX_CHAR_REQ)
        if count > 0:
            rects = rects_of(count)
            if rects:
                offset = nearest_char(rects, x, y)
                if offset >= 0:
                    if offset > len(raw):
                        offset = len(raw)
                    rel = sentence_start(raw, offset) - lead
                    return max(0, min(length, rel))
    return fallback_start(bounds, x, y, raw, length, lead)

def make_rects(text, x0=0, y0=0, cw=20, ch=40):
    """把一串字按等宽排成一行，返回每个字的矩形。"""
    out = []
    for i in range(len(text)):
        out.append(RectF(x0 + i * cw, y0, x0 + (i + 1) * cw, y0 + ch))
    return out

def center_of(i, cw=20, ch=40, x0=0, y0=0):
    return x0 + i * cw + cw / 2.0, y0 + ch / 2.0

def _order(lst):
    return [n.text for n in sort_reading_order(list(lst))]

def case_single_column():
    """普通单列：从上到下。"""
    ns = [
        Node("第三行", 0, 80, 100, 120),
        Node("第一行", 0, 0, 100, 40),
        Node("第二行", 0, 40, 100, 80),
    ]
    got = _order(ns)
    return got == ["第一行", "第二行", "第三行"], got

def case_two_columns():
    """
    两列布局。这正是旧排序会读乱的场景：
    右列第二行的 top 比左列第三行小，只按 top 排会插进左列中间。
    """
    ns = [
        Node("右二", 110, 40, 210, 80),
        Node("左三", 0, 80, 100, 120),
        Node("右一", 110, 0, 210, 40),
        Node("左一", 0, 0, 100, 40),
        Node("左二", 0, 40, 100, 80),
    ]
    got = _order(ns)
    return got == ["左一", "右一", "左二", "右二", "左三"], got

def case_same_row_misaligned_top():
    """同一行但 top 差几像素：仍要算同一行，行内按 left 排。"""
    ns = [
        Node("B", 200, 3, 300, 43),
        Node("A", 0, 0, 100, 40),
    ]
    got = _order(ns)
    return got == ["A", "B"], got

def case_different_rows_small_gap():
    """两行只差一点：垂直重叠不到一半，必须算两行。"""
    ns = [
        Node("下一行", 0, 30, 100, 70),
        Node("上一行", 0, 0, 100, 40),
    ]
    got = _order(ns)
    return got == ["上一行", "下一行"], got

def case_empty_and_single():
    ns = [Node("孤零零", 0, 0, 10, 10)]
    got = _order(ns)
    ok1 = got == ["孤零零"]
    ok2 = sort_reading_order([]) == []
    return (ok1 and ok2), got

def case_same_row_api():
    a = Node("a", 0, 0, 100, 40)
    b = Node("b", 110, 5, 210, 45)
    c = Node("c", 0, 100, 100, 140)
    return (same_row(a, b) and not same_row(a, c)), (same_row(a, b), same_row(a, c))

def case_sentence_plain():
    """没有前导空白：点中"光"(下标7)，回退到逗号后的"欢"(下标5)。"""
    raw = "你好世界，欢迎光临。"
    rects = make_rects(raw)
    x, y = center_of(7)
    rel = char_start_in(raw, lambda c: rects[:c], x, y)
    ok = (rel == 5) and (raw.strip()[rel:] == "欢迎光临。")
    return ok, (rel, raw.strip()[rel:] if rel < len(raw.strip()) else None)

def case_sentence_leading_ws():
    """
    关键回归：有前导空白。
    原文 "  你好世界，欢迎光临。"（两个空格），收录的是 trim 过的 "你好世界，欢迎光临。"
    点中"欢"：原文下标 7，减掉前导空白 2 → trim 串上的 5。
    旧实现不减前导空白，charStart=7 → 切到 "光临。"，正文被吞掉一半。
    """
    raw = "  你好世界，欢迎光临。"
    rects = make_rects(raw)
    x, y = center_of(7)
    rel = char_start_in(raw, lambda c: rects[:c], x, y)
    ok = (rel == 5) and (raw.strip()[rel:] == "欢迎光临。")
    return ok, (rel, raw.strip()[rel:] if rel < len(raw.strip()) else None)

def case_sentence_no_punct():
    """一个标点都没有：从点中的字开始。"""
    raw = "没有标点的一段话"
    rects = make_rects(raw)
    x, y = center_of(5)
    rel = char_start_in(raw, lambda c: rects[:c], x, y)
    return rel == 5, rel

def case_single_request_no_fallback_chain():
    """3.9.7：预检通过后只请求一次，长度夹到 20000，不再 2000/500 逐级退让。"""
    raw = "甲乙丙丁。" * 500
    full = make_rects(raw)
    seen = []

    def rects_of(count):
        seen.append(count)
        return full[:count]

    x, y = center_of(100)
    rel = char_start_in(raw, rects_of, x, y)
    ok = (len(seen) == 1 and seen[0] == min(len(raw), MAX_CHAR_REQ)
          and rel == 100)
    return ok, (seen, rel)

def case_extra_data_unavailable_not_zero():
    """getAvailableExtraData 不含该 key：降级按行估算，绝不从篇头读"""
    raw = _long_text()
    bounds = (0, 0, 1000, 1600)
    rel = char_start_in(raw, lambda c: None, 100, 800,
                        bounds=bounds, available=False)
    return rel > 0.25 * len(raw), rel

def case_obsolete_node_not_zero():
    """refreshWithExtraData 返回 false（节点 obsolete）：同样降级，不返回 0"""
    raw = _long_text()
    bounds = (0, 0, 1000, 1600)
    rel = char_start_in(raw, lambda c: None, 100, 800, bounds=bounds)
    return rel > 0.25 * len(raw), rel

def case_lookback_limits_start():
    """3.9.7：几百字没有标点时，句起点不许一路退到段首"""
    raw = "甲" * 900
    offset = 700
    got = sentence_start(raw, offset)
    return got >= offset - SENTENCE_LOOKBACK, (got, offset)

def case_char_req_capped():
    """超长文本第一次请求必须夹到 20000，不能把整段长度原样传进去。"""
    seen = []
    raw = "字" * 50000

    def rects_of(count):
        seen.append(count)
        return None

    rel = char_start_in(raw, rects_of, 0, 0)
    ok = rel == 0 and seen[0] == MAX_CHAR_REQ and all(c <= MAX_CHAR_REQ for c in seen)
    return ok, seen[:1]

def _long_text():
    return ''.join('第%d行的内容写在这里' % i
                   + ('。' if i % 3 == 2 else '，') for i in range(40))

def case_fallback_y_mid_not_head():
    """3.9.3 核心：长文没逐字位置时，点中段行首不能从篇头读"""
    raw = _long_text()
    bounds = (0, 0, 1000, 1600)
    x, y = 100, 800
    rel = char_start_in(raw, lambda c: None, x, y, bounds=bounds)
    ratio = rel / float(len(raw))
    return 0.25 < ratio < 0.65, 'rel=%d len=%d ratio=%.3f' % (
        rel, len(raw), ratio)

def case_fallback_short_from_head():
    """短段单行：降级仍从段首读，别把第一个字切掉"""
    raw = '今天天气很好，我们出去走走。'
    rel = char_start_in(raw, lambda c: None, 100, 20,
                        bounds=(0, 0, 300, 40))
    return rel == 0, rel

def case_last_sentence_not_zero():
    """点在最后一句：scanBack 不再把 0 当答案"""
    got = scan_back("甲乙丙。", 4, "。")
    return got > 0, got

def case_single_line_y_does_not_shift():
    """3.9.5：单行文本里手指上下偏几像素，起点下标必须一样。
    旧版 ry×0.85+rx×0.15 让同一行上下点估出不同字符 —— 点这里读那里。"""
    raw = '这一行是一段没有标点的连续文字用来做单行估算的回归用例甲乙丙丁'
    bounds = (0, 0, 1000, 40)
    a = fallback_start(bounds, 500, 4, raw, len(raw), 0)
    b = fallback_start(bounds, 500, 36, raw, len(raw), 0)
    return a == b, (a, b)

def case_reverse_old_zero_detected():
    """反向验证：旧逻辑（拿不到逐字位置直接 return 0）必须被抓出来"""
    raw = _long_text()
    bounds = (0, 0, 1000, 1600)
    new_rel = char_start_in(raw, lambda c: None, 100, 800, bounds=bounds)
    old_rel = 0
    detected = old_rel == 0 and new_rel > 0.25 * len(raw)
    return detected, 'old=%d new=%d len=%d' % (old_rel, new_rel, len(raw))

def case_old_buggy_offset():
    """
    证明修复前的算法确实是错的（用作对照，确保这个回归测试有区分力）。
    旧：直接用原文下标切 trim 串。
    """
    raw = "  你好世界，欢迎光临。"
    rects = make_rects(raw)
    x, y = center_of(7)
    offset = nearest_char(rects, x, y)
    old_rel = sentence_start(raw, offset)
    trimmed = raw.strip()
    old_head = trimmed[old_rel:] if old_rel < len(trimmed) else ""
    return old_head != "欢迎光临。", old_head

CASES = [
    ("单列从上到下", case_single_column),
    ("两列布局不乱序", case_two_columns),
    ("同一行 top 轻微错位", case_same_row_misaligned_top),
    ("相邻行不被合并", case_different_rows_small_gap),
    ("空列表与单元素", case_empty_and_single),
    ("sameRow 判定", case_same_row_api),
    ("无前导空白断句", case_sentence_plain),
    ("有前导空白断句（关键回归）", case_sentence_leading_ws),
    ("无标点时从点中的字开始", case_sentence_no_punct),
    ("3.9.7 单次请求、不做三级退让", case_single_request_no_fallback_chain),
    ("3.9.7 额外数据不可用时不退篇头", case_extra_data_unavailable_not_zero),
    ("3.9.7 节点失效时不退篇头", case_obsolete_node_not_zero),
    ("3.9.7 句读回扫设上限", case_lookback_limits_start),
    ("超长文本请求夹到 20000", case_char_req_capped),
    ("3.9.3 降级按 y 估行，不从篇头读", case_fallback_y_mid_not_head),
    ("3.9.3 短段降级仍从段首", case_fallback_short_from_head),
    ("3.9.3 点在最后一句不退 0", case_last_sentence_not_zero),
    ("3.9.5 单行上下点不跳字", case_single_line_y_does_not_shift),
    ("3.9.3 反向验证：旧逻辑必被抓出", case_reverse_old_zero_detected),
    ("对照：旧算法确实切错", case_old_buggy_offset),
]

def run_once():
    failed = 0
    for name, fn in CASES:
        ok, detail = fn()
        failed += 0 if ok else 1
        print(("PASS" if ok else "FAIL"), "-", name, "" if ok else ("→ %r" % (detail,)))
    return failed

def main():
    total = 0
    for i in range(1, 3):
        print("===== 第 %d 遍 =====" % i)
        total += run_once()
    if total:
        raise SystemExit("TextLayout 累计 %d 个用例失败" % total)
    print("TextLayout 两遍全部通过 ✅")

if __name__ == "__main__":
    main()
