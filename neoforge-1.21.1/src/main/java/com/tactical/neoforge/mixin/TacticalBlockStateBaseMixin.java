package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.BreezeBurst;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * Count the real block callbacks one marked breeze wind charge explosion reaches, and change nothing else.
 *
 * <p>A closed block-interaction branch is otherwise only observable as an absence: the marking explosion's block
 * list is emptied before the loop that calls this method runs, so the honest question is "how many blocks were
 * really called", not "what did one block decide". Every block goes through this one method - a vanilla door, a
 * button and a mod's own block that never asks {@code canTriggerBlocks} alike - so counting here is the only place
 * that answers it for all of them.</p>
 *
 * <p>The count is taken for the encounter's armed burst only; with no marked burst running, this method does
 * nothing at all and vanilla behaviour is bit for bit unchanged.</p>
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class TacticalBlockStateBaseMixin {

    @Inject(method = "onExplosionHit", at = @At("HEAD"))
    private void tactical$countBurstBlockCallback(Level level, BlockPos pos, Explosion explosion,
                                                 BiConsumer<ItemStack, BlockPos> dropConsumer,
                                                 CallbackInfo callback) {
        BreezeBurst.noteBlockCallback(explosion);
    }
}
