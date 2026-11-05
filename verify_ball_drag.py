#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
3.9.1：闲置拖球坐标的纯逻辑镜像 + 反向验证。

用户反馈「拖的时候坐标不准」「停了还卡一下」。根因是 3.8 把
placeBallAtFinger 的「球中心对齐手指」写法（要减半宽）套到了
placeBallKeepOffset 的「保持按下相对偏移」路径上，多减了半个球宽；
而 ACTION_UP 落位没减半宽 → 松手瞬间球跳一下。

这里把两条公式镜像出来，断言：
  ① 手指挪 Δ，球必须正好挪 Δ（零偏移）
  ② MOVE 最后位置 == UP 落位（松手不跳）
③ 反向验证：给 MOVE 注入半个球宽偏移 → ①② 必须 FAIL

跑法：python3 verify_ball_drag.py   全 PASS 返回 0
"""
import sys

BALL_W = 72
BALL_H = 72
SCREEN_W = 1080
SCREEN_H = 2400

# 反向验证开关：True = 模拟 3.8 的缺陷（MOVE 多减半宽）
INJECT_HALF_SIZE_BUG = False


def clamp_x(x, size=BALL_W):
    return max(int(-size * 0.5), min(SCREEN_W - size // 2, x))


def clamp_y(y, size=BALL_H):
    return max(0, min(SCREEN_H - size, y))


def down(fx, fy, lp_x, lp_y):
    """ACTION_DOWN：offset 是手指相对球左上角的偏移"""
    return fx - lp_x, fy - lp_y


def move(fx, fy, off_x, off_y, zooming=False):
    """placeBallKeepOffset 的非放大分支（闲置拖球）"""
    x, y = fx - off_x, fy - off_y
    if INJECT_HALF_SIZE_BUG:
        # 3.8 缺陷：误套「球中心对齐手指」，多减半个宽高
        x -= BALL_W // 2
        y -= BALL_H // 2
    return x, y


def up(fx, fy, off_x, off_y, snap_edge=False):
    """ACTION_UP 的 ST_DRAG_BALL 落位（snapEdge 默认 false）"""
    if snap_edge:
        return (int(fx - off_x), int(fy - off_y))     # 动画起点
    return (clamp_x(int(fx - off_x)), clamp_y(int(fy - off_y)))


CASES = []


def case(fn):
    CASES.append(fn)
    return fn


@case
def case_follows_finger_exactly():
    """手指挪 (Δx,Δy)，球必须正好挪 (Δx,Δy)"""
    lp_x, lp_y = 800, 1000
    fx0, fy0 = 830, 1030
    ox, oy = down(fx0, fy0, lp_x, lp_y)
    for dx, dy in [(1, 0), (0, 1), (120, -85), (-200, 300), (17, 43)]:
        nx, ny = move(fx0 + dx, fy0 + dy, ox, oy)
        if (nx - lp_x, ny - lp_y) != (dx, dy):
            return False, 'Δ=(%d,%d) 球实际挪 (%d,%d)' % (
                dx, dy, nx - lp_x, ny - lp_y)
    return True, '五组位移全部零偏移'


@case
def case_grab_point_preserved():
    """按下时抓球的哪个位置，拖动全程都保持那个位置"""
    lp_x, lp_y = 200, 400
    # 抓在球中心偏右下 10px 处
    fx0, fy0 = lp_x + BALL_W // 2 + 10, lp_y + BALL_H // 2 + 10
    ox, oy = down(fx0, fy0, lp_x, lp_y)
    fx1, fy1 = fx0 + 300, fy0 + 250
    nx, ny = move(fx1, fy1, ox, oy)
    # 手指到球左上角的相对位置应保持不变
    ok = (fx1 - nx, fy1 - ny) == (fx0 - lp_x, fy0 - lp_y)
    return ok, 'down=(%d,%d) move=(%d,%d)' % (
        fx0 - lp_x, fy0 - lp_y, fx1 - nx, fy1 - ny)


@case
def case_release_does_not_jump():
    """松手瞬间球不能跳：MOVE 最后位置必须等于 UP 落位"""
    lp_x, lp_y = 700, 900
    fx0, fy0 = 730, 930
    ox, oy = down(fx0, fy0, lp_x, lp_y)
    fx1, fy1 = 640, 1180
    mx, my = move(fx1, fy1, ox, oy)
    ux, uy = up(fx1, fy1, ox, oy)
    jump = abs(ux - mx) + abs(uy - my)
    return jump == 0, 'MOVE=(%d,%d) UP=(%d,%d) jump=%d' % (
        mx, my, ux, uy, jump)


@case
def case_release_at_boundary_no_jump():
    """球原本就在合法范围内时，松手的 clamp 也不该挪动它"""
    lp_x, lp_y = 500, 1200
    fx0, fy0 = 520, 1220
    ox, oy = down(fx0, fy0, lp_x, lp_y)
    fx1, fy1 = 510, 1210
    mx, my = move(fx1, fy1, ox, oy)
    ux, uy = up(fx1, fy1, ox, oy)
    return (mx, my) == (ux, uy), 'MOVE=(%d,%d) UP=(%d,%d)' % (
        mx, my, ux, uy)


@case
def case_snap_edge_start_matches_move():
    """开了贴边也一样：动画起点必须等于拖动最后位置，不能先跳一下"""
    lp_x, lp_y = 300, 600
    fx0, fy0 = 330, 630
    ox, oy = down(fx0, fy0, lp_x, lp_y)
    fx1, fy1 = 480, 700
    mx, my = move(fx1, fy1, ox, oy)
    ux, uy = up(fx1, fy1, ox, oy, snap_edge=True)
    return (mx, my) == (ux, uy), 'MOVE=(%d,%d) 动画起点=(%d,%d)' % (
        mx, my, ux, uy)


@case
def case_reverse_half_size_bug_detected():
    """反向验证：注入 3.8 的半宽偏移 → 上面两个核心断言必须 FAIL"""
    global INJECT_HALF_SIZE_BUG
    old = INJECT_HALF_SIZE_BUG
    INJECT_HALF_SIZE_BUG = True
    try:
        lp_x, lp_y = 700, 900
        fx0, fy0 = 730, 930
        ox, oy = down(fx0, fy0, lp_x, lp_y)
        fx1, fy1 = 640, 1180
        mx, my = move(fx1, fy1, ox, oy)
        ux, uy = up(fx1, fy1, ox, oy)
        follows = (mx - lp_x, my - lp_y) == (fx1 - fx0, fy1 - fy0)
        no_jump = (mx, my) == (ux, uy)
        # 缺陷存在时，两个断言都必须不成立，测试才算真的抓得住它
        detected = (not follows) and (not no_jump)
        return detected, '注入后 follows=%s noJump=%s (都应 False)' % (
            follows, no_jump)
    finally:
        INJECT_HALF_SIZE_BUG = old


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
    print('verify_ball_drag: %d/%d passed' % (len(CASES) - failed, len(CASES)))
    return failed


def main():
    return 1 if run_once() else 0


if __name__ == '__main__':
    sys.exit(main())
