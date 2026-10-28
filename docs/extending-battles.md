# 扩展战斗区域、实体 AI 与伤害（0.20.0）

> **版本范围**：本文记录的是 **0.20.0** 的扩展入口，其后的候选已推进多处：0.23.0 候选的主战斗协议为 **`8`**、独立技能协议为 **`3`**，同一时间仍只有一场活动战斗，但一场内可由多名真实玩家参战（上限 `maxBattlePlayers` 默认 4、可配置 `1..16`，每场构造时快照），行动者按「行动者实体 == 本地玩家」判定，C2S 意图携带会话 UUID 与服端回合代数。以 0.20.0 描述为准的段落（含下文的 `BattleManager` 单场说明与防御协议 `5`）只作历史参考，不代表 0.23.0 候选的运行时契约。

本文描述 `main` 上 Minecraft 1.21.1 / NeoForge 21.1.216 的 0.20.0 扩展入口与生命周期，前置 ApricityUI 1.2.8；其他版本尚未移植或测试。主网络协议为 **`5`**，客户端与服务端必须同时使用 **0.20.0**。验证范围、执行状态与结果以 [0.20.0 验证记录](validation/0.20.0.md) 为准，不表示运行时验收或所有第三方实体兼容性验证已完成。

战斗区域、AI 与原生伤害修饰器使用代码内注册表，并非数据包或 NeoForge 注册事件；伤害另外提供 NeoForge 事件。目前没有脚本加载或任意动作执行器接口。战术属性使用原生 NeoForge 属性注册与同步。

## 战术属性与管理员 AP（0.20.0）

入口：[TacticalAttributes](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/TacticalAttributes.java)。三个属性均为原生、可同步属性，通过属性修改事件添加给所有已注册属性供应器的生物类型，包括玩家和模组生物；不局限于内置敌人或已注册战术 AI 的类型。

| 属性 | 默认 / 范围 | 实际作用 |
|---|---|---|
| `tactical:max_ap` | 100 / 1–10000 | `max(1, round(150 × 属性最终值 / 100))`；固定基准 150，不依赖移动速度，属性仍表示百分比 |
| `tactical:ap_recovery` | 50 / 0–1000000 | 后续每次自身回合恢复的绝对 AP，按最终属性值四舍五入；首次自身回合不额外恢复 |
| `tactical:turn_charge_speed` | 1 / 0.01–100 | 原先攻蓄力速度的倍率；原先攻仍含攻击速度、移动速度与该单位入场时固定的随机值 |

支持原版 `/attribute` 的 base 与 modifier，使用最终属性值计算。例如有权限的玩家可执行：

```mcfunction
/attribute @s tactical:max_ap base set 100
/attribute @s tactical:ap_recovery base set 75
/attribute @s tactical:turn_charge_speed base set 1.5
/attribute @s tactical:max_ap modifier add example:ap_bonus 25 add_value
/attribute @s tactical:ap_recovery modifier add example:recovery_bonus 10 add_value
/attribute @s tactical:turn_charge_speed modifier add example:charge_bonus 0.5 add_multiplied_total
/attribute @s tactical:max_ap modifier remove example:ap_bonus
```

可把 `@s` 换成单个生物目标。原有存档中的 `max_ap = 100` 保持 100% 的含义，现对应上限 150；`max_ap = 150` 对应 225 AP。迁移不把属性改成绝对 AP，也不要求重写已有百分比修饰符。战斗中动态重读最终属性：上限下降时截断当前 AP，上升不填充；修改蓄力倍率不重掷原先攻随机值，也不清空已有蓄力。

玩家、初始敌人与后续增援均以 `min(自身上限, ap.initial)` 入场，默认 100 AP。首次自身回合只建立回合基线，不追加恢复；之后自身回合按 `min(自身上限, 当前AP + ap_recovery)` 恢复，保留实际余量，不按全局回合或其他单位回合恢复。例如余下 65、上限 150、恢复量 50 时，下次自身回合为 115 AP。

### 基础费用与成功操作计数

配置入口：[TacticalConfig](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/TacticalConfig.java)；纯规则：[ApCalculator](../common/src/main/java/com/tactical/core/combat/ApCalculator.java)。实际键名为：

