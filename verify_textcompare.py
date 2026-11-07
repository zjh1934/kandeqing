
def same_line(a, b):
    if a is None or b is None:
        return False
    return a.strip() == b.strip()

def run_once():
    cases = [
        ("你好世界", "你好世界", True),
        ("  你好世界  ", "你好世界", True),
        ("你好世界", "世界", False),
        ("世界", "你好世界", False),
        ("你好世界", "你好地球", False),
        ("一段长文本", "文本", False),
        (None, "x", False),
        ("x", None, False),
        (None, None, False),
    ]
    failed = 0
    for a, b, expect in cases:
        got = same_line(a, b)
        ok = (got == expect)
        failed += 0 if ok else 1
        print(("PASS" if ok else "FAIL"),
              "sameLine(%r, %r) = %s (expect %s)" % (a, b, got, expect))
    return failed

def main():
    total_failed = 0
    for i in range(1, 3):
        print("===== 第 %d 遍测试 =====" % i)
        total_failed += run_once()
        print("本遍通过 ✅" if total_failed == 0 else "")
    if total_failed:
        raise SystemExit("累计有 %d 个用例失败" % total_failed)
    print("两遍全部通过 ✅")

if __name__ == "__main__":
    main()
