# 第三方许可审计：YiRanExpressionLib 1.0.1

> 注：本文中形如 `evidence/...` 的路径指私有开发树中的取证目录，**不随本公开源码快照发布**；保留它们只为追溯当时的取证位置，此处没有对应文件，也不能据此核对内容。

本文件记录事实核查的结论与边界，服务于发布门判断。它不是法律意见，也不构成合规保证。凡证据只能支持“观察”的地方，本文不写成“证明”。

## 1. 结论摘要

| 项 | 状态 | 依据 |
| --- | --- | --- |
| 分发字节的来源（官方 JitPack `universal` 构建） | **确证** | 仓库内 JAR 与 JitPack `60584101e6` 官方 `universal` 制品逐字节相同（62897 字节，SHA-256 `d797d9ea…`），并与既有审计样本 `up-universal.jar` 同哈希 |
| 该二进制的对应源码**位置** | **确证** | 该制品由 JitPack 从上游提交 `60584101e698d1e3f79d327b18982dec7ddb5465`（`universal` 分支 / 标签 `1.0.1-universal`）构建，即对应源码位置明确 |
| 该二进制可由上述源码**本地完整重建** | **未验证** | 未在本仓库重建 JitPack 构建；字节一致只证明拿到的是官方制品，不证明可复现 |
| 库自身许可 | **确证为 LGPL v3.0** | 该提交 `LICENSE.txt`、`gradle.properties`、构建产物描述符三处一致，且许可文本与随包文本逐字节相同 |
| 固定 `universal` 提交为 LGPL、默认分支旧版本为 AGPL | **确证** | 两个提交的 `LICENSE.txt` 与 `gradle.properties` 各自自洽 |
| AGPL 标签的来源 | **部分确证 + 一处推断** | 确证：仓库级许可元数据与 JitPack 为 `60584101e6` 生成的 POM 都标 AGPL，而该提交源码是 LGPL。推断（无官方生成链文档）：这些标签取自默认分支/仓库元数据 |
| 身份归一化（本地 `1.0.1` 路径 vs JitPack `60584101e6`） | **确证的本地决定** | 字节取自官方制品，坐标/POM 保留本地 `1.0.1` 归一化身份；随包 POM 不是 JitPack POM |
| LGPL-3.0 第 4 节路径的文档与机制准备 | **部分完成** | 许可文本随包、路径/JarJar 身份保留、替换与调试例外写入 NOTICE/LICENSE；归档级替换 + 提供者选择已实测，正式启动器路径与“§4 条件全部具备”仍未验证 |
| LGPL-3.0 第 4 节组合物的法律结论 | **未定** | 事实缺口（本地可重建性、正式路径替换验证）与权利人的授权决定都还未完成；仅表示“接受”不能消除事实缺口 |

本审计未发现“必须按 AGPL 使用”的证据，同样**没有**得出“AGPL 肯定不适用”的结论。

本轮变更（相对上一版审计）：随包 JAR 从 Advanced Leveling 提交的分发副本，替换为**上游官方 JitPack `universal` 构建的原始字节**，使“分发字节 == 官方制品”重新成立。这项替换修复的是来源问题，**不是**法律问题：AGPL/LGPL 元数据冲突、1.0.1 再许可覆盖范围、§4 组合物授权决定仍未闭合，因此不得据此宣称合规或“许可门已通过”。

此前另有一次自有授权变更：项目自有代码、文档与美术的授权由“All Rights Reserved”改为 **LGPL-3.0-only**（根 `LICENSE` 换成完整 GNU LGPL v3 正文，项目自身版权行写入 `README.md` 与 `NOTICE.md`，`mod_license=LGPL-3.0-only`）。它改变的是**自有作品**的授权，不是对第三方事实缺口的修复。

## 2. 核验方法（可复现）

本地（只读）：

```
sha256sum libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/*.jar \
          libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/*.pom \
          licenses/YiRanExpressionLib-LICENSE.txt
git hash-object libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/*.jar \
                libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/*.pom \
                licenses/YiRanExpressionLib-LICENSE.txt
unzip -p <构建产物或本地发布包>/tactical-neoforge-1.21.1-<版本>.jar \
      META-INF/jarjar/YiRanExpressionLib-1.0.1.jar > /tmp/nested.jar && sha256sum /tmp/nested.jar
python tools/check_release.py            # 包内嵌套库字节 + JarJar 身份/区间/路径 + 唯一条目
python -m unittest discover -s tools -p test_release_pipeline.py
```

