# 发布配置与边界

## 入口和产物

**本公开源码快照不包含发布工作流，也没有启用任何自动发布。** 私有开发树中的 `.github/workflows/build-release.yml` 未随本快照导出；下列表格与流程描述的是该工作流的设计与边界，供后续启用时对照，不是本快照已具备的能力。脚本仅依赖 Python 3.11+ 标准库（CI 使用 3.12）；GitHub 写操作由工作流中的 `gh` 执行，CF 使用官方 Upload API。

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
python tools/apply_journal_theme.py --check
bash ./gradlew :common:test :neoforge-1.21.1:build --no-daemon
python tools/check_release.py
python tools/release_pipeline.py package
```

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
| Variable | `CURSEFORGE_APRICITYUI_SLUG` | 从真实 ApricityUI CF 项目确认的 slug |
| Variable | `CURSEFORGE_APRICITYUI_PROJECT_ID` | 同一 ApricityUI 项目的数字 ID |
| Variable | `CURSEFORGE_DUNGEONS_PERSPECTIVE_SLUG` | 从真实 Dungeons Perspective CF 项目确认的 slug |
| Variable | `CURSEFORGE_DUNGEONS_PERSPECTIVE_PROJECT_ID` | 同一 Dungeons Perspective 项目的数字 ID |
| 自动提供 | `GITHUB_TOKEN`（传给 gh 的 `GH_TOKEN`） | publish job 明确申请 `contents: write`；仓库/组织策略必须允许 |
| 环境 / 策略 | `release` | Environment 可访问，审批和分支/标签规则允许本次发布 |

依赖关系在 CF 上传 metadata 中分别为 `requiredDependency` 和 `optionalDependency`，而不是 `embeddedLibrary`。ApricityUI **1.2.8** 为必需客户端前置；Dungeons Perspective **3.6.3** 为可选客户端镜头验证目标。CF 关系本身只标项目，不锁定版本或客户端 side；英文描述明确版本和客户端要求，以 JAR NeoForge 元数据控制实际加载要求。

本次无法可靠确认这两个依赖的真实 CF slug/ID，故**不填入猜测值**。发布负责人必须在 CF 项目页面核对 slug、数字 ID、作者和 1.21.1 NeoForge 文件是否对应；脚本校验格式和项目 ID 互异，但不能替代这种身份核对。若依赖没有可确认的 CF 项目，保持缺配置阻断，或明确选择 `github`，不要伪造关系或默默删掉前置。

**当前是否可自动上传：不能据此宣称已启用。** 本快照内**没有**发布工作流，因此不存在可执行的自动上传路径；以下是开发过程中的配置观测，仅作记录。主会话报告：仓库 variable `CURSEFORGE_PROJECT_ID=1766445` 已设置并验证，secret **名称** `CURSEFORGE_TOKEN` 已存在；未读取任何 secret 值，也未调用上传/编辑项目接口、未创建 Release 或 tag，因此“配置就绪”只到“名称与映射对齐”这一层。其余 variable（ApricityUI / Dungeons Perspective 的 slug 与数字 ID）仍需负责人核对；缺少这些值时发布作业会失败关闭，不会上传半成品。即便配置齐备，发布仍需在补回工作流后单独完成构建、包检查与真实运行验收，**本快照不构成发布就绪结论**。（此前一轮用 `gh variable list --json name` / `gh secret list --json name` 看到两者为空、`release` 环境返回 404，该记录是**当时**的观测，已被上面的最新报告取代但保留为历史。）

## CF API 契约与失败行为

官方参考：<https://support.curseforge.com/en/support/solutions/articles/9000197321-curseforge-upload-api>（本次已在线读取正文核对）。

- 认证：`X-Api-Token` 请求头，不放 query string。
- 先 GET `https://minecraft.curseforge.com/api/game/versions`，从响应中精确查找名称 `1.21.1` 和 `NeoForge` 的唯一数字 ID；不存在或歧义即失败，不硬编码猜测 ID。
- POST `https://minecraft.curseforge.com/api/projects/{projectId}/upload-file`，multipart 仅含 `metadata` JSON 与 `file` 二进制。
- metadata 包括 Markdown CHANGELOG、`releaseType`、两项 gameVersions 和上述依赖关系。标签入口默认 `beta`；手动输入可选 `alpha` / `beta` / `release`。GitHub 对 alpha/beta 同样标记为 prerelease。
- 只有取得正整数文件 ID 才报告 CF 接收成功；接收不等于审核通过或已公开可下载。
- 不跟随 HTTP 重定向，避免把 token 带到另一站点；错误只打印安全状态/配置键名，不打印 token、响应正文或 headers；不对 POST 自动重试。
- 在任何外部写入前完成 CF 配置和版本查询预检；缺配置使整个两处发布失败，GitHub 不会先被误标成功。

两家服务不支持跨站原子提交。当前顺序是 CF 上传成功后再创建 GitHub Release；如果随后 GitHub 失败，可能留下 CF 已接收、GitHub 未创建的**部分完成**状态。应先检查 CF 项目文件列表/审核状态及 GitHub Release，再决定人工恢复；不要直接反复 rerun 导致 CF 重复文件。GitHub 已有同名 Release 时拒绝继续，不覆盖旧附件。网络超时/未知响应同样需要人工确认 CF 是否已接收，不是自动重试的理由。

若明确只发布 GitHub，手动选择 `target=github`。工作流摘要会写明 **CurseForge NOT enabled**；这种成功不能被描述为“两处发布成功”。对当前可见配置，这是唯一不依赖 CF token 的外部发布方式，但本次没有执行。

## 描述、美术与许可

- 英文 CF 正文：[curseforge.md](curseforge.md)，上传到 CF 项目描述栏；工作流只上传文件，不改写 CF 项目描述或项目头像。
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
