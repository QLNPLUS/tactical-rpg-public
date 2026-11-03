# Tactical RPG

## English

**Turn Minecraft encounters into block-based, turn-driven tactical battles.**

Plan your movement, manage Action Points, and choose the right moment to attack,
defend, use a skill, or retreat. Tactical RPG keeps Minecraft's world and real
entities while giving combat a deliberate command-menu rhythm — and since 0.23.1
several players can fight in the same battle, with several battles running at once.
From 0.24.0 the whole interface is drawn by the mod itself, so there is no UI
framework to install alongside it.

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
- **A native tactical interface:** dark stone-slab panels with gold accents, live
  HP/AP/MP, a combat log and a shared turn track, all drawn by the mod's own
  layout and drawing code. Eight explicit 5% interface-size presets (80%–115%)
  scale the native layout and type size; the roster and the journal HUD can also
  be resized on their own and keep that size, and theme colours and range colours
  are set separately. The journal opens into a continuous scroll viewport and
  keeps its scroll and collapsed state.
- **Bilingual, switch live:** mod-owned menus, HUDs, settings and battle messages
  follow each player's own client language on the same server, and change without
  restarting or reconnecting. Player names and custom item names stay literal.
- **Dynamic skills:** the skill list, its costs, cooldowns and targeting hints are
  generated from the skills you actually have available.
- **Portraits and rows target real entities:** hovering and clicking a portrait or
  a row in the roster and turn track lands on that entity, not on a fixed slot.
- **Minecraft-aware movement:** reachable-space previews, collision-checked
  routes, and choices between walking around or jumping down where available.
- **Enemy reinforcements:** nearby hostile mobs actively targeting the battle
  owner can join an ongoing fight within configured limits. At the participant
  limit a refused creature is isolated out of the running battle instead of being
  inserted into a full battlefield.

### Multiplayer

- **One battle, several players.** Participants share the turn track, the queue
  and the combat log, while each keeps their own AP, MP, cooldowns, action costs,
  movement and defence. The default limit is four players per battle
  (`maxBattlePlayers`, configurable 1-16); that is a configuration ceiling, not a
  tested capacity claim.
- **Several battles at once.** Battles are keyed per session, so different
  dimensions can each run their own. Two battles whose zones overlap inside one
  dimension are refused instead of being merged.
- **Join from the nearby-battle list.** An in-game list shows the battles you are
  eligible for; confirming joins directly. Explicit invitations work too:
  `/tactical battle invite <player>`, then `accept` / `decline` / `leave`.
- **Safety boundaries.** Joining is refused while someone is mid-action (an
  animation, a projectile in flight or an occupied target cell) and the invitation
  stays available for a retry. A player joining mid-battle cannot carry items
  through vanilla containers until their own turn.

### Requirements and installation

- **Minecraft 1.21.1 — NeoForge only.** The current development and verification
  target is **NeoForge 21.1.216**. Other Minecraft versions and Forge ports are
  not available or claimed as supported.
- **No UI dependency is required.** From the native-interface line (0.24.0) every
  screen and HUD is drawn by Tactical RPG itself. Released versions up to 0.23.1
  required ApricityUI 1.2.8 on the client; that requirement belongs to those
  builds only and is not part of the current one.
- **Dungeons Perspective 3.6.3 is optional on the client.** This is the camera
  integration test target, not a required dependency. Camera behavior without
  that perspective has known limitations.
- Use matching Tactical RPG versions on the client and server. A dedicated
  server does not need the client-only UI or optional camera mod.
- **The native-interface build (0.24.0) raises the battle protocol to 10**, the
  separate skill channel to 4 and adds a battle-log channel at 2. A 0.23.1 peer
  (protocol 8 / 3) and a 0.24.0 peer refuse to mix — update both sides together.

Place the Tactical RPG JAR for your version in the `mods` folder. No UI
framework is needed; add the optional mods you actually use. Use only files
matching Minecraft 1.21.1 and NeoForge, and consult the linked project pages
before downloading.

