package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.network.CreeperTelegraphPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Battle-owned AI lifecycle and server action validation. No static entities, battles or brains.
 * The owner animates MOVE and advances turns; it reconciles deaths after ROSTER_CHANGED.
 */
public final class BattleAiSession {
    private record Participant(Mob actor, TacticalMobAi adapter) { }
    private final ServerLevel level;
    private final java.util.function.Supplier<Boolean> live;
    private final java.util.function.Consumer<CreeperTelegraphPacket> broadcaster;
    /** The encounter's canonical session id, so telegraph packets pass the client's session gate. */
    private final UUID battleId;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private CreeperTelegraphPacket lastSnapshot;
    private boolean closed;

    /** Single-recipient fixture form kept for existing self tests. */
    public BattleAiSession(ServerPlayer player) {
        ServerPlayer anchor = Objects.requireNonNull(player, "battle owner");
        this.level = (ServerLevel) anchor.level();
        this.live = () -> anchor.level() == level;
        this.broadcaster = packet -> com.tactical.neoforge.network.ServerPackets.sendUncheckedType(anchor, packet);
        this.battleId = UUID.randomUUID();
    }

    /**
     * Multi-member form: the same public warning snapshot is broadcast to every current
     * participant, so a waiting player sees exactly what the acting player sees.
     */
    public BattleAiSession(ServerLevel level, UUID battleId, java.util.function.Supplier<Boolean> live,
                           java.util.function.Consumer<CreeperTelegraphPacket> broadcaster) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
        this.level = Objects.requireNonNull(level, "level");
        this.live = Objects.requireNonNull(live, "live");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
    }

    public UUID battleId() { return battleId; }

    /** Call after adding a participant, before its first world tick. Also used for late joins. */
    public void add(Mob actor) {
        if (closed || actor.level() != level) throw new IllegalStateException("AI participant outside its battle");
        if (participants.containsKey(actor.getUUID())) return;
        TacticalMobAi adapter = TacticalAiRegistry.create(actor);
        if (actor instanceof Creeper) access(actor).tactical$setControlled(true);
        participants.put(actor.getUUID(), new Participant(actor, adapter));
        sendSnapshot();
    }

    /** Safe inside the battle's own removal iterator: this only mutates the AI's separate map. */
    public void remove(Mob actor) {
        Participant participant = participants.remove(actor.getUUID());
        if (participant == null) return;
        participant.adapter.close();
        if (actor instanceof Creeper) access(actor).tactical$setControlled(false);
        sendSnapshot();
    }

    /** Call on every battle exit, including death, disconnect, level change and failed deployment. */
    public void clear() {
        if (closed) return;
        closed = true;
        for (Participant participant : List.copyOf(participants.values())) {
            participant.adapter.close();
            if (participant.actor instanceof Creeper) access(participant.actor).tactical$setControlled(false);
        }
        participants.clear();
        sendSnapshot();
    }

    /** Exposes per-participant lifecycle for diagnostics without sharing mutable state globally. */
    public TacticalMobAi adapter(Mob actor) {
        Participant participant = participants.get(actor.getUUID());
        return participant != null && participant.actor == actor ? participant.adapter : null;
    }

    public AiAction planTurn(AiContext context) {
        Participant participant = current(context);
        return participant == null ? AiAction.endTurn() : validate(context, participant.adapter.planTurn(context));
    }

    /** Call exactly once at path completion; it never starts another own turn. */
    public AiAction afterMovement(AiContext context) {
        Participant participant = current(context);
        return participant == null ? AiAction.endTurn() : validate(context, participant.adapter.afterMovement(context));
    }

    public AiResolution resolveInstant(AiContext context, AiAction proposed) {
        Participant participant = current(context);
        if (participant == null) return AiResolution.END_TURN;
        AiAction action = validate(context, proposed);
        switch (action.kind()) {
            case MOVE, DIVE -> throw new IllegalArgumentException("Battle owner must animate MOVE/DIVE; never resolve DIVE as an instant attack");
            case MELEE -> context.attackPlayer();
            case RANGED -> context.rangedAttackPlayer();
            case SKILL -> {
                // Runtime owns validation and all costs. Never reserve or pay again here.
                if (context.castSkill(action.skill(), action.skillTarget())) {
                    participant.adapter.onActionResolved(action.kind());
                }
                return AiResolution.END_TURN;
            }
            case PREPARE_EXPLOSION -> {
                participant.adapter.onActionResolved(action.kind());
                access(participant.actor).tactical$setPreparing(true);
                participant.actor.playSound(SoundEvents.CREEPER_PRIMED, 1.0F, 0.5F);
                participant.actor.gameEvent(GameEvent.PRIME_FUSE);
                context.log(participant.actor.getDisplayName().getString() + " 正在准备爆炸，将在它的下个回合引爆");
                sendSnapshot();
                return AiResolution.END_TURN;
            }
            case DETONATE -> {
                participant.adapter.onActionResolved(action.kind());
                access(participant.actor).tactical$setPreparing(false);
                context.log(participant.actor.getDisplayName().getString() + " 引爆了");
                // This invokes vanilla explosion, mobGriefing, charged/NBT radius, lingering cloud,
                // kill/loot attribution and NeoForge hooks. Never remove the battle roster here.
                access(participant.actor).tactical$explodeCreeper();
                sendSnapshot();
                return AiResolution.ROSTER_CHANGED;
            }
            case END_TURN -> { }
        }
        participant.adapter.onActionResolved(action.kind());
        return AiResolution.END_TURN;
    }

    /** Detect lightning, NBT radius edits and displacement even during idle player turns. */
    public void tick() {
        if (!closed) sendSnapshot();
    }

    /** Full replacement snapshot; unchanged packets are suppressed, including repeated sendState calls. */
    public void sendSnapshot() {
        CreeperTelegraphPacket next = snapshot();
        if (next.equals(lastSnapshot)) return;
        lastSnapshot = next;
        broadcaster.accept(next);
    }

    /** Connection-independent readback for diagnostics and deterministic server fixtures. */
    public CreeperTelegraphPacket snapshot() {
        List<CreeperTelegraphPacket.CreeperWarning> warnings = new ArrayList<>();
        if (!closed && Boolean.TRUE.equals(live.get())) {
            for (Participant participant : participants.values()) {
                if (!(participant.actor instanceof Creeper creeper) || !creeper.isAlive() || creeper.isRemoved()) continue;
                TacticalCreeperAccess access = access(creeper);
                warnings.add(new CreeperTelegraphPacket.CreeperWarning(creeper.getId(), creeper.getUUID(),
                        access.tactical$isPreparing(), creeper.getX(), creeper.getY(), creeper.getZ(),
                        BlastWarningGeometry.warningRadius(access.tactical$getExplosionRadius(), creeper.isPowered())));
            }
        }
        return closed || !Boolean.TRUE.equals(live.get())
                ? CreeperTelegraphPacket.empty(battleId, level.dimension().location())
                : new CreeperTelegraphPacket(true, battleId, level.dimension().location(), warnings);
    }

    private Participant current(AiContext context) {
        if (closed || context.actor().level() != level || !context.actor().isAlive() || context.actor().isRemoved()) return null;
        Participant participant = participants.get(context.actor().getUUID());
        return participant != null && participant.actor == context.actor() ? participant : null;
    }

    private AiAction validate(AiContext context, AiAction action) {
        Objects.requireNonNull(action, "Adapter returned null action");
        if (action.kind() == AiAction.Kind.MOVE) {
            if (context.moveCost() <= 0 || action.path().size() > context.ap() / context.moveCost()) return AiAction.endTurn();
            GridPos previous = context.actorCell();
            var seen = new HashSet<GridPos>();
            seen.add(previous);
            for (GridPos next : action.path()) {
                if (next.equals(context.targetCell()) || context.occupied(next) || !seen.add(next)
                        || !(context.flying() ? context.airNeighbors(previous) : context.standableNeighbors(previous)).contains(next)) {
                    context.log("AI movement rejected: occupied, outside zone or unstandable cell");
                    return AiAction.endTurn();
                }
                previous = next;
            }
        }
        if (action.kind() == AiAction.Kind.DIVE) {
            if (!(adapter(context.actor()) instanceof PhantomTacticalAi) || !validDive(context, action)) {
                context.log("AI dive rejected: incomplete return, collision, invalid attack or insufficient AP");
                return AiAction.endTurn();
            }
        }
        if (action.kind() == AiAction.Kind.MELEE && (context.meleeCost() <= 0 || context.ap() < context.meleeCost()
                || !context.inMeleeRange(context.actorCell(), context.targetCell()))) {
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.RANGED
                && (context.rangedCost() <= 0 || context.ap() < context.rangedCost()
                || !context.canRangedAttack(context.actorCell()))) {
            return AiAction.endTurn();
        }
        if (action.kind() == AiAction.Kind.PREPARE_EXPLOSION || action.kind() == AiAction.Kind.DETONATE) {
            TacticalMobAi adapter = adapter(context.actor());
            if (!(context.actor() instanceof Creeper) || !(adapter instanceof CreeperTacticalAi creeperAi)) return AiAction.endTurn();
            if (action.kind() == AiAction.Kind.PREPARE_EXPLOSION
                    && (creeperAi.state() != CreeperTacticalAi.State.APPROACHING || !CreeperTacticalAi.canPrepare(context))) {
                return AiAction.endTurn();
            }
            if (action.kind() == AiAction.Kind.DETONATE && creeperAi.state() != CreeperTacticalAi.State.PREPARING) {
                return AiAction.endTurn();
            }
        }
        return action;
    }

    /** Validate the complete precharged flight; repeated return cells are intentional, unlike MOVE. */
    public static boolean validDive(AiContext context, AiAction action) {
        if (action.kind() != AiAction.Kind.DIVE || !context.flying()
                || !PhantomTacticalAi.affordable(action.path().size(), context.moveCost(), context.meleeCost(), context.ap())
                || action.path().size() > com.tactical.neoforge.battle.AerialMovement.MAX_PATH_STEPS
                || !action.path().get(action.path().size() - 1).equals(context.actorCell())) return false;
        GridPos attack = action.path().get(action.attackIndex());
        if (!context.inMeleeRange(attack, context.targetCell()) || !context.hasLineOfSight(attack, context.targetCell())) return false;
        GridPos previous = context.actorCell();
        for (GridPos next : action.path()) {
            if (next.equals(context.targetCell()) || context.occupied(next)
                    || !context.airNeighbors(previous).contains(next)) return false;
            previous = next;
        }
        return true;
    }

    private static TacticalCreeperAccess access(Mob actor) {
        if (actor instanceof TacticalCreeperAccess access) return access;
        throw new IllegalStateException("Creeper tactical mixin missing: register tactical.creeper.mixins.json");
    }
}