| 键 | 默认 | 范围 / 用途 |
|---|---|---|
| `ap.initial` | 100 | 0–1000000；每个参战者入场 AP |
| `ap.meleeCost` | 10 | 1–1000000；近战基础费用 |
| `ap.bowCost` | 15 | 1–1000000；弓基础费用 |
| `ap.crossbowCost` | 20 | 1–1000000；弩基础费用 |
| `ap.itemCost` | 8 | 1–1000000；普通道具基础费用 |
| `ap.placeCost` | 10 | 1–1000000；方块放置基础费用 |

每个行动者独立维护本回合成功的攻击 / 道具使用 / 方块放置总数，三个类别共用计数，跨武器切换也不清零。报价为 `ceil(base × (1 + 0.25 × countBefore))`，只在成功操作提交后增加计数，失败、拒绝或只查看报价不增加；每次自身回合开始清零。移动不增加该计数；防御固定 25、逃跑固定 50 AP，不参与加价。默认连续近战费用为 10、13、15、18…… AP；混合操作同样共享已成功次数。

旧攻击速度 AP 费用公式不再使用，原版武器冷却、蓄力、弹药、附魔与耐久等机制继续保留。移动速度仍影响每步费用与原先攻，但不再决定 AP 上限。

### 管理员 AP 命令

管理员 AP 命令要求权限等级 **2**，语法为：

```mcfunction
/tactical ap get <targetPlayer>
/tactical ap add <targetPlayer> <amount>
/tactical ap remove <targetPlayer> <amount>
```

`targetPlayer` 必须解析为一个在线玩家，且是当前未结束战斗的拥有者；不支持敌人 AP 或修改非拥有者玩家。`amount` 是 1–10000 的整数，增减的是当前 AP，结果限制在 **0 至当前属性计算的上限**，并同步状态及记录纪事。例如 `/tactical ap add @p 20`；命令不改变 AP 上限，不切换回合，也不是恢复量属性的替代入口。

## 朝向、高度与技能伤害倍率（0.20.0）

服务端入口：[TacticalDamageMultipliers](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/TacticalDamageMultipliers.java)、[TacticalDamageEvent](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/TacticalDamageEvent.java)；公共几何规则与扇区枚举：[PositionalDamage](../common/src/main/java/com/tactical/core/combat/PositionalDamage.java)。战斗接入见 [CombatModifiers](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/CombatModifiers.java) 与 [PositionalCombat](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/PositionalCombat.java)。

### 过滤、几何与结算阶段

只有同场参战者之间的玩家 / Mob 直接近战（`PLAYER_ATTACK`、`MOB_ATTACK`、`MOB_ATTACK_NO_AGGRO`，直接实体必须是攻击者）和真实 `AbstractArrow` 箭矢的物理伤害（同时要求 `DamageTypes.ARROW`）获得位置倍率；`ThrownTrident` 明确排除。药箭物理命中可修正，附带的魔法 / 药水伤害不跟随；即使直接实体是箭矢，魔法或爆炸伤害类型也不进入位置修正。魔法、爆炸、火焰、摔落、药水、荆棘及投掷三叉戟不进入位置倍率 / 技能事件流程。防御继续保留此前符合条件的减伤入口，不因位置过滤而消失；效果周期伤害仍遵循既有特殊处理。

朝向使用目标的 `yBodyRot`，不是头部或视线 yaw。近战水平向量从目标指向攻击者；箭矢优先使用命中时的 `-velocity.x / -velocity.z` 表示水平来向，不用射手命中时的位置。箭矢水平速度近零时才回退到伤害来源位置；无法确定方向时扇区为 `UNKNOWN`，方向倍率 ×1。

默认正面 `angle <= 45°` 为 ×1，背面 `angle >= 135°` 为 ×1.5，二者之间为侧面 ×1.2；45° 与 135° 均包含边界。可配置两侧半角，背面阈值为 `180 - rearHalfAngle`；正 / 背半角均为 90° 时共享边界优先正面。

高度倍率为 `clamp(1 + 0.05 × (floor(sourceFeetY) - floor(targetFeetY)), 0.85, 1.15)`。分别 floor 后再相减，负坐标同样适用。箭矢高度由 [ArrowImpactGeometry](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ArrowImpactGeometry.java) 在**服务端 EntityJoinLevelEvent** 中首次记录生物所有者的脚底 Y 与维度标记；已有记录不覆盖。命中时维度必须匹配，记录缺失、维度不符或数值无效时高度 ×1，绝不使用所有者当前脚底 Y 代替。

