# Tactical RPG

## English

**Turn Minecraft encounters into block-based, turn-driven tactical battles.**

Plan your movement, manage Action Points, and choose the right moment to attack,
defend, use a skill, or retreat. Tactical RPG keeps Minecraft's world and real
entities while giving combat a deliberate command-menu rhythm.

### Features

- **Shared ATB turn track:** combatants charge toward their turns, with live
  entity portraits showing their position on a shared timeline.
- **Action Point tactics:** move on reachable blocks, attack with melee weapons
  or bows/crossbows, defend, use your held item, place a block, or attempt to flee.
  AP carries over between turns, while repeated successful actions become more
  expensive within the same turn.
- **Skills and MP:** equipment and player data can grant skills with AP/MP costs,
  targeting rules, levels and cooldowns. The built-in Backstab example rewards
  rear melee positioning; extension interfaces support additional skills.
- **Position matters:** supported in-battle melee and arrow damage account for
  facing and height, while ranged targeting checks actual line of fire.
- **A tactical journal interface:** parchment, dark leather and metal details,
  live HP/AP/MP, a combat log, and nine explicit interface-size presets.
- **Minecraft-aware movement:** reachable-space previews, collision-checked
  routes, and choices between walking around or jumping down where available.
- **Enemy reinforcements:** nearby hostile mobs actively targeting the battle
  owner can join an ongoing fight within configured limits.

### Requirements and installation

- **Minecraft 1.21.1 — NeoForge only.** The current development and verification
  target is **NeoForge 21.1.216**. Other Minecraft versions and Forge ports are
  not available or claimed as supported.
- **ApricityUI 1.2.8 is required on the client.** Install it separately alongside
  Tactical RPG. It is not bundled into this download.

> **⚠ Required prerequisite version — read this before installing.**
> As of **2026-10-28**, the newest ApricityUI file listed on CurseForge for
> **Minecraft 1.21.1 / NeoForge** is **1.2.7**, which does **not** satisfy Tactical RPG's
> requirement of **ApricityUI ≥ 1.2.8**. Therefore:
>
> - The CurseForge "required dependency" relation only states *which project* is required; it does
>   **not** pin a version. An automatic dependency install can therefore give you 1.2.7 or older,
>   and Tactical RPG will refuse to load/satisfy its prerequisite.
> - Install **1.2.8** yourself from the official Maven repository, using the **original, unmodified
>   JAR**: <https://maven.sighs.cc/repository/maven-releases/com/sighs/ApricityUI-neoforge-1.21.1/1.2.8/ApricityUI-neoforge-1.21.1-1.2.8.jar>
>   — SHA-256 `7636ae43311b5401b13776799fb79cf4122b23db7fe4ae6ca03a94b14acbc0f6` (verify the digest
>   before dropping the file into `mods/`).
> - The version requirement will **not** be lowered, and the ApricityUI JAR is **not** copied into
>   this repository or bundled with the Tactical RPG download.
>
> This "newest is 1.2.7" statement is an **observation of the visible file list on 2026-10-28**; it is
> not a claim that ApricityUI has no alpha or other update channel. Consult its project page for the
> authoritative publication state.
- **Dungeons Perspective 3.6.3 is optional on the client.** This is the camera
  integration test target, not a required dependency. Camera behavior without
  that perspective has known limitations.
- Use matching Tactical RPG versions on the client and server. A dedicated
  server does not need the client-only UI or optional camera mod.
- **0.23.1** (content identical to the 0.23.0 candidate that was never published) raises the battle
  protocol from 6 to **8** and the separate skill channel to **3**. A 0.22.2 peer and a 0.23.x peer
  refuse to mix; update both sides together. Tactical RPG itself is client **and** server side;
  only ApricityUI and the optional camera mod are client-only.

Install the release JAR in your `mods` folder together with the required
client dependency. Use only dependency files matching Minecraft 1.21.1 and
NeoForge; consult the linked dependency projects before downloading.

### Getting started

By default, enemies targeting you can start a tactical battle automatically.
For a controlled test, use `/tactical battle force` with the required command
permissions. During your turn, choose an action from the command menu and
click an eligible position or target. Normal WASD movement is suspended during
combat. Press **E** for the vanilla inventory; battle item use is performed
through the command menu. Configuration lives in `config/tactical-common.toml`.

### Current scope

