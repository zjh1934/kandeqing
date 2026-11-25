#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AOCI 认知层门禁：资产完整、Entry 覆盖、Baseline 对齐、不进 APK。"""
import io
import json
import os
import re
import subprocess
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


@case("四份正式资产与 .aoci 白名单都在")
def c_assets():
    need = ["aoci.txt", "aoci.meta.txt", "aoci.code.txt", "AGENTS.md",
            ".gitattributes", ".aoci/baseline.json", ".aoci/config.json",
            ".aoci/curation.json", ".aoci/.gitignore", "docs/aoci.md"]
    return all(os.path.exists(os.path.join(ROOT, p)) for p in need), ""


@case("Root / Meta / Code 标记符合 cognition-volumes/v1")
def c_markers():
    r, m, c = rd("aoci.txt"), rd("aoci.meta.txt"), rd("aoci.code.txt")
    return ("#AOCI-ROOT-MANIFEST: 1" in r
            and "#Format-Version: cognition-volumes/v1" in r
            and "#Locale: zh-CN" in r
            and "path=aoci.code.txt" in r
            and "#AOCI-META-VOLUME: 1" in m
            and "#FRAS-Discipline: 2" in m
            and "#AOCI-CODE-VOLUME: 1" in c
            and "===/<repository>/===" in c), ""


@case("Root 顶层不可破原则写全")
def c_invariants():
    r = rd("aoci.txt")
    for k in ["零 INTERNET 权限", "球心恒等于触点", "best-effort",
              "kandeqing-v", "多条目", "代码自绘"]:
        if k not in r:
            return False, k
    return True, ""


@case("每条 Entry 四段齐备且 S 字段写过硬约束")
def c_fras():
    c = rd("aoci.code.txt")
    entries = [l for l in c.splitlines()
               if re.match(r"^[^#\s][^\[\]]*\[[A-Z0-9]+\]: F:", l)]
    ok = all(l.count(" | R:") == 1 and l.count(" | A:") == 1
             and l.count(" | S:") == 1 for l in entries)
    keys = ["球心必须恒等于触点", "完整不透明", "不得往链路加判据", "全部历史版本多条目",
            "WRAP_CONTENT", "第四选项卡", "绝不记读到的正文", "固定签名"]
    return (ok and len(entries) >= 50 and all(k in c for k in keys)), \
        ("entries=%d keys=%s" % (len(entries), [k for k in keys if k not in c]))


@case("CI 自动产物不进受管范围")
def c_scope():
    cfg = json.loads(rd(".aoci/config.json"))
    cur = json.loads(rd(".aoci/curation.json"))
    ex = set(cfg.get("curation_exclude", [])) | set(cur.get("excluded", []))
    need = ["docs/feed.atom", "docs/feed.json", "docs/subscribe.atom",
            "docs/subscribe.json", "CHANGELOG.md", "build.log"]
    c = rd("aoci.code.txt")
    return (all(n in ex for n in need)
            and "/apk/" not in c
            and "docs/feed.atom[" not in c), ""


@case("静态资产与生成器一致（禁止手写绕过生成）")
def c_generated():
    r = subprocess.call([sys.executable, os.path.join(ROOT, "tools", "aoci_cognition.py"),
                         "--check"], cwd=ROOT)
    return r == 0, ""


@case("Baseline 与受管源码逐字节对齐")
def c_baseline():
    r = subprocess.call([sys.executable, os.path.join(ROOT, "tools", "aoci_sync.py"),
                         "--check"], cwd=ROOT)
    return r == 0, ""


@case("认知资产不进 APK：不在 app/src/main 里，也不被构建链复制")
def c_not_in_apk():
    b = rd("build_apk.sh")
    main = os.path.join(ROOT, "app", "src", "main")
    bad = []
    for root, dirs, files in os.walk(main):
        for f in files:
            if f in ("aoci.txt", "aoci.meta.txt", "aoci.code.txt", "AGENTS.md",
                     "baseline.json", "config.json"):
                bad.append(os.path.join(root, f))
    return (not bad and 'cp -r "$ROOT/app/src/main/."' in b
            and "aoci.txt" not in b), bad


@case("AGENTS.md 含官方托管区块与本项目红线")
def c_agents():
    a = rd("AGENTS.md")
    return ("<!-- aoci:begin -->" in a and "<!-- aoci:end -->" in a
            and "球心 = 触点" in a and "禁止再跑" in a
            and "aoci_overview" in a), ""


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
        print("===== 第 %d 遍 =====" % (i + 1))
        total += run_once()
    if total:
        raise SystemExit("AOCI 门禁失败 %d 项" % total)
    print("AOCI 认知层自检两遍全部通过 ✅")


if __name__ == "__main__":
    main()
