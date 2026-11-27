#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对外页面门禁：GitHub / Pages / 文档必须跟当前软件版本一致。

发新版只改 app/build.gradle 的版本号，这里就会点名哪一页没跟上，
避免「软件一直更新、GitHub 页面原地踏步」。

只校验文本口径，不联网；仓库 description 由 docs/repo-meta.json 声明、
推送脚本同步到 GitHub API。
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
CASES = []


def case(n):
    def d(f):
        CASES.append((n, f))
        return f
    return d


def rd(p):
    return io.open(os.path.join(ROOT, p), encoding="utf-8").read()


def ver():
    g = rd("app/build.gradle")
    m = re.search(r'versionName\s+"([^"]+)"', g)
    c = re.search(r"versionCode\s+(\d+)", g)
    return (m.group(1) if m else ""), (c.group(1) if c else "")


VERSION, CODE = ver()

# 每个对外页面必须出现的现状口径（按文件挑最小必要集合）
REQUIRED = {
    "README.md": ["两个独立的小球", VERSION, "kandeqing-v", "feed.atom",
                  "诊断", "复制朗读", "AOCI", "明朗"],
    "README.en.md": ["Two independent floating balls", VERSION, "kandeqing-v",
                     "feed.atom", "Diagnostics", "Copy & Read", "AOCI"],
    "docs/index.html": ["两个独立小球", VERSION, "ball-before-after-323.png",
                        "feed.atom", "诊断与日志", "AOCI", "kandeqing-latest.apk"],
    "使用说明.md": ["两个小球", "绿球", "feed.atom", "诊断与日志"],
    "docs/开发者.md": ["VERSION_MARK", "verify_pages.py", "AOCI", "ClipReader.java"],
    "docs/推广素材.md": ["两个独立小球", "Two independent floating balls", VERSION],
    "promo/下载页.html": ["绿喇叭球", "kandeqing-latest.apk"],
    "docs/privacy.md": ["剪贴板", "验证码", "诊断日志"],
    "SECURITY.md": ["kandeqing-v<版本>.apk", VERSION, "feed.atom", "固定签名"],
    "CONTRIBUTING.md": ["aoci_sync.py", "verify_pages.py", "绿球"],
    ".github/ISSUE_TEMPLATE/bug_report.md": ["绿球", "自动朗读"],
    ".github/PULL_REQUEST_TEMPLATE.md": ["verify_pages", "aoci_sync.py"],
    "app/src/main/res/values/strings.xml": ["两个独立悬浮球", "绿喇叭球"],
    "docs/aoci.md": ["aoci.txt", "baseline.json", "不进 APK"],
    "docs/repo-meta.json": ["VERSION_MARK", "两个独立悬浮球"],
}

# 精确禁止的过时口径（只在「历史/否定」语境外用）
BANNED = [
    "一个悬浮球，把「按住放大」",       # README 旧首屏
    "One floating ball that does both",  # 英文 README 旧首屏
    "One ball. Two actions.",           # 落地页旧英文段
    "只有两个动作",                      # 落地页旧 how 标题
    "最终选「清釉」",                    # 3.9.21 过时预览节
    "3.9.21 · 配色与层级",               # 落地页旧预览节标题
    "kanqing-<版本>.apk",                # SECURITY 旧资产名
    "skip password fields",              # 英文 README 与代码相反
    "屏幕右边就会出现一个小球",          # 给长辈说明旧文案
    "屏幕上有个小球",                    # 推广短帖旧文案
    "收进了一个悬浮球里",                # 推广长帖旧文案
    "The ball appears at the screen edge",  # 英文 README 旧安装步骤
    "3. 屏幕边上出现小球",               # 中文 README 旧安装步骤
]

SCAN = ["README.md", "README.en.md", "docs/index.html", "使用说明.md",
        "docs/推广素材.md", "promo/下载页.html", "SECURITY.md",
        "CONTRIBUTING.md"]


@case("版本号能从 build.gradle 读到，且所有页面写的是当前版本")
def c_version():
    if not VERSION or not CODE:
        return False, "读不到 versionName / versionCode"
    bad = []
    for p in ["README.md", "README.en.md", "docs/index.html", "SECURITY.md"]:
        s = rd(p)
        if VERSION not in s:
            bad.append(p)
    return not bad, bad


