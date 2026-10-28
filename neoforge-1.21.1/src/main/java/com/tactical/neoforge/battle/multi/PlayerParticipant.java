package com.tactical.neoforge.battle.multi;

import com.tactical.core.grid.GridPos;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-player battle resources. Nothing in this type is shared between players:
 * AP, escalation counter, move cost, defence, own-turn initiative and the own
 * movement animation are all scoped to exactly one member.
 *
 * <p>Pure model: no Minecraft types, so isolation can be unit tested without a server.</p>
 */
public final class PlayerParticipant {

    /** A pending "walk around vs. drop down" choice; only its owner may answer it. */
    public record PendingMove(UUID token, GridPos from, GridPos target, long expires, int walkCost) {
        public PendingMove {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(target, "target");
        }
    }

    private final UUID playerId;
    private final String name;
    private int entityId = -1;

    private GridPos cell;
    private int ap;
    private int maxAp;
    private int paidActions;
    private int moveCost;
    private final int initiative;
    private int lastRecovery = -1;
    private boolean takenTurn;
    private boolean defending;

    private List<GridPos> path = List.of();
    private int pathIndex;
    private int stepProgress;
    private int motionTicks;
    private boolean moving;
    private boolean stepping;
    private boolean dropping;
    private GridPos stepTarget;
    private PendingMove pendingMove;

    public PlayerParticipant(UUID playerId, String name, GridPos cell, int ap, int maxAp,
                             int moveCost, int initiative) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.name = name == null ? "" : name;
        this.cell = Objects.requireNonNull(cell, "cell");
        this.ap = Math.max(0, ap);
        this.maxAp = Math.max(0, maxAp);
        this.moveCost = Math.max(0, moveCost);
        this.initiative = initiative;
    }

    public UUID playerId() { return playerId; }
    public String name() { return name; }

    public int entityId() { return entityId; }
    public void entityId(int value) { entityId = value; }

    public GridPos cell() { return cell; }
    public void cell(GridPos value) { cell = Objects.requireNonNull(value, "cell"); }

    public int ap() { return ap; }
    public int maxAp() { return maxAp; }
    public int moveCost() { return moveCost; }
    public int paidActions() { return paidActions; }
    public int initiative() { return initiative; }
    public int lastRecovery() { return lastRecovery; }
    public boolean takenTurn() { return takenTurn; }
    public boolean defending() { return defending; }

    public void maxAp(int value) {
        maxAp = Math.max(0, value);
        ap = Math.min(ap, maxAp);
    }

    public void moveCost(int value) { moveCost = Math.max(0, value); }

    public void ap(int value) { ap = Math.max(0, Math.min(value, maxAp)); }

    /** Clamp into the current cap without refilling. */
    public void clampAp() { ap = Math.max(0, Math.min(ap, maxAp)); }

    public void lastRecovery(int value) { lastRecovery = value; }

    /** Committed paid action: subtract cost and escalate the quote counter exactly once. */
    public void commitPaidAction(int cost) {
        ap = Math.max(0, ap - Math.max(0, cost));
        paidActions++;
    }

    /** Reserved action cancelled before it took effect; never refills above the cap. */
    public void refund(int amount, boolean countedPaid) {
        if (amount > 0) ap = Math.min(maxAp, ap + amount);
        if (countedPaid) paidActions = Math.max(0, paidActions - 1);
    }

    public void resetPaidActions() { paidActions = 0; }

    public void takenTurn(boolean value) { takenTurn = value; }
    public void defending(boolean value) { defending = value; }

    public List<GridPos> path() { return path; }
    public int pathIndex() { return pathIndex; }
    public int stepProgress() { return stepProgress; }
    public int motionTicks() { return motionTicks; }
    public boolean moving() { return moving; }
    public boolean stepping() { return stepping; }
    public boolean dropping() { return dropping; }
    public GridPos stepTarget() { return stepTarget; }
    public PendingMove pendingMove() { return pendingMove; }
    public void pendingMove(PendingMove value) { pendingMove = value; }

    public void beginMove(List<GridPos> route, boolean drop, int stepTicks, int dropTicks) {
        path = List.copyOf(route);
        pathIndex = 0;
        stepProgress = 0;
        stepping = false;
        dropping = drop;
        motionTicks = drop ? dropTicks : stepTicks;
        moving = !path.isEmpty();
    }

    public void pathIndex(int value) { pathIndex = value; }
    public void stepProgress(int value) { stepProgress = value; }
    public void moving(boolean value) { moving = value; }
    public void stepping(boolean value) { stepping = value; }
    public void stepTarget(GridPos value) { stepTarget = value; }

    /** Drop the whole own-turn animation without touching position authority. */
    public void clearMotion() {
        path = List.of();
        pathIndex = 0;
        stepProgress = 0;
        moving = false;
        stepping = false;
        dropping = false;
        stepTarget = null;
        pendingMove = null;
    }

    public boolean busy() { return moving; }
}
