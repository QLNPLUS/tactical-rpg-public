package com.tactical.neoforge.battle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Directed aggro cleanup: remove one mob's relationship with exactly one player.
 *
 * <p>Shared by the flee grace ({@link FleeProtection}) and by encounter isolation
 * ({@link MobIsolation}). Both need the same seven places a mob can remember a target -
 * {@code setTarget}, navigation, kill credit, {@code lastHurt*}, Brain memories, neutral-mob
 * anger and warden anger - but they differ in <em>when</em> it runs and <em>for which</em>
 * players. Keeping one implementation means neither feature can quietly cover fewer memories.</p>
 *
 * <p>Nothing here touches the mob's own AI flags, persistence, gravity or position, and nothing
 * clears a relationship with a player that is not named in the call: an unrelated player's
 * encounter must never be damaged by another encounter's cleanup.</p>
 */
public final class AggroCleanup {

    private AggroCleanup() {
    }

    /** Clear every reference this mob holds about one player. */
    public static void clear(Mob mob, UUID playerId) {
        if (mob == null || playerId == null) return;
        if (matches(mob.getTarget(), playerId)) {
            mob.setTarget(null);
            mob.getNavigation().stop();
        }
        if (matches(mob.getKillCredit(), playerId)) mob.setLastHurtByPlayer(null);
        if (matches(mob.getLastHurtByMob(), playerId)) mob.setLastHurtByMob(null);
        if (matches(mob.getLastHurtMob(), playerId)) mob.setLastHurtMob(null);
        Brain<?> brain = mob.getBrain();
        if (memory(brain, MemoryModuleType.WALK_TARGET)
                .filter(walk -> walk.getTarget() instanceof EntityTracker tracker && matches(tracker.getEntity(), playerId)).isPresent()) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            mob.getNavigation().stop();
        }
        if (memory(brain, MemoryModuleType.LOOK_TARGET)
                .filter(look -> look instanceof EntityTracker tracker && matches(tracker.getEntity(), playerId)).isPresent()) {
            brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
        eraseMatchingEntity(brain, MemoryModuleType.ATTACK_TARGET, playerId);
        eraseMatchingEntity(brain, MemoryModuleType.HURT_BY_ENTITY, playerId);
        DamageSource hurt = memory(brain, MemoryModuleType.HURT_BY).orElse(null);
        ServerPlayer attacker = hurt == null ? null : causingPlayer(hurt);
        if (attacker != null && attacker.getUUID().equals(playerId)) brain.eraseMemory(MemoryModuleType.HURT_BY);
        if (memory(brain, MemoryModuleType.ANGRY_AT).filter(playerId::equals).isPresent()) {
            brain.eraseMemory(MemoryModuleType.ANGRY_AT);
        }
        if (mob instanceof NeutralMob neutral && playerId.equals(neutral.getPersistentAngerTarget())) {
            // stopBeingAngry also clears unrelated target/hurt references; do not use it.
            neutral.setPersistentAngerTarget(null);
            neutral.setRemainingPersistentAngerTime(0);
        }
        if (mob instanceof Warden warden) {
            ServerPlayer suspect = mob.getServer().getPlayerList().getPlayer(playerId);
            if (suspect == null && mob.level().getPlayerByUUID(playerId) instanceof ServerPlayer local) suspect = local;
            if (suspect != null) warden.clearAnger(suspect);
        }
    }

    /** True when this entity is exactly that player. */
    public static boolean matches(Entity entity, UUID playerId) {
        return entity != null && entity.getUUID().equals(playerId);
    }

    /** Mojmap {@code DamageSource.getEntity} is the causing entity, not the direct projectile. */
    public static ServerPlayer causingPlayer(DamageSource source) {
        ServerPlayer causing = ownerPlayer(source.getEntity());
        return causing != null ? causing : ownerPlayer(source.getDirectEntity());
    }

    private static ServerPlayer ownerPlayer(Entity entity) {
        Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (entity != null && visited.add(entity)) {
            if (entity instanceof ServerPlayer player) return player;
            if (entity instanceof Projectile projectile) entity = projectile.getOwner();
            else if (entity instanceof PrimedTnt tnt) entity = tnt.getOwner();
            else if (entity instanceof AreaEffectCloud cloud) entity = cloud.getOwner();
            else if (entity instanceof OwnableEntity owned) entity = owned.getOwner();
            else return null;
        }
        return null;
    }

    private static <T extends Entity> void eraseMatchingEntity(Brain<?> brain, MemoryModuleType<T> memory, UUID playerId) {
        if (memory(brain, memory).filter(entity -> matches(entity, playerId)).isPresent()) brain.eraseMemory(memory);
    }

    static <T> Optional<T> memory(Brain<?> brain, MemoryModuleType<T> type) {
        Optional<T> value = brain.getMemoryInternal(type);
        return value == null ? Optional.empty() : value;
    }
}
