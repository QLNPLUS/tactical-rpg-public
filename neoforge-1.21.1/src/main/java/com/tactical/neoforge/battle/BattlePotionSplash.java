package com.tactical.neoforge.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownPotion;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Marks the one kind of splash potion this ruleset filters: a bottle a battle's own witch threw inside it.
 *
 * <p>Vanilla's {@code ThrownPotion.applySplash} hands its splash to every living entity within four blocks.
 * Inside a battle that is wrong in both directions: the witch would slow herself, a same-side raider would be
 * poisoned by her own side's bottle, and a bystander or another encounter's unit would be dragged into a fight
 * it never joined. The filter therefore keeps exactly this encounter's opposed, living participants.</p>
 *
 * <p>Only marked bottles are filtered. A player's potion and an ordinary world witch's potion keep vanilla
 * behaviour, because there is no rule for them here at all.</p>
 */
public final class BattlePotionSplash {
    private record Rule(ServerBattle battle, LivingEntity thrower) { }

    private static final Map<ThrownPotion, Rule> RULES = new IdentityHashMap<>();

    private BattlePotionSplash() { }

    /** Remember which encounter one real bottle belongs to, before it ever joins the world. */
    public static void mark(ThrownPotion potion, ServerBattle battle, LivingEntity thrower) {
        if (potion == null || battle == null || thrower == null) return;
        RULES.put(potion, new Rule(battle, thrower));
    }

    /** Drop one bottle's rule; called when the projectile is discarded so the map cannot grow forever. */
    public static void forget(Projectile projectile) {
        if (projectile instanceof ThrownPotion potion) RULES.remove(potion);
    }

    /** Drop every rule of one encounter. */
    public static void clear(ServerBattle battle) {
        RULES.values().removeIf(rule -> rule.battle() == battle);
    }

    /**
     * The splash candidates a marked bottle is really allowed to affect; an unmarked bottle is untouched.
     *
     * <p>Range, distance falloff, instant versus periodic effects and the vanilla application call all stay
     * vanilla: only the candidate list is narrowed, so poisoning and slowness leak exactly as little as the
     * instant damage does.</p>
     */
    public static List<LivingEntity> filter(ThrownPotion potion, List<LivingEntity> candidates) {
        Rule rule = potion == null ? null : RULES.get(potion);
        if (rule == null || candidates == null || candidates.isEmpty()) return candidates;
        List<LivingEntity> allowed = new ArrayList<>(candidates.size());
        for (LivingEntity candidate : candidates) {
            if (rule.battle().acceptsSplash(rule.thrower(), candidate)) allowed.add(candidate);
        }
        return allowed;
    }

    /** True when this bottle carries a filter at all; a fixture can assert the bottle really was marked. */
    public static boolean marked(ThrownPotion potion) {
        return potion != null && RULES.containsKey(potion);
    }
}