修改应用在 `LivingDamageEvent.Pre`，即原版护甲等前序减伤之后、吸收生命之前。此时伤害 × `direction × height × skill × defense`；防御不属于 `Breakdown.total()`。玩家普通防御 ×0.5，副手盾牌防御 ×0.3，仍覆盖所有攻击角度；确认防御的身体朝向只影响扇区，背击不自动绕过防御。默认技能阶段为 ×1，本版没有直接赋予玩家背刺技能。

### 实际配置键

| 键 | 默认 | 范围 / 含义 |
|---|---|---|
| `damage.direction.front` | 1.0 | 0–1000000；正面倍率 |
| `damage.direction.side` | 1.2 | 0–1000000；侧面倍率 |
| `damage.direction.rear` | 1.5 | 0–1000000；背面倍率 |
| `damage.direction.frontHalfAngle` | 45.0 | 0–90°；含边界的正面半角 |
| `damage.direction.rearHalfAngle` | 45.0 | 0–90°；含边界的背面半角 |
| `damage.height.perLevel` | 0.05 | 0–1000000；每格整数脚底高差增量 |
| `damage.height.min` | 0.85 | 0–1000000；高度倍率下限 |
| `damage.height.max` | 1.15 | 0–1000000；高度倍率上限 |

`min / max` 颠倒时先按大小排序。技能倍率不使用固定背刺配置；由扩展按实际技能条件选择是否乘入。

### 公共 API 与 Context

- `register(ResourceLocation, Modifier)`：在模组启动期间注册；`Modifier.modify(Context, TacticalDamageEvent)` 处理单次评估，ID 确定稳定执行顺序。空参数拒绝，重复 ID 抛出 `IllegalArgumentException`。
- `unregister(ResourceLocation)`：只移除该 ID 的修饰器，返回是否实际移除；适用于扩展卸载或测试清理。
- `evaluate(Context)`：读取当前配置，计算基础倍率、执行注册修饰器，再发布一次 NeoForge 事件，返回最终 `Breakdown`。还有 `evaluate(Context, PositionalDamage.Rules)` 显式规则重载。该方法本身不执行参战 / 伤害来源过滤；正常战斗调用方先完成过滤。
- `TacticalDamageEvent.context()` / `base()` / `breakdown()`：分别读取几何上下文、不可变的基础倍率、当前累计倍率。`base().sector()` 使用公共 `PositionalDamage.Sector`：`FRONT`、`SIDE`、`REAR`、`UNKNOWN`。
- `multiply(Stage, factor)`：仅乘入指定独立阶段，`Stage` 为 `DIRECTION`、`HEIGHT`、`SKILL`。无取消标志；无效、非有限或负倍率忽略，零有效，每阶段上限 1000000。注册修饰器抛出运行时异常时撤销该修饰器本次修改并记录错误，继续其余修饰器。

`Context` 是服务端的单次几何快照，字段如下；它不携带独立“背刺技能已启用”或“箭矢发射元数据有效”标志：

| 访问器 | 内容 |
|---|---|
| `attacker()` / `target()` | 本次攻击者与受击生物；扩展可据此检查自己的技能、队伍或状态条件 |
| `damageSource()` | 原生伤害来源，可通过直接实体区分箭矢等来源 |
| `targetYaw()` | 目标身体 yaw |
| `sourceDirectionX()` / `sourceDirectionZ()` | 从目标指向攻击来向的水平向量；箭矢通常为反向水平速度 |
| `sourceFeetY()` | 近战攻击者脚底 Y，或箭矢已记录的发射脚底 Y；箭矢元数据无效时传入目标脚底 Y，使高度中性 |
| `targetFeetY()` | 受击时目标脚底 Y |
| `originKnown()` | 调用方是否提供了有限的来源几何；false 时基础扇区为 UNKNOWN，方向与高度均 ×1。它不能单独证明箭矢发射元数据有效 |

### 可复制示例：只对背面命中乘入双倍技能

这是需由扩展主动注册的 API 示例，不是本版内置技能。示例仅增加 `SKILL` 阶段，不重复乘入既有背面 ×1.5；有技能解锁条件的扩展应在条件内检查 `context.attacker()` 等自身状态。将下列类加入扩展模组，并在启动时调用 `ExampleRearSkill.register()`：

