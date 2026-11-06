package com.tactical.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Common config: battle tuning exposed to pack authors. */
public final class TacticalConfig {

    public static final ModConfigSpec SPEC;
    public static final TacticalConfig INSTANCE;

    static {
        Pair<TacticalConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(TacticalConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    public final ModConfigSpec.BooleanValue autoTrigger;
    public final ModConfigSpec.IntValue zoneRadius;
    public final ModConfigSpec.IntValue zoneHeight;
    public final ModConfigSpec.IntValue participantCap;
    public final ModConfigSpec.IntValue maxBattlePlayers;
    public final ModConfigSpec.BooleanValue friendlyFire;
    public final ModConfigSpec.IntValue playerStepTicks;
    public final ModConfigSpec.ConfigValue<String> zoneShape;
    public final ModConfigSpec.IntValue maxDropHeight;
    public final ModConfigSpec.IntValue initialAp;
    public final ModConfigSpec.IntValue meleeApCost;
    public final ModConfigSpec.IntValue bowApCost;
    public final ModConfigSpec.IntValue crossbowApCost;
    public final ModConfigSpec.IntValue itemApCost;
    public final ModConfigSpec.IntValue placeApCost;
    public final ModConfigSpec.IntValue breakApCost;
    /** Base price of one blaze fireball salvo: the mod's own design value, not a vanilla fact. */
    public final ModConfigSpec.IntValue blazeVolleyApCost;
    /** Base price of one drowned trident throw: this ruleset's value, not a vanilla fact. */
    public final ModConfigSpec.IntValue drownedTridentApCost;
    /** Base price of one enderman teleport: this ruleset's value, not a vanilla fact. */
    public final ModConfigSpec.IntValue endermanTeleportApCost;
    public final ModConfigSpec.IntValue witchDrinkApCost;
    public final ModConfigSpec.IntValue witchThrowApCost;
    public final ModConfigSpec.IntValue witchDrinkCooldownTurns;
    public final ModConfigSpec.DoubleValue witchHealThreshold;
    public final ModConfigSpec.IntValue initialMp;
    public final ModConfigSpec.IntValue outsideMpRecovery;
    public final ModConfigSpec.IntValue outsideMpInterval;
    public final ModConfigSpec.DoubleValue damageDirectionFront;
    public final ModConfigSpec.DoubleValue damageDirectionSide;
    public final ModConfigSpec.DoubleValue damageDirectionRear;
    public final ModConfigSpec.DoubleValue damageFrontHalfAngle;
    public final ModConfigSpec.DoubleValue damageRearHalfAngle;
    public final ModConfigSpec.DoubleValue damageHeightPerLevel;
    public final ModConfigSpec.DoubleValue damageHeightMin;
    public final ModConfigSpec.DoubleValue damageHeightMax;
    public final ModConfigSpec.IntValue progressionMaxLevel;
    public final ModConfigSpec.IntValue progressionStatCap;
    public final ModConfigSpec.IntValue progressionPointsPerLevel;
    public final ModConfigSpec.IntValue progressionXpBase;
    public final ModConfigSpec.IntValue progressionXpPerLevel;
    public final ModConfigSpec.DoubleValue progressionVigorHp;
    public final ModConfigSpec.DoubleValue progressionMindMp;
    public final ModConfigSpec.DoubleValue progressionEnduranceAp;
    public final ModConfigSpec.DoubleValue progressionStrengthDamage;
    public final ModConfigSpec.DoubleValue progressionDexterityCharge;
    public final ModConfigSpec.DoubleValue progressionIntelligenceSkillPower;

    private TacticalConfig(ModConfigSpec.Builder builder) {
        builder.push("battle");
        autoTrigger = builder
                .comment("Automatically enter turn-based combat when a mob targets the player.")
                .define("autoTrigger", true);
        zoneRadius = builder
                .comment("Horizontal radius of the battle zone in blocks.")
                .defineInRange("zoneRadius", 24, 4, 64);
        zoneHeight = builder
                .comment("Vertical half-height of the battle zone in blocks.")
                .defineInRange("zoneHeight", 16, 4, 64);
        participantCap = builder
                .comment("Maximum number of enemies taken over by one battle.")
                .defineInRange("participantCap", 16, 1, 64);
        maxBattlePlayers = builder
                .comment("Maximum number of real players in one battle. This is a roster limit, not a measured capacity.")
                .defineInRange("maxBattlePlayers", 4, 1, 16);
        friendlyFire = builder
                .comment("Allow allies to damage each other during battle.")
                .define("friendlyFire", false);
        playerStepTicks = builder
                .comment("Ticks spent animating one grid step (lower is faster).")
                .defineInRange("playerStepTicks", 3, 1, 20);
        zoneShape = builder.comment("Zone factory id; built-ins: tactical:cylinder, tactical:box. No boundary ring is rendered.")
                .define("zoneShape", "tactical:cylinder");
        maxDropHeight = builder.comment("Maximum adjacent direct drop height offered to the player (normal fall damage applies above 3).")
                .defineInRange("maxDropHeight", 8, 1, 16);
        builder.pop();
        builder.push("ap");
        initialAp = builder.comment("Initial AP for every participant, including reinforcements, bounded by its cap. First own turn adds no recovery.")
                .defineInRange("initial", 100, 0, 1_000_000);
        meleeApCost = builder.comment("Base melee AP cost; independent of attack speed. Successful attacks/items/placements share +25% of base per prior success this own turn (rounded up).")
                .defineInRange("meleeCost", 10, 1, 1_000_000);
        bowApCost = builder.defineInRange("bowCost", 15, 1, 1_000_000);
        crossbowApCost = builder.defineInRange("crossbowCost", 20, 1, 1_000_000);
        itemApCost = builder.defineInRange("itemCost", 8, 1, 1_000_000);
        placeApCost = builder.comment("Movement, defend and flee do not advance the shared paid action count. Native tactical:max_ap stays a percentage: saved value 100 now means cap150 for every movement speed. Native tactical:ap_recovery is absolute AP, default50.")
                .defineInRange("placeCost", 10, 1, 1_000_000);
        breakApCost = builder.comment("Base AP cost of one block break through the block action. It shares the same successful-action surcharge and battle-zone/tool checks as placement.")
                .defineInRange("breakCost", 10, 1, 1_000_000);
        blazeVolleyApCost = builder.comment("Base AP cost of one blaze fireball salvo. A salvo is one main action however many fireballs it really fires, and it shares the same successful-action surcharge as every other main action.")
                .defineInRange("blazeVolleyCost", com.tactical.core.combat.BattleRules.BLAZE_VOLLEY_COST, 1, 1_000_000);
        builder.pop();
        builder.push("drowned");
        drownedTridentApCost = builder.comment("Base AP cost of one drowned trident throw. Vanilla fires on a fixed 40 tick interval; a turn-based encounter maps that to one throw per own turn, and the throw shares the same successful-action surcharge as every other main action.")
                .defineInRange("tridentCost", com.tactical.core.combat.BattleRules.DROWNED_TRIDENT_COST, 1, 1_000_000);
        builder.pop();
        builder.push("enderman");
        endermanTeleportApCost = builder.comment("Base AP cost of one enderman teleport, whether it is the own-turn relocation or the single reaction to a hit, and the price charged to that enderman only. The own-turn relocation shares the successful-action surcharge; the reaction is paid at this base price and does not advance that surcharge.")
                .defineInRange("teleportCost", com.tactical.core.combat.BattleRules.ENDERMAN_TELEPORT_COST, 1, 1_000_000);
        builder.pop();
        builder.push("witch");
        witchDrinkApCost = builder.comment("Base AP cost of one witch self-drink; it shares the same successful-action surcharge as other main actions and is not a player item cost.")
                .defineInRange("drinkCost", 25, 1, 1_000_000);
        witchThrowApCost = builder.comment("Base AP cost of one thrown splash potion. One bottle is one main action.")
                .defineInRange("throwCost", 15, 1, 1_000_000);
        witchDrinkCooldownTurns = builder.comment("Own-turn gap between two witch self-drinks: with 3 she may drink on her 1st own turn, not on the 2nd or 3rd, and again on the 4th. All her self-drinks share this one cooldown; wall-clock time, other units' turns and reopening a menu never reduce it.")
                .defineInRange("drinkCooldownTurns", 3, 1, 100);
        witchHealThreshold = builder.comment("Health fraction below which a witch spends her drink on ordinary healing (the emergency heal at 0.25 is fixed by the ruleset). A drink is only started when at least one whole healing dose would land.")
                .defineInRange("healThreshold", 0.5D, 0.05D, 1.0D);
        builder.pop();
        builder.push("mana");
        initialMp=builder.comment("Used once only when an actor has no saved MP balance. Entering battle never refills MP.")
                .defineInRange("initial",100,0,1_000_000);
        outsideMpRecovery=builder.comment("MP restored outside battle per interval. Battle own-turn recovery uses tactical:mp_recovery, default zero.")
                .defineInRange("outsideRecovery",20,0,1_000_000);
        outsideMpInterval=builder.defineInRange("outsideIntervalTicks",200,20,72_000);
        builder.pop();
        builder.push("damage");
        builder.push("direction");
        damageDirectionFront = builder.comment("Multiplier for frontal attacks (inclusive front cone boundary).")
                .defineInRange("front", 1.0, 0.0, 1_000_000.0);
        damageDirectionSide = builder.comment("Multiplier for attacks between the front and rear cones.")
                .defineInRange("side", 1.2, 0.0, 1_000_000.0);
        damageDirectionRear = builder.comment("Multiplier for rear attacks (inclusive rear cone boundary).")
                .defineInRange("rear", 1.5, 0.0, 1_000_000.0);
        damageFrontHalfAngle = builder.comment("Front cone half-angle in degrees; angle <= this is frontal.")
                .defineInRange("frontHalfAngle", 45.0, 0.0, 90.0);
        damageRearHalfAngle = builder.comment("Rear cone half-angle; angle >= 180 minus this is rear. Front wins at a shared 90-degree boundary.")
                .defineInRange("rearHalfAngle", 45.0, 0.0, 90.0);
        builder.pop();
        builder.push("height");
        damageHeightPerLevel = builder.comment("Multiplier change per floor(source feet Y) minus floor(target feet Y); arrows use launch Y.")
                .defineInRange("perLevel", 0.05, 0.0, 1_000_000.0);
        damageHeightMin = builder.comment("Lower height multiplier bound; reversed min/max values are sorted.")
                .defineInRange("min", 0.85, 0.0, 1_000_000.0);
        damageHeightMax = builder.comment("Upper height multiplier bound.")
                .defineInRange("max", 1.15, 0.0, 1_000_000.0);
        builder.pop();
        builder.pop();
        // Character growth. Every value here is priced and enforced by the server; a page that was opened
        // under an earlier rule set is refused instead of being charged at the new price (see
        // com.tactical.neoforge.progression.ProgressionStore, which advances the profile revision when the
        // rule fingerprint changes). Changing pointsPerLevel never rewrites points already granted.
        builder.push("progression");
        progressionMaxLevel = builder.comment("Character level cap. Reaching it refuses further level-ups without touching owned points.")
                .defineInRange("max_level", 100, 1, 1_000_000);
        progressionStatCap = builder.comment("Displayed value cap of one stat; the displayed baseline is 10.")
                .defineInRange("stat_cap", 99, 10, 1_000_000);
        progressionPointsPerLevel = builder.comment("Points granted per character level. Raising or lowering it never retracts or grants historical points.")
                .defineInRange("points_per_level", 1, 0, 1_000_000);
        progressionXpBase = builder.comment("Vanilla XP price of the first level-up (level 1 -> 2).")
                .defineInRange("xp_base", 100, 0, 1_000_000_000);
        progressionXpPerLevel = builder.comment("Additional vanilla XP per character level already reached: price = xp_base + xp_per_level * (level - 1).")
                .defineInRange("xp_per_level", 50, 0, 1_000_000_000);
        progressionVigorHp = builder.comment("Max health added per invested vigor point.")
                .defineInRange("vigor_hp", 2.0, 0.0, 1_000_000.0);
        progressionMindMp = builder.comment("Max MP added per invested mind point.")
                .defineInRange("mind_mp", 5.0, 0.0, 1_000_000.0);
        progressionEnduranceAp = builder.comment("Percent points added to tactical:max_ap per invested endurance point; 100 stays 100 percent.")
                .defineInRange("endurance_ap", 2.0, 0.0, 1_000_000.0);
        progressionStrengthDamage = builder.comment("Attack damage added per invested strength point.")
                .defineInRange("strength_damage", 0.5, 0.0, 1_000_000.0);
        progressionDexterityCharge = builder.comment("Turn charge multiplier added per invested dexterity point; does not touch attack or movement speed.")
                .defineInRange("dexterity_charge", 0.01, 0.0, 1_000_000.0);
        progressionIntelligenceSkillPower = builder.comment("Skill damage multiplier added per invested intelligence point; applied once to core skill damage, never to plain attacks, costs, cooldowns or effect levels.")
                .defineInRange("intelligence_skill_power", 0.02, 0.0, 1_000_000.0);
        builder.pop();
    }
}
