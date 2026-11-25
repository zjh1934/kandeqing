#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AOCI Baseline：以源码原始字节的 SHA-256 维护受管范围并检测索引漂移。

官方 CLI 缺席时的等价治理门禁。受管文件 = aoci.code.txt 里有 Entry 的文件，
CI 自动产物由 .aoci/config.json 的 curation_exclude 排除，不进 Baseline。

用法：
    python3 tools/aoci_sync.py --check      # 只比对，不写
    python3 tools/aoci_sync.py --refresh    # 重建 .aoci/baseline.json
"""
import datetime
import hashlib
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE = os.path.join(ROOT, ".aoci", "baseline.json")
CONFIG = os.path.join(ROOT, ".aoci", "config.json")
CODE = os.path.join(ROOT, "aoci.code.txt")

SECTION = re.compile(r"^===(/[^=]*?)===\s*$")
ENTRY = re.compile(r"^([^\[\]#\s][^\[\]]*)\[([A-Z0-9]+)\]:\s*F:")


def managed_paths():
    """从 Code 卷解析出受管文件的仓库相对路径。"""
    out = []
    cur = ""
    for line in io.open(CODE, encoding="utf-8"):
        line = line.rstrip("\n")
        m = SECTION.match(line)
        if m:
            p = m.group(1)
            assert p.startswith("/<repository>/"), p
            cur = p[len("/<repository>/"):]
            continue
        m = ENTRY.match(line)
        if m and cur is not None:
            name = m.group(1).strip()
            out.append((cur + name) if cur else name)
    return sorted(out)


def digest(path):
    raw = open(path, "rb").read()
    normalized = raw.replace(b"\r\n", b"\n")
    return {
        "role": "index",
        "sha256": hashlib.sha256(raw).hexdigest(),
        "size": len(raw),
        "normalized_sha256": hashlib.sha256(normalized).hexdigest(),
    }


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def excluded():
    cfg = json.load(io.open(CONFIG, encoding="utf-8"))
    return set(cfg.get("curation_exclude", []))


def main():
    refresh = "--refresh" in sys.argv
    paths = managed_paths()
    ex = excluded()
    bad_ex = [p for p in paths if p in ex]
    if bad_ex:
        print("受管范围错误：CI 产物被写进了 Entry ->", bad_ex)
        return 1

    current = {}
    missing = []
    for p in paths:
        full = os.path.join(ROOT, p)
        if not os.path.exists(full):
            missing.append(p)
        else:
            current[p] = digest(full)
    if missing:
        print("Entry 指向不存在的文件 ->", missing)
        return 1

    if refresh:
        old = {}
        if os.path.exists(BASELINE):
            old = json.load(io.open(BASELINE, encoding="utf-8"))
        obj = {
            "version": 1,
            "created_at": old.get("created_at", now()),
            "updated_at": now(),
            "files": current,
        }
        os.makedirs(os.path.dirname(BASELINE), exist_ok=True)
        io.open(BASELINE, "w", encoding="utf-8", newline="\n").write(
            json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        print("Baseline 已刷新：%d 个受管文件" % len(current))
        return 0

    if not os.path.exists(BASELINE):
        print("缺少 .aoci/baseline.json，先跑 --refresh")
        return 1
    saved = json.load(io.open(BASELINE, encoding="utf-8")).get("files", {})
    stale, absent, extra = [], [], []
    for p, d in current.items():
        if p not in saved:
            absent.append(p)
        elif saved[p].get("sha256") != d["sha256"] or saved[p].get("size") != d["size"]:
            stale.append(p)
    for p in saved:
        if p not in current:
            extra.append(p)
    if stale or absent or extra:
        print("Baseline 漂移：")
        if stale:
            print("  内容变了 ->", stale)
        if absent:
            print("  新增未登记 ->", absent)
        if extra:
            print("  Entry 已移除 ->", extra)
        print("  改完源码后执行：python3 tools/aoci_sync.py --refresh")
        return 1
    print("AOCI Baseline 对齐：%d 个受管文件逐字节一致" % len(current))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