**Publication status of this specific version:** this description accompanies **0.23.1**. The 0.23.0
release preflight failed (CurseForge lists three version entries named `1.21.1`) and that run
**uploaded nothing**; its tag is unchanged. The upload status of 0.23.1 is whatever its CurseForge
file page and the corresponding run show; this page does not predict it and makes no statement
about which other files the project page does or does not list.

**What is verifiable locally:** the last formal build in this repository is
**0.22.2** (single battle, protocol 6) and it is still under the older
**All Rights Reserved** terms; its JAR also still contained the LXGW Marker Gothic
font file. **Its external publication status is unknown here.** The validation
records of that round state that no CurseForge upload happened, and the older
GitHub release is 0.21.0, so this page does not claim that 0.22.2 is downloadable
on CurseForge. Everything described below as 0.23.1 applies to that release candidate: it has not
been uploaded to this project and must not be presented as published. The **LGPL-3.0-only** licence and the removal of
the font file apply **only** to that unpublished candidate. The candidate is Minecraft 1.21.1 / NeoForge 21.1.216, main
protocol `8`, separate skill protocol `3`, and it still requires ApricityUI
1.2.8 as a client prerequisite (Dungeons Perspective 3.6.3 is an optional
camera/verification target, not a dependency).

The candidate adds two things on top of the last formal release: **several
battles running at the same time** (per-session keys, conservative AABB
rejection when zones overlap in one dimension) and **joining a nearby battle
directly from the in-game list** (explicit opt-in; no invitation command
required). Inside one battle several real players share a turn track and battle
state while each keeps separate AP, MP, cooldowns, action costs, movement and
defence, with a default limit of four players.

Verified scope of the candidate, stated narrowly: two real clients have run the
lifecycle matrix (leaving, dying, disconnecting, reconnecting without being
auto-joined), container/permission boundaries, busy-boundary refusal (an
invitation or a direct join arriving while another member is mid-move is refused
and succeeds once idle), two simultaneous battles each advancing only in its own
session (journal sequencing per session), and vanilla-inventory/context
interactions on both sides. **Not verified**: four or more real players, packet
volume at load, mixed-version directions other than the tested one, screen-size
control regression, and camera/size behaviour outside the recorded runs.
Automated tests and a headless server self-test pass; that is not the same as a
full acceptance run.

**Not implemented** (long-term plans): same-battle play across dimensions,
persistent player factions, and PvP. Supported third-party creature AI is not
universal. Read each release note for its verified scope; this page is not a
promise of compatibility with all modpacks or camera setups.

### License and third-party components

The project's own code, documentation and artwork are licensed **LGPL-3.0-only**
(the repository `LICENSE` carries the complete GNU LGPL v3 text, and the
per-project copyright line is in `README.md` and `NOTICE.md`). That is the scope
this project can license on its own behalf; it does not relicense anything it
does not own.

Bundled or referenced third parties, unchanged:

- **YiRanExpressionLib 1.0.1** ships as a nested JarJar library under its own
  LGPL v3 terms. It is not relicensed here. Its licence text and the
  incorporated GNU GPL v3 text are in `META-INF/licenses/yiran-expression-lib/`
  inside the JAR. The nested binary is the **unmodified official JitPack
  `universal` build** of upstream commit `60584101e` (preserved locally under the
  normalized `1.0.1` coordinate), and `NOTICE.md` records that plus the upstream
  LGPL/AGPL metadata conflict and the fact that a local rebuild from that source
  is still not verified — open items, not resolved ones.
- **ApricityUI** and **Dungeons Perspective** are separate mods with their own
  authors, licences and download pages; they are not bundled.
- **Minecraft** and its assets remain Mojang/Microsoft property. Every page of the unpublished
  0.23.1 candidate selects the vanilla Minecraft font (`aui-font-mode=mc`) and its JAR ships no
  third-party font file. An isolated development-launcher client loaded a no-font candidate JAR
  and displayed the nearby-battle list and battle menu; this was not a formal-launcher or
  glyph-by-glyph audit. The historical 0.22.2 build still declared All Rights
  Reserved and still packaged `lxgw3500.ttf` (LXGW Marker Gothic 1.003, SIL OFL 1.1).

Read `LICENSE`, `NOTICE.md` and the `META-INF` licence folder of the release JAR
for the exact texts. This description states facts about licensing; it is not a
legal opinion and it does not claim that licensing compliance has been fully
resolved.

---

## 中文

**让 Minecraft 遭遇战变成基于方块、由回合驱动的战术战斗。**

