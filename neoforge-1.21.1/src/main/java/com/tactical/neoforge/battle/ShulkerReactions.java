package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The exact synchronous scope of one native {@code Shulker.hurt}, and the one reaction decided inside it.
 *
 * <p>Vanilla relocates a shulker inside its own {@code hurt}, in one of exactly two branches: the low-health
 * roll, or {@code hitByShulkerBullet} when a same-family bullet really hit an open shell - and that second branch
 * is also what may replicate. So the reaction lives inside that one native call, and the scope below is what
 * makes it bounded:</p>
 *
 * <ul>
 *   <li>the scope opens at {@code hurt} entry and closes on <em>every</em> exit of {@code hurt}, including an
 *       exception thrown by another listener. The mixin wraps the call in a try/finally for exactly that;</li>
 *   <li>the decision is taken in between, by the incoming-damage event, because that is the only point where the
 *       damage source is known;</li>
 *   <li>an unused permission never survives the scope, so a cancelled or refused hit cannot leave a permit that
 *       a later, different source spends for free;</li>
 *   <li>the real receipt is settled on the way out of the <em>outermost</em> frame, from where the body really is -
 *       nested frames of the same body leave that outer scope untouched.</li>
 * </ul>
 *
 * <p>Nothing here is thread state any more: the frames belong to the unit inside
 * {@link ShulkerController}, so one encounter's teardown or one unit's exit can never drop another unit's
 * in-flight permission or window.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class ShulkerReactions {

    private ShulkerReactions() {}

    /** Native {@code hurt} entry: open this body's own frame. */
    public static void beginHurt(Shulker shulker) {
        if (shulker == null || shulker.level().isClientSide) return;
        ServerBattle battle = BattleManager.containing(shulker);
        if (battle == null) return;
        battle.shulkerHurtBegan(shulker);
    }

    /** Native {@code hurt} exit, on the normal path and on a thrown path alike. */
    public static void endHurt(Shulker shulker) {
        if (shulker == null || shulker.level().isClientSide) return;
        ServerBattle battle = BattleManager.containing(shulker);
        if (battle == null) return;
        battle.shulkerHurtEnded(shulker);
    }

    /**
     * The decision point: vanilla's damage path has not run yet, so its own relocation has not been decided
     * either. Only the encounter that owns this exact unit answers.
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onIncoming(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !(victim instanceof Shulker shulker)) return;
        if (event.getAmount() <= 0.0F) return;
        DamageSource source = event.getSource();
        if (source == null) return;
        ServerBattle battle = BattleManager.containing(shulker);
        if (battle == null) return;
        battle.shulkerIncomingDamage(shulker, source);
    }
}
