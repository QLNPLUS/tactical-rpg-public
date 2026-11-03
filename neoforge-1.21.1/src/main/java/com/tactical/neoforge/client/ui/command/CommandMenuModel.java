package com.tactical.neoforge.client.ui.command;

import com.tactical.core.combat.BattleRules;
import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.SkillClientState;
import com.tactical.neoforge.client.ui.platform.ClientText;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The labels and values the command panel shows, taken from the existing client state only.
 *
 * <p>This class performs no action and holds no behaviour: it turns {@link BattleClientState} into
 * text and bar fractions so the drawing code stays a pure function of a snapshot. Everything it
 * reports is the same value the previous AUI page reported, so the panel's data cannot drift.</p>
 */
public final class CommandMenuModel {

    /** Defend cost shown by the command panel; the server still quotes the authoritative price. */
    public static final int DEFEND_COST = 25;

    public record Row(String action, String name, String cost, String description, boolean affordable,
                      boolean enabled) {
    }

    /** The AP prices the server quoted for this frame's rows; the panel never invents one. */
    public record Prices(int attack, int item, int place, int breakCost) {
    }

    /**
     * The row icon, named the way the item registry names it.
     *
     * <p>Every command row has one fixed picture. The main hand used to pick the attack and block
     * icons; the panel now shows the same nine rows whatever the player holds, so the picture states
     * what the command is instead of what is in the hand. The weapon line still reports the real
     * hand — that is state, not a command row.</p>
     */
    public static String rowIconId(String action) {
        return switch (action) {
            case "character" -> "minecraft:book";
            case "move" -> "minecraft:iron_boots";
            case "skill" -> "minecraft:enchanted_book";
            case "attack" -> "minecraft:iron_sword";
            case "tool" -> "minecraft:potion";
            case "block" -> "minecraft:iron_pickaxe";
            case "defend" -> "minecraft:iron_chestplate";
            case "end" -> "minecraft:clock";
            case "flee" -> "minecraft:feather";
            default -> "";
        };
    }

    public final String actor;
    public final String round;
    /** Current AP as digits: the only number the panel draws at twice the normal size. */
    public final String ap;
    /** "/ max" shown next to the AP value in secondary text. */
    public final String apMax;
    public final double apFraction;
    public final int apRecovery;
    public final String hp;
    public final double hpFraction;
    public final String mp;
    public final double mpFraction;
    /** HP without the spaces around the slash: the two-column form has no room for them. */
    public final String hpCompact;
    /** MP without the spaces around the slash: the two-column form has no room for them. */
    public final String mpCompact;
    /** Weapon line in the released wording; {@link #WEAPON_PENDING} until equipment is synced. */
    public final String weapon;
    /** False when no equipment packet has been received yet, which greys the weapon line. */
    public final boolean weaponSynced;
    public final String footer;
    public final List<Row> rows;
    /**
     * True while this frame's command surface acts for a controlled companion.
     *
     * <p>The panel is the same panel with fewer rows and a different actor; nothing about the layout is
     * companion-specific except what this flag and {@link #rows} say.</p>
     */
    public final boolean companion;
    /** The identity chip text ("Companion"), empty for a non-companion frame. */
    public final String identityChip;

    private CommandMenuModel(String actor, String round, String ap, String apMax, double apFraction, int apRecovery,
                             String hp, double hpFraction, String mp, double mpFraction,
                             String weapon, boolean weaponSynced, String footer,
                             List<Row> rows, boolean companion, String identityChip) {
        this.actor = actor;
        this.round = round;
        this.ap = ap;
        this.apMax = apMax;
        this.apFraction = apFraction;
        this.apRecovery = apRecovery;
        this.hp = hp;
        this.hpFraction = hpFraction;
        this.hpCompact = compactValue(hp);
        this.mp = mp;
        this.mpFraction = mpFraction;
        this.mpCompact = compactValue(mp);
        this.weapon = weapon;
        this.weaponSynced = weaponSynced;
        this.footer = footer;
        this.rows = rows;
        this.companion = companion;
        this.identityChip = identityChip;
    }

    /**
     * {@code "100 / 100"} to {@code "100/100"}: the same value without the spaces around the slash,
     * which is the prototype's wording for the two-column vitals row. Nothing else changes, and a
     * value without a slash is returned untouched.
     */
    public static String compactValue(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace(" / ", "/");
    }