```java
import com.tactical.core.combat.PositionalDamage;
import com.tactical.neoforge.battle.TacticalDamageMultipliers;
import net.minecraft.resources.ResourceLocation;

public final class ExampleRearSkill {
    private static final ResourceLocation ID =
            ResourceLocation.parse("example:rear_skill");

    private ExampleRearSkill() { }

    public static void register() {
        TacticalDamageMultipliers.register(ID, (context, event) -> {
            if (event.base().sector() == PositionalDamage.Sector.REAR) {
                event.multiply(TacticalDamageMultipliers.Stage.SKILL, 2.0);
            }
        });
    }

    public static void unregister() {
        TacticalDamageMultipliers.unregister(ID);
    }
}
```

例如同高背击在此扩展启用后为 `1.5 × 1 × 2 = 3`，再独立乘以防御；正面 / 侧面技能倍率仍为 ×1。也可订阅 `TacticalDamageEvent`，但同一个技能应选择注册修饰器或事件监听之一，避免叠加两次。不要在修饰器中再次调用 `evaluate` 造成递归，也不要在全局注册项中捕获某次战斗或实体。

## 战斗区域

- 纯 Java 接口：[BattleZone](../common/src/main/java/com/tactical/core/zone/BattleZone.java)。
- NeoForge 工厂注册表：[BattleZones](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/BattleZones.java)。
- 配置：[TacticalConfig](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/TacticalConfig.java)。

在模组启动期间、战斗创建前注册工厂：

```java
BattleZones.register(ResourceLocation.parse("example:arena"),
        (center, radius, halfHeight) -> new MyBattleZone(center, radius, halfHeight));
```

`BattleZones.Factory.create(GridPos center, int radius, int halfHeight)` 必须返回非空 `BattleZone`。ID / 工厂为空或重复 ID 会抛出 `IllegalArgumentException`；`BattleZones.create(String id, ...)` 对未知 / 无效 ID 也抛出该异常。当前 `ServerBattle` 创建区域时会捕获 `IllegalArgumentException`，记录警告并回退到圆柱；不要依赖它屏蔽扩展中的其他异常。

`BattleZone` 的实际契约：

| 方法 | 用途 |
|---|---|
| `contains(GridPos cell)` | 权威的格子归属判断；不依赖客户端是否画出边界 |
| `bounds()` | 返回 `Bounds(minX, minY, minZ, maxX, maxY, maxZ)`，必须包住区域；服务端用最大值加 1 构造实体查询 AABB |
| `including(GridPos cell)` | 返回扩展后的区域，保留已有区域并包含新加入敌人的格子；调用方会替换原区域引用 |
| `center()` / `radius()` / `halfHeight()` | 区域中心与尺寸信息；现有可达距离、网络状态等仍使用水平半径，须与自定义形状保持一致 |

内置 `tactical:cylinder` 与 `tactical:box` 均由 [BoundedBattleZone](../common/src/main/java/com/tactical/core/zone/BoundedBattleZone.java) 实现。圆柱按 X/Z 欧氏距离判断，盒形按 X/Z 最大轴距离判断；两者都限制 Y 半高，边缘包含在内。扩展时中心不变，水平半径和垂直半高只增加。

在 `config/tactical-common.toml` 的 `[battle]` 下选择 `zoneShape = "example:arena"`。内置默认是 `tactical:cylinder`，`zoneRadius = 24`（4–64），`zoneHeight = 16`（4–64）；盒形的 radius 是 X/Z 半宽。区域用于服务端部署、路径限制和扩张，不再绘制蓝色边界；浅蓝移动格与红色爆炸预警是独立的显示层。自定义形状不会自动获得客户端轮廓渲染。

## 持续仇恨入场与名额（0.19.1）

`ServerBattle.pulseAggro` 每 10 个服务端 ticks 查询当前 `zoneBounds.inflate(8)`：范围是战场包围盒向所有方向扩展 8 格，不是全世界扫描，也不是仅查询 `zone.contains` 内的实体。候选必须是存活敌对 Mob，且 `mob.getTarget()` 必须精确为本场战斗拥有者；按距拥有者由近到远调用 `tryAbsorb`，直到名额满。中立、无目标、目标为其他实体或已经放弃追击拥有者的单位不加入；不在扫描盒内的远处实体，仍可通过直接场外攻击入口尝试吸收。

初始开战（包括 force）仍要求候选位于初始 `zone.contains` 内；候选按距离尝试部署，成功才计数，不在安全检查前先截取前 N 个。`participantCap` 是敌人数上限，不含拥有者玩家；周期吸收与场外攻击者吸收共用此限制。无安全位置的候选失败后继续尝试后面的候选。

