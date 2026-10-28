# 0.21 技能与 MP 扩展

0.21 的技能定义同时支持数据文件和 Java 接口。技能运行在服务端，客户端只显示服务端发来的技能状态、费用、冷却和预览；真正释放时会重新检查装备、等级、目标、范围、视线、AP、MP和冷却。

## MP

默认最大 MP 为 100，新增原生同步属性：

- `tactical:max_mp`：最大 MP，默认 100。
- `tactical:mp_recovery`：战斗中每个后续自身回合的 MP 恢复量，默认 0。

没有保存 MP 记录的实体第一次访问 MP 时初始化为 `min(100, 最大MP)`。进入战斗不会补满。战斗外默认每 200 ticks 回复 20 点，最多到最大值；这些值可在 `config/tactical-common.toml` 的 `[mana]` 中调整。玩家死亡复制保留当前 MP。技能消耗 MP，但不增加 AP 连续操作计数；成功技能的 AP 费用加入既有攻击、道具、放置共用涨费计数。

Java 代码使用 `ManaResources.current(actor)`, `maximum`, `recovery`, `spend`, `add`；使用 `ManaAttributes.MAX_MP` 与 `MP_RECOVERY` 修改实体属性。所有资源检查和修改必须在服务端线程进行。

## 技能文件

数据包技能放在：`data/<namespace>/tactical_skills/<id>.json`。服务器配置技能可放在 `config/tactical/skills/<name>.json`。配置目录定义会覆盖同 ID 的数据包定义。每次重载先完整编译和校验，任何文件失败都保留上一份可用快照。

主要字段：`name`、`description`、`max_level`、`aim`（`entity`/`ground`/`self`）、`targets`（`enemy`/`ally`/`self`）、`anchor`（`caster`/`target`）、`range`、`min_range`、`vertical_min`、`vertical_max`、`line_of_sight`、`area`、`shape`、`ap_cost`、`mp_cost`、`cooldown`、`require`、`damage`、`effects`、`conditions`、`attributes`、`enchantments`。

范围局部坐标使用 X/Z 平面：面向为 +Z，右侧为 +X；yaw 0 为 +Z，yaw 90 为 -X。支持 `circle`、`rectangle`、`line`、`right_triangle`、任意三点 `triangle`，以及 `union`、`intersection`、`difference`。范围可以指定 `offset_x`、`offset_z` 和 `yaw`，尺寸和坐标可以是表达式字符串。解析和预览限制为最大范围 64、最多 4096 个候选格、最多 100 个组合节点和有限嵌套深度。

示例：

```json
{
  "name": "穿刺线", "description": "击中前方直线上的敌人", "max_level": 3,
  "aim": "ground", "targets": ["enemy"], "anchor": "caster", "area": true,
  "shape": {"type":"line", "width":"1", "length":"4 + level"},
  "range": "8", "line_of_sight": true, "ap_cost": "8 + level", "mp_cost": "12", "cooldown": "1",
  "damage": {"mode":"formula", "type":"minecraft:magic", "amount":"attack_damage * (1 + 0.2 * level)"},
  "effects": []
}
```

表达式由 YiRanExpressionLib 1.0.1 编译。变量会显式检查，运行时使用局部绑定并拒绝非有限结果；未播种的 `random()` 不允许用于技能。变量包括 `base`、`level`、施法者和目标生命/护甲/攻击属性、`ap`、`mp`、`max_mp`、`distance`、`height_difference` 和 `direction_multiplier`。`attributes` 可将原生属性映射到自定义变量。

## 背刺示例

内置 `tactical:backstab` 由主手附魔或角色 NBT 授予：最高 5 级、目标必须是敌人、必须从背后、需要近战武器，AP 15、MP 10、冷却 1。技能倍率为 `1 + 0.25 * level`，与已有背面 ×1.5 位置倍率独立相乘。附魔文件是 `data/tactical/enchantment/backstab.json`。角色持久数据可使用：

```nbt
{tactical:{skills:[{id:"tactical:backstab",level:3}]}}
```

装备的 `custom_data` 使用同样的 `tactical.skills` 列表。玩家主手、副手和六个护甲槽都会扫描，多个来源取最高等级并限制在技能最大等级。换装会刷新技能列表，冷却不会因换装重置。

## Java 扩展

```java
ResourceLocation id = ResourceLocation.fromNamespaceAndPath("example", "arc_blast");
SkillRegistry.register(SkillDefinition.builder(id)
    .name("Arc Blast").description("A validated area skill").maxLevel(5)
    .aim(SkillDefinition.Aim.GROUND).targets(SkillDefinition.Relation.ENEMY)
    .costs("10 + level", "15").cooldown("2")
    .damage(ResourceLocation.fromNamespaceAndPath("minecraft", "magic"),
            "attack_damage * (1 + 0.25 * level)").build());
```

技能来源、表达式变量和条件均有注册入口：`SkillSources.register`, `SkillBindings.register`, `SkillConditions.register` / `registerGlobal`。自定义 `SkillDefinition.Action` 可以在资源预付成功后执行额外逻辑；`validate` 必须无副作用，`apply` 抛出异常时已发生的世界修改无法自动回滚。`SkillCastEvent.Before` 可在资源和世界效果发生前取消；`After` 用于成功释放后的联动。

## AI 与友军

`BattleParticipation.tryAddFriendly(mob)` 可将实体加入当前玩家方，友军上限独立为 16，不占敌方 `participantCap`。`sideOf`, `participants` 和 `friendlyParticipants` 提供只读阵营查询。不会自动招募宠物或友军；第三方必须显式调用加入接口。`BattleSide.PLAYER` 包含玩家和友军，`HOSTILE` 包含敌人。

第三方 AI 的 `AiContext` 提供当前主要对手、技能可用性和 `castSkill`，`AiAction.skill(id,target)` 表示延迟技能意图。旧的近战、远程和路径方法仍保留。技能释放仍由 `TacticalSkills.cast` 进行服务端校验。

## 攻击范围和移动选点

攻击瞄准时，橙色格子表示武器的几何射程，绿色实体框表示服务器已通过距离、碰撞或弹道检查的目标。橙色格子不保证无视线阻挡或弹药/AP条件，真正攻击时会再次检查。移动选点使用碰撞体射线；草、花和其他无实体碰撞装饰不会遮住可站立格子的高亮和点击，实心墙、危险流体和不可站立位置仍会被拒绝。

## 许可证与打包

YiRanExpressionLib 通过 Jar-in-Jar 打包，坐标沿用 Advanced-Leveling **for NeoForge 1.21.1 当年内嵌 1.0.1 时**使用的 `com.github.yiran1457:YiRanExpressionLib:1.0.1`，严格区间 `[1.0.1]`。**这不构成与 Advanced Leveling 当前版本的兼容声明**：实测 `AdvancedLeveling-1.21.1-1.0.1-fix` 内嵌另一个库（`version="1.0.0"`、`license="AGPL v3.0"`）并请求区间 `[1.21.1-NeoForge-1.0.0,)`，且要求 NeoForge `[21.1.248,)`；两者在隔离专用服务器同时加载时于启动前以 JarJar `VERSION_RESOLUTION_FAILED` 失败。该实测只说明那两个模组各自的版本请求在该次运行中冲突，不是本库字节替换造成的，也不代表更早版本同样失败；兼容该分发不在本项目目标内。库的来源、校验和和许可证见 [libs/maven/README.md](../libs/maven/README.md) 与 [NOTICE.md](../NOTICE.md)。
