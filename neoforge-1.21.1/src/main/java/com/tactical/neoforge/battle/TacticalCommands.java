package com.tactical.neoforge.battle;

import com.mojang.brigadier.Command;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
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

    private TacticalCommands() {
    }

    private static int changeAp(CommandSourceStack source, ServerPlayer target, int delta) throws CommandSyntaxException {
        ServerBattle battle = BattleManager.findFor(target.getUUID());
        if (battle == null || battle.finished() || !battle.isMember(target)) {
            source.sendFailure(Component.literal("[Tactical] Target does not participate in this battle"));
            return 0;
        }
        int before = battle.currentAp(target);
        int after = delta == 0 ? before : BattleManager.adjustAp(target, delta);
        if (after < 0) return 0;
        source.sendSuccess(() -> Component.literal("[Tactical] " + target.getScoreboardName()
                + " AP: " + before + " -> " + after), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int invite(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer inviter = source.getPlayerOrException();
        BattleEligibility.Denial denial = BattleManager.invite(inviter, target);
        if (denial != BattleEligibility.Denial.NONE) return 0;
        source.sendSuccess(() -> Component.literal("[Tactical] 已邀请 " + target.getScoreboardName()
                + " 参战（等待其接受）"), false);
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
                            boolean left = BattleManager.leave(player, "left by command");
                            context.getSource().sendSuccess(() -> Component.literal(left
                                    ? "[Tactical] 已离开战斗" : "[Tactical] 你不在战斗中"), false);
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
                    boolean left = BattleManager.leave(player, "left by command");
                    context.getSource().sendSuccess(() -> Component.literal(left
                            ? "[Tactical] 已离开战斗" : "[Tactical] 你不在战斗中"), false);
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
                            context.getSource().sendSuccess(
                                    () -> Component.literal("[Tactical] 自动触发已开启"), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            BattleManager.setAutoTrigger(false);
                            context.getSource().sendSuccess(
                                    () -> Component.literal("[Tactical] 自动触发已关闭"), false);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("status").executes(context -> {
                    java.util.List<ServerBattle> active = BattleManager.active();
                    ServerBattle single = BattleManager.current();
                    context.getSource().sendSuccess(() -> Component.literal(active.isEmpty()
                            ? "[Tactical] 当前无战斗"
                            : single != null
                                    ? "[Tactical] 战斗进行中 · 参战 " + single.memberCount() + "/" + single.playerCapacity()
                                            + " · 回合代数 " + single.turnEpoch()
                                    : "[Tactical] 进行中的战斗 " + active.size() + " 场"), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