上游（只读 GitHub API 与 JitPack 制品，未做任何写操作）：

```
gh api repos/QLNPLUS/Advanced-Leveling/contents/libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1?ref=c4b34fe38ca6008fce844af584b610b249ecadbb
gh api repos/QLNPLUS/Advanced-Leveling/commits/0419ff80ee6737b781a82144330a7c4e31c207c4
gh api 'repos/yiran1457/YiRanExpressionLib/commits?sha=universal&per_page=5'
gh api repos/yiran1457/YiRanExpressionLib/git/trees/60584101e698d1e3f79d327b18982dec7ddb5465
gh api repos/yiran1457/YiRanExpressionLib/contents/gradle.properties?ref=60584101e698d1e3f79d327b18982dec7ddb5465
gh api repos/yiran1457/YiRanExpressionLib/contents/gradle.properties?ref=938fff3b28dc11f4f3a614a8eaf43f136dd771d9
curl -sS -O https://jitpack.io/com/github/yiran1457/YiRanExpressionLib/60584101e6/YiRanExpressionLib-60584101e6-universal.jar
curl -sS -O https://jitpack.io/com/github/yiran1457/YiRanExpressionLib/60584101e6/YiRanExpressionLib-60584101e6-sources.jar
cmp YiRanExpressionLib-60584101e6-universal.jar \
    libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/YiRanExpressionLib-1.0.1.jar
javap -p -c <解包后的 AstNode.class / Token.class>
javap -l  <同上>
javap -v  <同上>
```

原始产物与哈希清单保存在被忽略的 `evidence/` 目录（不属于源码交付物）。

## 3. 已确证的事实

1. **分发字节就是官方构建。** 仓库内 JAR（62897 字节，SHA-256 `d797d9eaf22923b5f0d0d152b583aef3d60600bf349aa481a89ecd159255106a`，git blob `30e9f0d433ae811f8703f7cbbfc0414a20f5bec8`）经 `curl` 下载 JitPack `60584101e6` 的 `YiRanExpressionLib-60584101e6-universal.jar`（HTTP 200，62897 字节）后 `cmp` 逐字节相同，且等于既有审计样本 `up-universal.jar` 的哈希。文件未被解包、迁移、修补或重新生成。POM（455 字节，`854c1015…`，blob `c46b4572…`）与许可文本（8846 字节，`80fef18d…`，blob `13ffb4b1…`）保持原状。
2. **1.0.1 源码按 LGPL v3.0 发布。** 上游 `universal` 分支顶端与标签 `1.0.1-universal` 同为 `60584101e698d1e3f79d327b18982dec7ddb5465`（2026-09-02T04:49:05Z，提交说明为“1.0.1：扩展mc版本兼容，更新协议为lgpl3”）。该提交 `LICENSE.txt` 是 GNU LGPL v3.0 正文（blob `13ffb4b1…`，与随包文本逐字节相同），`gradle.properties` 为 `mod_license=LGPL v3.0`、`mod_version=1.0.1`，构建出的 JAR 内 `mods.toml` 与 `neoforge.mods.toml` 均声明 `license="LGPL v3.0"`。
3. **旧版本按 AGPL 发布。** 默认分支 `1.21.1-NeoForge` 顶端 `938fff3b…` 是 1.0.0：`LICENSE.txt` 为 AGPL-3.0（34523 字节，blob `be3f7b28…`），`mod_license=AGPL v3.0`，`mod_version=1.0.0`。因此“默认分支是 AGPL”描述的是 1.0.0，不能据此判定当前固定版本必为 AGPL。
4. **AGPL 标签在仓库元数据与 JitPack POM 上都出现。** GitHub 仓库级许可元数据为 AGPL；JitPack 为 `60584101e6` 生成的 POM（1258 字节，`94a4b805…`）也写 `<name>GNU Affero General Public License v3.0</name>`，而该提交源码是 LGPL。两者标签一致是确证的；**“JitPack 该 POM 由默认分支生成”属推断**，没有找到官方生成链说明，本审计不把它当因果事实。随包 `1.0.1` POM 是本地最小 POM，**不是**这份 JitPack POM，因此包内不含其 AGPL 字段，也不因此取得任何额外授权。
5. **随包二进制内的可核对事实。** 58 个归档条目；`META-INF/neoforge.mods.toml`（`loaderVersion="[1,)"`，NeoForge 实际读取的描述符）与 `META-INF/mods.toml`（`loaderVersion="[47,)"`）均为 `modLoader="lowcodefml"`、mod ID `yiranexpressionlib`、mod 版本 `1.0.1`、`license="LGPL v3.0"`；类文件为 Java 17（major 61）；无 `fabric.mod.json`。
6. **官方 `sources.jar` 的内容范围有限。** JitPack 发布的 `…-60584101e6-sources.jar` 含 11 个 `.java` 与 1 个 `MANIFEST.MF`，**不含** `build.gradle`、`gradle.properties`、`mods.toml` 等构建/元数据文件。上游仓库该提交本身有构建脚本；2026-10-28 已在忽略证据目录按该脚本重建（见 §5 的“本地可重建性”条与 `evidence/upstream-rebuild-2026-10-28/`），但重建产物与官方制品并非逐字节相同（差异定位到两个类的 `PermittedSubclasses` 条目顺序）。
7. **§4 相关的文档与机制已就位，但不是“已验证”。** 外套 MOD 内含 `META-INF/licenses/yiran-expression-lib/YiRanExpressionLib-LICENSE.txt`（LGPL 正文）与 `GPL-3.0.txt`；嵌套库保持路径 `META-INF/jarjar/YiRanExpressionLib-1.0.1.jar`、Maven 身份 `com.github.yiran1457:YiRanExpressionLib`、JarJar 区间 `[1.0.1]`；NOTICE 与 LICENSE 明确不限制为调试替换所需的逆向工程，且声明库未被改授为 ARR。此前的隔离副本实测（用户授权，见 `evidence/0.23.0/mp05/full/jarjar-verify/evidence-summary.json`）用的是**替换前**的字节加随机标记，证明了归档级替换与那一次运行的提供者选择，**不**证明源码对应、也未走正式启动器路径。

