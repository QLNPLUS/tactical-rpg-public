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
    }
}