死亡 / 移除清理释放名额后，应立即补充合格追击者，再判断是否胜利；不能等待下次 10-tick 周期而提前结束战斗。补充沿用同一战斗对象、现存单位 AP / 蓄力与回合队列，不重建战斗或重置当前回合。未参战实体保持普通 AI 和原仇恨，不因处于区域内就被清仇恨或冻结。

## 安全部署（0.17.0，0.19.1 入场规则修正）

入口：[BattleDeployment](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/BattleDeployment.java)。`findNearestStandable(ServerLevel, LivingEntity, Predicate<BlockPos> occupied, Predicate<BlockPos> zone)` 返回 `Optional<Vec3>`，只查询、不修改实体。候选包括当前位置、保留原始 X/Z 的竖直列，以及水平半径 4、垂直 ±8 格内的格心位置；按距原始物理位置的三维距离排序，距离相同再按 Y/X/Z 确定顺序。它是有界候选中的最近安全位置，不是全世界搜索。

检查使用实体当前姿态 / 宽度的完整包围盒，包含建造高度、世界边界、原版方块 / 实体碰撞、液体，以及额外的存活非旁观 living entity / 可碰撞实体检查，避免与未参战旁观者重叠。整个脚印需要准确脚下高度的坚固上表面，并排除支撑和身体空间中的火、熔岩、岩浆块、仙人掌、营火、细雪、浆果丛、凋零玫瑰等危险方块。当前仅支持整数脚底 Y 的完整顶面，不保证半砖 / 楼梯等任意分数高度；可保留非整数 X/Z，调用者不得再次强制居中。

`ServerBattle` 在初始敌人与 `tryAbsorb` 后续吸收中按移动策略选择部署查询；0.19.1 不再冻结未参战旁观者。地面单位沿用上述站立查询；`AerialMovement.supports` 当前仅识别 `Phantom`、`FlyingMob`、`Vex`，改用 `findNearestClearAir`，不要求脚下支撑。当前位置安全时保留完整实际坐标（含分数 X/Y/Z），否则在水平半径 4、垂直 ±8 的整数偏移候选中按三维距离搜索，仍保留原分数偏移；不代表所有无重力或模组飞行实体自动支持。

初始部署检查当前区域与占用；后续吸收先找安全位置，再将区域扩展以包含候选格。只有查询成功后才修改位置、AI / 持久化状态或名册；无候选则跳过该实体，吸收返回失败，不使用不安全后备位置。扩展调用也应遵循先查询、后提交顺序。空中查询同样检查完整身体、建造高度、世界边界、实体、液体与危险方块；每条飞行边还检查身体扫掠包围盒，不能因端点为空气而穿过墙壁或屋顶。验证结果以本版验收记录为准。

## 实体 AI 注册

入口：[TacticalAiRegistry](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ai/TacticalAiRegistry.java)。在 common setup、实体类型已经注册后调用：

```java
TacticalAiRegistry.register(ResourceLocation.parse("example:guard"),
        MyEntities.GUARD.get(), actor -> new GuardAi());
```

实际签名为 `register(ResourceLocation id, EntityType<?> type, Function<Mob, TacticalMobAi> factory)`。ID 必须是 `BuiltInRegistries.ENTITY_TYPE` 中对应的确切实体类型 ID，不能拿独立的“行为 ID”替代；未知 ID、类型不匹配、重复注册会失败。注册表按实体类型精确匹配，不按继承层级匹配。未注册的 Mob 每次创建时得到一个新的 `GenericMeleeAi`；内置 `minecraft:creeper`、`minecraft:skeleton` 与 `minecraft:phantom` 已注册，不能用同一个 ID 覆盖。

内置 [SkeletonTacticalAi](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ai/SkeletonTacticalAi.java) 仅注册给 `EntityType.SKELETON`；流浪者与沼骸不会因继承关系获得该行为。普通骷髅无弓时回退近战；持弓时争取 4–16 格射击位置，偏好约 8 格，最多搜索 128 个格子。规划可绕掩体、接近或后退，并在能本回合开火时为当前弓射击报价（默认本回合首次 15 AP）预留预算；不足时可先移动到后续射击位置。4 格是舒适距离而非硬性最近射程，无法退开时仍可近距离开火。每个自身回合最多射击一次。