    /**
     * The weapon line: "&lt;weapon&gt; · 近战" for melee and "&lt;weapon&gt; · 射程 N" for ranged.
     *
     * <p>The weapon's own name is the server's item text and is never translated; the suffix is wording
     * and is passed in already resolved, so this stays a pure formatting function. Empty until the server
     * has told the client which item is held, which is what the pending key is for.</p>
     */
    public static String weaponLine(String weapon, int kind, String meleeSuffix, String rangedSuffix) {
        if (weapon == null || weapon.isEmpty()) return "";
        return weapon + (kind == 0 ? meleeSuffix : rangedSuffix);
    }

    /** A component as this frame's text; resolved at the display point, never cached into the mirror. */
    private static String text(net.minecraft.network.chat.Component text) {
        return text == null ? "" : text.getString();
    }

    /** Snapshot for one frame. Reads the client mirror; never sends anything. */
    public static CommandMenuModel snapshot(Minecraft minecraft) {
        boolean companion = BattleClientState.isCommandingCompanion();
        String actor = companion ? text(BattleClientState.command.name()) : text(BattleClientState.actor);
        if (companion && actor.isEmpty()) actor = ClientText.get(ClientText.Keys.COMMAND_ACTOR_WAITING);
        else actor = actor.isEmpty() ? ClientText.get(ClientText.Keys.COMMAND_ACTOR_WAITING) : actor;
        String round = ClientText.get(ClientText.Keys.COMMAND_ROUND, Integer.toString(BattleClientState.round));
        int ap = BattleClientState.commandAp();
        int maxAp = Math.max(1, BattleClientState.commandMaxAp());

        int hp;
        int maxHp;
        if (companion) {
            // A companion's HP is its own; the server sends it in the projection, never the owner's.
            hp = Math.max(0, BattleClientState.command.hp());
            maxHp = Math.max(1, BattleClientState.command.maxHp());
        } else {
            int[] health = minecraft != null && minecraft.player != null
                    ? BattleClientState.participantHealth.get(minecraft.player.getId()) : null;
            if (health != null) {
                hp = Math.max(0, health[0]);
                maxHp = Math.max(1, health[1]);
            } else if (minecraft != null && minecraft.player != null) {
                hp = Math.round(minecraft.player.getHealth());
                maxHp = Math.max(1, Math.round(minecraft.player.getMaxHealth()));
            } else {
                hp = 0;
                maxHp = 1;
            }
        }
        String hpText = hp + " / " + maxHp;
        double mpNow;
        String mpText;
        double mpMax;
        if (companion) {
            // No authoritative companion MP exists yet, so the column shows the existing "no value" dash on
            // an empty track. The owner's MP is never borrowed, and no number is invented.
            mpNow = 0.0D;
            mpMax = 1.0D;
            mpText = ClientText.get(ClientText.Keys.COMMAND_VALUE_NONE);
        } else {
            mpNow = Math.max(0.0D, BattleClientState.mp);
            mpMax = Math.max(1.0D, BattleClientState.maxMp);
            mpText = SkillClientState.number(mpNow) + " / " + SkillClientState.number(mpMax);
        }

        int paid = BattleClientState.commandPaidActions();
        String footer;
        if (companion && paid == 0) {
            // Before the first accepted order the footer says who is being commanded; afterwards it goes
            // back to the existing per-turn counter, so nothing new is gained in height.
            footer = ClientText.get(ClientText.Keys.COMMAND_FOOTER_COMMANDING, actor);
        } else {
            footer = ClientText.get(ClientText.Keys.COMMAND_FOOTER, Integer.toString(paid));
        }
        if (BattleClientState.commandDefending()) footer = footer + ClientText.get(ClientText.Keys.COMMAND_FOOTER_DEFENDING);

        String weaponLine = weaponLine(text(BattleClientState.commandWeaponName()), BattleClientState.commandWeaponKind(),
                ClientText.get(ClientText.Keys.HUD_WEAPON_MELEE),
                ClientText.get(ClientText.Keys.HUD_WEAPON_BOW, Integer.toString(BattleClientState.commandWeaponRange())));
        boolean weaponSynced = !weaponLine.isEmpty();
        String weaponText = weaponSynced ? weaponLine : ClientText.get(ClientText.Keys.COMMAND_WEAPON_PENDING);

        // What the main hand decides is whether the item row can be used at all, never how a row is
        // worded or drawn: the server refuses to place a block through the item command. A companion has no
        // item row at all, so the owner's hand is never consulted on its turn.
        boolean holdsBlock = !companion && minecraft != null && minecraft.player != null
                && minecraft.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem;
        List<Row> rows = companion
                ? companionRows(ap, BattleClientState.commandAttackCost(),
                        BattleClientState.command.abilities(), BattleClientState.terrainFalling)
                : rows(ap, new Prices(BattleClientState.attackCost, BattleClientState.itemCost,
                        BattleClientState.placeCost, BattleClientState.breakCost), holdsBlock,
                        BattleClientState.terrainFalling);
        if (BattleClientState.terrainFalling) {
            footer = footer + ClientText.get(ClientText.Keys.COMMAND_FOOTER_FALLING);
        }

        return new CommandMenuModel(actor, round, Integer.toString(ap), "/ " + BattleClientState.commandMaxAp(),
                ap / (double) maxAp, BattleClientState.commandApRecovery(), hpText, hp / (double) maxHp, mpText,
                mpNow / mpMax, weaponText, weaponSynced, footer, rows, companion,
                companion ? ClientText.get(ClientText.Keys.COMMAND_CHIP_COMPANION) : "");
    }

