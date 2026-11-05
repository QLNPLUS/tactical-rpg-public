# 发布配置与边界

## 仓库与发布目标（公开线）

- 规范仓库：`QLNPLUS/tactical-rpg-public`，默认分支 `main`。后续提交、普通 push、版本标签与显式发布都在这个仓库完成；原私有仓库只作历史备份，不再推送。
- 普通 push 与 pull_request 只构建、测试与检查发布包，不提供发布凭据、不创建 Release、不上传 CurseForge。只有与 `gradle.properties` 的 `mod_version` 精确对应的 `v<版本>-neoforge-1.21.1` 标签，或显式 `workflow_dispatch`，才进入发布作业。
- **开源不等于已发布**：这个仓库里的源码树不等于对应版本的二进制已经上传到 CurseForge 或 GitHub Release。是否上传、文件 ID 与审核状态一律以两家服务的回执为准，不以仓库文档的声明代替。
- 工作流不使用 ApricityUI 相关的任何变量；界面自 0.24.0 起由本模组原生绘制，没有 UI 前置可声明。

## 入口和产物

工作流：[build-release.yml](../.github/workflows/build-release.yml)。脚本仅依赖 Python 3.11+ 标准库（CI 使用 3.12）；GitHub 写操作由工作流中的 `gh` 执行，CF 使用官方 Upload API。

| 事件 | 构建 / 检查 | GitHub Release / CurseForge |
| --- | --- | --- |
| 普通分支 push（含 main） | 是 | **不发布**，不向构建步骤提供发布凭据 |
| pull_request | 是 | **不发布**，不使用 pull_request_target |
| push `v*-neoforge-1.21.1` 标签 | 是，先核对版本与 tag | 两处发布；CF 配置缺失则阻断 |
| workflow_dispatch | 是，检出显式输入的既有标签 | `both`（默认）或明确选择 `github` |

版本唯一来源是根 `gradle.properties` 的 `mod_version`；例如版本 `0.22.2` 只接受标签 `v0.22.2-neoforge-1.21.1`，不接受省略平台、版本不符或旧版本标签。手动运行也必须提供已存在且指向本次构建提交的标签。不要把本文的版本示例当作已发布事实。

构建前检查 Minecraft **1.21.1**、NeoForge **21.1.216**、JDK **21**；更改目标需先人工审核工作流。构建执行：

```bash
python -m unittest discover -s tools -p test_release_pipeline.py -v
bash ./gradlew :common:test :neoforge-1.21.1:build --no-daemon
python tools/check_release.py
python tools/release_pipeline.py package
```

原有的 `python tools/apply_journal_theme.py --check` 已从 CI 与本清单删除：该脚本随 AUI 页面一起退役、仓库内已不存在，继续执行必然阻断推送 CI。页面主题不再由脚本检查，而由 `NativeUiOnlyTest` 与 `tools/check_release.py` 的原生门覆盖（JAR 内不得有 AUI 页面/类/旧 mixin/字体，且必须包含原生界面类）。

只调用仓库 wrapper，不调用全局 Gradle。`package` 使用标准库核对三份 PNG 的 IHDR 尺寸（1024 / 400 / 256），并核对 JAR 中 logo 与源码一致、JAR 版本、许可元数据（当前为 **LGPL-3.0-only**）、根 LICENSE、NOTICE、两份第三方许可原文和 YiRan 嵌套包条目；字体门由 `tools/check_release.py` 的“包内不得出现 `.ttf`”断言负责，`release_pipeline.py` 的 `check_jar` 不检查字体，CI 中两者都会运行；不依赖 Pillow，也不冒充图像解码/视觉验收。生成：

嵌套库门（两个工具共用 `tools/expression_lib.py` 的同一组常量，不各写一份哈希）：`META-INF/jarjar/metadata.json` 必须**恰好一个**条目，身份 `com.github.yiran1457:YiRanExpressionLib`、JarJar 区间 `[1.0.1]`、`artifactVersion 1.0.1`、路径 `META-INF/jarjar/YiRanExpressionLib-1.0.1.jar`；包内 `.jar` 嵌套条目必须唯一；该条目的字节必须等于 `libs/maven/.../YiRanExpressionLib-1.0.1.jar`，且等于**官方 JitPack `universal` 制品**（62897 字节 / SHA-256 `d797d9ea…`，上游提交 `60584101e`），并仍声明 mod ID `yiranexpressionlib`、版本 `1.0.1`、`license="LGPL v3.0"`。`libs/maven` 内解析用的 artifact 也要通过同一字节校验，防止 Gradle 缓存旧模块后仍打包成功。这条门证明的是**打包进来的字节是哪一份**，不证明许可合规。

