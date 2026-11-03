package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.BattleDiscoveryClientState;
import com.tactical.neoforge.client.BattleLog;
import com.tactical.neoforge.client.SkillClientState;
import com.tactical.neoforge.client.ui.platform.ClientText;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the battle HUD draws, captured once per frame.
 *
 * <p>The HUD never reads battle state while it draws: it draws this snapshot. That keeps one frame
 * internally consistent (a roster cannot show a dead actor next to a live portrait) and it keeps the
 * records below free of Minecraft types, so their formatting rules can be tested headlessly.</p>
 *
 * <p>All of it is a client mirror of server-stamped packets. Nothing here is a server-side readout
 * and nothing here can change the fight.</p>
 */
public record HudModel(Roster roster, Vitals vitals, Charge charge, Journal journal, String hintText) {

    /** One roster line: the entity id is the identity that binds hover, the world box and the rail. */
    public record RosterRow(int entityId, String name, int hp, int maxHp, boolean ally, boolean current,
                            boolean dead) {

        public double fraction() {
            int max = Math.max(1, maxHp);
            return Math.max(0.0D, Math.min(1.0D, Math.max(0, hp) / (double) max));
        }

        public String health() {
            return Math.max(0, hp) + " / " + Math.max(1, maxHp);
        }
    }

    public record Roster(List<RosterRow> rows) {
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /**
     * The roster footer keeps the old HUD data on purpose: the AP line, the turn recovery (or the
     * defence state) and the weapon line. The command panel shows its own AP and does not replace it.
     */
    public record Vitals(int round, String actorName, int ap, int maxAp, int apRecovery, boolean defending,
                         String weaponText) {

        /** Nothing but the numbers: the AP unit and the slash are not translated, the label is a key. */
        public String apValue() {
            return Integer.toString(ap) + " / " + Math.max(0, maxAp);
        }

        /** The actor's own name, or nothing while the snapshot has no actor (the painter words it). */
        public boolean hasActor() {
            return actorName != null && !actorName.isEmpty();
        }
    }

    public record Charge(int[] ids, int[] gauges, int actorEntityId) {
        public boolean isEmpty() {
            return ids.length == 0;
        }
    }

    public record Entry(long sequence, String label, String text) {
    }

    /**
     * The journal's visible state.
     *
     * @param collapsedNow the effective body state, i.e. the saved preference in the standard form
     *                     and the in-form toggle in the tight and narrow forms
     * @param bodyShown    whether the body may be drawn at all
     * @param entries      the history window, oldest first, already bounded by
     *                     {@link com.tactical.neoforge.client.BattleLogState#VISIBLE_LINES}
     */
    /**
     * The journal this frame. {@code entries} is the whole retained history, oldest first: which of
     * them are drawn is the layout's window, and the footer's count is taken from exactly those.
     *
     * <p>This is also the journal's only resolution point: the retained rows keep the server's component
     * structure, and each drawn row is resolved here in this client's own language. A language change
     * therefore re-renders the rows the client already holds, and two players who joined the same
     * encounter read the same row in their own language.</p>
     */
    public record Journal(boolean visible, boolean collapsedNow, boolean bodyShown, List<Entry> entries,
                          String status) {
        static final Journal EMPTY = new Journal(false, true, false, List.of(), "");
    }

    public static HudModel capture(Minecraft minecraft) {
        return new HudModel(snapshotRoster(minecraft), snapshotVitals(), snapshotCharge(), snapshotJournal(),
                snapshotHint(minecraft));
    }

    /** Charge ids and gauge percentages, both ordered like the server's turn list. */
    private static Charge snapshotCharge() {
        return new Charge(BattleClientState.turnEntityIds.clone(), BattleClientState.gauges.clone(),
                BattleClientState.actorEntityId);
    }

    private static Vitals snapshotVitals() {
        return new Vitals(BattleClientState.round, text(BattleClientState.actor), BattleClientState.ap,
                BattleClientState.maxAp, BattleClientState.apRecovery, BattleClientState.defending,
                weaponText());
    }

