package com.tactical.neoforge.battle;

import com.mojang.brigadier.Command;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.UUID;

/**
 * Server commands: /tactical battle start|force|stop|invite|accept|decline|leave,
 * /tactical turn end, /tactical flee, /tactical auto on|off.
 */
public final class TacticalCommands {

    private static final java.util.concurrent.atomic.AtomicInteger CHARACTER_REQUESTS =
            new java.util.concurrent.atomic.AtomicInteger();

    private TacticalCommands() {
    }

    /** A request id that never repeats within a server session, so an answer is never mistaken for another. */
    private static int nextCharacterRequest() {
        return CHARACTER_REQUESTS.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
    }

    private static int changeAp(CommandSourceStack source, ServerPlayer target, int delta) throws CommandSyntaxException {
        ServerBattle battle = BattleManager.findFor(target.getUUID());
        if (battle == null || battle.finished() || !battle.isMember(target)) {
            source.sendFailure(BattleText.commandLine(
                    BattleText.translatable(BattleText.KEY_COMMAND_TARGET_NOT_PARTICIPANT)));
            return 0;
        }
        int before = battle.currentAp(target);
        int after = delta == 0 ? before : BattleManager.adjustAp(target, delta);
        if (after < 0) return 0;
        // The receipt keeps its technical format on purpose: existing server evidence parses it, and
        // translating the wording must never change what the command reports.
        source.sendSuccess(() -> BattleText.commandLine(BattleText.translatable(
                BattleText.KEY_COMMAND_AP_RECEIPT, BattleText.name(target.getScoreboardName()),
                before, after)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int invite(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer inviter = source.getPlayerOrException();
        BattleEligibility.Denial denial = BattleManager.invite(inviter, target);
        if (denial != BattleEligibility.Denial.NONE) return 0;
        source.sendSuccess(() -> BattleText.commandLine(BattleText.translatable(
                BattleText.KEY_COMMAND_INVITED, BattleText.name(target.getScoreboardName()))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static UUID session(String raw) {
        try {
            return UUID.fromString(raw == null ? "" : raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tactical")
                // The character page only ever answers the connection that asked: no target argument, no
                // operator requirement, and no growth transaction here - the command asks the service for the
                // player's own snapshot with the open intent, and the page does the rest.
                .then(Commands.literal("character").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    com.tactical.neoforge.network.ServerPackets.sendUnchecked(player,
                            com.tactical.neoforge.progression.ProgressionService.snapshot(player,
                                    nextCharacterRequest(), true));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("ap").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("targetPlayer", EntityArgument.player())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10000))
                                                .executes(context -> changeAp(context.getSource(),
                                                        EntityArgument.getPlayer(context, "targetPlayer"),
                                                        IntegerArgumentType.getInteger(context, "amount"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("targetPlayer", EntityArgument.player())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10000))
                                                .executes(context -> changeAp(context.getSource(),
                                                        EntityArgument.getPlayer(context, "targetPlayer"),
                                                        -IntegerArgumentType.getInteger(context, "amount"))))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("targetPlayer", EntityArgument.player())
                                        .executes(context -> changeAp(context.getSource(),
                                                EntityArgument.getPlayer(context, "targetPlayer"), 0)))))
                .then(Commands.literal("battle")
                        .then(Commands.literal("start").executes(context -> {
                            BattleManager.start(context.getSource().getPlayerOrException());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("force").executes(context -> {
                            BattleManager.start(context.getSource().getPlayerOrException(), true);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("stop").executes(context -> {
                            BattleManager.stop(context.getSource().getPlayerOrException());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("invite")
                                .then(Commands.argument("targetPlayer", EntityArgument.player())
                                        .executes(context -> invite(context.getSource(),
                                                EntityArgument.getPlayer(context, "targetPlayer")))))
                        .then(Commands.literal("accept")
                                .executes(context -> BattleManager.accept(context.getSource().getPlayerOrException())
                                        == BattleEligibility.Denial.NONE ? Command.SINGLE_SUCCESS : 0)
                                .then(Commands.argument("session", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(context -> BattleManager.accept(
                                                        context.getSource().getPlayerOrException(),
                                                        session(context.getArgument("session", String.class)))
                                                == BattleEligibility.Denial.NONE ? Command.SINGLE_SUCCESS : 0)))
                        .then(Commands.literal("decline")
                                .executes(context -> BattleManager.decline(context.getSource().getPlayerOrException())
                                        == BattleEligibility.Denial.NONE ? Command.SINGLE_SUCCESS : 0)
                                .then(Commands.argument("session", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(context -> BattleManager.decline(
                                                        context.getSource().getPlayerOrException(),
                                                        session(context.getArgument("session", String.class)))
                                                == BattleEligibility.Denial.NONE ? Command.SINGLE_SUCCESS : 0)))
                        .then(Commands.literal("leave").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            boolean left = BattleManager.leave(player,
                                    BattleText.exitReason(BattleText.Exit.COMMAND));
                            context.getSource().sendSuccess(() -> BattleText.commandLine(
                                    BattleText.translatable(left
                                            ? BattleText.KEY_COMMAND_LEFT
                                            : BattleText.KEY_COMMAND_NOT_IN_BATTLE)), false);
                            return left ? Command.SINGLE_SUCCESS : 0;
                        })))
                // Short aliases for the invitation conversation.
                .then(Commands.literal("invite")
                        .then(Commands.argument("targetPlayer", EntityArgument.player())
                                .executes(context -> invite(context.getSource(),
                                        EntityArgument.getPlayer(context, "targetPlayer")))))
                .then(Commands.literal("accept").executes(context ->
                        BattleManager.accept(context.getSource().getPlayerOrException())
                                == BattleEligibility.Denial.NONE ? Command.SINGLE_SUCCESS : 0))
                .then(Commands.literal("decline").executes(context ->
                        BattleManager.decline(context.getSource().getPlayerOrException())
                                == BattleEligibility.Denial.NONE ? Command.SINGLE_SUCCESS : 0))
                .then(Commands.literal("leave").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    boolean left = BattleManager.leave(player,
                            BattleText.exitReason(BattleText.Exit.COMMAND));
                    context.getSource().sendSuccess(() -> BattleText.commandLine(
                            BattleText.translatable(left
                                    ? BattleText.KEY_COMMAND_LEFT
                                    : BattleText.KEY_COMMAND_NOT_IN_BATTLE)), false);
                    return left ? Command.SINGLE_SUCCESS : 0;
                }))
                .then(Commands.literal("turn")
                        .then(Commands.literal("end").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            ServerBattle battle = BattleManager.findFor(player.getUUID());
                            if (battle == null || !battle.isMember(player)) return 0;
                            battle.endPlayerTurn(player, battle.stamp());
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("flee").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ServerBattle battle = BattleManager.findFor(player.getUUID());
                    if (battle == null || !battle.isMember(player)) return 0;
                    BattleManager.flee(player, battle.stamp());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("auto")
                        .then(Commands.literal("on").executes(context -> {
                            BattleManager.setAutoTrigger(true);
                            context.getSource().sendSuccess(() -> BattleText.commandLine(
                                    BattleText.translatable(BattleText.KEY_COMMAND_AUTO_ON)), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            BattleManager.setAutoTrigger(false);
                            context.getSource().sendSuccess(() -> BattleText.commandLine(
                                    BattleText.translatable(BattleText.KEY_COMMAND_AUTO_OFF)), false);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("status").executes(context -> {
                    java.util.List<ServerBattle> active = BattleManager.active();
                    ServerBattle single = BattleManager.current();
                    context.getSource().sendSuccess(() -> BattleText.commandLine(active.isEmpty()
                            ? BattleText.translatable(BattleText.KEY_COMMAND_STATUS_NONE)
                            : single != null
                                    ? BattleText.translatable(BattleText.KEY_COMMAND_STATUS_SINGLE,
                                            single.memberCount(), single.playerCapacity(), single.turnEpoch())
                                    : BattleText.translatable(BattleText.KEY_COMMAND_STATUS_MULTIPLE,
                                            active.size())), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
