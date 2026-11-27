# AOCI 认知层 · 看得清

[AOCI-CODE](https://github.com/aoci-spec/aoci-code) 是一套本地 CLI + MCP Server，
为 Codex / Claude Code / Cursor / OpenCode 等编码 Agent 建立并维护**仓库级认知索引**。
它把「每个文件负责什么、改它时还要看谁、对外提供什么契约、有哪些推不出的硬约束」
写成随仓库走的纯文本，由 Git 管理，可 diff、可审阅、可回滚。

**关键定位**：这些文件**不进 APK、不编译、不是源码**，但**管着源码**。
`app/src/` 是跑的东西；`aoci.*` / `AGENTS.md` / `.aoci/` 是告诉下一次接手的
Agent「这个系统是什么、哪些线不能踩」。二者一起提交，才是「受管」。

## 资产清单

| 文件 | 角色 |
|---|---|
| `aoci.txt` | Root：项目身份、顶层不可破原则、Volume 声明 |
| `aoci.meta.txt` | Meta：标签字典、FRAS 规则、S 字段纪律与配额 |
| `aoci.code.txt` | Code 卷：每个受管文件一条 FRAS Entry |
| `AGENTS.md` | 任何 Agent 进仓库先读；含官方 AOCI 托管区块 + 本项目红线 |
| `.aoci/baseline.json` | 受管文件的 SHA-256 基线，用来检测代码与索引漂移 |
| `.aoci/config.json` | 团队策略：受管范围、排除规则、语言、审批姿态 |
| `.aoci/curation.json` | 明确排除的 CI 产物清单 |
| `.aoci/.gitignore` | 白名单：只有上面三份 json 与自身入库，运行时状态留本地 |
| `.gitattributes` | 强制 LF，保证 Baseline 的字节在任何平台一致 |

`aoci.txt` + `aoci.meta.txt` + `aoci.code.txt` 合起来是 **Whole-Index**。
每条 Entry 形如：

```
BallArt.java[KU9S]: F:绘制悬浮球 | R:code:.../BallView.java | A:- | S:alpha=1 时内圈必须完整不透明
```

- **F** 一个核心职责命题
- **R** 改它时必须一并关注的对象（精确 `code:` 相对路径）
- **A** 对外稳定契约
- **S** 高熵系统约束：只有「从名字/标签/F/R/A 推不出」**且**「删了会让模型改错代码」
  两句都成立才写。本项目把踩过的坑都放在这里 —— 球心必须恒等触点、
  不透明度 100% 必须真不透明、抓字链路只做减法、订阅源不许覆盖成单条目……

## 受管边界

**受管**（写 Entry + 进 Baseline）：Java 源码、Manifest 与 res、gradle、
`build_apk.sh`、CI workflow、自检脚本、`tools/*.py`、README / 落地页 / 本页、
AOCI 自身资产、`AGENTS.md`。

**不进受管范围**（CI 每次发版自动改写，纳入只会造成永久漂移）：
`apk/**`、`docs/feed.atom|json`、`docs/subscribe.atom|json`、
`docs/kandeqing-latest.apk`、`docs/preview/**`、`CHANGELOG.md`、`build.log`、
`__pycache__/`、`.toolchain/`。

## 怎么维护

```bash
# 换新机器 / 换 Agent：只需补机器侧 MCP 配置，禁止 scan
aoci --repo . init --agent codex          # 或 claude-code / cursor / opencode

# 每次任务开始
#   宿主里先 aoci_rules，再 aoci_overview 拿完整索引

# 改完受管文件、收尾前
python3 tools/aoci_sync.py --refresh       # 重建 SHA-256 Baseline
python3 tools/aoci_cognition.py            # 若 F/R/A/S 语义也要改
python3 verify_all.py                      # 10 套含 AOCI 与页面门禁，三轮全绿
aoci --repo . verify && aoci --repo . check

# 只检查不写
python3 tools/aoci_sync.py --check
```

`tools/aoci_sync.py` 是官方 CLI 缺席时的等价漂移门禁：它按同一份
`.aoci/config.json` 算每个受管文件的 SHA-256，比对 Baseline，不一致就报错。
`verify_all.py` 每轮都会跑它，所以**改了源码却忘了更新认知，提交前就会被挡住** ——
这就是「受管」落到可执行层面的意思。

## 如实说明

本仓库的静态资产按官方 `cognition-volumes/v1` + `object-fras/v2` 契约撰写，
Baseline 按源码原始字节的 SHA-256 生成；首次生成时沙盒无法下载官方二进制
（GitHub Release 下载域 403，且无 Go 工具链），因此**没有跑过官方 `init` / `scan`**。
在装有官方 CLI 的机器上执行上面的 `init --agent` 后，用 `verify` / `check`
即可接管；若报告漂移，按 Guide 用 `aoci_maintain` 增量对齐即可。

绿色只证明**结构与哈希对齐**，不证明 F/R/A/S 语义正确 —— 语义仍需对照源码
人工审阅，这也是 AOCI 自己的纪律。
