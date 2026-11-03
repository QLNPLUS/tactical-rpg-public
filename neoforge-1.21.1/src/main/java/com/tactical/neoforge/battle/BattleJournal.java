package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleLogPacket;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Per-encounter server authority. Constructing a failed encounter does not open a client log. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleJournal {
    /** Translation keys of this class' own wording; the two shipped languages must carry them all. */
    public static final String KEY_START = "tactical.journal.start";
    public static final String KEY_FINISH = "tactical.journal.finish";
    public static final String KEY_DAMAGE = "tactical.journal.damage";
    public static final String KEY_BLOCKED = "tactical.journal.blocked";
    public static final String KEY_HEAL = "tactical.journal.heal";
    public static final String KEY_DEATH = "tactical.journal.death";
    public static final String KEY_EFFECT_GAINED = "tactical.journal.effect.gained";
    public static final String KEY_EFFECT_UPDATED = "tactical.journal.effect.updated";
    public static final String KEY_EFFECT_REMOVED = "tactical.journal.effect.removed";
    public static final String KEY_EFFECT_EXPIRED = "tactical.journal.effect.expired";
    public static final String KEY_DURATION_INFINITE = "tactical.journal.duration.infinite";
    public static final String KEY_DURATION_SECONDS = "tactical.journal.duration.seconds";
    public static final String KEY_SOURCE_MAGIC = "tactical.journal.source.magic";
    public static final String KEY_SOURCE_WITHER = "tactical.journal.source.wither";
    public static final String KEY_SOURCE_FALL = "tactical.journal.source.fall";
    public static final String KEY_SOURCE_FIRE = "tactical.journal.source.fire";
    public static final String KEY_SOURCE_DROWN = "tactical.journal.source.drown";
    public static final String KEY_SOURCE_STARVE = "tactical.journal.source.starve";
    public static final String KEY_SOURCE_OTHER = "tactical.journal.source.other";
    /** Stable short row used when a producer's component cannot fit one journal frame. */
    public static final String KEY_TOO_LONG = "tactical.journal.too_long";

    private final java.util.function.Supplier<java.util.Collection<ServerPlayer>> recipients;
    /**
     * The encounter's canonical session id, not a private one.
     *
     * <p>Clients compare a log's id against the session they are showing, so a second random id here
     * would make every legitimate UPDATE/FINISH/CLEAR look like another encounter's traffic.</p>
     */
    private final UUID sessionId;
    private final ArrayDeque<BattleLogPacket.Entry> history = new ArrayDeque<>();
    private long sequence;
    private boolean finished;
    private boolean refusalReported;

    /** Single-recipient fixture form kept for existing self tests; it owns a private session id. */
    public BattleJournal(ServerPlayer player) {
        ServerPlayer anchor = java.util.Objects.requireNonNull(player);
        this.recipients = () -> List.of(anchor);
        this.sessionId = UUID.randomUUID();
    }

    /** Encounter-wide journal bound to the encounter's own session id. */
    public BattleJournal(UUID sessionId, java.util.function.Supplier<java.util.Collection<ServerPlayer>> recipients) {
        this.recipients = java.util.Objects.requireNonNull(recipients);
        this.sessionId = java.util.Objects.requireNonNull(sessionId);
    }

    public UUID sessionId() { return sessionId; }

    /**
     * Append one row whose text is still structure, so every member reads it in its own language.
     *
     * <p>This is the main path. Nothing here resolves the component: no {@code getString()}, no string
     * concatenation, no server-language snapshot.</p>
     */
    public void record(String category, Component text) {
        if (finished || text == null || isEmpty(text)) return;
        if (!append(category, text)) return;
        send(sequence == 1 ? BattleLogPacket.START : BattleLogPacket.UPDATE);
    }

    /**
     * Compatibility path for finished text.
     *
     * <p>The text is stored as a <em>literal</em>: it is never guessed to be a translation key, so a
     * third-party or data-pack string that happens to look like one cannot be silently translated. The
     * producers that still call this form (encounter actions, skills, combat summaries) are listed in the
     * delivery note as remaining to be keyed; this class' own events no longer use it.</p>
     */
    public void record(String category, String text) {
        if (finished) return;
        String safeText = bounded(text, BattleLogPacket.MAX_TEXT);
        if (safeText.isBlank()) return;
        if (!append(category, Component.literal(safeText))) return;
        send(sequence == 1 ? BattleLogPacket.START : BattleLogPacket.UPDATE);
    }

    /** Idempotent; append the outcome before the client starts its 20-second retention period. */
    public void finish(Component reason) {
        if (finished) return;
        if (sequence == 0) append("battle", Component.translatable(KEY_START));
        // The reason travels as a nested argument, so the verified snapshot below copies all of its
        // segments without resolving any of them.
        append("battle", Component.translatable(KEY_FINISH, reason == null ? "" : reason));
        finished = true;
        send(BattleLogPacket.FINISH);
    }

    /** Compatibility form: the reason is literal text (an enum-like token or a finished sentence). */
    public void finish(String reason) {
        finish(Component.literal(bounded(reason, BattleLogPacket.MAX_TEXT - 10)));
    }

    /** Explicit immediate clear for a lifecycle that should discard rather than retain its history. */
    public void clear() {
        finished = true;
        history.clear();
        send(BattleLogPacket.CLEAR);
    }

    /**
     * Store one verified row, or refuse it.
     *
     * <p>{@link BattleLogPacket#verifiedSnapshot} is the only way a row enters the history: it runs the
     * real frame writer and reader on the server's registry context, which both deep-copies the structure
     * (siblings, nested arguments - the earlier shallow copy lost them) and proves that the row satisfies
     * the sender's wire limit and the receiver's heap limit. A row that fails either limit, or that cannot
     * be measured at all, never reaches {@code history}, so no mutable component is kept around for a
     * later send that would fail on every member at once.</p>
     *
     * @return whether the row was stored
     */
    private boolean append(String category, Component text) {
        Component snapshot = snapshot(text);
        if (snapshot == null) return false;
        String safeCategory = bounded(bounded(category, BattleLogPacket.MAX_CATEGORY).toLowerCase(Locale.ROOT),
                BattleLogPacket.MAX_CATEGORY);
        history.addLast(new BattleLogPacket.Entry(++sequence, safeCategory, snapshot));
        while (history.size() > BattleLogPacket.MAX_HISTORY) history.removeFirst();
        return true;
    }

    /**
     * Take the value that will really be stored for one row, or refuse the row.
     *
     * <p>Nothing an action produces may disturb the action itself, so this never throws. Three outcomes,
     * in order:</p>
     * <ol>
     *   <li>the row is verified by {@link BattleLogPacket#verifiedSnapshot} - the value stored is that deep
     *       copy, which satisfies both journal limits;</li>
     *   <li>it is not (wire or heap), and the row becomes a stable short localised line, itself verified
     *       the same way, so the encounter still shows that a row existed and no unreadable value is kept;</li>
     *   <li>there is no registry context at all, so nothing can be copied or measured: the row is dropped
     *       rather than kept as a mutable component for a later send.</li>
     * </ol>
     *
     * <p>Each refusal is logged once per encounter so a misbehaving producer cannot flood the log.</p>
     */
    private Component snapshot(Component text) {
        RegistryAccess context = registries();
        if (context == null) {
            reportRefusal("no registry context to verify it");
            return null;
        }
        Component verified = BattleLogPacket.verifiedSnapshot(text, context);
        if (verified != null) return verified;
        Component fallback = BattleLogPacket.verifiedSnapshot(Component.translatable(KEY_TOO_LONG), context);
        if (fallback == null) {
            reportRefusal("neither the row nor its fallback could be verified");
            return null;
        }
        reportRefusal("wire or heap limit");
        return fallback;
    }

    private void reportRefusal(String cause) {
        if (refusalReported) return;
        refusalReported = true;
        Tactical.LOGGER.warn("[Tactical] journal row refused ({}): the encounter's own action is untouched", cause);
    }

    /**
     * The server's own registry context, used only to verify a row before it is stored.
     *
     * <p>The running server is asked first, so a journal with no recipient right now (everyone left, or a
     * joiner is about to be sent the full history) can still verify its rows. A recipient's level is the
     * fallback for fixtures that run without the server lifecycle hook.</p>
     */
    private RegistryAccess registries() {
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.registryAccess();
        try {
            for (ServerPlayer recipient : recipients.get()) {
                if (recipient != null && recipient.level() != null) return recipient.level().registryAccess();
            }
        } catch (RuntimeException ignored) {
            // A fixture supplier may be mid-teardown; a missing context refuses the row, never guesses.
        }
        return null;
    }

    /**
     * Force the complete history to exactly one member.
     *
     * <p>Used right after that member's membership transition: the joiner never has to reconstruct the
     * journal from deltas it may have missed, and the recipients of the broadcast are untouched.</p>
     */
    public void sendFullTo(net.minecraft.server.level.ServerPlayer recipient) {
        if (recipient == null) return;
        int lifecycle = finished ? BattleLogPacket.FINISH
                : sequence == 0 ? BattleLogPacket.START : BattleLogPacket.UPDATE;
        push(recipient, new BattleLogPacket(sessionId, lifecycle, sequence, List.copyOf(history)));
    }

    private void send(int lifecycle) {
        BattleLogPacket packet = new BattleLogPacket(sessionId, lifecycle, sequence, List.copyOf(history));
        for (ServerPlayer recipient : List.copyOf(recipients.get())) {
            push(recipient, packet);
        }
    }

    /**
     * One best-effort push. A transport that refuses this snapshot costs that snapshot, never the action
     * that produced it, and never the other recipients of the same snapshot.
     */
    private static void push(ServerPlayer recipient, BattleLogPacket packet) {
        try {
            com.tactical.neoforge.network.ServerPackets.sendUncheckedType(recipient, packet);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.warn("[Tactical] journal snapshot was not published: {}", failure.toString());
        }
    }

    private static boolean isEmpty(Component text) {
        return text.getContents() instanceof PlainTextContents plain
                && plain.text().isBlank() && text.getSiblings().isEmpty();
    }

    private static String bounded(String text, int limit) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(Math.min(text.length(), limit));
        for (int offset = 0; offset < text.length() && result.length() < limit;) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            // Formatting codes and control characters must not disguise subsequent journal rows.
            if (point == 0x00a7) {
                if (offset < text.length()) offset += Character.charCount(text.codePointAt(offset));
                continue;
            }
            if (Character.isISOControl(point)) point = ' ';
            if (result.length() + Character.charCount(point) > limit) break;
            result.appendCodePoint(point);
        }
        return result.toString().strip();
    }

    private static BattleJournal journal(LivingEntity entity) {
        if (entity.level().isClientSide) return null;
        // Route by real actor identity - players and NPCs alike - so several encounters log
        // independently and a unit is never attributed to an encounter it does not belong to.
        ServerBattle battle = BattleManager.containing(entity);
        return battle == null ? null : battle.journal();
    }

    /**
     * A participant's name as structure.
     *
     * <p>An unnamed vanilla mob keeps its registry translation key ({@code entity.minecraft.*}), so a row
     * about a mob that unloads or dies is still readable, and each client reads it in its own language.
     * A name somebody typed - a player profile, a custom name, a third-party name - is kept as that text
     * and is never treated as a key; only its untrusted literal text is stripped of formatting codes and
     * control characters, exactly as the string journal always did. Such a name is flattened to text on
     * purpose: a name has to stay a name, and it is not a game translation.</p>
     */
    private static Component name(Entity entity) {
        Component raw = entity.getName();
        if (raw.getContents() instanceof TranslatableContents) return raw;
        return Component.literal(bounded(raw.getString(), 64));
    }

    /** One decimal place, always with the same separator, so the value is not locale-dependent. */
    private static String amount(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Component environmentalSource(String id) {
        return switch (id) {
            case "magic", "indirectMagic" -> Component.translatable(KEY_SOURCE_MAGIC);
            case "wither" -> Component.translatable(KEY_SOURCE_WITHER);
            case "fall" -> Component.translatable(KEY_SOURCE_FALL);
            case "onFire", "inFire", "lava" -> Component.translatable(KEY_SOURCE_FIRE);
            case "drown" -> Component.translatable(KEY_SOURCE_DROWN);
            case "starve" -> Component.translatable(KEY_SOURCE_STARVE);
            // The damage type id is data, not a translation key: it stays verbatim inside the template.
            default -> Component.translatable(KEY_SOURCE_OTHER, Component.literal(bounded(id, 64)));
        };
    }

    private static Component effectName(MobEffectInstance effect) {
        // A vanilla (or third-party) effect description id is a translation key; it is never resolved here.
        return Component.translatable(effect.getDescriptionId());
    }

    private static Component duration(MobEffectInstance effect) {
        if (effect.isInfiniteDuration()) return Component.translatable(KEY_DURATION_INFINITE);
        int seconds = Math.max(1, (effect.getDuration() + 19) / 20);
        return Component.translatable(KEY_DURATION_SECONDS, seconds);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Post event) {
        BattleJournal journal = journal(event.getEntity());
        if (journal == null || !Float.isFinite(event.getNewDamage())) return;
        Entity attacker = event.getSource().getEntity();
        Component source = attacker == null
                ? environmentalSource(event.getSource().getMsgId())
                : name(attacker);
        if (event.getNewDamage() > 0) {
            journal.record("damage", Component.translatable(KEY_DAMAGE,
                    name(event.getEntity()), amount(event.getNewDamage()), source,
                    amount(event.getEntity().getHealth()), amount(event.getEntity().getMaxHealth())));
        } else if (event.getBlockedDamage() > 0 || event.getOriginalDamage() > 0) {
            journal.record("block", Component.translatable(KEY_BLOCKED, name(event.getEntity()), source));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHeal(LivingHealEvent event) {
        if (event.isCanceled()) return;
        BattleJournal journal = journal(event.getEntity());
        if (journal == null || !Float.isFinite(event.getAmount()) || event.getEntity().getHealth() <= 0) return;
        float actual = Math.min(event.getAmount(), event.getEntity().getMaxHealth() - event.getEntity().getHealth());
        if (actual > 0) {
            journal.record("heal", Component.translatable(KEY_HEAL, name(event.getEntity()), amount(actual)));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        BattleJournal journal = journal(event.getEntity());
        if (journal != null) journal.record("death", Component.translatable(KEY_DEATH, name(event.getEntity())));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEffectAdded(MobEffectEvent.Added event) {
        BattleJournal journal = journal(event.getEntity());
        MobEffectInstance effect = event.getEffectInstance();
        if (journal == null || effect == null) return;
        journal.record("effect", Component.translatable(
                event.getOldEffectInstance() == null ? KEY_EFFECT_GAINED : KEY_EFFECT_UPDATED,
                name(event.getEntity()), effectName(effect), effect.getAmplifier() + 1, duration(effect)));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        if (event.isCanceled() || event.getEffectInstance() == null) return;
        recordEffectEnd(event, KEY_EFFECT_REMOVED);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEffectExpired(MobEffectEvent.Expired event) {
        if (event.isCanceled()) return;
        recordEffectEnd(event, KEY_EFFECT_EXPIRED);
    }

    private static void recordEffectEnd(MobEffectEvent event, String key) {
        BattleJournal journal = journal(event.getEntity());
        MobEffectInstance effect = event.getEffectInstance();
        if (journal != null && effect != null) {
            journal.record("effect", Component.translatable(key, name(event.getEntity()), effectName(effect)));
        }
    }
}
