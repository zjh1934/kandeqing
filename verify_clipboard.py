"""
剪贴板朗读自检（3.9.19）。

对照开源项目 DLWangSan/tapread（点读）的实现做静态断言 + 纯逻辑镜像：
两个独立悬浮球、没有双击手势冲突、Android 10+ 靠无障碍焦点读剪贴板、
复制事件驱动自动读、文本过滤与去重、隐私边界。

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


@case("默认：复制朗读开（第二球出现），复制后自动朗读关")
def c_defaults():
    t = read("Prefs.java")
    ok = ("DEF_CLIP_READ = true" in t
          and "DEF_CLIP_AUTO_READ = false" in t
          and re.search(r"clipRead\(\)\s*\{\s*return sp\.getBoolean\(K_CLIP_READ,\s*DEF_CLIP_READ\)", t) is not None
          and re.search(r"clipAutoRead\(\)\s*\{\s*return sp\.getBoolean\(K_CLIP_AUTO_READ,\s*DEF_CLIP_AUTO_READ\)", t) is not None)
    return ok, ""


@case("两个开关在朗读页，复制朗读开关会立刻刷新服务")
def c_ui():
    t = read("MainActivity.java")
    ok = ("Ui.switchRow(this, \"复制朗读\", prefs.clipRead()" in t
          and "Ui.switchRow(this, \"复制后自动朗读\", prefs.clipAutoRead()" in t
          and "prefs.setClipRead(on);" in t
          and "ComboService.refresh();" in t
          and "prefs.setClipAutoRead(on);" in t
          and "Ui.group(this, \"剪贴板\"" in t)
    return ok, ""


@case("单击零延迟：BallController 里没有任何双击残留")
def c_no_double():
    t = read("BallController.java")
    banned = ["onDoubleTap", "DOUBLE_TAP_MS", "lastTapMs", "lastTapX",
              "doubleSlop", "SystemClock", "isDouble"]
    left = [x for x in banned if x in t]
    ok = (not left and "cb.onTap();" in t)
    return ok, left


@case("第二球是独立的 WindowManager 球，位置单独存")
def c_second_ball():
    t = read("ClipBallController.java")
    ok = ("new ClipBallView(ctx)" in t
          and "TYPE_ACCESSIBILITY_OVERLAY" in t
          and "wm.addView(view, lp)" in t
          and "prefs.hasClipBallPos()" in t
          and "prefs.setClipBallPos" not in t
          and "cb.onClipTap();" in t
          and "cb.onClipMoved(lp.x, lp.y);" in t)
    return ok, ""


@case("第二球外观抄点读：绿色 #0F7A4F + 白色喇叭")
def c_look():
    t = read("ClipBallView.java")
    ok = ("0xFF0F7A4F" in t and "GREEN_DEEP" in t
          and "drawCircle" in t and "drawPath" in t
          and "drawArc" in t and "Color.WHITE" in t)
    return ok, ""


@case("服务持有两个球，开关同步显隐")
def c_service_balls():
    t = read("ComboService.java")
    ok = ("implements BallController.Callback, ClipBallController.Callback" in t
          and "private ClipBallController clipBall;" in t
          and "private ClipReader clipReader;" in t
          and "clipBall = new ClipBallController(this, prefs, this);" in t
          and "clipReader = new ClipReader(this);" in t
          and "syncClipBall();" in t
          and "public void onClipTap()" in t
          and "public void onClipMoved(int x, int y)" in t
          and "clipBall.setSpeaking(true)" in t
          and "clipBall.setSpeaking(false)" in t)
    return ok, ""


@case("点第二球：朗读中停止，否则读剪贴板（无双击）")
def c_tap_path():
    t = read("ComboService.java")
    ok = ("if (state == ST_SPEAKING)" in t
          and "stopSpeaking();" in t
          and "readClipAndSpeak(false);" in t
          and "Haptics.tap();" in t)
    return ok, ""


@case("读取三级兜底：直接读 → 绿球取焦 → 1×1 焦点窗")
def c_focus():
    t = read("ClipReader.java")
    ball = read("ClipBallController.java")
    ok = ("readOnce(final Result cb, final FocusHost host)" in t
          and "host.withFocus(" in t
          and "readWithFocusProxy" in t
          and "TYPE_ACCESSIBILITY_OVERLAY" in t
          and "v.requestFocus()" in t
          and "removeFocusProxy()" in t
          and "public void withFocus(final Runnable block)" in ball
          and "~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE" in ball
          and "postDelayed" in ball
          and "lp.flags = original" in ball)
    return ok, ""


@case("剪贴板变化监听回归（点读主通道），成对注册/注销")
def c_listener():
    t = read("ClipReader.java")
    svc = read("ComboService.java")
    ok = ("addPrimaryClipChangedListener(clipListener)" in t
          and "removePrimaryClipChangedListener(clipListener)" in t
          and "public void registerClipListener()" in t
          and "clipReader.setOnClipChanged(clipChangedTask)" in svc
          and "clipReader.registerClipListener()" in svc
          and "clipReader.release()" in svc)
    return ok, ""


@case("双通道 80ms 去重，自动读失败有可见反馈")
def c_schedule():
    t = read("ComboService.java")
    ok = ("scheduleAutoRead(\"clip-listener\", 80, false)" in t
          and "scheduleAutoRead(\"copy-event\", delay, true)" in t
          and "now - lastAutoScheduleAt < 80" in t
          and "autoFromExplicitCopy" in t
          and "lastAutoFailHintAt > 60000" in t
          and "自动读没取到，点一下绿色球就能听" in t
          and "state != ST_IDLE" in t)
    return ok, ""


@case("自动读由复制事件驱动，不是靠剪贴板轮询")
def c_auto_event():
    t = read("ComboService.java")
    ok = ("maybeScheduleAutoRead(event)" in t
          and "TYPE_NOTIFICATION_STATE_CHANGED" in t
          and "TYPE_ANNOUNCEMENT" in t
          and "TYPE_VIEW_CLICKED" in t
          and "ClipReader.isCopyFeedback(event)" in t
          and "ClipReader.isCopyClick(event)" in t
          and "delay = 120" in t and "delay = 280" in t
          and "scheduleAutoRead" in t
          and "h.postDelayed(clipAutoTask, delay)" in t
          and "prefs.clipAutoRead()" in t
          and "state != ST_IDLE" in t)
    return ok, ""


@case("文本过滤：短文本 / 超长 / 验证码 / 纯数字")
def c_cleaner():
    t = read("ClipReader.java")
    ok = ("s.length() < 2 || s.length() > 4000" in t
          and "验证码|校验码|code" in t
          and "d{4,8}$" in t
          and "shouldSpeakAuto" in t
          and "baselineReady" in t
          and "lastSpoken" in t
          and "1200" in t)
    return ok, ""


@case("镜像：clean / shouldSkip 与点读一致")
def c_mirror():
    def clean(raw):
        s = re.sub(r"[\r\n]+", "。", raw.replace("\u00a0", " "))
        s = re.sub(r"\s+", " ", s).strip()
        s = s.strip("。，, . \t")
        return s.strip()

    def skip(raw):
        s = clean(raw)
        if len(s) < 2 or len(s) > 4000:
            return True
        return (re.match(r"(?i)^(验证码|校验码|code)[:：\s]*\d{4,8}$", s)
                is not None) or (re.match(r"^\d{4,8}$", s) is not None)

    samples = [
        ("  复制的文字\n换行  ", False),
        ("a", True),
        ("", True),
        ("验证码：123456", True),
        ("123456", True),
        ("这是一段正常的文字，应该念出来。", False),
    ]
    bad = [(raw, skip(raw), want) for raw, want in samples if skip(raw) != want]
    ok = clean("  甲\n乙  ") == "甲。乙"
    return (not bad and ok), (bad, clean("  甲\n乙  "))


@case("隐私：日志只记字数，自写剪贴板不念，零网络")
def c_privacy():
    t = read("ClipReader.java")
    svc = read("ComboService.java")
    logs = re.findall(r"Log\.[a-z]+\(\"clip\"[^;]*;", svc)
    bad = [x for x in logs if "cleaned" in x and ".length()" not in x]
    ok = ('l.startsWith("看得清")' in t
          and 'l.equals("微信号")' in t
          and not bad and len(logs) >= 1)
    return ok, (logs, bad)


@case("Manifest 没有新增网络 / 剪贴板权限")
def c_manifest():
    p = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
    t = io.open(p, encoding="utf-8").read()
    bad = [k for k in ("android.permission.INTERNET",
                       "android.permission.READ_CLIPBOARD",
                       "android.permission.WRITE_CLIPBOARD") if k in t]
    ok = not bad
    return ok, bad


@case("两个球共用一套 TTS，没有第二套朗读")
def c_single_speak():
    t = read("ComboService.java")
    ok = (t.count("TtsManager.speak(") == 1
          and "speakNow(cleaned);" in t
          and "speakNow(text);" in t)
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
