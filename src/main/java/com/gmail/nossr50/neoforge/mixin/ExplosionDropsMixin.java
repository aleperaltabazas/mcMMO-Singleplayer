package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.BlastMiningListener;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Blast Mining ore-yield hook: replaces the drops of an mcMMO-detonated blast with mcMMO's own.
 * Stands in for legacy's {@code EntityExplodeEvent} handler
 * ({@code EntityListener#onEnitityExplode}), which vanilla has no event for.
 *
 * <p>Two injections into the method that carries out the blast, together reproducing what the Bukkit
 * handler did:
 * <ol>
 *   <li>at <b>HEAD</b> — the analogue of the event firing: the doomed block list is already resolved
 *       but the blocks are still standing, so {@link BlastMiningListener#processBlastDrops} can read
 *       their states, spawn mcMMO's payout and award the XP;</li>
 *   <li>on the <b>drop collector</b> — the analogue of {@code event.setYield(0F)}: vanilla gathers
 *       each block's loot through the {@code BiConsumer} passed to
 *       {@code BlockState#onExplosionHit}, so swapping in a no-op collector suppresses the vanilla
 *       drops that mcMMO has just replaced, while leaving the rest of the explosion (block removal,
 *       block entities, chain-detonating neighbouring TNT) completely untouched.</li>
 * </ol>
 *
 * <p>⚠️ <b>Which method that is, and which class declares it, are both version-specific.</b> At this
 * NeoForge/Mojmap band, the concrete {@code Explosion} carries out its own blast in
 * {@code finalizeExplosion(boolean)} (the Fabric/yarn port of this mixin targeted the same seam under
 * its yarn name {@code affectWorld(boolean)}), and the doomed block list is read from the explosion
 * itself ({@code getToBlow()}) rather than taken from an argument. The block-loot call the second
 * injection rides is {@code BlockState#onExplosionHit} here (yarn: {@code BlockState#onExploded}) —
 * same shape, renamed method, and it still takes a {@code Level}, not a {@code ServerLevel}, so the
 * descriptor is not portable either. Re-resolve both against the band's merged jar; do not copy this
 * descriptor to another band.
 *
 * <p>Both are no-ops for any explosion mcMMO didn't cause — a creeper, a bed, a hand-lit TNT — which
 * keeps their vanilla drops. The HEAD injection decides that once and stashes it, rather than
 * re-resolving the detonator for every block destroyed; an explosion object is built per blast, so the
 * flag can't leak between explosions.
 */
@Mixin(Explosion.class)
public abstract class ExplosionDropsMixin {

    /** Whether mcMMO has already paid out this blast's drops, so vanilla's must be suppressed. */
    @Unique
    private boolean mcmmo$blastMiningHandled;

    @Inject(method = "finalizeExplosion(Z)V", allow = 1, at = @At("HEAD"))
    private void mcmmo$processBlastMiningDrops(boolean particles, CallbackInfo ci) {
        final Explosion self = (Explosion) (Object) this;
        mcmmo$blastMiningHandled =
                BlastMiningListener.processBlastDrops(self, self.getToBlow());
    }

    @ModifyArg(
            method = "finalizeExplosion(Z)V", allow = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;onExplosionHit("
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/Explosion;"
                            + "Ljava/util/function/BiConsumer;)V"),
            index = 3)
    private BiConsumer<ItemStack, BlockPos> mcmmo$suppressVanillaDrops(
            BiConsumer<ItemStack, BlockPos> dropCollector) {
        return mcmmo$blastMiningHandled ? (stack, pos) -> { } : dropCollector;
    }
}
