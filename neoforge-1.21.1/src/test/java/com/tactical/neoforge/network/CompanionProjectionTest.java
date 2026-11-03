package com.tactical.neoforge.network;

import com.tactical.core.combat.BattlePhase;
import com.tactical.neoforge.api.companion.CompanionAbility;
import com.tactical.neoforge.battle.BattleText;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The companion command projection as a model: what it refuses to carry, and what it must never claim.
 *
 * <p>The byte round-trip needs a booted game and lives in the {@code battle_state_wire} runtime fixture. What
 * can be pinned here is the arithmetic of the projection itself - the null normalization, the ability mask,
 * and the guarantee that a non-commanding recipient carries no companion identity.</p>
 */
class CompanionProjectionTest {

    private static final UUID SESSION = new UUID(11L, 22L);
    private static final UUID COMPANION = new UUID(33L, 44L);

    private static BattleStatePacket.Command command(boolean commanding, UUID id, int entityId, int abilities) {
        return new BattleStatePacket.Command(commanding, id, entityId,
                Component.translatable("entity.minecraft.wolf"), 20, 40, 88, 150, 12, 13, 50, 2, true, false,
                0, 1, Component.translatable(BattleText.KEY_NATURAL_WEAPON), abilities, 3, 70, -4);
    }

    private static BattleStatePacket withCommand(BattleStatePacket.Command command) {
        return new BattleStatePacket(true, BattlePhase.COMPANION_TURN.ordinal(), 7, 100, 150, 10, 0, 99, 0,
                new int[]{3, 70, -4}, Component.translatable("entity.minecraft.wolf"),
                List.of(Component.translatable("entity.minecraft.wolf")), 0,
                Component.translatable(BattleText.KEY_EMPTY_HAND), 0, 0, 0, 99, 0, 24, false, 913,
                new int[]{913, 20, 40}, new int[]{100}, 1, false, new int[]{913}, 10, 8, 10, 13, 50, 2,
                false, SESSION, 7L, 0L, 0L, command);
    }

    @Test
    void aNonCommandingProjectionCarriesNoIdentityOrOrders() {
        BattleStatePacket.Command none = new BattleStatePacket.Command(false, COMPANION, 913,
                Component.literal("wolf"), 20, 40, 88, 150, 12, 13, 50, 2, true, false, 0, 1,
                Component.literal("Natural"), CompanionAbility.all(), 3, 70, -4);
        assertFalse(none.commanding());
        assertNull(none.companionId(), "a recipient that commands nothing never learns a companion id");
        assertEquals(-1, none.companionEntityId());
        assertEquals(0, none.abilities(), "and is offered no order at all");
        assertFalse(none.allows(CompanionAbility.MOVE.ordinal()));
    }

    @Test
    void anAbsentProjectionIsTheCommonEmptyOne() {
        BattleStatePacket packet = withCommand(null);
        assertEquals(BattleStatePacket.Command.NONE, packet.command(),
                "a snapshot built without a projection carries the shared empty one");
        assertFalse(packet.command().commanding());
    }

    @Test
    void theCompatibilityConstructorCarriesNoProjection() {
        BattleStatePacket packet = new BattleStatePacket(true, BattlePhase.PLAYER_TURN.ordinal(), 3, 100, 150, 10,
                0, 99, 0, new int[]{0, 99, 0},
                Component.literal("player"), List.of(Component.literal("player")), 0,
                Component.translatable(BattleText.KEY_EMPTY_HAND), 0, 0, 0, 99, 0, 24, false, 72,
                new int[]{72, 20, 20}, new int[]{100}, 0, false, new int[]{72}, 10, 8, 10, 13, 50, 0,
                false, SESSION, 4L, 0L, 0L);
        assertFalse(packet.command().commanding(),
                "the older producer form describes a player turn and can never fake a companion");
    }

    @Test
    void theOrderSetIsAnExactMask() {
        int all = CompanionAbility.all();
        for (CompanionAbility ability : CompanionAbility.values()) {
            assertEquals(ability.ordinal() >= 0 ? 1 << ability.ordinal() : 0, ability.mask());
            assertTrue((all & ability.mask()) != 0, "every declared order is in the full set");
        }
        BattleStatePacket.Command moveOnly = command(true, COMPANION, 913, CompanionAbility.MOVE.mask());
        assertTrue(moveOnly.allows(CompanionAbility.MOVE.ordinal()));
        assertFalse(moveOnly.allows(CompanionAbility.ATTACK.ordinal()),
                "an order the adapter did not declare is not offered");
        assertFalse(moveOnly.allows(-1), "an unknown ordinal grants nothing");
        assertFalse(moveOnly.allows(CompanionAbility.values().length), "an out-of-range ordinal grants nothing");
        assertEquals(0, CompanionAbility.maskOf(99), "and contributes no bit at all");
    }

    @Test
    void theMembershipStampKeepsTheProjection() {
        BattleStatePacket packet = withCommand(command(true, COMPANION, 913, CompanionAbility.all()));
        BattleStatePacket stamped = packet.withMembership(4242L, 9L);
        assertEquals(4242L, stamped.membershipServerEpoch());
        assertEquals(9L, stamped.membershipGeneration());
        assertTrue(stamped.command().commanding(), "restamping a snapshot never drops its projection");
        assertEquals(COMPANION, stamped.command().companionId());
        assertEquals(88, stamped.command().ap());
    }

    @Test
    void theProjectionKeepsTheOwnersOwnBlockIntact() {
        BattleStatePacket packet = withCommand(command(true, COMPANION, 913, CompanionAbility.all()));
        assertEquals(100, packet.ap(), "the owner's own AP is a separate field");
        assertEquals(0, packet.playerX(), "and the owner's own cell is not replaced by the companion's");
        assertEquals(99, packet.playerY());
        // While commanding, the single reachable set on the wire is the companion's.
        assertEquals(1, packet.reachable().length / 3, "exactly one reachable set travels, and it is the companion's");
        assertEquals(3, packet.reachable()[0]);
        assertEquals(70, packet.reachable()[1]);
        assertEquals(-4, packet.reachable()[2]);
    }
}
