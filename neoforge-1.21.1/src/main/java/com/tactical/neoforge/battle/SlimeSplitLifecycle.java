package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.mixin.BattleMobPersistenceAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobSplitEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The native split one encounter is still owed, kept honest from the death to the child's first own turn.
 *
 * <p>Vanilla produces the children itself, later than this encounter would like: {@code removeDeadEnemies} drops a
 * dead body on the tick it dies, while {@code Slime#remove} only runs when {@code deathTime} reaches 20 and does the
 * spawning. Two things therefore have to survive the roster entry: the fact that a split is owed (so no victory is
 * declared and no turn advances while a body is still going to split), and the exact body it is owed by (so the
 * borrowed control flags can be given back before vanilla copies them onto every child).</p>
 *
 * <p>The children are the world's and the event's, not this class's. Nothing here creates, moves or re-times them:
 * vanilla decides how many, the {@link MobSplitEvent} decides which of them may really be added, and this class only
 * recognises the exact objects that the event finally left <em>and</em> that the world really registered. Until the
 * encounter has confirmed a child and taken it over, that child is held with its own flags sampled and temporarily
 * forced (no AI, persistent) so it can neither touch a member nor run a single AI tick first, and no other admission
 * path may claim it: the separation is a narrow pending hand-over owned by exactly one birth.</p>
 *
 * <p>Scope is deliberately narrow: only bodies this encounter held ({@link BattleManager#ownerOf}), only the two
 * exact vanilla types, only a dead body with {@code size > 1}. The native-removal scope is per thread and restored
 * by the mixin's {@code try/finally}, so a nested removal returns to the outer one and an exception cannot leak it.
 * Every other entity join, every other mob family and every other encounter is untouched, and a child this
 * encounter cannot place is left in the world instead of being deleted, teleported somewhere illegal or frozen.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class SlimeSplitLifecycle {

    /** How long one owed split may keep the encounter waiting, in its own ticks. Vanilla needs about 20. */
    static final int ABNORMAL_DEADLINE = 60;
    /** How long a born child may stay unconfirmed (never really registered) before it is given up. */
    static final int BIRTH_DEADLINE = 20;
    /** Bounded attempts to take a held child back in, on the existing exclusion cadence. */
    private static final int MAX_TAKE_ATTEMPTS = 5;
    private static final int TAKE_RETRY_TICKS = 20;

    /** Where one born child stands: separated by this encounter, taken over, or handed to exclusion. */
    enum BirthState {
        /** Born, recognised and held with this encounter's temporary flags; nothing else happened yet. */
        SEPARATED,
        /** Admitted through the ordinary roster transaction; this encounter owns it now. */
        ADMITTED,
        /** Kept in the world and held out of the battlefield until it can be taken legally. */
        HELD,
        /** Given up: the world keeps it as an ordinary mob, with its own flags back. */
        DROPPED
    }

    /** One dead parent whose native removal this encounter is still owed children by. */
    public static final class Pending {
        final SlimeSplitLifecycle owner;
        final ServerBattle battle;
        final Mob parent;
        final UUID id;
        final ResourceKey<Level> dimension;
        final BattleSide side;
        final int size;
        final boolean originalNoAi;
        final boolean originalPersistenceRequired;
        final int startedTick;
        /** Whether this wait came from a real death (only such a wait may be withdrawn by a revival). */
        final boolean fromDeath;
        /**
         * Whether vanilla's own removal <em>returned normally</em>, so its child list really was produced and
         * offered to the world.
         *
         * <p>Leaving the guarded window is not the same fact: an exception thrown out of {@code remove} only
         * proves the scope was closed. Such a wait keeps this false and can therefore never be settled as a
         * completed split - it rides the abnormal cutoff instead, which is what keeps an unknown outcome from
         * being reported as a victory.</p>
         */
        boolean removeSeen;
        /** Whether a native removal left through an exception, so what it produced is unknown. */
        boolean nativeFaulted;
        boolean eventCancelled;
        /**
         * The exact child objects the split event finally left, by identity; null until the event has returned.
         *
         * <p>A listener's own same-typed spawn is not in this list, and a child the list dropped is not either, so
         * neither can be mistaken for this split's child.</p>
         */
        List<Mob> finalChildren;
        final List<Birth> children = new ArrayList<>();
        boolean resolved;

        private Pending(SlimeSplitLifecycle owner, ServerBattle battle, Mob parent, BattleSide side, int size,
                        boolean originalNoAi, boolean originalPersistenceRequired, int startedTick,
                        boolean fromDeath) {
            this.owner = owner;
            this.battle = battle;
            this.parent = parent;
            this.id = parent.getUUID();
            this.dimension = parent.level().dimension();
            this.side = side;
            this.size = size;
            this.originalNoAi = originalNoAi;
            this.originalPersistenceRequired = originalPersistenceRequired;
            this.startedTick = startedTick;
            this.fromDeath = fromDeath;
        }
    }

    /** One child a real native split produced, from the first moment it is separable. */
    static final class Birth {
        final Pending parent;
        final Mob child;
        final UUID id;
        final boolean originalNoAi;
        final boolean originalPersistenceRequired;
        final int joinedTick;
        BirthState state = BirthState.SEPARATED;
        /** Whether this encounter's temporary flags are still applied and still owed back. */
        boolean separated = true;

        private Birth(Pending parent, Mob child, boolean originalNoAi, boolean originalPersistenceRequired,
                      int joinedTick) {
            this.parent = parent;
            this.child = child;
            this.id = child.getUUID();
            this.originalNoAi = originalNoAi;
            this.originalPersistenceRequired = originalPersistenceRequired;
            this.joinedTick = joinedTick;
        }

        boolean terminal() {
            return state != BirthState.SEPARATED;
        }
    }

    /**
     * Parents by exact object identity, across every encounter.
     *
     * <p>The native removal happens on the entity's own tick, outside the encounter's tick and after the roster
     * entry is already gone, so the parent has to be findable without an ownership claim. Identity is always
     * re-checked: a same-UUID respawn is a different body and never inherits this wait.</p>
     */
    private static final Map<Mob, Pending> INDEX = new IdentityHashMap<>();
    /** Born children by UUID; used by tests and by the exclusion retry to prove one birth is handled once. */
    private static final Map<UUID, Birth> BIRTH_INDEX = new LinkedHashMap<>();
    /** Every live lifecycle, so a stopping server can clear all transient state it created. */
    private static final Set<SlimeSplitLifecycle> LIVE = new LinkedHashSet<>();
    /**
     * The native removal currently executing <em>on this thread</em>, as a stack.
     *
     * <p>Thread-local on purpose: an integrated server's client thread runs its own removals, and a nested removal
     * of another parent must return to the outer scope instead of clearing it.</p>
     */
    private static final ThreadLocal<Deque<Pending>> SCOPE = ThreadLocal.withInitial(ArrayDeque::new);
    /**
     * Children currently separated by their birth, by exact object.
     *
     * <p>This is the narrow pending hand-over gate: while a child is in here, no admission path may claim it or
     * sample its temporary flags, and only the birth that separated it can hand it over.</p>
     */
    private static final Map<Mob, Birth> SEPARATED = new IdentityHashMap<>();

    private final ServerBattle battle;
    private final Map<UUID, Pending> pendings = new LinkedHashMap<>();
    private final Map<UUID, Birth> births = new LinkedHashMap<>();
    private int bornTotal;
    private int admittedTotal;
    private int anomalies;
    /** An abnormal release this encounter has not answered yet; it must not decay into a victory. */
    private boolean unreleasedAnomaly;

    SlimeSplitLifecycle(ServerBattle battle) {
        this.battle = battle;
        LIVE.add(this);
    }

    // ------------------------------------------------------------------ encounter side

    /** True while this encounter still owes somebody's native split a decision. */
    boolean pending() {
        return !pendings.isEmpty();
    }

    /** True when a split was released unconfirmed and the encounter has not settled that yet. */
    boolean hasUnreleasedAnomaly() {
        return unreleasedAnomaly;
    }

    /** Diagnose-only counters; the fixture reads them instead of inferring a lifecycle from log lines. */
    int pendingCount() {
        return pendings.size();
    }

    int bornTotal() {
        return bornTotal;
    }

    int admittedTotal() {
        return admittedTotal;
    }

    int anomalies() {
        return anomalies;
    }

    /** The exact bodies this encounter is waiting on, for diagnostics and fixture reads. */
    List<UUID> waitingParents() {
        return List.copyOf(pendings.keySet());
    }

    /**
     * One held child was taken back in through the ordinary admission.
     *
     * <p>Counted where the transaction really happened, so "one birth is one admission" stays true for both the
     * direct hand-over and the retake after an exclusion.</p>
     */
    void noteRetake(Mob child) {
        if (child == null) return;
        admittedTotal++;
    }

    /** Fixture probe: how deep the native-removal scope is on this thread right now. */
    static int scopeDepth() {
        return SCOPE.get().size();
    }

    /** The gate's answer for one exact object: is another birth's hand-over still holding it? */
    static boolean isSeparated(Mob mob) {
        return mob != null && SEPARATED.containsKey(mob);
    }

    /**
     * Remember a dead parent before its roster entry goes away.
     *
     * <p>The borrowed flags are deliberately <em>not</em> given back here: the only moment vanilla reads them is its
     * own child creation, so the hand-back belongs to that narrow window and nowhere else. A dead body that some
     * later listener revives therefore keeps exactly the state it had while it was still fighting.</p>
     */
    Pending capture(Mob parent, BattleSide side, boolean originalNoAi, boolean originalPersistenceRequired) {
        return capture(parent, side, originalNoAi, originalPersistenceRequired, false);
    }

    /** The same record, marked as coming from a real death so a cancelled death can withdraw it again. */
    Pending captureFromDeath(Mob parent, BattleSide side, boolean originalNoAi,
                             boolean originalPersistenceRequired) {
        return capture(parent, side, originalNoAi, originalPersistenceRequired, true);
    }

    private Pending capture(Mob parent, BattleSide side, boolean originalNoAi,
                            boolean originalPersistenceRequired, boolean fromDeath) {
        if (parent == null || battle == null) return null;
        if (!(parent instanceof Slime slime) || !SlimeGround.supports(parent)) return null;
        if (slime.getSize() <= 1) return null;
        Pending existing = pendings.get(parent.getUUID());
        if (existing != null) {
            if (existing.parent == parent) return existing;
            // A same-UUID body that is not the parent this wait belongs to is a different entity. Writing it in
            // would erase a wait that is still owed children by the body the encounter really held, so the
            // substitute is refused: it gets no record of its own and the old wait is left exactly as it was.
            Tactical.LOGGER.warn("[Tactical] split wait for {} belongs to another body; refusing the same-UUID "
                    + "substitute instead of overwriting it", parent.getUUID());
            return null;
        }
        Pending pending = new Pending(this, battle, parent, side, slime.getSize(), originalNoAi,
                originalPersistenceRequired, battle.tickCount(), fromDeath);
        pendings.put(pending.id, pending);
        INDEX.put(parent, pending);
        if (DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] dev-trace split-pending session={} tick={} parent={} size={} side={} "
                            + "noAi={} persistent={} at={}", battle.sessionId(), battle.tickCount(), pending.id,
                    pending.size, side, originalNoAi, originalPersistenceRequired, parent.blockPosition());
        }
        return pending;
    }

    /**
     * One encounter tick of this lifecycle: confirm what the world really registered, hand it over, and close the
     * wait when nothing is left.
     */
    void tick() {
        if (!births.isEmpty()) {
            for (Birth birth : List.copyOf(births.values())) advance(birth);
        }
        if (pendings.isEmpty()) return;
        for (Pending pending : List.copyOf(pendings.values())) settle(pending);
    }

    /** Give everything back: the encounter is over, so no child may stay separated and no wait may survive. */
    void clear() {
        for (Birth birth : List.copyOf(births.values())) drop(birth);
        births.clear();
        for (Pending pending : List.copyOf(pendings.values())) resolve(pending);
        pendings.clear();
        SCOPE.get().removeIf(pending -> pending.owner == this);
        LIVE.remove(this);
    }

    // ------------------------------------------------------------------ native hooks

    /**
     * Before vanilla decides how many children to make, inside the mixin's guarded window.
     *
     * <p>Two jobs, both narrow. Hand the borrowed flags back so the children inherit what the parent really had, and
     * record the scope that lets the coming joins be recognised. A parent this encounter did not record yet is
     * captured here from the encounter that still holds it: the native removal can arrive before the death scan (an
     * unloaded, discarded or foreignly removed body), and missing that window would silently turn a split into a
     * plain removal.</p>
     *
     * @return the scope this call opened, or null when this removal is none of this encounter's business
     */
    public static Pending beforeNativeRemove(Slime parent) {
        if (parent == null || parent.level().isClientSide()) return null;
        Pending pending = INDEX.get(parent);
        if (pending != null && (!pending.id.equals(parent.getUUID())
                || !pending.dimension.equals(parent.level().dimension()))) return null;
        if (pending == null || pending.parent != parent || pending.resolved) {
            pending = captureFromLiveOwner(parent);
            if (pending == null) return null;
        }
        restoreParentFlags(pending);
        SCOPE.get().push(pending);
        return pending;
    }

    /**
     * The guarded native removal is leaving its window.
     *
     * <p>The scope is dropped either way, so an exception can never leak it or a birth record. What the two
     * exits deliberately do <em>not</em> share is the meaning for the wait: only a normal return proves that
     * vanilla really produced and added its children, so only that closes the wait. An exception leaves the
     * outcome unknown, which is settled by the abnormal cutoff and ends the encounter on its own reason instead
     * of a victory nobody proved.</p>
     *
     * @param returned whether {@code Slime#remove} itself returned normally
     */
    public static void afterNativeRemove(Pending pending, boolean returned) {
        if (pending == null) return;
        Deque<Pending> scope = SCOPE.get();
        if (scope.peek() == pending) scope.pop();
        // Nested removals unwind in order, but a foreign removal in the middle must never be able to leave this
        // record behind as the current scope: remove it wherever it sits instead of clearing the whole stack.
        else scope.remove(pending);
        if (returned) pending.removeSeen = true;
        else pending.nativeFaulted = true;
        if (DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] dev-trace split-native session={} tick={} parent={} expected={} "
                            + "cancelled={} born={} returned={}", pending.battle.sessionId(),
                    pending.battle.tickCount(), pending.id,
                    pending.finalChildren == null ? -1 : pending.finalChildren.size(),
                    pending.eventCancelled, pending.children.size(), returned);
        }
    }

    /**
     * What the split event really left behind.
     *
     * <p>The list is the generation source, never a roster: a cancelled event means no child at all, and a listener
     * that removed, replaced or added entries is believed exactly as it stands. The exact objects are kept, so a
     * later join is only ever this split's child when it is one of them - an entity a listener spawned itself
     * during the event is not.</p>
     */
    public static void afterSplitEvent(Mob parent, List<Mob> children, MobSplitEvent event) {
        Pending pending = SCOPE.get().peek();
        if (pending == null || pending.parent != parent) return;
        pending.eventCancelled = event != null && event.isCanceled();
        List<Mob> finalChildren = new ArrayList<>(children == null ? 0 : children.size());
        if (children != null) {
            for (Mob child : children) if (child != null) finalChildren.add(child);
        }
        pending.finalChildren = finalChildren;
    }

    /**
     * A body the world is about to register while a native split is producing its children.
     *
     * <p>Recognised by thread scope <em>and</em> by identity in the final list the event left, so nothing else can be
     * mistaken for this split's child: not another add on the same thread, not a listener's own spawn, and not a
     * child the list dropped. The child's own flags are sampled first and then forced, so it can neither touch a
     * member in this tick nor run an AI tick before the encounter has confirmed it.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        Pending pending = SCOPE.get().peek();
        if (pending == null || pending.resolved) return;
        if (pending.finalChildren == null) return;
        if (!(event.getEntity() instanceof Mob child) || child.getType() != pending.parent.getType()) return;
        if (event.getLevel() != pending.battle.level()) return;
        // A refused join never becomes an entity: no hold, no slot, no ghost.
        if (event.isCanceled()) return;
        if (!containsIdentity(pending.finalChildren, child)) return;
        pending.owner.separate(pending, child);
    }

    /**
     * A body of this family dies while an encounter holds it.
     *
     * <p>Registered last, so a cancellation or a revival from any other listener is already visible: only a death
     * that really stands opens a wait, and the borrowed flags are left alone until vanilla's own child creation.
     * The barrier is still up in this very tick, which is what keeps a same-tick look at an apparently emptied
     * battlefield from deciding anything.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Slime slime)) return;
        if (slime.level().isClientSide() || slime.getSize() <= 1) return;
        if (!slime.isDeadOrDying() && slime.getHealth() > 0.0F) return;
        ServerBattle owner = BattleManager.ownerOf(slime.getUUID());
        if (owner == null) return;
        owner.captureSplitFromRoster(slime);
    }

    /** Static state must not outlive the server that owned it, and no separated child may stay frozen. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (SlimeSplitLifecycle lifecycle : List.copyOf(LIVE)) {
            try {
                lifecycle.clear();
            } catch (RuntimeException ignored) {
                // A stopping server must not fail on cosmetic cleanup of a body it is dropping anyway.
            }
        }
        LIVE.clear();
        SEPARATED.clear();
        INDEX.clear();
        BIRTH_INDEX.clear();
        SCOPE.remove();
    }

    // ------------------------------------------------------------------ internals

    private static Pending captureFromLiveOwner(Slime parent) {
        if (!SlimeGround.supports(parent) || parent.getSize() <= 1) return null;
        if (!parent.isDeadOrDying()) return null;
        ServerBattle owner = BattleManager.ownerOf(parent.getUUID());
        if (owner == null || owner.level() != parent.level()) return null;
        return owner.captureSplitFromRoster(parent);
    }

    private static boolean containsIdentity(List<Mob> children, Mob candidate) {
        for (Mob child : children) if (child == candidate) return true;
        return false;
    }

    private void separate(Pending pending, Mob child) {
        if (child.isRemoved() || !child.isAlive()) return;
        for (Birth existing : pending.children) if (existing.child == child) return;
        // A body another encounter already owns is never ours to hold, sample or touch.
        if (BattleManager.ownerOf(child.getUUID()) != null) return;
        // Identity before any side effect: a record under this UUID that belongs to a *different* object may not
        // be overwritten. The list the event leaves can hold two distinct children a listener gave the same UUID;
        // writing the second would drop the first one's record from the advance set, leave its temporary flags on
        // until the cutoff and hand the birth record to a body the world may never register at all. This object is
        // therefore refused untouched - no flags borrowed, no record, nothing to give back.
        Birth clash = birthOf(child.getUUID());
        if (clash != null && clash.child != child) {
            Tactical.LOGGER.warn("[Tactical] child {} shares its UUID with another birth record; refusing the "
                    + "duplicate object instead of overwriting the registered one", child.getUUID());
            return;
        }
        Birth birth = new Birth(pending, child, child.isNoAi(), child.isPersistenceRequired(),
                battle.tickCount());
        child.setNoAi(true);
        ((BattleMobPersistenceAccess) child).tactical$persistenceRequired(true);
        pending.children.add(birth);
        births.put(child.getUUID(), birth);
        BIRTH_INDEX.put(child.getUUID(), birth);
        SEPARATED.put(child, birth);
        bornTotal++;
    }

    /** Confirm, then hand one separated child over; nothing is admitted before the world really holds it. */
    private void advance(Birth birth) {
        if (birth.state != BirthState.SEPARATED) return;
        Mob child = birth.child;
        if (child.isRemoved() || !child.isAlive() || child.level() != battle.level()) {
            drop(birth);
            return;
        }
        if (battle.finished() || battle.ending() || battle.memberCount() == 0) {
            drop(birth);
            return;
        }
        // The world is the authority on registration: a cancelled join, a UUID clash or a foreign removal never
        // leaves a slot behind here.
        if (battle.level().getEntity(child.getUUID()) != child) {
            if (battle.tickCount() - birth.joinedTick > BIRTH_DEADLINE) drop(birth);
            return;
        }
        ServerBattle holder = BattleManager.ownerOf(child.getUUID());
        if (holder != null && holder != battle) {
            // Somebody else's unit now: this birth must not touch its flags, and its own state captured the values
            // it had (never the temporary separation, which the gate below prevents).
            dropWithoutTouching(birth);
            return;
        }
        if (holder == battle) {
            // Already ours through another legal path. One birth is one admission: nothing is claimed, charged or
            // queued twice, and the temporary values are still owed back.
            claimSeparation(birth);
            restoreChildFlags(birth);
            birth.state = BirthState.ADMITTED;
            admittedTotal++;
            forget(birth);
            return;
        }
        // The single controlled hand-over: from here the child is no longer claimable by an ordinary admission, and
        // giving its own flags back and taking it into the roster happen together.
        claimSeparation(birth);
        restoreChildFlags(birth);
        if (battle.tryAdmitSplitChild(child, birth.parent.side)) {
            birth.state = BirthState.ADMITTED;
            admittedTotal++;
            forget(birth);
            return;
        }
        // Capacity full, or a slot free but no legal place for this body: the child stays in the world and this
        // encounter keeps it off its members through the existing exclusion.
        birth.state = BirthState.HELD;
        battle.handOffSplitChild(child, birth.parent.side);
        forget(birth);
    }

    private void settle(Pending pending) {
        if (pending.resolved) return;
        if (battle.finished() || battle.ending() || battle.memberCount() == 0) {
            resolve(pending);
            return;
        }
        if (pending.parent.level() != battle.level()) {
            // A body that changed dimension is not this encounter's removal any more.
            resolve(pending);
            return;
        }
        // A death a later listener cancelled and undid is not a final death: no wait, and no flag of a living body
        // is touched. The roster still owns those flags, so this only drops the record.
        if (pending.fromDeath && !pending.removeSeen && isAliveAgain(pending)) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace split-withdrawn session={} tick={} parent={}",
                        battle.sessionId(), battle.tickCount(), pending.id);
            }
            resolve(pending);
            return;
        }
        // Once vanilla's own removal has returned normally, no further join can happen for this parent: the
        // recorded children are the complete set, and the wait is over as soon as each reached a terminal state.
        // A removal that left through an exception never sets removeSeen, so its unknown outcome cannot be
        // completed here - it waits for the abnormal cutoff below like any other unconfirmed split.
        if (pending.removeSeen && pending.children.stream().allMatch(Birth::terminal)) {
            if (pending.finalChildren != null && !pending.finalChildren.isEmpty() && pending.children.isEmpty()) {
                Tactical.LOGGER.info("[Tactical] split of {} produced no child the world registered "
                        + "(event cancelled={} expected={})", pending.id, pending.eventCancelled,
                        pending.finalChildren.size());
            }
            resolve(pending);
            return;
        }
        // The frozen abnormal upper bound counts real encounter ticks from the first pending: at 60 the wait is
        // released, not at 61.
        if (battle.tickCount() - pending.startedTick < ABNORMAL_DEADLINE) return;
        anomalies++;
        unreleasedAnomaly = true;
        // An unconfirmed split is not a victory: the encounter ends with its own reason if nothing else is left,
        // and this body's wait is released so the encounter can never stay busy forever.
        Tactical.LOGGER.warn("[Tactical] split of {} was never confirmed within {} ticks (removeSeen={} "
                        + "nativeFaulted={} expected={} born={}); releasing the wait", pending.id, ABNORMAL_DEADLINE,
                pending.removeSeen, pending.nativeFaulted,
                pending.finalChildren == null ? -1 : pending.finalChildren.size(), pending.children.size());
        resolve(pending);
    }

    private void resolve(Pending pending) {
        if (pending.resolved) return;
        pending.resolved = true;
        pendings.remove(pending.id);
        INDEX.remove(pending.parent, pending);
        for (Birth birth : List.copyOf(pending.children)) if (!birth.terminal()) drop(birth);
        // The borrowed flags are only given back to a body this encounter no longer holds: a participant's flags
        // belong to its roster entry, and a body somebody else owns is never touched.
        if (battle.stateOf(pending.parent) == null && BattleManager.ownerOf(pending.id) == null) {
            restoreParentFlags(pending);
        }
    }

    private static boolean isAliveAgain(Pending pending) {
        Mob parent = pending.parent;
        return parent != null && !parent.isRemoved() && !parent.isDeadOrDying() && parent.getHealth() > 0.0F;
    }

    private void drop(Birth birth) {
        ServerBattle holder = BattleManager.ownerOf(birth.id);
        if (holder != null && holder != battle) {
            dropWithoutTouching(birth);
            return;
        }
        claimSeparation(birth);
        restoreChildFlags(birth);
        birth.state = BirthState.DROPPED;
        forget(birth);
    }

    private void dropWithoutTouching(Birth birth) {
        claimSeparation(birth);
        // The flags belong to whoever took the body; this encounter never writes them again.
        birth.separated = false;
        birth.state = BirthState.DROPPED;
        forget(birth);
    }

    private void forget(Birth birth) {
        births.remove(birth.id);
        BIRTH_INDEX.remove(birth.id, birth);
        SEPARATED.remove(birth.child);
    }

    /** The birth record currently held under this UUID, in this encounter or in any other one. */
    private Birth birthOf(UUID id) {
        Birth mine = births.get(id);
        return mine != null ? mine : BIRTH_INDEX.get(id);
    }

    private void claimSeparation(Birth birth) {
        SEPARATED.remove(birth.child, birth);
    }

    private void restoreChildFlags(Birth birth) {
        if (!birth.separated) return;
        birth.separated = false;
        Mob child = birth.child;
        if (child == null) return;
        child.setNoAi(birth.originalNoAi);
        ((BattleMobPersistenceAccess) child).tactical$persistenceRequired(birth.originalPersistenceRequired);
    }

    private static void restoreParentFlags(Pending pending) {
        Mob parent = pending.parent;
        if (parent == null) return;
        parent.setNoAi(pending.originalNoAi);
        ((BattleMobPersistenceAccess) parent).tactical$persistenceRequired(pending.originalPersistenceRequired);
    }

    // ------------------------------------------------------------------ exclusion retry

    /**
     * Whether a held child may be retried now, on the exclusion's own bounded cadence.
     *
     * <p>Separate from the placement retries: a body that could not be placed outside must still get its bounded
     * attempts, and a body that could be placed must still get its bounded attempts to be taken back in. Sharing
     * one counter would let either path exhaust the other.</p>
     */
    boolean takeDue(int attempts, int retryAt, int now) {
        return attempts < MAX_TAKE_ATTEMPTS && now >= retryAt;
    }

    int takeRetryDelay() {
        return TAKE_RETRY_TICKS;
    }
}
