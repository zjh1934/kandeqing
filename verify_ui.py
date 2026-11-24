"""
3.9.21 配色与 UI 层级自检。

静态断言：新色板、绿球色值、主题两端强调色、层级常量、字号、
白球描边、试听归组、版本号、订阅资产名。两遍全绿才算过。
"""
import io, os, re, sys
ROOT = os.path.dirname(os.path.abspath(__file__))
J = os.path.join(ROOT, "app", "src", "main", "java", "com", "floating", "combo")
CASES = []
def case(n):
    def d(f): CASES.append((n, f)); return f
    return d
def rd(p): return io.open(os.path.join(J, p) if not p.startswith((".","/")) else os.path.join(ROOT,p), encoding="utf-8").read()

@case("清釉七色：统一明度，旧灰调全清")
def c_palette():
    t = rd("Prefs.java")
    new = ["0xFFF4F7FA","0xFF6FA8CF","0xFF79C2AE","0xFFE0B873",
           "0xFFE08E86","0xFF9C90D8","0xFF6E8B9B"]
    old = ["0xFFFAFAFA","0xFF4A7FD4","0xFF2E9E8F","0xFFD9963F",
           "0xFFD96B6B","0xFF8B74D6","0xFF5A7494"]
    names = ["月白","霁蓝","青瓷","麦金","藕红","鸢尾","岩青"]
    return (all(x in t for x in new) and not any(x in t for x in old)
            and all(n in t for n in names)), ""

@case("绿球提亮 + 朗读态深绿")
def c_green():
    t = rd("ClipBallView.java")
    return ("0xFF1E8A68" in t and "0xFF166A50" in t
            and "0xFF0F7A4F" not in t), ""

@case("主题强调色跟随球色：深亮蓝 / 浅沉蓝")
def c_accent():
    t = rd("Theme.java")
    return ("0xFF6FA8CF" in t and "0xFF3F6FA8" in t
            and "0xFF4AA8FF" not in t and "0xFF2F6BFF" not in t
            and "0xFF1E8A68" in t), ""

@case("层级收紧：行高 / 组距 / 圆角 / 命中区")
def c_density():
    t = rd("Theme.java")
    want = ["GAP_GROUP = 8","CARD_R = 12","HIT = 44","ROW_SELECT = 44",
            "ROW_SWITCH = 44","ROW_ACTION = 44","ROW_SLIDER = 48",
            "ROW_CHIPS = 56","ROW_QR = 132"]
    miss = [x for x in want if x not in t]
    return not miss, miss

@case("字号降级：正文 13 / 说明 11 / 组标题 12 且用 TEXT_2")
def c_type():
    t = rd("Ui.java")
    return ("return text(c, s, 13, Theme.TEXT, false);" in t
            and "return text(c, s, 11, Theme.TEXT_2, false);" in t
            and "text(c, title, 12, Theme.TEXT_2, true)" in t
            and "dp(c, 2), dp(c, 12)" in t
            and "hlp.topMargin = dp(c, 6);" in t), ""

@case("亮色球加浅描边，浅壁纸上不再消失")
def c_outline():
    t = rd("BallArt.java")
    return ("(rgb >> 16) & 0xFF) > 225" in t
            and "Paint.Style.STROKE" in t
            and "90, 105, 125" in t), ""

@case("主开关唯一强调 + 长说明压一行 + 试听并入声音组")
def c_page():
    t = rd("MainActivity.java")
    return ("Ui.dp(this, Theme.HIT + 8)))" in t
            and "复制后点绿球最稳 · 自动读受系统限制 · 不联网不记内容" in t
            and 'Ui.group(this, "声音",\n                spEngine, spVoice, spScope, btnTry)' in t
            and "page.addView(btnTry, Ui.lp" not in t
            and t.count("btnTry = Ui.primaryButton") == 1), ""

@case("滑块不再被固定行高裁切（3.9.21 回归点）")
def c_slider():
    t = rd("Ui.java")
    seg = t[t.find("public static LinearLayout sliderRow"):t.find("public static LinearLayout switchRow")]
    return ("WRAP_CONTENT" in seg
            and "setMinimumHeight(dp(c, Theme.ROW_SLIDER))" in seg
            and "dp(c, 36)" in seg
            and "rowLp(c, Theme.ROW_SLIDER)" not in seg), ""

@case("下拉主行不再被固定行高裁切，展开菜单完整")
def c_select():
    t = rd("Ui.java")
    seg = t[t.find("public SelectRow(Context"):t.find("public void setItems")]
    return ("WRAP_CONTENT" in seg
            and "row.setMinimumHeight(dp(c, Theme.ROW_SELECT))" in seg
            and "rowLp(c, Theme.ROW_SELECT)" not in seg), ""

@case("第四选项卡接回诊断与日志：查看/复制/导出/刷新/清空")
def c_log():
    t = rd("MainActivity.java")
    seg = t[t.find("private LinearLayout pageMore()"):]
    return ('Ui.group(this, "诊断与日志", logSection())' in seg
            and "private LinearLayout logSection()" in t
            and "private void refreshLog()" in t
            and "Log.content()" in t
            and "Log.export(MainActivity.this)" in t
            and "Log.clear()" in t
            and "Log.recent()" in t
            and "Ui.smallButton" in t), ""

@case("音色接线：空列表不静默、切换即 apply 且有反馈")
def c_voice():
    t = rd("MainActivity.java")
    return ("voices.isEmpty()" in t
            and "TtsManager.applyVoice(name)" in t
            and "已切换音色" in t
            and "TtsManager.saveVoice(MainActivity.this, name)" in t
            and "else if (TtsManager.isReady())" in t), ""

@case("版本 3.9.22 / versionCode 52")
def c_ver():
    g = io.open(os.path.join(ROOT,"app","build.gradle"),encoding="utf-8").read()
    return ("versionCode 52" in g and 'versionName "3.9.22"' in g), ""

@case("订阅资产仍为固定名 kandeqing-latest.apk")
def c_subscribe():
    w = io.open(os.path.join(ROOT,".github","workflows","build-apk.yml"),encoding="utf-8").read()
    return "kandeqing-latest.apk" in w, ""

def run_once():
    bad = 0
    for n, fn in CASES:
        ok, det = fn()
        bad += 0 if ok else 1
        print(("PASS" if ok else "FAIL"), "-", n, "" if ok else ("-> %r" % (det,)))
    return bad

def main():
    total = 0
    for i in range(2):
        print("===== 第 %d 遍 =====" % (i+1))
        total += run_once()
    if total: raise SystemExit("UI 自检失败 %d 项" % total)
    print("UI 配色与层级自检两遍全部通过 \u2705")
if __name__ == "__main__": main()

