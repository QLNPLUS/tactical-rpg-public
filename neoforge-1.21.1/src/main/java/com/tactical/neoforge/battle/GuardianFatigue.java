package com.tactical.neoforge.battle;

import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.ElderGuardian;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The elder guardian's own fatigue pulse, moved from vanilla's per-tick wall clock onto that unit's own turns.
 *
 * <p>Vanilla runs the whole fatigue segment from {@code customServerAiStep} whenever
 * {@code (tickCount + getId()) % 1200 == 0}: one {@code DIG_SLOWDOWN} instance of 6000 ticks at amplifier 2 handed
 * to every eligible survival player within 50 blocks, and a {@code GUARDIAN_ELDER_EFFECT} game event per candidate
 * it returned. None of that can happen for a body an encounter holds: {@code NoAI} makes the ordinary AI entry
 * answer false, and opening the whole {@code customServerAiStep} would also change the native home restriction.</p>
 *
 * <p>So the segment is driven from that body's <em>own</em> turn start instead, once every six complete own cycles -
 * the 1st, 7th, 13th and so on - which is the same 1200 ticks of {@link BattleEffects#TICKS_PER_OWN_CYCLE} per
 * cycle without any wall-clock waiting and without another unit's turn ever advancing it. The native eligibility
 * predicate is kept intact (survival, not allied, inside 50 blocks, no effect or a weaker one or one ending within
 * 1199 ticks), the native {@code addEffect} chain and its event are used rather than a written effect map, and only
 * a candidate whose {@code addEffect} really returned true is counted as hit - the native return list is a list of
 * attempts, not of successes.</p>
 *
 * <p>The candidate set is narrowed, not widened: only a living player of this same encounter that is really opposed
 * to this elder may be affected. A companion, a bystander, another encounter's member or a non-player body is never
 * touched.</p>
 */
public final class GuardianFatigue {

    /** Six complete own cycles of {@value BattleEffects#TICKS_PER_OWN_CYCLE} ticks are vanilla's 1200 tick interval. */
    public static final int OWN_CYCLE_INTERVAL = 6;
    /** Vanilla's effect duration. */
    public static final int EFFECT_DURATION = 6000;
    /** Vanilla's amplifier. */
    public static final int EFFECT_AMPLIFIER = 2;
    /** Vanilla's radius, in blocks. */
    public static final double EFFECT_RADIUS = 50.0D;
    /** Vanilla's own refresh window passed to {@code endsWithin}. */
    public static final int REFRESH_WINDOW = 1200;

    private GuardianFatigue() { }

    /**
     * Whether this own-turn ordinal is one of vanilla's pulse turns: the 1st, 7th, 13th and so on.
     *
     * <p>Counted from the unit's own turn counter, so a player's sixth turn, a real-time wait or another elder's
     * count can never trigger it.</p>
     */
    public static boolean dueOnOwnTurn(int ownTurns) {
        return ownTurns >= 1 && (ownTurns - 1) % OWN_CYCLE_INTERVAL == 0;
    }

    /**
     * Vanilla's own candidate predicate, unchanged: survival, not allied to the source, inside the radius, and
     * either no effect, a weaker one, or one that is about to run out.
     */
    public static boolean nativeEligible(ServerPlayer player, Entity source) {
        if (player == null || !player.gameMode.isSurvival()) return false;
        if (source != null && source.isAlliedTo(player)) return false;
        if (source != null && !source.position().closerThan(player.position(), EFFECT_RADIUS)) return false;
        MobEffectInstance current = player.getEffect(MobEffects.DIG_SLOWDOWN);
        return current == null || current.getAmplifier() < EFFECT_AMPLIFIER || current.endsWithin(REFRESH_WINDOW - 1);
    }

    /**
     * One pulse: vanilla's predicate, this encounter's opposition filter, the native {@code addEffect} chain and
     * vanilla's own client event for the players that really took the effect.
     *
     * @param withinEncounter the encounter's own "this is a living opposed member of mine" test
     * @return the candidates that passed every predicate and the ones that really took the effect
     */
    public static Pulse pulse(ServerLevel level, ElderGuardian elder, Predicate<ServerPlayer> withinEncounter) {
        if (level == null || elder == null || withinEncounter == null) return new Pulse(0, List.of());
        int candidates = 0;
        List<ServerPlayer> applied = new ArrayList<>();
        for (ServerPlayer player : List.copyOf(level.players())) {
            if (!nativeEligible(player, elder)) continue;
            if (!withinEncounter.test(player)) continue;
            candidates++;
            boolean took = player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, EFFECT_DURATION,
                    EFFECT_AMPLIFIER), elder);
            if (!took) continue;
            applied.add(player);
            // Vanilla's own presentation, sent only to a player the effect was really applied to.
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT,
                    elder.isSilent() ? 0.0F : 1.0F));
        }
        return new Pulse(candidates, List.copyOf(applied));
    }

    /** One pulse's real outcome: how many candidates were eligible, and which ones really took the effect. */
    public record Pulse(int candidates, List<ServerPlayer> applied) {
        public int appliedCount() { return applied.size(); }

        public boolean tookEffect() { return !applied.isEmpty(); }
    }
}
