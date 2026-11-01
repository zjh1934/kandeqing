#!/usr/bin/env python3
"""
补上 verify_structure.py 没盖住的一类编译错误：**不带点号的方法调用**。

verify_structure 只查 `Haptics.error()` 这种「类名.方法」的形式，
对 `toast(...)`、`abortPendingGrab(...)`、`isPassword(...)` 这种直接调用
是完全不看的。而环境里没有 javac，拼错一个方法名只有等打包时才炸 ——
对不会写代码的人来说，那是一条走不通的排查路。

所以这条检查的规则很简单：

    源码里出现的每个「无点号前缀的调用名」，
    要么在本工程里被声明成方法，要么在修改前的基线版本里就已经出现过。

前者挡「新写的调用拼错了」，后者放过「本来就有的框架调用」
（findViewById、getSystemService 这些继承自 Activity / Service 的，
源码里当然找不到声明）。

抓不到 arity（参数个数）和类型错误 —— 那得真编译器。
但「方法名根本不存在」这一类，也就是改代码最容易犯的，基本能挡住。

依赖 git 做基线对比。不在 git 仓库里就整条跳过并提示，绝不误报。
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC_DIRS = [
    os.path.join(ROOT, "app", "src", "main", "java"),
    os.path.join(ROOT, "app", "src", "test", "java"),
]

# 改代码之前的那个 commit —— 基线只用来「放过本来就存在的调用」
BASELINE = "ebb50db"

sys.path.insert(0, ROOT)
try:
    from verify_structure import strip_literals
except Exception:
    strip_literals = None

# 长得像调用但不是调用
NOT_CALLS = {
    "if", "for", "while", "switch", "catch", "synchronized", "return",
    "new", "else", "do", "try", "finally", "throw", "throws", "case",
    "super", "this", "instanceof", "assert", "default", "package",
    "import", "class", "interface", "enum", "extends", "implements",
    "void", "int", "long", "boolean", "byte", "char", "float", "double",
    "short",
}

# 方法声明：名字 + 参数表 + 方法体（接口/抽象方法没有体，这里不收，
# 反正调用方也不会凭空多出来）
DECL_RE = re.compile(
    r"[\w\[\]<>.,\s]+?\s(\w+)\s*\([^;{}()]*\)\s*(?:throws\s+[\w.,\s]+)?\{")

# 无点号前缀的调用：前面不能是 . $ 或单词字符
CALL_RE = re.compile(r"(?<![.\w$])(\w+)\s*\(")


def java_files():
    out = []
    for d in SRC_DIRS:
        if not os.path.isdir(d):
            continue
        for dirpath, _dirs, files in os.walk(d):
            for name in sorted(files):
                if name.endswith(".java"):
                    out.append(os.path.join(dirpath, name))
    return sorted(out)


def strip(src):
    """去掉注释和字符串，避免把注释里的中文当代码"""
    if strip_literals is None:
        src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
        src = re.sub(r"//[^\n]*", " ", src)
        return src
    try:
        return strip_literals(src)
    except Exception:
        return src


def declared_names(src):
    return set(DECL_RE.findall(strip(src)))


def called_names(src):
    s = strip(src)
    out = set()
    for m in CALL_RE.finditer(s):
        name = m.group(1)
        if name in NOT_CALLS:
            continue
        # new Runnable() 这种前面紧跟 new
        head = s[max(0, m.start() - 4):m.start()].strip()
        if head.endswith("new"):
            continue
        out.add(name)
    return out


def baseline_of(rel):
    """拿基线版本的内容；拿不到返回 None"""
    try:
        r = subprocess.run(["git", "show", "%s:%s" % (BASELINE, rel)],
                           cwd=ROOT, capture_output=True, text=True)
        if r.returncode == 0 and r.stdout:
            return r.stdout
    except Exception:
        pass
    return None


def main():
    files = java_files()
    if not files:
        print("PASS - 没找到 java 文件")
        return 0

    # 全工程声明过的方法名（含本次新加的）
    all_declared = set()
    for f in files:
        with open(f, "r", encoding="utf-8") as fh:
            all_declared |= declared_names(fh.read())

    git_ok = baseline_of("settings.gradle") is not None
    if not git_ok:
        print("⚠️  拿不到 git 基线（%s），跳过本项检查" % BASELINE)
        print("PASS - 调用名检查（已跳过）")
        return 0

    errs = []
    for f in files:
        rel = os.path.relpath(f, ROOT).replace(os.sep, "/")
        with open(f, "r", encoding="utf-8") as fh:
            cur = fh.read()
        base = baseline_of(rel) or ""
        old_calls = called_names(base)
        for name in sorted(called_names(cur)):
            if name in all_declared or name in old_calls:
                continue
            errs.append("%s: 调用了 %s()，但工程里没有这个方法，"
                        "基线里也没出现过" % (os.path.basename(f), name))

    if errs:
        print("FAIL - 有 %d 处可疑调用：" % len(errs))
        for e in errs:
            print("   " + e)
        print("   多半是方法名拼错了，或者漏写了方法。")
        return 1

    print("PASS - 所有无点号调用都能解析到方法（或基线里本就有）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