8. **官方字节的客户端与服务端实测（本轮，隔离目录）**：用换库后重新构建的 `tactical-neoforge-1.21.1-0.23.0.jar`（`753a604c…`，包内嵌套库 `d797d9ea…`）在隔离 gameDir 中启动，mods 目录只有该 JAR，classpath 已剔除独立库与 dev 输出（证据 `evidence/libswap-2026-10-28/evidence-summary.json`）：
   - 客户端（`forgeclientdev`）：mod 列表含 `YiRanExpressionLib 1.0.1 (yiranexpressionlib)`，`Expression`/`ExpressionBuilder` 的 codeSource 为 `union:…!/META-INF/jarjar/YiRanExpressionLib-1.0.1.jar!/`（module `yiranexpressionlib`），`SkillFormula.compile/evaluate(base=10, level=3) = 17.5`，到达 TitleScreen。
   - 专用服务器（`forgeserverdev`）：同样由嵌套 JAR 提供库类，`Done (4.332s)` 正常启动，探针同一公式返回 17.5；随后以 `-Dtactical.selftest=skills` 跑真实技能自测，输出 `SKILLS SELFTEST PASSED: 546 assertions`（含 formula preflight）并自行停机。
   - 边界：两次都是 dev 启动目标，未走正式启动器配置；探针只说明“这些类来自哪个嵌套条目 + 一次产品级公式求值”，不是功能回归全集；嵌套 JAR 无法在 JVM 内经 union URL 求哈希，字节一致性由 JVM 外的哈希比对（`tools/check_release.py`）建立。

## 4. 历史：替换前的 Advanced Leveling 分发副本（已不在当前候选包内；已发布的旧版本仍携带）

