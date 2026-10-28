package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleLogPacket;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Per-encounter server authority. Constructing a failed encounter does not open a client log. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleJournal {
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

    /** Call for authoritative turn/action/rejection messages, never for client-provided display text. */
    public void record(String category, String text) {
        if (finished) return;
        String safeText = bounded(text, BattleLogPacket.MAX_TEXT);
        if (safeText.isBlank()) return;
        int lifecycle = sequence == 0 ? BattleLogPacket.START : BattleLogPacket.UPDATE;
        append(category, safeText);
        send(lifecycle);
    }

    /** Idempotent; append the outcome before the client starts its 20-second retention period. */
    public void finish(String reason) {
        if (finished) return;
        if (sequence == 0) record("battle", "战斗开始");
        append("battle", "战斗结束 · " + bounded(reason, BattleLogPacket.MAX_TEXT - 10));
        finished = true;
        send(BattleLogPacket.FINISH);
    }

    /** Explicit immediate clear for a lifecycle that should discard rather than retain its history. */
    public void clear() {
        finished = true;
        history.clear();
        send(BattleLogPacket.CLEAR);
    }

    private void append(String category, String text) {
        String safeCategory = bounded(bounded(category, BattleLogPacket.MAX_CATEGORY).toLowerCase(Locale.ROOT),
                BattleLogPacket.MAX_CATEGORY);
        history.addLast(new BattleLogPacket.Entry(++sequence, safeCategory,
                bounded(text, BattleLogPacket.MAX_TEXT)));
        while (history.size() > BattleLogPacket.MAX_HISTORY) history.removeFirst();
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
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(recipient,
                new BattleLogPacket(sessionId, lifecycle, sequence, List.copyOf(history)));
    }

    private void send(int lifecycle) {
        BattleLogPacket packet = new BattleLogPacket(sessionId, lifecycle, sequence, List.copyOf(history));
        for (ServerPlayer recipient : List.copyOf(recipients.get())) {
            com.tactical.neoforge.network.ServerPackets.sendUncheckedType(recipient, packet);
        }
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

    private static String name(Entity entity) {
        return bounded(entity.getName().getString(), 64);
    }

    private static String amount(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String environmentalSource(String id) {
        return switch (id) {
            case "magic", "indirectMagic" -> "魔法 / 状态效果";
            case "wither" -> "凋零";
            case "fall" -> "摔落";
            case "onFire", "inFire", "lava" -> "火焰 / 熔岩";
            case "drown" -> "溺水";
            case "starve" -> "饥饿";
            default -> "环境（" + id + "）";
        };
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Post event) {
        BattleJournal journal = journal(event.getEntity());
        if (journal == null || !Float.isFinite(event.getNewDamage())) return;
        Entity attacker = event.getSource().getEntity();
        String source = attacker == null
                ? environmentalSource(event.getSource().getMsgId())
                : name(attacker);
        if (event.getNewDamage() > 0) {
            journal.record("damage", name(event.getEntity()) + " 受到 " + amount(event.getNewDamage())
                    + " 伤害 · " + source + " · HP " + amount(event.getEntity().getHealth())
                    + "/" + amount(event.getEntity().getMaxHealth()));
        } else if (event.getBlockedDamage() > 0 || event.getOriginalDamage() > 0) {
            journal.record("block", name(event.getEntity()) + " 抵挡了来自 " + source + " 的伤害");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHeal(LivingHealEvent event) {
        if (event.isCanceled()) return;
        BattleJournal journal = journal(event.getEntity());
        if (journal == null || !Float.isFinite(event.getAmount()) || event.getEntity().getHealth() <= 0) return;
        float actual = Math.min(event.getAmount(), event.getEntity().getMaxHealth() - event.getEntity().getHealth());
        if (actual > 0) journal.record("heal", name(event.getEntity()) + " 恢复 " + amount(actual) + " 生命");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        BattleJournal journal = journal(event.getEntity());
        if (journal != null) journal.record("death", name(event.getEntity()) + " 已被击败");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEffectAdded(MobEffectEvent.Added event) {
        BattleJournal journal = journal(event.getEntity());
        MobEffectInstance effect = event.getEffectInstance();
        if (journal == null || effect == null) return;
        String duration = effect.isInfiniteDuration() ? "持续" : Math.max(1, (effect.getDuration() + 19) / 20) + "秒";
        journal.record("effect", name(event.getEntity()) + (event.getOldEffectInstance() == null ? " 获得 " : " 更新 ")
                + Component.translatable(effect.getDescriptionId()).getString() + " "
                + (effect.getAmplifier() + 1) + "级 · " + duration);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        if (event.isCanceled() || event.getEffectInstance() == null) return;
        recordEffectEnd(event, " 被移除");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEffectExpired(MobEffectEvent.Expired event) {
        if (event.isCanceled()) return;
        recordEffectEnd(event, " 已结束");
    }

    private static void recordEffectEnd(MobEffectEvent event, String suffix) {
        BattleJournal journal = journal(event.getEntity());
        MobEffectInstance effect = event.getEffectInstance();
        if (journal != null && effect != null) journal.record("effect", name(event.getEntity()) + " 的 "
                + Component.translatable(effect.getDescriptionId()).getString() + suffix);
    }
}
