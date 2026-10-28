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
        event.setCanceled(true);
        if (attacker instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal("[Tactical] 你不在战场内，无法攻击战场目标"));
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
        // Guard is per defender: one member raising a shield never protects another member.
        boolean guarding = target instanceof net.minecraft.server.level.ServerPlayer defender
                && battle.isDefending(defender);
        double defense = guarding ? (battle.hasShield(target) ? 0.3D : 0.5D) : 1.0D;
        double multiplier=defense;
        if(PositionalCombat.eligible(event.getSource(),attacker)){
            var factors=PositionalCombat.evaluate(attacker,target,event.getSource());
            multiplier*=factors.total();
            if(event.getNewDamage()>0)battle.journal().record("damage",PositionalCombat.summary(factors,defense));
        }
        if(multiplier!=1.0D){
            double amount=event.getNewDamage()*multiplier;
            if(Double.isFinite(amount)&&amount>=0)event.setNewDamage((float)Math.min(Float.MAX_VALUE,amount));
        }
    }
}
