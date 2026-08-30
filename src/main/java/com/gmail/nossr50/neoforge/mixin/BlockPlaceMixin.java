package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.RepairSalvageListener;
import com.gmail.nossr50.platform.BlockUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The block-place hook: mcMMO's replacement for Bukkit's {@code BlockPlaceEvent}. It marks a
 * hand-placed block ineligible for gathering rewards so a player cannot farm XP by re-mining blocks
 * they placed (legacy {@code BlockListener#onBlockPlace} → {@code BlockUtils#setUnnaturalBlock}).
 *
 * <p>Targets {@code BlockItem#placeBlock(BlockPlaceContext, BlockState)} — bytecode-verified to be
 * exactly {@code context.getLevel().setBlock(context.getClickedPos(), state, 11)} in this version's
 * official mappings (the yarn reference targeted the differently-shaped
 * {@code place(ItemPlacementContext, BlockState)} inner helper; in 1.21.1's official mappings that
 * exact bytecode instead lives in the protected {@code placeBlock} method, so this is the equivalent
 * injection point, not a like-for-like name translation) — so its boolean return is an unambiguous
 * "a block was placed here" signal and {@code context.getClickedPos()} is the placement position.
 * That is cleaner than the public {@code place(...)InteractionResult}, whose early {@code FAIL}
 * returns would otherwise have to be filtered out of an {@code InteractionResult} (and would risk
 * marking a block that was never placed).
 *
 * <p>Injected at {@code RETURN}: only a {@code true} return means the block state actually changed.
 * Gated to {@link ServerLevel}, since in singleplayer the client also runs {@code placeBlock}
 * (block-place prediction on the client world) and the tracker is authoritative server-side session
 * state.
 *
 * <p>By construction this is the <em>only</em> writer of the placed-block flags, so grown / fallen /
 * world-gen blocks are never marked — the port needs none of legacy's "reset to natural" hooks to
 * walk back over-marking (see {@link com.gmail.nossr50.util.PlacedBlockTracker}).
 *
 * <p>Anvil-placement tracking flows through {@link RepairSalvageListener#onAnvilPlaced}, called from
 * this same injection: a placed anvil is immediately classified (vanilla / mcMMO-repaired /
 * mcMMO-salvage) so the one-shot "you placed an anvil" hint can fire.
 */
@Mixin(BlockItem.class)
public abstract class BlockPlaceMixin {

    @Inject(
            method = "placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)Z", allow = 1,
            at = @At("RETURN"))
    private void mcmmo$onBlockPlaced(BlockPlaceContext context, BlockState state,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return; // setBlock reported no change: nothing was placed.
        }
        final Level world = context.getLevel();
        if (world instanceof ServerLevel serverWorld) {
            BlockUtils.markPlaced(serverWorld, context.getClickedPos());
            if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
                RepairSalvageListener.onAnvilPlaced(serverWorld, context.getClickedPos(),
                        serverPlayer);
            }
        }
    }
}