- 旧 JAR 为 62894 字节，SHA-256 `dbaa9f289b0e1a21ff8a0ce0b293ac05cc221e6f1383abc99663b37e1da51ab3`，git blob `7e5cf834…`，与 `QLNPLUS/Advanced-Leveling` 提交 `c4b34fe38ca6008fce844af584b610b249ecadbb`（2026-09-02T15:26:07Z）中的 blob 完全一致；引入该文件的提交为 `0419ff80`（“Make YiRanExpressionLib fallback reproducible”）。
- 与官方 `universal` 制品比对（**两份归档同为 58 个条目、52 个文件条目**）：49 条逐字节相同；`META-INF/mods.toml` 仅 `loaderVersion` 不同（官方 `"[47,)"`，副本 `"[43,)"`）；`AstNode.class`、`Token.class` 字节不同，但 `javap -p -c` 输出完全相同，`javap -v` 去掉文件路径行与 `SHA-256 checksum` 行后各仅 2 行差异，且都位于 **`PermittedSubclasses`** 属性内——只差一条条目的位置（`AstNode$BinaryAstNode`、`Token$FunctionToken`）。此前本文件写“`InnerClasses` 中条目位置变化”，经 2026-10-28 用 JDK 21.0.5 的 `javap` 复核属**属性名误记**，已更正；`InnerClasses` 段在两个版本间完全相同，旧记录“各 12 行差异”亦不再成立。
- 计数更正：此前本文件与 `NOTICE.md`、`libs/maven/README.md` 把同一比对写成“60 个归档条目、57 条相同”。对同一批字节重算得到 **58 个归档条目（52 个文件条目）、49 个文件条目逐字节相同**（另 3 条为 `mods.toml`、`AstNode.class`、`Token.class`），旧的计数有误，以本节为准；差异条目、大小与哈希结论本身未变。
- 该副本的 `[43,)` 改动没有公开补丁或构建说明载体，制作方与意图至今未知（上下游均无说明；本轮未向上游询问谁构建了这份副本，也未创建 issue）。它现在已不在产品中，但作为历史事实保留：**旧版本已发布产物仍携带该副本**，追溯旧版本的来源仍需本节信息。

## 5. 未闭合与已知边界