内置 [PhantomTacticalAi](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ai/PhantomTacticalAi.java) 只精确注册给 `EntityType.PHANTOM`。空中移动策略比 AI 注册范围更宽：其他 `FlyingMob` / `Vex` 或未精确注册的子类型仍得到通用 `GenericMeleeAi`，不会自动获得幻翼俯冲。安全部署后记录网格偏移，飞行移动与返回均保持原分数坐标；战斗期间普通 AI 关闭，非行动期间保持安全悬空位置，不执行原版自由飞行。

幻翼在自身回合以三维 26 邻格路线寻找近战及视线均可行的位置；垂直、水平与对角边均计一步。完整 `DIVE` 路径包含去程、攻击位置与回到起点的回程，预先检查 **当前近战报价 + 路径全部步数 × moveCost** AP（默认本回合首次近战基础费用 10 AP）。去程与回程都检查区域、占用与扫掠碰撞；搜索上限为 8192 节点、128 步。完整往返预算不足时仅执行付费接近，不免费攻击或传送回返；已在可攻击位置时可按当前近战报价近战。动态障碍也会在动画中重新检查，不能承诺障碍变化后仍强制完成原路线。

工厂必须返回非空适配器，并为每个参战实体创建独立实例。全局注册表只保存工厂，不应捕获某次战斗、世界或实体；可变状态应属于适配器，由战斗会话管理。

### 适配器与上下文

[TacticalMobAi](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ai/TacticalMobAi.java) 提供：

- `AiAction planTurn(AiContext context)`：实际敌方回合的规划入口。
- `afterMovement(AiContext context)`：一次路径完成后调用一次，不是新回合；默认返回结束回合。
- `onActionResolved(AiAction.Kind action)`：会话执行即时动作时通知适配器，可在此提交状态转移；现有 MOVE 动画没有该回调，移动后的决策使用 `afterMovement`。
- `close()`：单位移除或会话清理时释放实例状态，默认无操作。

[AiContext](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ai/AiContext.java) 是服务端视图，包含 actor、双方格子、剩余 AP、每步费用、到玩家的最短路径、可站立邻格、占用 / 近战距离 / 视线判断、原版近战执行与日志入口。所有路径都**不含起点**；`pathToPlayer()` 可以以玩家占用格结束，适配器不能把该格直接放进 MOVE。`standableNeighbors()` 已检查地形与区域，仍须单独考虑占用。

0.19.0 空中移动默认方法（不使第三方类型自动获得幻翼 AI）：

| 方法 | 默认值 / 契约 |
|---|---|
| `flying()` | 按 `AerialMovement.supports(actor())` 判断，仅覆盖上述有限类型 |
| `meleeCost()` | 默认 `10`；完整俯冲同时计入当前攻击报价，实际会话按配置与行动者本回合计数报价 |
| `airNeighbors(GridPos from)` | 默认空列表；实现需检查三维相邻边的全身扫掠碰撞、区域与占用 |
| `airPath(GridPos from, GridPos to, int budget)` | 在 `airNeighbors` 上做有界 BFS；路径不含起点，找不到返回空列表 |

实际战斗上下文对空中单位使用上述三维邻格，并保留部署时的实际坐标偏移；自定义上下文仅返回 `flying() == true` 而不实现合法邻格，不能获得可用路线。`pathToPlayer()` 对空中单位返回接近近战位置的路径，不保证终点为玩家格。

0.16.0 新增的三个远程默认方法继续保留：

| 方法 | 默认值 / 契约 |
|---|---|
| `canRangedAttack(GridPos from)` | 默认 `false`；从假设脚下格检查权威射程与世界弹道，不依赖当前朝向 |
| `rangedAttackPlayer()` | 默认无操作；由战斗拥有者执行原版射击、扣除 AP 并管理飞行等待 |
| `rangedCost()` | 默认 `15`；启用远程的上下文仍须提供配套检查 / 执行逻辑，实际会话按配置与行动者本回合计数报价 |

当前 `ServerBattle` 按该骷髅当前弓报价执行射击（默认本回合首次 15 AP）：检查主手弓、水平射程不超过约 16 格及 [BallisticShot](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/BallisticShot.java) 的实际碰撞弹道。规划同时考虑方块、生物、重力与阻力，可搜索其他弧线；它不是按模型面向做视线判断。原版射击创建真实箭矢，中央箭使用确定弹道，多重射击侧箭保留原版行为。玩家弓弩也使用此弹道规划；无可行弹道时明确拒绝，调用原版武器发射前不会消耗 AP、弹药或耐久。

