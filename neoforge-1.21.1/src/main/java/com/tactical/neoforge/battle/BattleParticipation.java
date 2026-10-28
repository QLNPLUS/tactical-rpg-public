package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.skill.TacticalSkills;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.List;

/** Public recruitment/read API. Calls must run on the owning server thread. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleParticipation {
    public static final int MAX_FRIENDLIES = 16;
    private BattleParticipation() { }

    /**
     * First-time friendly recruitment uses candidate semantics: the mob is not a participant
     * yet, so {@link BattleManager#containing} would always reject it. Same level and zone are
     * still required, so a unit elsewhere never joins by accident.
     */
    public static boolean tryAddFriendly(Mob mob) {
        ServerBattle battle = BattleManager.candidateFor(mob);
        return battle != null && battle.tryAddFriendly(mob);
    }

    /** Null means this exact entity is outside the active encounter. */
    public static BattleSide sideOf(LivingEntity actor) {
        ServerBattle battle = BattleManager.containing(actor);
        return battle == null ? null : battle.sideOf(actor);
    }

    public static List<LivingEntity> participants() {
        List<LivingEntity> result = new ArrayList<>();
        for (ServerBattle battle : BattleManager.active()) result.addAll(battle.participants());
        return List.copyOf(result);
    }

    public static List<Mob> friendlyParticipants() {
        List<Mob> result = new ArrayList<>();
        for (ServerBattle battle : BattleManager.active()) result.addAll(battle.friendlyParticipants());
        return List.copyOf(result);
    }

    /** Single relation strategy, scoped to the player side so enemy collateral stays vanilla. */
    public static boolean allied(LivingEntity first, LivingEntity second) {
        ServerBattle battle = BattleManager.containing(first);
        if (battle == null) battle = BattleManager.containing(second);
        return battle != null && battle.protectedAllies(first, second);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target != null && allied(event.getEntity(), target)) event.setCanceled(true);
    }

    /** Causing entity covers arrows and native explosions as well as direct melee. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        boolean friendlyFire;
        try { friendlyFire = TacticalConfig.INSTANCE.friendlyFire.get(); }
        catch (Exception ignored) { friendlyFire = false; }
        if (!friendlyFire && event.getSource().getEntity() instanceof LivingEntity attacker
                && allied(attacker, event.getEntity())
                && !TacticalSkills.permitsFriendlyDamage(attacker, event.getEntity())) event.setCanceled(true);
    }
}