### Getting started

By default, enemies targeting you can start a tactical battle automatically.
For a controlled test, use `/tactical battle force` with the required command
permissions. During your turn, choose an action from the command menu and
click an eligible position or target. Normal WASD movement is suspended during
combat. Press **E** for the vanilla inventory; battle item use is performed
through the command menu. Configuration lives in `config/tactical-common.toml`.

### Tested scope and limits

Verified on two real clients: leaving, dying, disconnecting and reconnecting
without being auto-joined; container and permission boundaries; refusal at the
busy boundary followed by a successful retry once idle; and two battles advancing
only in their own session.

The native-interface line was additionally run on real clients without any UI
framework: a development client started, entered a battle and used the native
menu, HUDs, skill and facing pages; English and Chinese page layouts were checked
at representative sizes in a limited run; two clients on one server switched
language live without reconnecting; vanilla-inventory real pickup and place were
verified with server-side conservation; and the camera integration was checked
with a positive/negative comparison. The retired ApricityUI inventory page and its
right-click context menu are not reachable in this version and are not claimed as
tested here.

**Not verified:** four or more real players, packet volume under load,
mixed-version combinations other than the tested one, an exhaustive sweep of every
page at every interface size, camera behaviour outside the recorded runs, and the
formal launcher path. Automated tests and a headless server self-test pass, which
is not the same as a full acceptance run.

**Not implemented** (long-term plans): same-battle play across dimensions,
persistent player factions and PvP. Compatibility with other mods' creature AI is
not universal. This page describes verified behavior; it is not a promise of
compatibility with every modpack or camera setup.

### License and third-party components

The project's own code, documentation and artwork are licensed **LGPL-3.0-only**
(the repository `LICENSE` carries the complete GNU LGPL v3 text, and the
per-project copyright line is in `README.md` and `NOTICE.md`). That is the scope
this project can license on its own behalf; it does not relicense anything it does
not own.

Bundled or referenced third parties, unchanged:

- **YiRanExpressionLib 1.0.1** ships as a nested JarJar library under its own
  LGPL v3 terms. It is not relicensed here. Its licence text and the incorporated
  GNU GPL v3 text are in `META-INF/licenses/yiran-expression-lib/` inside the JAR.
  The nested binary is the **unmodified official JitPack `universal` build** of
  upstream commit `60584101e` (preserved locally under the normalized `1.0.1`
  coordinate). `NOTICE.md` records that plus the upstream LGPL/AGPL metadata
  conflict and the fact that a local rebuild from that source is still not
  verified — open items, not resolved ones.
- **Dungeons Perspective** is a separate mod with its own author, licence and
  download page; it is not bundled. ApricityUI, required by releases up to 0.23.1,
  is likewise a separate mod and is neither required nor bundled by this build.
- **Minecraft** and its assets remain Mojang/Microsoft property. The native
  interface draws with Minecraft's own font renderer, and the JAR ships no
  third-party font file. (0.23.1's pages selected the same vanilla font through
  `aui-font-mode=mc`; that markup no longer exists.) The historical 0.22.2 build
  still declared All Rights Reserved and still packaged `lxgw3500.ttf`
  (LXGW Marker Gothic 1.003, SIL OFL 1.1).

Read `LICENSE`, `NOTICE.md` and the `META-INF` licence folder of the release JAR
for the exact texts. This description states facts about licensing; it is not a
legal opinion and it does not claim that licensing compliance has been fully
resolved.

---

## 中文

**让 Minecraft 遭遇战变成基于方块、由回合驱动的战术战斗。**

规划移动路线、管理行动点，在合适的时机攻击、防御、使用技能或撤退。Tactical RPG 保留 Minecraft 的世界与真实实体，为战斗加入更从容的指令菜单与策略节奏；自 0.23.1 起，多名玩家可以打同一场战斗，也可以同时进行多场；自 0.24.0 起全部界面由本模组自己绘制，不再需要任何 UI 前置。

