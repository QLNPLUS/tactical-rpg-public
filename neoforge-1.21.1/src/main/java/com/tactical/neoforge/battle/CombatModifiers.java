package com.tactical.neoforge.battle;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Zone rules and height advantage.
 *
 * <p>Damage between a participant and an outsider is invalid: participants can
 * not hit outsiders (no kiting), and outsiders can not hit participants. A
 * hostile outsider that attacks a participant is instead pulled into the battle
 * and the zone grows to cover it.</p>
 */
public final class CombatModifiers {

    private CombatModifiers() {
    }

    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (BattleEffects.isApplyingEffect(event.getEntity())) return;
        Entity source = event.getSource().getEntity();
        if (!(source instanceof LivingEntity attacker)) {
            return;
        }
        LivingEntity target = event.getEntity();

        // Resolve from both identities: a participant hitting an outsider must still be seen,
        // so the target alone cannot be the lookup key.
        ServerBattle battle = BattleManager.containing(attacker);
        if (battle == null) battle = BattleManager.containing(target);
        if (battle == null) {
            return;
        }

        boolean attackerInside = battle.isParticipant(attacker);
        boolean targetInside = battle.isParticipant(target);
        if (attackerInside && targetInside) {
            return;
        }
        if (attackerInside == targetInside) {
            return;
        }
        if (!targetInside) {
            // A participant may not damage anything outside the zone.
            event.setCanceled(true);
            return;
        }
        // Outsider attacking a participant: absorb hostile mobs, block the rest. A member under
        // flee grace is not a legitimate target, so a mob that just hit one must never be absorbed.
        boolean targetProtected = target instanceof ServerPlayer protectedPlayer && BattleManager.isImmune(protectedPlayer);
        if (attacker instanceof Mob mob && !targetProtected && battle.tryAbsorb(mob)) {
            return;
        }
        // The hit is refused, but a refusal is not an exit: at cap the attacker is handed to this
        // encounter's isolation, so it leaves the battlefield instead of crowding the members.
        if (attacker instanceof Mob mob && !targetProtected) battle.isolateIntruder(mob);
        event.setCanceled(true);
        if (attacker instanceof ServerPlayer player) {
            BattleText.chat(player, Component.translatable(BattleText.KEY_CHAT_OUTSIDER_ATTACK));
        }
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Pre event) {
        if (BattleEffects.isApplyingEffect(event.getEntity())) return;
        ServerBattle battle = BattleManager.containing(event.getEntity());
        if (battle == null) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (!battle.isParticipant(attacker) || !battle.isParticipant(target)) {
            return;
        }
        // Guard is per defender: one participant raising a guard never protects another one, and the same
        // rule covers a player body and a hand-commanded companion, so neither shields the other.
        boolean guarding = battle.isDefending(target);
        double defense = ServerBattle.guardFactor(battle.hasShield(target), guarding);
        double multiplier=defense;
        if(PositionalCombat.eligible(event.getSource(),attacker)){
            var factors=PositionalCombat.evaluate(attacker,target,event.getSource());
            multiplier*=factors.total();
            // Only the display is filtered: a breakdown whose every modifier is neutral writes no row at all,
            // while the multipliers, the damage below and the raw combat events are exactly as they were.
            if(event.getNewDamage()>0) {
                Component row = PositionalCombat.summary(factors, defense);
                if (row != null) battle.journal().record("damage", row);
            }
        }
        if(multiplier!=1.0D){
            double amount=event.getNewDamage()*multiplier;
            if(Double.isFinite(amount)&&amount>=0)event.setNewDamage((float)Math.min(Float.MAX_VALUE,amount));
        }
    }
}
