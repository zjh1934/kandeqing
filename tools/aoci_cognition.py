#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""看得清 AOCI 认知资产生成器。

按 aoci-spec/aoci-code 的 cognition-volumes/v1 + object-fras/v2 契约，
输出随仓库走、由 Git 管理的正式资产。F/R/A/S 由本项目维护者依据源码撰写，
不按文件名、路径或 AST 机械推导。CI 自动产物不进受管范围。

用法：
    python3 tools/aoci_cognition.py            # 生成全部静态资产
    python3 tools/aoci_cognition.py --check    # 只校验，不写文件
"""
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# CI / 构建自动改写，不进受管范围（不写 Entry、不进 Baseline）
EXCLUDE_DIRS = ["apk", "docs/preview", "__pycache__", ".toolchain",
                ".git", "build", ".gradle", "app/build"]
EXCLUDE_FILES = ["build.log", "docs/feed.atom", "docs/feed.json",
                 "docs/subscribe.atom", "docs/subscribe.json",
                 "docs/kandeqing-latest.apk", "CHANGELOG.md",
                 ".aoci/baseline.json", ".aoci/config.local.json"]

# path -> (A层, B模块, C重要度, F, R, A, S)
# A: C共享基础 E入口边界 A应用编排 D领域逻辑 K算法计算 M中间件 P持久化
#    I集成适配 R运行基础 L库SDK F声明配置 O运维交付 T测试验证 S文档规范 X开发工具
# B: G跨域 U用户交互 B核心业务 D数据状态 I身份权限 N网络协议 M消息事件
#    S安全隐私 C配置策略 O可观测性 R可靠性恢复 P性能资源 W流程调度
#    A分析智能 H硬件设备 L本地化 V构建发布 Q质量保障 E扩展插件
# S 只写「推不出、删了会改错」的约束，不写演进史、版本、日期。
E = {}

E[".gitignore"] = ("F", "V", 5, "划定构建产物与本地工具的 Git 忽略边界",
                   "-", "-", "AOCI 运行时状态必须留在 .aoci/.gitignore 的白名单之外；apk/ 与 docs/preview/ 是受版本管理的产物，不得顺手 ignore 掉")
E[".gitattributes"] = ("F", "V", 5, "强制全部文本以 LF 检出",
                       "-", "-", "Baseline 绑定原始字节；autocrlf 重写会让整棵树被判 stale")
E["AGENTS.md"] = ("S", "G", 9, "任何编码 Agent 进入本仓库时的第一份上下文与红线",
                  "-", "-", "AOCI 托管区块之外还有本项目硬约束；两者冲突时以本文件靠近被改文件的段落为准，且用户当前指令优先")
E["README.md"] = ("S", "V", 8, "给人看的中文入口：双球功能、安装、两类链接、版本徽章",
                  "-", "-", "订阅框收 feed 文档，下载框收 APK 文件直链，两者绝不能混；releases.atom 指向 HTML 网页，会让下载器报 server returned HTML page；首屏口径必须与当前软件一致，发新版由 verify_pages.py 点名漏更的页面；首屏只讲产品与上手，AOCI 与订阅技术段必须后置到「给开发者」之后，别把 README 写成技术手册")
E["README.en.md"] = ("S", "V", 6, "英文入口，与中文 README 口径一致",
                     "code:README.md", "-", "版本号与订阅地址必须与中文 README 同步，否则对外页面自相矛盾")
E["使用说明.md"] = ("S", "U", 5, "给长辈看的装包与无障碍开启步骤",
                    "-", "-", "不得写死具体版本号的安装包文件名；下载页曾写死 3.9.7 导致长辈找不到文件")
E["docs/index.html"] = ("S", "V", 7, "GitHub Pages 落地页：产品示意 Hero、三步上手、数字条、折叠技术细节、收尾 CTA",
                        "-", "-", "当前版本文案与订阅地址必须随发版更新；所有主 CTA 只能指向 raw 固定直链，jsDelivr 同名文件在 CDN 边缘会滞后一版；footer 必须在所有 section 之后，不得留已被取代的旧版本预览节；单文件零外部依赖（不引字体 / CDN CSS / JS），否则弱网访客连文字都看不到；Hero 必须有代码自绘的产品示意 SVG 而非抽象装饰；订阅与直链等技术细节收进 details，不占主视觉；浅色高对比并支持深色 / 高对比 / 减少动效偏好")
E["docs/privacy.md"] = ("S", "S", 6, "隐私说明：本地处理、无网络权限、日志不记正文",
                        "-", "-", "若新增 INTERNET 权限或让日志记下朗读内容，本页与对外承诺同时失效")
E["docs/开发者.md"] = ("S", "S", 5, "技术长文：抓字链路、状态机、发版流程",
                       "-", "-", "历史版本号是事实记录，不是待同步的当前版本")
E["docs/推广素材.md"] = ("S", "V", 3, "站外分发的中英文发帖文案与曝光复盘",
                         "-", "-", "一句话定位与锚文本必须跟当前双球口径一致；曝光诊断表是改版前历史数据，不得当成现状")
E["docs/aoci.md"] = ("S", "S", 7, "说明 AOCI 认知层在本仓库的位置、受管边界与维护方式",
                     "-", "-", "本仓库已带索引；clone 后只运行 init --agent 补机器侧配置，禁止再跑 scan，否则 Baseline 冲突")
E["docs/design.md"] = ("S", "S", 8, "记录主页参考了哪些优秀开源项目、信息架构决策与可访问性基线",
                       "code:docs/index.html,code:verify_pages.py", "-",
                       "改页面必须同步改本文与 verify_pages 断言；设计约束同样写进本卷 S 字段，绿色只证明结构对齐不证明好看，视觉改动仍要人眼在桌面和手机各看一遍")
E["docs/repo-meta.json"] = ("F", "V", 8, "声明仓库 description / homepage / topics，推送脚本据此同步 GitHub 元数据",
                            "code:README.md", "-", "description 是 GitHub 首页第一眼看到的文字，必须与当前双球口径一致；topics 覆盖剪贴板与 AOCI，改动后走推送脚本同步 API，不能只改本地文件")
E["CODE_OF_CONDUCT.md"] = ("S", "G", 2, "社区行为约定", "-", "-", "-")
E["CONTRIBUTING.md"] = ("S", "G", 3, "贡献与验证要求", "-", "-", "-")
E["SECURITY.md"] = ("S", "S", 4, "漏洞报告口径、官方下载渠道、签名指纹与版本支持表",
                     "-", "-", "Release 资产名已统一 kandeqing-v<版本>.apk，写回 kanqing-<版本>.apk 是失效信息；固定签名指纹必须与 CI 恢复的密钥一致")
E["LICENSE"] = ("S", "G", 4, "MIT 许可", "-", "-", "-")
E["settings.gradle"] = ("F", "V", 3, "单模块工程声明", "-", "-", "-")
E["build.gradle"] = ("F", "V", 3, "顶层仓库声明", "-", "-", "真编译不走 gradle；build_apk.sh 用 aapt2 + d8 手工链")
E["app/build.gradle"] = ("F", "V", 8, "声明 versionCode / versionName / minSdk / 签名配置",
                         "code:build_apk.sh", "-", "versionCode 必须单调递增且发版时与 workflow NOTES、README 徽章、落地页当前版本四处对齐；签名别名与密钥来源不可改")
E["build_apk.sh"] = ("F", "V", 9, "aapt2 + javac + d8 + zipalign + apksigner 手工构建链",
                     "code:app/build.gradle,code:.github/workflows/build-apk.yml", "bash build_apk.sh",
                     "输出名固定 apk/kandeqing-v<版本>.apk；固定签名来自 secrets 恢复的 .toolchain/debug.keystore，换成临时密钥会导致用户无法覆盖安装")
E[".github/workflows/build-apk.yml"] = ("O", "V", 9, "CI 真编译、回传安装包、发 Release、生成订阅源、清 CDN 缓存",
                                        "code:build_apk.sh,code:tools/make_feed.py", "push main / workflow_dispatch",
                                        "每次发版必须同时上传 kandeqing-v<版本>.apk 与固定名 kandeqing-latest.apk；NOTES 文案随版本更新；发版后清 jsDelivr 缓存；不得改固定签名来源；[skip ci] 提交避免自触发")
E[".github/PULL_REQUEST_TEMPLATE.md"] = ("S", "G", 2, "PR 模板", "-", "-", "-")
E[".github/ISSUE_TEMPLATE/config.yml"] = ("F", "G", 2, "Issue 模板入口", "-", "-", "config.yml 里的 #常见问题 是 URL 锚点，不是注释")
E[".github/ISSUE_TEMPLATE/bug_report.md"] = ("S", "G", 2, "缺陷报告模板", "-", "-", "-")
E[".github/ISSUE_TEMPLATE/feature_request.md"] = ("S", "G", 2, "功能建议模板", "-", "-", "-")

E["tools/make_feed.py"] = ("X", "V", 9, "扫描全部历史安装包生成 Atom / JSON Feed 订阅源",
                           "code:.github/workflows/build-apk.yml,code:docs/aoci.md", "python3 tools/make_feed.py apk docs --repo <owner/repo>",
                           "订阅源必须是全部历史版本多条目，严禁每次发版覆盖成单条目；每条 enclosure/attachments 必须直指该版本 APK 文件，不得指向 Release 网页；kandeqing-latest.apk 只作永久别名不单列条目；标题与附件名都带 kandeqing-v<版本>.apk")
E["tools/aoci_cognition.py"] = ("X", "G", 8, "生成 AOCI 正式认知资产（Root/Meta/Code/AGENTS/config）",
                                "code:tools/aoci_sync.py,code:verify_aoci.py", "python3 tools/aoci_cognition.py [--check]",
                                "F/R/A/S 语义只能依据源码人工撰写；不得用文件名、AST、依赖扫描或模板机械生成；受管范围排除 CI 自动改写的文件")
E["tools/aoci_agents_block.txt"] = ("S", "G", 7, "AOCI 官方 AGENTS.md 托管区块原文",
                                    "code:AGENTS.md", "-",
                                    "这段由官方模板渲染，不得手写改写；AGENTS.md 中只有本区块之外的内容属于本项目")
E["tools/aoci_sync.py"] = ("X", "G", 9, "以源码 SHA-256 维护 AOCI Baseline 并检测索引漂移",
                           "code:tools/aoci_cognition.py,code:verify_aoci.py", "python3 tools/aoci_sync.py --check | --refresh",
                           "改动任何受管文件后必须 --refresh 再提交，否则索引与代码漂移；Baseline 绑定原始字节，不得用规范化后的内容代替")
E["verify_pages.py"] = ("T", "Q", 9, "自检全部对外页面是否与当前软件版本一致",
                        "code:verify_all.py,code:tools/aoci_sync.py", "-",
                        "发新版只改 build.gradle 的版本号，这里必须点名漏更的页面；禁止出现旧首屏「一个悬浮球 / One floating ball」、旧资产名 kanqing-、3.9.21 过时预览节；落地页主下载按钮必须是 raw 无缓存直链")
E["verify_aoci.py"] = ("T", "Q", 8, "自检 AOCI 资产完整性、Entry 覆盖与 Baseline 对齐",
                       "code:tools/aoci_sync.py,code:verify_all.py", "-",
                       "绿只证明结构与哈希对齐，不证明 F/R/A/S 语义正确；语义仍需对照源码人工审阅")
E["verify_all.py"] = ("T", "Q", 9, "提交前总自检入口，跑三遍全部 suite",
                      "code:verify_aoci.py", "python3 verify_all.py",
                      "沙盒没有 JDK / Android SDK，静态自检绿不等于编译过、更不等于真机好用；真编译只能靠 CI")
for n, desc in [
    ("verify_structure.py", "结构与跨文件符号解析"),
    ("verify_textlayout.py", "阅读顺序与断句纯逻辑镜像"),
    ("verify_grabber.py", "抓字全流程镜像"),
    ("verify_speak.py", "长文本切段与 4000 字上限"),
    ("verify_calls.py", "无点号方法调用解析"),
    ("verify_ball.py", "球定位数值模拟与四态形态断言"),
    ("verify_clipboard.py", "双球、无双击、焦点读取与隐私"),
    ("verify_ui.py", "配色、透明度、层级与拼音命名断言"),
    ("verify_textcompare.py", "文本比较镜像"),
]:
    E[n] = ("T", "Q", 6, desc, "-", "-",
            "断言绑死当前实现；改实现必须同步改断言，不能靠删断言让自检变绿")

E["promo/make_qr.py"] = ("X", "V", 3, "生成下载页二维码", "-", "-", "二维码指向固定链接，不得写死某一版文件名")
E["promo/下载页.html"] = ("S", "V", 4, "离线下载页", "-", "-", "不得写死具体版本的 APK 文件名")

J = "app/src/main/java/com/floating/combo/"
E[J + "MainActivity.java"] = ("E", "U", 9, "单 Activity 设置界面：四个选项卡、开关、滑块、下拉、诊断日志",
                               "code:" + J + "Ui.java,code:" + J + "Prefs.java,code:" + J + "TtsManager.java", "-",
                               "诊断与日志折叠入口必须在第四选项卡 pageMore；音色列表为空时不得静默 return；onResume 且引擎 ready 时重新枚举音色；sliderRow/SelectRow 的可见性依赖 Ui 的 WRAP_CONTENT，改布局时先验证控件还能不能点")
E[J + "ComboService.java"] = ("A", "B", 9, "无障碍服务主体：状态机、两球、抓字调度、剪贴板朗读",
                              "code:" + J + "BallController.java,code:" + J + "ClipReader.java,code:" + J + "TextGrabber.java", "-",
                              "两个悬浮球彼此独立、可各自拖动；自动读只在待命态触发，不打断放大/选字/朗读；抓字失败不二次全树遍历；日志只记状态与字数，绝不记读到的正文")
E[J + "BallController.java"] = ("D", "U", 9, "悬浮球窗口定位：闲置拖动、放大态跟手、贴边",
                                "code:" + J + "BallView.java,code:" + J + "ZoomController.java", "-",
                                "放大态球心必须恒等于触点，唯一写法是 lp = 触点 − 半宽；禁止 getLocationOnScreen 测原点差、raw−local 反推、相对偏移或保持按下偏移等任何补偿方案；闲置拖球必须保持按下偏移，不得一起改")
E[J + "BallArt.java"] = ("K", "U", 9, "绘制球的全部形态：实心、空心环、两竖线、等待转圈",
                         "code:" + J + "BallView.java,code:" + J + "Prefs.java", "-",
                         "alpha=1 时 0~0.80r 必须完整不透明，只有最外 20% 做短柔边；禁止退回中心 0.90 峰值的径向渐变，那样不透明度调到 100% 仍半透明；朗读竖线与等待点必须球色填充加深色描边，否则浅壁纸上消失")
E[J + "BallView.java"] = ("D", "U", 7, "球的 View 壳：颜色、alpha、状态、绘制缩放",
                          "code:" + J + "BallArt.java", "setBallColor / setBallAlpha / setState",
                          "选字、朗读、等待态会把有效 alpha 抬到至少 0.94，不是普通 alpha 直通")
E[J + "BallPreview.java"] = ("D", "U", 4, "设置页球外观预览", "code:" + J + "BallArt.java", "-", "-")
E[J + "ClipBallController.java"] = ("D", "U", 7, "第二个球（复制朗读）的窗口定位与点击",
                                    "code:" + J + "ClipBallView.java,code:" + J + "ClipReader.java", "-",
                                    "与白球共用服务但位置独立保存；朗读中点它停止，否则读剪贴板；不得接双击手势")
E[J + "ClipBallView.java"] = ("D", "U", 6, "第二球绘制：亮绿实心、白边、白色喇叭",
                              "code:" + J + "ClipBallController.java", "-", "绿球必须高饱和并带白描边，深色壁纸上才找得到")
E[J + "ClipReader.java"] = ("I", "S", 9, "剪贴板读取：变化监听、复制事件、三级焦点兜底、文本过滤",
                            "code:" + J + "ComboService.java", "-",
                            "主通道是 addPrimaryClipChangedListener，复制动作事件只是第二通道；只认「复制」提示会漏掉长按菜单、WebView 与分享菜单；Android 10+ 后台读剪贴板受限，自动读是 best-effort，点绿球最稳；会跳过验证码与过短文本")
E[J + "TextGrabber.java"] = ("K", "B", 9, "无障碍树取字：单趟后序、命中窗口、逐字位置、句读回扫",
                             "code:" + J + "TextLayout.java,code:" + J + "TextCompare.java", "-",
                             "骨架是球球版：单趟后序收集、第一个命中窗口即返回、逐字位置只问命中段；MAX_SEG 只截收录不截遍历；不得往链路加判据、分支或二次全树兜底；密码保护整套已拔除，不得恢复")
E[J + "TextLayout.java"] = ("K", "B", 7, "按 y 落行、断句、拼接阅读顺序",
                            "code:" + J + "TextGrabber.java", "-", "正文逐字不改；读多少三档在拼接阶段生效")
E[J + "TextCompare.java"] = ("K", "B", 3, "文本比较辅助", "-", "-", "-")
E[J + "TtsManager.java"] = ("I", "R", 9, "TTS 引擎枚举、音色、切段、朗读回调",
                            "code:" + J + "MainActivity.java", "-",
                            "切换引擎后必须无条件刷新音色列表；保存的 voice 在新引擎不存在时会静默退回中文；朗读状态机的回调时序是显式取舍，不要顺手重构")
E[J + "Prefs.java"] = ("C", "C", 9, "SharedPreferences 读写与全部默认值、七色表",
                       "code:" + J + "MainActivity.java,code:" + J + "BallArt.java", "-",
                       "七色必须是高饱和明朗色板，不得退回低饱和清釉；DEF_ALPHA 与 DEF_IDLE_ALPHA 为 1.0；密码保护键已删，不得恢复")
E[J + "Theme.java"] = ("C", "U", 7, "深浅两套 UI 配色与行高、圆角、命中区常量",
                       "code:" + J + "Ui.java", "-", "强调色跟随球色；压行高曾把滑块与下拉裁没，改常量前先确认控件外层是 WRAP_CONTENT")
E[J + "Ui.java"] = ("L", "U", 9, "纯代码 UI 控件工厂：group / sliderRow / selectRow / switchRow 等",
                    "code:" + J + "Theme.java,code:" + J + "MainActivity.java", "-",
                    "sliderRow 与 SelectRow 主行必须 WRAP_CONTENT + setMinimumHeight，SeekBar 至少 36dp；改回固定行高会把控件裁到点不动；界面中文字符串必须原样保留")
E[J + "ZoomController.java"] = ("D", "U", 7, "放大覆盖层与放大中心计算",
                                "code:" + J + "BallController.java", "-",
                                "放大中心取 getRealMetrics 的物理显示区；球的窗口位置由 BallController 负责，两套坐标不要混用")
E[J + "ZoomPreview.java"] = ("D", "U", 3, "放大倍率预览", "-", "-", "-")
E[J + "Log.java"] = ("O", "O", 7, "诊断日志落盘、最近条目、导出与清空",
                     "code:" + J + "MainActivity.java", "-", "只记手势、状态、错误和字数，绝不记读到的正文；recent/content/export/clear 被第四选项卡日志区调用")
E[J + "Haptics.java"] = ("I", "H", 4, "震动反馈封装", "-", "-", "-")

E["app/src/main/AndroidManifest.xml"] = ("F", "S", 9, "权限、Activity、无障碍服务声明",
                                          "code:app/src/main/res/xml/accessibility_service_config.xml", "-",
                                          "不得新增 INTERNET 权限；整个对外承诺建立在零网络权限上")
E["app/src/main/res/xml/accessibility_service_config.xml"] = ("F", "I", 8, "无障碍服务能力声明",
                                                               "code:app/src/main/AndroidManifest.xml", "-", "canRetrieveWindowContent 必须保留，否则无障碍树取不到字")
E["app/src/main/res/values/strings.xml"] = ("F", "C", 7, "app 名、无障碍描述、分享下载链接",
                                             "-", "-", "share_download_url 必须指向当前仓库，旧地址会让一键分享发出 404")
E["app/src/main/res/values/colors.xml"] = ("F", "C", 3, "资源色值", "-", "-", "-")
E["app/src/main/res/values/styles.xml"] = ("F", "C", 4, "深浅主题样式", "-", "-", "-")
for f in ["app/src/main/res/drawable/ic_launcher.xml",
          "app/src/main/res/drawable/ic_launcher_background.xml",
          "app/src/main/res/drawable/ic_launcher_foreground.xml",
          "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"]:
    E[f] = ("F", "C", 2, "启动图标资源", "-", "-", "-")
E["app/src/test/java/com/floating/combo/TextCompareTest.java"] = ("T", "Q", 3, "文本比较用例",
                                                                   "-", "-", "-")

ROOT_MANIFEST = """#AOCI-ROOT-MANIFEST: 1
#Format-Version: cognition-volumes/v1
#Locale: zh-CN
#Project: 看得清 KanDeQing — 单 Activity 的 Android 无障碍读屏工具：按住悬浮球放大、点一下把屏幕上的字念给你听、第二个球读剪贴板；零网络权限、不用 OCR、纯代码 UI、手工 aapt2+d8 构建链
#Global-Invariants: 零 INTERNET 权限且日志不记朗读正文 | 放大态球心恒等于触点 | 剪贴板自动读在 Android 10+ 仅 best-effort，点绿球最稳 | 安装包固定签名可覆盖升级，文件名 kandeqing-v<版本>.apk | 订阅源是全部历史版本多条目且附件直指 APK 文件 | 预览图一律代码自绘不用生图模型 | 认知资产随 Git 受管，CI 自动产物不进受管范围
#Volume: id=meta kind=meta path=aoci.meta.txt format=meta-v1 depends=- state=enabled
#Volume: id=code kind=code path=aoci.code.txt format=object-fras-v2 depends=meta state=enabled
"""

META_VOLUME = """#AOCI-META-VOLUME: 1
#Object-Protocol: repository-cognition-object/v2
#FRAS-Discipline: 2
#FRAS-v2-Limits-Authority: machine-contract
#S-Admission: non-inferable-and-error-preventing
#S配额: C9-8≤600 C7-4≤200 C3-1≤50
#Object-Kinds: code=file
#Canonical-Tag-Authoring: compact A+B+C+[D]+E; 点分形态仅用于读取兼容
#Code canonical identity example: code:app/src/main/java/com/floating/combo/BallArt.java
#段根: /<repository> 代表存放本索引的那份检出的仓库根，不是真实路径；===/<repository>/=== 开启根目录，===/<repository>/<dir>/=== 开启相对仓库根的目录 <dir>
#Code Entry example: BallArt.java[KU9S]: F:绘制悬浮球 | R:code:app/src/main/java/com/floating/combo/BallView.java | A:- | S:alpha=1 时内圈必须完整不透明
#[Tag dictionary: code]
#A Layer: C-共享基础 E-入口边界 A-应用编排 D-领域逻辑 K-算法计算 M-中间件 P-持久化 I-集成适配 R-运行基础 L-库与SDK F-声明配置 O-运维交付 T-测试验证 S-文档规范 X-开发工具 Z-其他
#B Module: G-跨域通用 U-用户交互 B-核心业务 D-数据状态 I-身份权限 N-网络协议 M-消息事件 S-安全隐私 C-配置策略 O-可观测性 R-可靠性恢复 P-性能资源 W-流程调度 A-分析智能 H-硬件设备 L-本地化 V-构建发布 Q-质量保障 E-扩展插件 Z-其他
#C Importance: 9-最高 8-很高 7-高 6-较高 5-中等 4-较低 3-低 2-很低 1-最低
#E Scale: L-大>400 M-中200-400 S-小100-200 T-微<100
"""

GITATTR = """# AOCI Baseline 绑定原始字节，全树必须以 LF 检出，
# 否则 Windows 的 autocrlf 重写会让整棵树被判为 stale。
* text=auto eol=lf
"""

AOCI_GITIGNORE = """# AOCI .aoci 资产边界
# 本目录未知文件一律视为当前机器的运行时产物，不进入 Git。
*

