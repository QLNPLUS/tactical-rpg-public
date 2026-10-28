package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** A scoped receipt for Player.attack's void native API, including cancelled damage hooks. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class NativeAttackReceipt {
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();
    private static final class Attempt {
        final LivingEntity attacker, target;
        boolean committed;
        Attempt(LivingEntity attacker, LivingEntity target) { this.attacker=attacker; this.target=target; }
    }
    private NativeAttackReceipt() { }

    public static boolean run(LivingEntity attacker, LivingEntity target, Runnable attack) {
        Attempt previous=CURRENT.get(), attempt=new Attempt(attacker,target);
        CURRENT.set(attempt);
        try { attack.run(); return attempt.committed; }
        finally { if(previous==null)CURRENT.remove();else CURRENT.set(previous); }
    }

    @SubscribeEvent
    public static void damaged(LivingDamageEvent.Post event) {
        Attempt attempt=CURRENT.get();
        if(attempt!=null && event.getEntity()==attempt.target && event.getSource().getEntity()==attempt.attacker)
            attempt.committed=true;
    }
}
