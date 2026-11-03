package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.skill.SkillDefinition;

import java.util.UUID;

/**
 * The one HUD entity-target entry: which action a press on a roster row or a rail portrait means, and
 * what the matching release is still allowed to execute.
 *
 * <p>Everything here is plain data and arithmetic on purpose - the client {@link net.minecraft.client.Minecraft}
 * side resolves the row to a stable id and sends the intent, while this class owns the two rules that
 * must not drift: a row is only choosable when the current mode targets a single entity, and one real
 * press/release pair may execute at most once for the id it captured at press time.</p>
 *
 * <p>Only four modes have an entity meaning. {@link BattleMode#NONE} never acts, {@link BattleMode#MOVE},
 * {@link BattleMode#PLACE}, {@link BattleMode#BREAK} and a ground-aimed skill stay battlefield clicks: the
 * entity's feet must not be turned into a ground anchor.</p>
 */
public final class HudEntityTarget {

    /** What a press on this row would do right now. */
    public enum Action {
        /** The row is not a target: the HUD only swallows the click. */
        NONE,
        ATTACK,
        /** A skill whose aim is this row itself (entity aim, or the caster's own row for self aim). */
        SKILL,
        /** The main-hand item used on the row through the native entity interaction. */
        ITEM
    }

    /**
     * What the row is, as far as the client can tell: the identity it binds to (the stable id resolved
     * from the loaded world entity, or null when that entity is not loaded) plus the flags the routing
     * needs. Never a server readout.
     */
    public record Row(UUID id, boolean alive, boolean mob, boolean participant, boolean self) {
        public static final Row UNKNOWN = new Row(null, false, false, false, false);
    }

    private HudEntityTarget() {
    }

    /**
     * True when the mode can target one entity at all. Only these modes give roster rows and portraits
     * a selectable appearance; every other mode keeps the plain hover look.
     */
    public static boolean entityMode(BattleMode mode, SkillDefinition.Aim aim) {
        return switch (mode) {
            case ATTACK, USE_ITEM -> true;
            case SKILL -> aim == SkillDefinition.Aim.ENTITY || aim == SkillDefinition.Aim.SELF;
            default -> false;
        };
    }

    /**
     * The action a press on {@code row} would perform in this mode.
     *
     * <p>Every action requires the row to be a participant of this encounter: a unit that already left,
     * or a bystander the roster no longer speaks for, must not stay clickable behind a stale row.</p>
     */
    public static Action action(BattleMode mode, SkillDefinition.Aim aim, Row row) {
        if (mode == null || row == null || row.id() == null || !row.alive() || !row.participant()) return Action.NONE;
        return switch (mode) {
            // The world path only ever attacks a mob, and the server only knows battle enemies, so a
            // row that is not a mob is not an attack target here either.
            case ATTACK -> row.mob() ? Action.ATTACK : Action.NONE;
            case SKILL -> {
                if (aim == SkillDefinition.Aim.ENTITY) yield Action.SKILL;
                if (aim == SkillDefinition.Aim.SELF) yield row.self() ? Action.SKILL : Action.NONE;
                yield Action.NONE;
            }
            case USE_ITEM -> Action.ITEM;
            default -> Action.NONE;
        };
    }

    /**
     * Why a row is not choosable, as a wording key the row's tooltip resolves. A choosable row has no
     * reason. The key is not a译文: {@code ClientText} turns it into the player's own language at the
     * display point, and this class stays free of Minecraft types.
     */
    public static String blockedReason(BattleMode mode, SkillDefinition.Aim aim, Row row) {
        if (row == null || row.id() == null) return ClientText.Keys.ENTITY_NO_TARGET;
        if (!row.alive()) return ClientText.Keys.ENTITY_DEAD;
        if (!row.participant()) return ClientText.Keys.ENTITY_NOT_PARTICIPANT;
        if (action(mode, aim, row) != Action.NONE) return "";
        if (!entityMode(mode, aim)) {
            return switch (mode == null ? BattleMode.NONE : mode) {
                case MOVE -> ClientText.Keys.ENTITY_MOVE_GROUND;
                case PLACE -> ClientText.Keys.ENTITY_PLACE_GROUND;
                case BREAK -> ClientText.Keys.ENTITY_BREAK_GROUND;
                case SKILL -> ClientText.Keys.ENTITY_SKILL_GROUND;
                case NONE -> ClientText.Keys.ENTITY_NONE;
                default -> ClientText.Keys.ENTITY_MODE_NOT_ENTITY;
            };
        }
        return switch (mode) {
            case ATTACK -> ClientText.Keys.ENTITY_NOT_ENEMY;
            case SKILL -> aim == SkillDefinition.Aim.SELF
                    ? ClientText.Keys.ENTITY_SELF_ONLY : ClientText.Keys.ENTITY_NOT_PARTICIPANT;
            default -> "";
        };
    }

    /**
     * A capture is the press half of one gesture. It stores the stable id and the whole action context,
     * so a release can never act on whatever row happens to sit under the pointer afterwards.
     *
     * <p>{@code selection} is the identity of the chosen command itself: the selected skill's id for a
     * skill cast, empty otherwise. Two entity skills share the same mode and aim, so without it a press
     * made for one skill could cast the other.</p>
     */
    public static final class Capture {
        private final UUID id;
        private final Action action;
        private final UUID session;
        private final long epoch;
        private final BattleMode mode;
        private final SkillDefinition.Aim aim;
        private final String selection;
        private final int slot;
        private boolean used;

        public Capture(UUID id, Action action, UUID session, long epoch, BattleMode mode, SkillDefinition.Aim aim,
                       String selection, int slot) {
            this.id = id;
            this.action = action;
            this.session = session;
            this.epoch = epoch;
            this.mode = mode;
            this.aim = aim;
            this.selection = selection == null ? "" : selection;
            this.slot = slot;
        }

        public UUID id() {
            return id;
        }

        public Action action() {
            return action;
        }

        public String selection() {
            return selection;
        }

        public boolean used() {
            return used;
        }

        /**
         * The release half of the same gesture.
         *
         * <p>It executes only when nothing that mattered at press time moved: the same active participant
         * is under the pointer (its stable id, not its possibly recycled numeric id), the same encounter
         * generation still rules this client, the same mode, aim and chosen skill are still selected, and
         * the action that row means has not changed. Whatever the answer is, the capture is spent: a
         * second release of the same press can never act.</p>
         */
        public boolean release(Action nowAction, Row nowRow, UUID nowSession, long nowEpoch, BattleMode nowMode,
                               SkillDefinition.Aim nowAim, String nowSelection, int nowSlot) {
            boolean allowed = !used && id != null && action != null && action != Action.NONE
                    && nowRow != null && id.equals(nowRow.id()) && nowRow.alive() && nowRow.participant()
                    && nowAction == action
                    && nowMode == mode
                    && nowAim == aim
                    && (nowSelection == null ? "" : nowSelection).equals(selection)
                    && nowSlot == slot
                    && (session == null ? nowSession == null : session.equals(nowSession))
                    && epoch == nowEpoch;
            used = true;
            return allowed;
        }

        /** The press is abandoned: a lost focus, a layer above the HUD, a closed page or a changed mode. */
        public void cancel() {
            used = true;
        }
    }
}