!.gitignore
!baseline.json
!config.json
!curation.json
"""

CONFIG = {
    "version": 2,
    "locale": "zh-CN",
    "index_path": "aoci.txt",
    "exclude_dirs": EXCLUDE_DIRS,
    "exclude_files": ["*.apk", "*.png", "*.jpg", "*.jpeg", "*.pyc",
                      "build.log", "*.bak", "*.backup.*"],
    "exclude_exts": [],
    "curation_exclude": EXCLUDE_FILES,
    "hook_strict": False,
    "ledger_enabled": True,
    "line_ending_tolerance": False,
    "cognition_refresh_threshold": 30,
    "overview_delivery": {"chunk_tokens": 14000},
    "managed_scope": {
        "version": "managed-scope-policy/v2",
        "profile": "production",
        "observe_change_policy": "review_required",
        "approval_mode": "review",
        "approval_thresholds": {"entry_removal_count": 25,
                                "entry_removal_percent": 25},
        "rules": [
            {"rule_id": "kandeqing-ci-feeds-exclude", "action": "exclude",
             "pattern": "docs/feed.*", "pattern_kind": "glob",
             "reason": "CI 每次发版重生成，纳入 Baseline 会造成永久漂移",
             "source": "user", "order": 100, "enabled": True},
            {"rule_id": "kandeqing-legacy-feeds-exclude", "action": "exclude",
             "pattern": "docs/subscribe.*", "pattern_kind": "glob",
             "reason": "与 feed.* 同内容的旧路径，同为 CI 产物",
             "source": "user", "order": 101, "enabled": True},
            {"rule_id": "kandeqing-apk-binaries-exclude", "action": "exclude",
             "pattern": "apk/**", "pattern_kind": "glob",
             "reason": "40 个历史安装包是二进制产物，不写语义 Entry",
             "source": "user", "order": 102, "enabled": True},
        ],
    },
    "sensitive_name_rules": "v2",
}

CURATION = {
    "version": 1,
    "excluded": EXCLUDE_FILES,
    "note": "CI 自动改写或大体量历史记录；不写语义 Entry，不进 Baseline 覆盖校验。",
}

GUARDRAILS = """# AGENTS.md