- `build/release/tactical-neoforge-1.21.1-<版本>.jar`
- `build/release/SHA256SUMS`
- `build/release/release-manifest.json`

空目录才允许打包，避免历史产物混入。发布作业下载同一次构建的唯一制品包，重新核对 SHA-256、元数据及声明，不再次构建。CF 和 GitHub 使用**同一份通过校验的 JAR**；GitHub 另附上述校验和与 manifest，CF 只接收该 JAR。不上传 sources JAR、运行日志、遥测、运行目录或凭据；GitHub 平台自身可能提供的自动源码归档不属于工作流上传附件。

## 必需配置清单

在仓库 Settings → Secrets and variables → Actions 中配置；发布作业使用名为 **release** 的 GitHub Environment，建议为它设置审批人和允许发布的标签规则。可把 token 放进该 Environment 的 secrets；其他值可使用仓库 variables 或该 Environment 的 variables。

| 类型 | 名称 | 要求 |
| --- | --- | --- |
| Variable | `CURSEFORGE_PROJECT_ID` | **自己的** Minecraft CF 项目数字 ID；需先实际创建并确认项目，不猜测 |
| Secret | `CURSEFORGE_TOKEN` | 有该项目上传权限的 Upload API token；workflow 把它映射为作业内环境变量 `CURSEFORGE_API_TOKEN`（Python 侧变量名不变）。不得提交到源码、URL 或日志 |
| Variable | `CURSEFORGE_DUNGEONS_PERSPECTIVE_SLUG` | 从真实 Dungeons Perspective CF 项目确认的 slug |
| Variable | `CURSEFORGE_DUNGEONS_PERSPECTIVE_PROJECT_ID` | 同一 Dungeons Perspective 项目的数字 ID |
| 自动提供 | `GITHUB_TOKEN`（传给 gh 的 `GH_TOKEN`） | publish job 明确申请 `contents: write`；仓库/组织策略必须允许 |
| 环境 / 策略 | `release` | Environment 可访问，审批和分支/标签规则允许本次发布 |

上传 metadata 只声明**一个**依赖关系：Dungeons Perspective **3.6.3** 为 `optionalDependency`（可选客户端镜头验证目标），不是 `embeddedLibrary`。界面重制后本模组不再有 UI 前置，因此**不再声明 `requiredDependency`**；已发布的 0.23.1 上传时曾声明 ApricityUI 1.2.8 为必需前置，那是已发布版本的历史事实。CF 关系本身只标项目，不锁定版本或客户端 side；英文描述明确版本和客户端要求，以 JAR NeoForge 元数据控制实际加载要求。

这个可选依赖的真实 CF 标识在 0.23.1 轮已按可核查来源填入（见下）。发布负责人仍应在 CF 项目页面核对 slug、数字 ID、作者和 1.21.1 NeoForge 文件是否对应；脚本校验格式和项目 ID 互异，但不能替代这种身份核对。若依赖没有可确认的 CF 项目，保持缺配置阻断，或明确选择 `github`，不要伪造关系或默默删掉前置。

**已废弃的变量（不要再配置）**：`CURSEFORGE_APRICITYUI_SLUG` 与 `CURSEFORGE_APRICITYUI_PROJECT_ID` 已从工作流和 `tools/release_pipeline.py` 移除；界面重制后没有任何 UI 前置可声明。保留它们不会改变上传内容，只会让人误以为仍有必需前置。

**0.23.1 发布轮的配置状态（历史）**：仓库 variable `CURSEFORGE_PROJECT_ID=1766445` 与 secret 名称 `CURSEFORGE_TOKEN` 沿用既有设置；本轮补齐了此前缺失的四个依赖 variable：`CURSEFORGE_APRICITYUI_SLUG=apricityui` / `CURSEFORGE_APRICITYUI_PROJECT_ID=1470115`、`CURSEFORGE_DUNGEONS_PERSPECTIVE_SLUG=dungeons-perspective` / `CURSEFORGE_DUNGEONS_PERSPECTIVE_PROJECT_ID=1258200`。这四个数字/文案取自 CurseForge 数据的只读镜像 `api.cfwidget.com`（返回的 `id`、`title`、`urls.curseforge` 与 slug 三者自洽），并用文件名前缀交叉核对：该镜像下 ApricityUI 的文件为 `ApricityUI-neoforge-1.21.1-*.jar`、Dungeons Perspective 的文件为 `dungeons_iso-neoforge-*+1.21.1.jar`，与本项目测试安装实际加载的 `ApricityUI-neoforge-1.21.1-1.2.8.jar`、`dungeons_iso-neoforge-3.6.3+1.21.1.jar` 命名规则一致。**这仍不是 CF 项目页面的人工核对**：页面渲染需要 Cloudflare 交互，本轮未取得；发布负责人应自行打开两个项目页面确认作者与 1.21.1 NeoForge 文件无误。脚本只校验格式、互异性与"依赖项目不同于发布项目"，不校验身份。改动任一 variable 前先看该轮发布说明是否已声明依赖关系。