- **本地可重建性：已在隔离证据目录重建，官方字节未被逐字节复现**：2026-10-28 在 `evidence/upstream-rebuild-2026-10-28/`（忽略目录）从该提交源码、按上游 wrapper（Gradle 9.2.1）与本机 JDK 21.0.5 执行 `./gradlew jar`，成功且二次构建字节相同（`f8a44882…`，62896 字节）。与官方 `d797d9ea…`（62897 字节）逐条对比：条目名/顺序、时间戳、压缩方式相同；**内容 50/52 文件条目相同**，差异仍是 `AstNode.class`、`Token.class`（`javap -v` 仅 `PermittedSubclasses` 内一条条目位移）；**压缩大小差异仅 `AstNode.class`**（官方 1632 / 本次重建 1631，即整体 1 字节差）。JitPack 日志显示其环境为 JDK **21.0.2**（本机 21.0.5），用 21.0.2 复现**未执行**，因此“官方字节可由该源码逐字节复现”仍**未被证明**；“拿到官方制品”与“可逐字节复现官方制品”仍是两件事。
- **身份归一化是本地决定**：JitPack 以 `60584101e6` 发布该构建，本地按 `1.0.1` 路径与最小 POM 归一化（沿用本项目自首次随包以来使用的身份）。这不代表上游声明 `1.0.1 == 60584101e6`，也不改变第三方权利。Gradle 可能缓存旧模块文件，构建后须以 `tools/check_release.py` 校验包内嵌套库哈希，必要时 `--refresh-dependencies`。
- **与当前 Advanced Leveling 分支的共存不成立（已实测）**：`AdvancedLeveling-1.21.1-1.0.1-fix` 自带 `META-INF/jarjar/YiRanExpressionLib-1.21.1-NeoForge-1.0.0.jar`（47022 字节，`dbf7f922…`，描述符 `version="1.0.0"`、`license="AGPL v3.0"`），JarJar 区间 `[1.21.1-NeoForge-1.0.0,)`，并要求 NeoForge `[21.1.248,)`。隔离专用服务器同时装入两者时在启动前即失败：`JarJar VERSION_RESOLUTION_FAILED` for `com.github.yiran1457:YiRanExpressionLib`（`tactical` 请求 `[1.0.1,1.0.1]`，`autoleveling` 请求 `[1.21.1-NeoForge-1.0.0,)`），另加该模组对 NeoForge `[21.1.248,)` 的要求与 21.1.216 开发运行时不符。**这是两个模组各自请求冲突导致的加载失败，不是本次字节替换造成的**（本次未改变任一方的版本请求：本项目换库前后都是 `[1.0.1]`），也不代表更早的 Tactical 或 Advanced Leveling 版本同样失败。旧文档“区间与 Advanced Leveling for NeoForge 1.21.1 一致”的说法只对当年内嵌同一 `1.0.1` 制品的旧分发成立，现已不准确。
- **AGPL/LGPL 元数据冲突未解决**：JitPack POM 与仓库级元数据标 AGPL、提交源码标 LGPL，两者共存事实已确证，法律后果未定，不得断言 AGPL 肯定适用或不适用。
- **JarJar 替换路径：隔离实测在其有限范围内通过，正式路径未测**：隔离副本只替换嵌套条目（其余 447 条与 `metadata.json` 字节一致）；运行期 `Expression`／`ExpressionBuilder` 由该被替换的嵌套 JAR 提供（与产品类同一 loader，可读到随机标记），并通过产品 API 完成一次公式烟测（`SkillFormula.compile` + `evaluate`，`base=10 level=3` → 17.5）。**未测**：正式启动器路径，以及任何库代码修改后的行为差异。
- **1.0.1 再许可是否覆盖 1.0.0 的全部贡献者**未核实；仓库表现为单一维护者只是观察，不是证明。
- **作者许可（已报告，未核实）**：项目维护者此前已取得库作者对本项目打包该库的许可——该事实于 2026-10-28 的会话中告知，**实际取得日期未知**；但**未提供任何许可文本、范围、当事方或证据**，因此本文件不引用、不解释、也不据此下结论；它既未核实，也不解除上述任何一项（官方字节可复现性、再许可覆盖范围、AGPL/LGPL 元数据冲突、LGPL-3.0 §4 组合路径）。只有把实际条款记录在案并对照随包字节复核，才算闭合。
- **纪事纸张/皮革/金属装饰与回合头像边框（用户确认的权利，依据未记录）**：项目维护者确认自己拥有、或已获得这两族素材及其衍生图（`ui/textures/journal/*-source.png` 与 `*-tile.png`、`*-frame.png`；`ui/textures/turn-portrait-frame*.png`）在公开源码发布与作为 CF 模组使用/分发上的权利。本文件如实记为**用户陈述**：**未**记录权利人姓名、许可名称、授权范围文件或转让证据，**也**不把「允许使用与分发」推断为「允许按 LGPL-3.0-only 再许可」——项目自有许可声明不因此扩展到这两族素材。turn portrait 的输入图仍不在仓库内，其制作工具与来源无记录。此前把这两族记为「权属待核实」的表述，已被本条用户确认取代；事实层面的来源缺口仍然存在。
- **法律门不是表态就能消除的**：即使权利人声明接受 LGPL-3.0 §4 路径，上面的事实缺口仍需闭合；反过来，事实证据齐备也不能替代授权决定。

## 6. 发布门与建议

- 分发**来源**已确证为官方构建；**源码重建并比较已初次完成**（2026-10-28，见 §5 与 `evidence/upstream-rebuild-2026-10-28/`），但**官方字节未被逐字节复现**（差异为两个类 `PermittedSubclasses` 内一条条目顺序，压缩大小差仅 `AstNode.class`：官方 1632 / 重建 1631），且正式路径替换验证仍未闭合、AGPL/LGPL 元数据冲突未解决，因此许可门当前仍**阻断**，不得标记通过，也不得写成“合规”“许可问题已解决”。
- 不得写“来源缺口已关闭”“不存在隐藏源码”“§4 条件已全部落地”这类表述；也不得声称 AGPL 肯定不适用。允许且应当写的是：**随包字节即官方制品**（有哈希可比对）。
- 第三方仍不被改授；不改依赖坐标、不改第三方 POM、不为第三方重新许可。（历史记录：此前版本的自有内容曾按 ARR 声明，那些版本的历史文本不作追溯改写。）
- 仍需闭合的动作（均需用户另行授权，本轮不执行）：用 JDK 21.0.2（JitPack 构建环境）复现并确认官方字节是否可逐字节重建（初次重建与比较已完成，官方字节未复现）；取得权利人书面确认；替换为自研等价实现；或由专业法律意见处理 AGPL/LGPL 元数据冲突。