规划移动路线、管理行动点，在合适的时机攻击、防御、使用技能或撤退。Tactical RPG 保留 Minecraft 的世界与真实实体，为战斗加入更从容的指令菜单与策略节奏。

### 主要特色

- **共享 ATB 行动轨道**：参战单位蓄力后进入自己的回合，实时实体肖像显示各自在共享时间轴上的位置。
- **行动点策略**：在可达方块间移动，使用近战武器、弓或弩攻击，防御、使用主手道具、放置方块或尝试逃跑。剩余 AP 可保留至后续回合，同一回合内重复成功行动会提高费用。
- **技能与 MP**：装备和玩家数据可授予带有 AP／MP 费用、目标规则、等级与冷却的技能。内置背刺示例鼓励从敌人后方进行近战，扩展接口支持添加更多技能。
- **位置影响伤害**：受支持的战斗内近战与箭矢伤害考虑朝向和高差，远程瞄准检查实际射线遮挡。
- **旅札式战术界面**：纸张、深色皮革与金属装饰，配合实时 HP／AP／MP、战斗记录和九档显式界面尺寸。
- **适应方块世界的移动**：提供可达区域预览、碰撞检查路线，以及在条件允许时绕行或跳下的选择。
- **敌人增援**：附近正在追击战斗拥有者的敌对生物，可在配置上限内加入进行中的战斗。

### 运行要求与安装

- **仅支持 Minecraft 1.21.1 / NeoForge**。当前开发与验证目标为 **NeoForge 21.1.216**；其他 Minecraft 版本及 Forge 移植尚未提供，也不宣称支持。
- **客户端必需 ApricityUI 1.2.8**，请与 Tactical RPG 一同单独安装；本模组不内置该前置。

> **⚠ 前置版本要求（安装前必读）**：截至 **2026-10-28** 查看，CurseForge 上 ApricityUI 的 **1.21.1 NeoForge 文件列表最新仍是 1.2.7**，**不满足**本模组要求的 `>= 1.2.8`。
>
> - CF 的“必需依赖”只声明是**哪个项目**，不锁定版本；自动安装可能给你 1.2.7 或更旧，本模组的前置版本要求因此无法满足。
> - 请自行从**官方 Maven** 下载 1.2.8 的**原始 JAR**：<https://maven.sighs.cc/repository/maven-releases/com/sighs/ApricityUI-neoforge-1.21.1/1.2.8/ApricityUI-neoforge-1.21.1-1.2.8.jar>，SHA-256 `7636ae43311b5401b13776799fb79cf4122b23db7fe4ae6ca03a94b14acbc0f6`，核对无误后再放入 `mods/`。
> - **不会**下调本模组的版本范围，也**不会**把 ApricityUI 的 JAR 复制进本仓库或本模组分发包。
>
> “最新为 1.2.7”是 **2026-10-28 的可见列表观察**，不是“ApricityUI 没有 alpha/更新通道”的断言；官方发布状态以其项目页为准。
- **客户端可选 Dungeons Perspective 3.6.3**。这是镜头集成的验证目标，而非必需前置；不使用该视角时，镜头行为仍有已知限制。
- 客户端与服务端请使用相同版本的 Tactical RPG。专用服务端不需要客户端 UI 前置或可选镜头模组。
- **0.23.1**（内容与未获发布的 0.23.0 候选相同）把战斗主协议由 6 升至 8，独立技能协议为 3。0.22.2 与 0.23.x 之间不会静默混连，请同时更新两端。本模组为客户端 + 服务端；只有 ApricityUI 与可选镜头模组是纯客户端。

将正式发布的 JAR 与必需客户端前置放入 `mods` 文件夹。下载依赖时，请确认文件对应 Minecraft 1.21.1 和 NeoForge，并查看各依赖项目的说明。

### 快速开始

默认情况下，以玩家为攻击目标的敌人可以自动触发战术战斗。拥有相应命令权限时，可使用 `/tactical battle force` 进行受控测试。

自己的回合中，在指令菜单选择行动，再点击有效位置或目标。战斗期间暂停普通 WASD 移动；按 **E** 打开原版背包，战斗道具使用通过指令菜单执行。配置文件位于 `config/tactical-common.toml`。

### 当前范围与限制

**本版本的发布状态**：本页说明对应 **0.23.1**。0.23.0 的发布预检失败（CurseForge 目录里有三条名为 `1.21.1` 的版本行），该次**没有上传任何文件**，其 tag 不变；0.23.1 的上传状态以其 CurseForge 文件页与对应运行结果为准，本页不预判。本页不支持“0.23.1 已可在 CurseForge 下载”这种读法；本页同样不判断该项目页面上已有或没有其它文件。