## CF API 契约与失败行为

**0.23.1 实测补充（直接调用官方 Upload API 的项目必读）**：以下三点都在真实发布中被 HTTP 400 挡住过，已由 `tools/release_pipeline.py` 处理并有对应的离线测试。

1. **版本名不唯一**：`GET /api/game/versions` 里 `1.21.1` 同名出现三次——`(11779, 类型 77784 "Minecraft 1.21")`、`(12735, 类型 1)`、`(16115, 类型 615 "Addons")`。只按名字要求“唯一”会永远失败；必须先取 `GET /api/game/version-types`，再在对应类型组内解析。
2. **必须带环境组**：`gameVersions` 除游戏版本与加载器外，还要至少一个环境条目，否则回执 `You must select at least one version from the environment group of versions`。实测 `Environment=75208`（`Client=9638` / `Server=9639`）、`Modloader=68441`（`NeoForge=10150`）、`1.21.1=11779`。
3. **`projectID` 必须是整数**：官方文章示例写 `projectID: "74924"`，实际校验为 `Invalid type. Expected Integer but got String. Path 'relations.projects[0].projectID'`。URL 与比较仍用字符串，payload 里转 `int`。

错误诊断口径也已调整：命中 HTTP 错误时读取至多 16385 字节、按 JSON 解析后**只采纳 `errorMessage`（回退 `message`）**，替换 token 为 `[redacted]` 并截断到 512 字符；绝不原样输出正文、headers 或 URL。整段抑制会让 400 完全无法定位（本轮就先卡在这里）。

官方参考：<https://support.curseforge.com/en/support/solutions/articles/9000197321-curseforge-upload-api>（本次已在线读取正文核对）。

- 认证：`X-Api-Token` 请求头，不放 query string。
- 先 GET `https://minecraft.curseforge.com/api/game/versions`，从响应中精确查找名称 `1.21.1` 和 `NeoForge` 的唯一数字 ID；不存在或歧义即失败，不硬编码猜测 ID。
- POST `https://minecraft.curseforge.com/api/projects/{projectId}/upload-file`，multipart 仅含 `metadata` JSON 与 `file` 二进制。
- metadata 包括 Markdown CHANGELOG（**仅当前版本章节**，见下节）、`releaseType`、两项 gameVersions 和上述依赖关系。标签入口默认 `beta`；手动输入可选 `alpha` / `beta` / `release`。GitHub 对 alpha/beta 同样标记为 prerelease。
- 只有取得正整数文件 ID 才报告 CF 接收成功；接收不等于审核通过或已公开可下载。
- 不跟随 HTTP 重定向，避免把 token 带到另一站点；错误只打印安全状态/配置键名，不打印 token、响应正文或 headers；不对 POST 自动重试。
- 在任何外部写入前完成 CF 配置和版本查询预检；缺配置使整个两处发布失败，GitHub 不会先被误标成功。

两家服务不支持跨站原子提交。当前顺序是 CF 上传成功后再创建 GitHub Release；如果随后 GitHub 失败，可能留下 CF 已接收、GitHub 未创建的**部分完成**状态。应先检查 CF 项目文件列表/审核状态及 GitHub Release，再决定人工恢复；不要直接反复 rerun 导致 CF 重复文件。GitHub 已有同名 Release 时拒绝继续，不覆盖旧附件。网络超时/未知响应同样需要人工确认 CF 是否已接收，不是自动重试的理由。

若明确只发布 GitHub，手动选择 `target=github`。工作流摘要会写明 **CurseForge NOT enabled**；这种成功不能被描述为“两处发布成功”。对当前可见配置，这是唯一不依赖 CF token 的外部发布方式，但本次没有执行。

## 发布说明、描述、美术与许可

