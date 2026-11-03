package com.tactical.neoforge.client.ui.platform;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The one adapter the client draws its own wording through.
 *
 * <p>The two shipped language files are the only source of truth: this class holds the key names, and
 * every visible string is resolved per call, in the player's current language, from
 * {@link Component#translatable}. There is no second Java translation table, no cached wording and no
 * guessing "this string looks like a key": a caller either passes a key from {@link Keys} or passes
 * text it already owns (a player name, an item name, a number).</p>
 *
 * <p>Resolution happens at the display point, once per frame. That is what makes a language change
 * show up on the next frame without a cached layout, and it keeps the pure models
 * ({@code JournalLabels}, {@code HudModel.Vitals}, {@code HudEntityTarget}) free of Minecraft types:
 * they report keys, the painter asks this class for the wording.</p>
 */
public final class ClientText {

    /** Stands in for a missing key so a broken language file is visible instead of blank. */
    public static final String MISSING_PREFIX = "?";

    private ClientText() {
    }

    /** The key's wording in the current language; {@code args} fill the template's placeholders. */
    public static String get(String key, Object... args) {
        return component(key, args).getString();
    }

    /**
     * The key's component, still unresolved. Used where a component is needed (tooltips, chat-like
     * sinks); everything that measures text asks {@link #get} instead.
     */
    public static Component component(String key, Object... args) {
        if (key == null || key.isEmpty()) return Component.empty();
        return args == null || args.length == 0 ? Component.translatable(key) : Component.translatable(key, args);
    }

    /**
     * Every wording key this class ships. Built from the constants below, so a key can never exist in
     * one place and be forgotten in the list the language test walks.
     */
    public static final List<String> ALL_KEYS = collectKeys();

    private static List<String> collectKeys() {
        List<String> keys = new ArrayList<>();
        for (java.lang.reflect.Field field : Keys.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != String.class) continue;
            try {
                keys.add((String) field.get(null));
            } catch (IllegalAccessException ignored) {
                // A private key constant is a programming error the tests catch by count mismatch.
            }
        }
        return List.copyOf(keys);
    }

    /**
     * The client's own wording, one constant per key. Grouped by the surface that draws it, because the
     * coverage record and the tests both walk these groups.
     *
     * <p>Namespaces: {@code tactical.ui.command.*} the command panel, {@code tactical.ui.hud.*} the four
     * HUDs, {@code tactical.ui.journal.*} the journal's category chips, {@code tactical.ui.aim.*},
     * {@code tactical.ui.facing.*}, {@code tactical.ui.skill.*}, {@code tactical.ui.nearby.*},
     * {@code tactical.ui.move.*}, {@code tactical.ui.range.*}, {@code tactical.ui.entity.*} the target
     * tooltips, {@code tactical.ui.gear.*} the size popover, {@code tactical.ui.inv.*} the AUI inventory
     * page. Control ids, slot ids and action words ({@code move}, {@code attack}, ...) are not keys.</p>
     */
    public static final class Keys {

        private Keys() {
        }

        // ------------------------------------------------------------------ command panel
        public static final String COMMAND_TITLE = "tactical.ui.command.title";
        public static final String COMMAND_AP_LABEL = "tactical.ui.command.ap_label";
        public static final String COMMAND_HP_LABEL = "tactical.ui.command.hp_label";
        public static final String COMMAND_MP_LABEL = "tactical.ui.command.mp_label";
        /**
         * The two-column vitals row has half the panel's inner width, so English uses the same short forms
         * the specification keeps untranslated everywhere else ("HP", "MP"); Chinese keeps its full words.
         */
        public static final String COMMAND_HP_LABEL_SHORT = "tactical.ui.command.hp_label_short";
        public static final String COMMAND_MP_LABEL_SHORT = "tactical.ui.command.mp_label_short";
        public static final String COMMAND_WEAPON_LABEL = "tactical.ui.command.weapon_label";
        public static final String COMMAND_WEAPON_PENDING = "tactical.ui.command.weapon_pending";
        public static final String COMMAND_ACTOR_WAITING = "tactical.ui.command.actor_waiting";
        public static final String COMMAND_ROUND = "tactical.ui.command.round";
        public static final String COMMAND_FOOTER = "tactical.ui.command.footer";
        public static final String COMMAND_FOOTER_DEFENDING = "tactical.ui.command.footer_defending";
        /** Appended while the encounter is still waiting for falling units to land. */
        public static final String COMMAND_FOOTER_FALLING = "tactical.ui.command.footer_falling";
        public static final String COMMAND_ROW_MOVE = "tactical.ui.command.row.move";
        public static final String COMMAND_ROW_MOVE_DESC = "tactical.ui.command.row.move_desc";
        /** The read-only character row: it opens the character page and is never a battle action. */
        public static final String COMMAND_ROW_CHARACTER = "tactical.ui.command.row.character";
        public static final String COMMAND_ROW_CHARACTER_DESC = "tactical.ui.command.row.character_desc";
        public static final String COMMAND_ROW_SKILL = "tactical.ui.command.row.skill";
        public static final String COMMAND_ROW_SKILL_DESC = "tactical.ui.command.row.skill_desc";
        public static final String COMMAND_ROW_ATTACK = "tactical.ui.command.row.attack";
        public static final String COMMAND_ROW_ATTACK_DESC = "tactical.ui.command.row.attack_desc";
        public static final String COMMAND_ROW_TOOL = "tactical.ui.command.row.tool";
        public static final String COMMAND_ROW_TOOL_DESC = "tactical.ui.command.row.tool_desc";
        /**
         * No longer drawn by the command panel, which words the item row the same way for every hand.
         * Kept with its wording so a client written against the previous panel still resolves it; the
         * matching in-game hint below is still what the refused item command says.
         */
        public static final String COMMAND_ROW_TOOL_BLOCK_DESC = "tactical.ui.command.row.tool_block_desc";
        /** The line shown when the item row is pressed with a block in hand. */
        public static final String COMMAND_ROW_TOOL_BLOCK_HINT = "tactical.ui.command.row.tool_block_hint";
        public static final String COMMAND_ROW_BLOCK = "tactical.ui.command.row.block";
        /**
         * The block row's fixed description. The row names the command, not the hand: placement and
         * breaking are both what it does, and which one happens is decided when the action is taken.
         */
        public static final String COMMAND_ROW_BLOCK_DESC = "tactical.ui.command.row.block_desc";
        /** No longer drawn by the command panel; kept with its wording, see the item row note above. */
        public static final String COMMAND_ROW_BLOCK_PLACE_DESC = "tactical.ui.command.row.block_place_desc";
        /** No longer drawn by the command panel; kept with its wording, see the item row note above. */
        public static final String COMMAND_ROW_BLOCK_BREAK_DESC = "tactical.ui.command.row.block_break_desc";
        public static final String COMMAND_ROW_DEFEND = "tactical.ui.command.row.defend";
        public static final String COMMAND_ROW_DEFEND_DESC = "tactical.ui.command.row.defend_desc";
        public static final String COMMAND_ROW_END = "tactical.ui.command.row.end";
        public static final String COMMAND_ROW_END_DESC = "tactical.ui.command.row.end_desc";
        public static final String COMMAND_ROW_FLEE = "tactical.ui.command.row.flee";
        public static final String COMMAND_ROW_FLEE_DESC = "tactical.ui.command.row.flee_desc";
        public static final String COMMAND_COST_TERRAIN = "tactical.ui.command.cost.terrain";
        public static final String COMMAND_COST_SKILL = "tactical.ui.command.cost.skill";

        // ---- companion command surface ---------------------------------------------------
        /** The identity chip of a controlled companion. It is never shortened to "Pet". */
        public static final String COMMAND_CHIP_COMPANION = "tactical.ui.command.chip.companion";
        /** Footer before the first accepted order: "Commanding: <name>". */
        public static final String COMMAND_FOOTER_COMMANDING = "tactical.ui.command.footer_commanding";
        /** The skill row's description while a companion is commanding. */
        public static final String COMMAND_ROW_SKILL_DESC_COMPANION = "tactical.ui.command.row.skill_desc_companion";
        /** The empty companion skill page's single line. */
        public static final String SKILL_COMPANION_EMPTY = "tactical.ui.skill.companion_empty";
        /** The aiming and facing pages' actor row label. */
        public static final String COMMAND_ACTOR_LABEL = "tactical.ui.command.actor_label";
        /** A value that has no authoritative counterpart for this actor, drawn as a dash. */
        public static final String COMMAND_VALUE_NONE = "tactical.ui.command.value_none";

        // ------------------------------------------------------------------ shared screens
        /** "Back to commands": the footer button every tactical page draws. */
        public static final String BACK_TO_COMMANDS = "tactical.ui.screen.back_commands";

        // ------------------------------------------------------------------ HUD
        public static final String HUD_ROUND_LABEL = "tactical.ui.hud.round_label";
        public static final String HUD_ACTOR_WAITING = "tactical.ui.hud.actor_waiting";
        public static final String HUD_RECOVERY = "tactical.ui.hud.recovery";
        /**
         * The recovery line's bare value ("+50"). It is the part that may never be dropped: when the
         * whole line does not fit beside the AP figures, the wording goes and this number stays.
         */
        public static final String HUD_RECOVERY_AMOUNT = "tactical.ui.hud.recovery_amount";
        public static final String HUD_DEFENDING = "tactical.ui.hud.defending";
        public static final String HUD_WEAPON_MELEE = "tactical.ui.hud.weapon_melee";
        public static final String HUD_WEAPON_BOW = "tactical.ui.hud.weapon_bow";
        public static final String HUD_WEAPON_CROSSBOW = "tactical.ui.hud.weapon_crossbow";
        public static final String HUD_ENTITY = "tactical.ui.hud.entity";
        public static final String HUD_JOURNAL_TITLE = "tactical.ui.hud.journal_title";
        public static final String HUD_ACTING = "tactical.ui.hud.acting";
        public static final String HUD_STATUS_LATEST = "tactical.ui.hud.status_latest";
        public static final String HUD_STATUS_HISTORY = "tactical.ui.hud.status_history";
        public static final String HUD_COUNT = "tactical.ui.hud.count";
        public static final String HUD_TOGGLE_COLLAPSE = "tactical.ui.hud.toggle_collapse";
        public static final String HUD_TOGGLE_EXPAND = "tactical.ui.hud.toggle_expand";
        public static final String HUD_HINT_PREFIX = "tactical.ui.hud.hint_prefix";
        public static final String HUD_HINT_SUFFIX = "tactical.ui.hud.hint_suffix";

        // ------------------------------------------------------------------ journal category chips
        public static final String JOURNAL_BATTLE = "tactical.ui.journal.battle";
        public static final String JOURNAL_TURN = "tactical.ui.journal.turn";
        public static final String JOURNAL_MOVE = "tactical.ui.journal.move";
        public static final String JOURNAL_ATTACK = "tactical.ui.journal.attack";
        public static final String JOURNAL_DAMAGE = "tactical.ui.journal.damage";
        public static final String JOURNAL_HEAL = "tactical.ui.journal.heal";
        public static final String JOURNAL_EFFECT = "tactical.ui.journal.effect";
        public static final String JOURNAL_DEATH = "tactical.ui.journal.death";
        public static final String JOURNAL_BLOCK = "tactical.ui.journal.block";
        public static final String JOURNAL_REJECT = "tactical.ui.journal.reject";
        public static final String JOURNAL_ENEMY = "tactical.ui.journal.enemy";
        public static final String JOURNAL_INVENTORY = "tactical.ui.journal.inventory";
        public static final String JOURNAL_ACTION = "tactical.ui.journal.action";

        // ------------------------------------------------------------------ aim page
        public static final String AIM_TITLE_MOVE = "tactical.ui.aim.title.move";
        public static final String AIM_TITLE_ATTACK = "tactical.ui.aim.title.attack";
        public static final String AIM_TITLE_PLACE = "tactical.ui.aim.title.place";
        public static final String AIM_TITLE_BREAK = "tactical.ui.aim.title.break";
        public static final String AIM_TITLE_USE_ITEM = "tactical.ui.aim.title.use_item";
        public static final String AIM_TITLE_SKILL = "tactical.ui.aim.title.skill";
        public static final String AIM_TITLE_DEFAULT = "tactical.ui.aim.title.default";
        public static final String AIM_HINT_MOVE = "tactical.ui.aim.hint.move";
        public static final String AIM_HINT_ATTACK = "tactical.ui.aim.hint.attack";
        public static final String AIM_HINT_PLACE = "tactical.ui.aim.hint.place";
        public static final String AIM_HINT_BREAK = "tactical.ui.aim.hint.break";
        public static final String AIM_HINT_USE_ITEM = "tactical.ui.aim.hint.use_item";
        public static final String AIM_HINT_SKILL = "tactical.ui.aim.hint.skill";
        public static final String AIM_HINT_DEFAULT = "tactical.ui.aim.hint.default";
        public static final String AIM_COST_LEAD = "tactical.ui.aim.cost_lead";
        public static final String AIM_SLOT_NO_GROUND = "tactical.ui.aim.slot.no_ground";
        public static final String AIM_SLOT_LANDING = "tactical.ui.aim.slot.landing";
        public static final String AIM_SLOT_REACHABLE = "tactical.ui.aim.slot.reachable";
        public static final String AIM_SLOT_UNREACHABLE = "tactical.ui.aim.slot.unreachable";
        public static final String AIM_SLOT_FACE = "tactical.ui.aim.slot.face";
        public static final String AIM_SLOT_BREAK = "tactical.ui.aim.slot.break";

        // ------------------------------------------------------------------ facing page
        public static final String FACING_TITLE = "tactical.ui.facing.title";
        public static final String FACING_NORTH = "tactical.ui.facing.north";
        public static final String FACING_SOUTH = "tactical.ui.facing.south";
        public static final String FACING_WEST = "tactical.ui.facing.west";
        public static final String FACING_EAST = "tactical.ui.facing.east";
        public static final String FACING_CONFIRM_LEAD = "tactical.ui.facing.confirm_lead";
        public static final String FACING_CONFIRM_TAIL = "tactical.ui.facing.confirm_tail";
        public static final String FACING_PLAIN = "tactical.ui.facing.plain";

        // ------------------------------------------------------------------ skill page and overlay
        public static final String SKILL_TITLE = "tactical.ui.skill.title";
        public static final String SKILL_SUBTITLE = "tactical.ui.skill.subtitle";
        public static final String SKILL_SYNCING = "tactical.ui.skill.syncing";
        public static final String SKILL_EMPTY = "tactical.ui.skill.empty";
        public static final String SKILL_PICK = "tactical.ui.skill.pick";
        public static final String SKILL_MP_RECOVERY = "tactical.ui.skill.mp_recovery";
        public static final String SKILL_COST_BY_TARGET = "tactical.ui.skill.cost_by_target";
        public static final String SKILL_COOLDOWN = "tactical.ui.skill.cooldown";
        public static final String SKILL_AIM_SELF = "tactical.ui.skill.aim.self";
        public static final String SKILL_AIM_ENTITY = "tactical.ui.skill.aim.entity";
        public static final String SKILL_AIM_GROUND = "tactical.ui.skill.aim.ground";
        public static final String SKILL_UNAVAILABLE = "tactical.ui.skill.unavailable";
        public static final String SKILL_WAITING_PREVIEW = "tactical.ui.skill.waiting_preview";
        public static final String SKILL_EFFECT = "tactical.ui.skill.effect";

        // ------------------------------------------------------------------ nearby battles
        public static final String NEARBY_TITLE = "tactical.ui.nearby.title";
        public static final String NEARBY_INFO = "tactical.ui.nearby.info";
        public static final String NEARBY_EMPTY = "tactical.ui.nearby.empty";
        public static final String NEARBY_REFRESH = "tactical.ui.nearby.refresh";
        public static final String NEARBY_CLOSE = "tactical.ui.nearby.close";
        public static final String NEARBY_UNKNOWN_MANAGER = "tactical.ui.nearby.unknown_manager";
        public static final String NEARBY_META = "tactical.ui.nearby.meta";
        public static final String NEARBY_JOINABLE = "tactical.ui.nearby.joinable";
        public static final String NEARBY_JOIN = "tactical.ui.nearby.join";
        public static final String NEARBY_DISMISS = "tactical.ui.nearby.dismiss";
        public static final String NEARBY_REFRESHING = "tactical.ui.nearby.refreshing";
        public static final String NEARBY_REFRESHING_SUFFIX = "tactical.ui.nearby.refreshing_suffix";
        public static final String NEARBY_THROTTLED = "tactical.ui.nearby.throttled";
        public static final String NEARBY_THROTTLED_SUFFIX = "tactical.ui.nearby.throttled_suffix";

        // ------------------------------------------------------------------ move choice
        public static final String MOVE_TITLE = "tactical.ui.move.title";
        public static final String MOVE_TARGET = "tactical.ui.move.target";
        public static final String MOVE_WALK = "tactical.ui.move.walk";
        public static final String MOVE_DROP = "tactical.ui.move.drop";
        public static final String MOVE_WARNING_FALL = "tactical.ui.move.warning_fall";
        public static final String MOVE_WARNING_BLOCKED = "tactical.ui.move.warning_blocked";
        public static final String MOVE_BACK = "tactical.ui.move.back";

        // ------------------------------------------------------------------ attack range legend
        public static final String RANGE_ORANGE = "tactical.ui.range.orange";
        public static final String RANGE_VALID_COUNT = "tactical.ui.range.valid_count";
        public static final String RANGE_PENDING = "tactical.ui.range.pending";
        public static final String RANGE_TRUNCATED = "tactical.ui.range.truncated";
        public static final String RANGE_HOVER = "tactical.ui.range.hover";
        public static final String RANGE_HOVER_OK = "tactical.ui.range.hover_ok";
        public static final String RANGE_HOVER_FAIL = "tactical.ui.range.hover_fail";

        // ------------------------------------------------------------------ entity target tooltips
        public static final String ENTITY_NO_TARGET = "tactical.ui.entity.no_target";
        public static final String ENTITY_DEAD = "tactical.ui.entity.dead";
        public static final String ENTITY_NOT_PARTICIPANT = "tactical.ui.entity.not_participant";
        public static final String ENTITY_MOVE_GROUND = "tactical.ui.entity.move_ground";
        public static final String ENTITY_PLACE_GROUND = "tactical.ui.entity.place_ground";
        public static final String ENTITY_BREAK_GROUND = "tactical.ui.entity.break_ground";
        public static final String ENTITY_SKILL_GROUND = "tactical.ui.entity.skill_ground";
        public static final String ENTITY_NONE = "tactical.ui.entity.none";
        public static final String ENTITY_MODE_NOT_ENTITY = "tactical.ui.entity.mode_not_entity";
        public static final String ENTITY_NOT_ENEMY = "tactical.ui.entity.not_enemy";
        public static final String ENTITY_SELF_ONLY = "tactical.ui.entity.self_only";

        // ------------------------------------------------------------------ gear popover
        /** The zoom preset's own reset. Not the HUD sizes' reset: the two controls sit in one popover. */
        public static final String GEAR_RESET_ZOOM = "tactical.ui.gear.reset_zoom";
        public static final String GEAR_LEGEND_ON = "tactical.ui.gear.legend_on";
        public static final String GEAR_LEGEND_OFF = "tactical.ui.gear.legend_off";
        public static final String GEAR_CLOSE = "tactical.ui.gear.close";

        // ------------------------------------------------------------------ AUI inventory page
        public static final String INV_TITLE = "tactical.ui.inv.title";
        public static final String INV_SUBTITLE = "tactical.ui.inv.subtitle";
        public static final String INV_BAG = "tactical.ui.inv.bag";
        public static final String INV_HOTBAR = "tactical.ui.inv.hotbar";
        public static final String INV_HINT = "tactical.ui.inv.hint";
        public static final String INV_BACK = "tactical.ui.inv.back";
        public static final String INV_SCALE_LABEL = "tactical.ui.inv.scale_label";
        public static final String INV_SCALE_RESET = "tactical.ui.inv.scale_reset";
        public static final String INV_CONTEXT_TITLE = "tactical.ui.inv.context_title";
        public static final String INV_CTX_PLACE = "tactical.ui.inv.ctx_place";
        public static final String INV_CTX_USE = "tactical.ui.inv.ctx_use";
        public static final String INV_CTX_MAIN = "tactical.ui.inv.ctx_main";
        public static final String INV_CTX_OFF = "tactical.ui.inv.ctx_off";
        public static final String INV_CTX_DROP = "tactical.ui.inv.ctx_drop";
        public static final String INV_CTX_CANCEL = "tactical.ui.inv.ctx_cancel";
    }
}