@case("每个对外页面都带齐当前现状关键词")
def c_required():
    bad = []
    for p, keys in REQUIRED.items():
        if not os.path.exists(os.path.join(ROOT, p)):
            bad.append(p + "(缺文件)")
            continue
        s = rd(p)
        want = [VERSION if k == "VERSION_MARK" else k for k in keys]
        miss = [k for k in want if k not in s]
        if miss:
            bad.append("%s 缺 %s" % (p, miss))
    return not bad, bad


@case("页面上没有残留过时口径")
def c_banned():
    bad = []
    for p in SCAN:
        s = rd(p)
        for b in BANNED:
            if b in s:
                bad.append("%s -> %r" % (p, b))
    return not bad, bad


@case("落地页主下载按钮是 raw 无缓存直链，不是会滞后一版的 jsDelivr")
def c_landing_btn():
    h = rd("docs/index.html")
    m = re.search(r'<a class="btn btn-p" href="([^"]+)">下载安装包', h)
    if not m:
        return False, "找不到主下载按钮"
    want = "https://raw.githubusercontent.com/zjh1934/kandeqing/main/apk/kandeqing-latest.apk"
    return m.group(1) == want, m.group(1)


@case("落地页结构完整：footer 在最后，无页脚后悬挂 section，无过时预览")
def c_landing_order():
    h = rd("docs/index.html")
    i_footer = h.find("<footer")
    i_body_end = h.find("</body>")
    i_last_sec = h.rfind("<section")
    checks = {
        "footer 存在": i_footer > 0,
        "section 全在 footer 之前": i_last_sec < i_footer,
        "footer 在 body 结束前": i_footer < i_body_end,
        "无 3.9.21 旧预览节": "3.9.21 · 配色与层级" not in h,
        "无 palette-before 旧图": "preview/palette-before.png" not in h,
        "有 3.9.23 新预览": "preview/ball-before-after-323.png" in h,
        "nav 有订阅入口": 'href="#subscribe"' in h,
    }
    bad = [k for k, v in checks.items() if not v]
    return not bad, bad


@case("订阅源与固定直链在关键页面口径一致")
def c_links():
    feed = "https://cdn.jsdelivr.net/gh/zjh1934/kandeqing@main/docs/feed.atom"
    raw = "https://raw.githubusercontent.com/zjh1934/kandeqing/main/apk/kandeqing-latest.apk"
    bad = []
    for p in ["README.md", "README.en.md", "docs/index.html", "使用说明.md", "SECURITY.md"]:
        s = rd(p)
        if feed not in s:
            bad.append(p + " 缺 feed.atom")
    for p in ["README.md", "README.en.md", "docs/index.html", "使用说明.md",
              "promo/下载页.html", "SECURITY.md"]:
        if raw not in rd(p):
            bad.append(p + " 缺 raw 直链")
    return not bad, bad


@case("仓库简介声明文件齐全，且与页面口径一致")
def c_meta():
    p = "docs/repo-meta.json"
    if not os.path.exists(os.path.join(ROOT, p)):
        return False, "缺 docs/repo-meta.json"
    m = json.loads(rd(p))
    d = m.get("description", "")
    t = m.get("topics", [])
    ok = ("两个独立悬浮球" in d and "clipboard" in d.lower()
          and "no internet" in d.lower()
          and "Two independent floating balls" in d
          and len(t) >= 15 and "clipboard" in " ".join(t)
          and "aoci" in " ".join(t))
    return ok, {"description_len": len(d), "topics": len(t)}


@case("verify_all 已纳入页面门禁与 AOCI 门禁")
def c_gate():
    s = rd("verify_all.py")
    return ("verify_pages.py" in s and "verify_aoci.py" in s), ""


@case("AOCI 生成器把页面维护写进了 S 字段")
def c_aoci_docs():
    c = rd("aoci.code.txt")
    return ("当前版本文案" in c and "verify_pages.py" in c
            and "kandeqing-v" in c), ""


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
        print("===== 第 %d 遍（版本 %s / versionCode %s）=====" % (i + 1, VERSION, CODE))
        total += run_once()
    if total:
        raise SystemExit("对外页面门禁失败 %d 项：页面没跟上软件现状" % total)
    print("对外页面与当前版本一致 ✅（%s / versionCode %s）" % (VERSION, CODE))


if __name__ == "__main__":
    main()