- **两家服务的发布说明都只取当前版本章节**（`tools/release_pipeline.py` 的同一个 `release_changelog`）：CurseForge 上传把该段放进 metadata 的 changelog；GitHub Release 由 `release-notes` 子命令先写到 `$RUNNER_TEMP/release-notes.md`，`gh release create` 只读这个临时文件，不再用整本 `CHANGELOG.md` 当 notes。本地可用 `python tools/release_pipeline.py release-notes --version <版本> --output <路径>` 预览同一段文本。当前 `mod_version` 是 **0.28.0 已有限验收、未公开发布**（烈焰人原版蓄力与三发真实小火球、同场对立名册碰撞隔离、空中战术站位均已实装；875 项 Java 测试、51 项发布脚本测试和新 `blaze` 服务端夹具及受影响夹具通过；真实客户端已完成原地齐射、遮挡走位、旁观者与地形碰撞三组有限场景，服务端权威读数与关键原图已由主控复核。开发启动使用 sourceSet 输出，未将打包 JAR 独立运行；未因此重跑全部历史界面或多人矩阵）。本轮批准的是实现、验收及通过后的提交推送/测试机检查，**尚未授权本版本 CF/GitHub 公开发布**；0.25.2 的发布指令不自动延伸到本版本。实际提交、部署、上传文件 ID 和审核状态分别以相应回执为准，不以文档声明代替成功。只提取当前版本章节，不额外上传历史章节。该章节必须全文英文且不超过 8000 字符上限（超限会被裁成 `(truncated for upload)`）；`CHANGELOG.md` 里 0.24.2 及更早章节保持历史原文，不随本版本发到两个服务。缺该版本章节时 `release-notes` 直接失败，不会退回整本文件。
- 临时 notes 文件只放在 runner 临时目录，**不进** `build/release` 这个只含二进制的制品包。
- 英文 CF 正文：[curseforge.md](curseforge.md)，上传到 CF 项目描述栏；工作流只上传文件，不改写 CF 项目描述或项目头像。GitHub Release 也不会用该文件作 notes。
- 模组 logo 位于 `neoforge-1.21.1/src/main/resources/logo.png`，`neoforge.mods.toml` 的 `logoFile` 指向 `logo.png`；发布包检查要求该 PNG 实际存在。
- 高分辨率源图约定 `branding/tactical-rpg-1024.png`；CF 图约定 `branding/curseforge-icon.png`。图像由项目主控制作并审核，本工作流不会生成或自动上传项目头像。
- 自有部分以根 [LICENSE](../LICENSE) 为准；根 Gradle 为 Java 模块 JAR 加入 `META-INF/LICENSE`。NeoForge 模块继续打包原有 [NOTICE.md](../NOTICE.md)、第三方 LGPL / GPL 文本和嵌套库，不修改第三方声明或库二进制。
- 嵌套库现在是上游官方 JitPack `universal` 制品（提交 `60584101e`）的原始字节，本地只做坐标/路径归一化为 `1.0.1`；见 [libs/maven/README.md](../libs/maven/README.md)。
- NOTICE 已记录 YiRan 上游 LGPL / AGPL 元数据差异和替换/调试例外；自动检查只证明声明存在、字节与官方制品一致，**不证明许可争议已解决或法律合规**，也不证明该制品可由源码本地重建。

## 测试与验收边界

离线 [test_release_pipeline.py](../tools/test_release_pipeline.py) 使用临时 fixture 和 mock GET/POST，覆盖精确 tag、MC/JDK、包污染、校验和篡改、JAR 许可/图标、缺配置阻断、依赖关系、multipart 同一 JAR、错误脱敏与重定向禁用；嵌套库门另有正例与失败例：非官方字节（改一字节/截断/空）、第二个嵌套 JAR、metadata 条目 0 个或 2 个、身份被改名、区间/`artifactVersion` 不符、路径不符、解析用 artifact 过期或缺失、描述符 mod ID/版本/许可被改。它不读取真实 token、不访问 CF 上传接口、不创建 GitHub Release。


CI 单元测试与打包检查不等于游戏运行验收。发布负责人仍需按项目要求完成独立 runClient、真实 Dungeons Perspective、真实输入回调和截图的鼠标拿放/右键/尺寸/选点等验收，再授权发布标签；共享目录部署也不等于目标安装运行验收。无真实运行证据时不得在 CF 描述或发布说明补写“全面兼容”或“多人共同参战”。

## Actions 固定版本来源

本次使用 `gh api repos/actions/<name>/git/ref/tags/<version>` 读取官方仓库 tag 引用，并确认对象类型为 `commit`；工作流使用完整 SHA，不用浮动 tag。固定并非永久安全保证，升级时仍需复核官方仓库/变更与实际执行行为。

| 官方 Action | 查询 tag | 固定 SHA |
| --- | --- | --- |
| actions/checkout | v4 | `11d5960a326750d5838078e36cf38b85af677262` |
| actions/setup-java | v4 | `cf277c60eb25467037889841efdb72551f06f6c3` |
| actions/setup-python | v5 | `a26af69be951a213d495a4c3e4e4022e16d87065` |
| actions/upload-artifact | v4 | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| actions/download-artifact | v4 | `d3f86a106a0bac45b974a628896c90dbdf5c8093` |
