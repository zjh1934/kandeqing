"""
剪贴板朗读自检（3.9.18）。

沙盒没有 Android 运行时，用静态断言 + 纯逻辑镜像兜底：
开关默认值、双击判定不给单击加延迟、自动朗读不打断用户操作、
剪贴板正文不进日志、App 自己写入的剪贴板不念、监听成对注销。

跑两遍，全绿才算过。
"""

import io
import os
import re

ROOT = os.path.dirname(os.path.abspath(__file__))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com",
                    "floating", "combo")

CASES = []


def case(name):
    def deco(fn):
        CASES.append((name, fn))
        return fn
    return deco


def read(name):
    return io.open(os.path.join(JAVA, name), encoding="utf-8").read()


@case("默认：双击读剪贴板开，复制后自动朗读关")
def c_defaults():
    t = read("Prefs.java")
    ok = ("DEF_CLIP_TAP_READ = true" in t
          and "DEF_CLIP_AUTO_READ = false" in t
          and re.search(r"clipTapRead\(\)\s*\{\s*return sp\.getBoolean\(K_CLIP_TAP_READ,\s*DEF_CLIP_TAP_READ\)", t) is not None
          and re.search(r"clipAutoRead\(\)\s*\{\s*return sp\.getBoolean\(K_CLIP_AUTO_READ,\s*DEF_CLIP_AUTO_READ\)", t) is not None)
    return ok, t[:0]


@case("两个开关都在朗读页，且都接 Prefs")
def c_ui():
    t = read("MainActivity.java")
    ok = ("Ui.switchRow(this, \"双击球读剪贴板\", prefs.clipTapRead()" in t
          and "Ui.switchRow(this, \"复制后自动朗读\", prefs.clipAutoRead()" in t
          and "prefs.setClipTapRead(on);" in t
          and "prefs.setClipAutoRead(on);" in t
          and "Ui.group(this, \"剪贴板\"" in t)
    return ok, t[:0]


@case("双击判定用系统超时 + 位移容差，且单击没有被延迟")
def c_double():
    t = read("BallController.java")
    ok = ("ViewConfiguration.getDoubleTapTimeout()" in t
          and "(now - lastTapMs) <= DOUBLE_TAP_MS" in t
          and "Math.abs(fx - lastTapX) <= doubleSlop" in t
          and "Math.abs(fy - lastTapY) <= doubleSlop" in t
          and "cb.onDoubleTap();" in t
          and "cb.onTap();" in t
          and "void onDoubleTap();" in t)
    no_delay = not re.search(r"postDelayed\([^;]*onTap", t)
    i_restore = t.find("view.setState(BallView.ST_NORMAL);")
    i_tap = t.find("cb.onTap();")
    ok = ok and no_delay and 0 < i_restore < i_tap
    return ok, ("no_delay=%s order=%s" % (no_delay, 0 < i_restore < i_tap))


def mirror(now, last_ms, fx, last_x, fy, last_y, timeout=300, slop=20):
    is_double = (last_ms > 0 and now - last_ms <= timeout
                 and abs(fx - last_x) <= slop and abs(fy - last_y) <= slop)
    return "double" if is_double else "single"


@case("镜像：窗口内且近 → 双击；超时 / 位移大 → 单击；第三次不算双击")
def c_mirror():
    seq = [
        (1000, 0, 200, 0, 300, 0, "single"),
        (1200, 1000, 205, 200, 303, 300, "double"),
        (1500, 0, 200, 200, 300, 300, "single"),
        (1001, 700, 200, 200, 300, 300, "single"),
        (1000, 900, 260, 200, 300, 300, "single"),
    ]
    bad = [(a, mirror(*a[:6]), a[6]) for a in seq if mirror(*a[:6]) != a[6]]
    return not bad, bad


@case("自动朗读：只在待命态、开关开、内容不同才读")
def c_auto():
    t = read("ComboService.java")
    ok = ("if (prefs == null || !prefs.clipAutoRead())" in t
          and "if (zooming || waitingSpeak || state != ST_IDLE)" in t
          and "if (text.equals(lastAutoClipText))" in t
          and "lastAutoClipText = text;" in t
          and "h.removeCallbacks(clipAutoTask);" in t)
    return ok, t[:0]


@case("双击：开关关时退回原行为，开时作废选字并读剪贴板")
def c_tap():
    t = read("ComboService.java")
    ok = ("public void onDoubleTap()" in t
          and "if (!prefs.clipTapRead())" in t
          and "onTap();" in t
          and "abortPendingGrab(true);" in t
          and "removeOverlay();" in t
          and "readClipboardAloud(false);" in t)
    return ok, t[:0]


@case("监听成对注册 / 注销")
def c_listener():
    t = read("ComboService.java")
    ok = ("addPrimaryClipChangedListener(clipListener)" in t
          and "removePrimaryClipChangedListener(clipListener)" in t
          and "registerClipListener();" in t
          and "OnPrimaryClipChangedListener" in t)
    return ok, t[:0]


@case("日志只记字数，剪贴板正文一个字都不进日志")
def c_privacy():
    t = read("ComboService.java")
    clip_logs = re.findall(r"Log\.[a-z]+\(\"clip\"[^;]*;", t)
    bad = [x for x in clip_logs if "text" in x and ".length()" not in x]
    ok = len(clip_logs) >= 1 and not bad
    return ok, (clip_logs, bad)


@case("App 自己复制的分享文案 / 微信号不念")
def c_self():
    t = read("ComboService.java")
    return ("l.startsWith(\"看得清\")" in t
            and "l.equals(\"微信号\")" in t), t[:0]


@case("抓字链路仍复用同一份 speakNow，没有第二套朗读")
def c_single_speak():
    t = read("ComboService.java")
    ok = (t.count("TtsManager.speak(") == 1
          and "speakNow(text);" in t
          and "private void startSpeak(String text, int seq)" in t)
    return ok, t.count("TtsManager.speak(")


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
        raise SystemExit("剪贴板自检失败 %d 项" % total)
    print("剪贴板自检两遍全部通过 ✅")


if __name__ == "__main__":
    main()