> 这是给编码 Agent 看的项目文件；给人看的入口是 `README.md`。
> 它不编译进 APK，但由 Git 管理，管着 `app/` 里每一行源码该怎么改。

## 开工前先读

1. 接了 AOCI 的宿主：先 `aoci_rules`，再 `aoci_overview`，拿到 `aoci.txt` +
   `aoci.meta.txt` + `aoci.code.txt` 组成的 Whole-Index。
2. 没接 AOCI：至少读本文件 + `aoci.code.txt` 里对应目录段的 S 字段。
   S 字段写的都是「光看代码推不出、删了就会改错」的约束。
3. **本仓库已带索引**：clone 后只需 `aoci --repo . init --agent <host>` 补机器侧
   配置，**禁止再跑 `scan`**（官方明确已有索引的仓库 scan 会拒绝覆盖 Baseline）。

## 命令

- 自检：`python3 verify_all.py`（10 套含 AOCI 与页面门禁，三轮全绿才算过）
- 索引漂移：`python3 tools/aoci_sync.py --check`；改动受管文件后
  `python3 tools/aoci_sync.py --refresh` 再提交
- 重建静态资产：`python3 tools/aoci_cognition.py`
- 真编译：只能在 CI（`.github/workflows/build-apk.yml`）或有 Android SDK 的机器上
  `bash build_apk.sh`；沙盒没有 JDK / SDK，静态自检绿 ≠ 编译过 ≠ 真机好用