**本地可核实的事实**：本仓库上一个正式构建是 **0.22.2**（单场、协议 6），它仍是旧的 **All Rights Reserved** 授权，其 JAR 当时也仍然打包了 LXGW Marker Gothic 字体文件；**它的外部发布状态在此未知**——那一轮验证记录写明未发生 CurseForge 上传，更早的 GitHub Release 是 0.21.0，因此本页不声称 0.22.2 可在 CurseForge 下载。下面所有关于 0.23.1 的描述都属于该发布候选：它没有上传到本项目页面，不得表述为已上架。**LGPL-3.0-only** 授权与字体文件不再打包**仅**适用于该未发布候选。该候选对应 Minecraft 1.21.1 / NeoForge 21.1.216，战斗主协议 `8`、独立技能协议 `3`，仍以 ApricityUI 1.2.8 作为必需客户端前置（Dungeons Perspective 3.6.3 是可选镜头/验证目标，不是依赖）。

候选在上一正式版之上新增两类能力：**多场并行**（按会话键控；同一维度内战区包围盒重叠时按保守 AABB 拒绝新场）与**从附近战斗列表直接加入**（显式同意，不依赖邀请命令）。同一场战斗内多名真实玩家共享行动轨道与公共战况，而每名玩家的 AP、MP、冷却、行动费用、移动与防御各自独立，默认上限 4 人。

候选的**已验证范围**（从严表述）：两个真实客户端跑过生命周期矩阵（离场／死亡／断线／重连不自动参战）、容器与权限边界、busy 边界拒绝（他人移动结算期间到来的邀请或直接加入被拒，idle 后同一操作成功）、两场同时进行且各自只在自己会话内推进（journal 按会话递增），以及双端原版物品/右键上下文交互。**未验证**：4 人以上真实玩家、负载下的包量、除已测方向外的其它混版方向、界面尺寸控件回归、以及记录运行之外的镜头与水印行为。自动化测试与无头服务端自检通过，但这不等于完整的双端验收。

**尚未实现**（长期计划）：跨维度同场战斗、玩家阵营持久化与 PvP。对其他模组生物与 AI 的兼容并非通用。具体已验证范围以各版本发布说明为准，本描述不承诺适配所有整合包或镜头组合。

### 许可与第三方组件

项目自有代码、文档与美术采用 **LGPL-3.0-only**（仓库 `LICENSE` 为完整的 GNU LGPL v3 正文，项目自身版权行写在 `README.md` 与 `NOTICE.md`）。这是本项目仅就自有权利作出的授权，不包含、也不改写任何第三方作品。

随包或引用的第三方（均未改动）：

- **YiRanExpressionLib 1.0.1** 以 JarJar 嵌套库形式随包，按其自身 LGPL v3 条款授权，本项目不重新许可它；其许可正文与并入的 GNU GPL v3 正文位于 JAR 内 `META-INF/licenses/yiran-expression-lib/`。随包二进制是上游提交 `60584101e` 的**官方 JitPack `universal` 未修改构建**（本地按归一化的 `1.0.1` 坐标保留），`NOTICE.md` 记录该来源、上游 LGPL／AGPL 元数据冲突，以及“尚未验证可由该源码本地重建同一字节”——这些是未闭合项，不是已解决项。
- **ApricityUI** 与 **Dungeons Perspective** 是各自作者的独立模组，各有自己的许可与下载页，不随本包捆绑。
- **Minecraft** 及其资源仍归 Mojang/Microsoft 所有。未发布的 0.23.1 候选每个页面都选择 Minecraft 原版字体（`aui-font-mode=mc`）且 JAR 内不随附第三方字体文件；隔离开发启动器客户端曾加载该无字体候选 JAR 并显示附近战斗列表和战斗菜单；这不是正式启动器环境测试，也不是逐字形检查。历史构建 0.22.2 仍按 All Rights Reserved 声明，且当时 JAR 内仍打包 `lxgw3500.ttf`（LXGW Marker Gothic 1.003，SIL OFL 1.1）。

确切文本以 `LICENSE`、`NOTICE.md` 与发布 JAR 的 `META-INF` 许可目录为准。本节陈述许可事实，不构成法律意见，也不声称许可合规已完全解决。

Minecraft 与第三方模组仍归各自权利人所有。