### 主要特色

- **共享 ATB 行动轨道**：参战单位蓄力后进入自己的回合，实时实体肖像显示各自在共享时间轴上的位置。
- **行动点策略**：在可达方块间移动，使用近战武器、弓或弩攻击，防御、使用主手道具、放置方块或尝试逃跑。剩余 AP 可保留至后续回合，同一回合内重复成功行动会提高费用。
- **技能与 MP**：装备和玩家数据可授予带有 AP／MP 费用、目标规则、等级与冷却的技能。内置背刺示例鼓励从敌人后方进行近战，扩展接口支持添加更多技能。
- **位置影响伤害**：受支持的战斗内近战与箭矢伤害考虑朝向和高差，远程瞄准检查实际射线遮挡。
- **原生战术界面**：深色石板面板配金色装饰，实时 HP／AP／MP、战斗记录与共享行动轨道全部由本模组自己的布局与绘制代码画出；八档显式界面尺寸（80%–115%，5% 一档）作用于原生布局与字号，名册与纪事还可各自拖动尺寸并保持，主题配色与射程颜色分别设置。纪事展开为连续滚动视口，滚动与折叠状态可保持。
- **双语与热切**：模组自有的菜单、HUD、设置与战斗提示按各客户端自己的语言解析，在同一服务器上可以热切，不必重启或重连；玩家名和自定义物品名保留原文。
- **动态技能列表**：技能、费用、冷却与目标提示按当前实际可用技能生成。
- **肖像与行指向真实实体**：名册与行动轨道上的头像和行悬停、点击落在对应实体目标上，而不是固定槽位。
- **适应方块世界的移动**：提供可达区域预览、碰撞检查路线，以及在条件允许时绕行或跳下的选择。
- **敌人增援**：附近正在追击战斗拥有者的敌对生物，可在配置上限内加入进行中的战斗；达到参战上限时，被拒绝的生物会被隔离出已开始的战斗，不再插入满员战场。

### 多人战斗

- **一场战斗，多名玩家。** 参战者共享行动轨道、队列与战斗记录，而各自的 AP、MP、冷却、行动费用、移动与防御互相独立。单场默认上限 4 人（`maxBattlePlayers`，可配 1–16）；这是配置上限，不是已验证的承载量。
- **同时进行多场。** 战斗按会话键控，不同维度可以各自开战；同一维度内战区重叠的两场会被拒绝，而不是合并成一场。
- **从附近战斗列表加入。** 游戏内列表会列出你有资格加入的战斗，确认即加入；也可以用显式邀请：`/tactical battle invite <player>`，随后 `accept` / `decline` / `leave`。
- **安全边界。** 有人正在结算动作（动画、在飞投射物或目标格被占用）时加入会被拒绝，邀请保留以便重试；中途加入的玩家在自己回合开始前无法通过原版容器搬运物品。

### 运行要求与安装

- **仅支持 Minecraft 1.21.1 / NeoForge**。当前开发与验证目标为 **NeoForge 21.1.216**；其他 Minecraft 版本及 Forge 移植尚未提供，也不宣称支持。
- **无需任何 UI 前置**：界面原生线（0.24.0）起，全部界面由本模组自己绘制。已发布的 0.23.1 及更早版本需要客户端 ApricityUI 1.2.8，那只属于那些版本。
- **客户端可选 Dungeons Perspective 3.6.3**。这是镜头集成的验证目标，而非必需前置；不使用该视角时，镜头行为仍有已知限制。
- 客户端与服务端请使用相同版本的 Tactical RPG。专用服务端不需要客户端 UI 前置或可选镜头模组。
- **0.24.0（原生界面构建）把战斗主协议升至 10**，独立技能协议升至 4，并新增战斗记录通道 2。0.23.1（协议 8／3）与 0.24.0 之间不会静默混连，请同时更新两端。