## 本项目硬约束（违反过、代价很大）

- **放大态球心 = 触点**。只允许 `lp = 触点 − 半宽`，不许测原点差、
  不许 raw−local 反推、不许保持按下偏移。闲置拖球保持按下偏移，别一起改。
- **不透明度 100% 必须真不透明**。禁止退回中心 0.90 峰值的径向渐变。
- **抓字链路只做减法**。球球版单趟后序骨架不许加判据、加分支、加二次全树兜底；
  密码保护整套已拔除，不许恢复。
- **控件不许被裁**。3.9.21 压行高把滑块和下拉裁到点不动；`sliderRow` /
  `SelectRow` 外层必须 `WRAP_CONTENT` + `setMinimumHeight`。
- **诊断日志在第四选项卡**，早期版本被误删过；日志绝不记朗读正文。
- **订阅源是全部历史版本多条目**，不许覆盖成单条目；`enclosure` 不许指向
  Release 网页；安装包一律 `kandeqing-v<版本>.apk`。
- **零 INTERNET 权限**、固定签名可覆盖升级、预览图一律代码自绘不用生图模型。
- **对外页面不许原地踏步**：改版本、改功能就同步 README 中英 / 落地页 / 使用说明 /
  隐私 / 安全 / 推广素材 / Issue 模板；`verify_pages.py` 会按当前版本逐页点名。
