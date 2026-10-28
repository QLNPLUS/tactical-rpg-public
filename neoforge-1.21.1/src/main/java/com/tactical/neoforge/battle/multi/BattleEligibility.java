package com.tactical.neoforge.battle.multi;

import java.util.List;

/**
 * Participation eligibility, evaluated without any Minecraft type so the rules can be
 * unit tested and reused by invite and re-validation-at-accept.
 *
 * <p>This is deliberately separate from {@link BattleRelationPolicy}: being allowed to
 * join says nothing about whether you are friendly to somebody already inside.</p>
 */
public final class BattleEligibility {

    public enum Denial {
        NONE,
        SESSION_CLOSED,
        OFFLINE,
        DEAD,
        SPECTATOR,
        DIFFERENT_DIMENSION,
        OUTSIDE_ZONE,
        ALREADY_MEMBER,
        BATTLE_FULL,
        NOT_MANAGER,
        SELF_INVITE,
        NO_INVITE,
        INVITE_EXPIRED,
        SESSION_MISMATCH,
        /** An animation, projectile or turn hand-over is in flight; join again later. */
        NOT_A_SAFE_BOUNDARY,
        /** The candidate's cell is taken, including a cell a moving actor is about to occupy. */
        OCCUPIED,
        /** The server could not resolve a live entity for an otherwise eligible candidate. */
        UNRESOLVED
    }

    /** Everything the rules need, already resolved by the caller. */
    public record Facts(boolean online, boolean alive, boolean spectator, boolean sameDimension,
                        boolean insideZone, boolean alreadyMember, int memberCount, int capacity,
                        boolean closed) {

        public static Facts of(boolean online, boolean alive, boolean spectator, boolean sameDimension,
                              boolean insideZone, boolean alreadyMember, int memberCount, int capacity,
                              boolean closed) {
            return new Facts(online, alive, spectator, sameDimension, insideZone, alreadyMember,
                    memberCount, capacity, closed);
        }

        /** A closed session, an offline/dead/spectator candidate or a full roster can never join. */
        public Facts withAlreadyMember(boolean value) {
            return new Facts(online, alive, spectator, sameDimension, insideZone, value,
                    memberCount, capacity, closed);
        }
    }

    private BattleEligibility() { }

    /** Ordered so the most fundamental refusal wins; NONE means "may participate". */
    public static Denial evaluate(Facts facts) {
        if (facts == null) return Denial.OFFLINE;
        if (facts.closed()) return Denial.SESSION_CLOSED;
        if (!facts.online()) return Denial.OFFLINE;
        if (!facts.alive()) return Denial.DEAD;
        if (facts.spectator()) return Denial.SPECTATOR;
        if (!facts.sameDimension()) return Denial.DIFFERENT_DIMENSION;
        if (!facts.insideZone()) return Denial.OUTSIDE_ZONE;
        if (facts.alreadyMember()) return Denial.ALREADY_MEMBER;
        if (facts.memberCount() >= facts.capacity()) return Denial.BATTLE_FULL;
        return Denial.NONE;
    }

    /**
     * A join is only admitted at a safe action boundary: nothing may be animating and the
     * candidate's cell must be free. The invitation is left pending by the caller, so a
     * refused accept can simply be retried instead of inserting a unit mid-path.
     */
    public static Denial evaluateJoinBoundary(boolean busy, boolean cellFree) {
        if (busy) return Denial.NOT_A_SAFE_BOUNDARY;
        if (!cellFree) return Denial.OCCUPIED;
        return Denial.NONE;
    }

    /** Human readable reason kept server side; never echoed as trusted client state. */
    public static String describe(Denial denial) {
        return switch (denial) {
            case NONE -> "";
            case SESSION_CLOSED -> "战斗已经结束";
            case OFFLINE -> "目标玩家不在线";
            case DEAD -> "目标玩家已阵亡";
            case SPECTATOR -> "旁观者不能参战";
            case DIFFERENT_DIMENSION -> "只能加入同一维度的战斗";
            case OUTSIDE_ZONE -> "目标玩家不在战斗区域内";
            case ALREADY_MEMBER -> "该玩家已在战斗中";
            case BATTLE_FULL -> "参战人数已达上限";
            case NOT_MANAGER -> "只有战斗管理者可以发出邀请";
            case SELF_INVITE -> "不能邀请自己";
            case NO_INVITE -> "没有待接受的邀请";
            case INVITE_EXPIRED -> "邀请已过期";
            case SESSION_MISMATCH -> "邀请不属于当前战斗";
            case NOT_A_SAFE_BOUNDARY -> "战斗正在结算动作，请稍后再接受邀请";
            case OCCUPIED -> "你所在的位置已被占用，请移动后重试";
            case UNRESOLVED -> "无法解析该玩家";
        };
    }

    public static List<Denial> valuesForTesting() {
        return List.of(Denial.values());
    }
}