    /**
     * The five rows of a commandable companion: move, attack, skill, defend, end turn.
     *
     * <p>Nothing else is drawn - not the character page, the item, the block or the flee row. They are absent
     * rather than disabled, because a companion has no such order at all, and the server would refuse one.
     * The skill row stays clickable: it opens the empty companion page and spends nothing.</p>
     */
    public static List<Row> companionRows(int ap, int attackCost, int abilities, boolean falling) {
        // The adapter's declared order set is what the surface may offer: an order it does not declare is
        // drawn disabled here and refused by the server, which never trusts this list.
        boolean canMove = offers(abilities, com.tactical.neoforge.api.companion.CompanionAbility.MOVE);
        boolean canAttack = offers(abilities, com.tactical.neoforge.api.companion.CompanionAbility.ATTACK);
        boolean canSkill = offers(abilities, com.tactical.neoforge.api.companion.CompanionAbility.SKILL);
        boolean canDefend = offers(abilities, com.tactical.neoforge.api.companion.CompanionAbility.DEFEND);
        boolean canEnd = offers(abilities, com.tactical.neoforge.api.companion.CompanionAbility.END_TURN);
        List<Row> rows = new ArrayList<>(5);
        rows.add(new Row("move", ClientText.get(ClientText.Keys.COMMAND_ROW_MOVE),
                ClientText.get(ClientText.Keys.COMMAND_COST_TERRAIN),
                ClientText.get(ClientText.Keys.COMMAND_ROW_MOVE_DESC), false, canMove));
        rows.add(new Row("attack", ClientText.get(ClientText.Keys.COMMAND_ROW_ATTACK),
                attackCost + " AP", ClientText.get(ClientText.Keys.COMMAND_ROW_ATTACK_DESC),
                ap >= attackCost, canAttack));
        rows.add(new Row("skill", ClientText.get(ClientText.Keys.COMMAND_ROW_SKILL),
                "", ClientText.get(ClientText.Keys.COMMAND_ROW_SKILL_DESC_COMPANION), false, canSkill));
        rows.add(new Row("defend", ClientText.get(ClientText.Keys.COMMAND_ROW_DEFEND), DEFEND_COST + " AP",
                ClientText.get(ClientText.Keys.COMMAND_ROW_DEFEND_DESC), ap >= DEFEND_COST, canDefend));
        rows.add(new Row("end", ClientText.get(ClientText.Keys.COMMAND_ROW_END), "",
                ClientText.get(ClientText.Keys.COMMAND_ROW_END_DESC), false, canEnd));
        if (!falling) return rows;
        // A forced fall owns the encounter, so every acting row waits; the skill row only reads.
        List<Row> waiting = new ArrayList<>(rows.size());
        for (Row row : rows) {
            boolean readsOnly = "skill".equals(row.action());
            waiting.add(new Row(row.action(), row.name(), row.cost(), row.description(),
                    row.affordable(), readsOnly));
        }
        return waiting;
    }