- 界面中文字符串必须原样保留；源码里的解释性注释已清理，不要再加回来。

## 交付边界

- 做 App 只交安装包，源码包不发；仓库页面与文档由 CI / 显式提交维护。
- CI 自动改写的 `apk/`、`docs/feed.*`、`docs/subscribe.*`、`docs/preview/`、
  `CHANGELOG.md` 不进 AOCI 受管范围，不要给它们写 Entry 或塞进 Baseline。

"""


def line_count(path):
    try:
        with open(path, "rb") as fp:
            return sum(1 for _ in fp)
    except OSError:
        return 0


def scale(n):
    return "L" if n > 400 else ("M" if n >= 200 else ("S" if n >= 100 else "T"))


def build_code_volume():
    groups = {}
    for path, (a, b, c, f, r, ac, s) in E.items():
        d = os.path.dirname(path)
        groups.setdefault(d, []).append((os.path.basename(path), a, b, c, f, r, ac, s))
    out = ["#AOCI-CODE-VOLUME: 1", ""]
    for d in sorted(groups, key=lambda x: (x != "", x)):
        header = "/<repository>/" if d == "" else "/<repository>/" + d + "/"
        out.append("===%s===" % header)
        for name, a, b, c, f, r, ac, s in sorted(groups[d]):
            tag = "%s%s%d%s" % (a, b, c, scale(line_count(os.path.join(ROOT, d, name) if d else os.path.join(ROOT, name))))
            out.append("%s[%s]: F:%s | R:%s | A:%s | S:%s" % (name, tag, f, r, ac, s))
        out.append("")
    out.append("#代码索引完毕")
    out.append("")
    return "\n".join(out)


def agents_md():
    tpl = os.path.join(ROOT, "tools", "aoci_agents_block.txt")
    if os.path.exists(tpl):
        block = io.open(tpl, encoding="utf-8").read()
    else:
        raise SystemExit("缺少 tools/aoci_agents_block.txt（官方托管区块）")
    return GUARDRAILS + block


def write(path, text):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    io.open(full, "w", encoding="utf-8", newline="\n").write(text)


def main():
    check = "--check" in sys.argv
    assets = {
        "aoci.txt": ROOT_MANIFEST,
        "aoci.meta.txt": META_VOLUME,
        "aoci.code.txt": build_code_volume(),
        "AGENTS.md": agents_md(),
        ".gitattributes": GITATTR,
        ".aoci/.gitignore": AOCI_GITIGNORE,
        ".aoci/config.json": json.dumps(CONFIG, ensure_ascii=False, indent=2) + "\n",
        ".aoci/curation.json": json.dumps(CURATION, ensure_ascii=False, indent=2) + "\n",
    }
    if check:
        bad = [p for p, t in assets.items()
               if (not os.path.exists(os.path.join(ROOT, p))
                    or io.open(os.path.join(ROOT, p), encoding="utf-8").read() != t)]
        print("AOCI 资产校验：", "通过" if not bad else "不一致 " + repr(bad))
        return 1 if bad else 0
    for p, t in assets.items():
        write(p, t)
        print("write", p, len(t))
    # .gitignore 追加 AOCI 边界（只加一次）
    gi = os.path.join(ROOT, ".gitignore")
    cur = io.open(gi, encoding="utf-8").read()
    if "# AOCI" not in cur:
        io.open(gi, "w", encoding="utf-8", newline="\n").write(
            cur.rstrip() + "\n\n# AOCI 认知资产：正式文件入库，运行时状态留在本地\n"
            ".aoci/*\n!.aoci/.gitignore\n!.aoci/baseline.json\n"
            "!.aoci/config.json\n!.aoci/curation.json\n")
        print("append .gitignore")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