[AiAction](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ai/AiAction.java) 是意图，不是回调，记录字段为 `kind`、`path`、`attackIndex`。当前种类固定为 `MOVE`、`DIVE`、`MELEE`、`RANGED`、`PREPARE_EXPLOSION`、`DETONATE`、`END_TURN`；只有 MOVE / DIVE 必须带非空路径，其他动作路径必须为空。普通扩展可返回移动、近战、远程或结束回合；`RANGED` 需要上下文提供上述钩子，默认上下文会拒绝它。

`AiAction.dive(roundTrip, attackIndex)` 的攻击索引从 0 开始，必须指向末尾之前的格子，以保证攻击之后还有回程；非 DIVE 的索引必须为 -1，原两参数构造仍可用于非 DIVE。会话只接受内置 `PhantomTacticalAi` 的 DIVE，并独立验证费用、攻击距离 / 视线、每条三维边及最终回到起始格；回程重复格是有意允许的，不适用普通 MOVE 的去重规则。它不是任意第三方技能接口。爆炸动作同样额外要求实体是 Creeper 且适配器是内置 `CreeperTacticalAi`。

### 会话与行动生命周期

实现参见 [BattleAiSession](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ai/BattleAiSession.java) 与 [ServerBattle](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/ServerBattle.java)：

1. 每个 `ServerBattle` 持有一个 `BattleAiSession(player)`；初始参战和后来吸收的敌人均调用 `add(mob)`。会话拒绝关闭后的添加和异世界添加，对同 UUID 的重复添加不重复建适配器。Creeper 同时进入战术控制状态，普通计 tick 引信不接管回合计时。
2. 单位真正开始自身回合时，先调用 `BattleEffects.onTurnStart(actor)`，再处理死亡检查、按属性重算 AP 上限与移动费用、重置该行动者连续成功操作计数；首次自身回合不追加 AP，后续按 `min(cap, current + recovery)` 恢复。效果时钟不能在每个 tick、规划或移动完成时推进；具体语义见 [效果时间规则](effects-turn-time.md)。敌方短暂行动延迟结束后调用 `planTurn`。
3. 会话检查 MOVE 的 AP 步数预算、占用、玩家格、重复格及相邻连通性；地面用站立邻格，空中用三维飞行邻格。DIVE 单独校验完整往返，不合法意图降为结束回合。近战必须满足正攻击费用、足够 AP 与近战距离；`RANGED` 要求正费用、AP 足够且 `canRangedAttack(actorCell())` 为真；爆炸动作还检查适配器状态与准备条件。
4. MOVE / DIVE 由 `ServerBattle` 预扣整条路线费用并逐格动画；DIVE 另预留当前近战报价，到攻击索引时最多执行一次原版近战，不重复扣费。攻击成功才增加该行动者成功操作计数；攻击未成功或未执行时退还预留攻击费，再继续合法回程。完成后调用 `afterMovement`，幻翼直接结束回合。不要把 MOVE / DIVE 传给 `resolveInstant`，该方法会抛出异常；直接空中 MELEE 则单独按当前近战报价结算。
5. 即时动作由 `resolveInstant` 执行；近战走现有原版 Mob 攻击及战斗钩子，远程调用 `rangedAttackPlayer()` 并通知 `onActionResolved(RANGED)`。当前战斗拥有者在成功发射后扣 AP，锁住后续行动，待所有被追踪投射物结算及位置恢复完成后才结束该敌方回合；适配器不能自行提前推进。准备爆炸提交状态、发声并同步预警；下一次 Creeper 自身回合的 `planTurn` 才返回引爆。实际爆炸走原版机制，包括充能 / NBT 半径、破坏规则与 NeoForge 钩子。
6. `AiResolution.END_TURN` 由战斗推进队列；`ROSTER_CHANGED` 必须先交给战斗拥有者处理死亡 / 名册变化，不能直接再推进一次，否则可能跳过回合。适配器不要自行删除战斗名册或切换当前回合。
7. 死亡 / 移除清理由 `ServerBattle.removeDeadEnemies()` 调用效果 `forget` 和 AI `remove`，后者调用适配器 `close()` 并退出 Creeper 控制。`ServerBattle.finish()` 调用 `ai.clear()` 与 `BattleEffects.clear()`；`clear()` 幂等、关闭会话、关闭剩余适配器并发送空预警。若新增部署失败 / 提前退出路径，应同样清理已添加实例，不能只丢弃引用。

