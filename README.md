# Tactical RPG

把 Minecraft 战斗改造成**基于方块的回合制战术 RPG**的 NeoForge 模组。

- 目标平台：**Minecraft 1.21.1 / NeoForge 21.1.216**，构建需要 JDK 21（`common` 模块编译目标为 Java 17）。
- 客户端**没有 UI 前置**：所有界面、面板与 HUD 都由本模组原生绘制。已发布的 0.23.1 及更早版本仍需要 [ApricityUI](https://github.com/Tower-of-Sighs/AUI) 1.2.8，那是历史版本的事实，不因当前源码树而改变。
- 本仓库是**源码仓库**：源码公开不等于对应版本的二进制已经上传到 CurseForge 或 GitHub Release，二者以各自的回执为准。
- 1.20.1 Forge、26.1.2 NeoForge 尚未移植或测试。

## 当前源码树

`gradle.properties` 的 `mod_version` 是 **0.28.0**，产物名 `tactical-neoforge-1.21.1-0.28.0.jar`。

网络协议：主协议 **Tactical 12**、独立技能通道 **Skill 4**、战斗记录通道 **BattleLog 2**。客户端与服务端必须同时更新到同一版本；混版会被通道协商明确拒绝，不会静默降级。协议较旧的已发布版本（0.23.1 为 `8` / `3`）与本源码树**不能混连**。

逐版本改动与每轮实际跑过的检查见 [更新记录](CHANGELOG.md)。

## 玩法概要

- **自动进入战斗**：启用自动触发且无逃跑保护时，敌对生物仇恨你会开启回合制战斗；也可用 `/tactical battle force` 手动开战。
- **ATB 行动条**：每个单位按先攻速度充能，顶部共享轨道用肖像位置显示进度。
- **玩家回合弹出九项指令菜单**：属性、移动、攻击、技能、道具、方块、防御、逃跑、结束回合；费用随本回合成功操作次数上涨，只有成功才扣 AP。
- **移动是点击落点**：服务端计算路线并检查 AP，必要时提供绕路或跳下的选择；草花等无碰撞装饰不会挡住可站立格。
- **位置与高度修正伤害**、**武器射程与视线**、**友军阵营与范围预览**、**技能与 MP** 均按服务端权威状态结算；客户端只画预览。
- **同伴（0.26 起）**：已驯服的站立狼由主人在其自身回合手动指挥移动、近战、技能、防御与结束回合，拥有独立回合与 AP。
- **多人合作 PvE（0.23 起）**：显式邀请与附近战场直接加入两条路径，多场可并行（不同维度各自开战，同维重叠按保守 AABB 拒绝），每条战斗意图都由连接真实身份 + 会话 UUID + 服务端回合代数校验。
- **敌对生物的战术**：掠夺者的弩会真实装填并在同一回合射出，流浪者/沼骸使用原生药箭，女巫在自身回合饮药与投瓶，卫道士按原版铁斧行动并在必要时破木门。**0.28 新增烈焰人**：空中寻找合法射击位置，蓄力后连发三枚真实小火球，整轮基础 20 AP、只计一次付费；战斗火球不会伤及旁观者、其它战场或点燃地形。

## 命令

| 命令 | 作用 |
|------|------|
| `/tactical battle start` | 对附近有仇恨的敌对生物开战 |
| `/tactical battle force` | 强制接管附近所有敌对生物（测试用） |
| `/tactical battle stop` | 强制结束战斗 |
| `/tactical turn end` | 结束玩家回合（菜单也有按钮） |
| `/tactical flee` | 尝试逃跑（菜单亦有按钮，50 AP；失败耗尽剩余 AP 并结束回合） |
| `/tactical ap get <targetPlayer>` | 管理员查询战斗拥有者当前 AP（权限等级 2，另有 `ap add` / `ap remove`） |
| `/tactical character` | 打开自己的角色页（只回给发起的连接，无目标参数、无管理员要求） |
| `/tactical status` | 报告当前是否有战斗、人数 / 容量 / 回合代数或并行场数 |
| `/tactical auto on\|off` | 开关「被仇恨时自动进入战斗」 |

多人协作还有 `/tactical battle invite <player>`、`accept`、`decline`、`leave`。其余子命令与参数在游戏内用 `/tactical` 补全查看，规则总览见 [设计说明](docs/DESIGN.md)。

## 安装 / 试玩

需要 **Minecraft 1.21.1 / NeoForge 21.1.216** 的客户端与对应服务端，由本仓库自行构建或使用同一版本的已发布文件。客户端与服务端必须同版本。

```mcfunction
/summon zombie ~3 ~ ~
/tactical battle force
```

玩家回合会自动弹出右侧菜单；点「移动」后点浅蓝格子，点「攻击」后点敌人，点「防御」后选择四方向之一并确认（25 AP，减伤 50%，副手盾牌 70%），返回则取消且不扣费。

配置文件为 `config/tactical-common.toml`（首次运行后生成）。

## 构建

```bash
bash ./gradlew :common:test
bash ./gradlew :neoforge-1.21.1:build
bash ./gradlew :neoforge-1.21.1:runClient      # 独立开发客户端
bash ./gradlew :neoforge-1.21.1:runServerSelfTest   # 无头端到端自检，跑完自动停服
```

产物：`neoforge-1.21.1/build/libs/tactical-neoforge-1.21.1-<版本>.jar`。

只用仓库自带 wrapper，不依赖全局 Gradle。嵌套第三方库由 `libs/maven` 提供，不需要额外下载。

## 模块

| 模块 | 说明 |
|------|------|
| `common` | 纯 Java 17：网格、最短 AP 路径、ATB 调度、AP 与位置伤害公式、常量与 DTO |
| `neoforge-1.21.1` | 适配层：战斗会话、网络、原生界面与 HUD、世界渲染、输入、配置与属性 |

## 测试与验收边界

本仓库的自动化门是：

```bash
bash ./gradlew :common:test :neoforge-1.21.1:build --no-daemon
python -m unittest discover -s tools -p test_release_pipeline.py -v
python tools/check_release.py
```

当前源码树这一轮的计数是 `:common:test` **86** 项与 `:neoforge-1.21.1:test` **789** 项 Java 测试、以及 **51** 项离线发布脚本测试，均为零失败。构建通过、打包门通过、服务端无头夹具通过都**不**等于真实客户端交互验收；真实鼠标/键盘回调、镜头与界面观感需要独立的人工运行。历史各版本的验证范围见 [docs/validation](docs/validation)，各版本「未宣称覆盖什么」写在 [更新记录](CHANGELOG.md) 对应章节里。

## 许可

项目自有代码、文档与美术采用 **LGPL-3.0-only**：根 [LICENSE](LICENSE) 为完整的 GNU LGPL v3 正文；本项目仅就自有权利作出该授权。版权与归属：`Copyright (c) 2026 QLNPLUS and contributors`。署名、许可文本与第三方声明必须随分发保留。

第三方不被本项目重新许可：[NOTICE.md](NOTICE.md) 记录 YiRanExpressionLib 的嵌套 LGPL v3 文本、上游 LGPL／AGPL 元数据差异，以及替换兼容库和为调试该替换所需逆向工程的例外。随包嵌套库是上游官方 JitPack `universal` 制品（提交 `60584101e`）的原始字节，本地只把坐标/路径归一化为 `1.0.1`；"可由该源码本地重建同一字节"尚未验证，元数据冲突也未解决，**因此仍不能写成本项目已合规**。来源追溯与未闭合边界见 [许可审计](docs/license-audit.md) 与 [嵌套库说明](libs/maven/README.md)。不得删除第三方声明或以项目许可覆盖第三方权利；这不是法律合规保证。

## 文档

| 文档 | 内容 |
|------|------|
| [设计说明](docs/DESIGN.md) | 系统结构与规则总览 |
| [扩展战斗](docs/extending-battles.md) | 其他模组接入战斗的接口 |
| [技能与 MP](docs/skills.md) | 技能来源、瞄准与费用 |
| [同伴接入](docs/companions.md) | 同伴适配器契约 |
| [长期路线图](docs/ROADMAP.md) | 已实现与未实现的范围 |
| [发布配置](docs/releasing.md) | 构建 / 显式发布工作流与必需配置 |
| [CurseForge 描述](docs/curseforge.md) | 英文项目描述正文 |

## 构建与发布工作流

[build-release.yml](.github/workflows/build-release.yml) 在普通分支 push 和 PR 时只构建、测试并检查发布包，**不提供发布凭据、不创建 Release、不上传 CurseForge**。只有与 `gradle.properties` 版本精确对应的 `v<版本>-neoforge-1.21.1` 标签，或显式手动运行，才进入发布作业；缺 CurseForge 配置会阻止发布而不是假报成功。

## 路线图

已实现：网格战斗骨架、ATB 与 AP、弓弩与高度/视线修正、屏障与仇恨清除、原生界面与 HUD、多人合作与多场并行、同伴指挥、远程怪物与女巫/卫道士战术。

未实现：PvP、玩家阵营持久化、跨维度同场战斗、外部观众、断线后自动恢复参战、1.20.1 Forge、26.1.2 NeoForge。详见 [docs/ROADMAP.md](docs/ROADMAP.md)。