把对应版本的 Tactical RPG JAR 放入 `mods` 文件夹，不需要任何 UI 前置；可选模组按需另放。下载时请确认文件对应 Minecraft 1.21.1 和 NeoForge，并查看各项目页面的说明。

### 快速开始

默认情况下，以玩家为攻击目标的敌人可以自动触发战术战斗。拥有相应命令权限时，可使用 `/tactical battle force` 进行受控测试。

自己的回合中，在指令菜单选择行动，再点击有效位置或目标。战斗期间暂停普通 WASD 移动；按 **E** 打开原版背包，战斗道具使用通过指令菜单执行。配置文件位于 `config/tactical-common.toml`。

### 已验证范围与限制

在两个真实客户端上验证过：离场、死亡、断线，以及重连后不自动参战；容器与权限边界；busy 边界（动作结算期间加入被拒，idle 后同一操作成功）；两场战斗各自只在自己的会话内推进。

原生界面线另外在真实客户端上做过有限运行（无任何 UI 前置）：开发客户端启动并进入战斗，使用了原生命令菜单、HUD、技能页与朝向页；中英文页面布局按代表尺寸逐页检查过（有限运行）；同一服务器上两个客户端不重连热切语言；原版背包真实拾放并有服务端守恒；镜头集成做了正负对照。已退役的 AUI 物品栏页与其右键上下文菜单在本版不可达，本页不声称这些动作在本版被测过。

**未验证**：4 人以上真实玩家、负载下的包量、除已测组合外的其它混版方向、每一档界面尺寸下的全页面穷举、记录运行之外的镜头行为，以及正式启动器路径。自动化测试与无头服务端自检通过，但这不等于完整的双端验收。

**尚未实现**（长期计划）：跨维度同场战斗、玩家阵营持久化与 PvP。对其他模组生物与 AI 的兼容并非通用。本页描述已验证行为，不承诺适配所有整合包或镜头组合。

### 许可与第三方组件

项目自有代码、文档与美术采用 **LGPL-3.0-only**（仓库 `LICENSE` 为完整的 GNU LGPL v3 正文，项目自身版权行写在 `README.md` 与 `NOTICE.md`）。这是本项目仅就自有权利作出的授权，不包含、也不改写任何第三方作品。

随包或引用的第三方（均未改动）：

- **YiRanExpressionLib 1.0.1** 以 JarJar 嵌套库形式随包，按其自身 LGPL v3 条款授权，本项目不重新许可它；其许可正文与并入的 GNU GPL v3 正文位于 JAR 内 `META-INF/licenses/yiran-expression-lib/`。随包二进制是上游提交 `60584101e` 的**官方 JitPack `universal` 未修改构建**（本地按归一化的 `1.0.1` 坐标保留）。`NOTICE.md` 记录该来源、上游 LGPL／AGPL 元数据冲突，以及“尚未验证可由该源码本地重建同一字节”——这些是未闭合项，不是已解决项。
- **Dungeons Perspective** 是独立作者的模组，有自己的许可与下载页，不随本包捆绑；0.23.1 及更早版本需要的 ApricityUI 同样是独立模组，当前构建既不需要也不捆绑它。
- **Minecraft** 及其资源仍归 Mojang/Microsoft 所有。界面用 Minecraft 自己的字体渲染器绘制，JAR 内不随附第三方字体文件（0.23.1 的页面通过 `aui-font-mode=mc` 选择同一款原版字体，那段标记已随页面一起退役）。历史构建 0.22.2 仍按 All Rights Reserved 声明，且当时 JAR 内仍打包 `lxgw3500.ttf`（LXGW Marker Gothic 1.003，SIL OFL 1.1）。

确切文本以 `LICENSE`、`NOTICE.md` 与发布 JAR 的 `META-INF` 许可目录为准。本节陈述许可事实，不构成法律意见，也不声称许可合规已完全解决。

Minecraft 与第三方模组仍归各自权利人所有。
