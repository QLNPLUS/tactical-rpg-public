package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.VanillaInventoryGuard;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejects vanilla inventory/container mutations from a battle member that is not currently
 * acting on its own turn.
 *
 * <p>Every injection sits immediately <em>after</em> {@code PacketUtils.ensureRunningOnSameThread},
 * i.e. on the server thread and before any menu state has been touched. Reading battle state from
 * the netty thread would be unsafe, so the thread hand-off is deliberately the anchor point.
 * A rejected packet is followed by an authoritative re-sync instead of a silent drop, so the
 * client cannot keep a predicted stack.</p>
 *
 * <p>Complete review of {@code ServerGamePacketListenerImpl} handlers that can change inventory
 * content in 1.21.1 (verified against the installed decompiled bytecode):</p>
 * <ul>
 *   <li>gated: {@code handleContainerClick}, {@code handleContainerButtonClick},
 *       {@code handlePlaceRecipe}, {@code handleSetCreativeModeSlot},
 *       {@code handlePlayerAction} (DROP_ITEM / DROP_ALL_ITEMS / SWAP_ITEM_WITH_OFFHAND),
 *       {@code handlePickItem} (swaps inventory slot 9..35 with the selected hotbar slot),
 *       {@code handleSelectTrade} (merchant slots move inventory items)</li>
 *   <li>not item transport, intentionally not gated: {@code handleContainerClose} (only returns
 *       items), {@code handleSetCarriedItem} (selected index only), {@code handleRenameItem} and
 *       {@code handleContainerSlotStateChanged} (menu state only, the result is taken by a click),
 *       {@code handleUseItem}/{@code handleUseItemOn} (use is blocked by BattleItemLock),
 *       {@code handleEditBook} (rewrites the caller's own writable book NBT and has no
 *       {@code ensureRunningOnSameThread} call, so it offers no safe server-thread anchor)</li>
 * </ul>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerInventoryGuardMixin {

    @Shadow public ServerPlayer player;

    /** Full descriptor: the exact overload every one of these handlers calls first. */
    private static final String SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread"
                    + "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
                    + "Lnet/minecraft/server/level/ServerLevel;)V";

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void tactical$guardContainerClick(ServerboundContainerClickPacket packet, CallbackInfo info) {
        if (VanillaInventoryGuard.denyAndResync(this.player)) info.cancel();
    }

    @Inject(method = "handleContainerButtonClick", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void tactical$guardContainerButton(ServerboundContainerButtonClickPacket packet, CallbackInfo info) {
        if (VanillaInventoryGuard.denyAndResync(this.player)) info.cancel();
    }

    @Inject(method = "handlePlaceRecipe", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void tactical$guardPlaceRecipe(ServerboundPlaceRecipePacket packet, CallbackInfo info) {
        if (VanillaInventoryGuard.denyAndResync(this.player)) info.cancel();
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void tactical$guardCreativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo info) {
        if (VanillaInventoryGuard.denyAndResync(this.player)) info.cancel();
    }

    /** Creative middle-click: moves inventory slot 9..35 into the selected hotbar slot. */
    @Inject(method = "handlePickItem", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void tactical$guardPickItem(ServerboundPickItemPacket packet, CallbackInfo info) {
        if (VanillaInventoryGuard.denyAndResync(this.player)) info.cancel();
    }

    /** Merchant trade: selecting a trade moves inventory items into the trade slots. */
    @Inject(method = "handleSelectTrade", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void tactical$guardSelectTrade(ServerboundSelectTradePacket packet, CallbackInfo info) {
        if (VanillaInventoryGuard.denyAndResync(this.player)) info.cancel();
    }

    /** Only the item-transport actions are gated; block breaking and mining stay untouched. */
    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true)
    private void tactical$guardPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo info) {
        if (!VanillaInventoryGuard.guardsVanillaAction(packet.getAction())) return;
        if (VanillaInventoryGuard.denyAndResync(this.player)) info.cancel();
    }
}
