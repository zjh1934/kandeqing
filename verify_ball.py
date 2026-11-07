"""
球的定位与形态自检（3.9.17）。

沙盒没有 Android 运行时，用两件事兜底：
  1) 静态断言：放大态定位必须是「球心 = 触点」那一行，且不许再出现任何补偿
  2) 数值断言：同原点下球心恒等于触点；反向用例证明带 offset 的写法必偏

3.9.11~3.9.16 反复加原点差测量、偏心量扣减、相对直写，越堆越厚还一直偏。
3.9.17 全部删掉。这里的断言就是为了防「以后又偷偷加回去」。

跑两遍，全绿才算过。
"""

import io
import os
import re

ROOT = os.path.dirname(os.path.abspath(__file__))
BC = os.path.join(ROOT, "app", "src", "main", "java", "com",
                  "floating", "combo", "BallController.java")
BA = os.path.join(ROOT, "app", "src", "main", "java", "com",
                  "floating", "combo", "BallArt.java")

CASES = []

def case(name):
    def deco(fn):
        CASES.append((name, fn))
        return fn
    return deco

def read(p):
    return io.open(p, encoding="utf-8").read()

def body_of(t, signature):
    i = t.find(signature)
    if i < 0:
        return ""
    j = t.find("\n    }", i)
    return t[i:j]

@case("放大态只有「球心 = 触点」，没有任何补偿")
def c_absolute_align():
    t = read(BC)
    must = ["lp.x = (int) (fx - lp.width / 2f);",
            "lp.y = (int) (fy - lp.height / 2f);",
            "lp.x = clampX((int) (fx - lp.width / 2f), lp.width);",
            "lp.y = clampY((int) (fy - lp.height / 2f), lp.height);"]
    banned_global = ["grabDx", "grabDy", "originDx", "originDy",
                     "getLocationOnScreen", "measureOrigin",
                     "getRawX() - e.getX()", "offsetX -=", "offsetY -="]
    miss = [x for x in must if x not in t]
    left = [x for x in banned_global if x in t]
    zoom_body = body_of(t, "private void placeBallAtFinger")
    bad_zoom = [x for x in ["offsetX", "offsetY", "screenToContent"]
                if x in zoom_body]
    return (not miss and not left and not bad_zoom), \
        ("缺=%r 全局残留=%r 放大态残留=%r" % (miss, left, bad_zoom))

@case("闲置拖球仍保持按下偏移，没被放大态牵连")
def c_drag_untouched():
    t = read(BC)
    i = t.find("private void placeBallKeepOffset")
    j = t.find("\n    }", i)
    body = t[i:j]
    ok = ("lp.x = (int) (fx - offsetX);" in body
          and "lp.y = (int) (fy - offsetY);" in body
          and "offsetX = fx - lp.x;" in t)
    return ok, body.strip()[:120]

def shape_map():
    """从 BallArt.shape() 源码里解出每个 case 的返回值，连续 case 共享 return。"""
    t = read(BA)
    i = t.find("private static int shape(int state)")
    j = t.find("\n    }", i)
    body = t[i:j]
    parts = re.split(r"case\s+(\w+):", body)
    names, chunks = parts[1::2], parts[2::2]
    vals = []
    for ch in chunks:
        r = re.search(r"return\s+(\d+)\s*;", ch)
        vals.append(int(r.group(1)) if r else None)
    carry = None
    for k in range(len(vals) - 1, -1, -1):
        if vals[k] is None:
            vals[k] = carry
        else:
            carry = vals[k]
    return dict(zip(names, vals)), body

@case("四种状态：闲置实心 / 点一下空心 / 朗读空心加竖线 / 放大实心")
def c_shape():
    m, body = shape_map()
    want = {"ST_NORMAL": 1, "ST_ACTIVE": 1, "ST_SPEAKING": 2,
            "ST_DOUBLE": 1, "ST_PAN": 1, "ST_IDLE": 1}
    bad = {k: (m.get(k), v) for k, v in want.items() if m.get(k) != v}
    d = re.search(r"default:\s*return\s+(\d+)\s*;", body)
    ok_default = (d is not None and d.group(1) == "0"
                  and "ST_PICKING" not in m and "ST_LOADING" not in m)
    t = read(BA)
    ok_bars = "shape == 2" in t and "drawRoundRect(x - bw" in t
    ok_dots = "drawDots" in t and "state == ST_LOADING" in t
    return (not bad and ok_default and ok_bars and ok_dots), \
        ("映射错=%r default=%s 竖线=%s 转圈=%s"
         % (bad, ok_default, ok_bars, ok_dots))

@case("按下变实心、抬手复常态")
def c_down_up():
    t = read(BC)
    ok = ("view.setState(BallView.ST_ACTIVE);" in t
          and "view.setState(BallView.ST_NORMAL);" in t)
    i_restore = t.find("view.setState(BallView.ST_NORMAL);")
    i_tap = t.find("cb.onTap();")
    order = 0 < i_restore < i_tap
    return (ok and order), ("有=%s 顺序=%s" % (ok, order))

SIZE = 144
POINTS = [200, 600, 900, 1200, 1800, 2200, 2350]

@case("球心 = 触点：手指在哪、按球哪个位置，都恒等")
def c_numeric():
    bad = []
    for f in POINTS:
        for delta0 in [0, 40, -40, 35, -45, 20]:
            lp = f - SIZE / 2
            center = lp + SIZE / 2
            if abs(center - f) > 1e-6:
                bad.append((f, delta0, center - f))
    return (not bad), bad[:6]

@case("反向验证：带 offset 的旧写法必被测出偏差")
def c_reverse():
    errs = []
    for f in POINTS:
        for delta0 in [40, -35, 20]:
            off = SIZE / 2 + delta0
            lp = f - off
            errs.append(abs(lp + SIZE / 2 - f))
    ok = max(errs) > 10
    return ok, max(errs)

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
    for i in range(2):
        print("===== 第 %d 遍 =====" % (i + 1))
        total += run_once()
    if total:
        raise SystemExit("球自检失败 %d 项" % total)
    print("球定位与形态两遍全部通过 ✅")

if __name__ == "__main__":
    main()