    /**
     * The nine rows of one frame.
     *
     * <p>Presentation is static: a row's name, description and icon are the same for every hand and
     * every character, so the panel can never describe an action it would not perform. What stays
     * dynamic is what the row would really do — the price the server quoted, whether this turn can
     * afford it, and whether any command is possible at all.</p>
     *
     * <p>{@code holdsBlock} is the only hand fact that reaches the rows, and it only greys the item
     * row: with a block in hand that command would be refused. The block row keeps quoting the price
     * of the action it would really take, which is placement for a block and breaking otherwise.</p>
     *
     * <p>The first row is the character page. It is a view, not an action: no AP price, no badge, and
     * it stays enabled while every other row is waiting out a forced fall, because reading a character
     * is allowed in any state.</p>
     */
    public static List<Row> rows(int ap, Prices prices, boolean holdsBlock, boolean falling) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("character", ClientText.get(ClientText.Keys.COMMAND_ROW_CHARACTER), "",
                ClientText.get(ClientText.Keys.COMMAND_ROW_CHARACTER_DESC), false, true));
        rows.add(new Row("move", ClientText.get(ClientText.Keys.COMMAND_ROW_MOVE),
                ClientText.get(ClientText.Keys.COMMAND_COST_TERRAIN),
                ClientText.get(ClientText.Keys.COMMAND_ROW_MOVE_DESC), false, true));
        rows.add(new Row("attack", ClientText.get(ClientText.Keys.COMMAND_ROW_ATTACK),
                prices.attack() + " AP", ClientText.get(ClientText.Keys.COMMAND_ROW_ATTACK_DESC),
                ap >= prices.attack(), true));
        rows.add(new Row("skill", ClientText.get(ClientText.Keys.COMMAND_ROW_SKILL),
                ClientText.get(ClientText.Keys.COMMAND_COST_SKILL),
                ClientText.get(ClientText.Keys.COMMAND_ROW_SKILL_DESC), false, true));
        rows.add(new Row("tool", ClientText.get(ClientText.Keys.COMMAND_ROW_TOOL),
                prices.item() + " AP", ClientText.get(ClientText.Keys.COMMAND_ROW_TOOL_DESC),
                !holdsBlock && ap >= prices.item(), !holdsBlock));
        int blockCost = holdsBlock ? prices.place() : prices.breakCost();
        rows.add(new Row("block", ClientText.get(ClientText.Keys.COMMAND_ROW_BLOCK), blockCost + " AP",
                ClientText.get(ClientText.Keys.COMMAND_ROW_BLOCK_DESC), ap >= blockCost, true));
        rows.add(new Row("defend", ClientText.get(ClientText.Keys.COMMAND_ROW_DEFEND), DEFEND_COST + " AP",
                ClientText.get(ClientText.Keys.COMMAND_ROW_DEFEND_DESC), ap >= DEFEND_COST, true));
        rows.add(new Row("flee", ClientText.get(ClientText.Keys.COMMAND_ROW_FLEE), BattleRules.FLEE_COST + " AP",
                ClientText.get(ClientText.Keys.COMMAND_ROW_FLEE_DESC), ap >= BattleRules.FLEE_COST, true));
        rows.add(new Row("end", ClientText.get(ClientText.Keys.COMMAND_ROW_END), "",
                ClientText.get(ClientText.Keys.COMMAND_ROW_END_DESC), false, true));

        // A forced fall owns the encounter until every unit has landed, so no row may offer an action
        // while it runs. The server refuses them anyway; showing them as available would be a lie the
        // player only discovers as a rejection line in the journal. The character row is the exception:
        // it performs nothing, so it is not "an action while the encounter is busy".
        if (falling) {
            List<Row> waiting = new ArrayList<>(rows.size());
            for (Row row : rows) {
                boolean readsOnly = "character".equals(row.action());
                waiting.add(new Row(row.action(), row.name(), row.cost(), row.description(),
                        row.affordable(), readsOnly));
            }
            return waiting;
        }
        return rows;
    }

    /** True when the projection's order mask declares one ability. */
    private static boolean offers(int abilities, com.tactical.neoforge.api.companion.CompanionAbility ability) {
        return (abilities & ability.mask()) != 0;
    }

    public Row row(String action) {
        for (Row row : rows) {
            if (row.action().equals(action)) return row;
        }
        return null;
    }
}