会话 `tick()` 更新苦力怕预警快照，`sendSnapshot()` 抑制未变化的数据；这只是同步状态，不会增加自身回合或推进爆炸状态。当前系统仍由 `BattleManager` 管理单场活动战斗，不是多人联合作战 API。

## 逃跑保护与实际伤害（0.19.0）

入口：[FleeProtection](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/battle/FleeProtection.java)。成功逃跑仍走既有 50 AP 与概率流程，失败耗尽剩余 AP 并结束回合；只有成功才授予保护。`BattleManager.grantFleeImmunity` / `isImmune` 委托给该实现，不再仅靠自动开战扫描忽略玩家。

保护按玩家 UUID 保持 **200 个真实服务端 ticks**，不是自身回合周期。授予时立即清除所有已加载维度中指向该玩家的目标、相关伤害 / Brain 记忆、持久愤怒及监守者愤怒，保护期间每 tick 再清理，并取消重新选中该玩家的目标事件；不清除其他玩家的仇恨，不冻结普通 AI，也不使玩家无敌。未加载实体不在扫描范围内。

仅 `LivingDamageEvent.Post` 的 `getNewDamage() > 0` 或 `getReduction(DamageContainer.Reduction.ABSORPTION) > 0` 才解除实际造成伤害玩家的保护，实际消耗吸收生命也计入伤害。检查伤害来源的致因实体与直接实体，递归追溯 `Projectile`、`PrimedTnt`、`AreaEffectCloud` 与 `OwnableEntity` 的所有者（含宠物），并防止所有权循环。挥空、取消命中、未消耗生命 / 吸收生命的零伤害或完全格挡都不解除，环境伤害不会虚构玩家归属。实际伤害后可恢复存活敌人的目标，但事件不直接创建战斗；后续自动触发仍由正常扫描处理，遵守配置与 `/tactical auto on|off`。

## 客户端布局与防御协议（0.19.0）

生命名册固定左上，纪事固定左下安全区，按名册底边与底部 HUD 保留空间缩减行数，空间不足时仅留标题或隐藏，不迁移到中间列。顶部共享轨道不填充、同时容纳全部参战肖像，横向位置为蓄力比例（当前行动者在终点），避免重叠只调整纵向；0.19.1 中名称与肖像悬停均通过按来源持有的定位状态联动世界轮廓、镜头、对应左侧名称及肖像边框；一个来源的离开不能误清除另一来源仍有效的定位，移开或目标死亡时清理。此交互不消耗 AP、不改变移动 / 攻击等操作模式。

防御客户端先预览并选择四方向之一；取消不发送防御提交。主协议 **`5`** 的 `DefendPacket` 携带确认朝向，服务端要求玩家行动阶段、无移动 / 投射物等待、至少 25 AP 且 yaw 为 0 / 90 / 180 / 270，全部通过后才扣 25 AP、设置身体朝向和防御并结束回合；不足或拒绝不扣费。朝向影响位置伤害扇区，防御减伤仍覆盖全角度，持续到下一自身回合。朝向包自 0.19.0 引入；0.20.0 的主协议现为 `5`，双方必须同时更新，不能只替换 UI 页面来获取服务端修正。

## 原生头顶血条（0.20.0）

入口：[HealthBarRenderer](../neoforge-1.21.1/src/main/java/com/tactical/neoforge/client/HealthBarRenderer.java)。紧凑边框、暗底与连续填充使用暖红敌人 / 青绿玩家配色，当前行动者或悬停定位目标使用金色边框。渲染锚点与模型一致：按当前实体的 partial tick 对 `xOld / yOld / zOld` 与当前位置插值，再加实体渲染器的 `getRenderOffset`；不改用 `xo / yo / zo`，不叠加独立 EMA 跟随，也不按 20 Hz 快照硬跳。

相机位置以双精度减去一次后再做 billboard 旋转，轮廓、边框、底槽、填充与标记使用分离深度，避免共面闪烁。血量数值仍来自战斗同步；逐帧跟随位置不代表新增逐帧血量网络同步。客户端画面、移动和镜头验收状态以本版验证记录为准。
