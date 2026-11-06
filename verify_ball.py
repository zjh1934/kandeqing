#!/usr/bin/env python3
"""
球的定位与形态自检（3.9.15）。

沙盒没有 Android 运行时，所以用两件事兜底：
  1) 静态断言：产品代码里必须/必须不出现哪些写法（防「又改回测 D」）
  2) 数值模拟：两种窗口放大模型下，放大态球显示中心必须恒等于手指

跑两遍，全绿才算过。
"""

import io
import os
import re
import sys

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


# ---------------------------------------------------------------- 静态断言

@case("放大态用相对直写，不测原点差")
def c_relative():
    t = read(BC)
    must = ["lp.x = (int) (fx - offsetX);",
            "lp.y = (int) (fy - offsetY);",
            "offsetX -= grabDx;",
            "offsetY -= grabDy;",
            "grabDx = e.getX() - lp.width / 2f;",
            "grabDy = e.getY() - lp.height / 2f;"]
    banned = ["originDx", "originDy", "getLocationOnScreen",
              "getRawX() - e.getX()", "fx - lp.width / 2f -"]
    miss = [x for x in must if x not in t]
    left = [x for x in banned if x in t]
    return (not miss and not left), ("缺=%r 残留=%r" % (miss, left))


def shape_map():
    """从 BallArt.shape() 源码里解出每个 case 的返回值，防止只靠字符串蒙混。
    连续 case（case A: case B: return 1;）共享同一个 return，从后往前传播。"""
    t = read(BA)
    i = t.find("private static int shape(int state)")
    j = t.find("\n    }", i)
    body = t[i:j]
    parts = re.split(r"case\s+(\w+):", body)
    names = parts[1::2]
    chunks = parts[2::2]
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
    # 点一下 / 加载转圈必须走 default（空心环 0），不能混进实心分支
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
    # 恢复必须排在 cb.onTap() 之前，否则会冲掉选字态
    i_restore = t.find("view.setState(BallView.ST_NORMAL);")
    i_tap = t.find("cb.onTap();")
    order = 0 < i_restore < i_tap
    return (ok and order), ("有=%s 顺序=%s" % (ok, order))


# ---------------------------------------------------------------- 数值模拟

SY = 2400
SIZE = 144
POINTS_Y = [200, 600, 900, 1200, 1800, 2200, 2350]


def center_k(f, s):
    """ZoomController.panTo 的不动点公式 + clampCenter"""
    ideal = f - (f - SY / 2) / s
    hw = SY / (2 * s)
    return max(hw, min(SY - hw, ideal))


def shown_center_y(D, lpy, fy, s, model):
    """球窗口在屏幕上的显示中心 y"""
    c_content = D + lpy + SIZE / 2
    if model == 1:                       # 悬浮窗不随系统放大变换
        return c_content
    return SY / 2 + s * (c_content - center_k(fy, s))   # 随放大变换


def simulate(D, delta0, s, model):
    """
    按下：手指在球中心偏 delta0 处（e.getY()-SIZE/2）
      downRawY = D + lp_down + SIZE/2 + delta0
      offsetY  = downRawY - lp_down = D + SIZE/2 + delta0
    holdTask：offsetY -= delta0  →  D + SIZE/2
    放大态：lp.y = fy - offsetY
    """
    f_down = 900
    lp_down = f_down - D - SIZE / 2 - delta0
    off = f_down - lp_down
    off -= delta0
    worst = 0.0
    for fy in POINTS_Y:
        lpy = fy - off
        got = shown_center_y(D, lpy, fy, s, model)
        worst = max(worst, abs(got - fy))
    return worst


@case("两种窗口模型 / D / 偏心量 / 倍率下偏差恒为 0")
def c_numeric():
    bad = []
    for D in [0, 72, 100, 120, 168]:
        for d0 in [0, 40, -40, 35, -45, 20]:
            for s in [1.0, 1.5, 2.0, 3.0, 5.0, 8.0]:
                for model in [1, 2]:
                    err = simulate(D, d0, s, model)
                    if err > 1e-6:
                        bad.append((D, d0, s, model, round(err, 3)))
    return (not bad), bad[:6]


@case("反向验证：不扣偏心量必被测出（旧 3.9.12 行为）")
def c_reverse():
    # 故意不扣 delta0，偏差应等于 |delta0| 或 s*|delta0|，必须非 0
    D, d0, s = 100, 40, 3.0
    off = D + SIZE / 2 + d0
    errs = []
    for fy in POINTS_Y:
        lpy = fy - off
        errs.append(abs(shown_center_y(D, lpy, fy, s, 1) - fy))
        errs.append(abs(shown_center_y(D, lpy, fy, s, 2) - fy))
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