    /**
     * The roster order is the old one: the turn order first, then anybody else who is a participant.
     * Membership is keyed by entity id, and the friendly colour comes from the server's own
     * {@code friendlyIds}; before that packet arrives every row is drawn as an enemy.
     */
    private static Roster snapshotRoster(Minecraft minecraft) {
        if (!BattleClientState.active || BattleClientState.participantHealth.isEmpty()) {
            return new Roster(List.of());
        }
        List<Integer> ids = new ArrayList<>();
        for (int id : BattleClientState.turnEntityIds) {
            if (BattleClientState.participantHealth.containsKey(id) && !ids.contains(id)) ids.add(id);
        }
        for (int id : BattleClientState.participantHealth.keySet()) {
            if (!ids.contains(id)) ids.add(id);
        }
        List<RosterRow> rows = new ArrayList<>(ids.size());
        for (int id : ids) {
            int[] health = BattleClientState.participantHealth.get(id);
            if (health == null) continue;
            rows.add(new RosterRow(id, nameFor(minecraft, id), health[0], health[1],
                    SkillClientState.friendly(id), id == BattleClientState.actorEntityId, health[0] <= 0));
        }
        return new Roster(List.copyOf(rows));
    }

    /** The live entity's own display name first; the snapshot's order labels are the fallback. */
    public static String nameFor(Minecraft minecraft, int id) {
        var level = minecraft == null ? null : minecraft.level;
        Entity entity = level == null ? null : level.getEntity(id);
        if (entity != null) return entity.getDisplayName().getString();
        for (int index = 0; index < BattleClientState.turnEntityIds.length && index < BattleClientState.order.size();
             index++) {
            if (BattleClientState.turnEntityIds[index] == id) return text(BattleClientState.order.get(index));
        }
        return ClientText.get(ClientText.Keys.HUD_ENTITY, Integer.toString(id));
    }

    /**
     * The HUD's own weapon wording; the command panel reuses it so both lines always agree.
     *
     * <p>The weapon's name is the server's own item text and stays as it is; only the suffix that says
     * how the weapon reaches is wording, and it is resolved here in this client's language.</p>
     */
    public static String weaponText() {
        String weapon = text(BattleClientState.weapon);
        if (weapon.isEmpty()) return "";
        if (BattleClientState.weaponKind == 0) return weapon + ClientText.get(ClientText.Keys.HUD_WEAPON_MELEE);
        String suffix = ClientText.get(BattleClientState.weaponKind == 2
                ? ClientText.Keys.HUD_WEAPON_CROSSBOW : ClientText.Keys.HUD_WEAPON_BOW,
                Integer.toString(BattleClientState.weaponRange));
        return weapon + suffix;
    }

    /**
     * A component as this frame's text.
     *
     * <p>Resolution happens here, at the display point, and only for this frame: the mirror keeps the
     * structure the server sent, so changing the game language re-reads every name without a resend.</p>
     */
    private static String text(net.minecraft.network.chat.Component text) {
        return text == null ? "" : text.getString();
    }

    private static Journal snapshotJournal() {
        if (!BattleLog.stateVisible()) return Journal.EMPTY;
        List<Entry> entries = new ArrayList<>();
        for (var packetEntry : BattleLog.retainedHistory()) {
            // Display point: the retained row stays a component, and only the string this frame draws is
            // resolved - in this client's language, on this client's thread. The category chip is a key
            // too, so the chip is worded (and therefore measured) in the same language as its row.
            entries.add(new Entry(packetEntry.sequence(),
                    ClientText.get(JournalLabels.label(packetEntry.category())),
                    packetEntry.text().getString()));
        }
        return new Journal(true, BattleLog.collapsedNow(), BattleLog.bodyShown(),
                List.copyOf(entries), BattleLog.statusText());
    }

    /**
     * The passive hint's wording, with the key it asks for. Only its measured width is needed to place
     * it, and both halves are resolved per frame so a language change needs no reconnect.
     */
    private static String snapshotHint(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.screen != null) return null;
        if (minecraft.options.hideGui || !BattleDiscoveryClientState.hintVisible()) return null;
        return ClientText.get(ClientText.Keys.HUD_HINT_PREFIX) + JournalLabels.HINT_KEY
                + ClientText.get(ClientText.Keys.HUD_HINT_SUFFIX);
    }

    /** Empty model, used while a battle is not active so the HUD can never draw stale rows. */
    public static HudModel empty() {
        return new HudModel(new Roster(List.of()), new Vitals(0, "", 0, 0, 0, false, ""),
                new Charge(new int[0], new int[0], -1), Journal.EMPTY, null);
    }

    /** Debug helper: entity id to name, in roster order. */
    public Map<Integer, String> names() {
        Map<Integer, String> names = new LinkedHashMap<>();
        for (RosterRow row : roster.rows()) names.put(row.entityId(), row.name());
        return names;
    }
}
