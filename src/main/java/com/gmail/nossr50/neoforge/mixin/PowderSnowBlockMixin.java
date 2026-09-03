package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.PlayerMovementTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Parkour's <b>Snow Walker</b> seam. Ports the Fabric original's {@code PowderSnowBlockMixin} onto
 * Mojang's name for the check — {@code canWalkOnPowderSnow} does not exist under these mappings;
 * the real method is {@link PowderSnowBlock#canEntityWalkOnPowderSnow}, verified via {@code javap}
 * against this branch's merged 1.21.1 jar (a single {@code public static (Entity) -> boolean}
 * overload, so {@code allow = 1} is exact, not a guess).
 *
 * <p>Only ever turns a {@code false} into a {@code true}: a non-Snow-Walker player, or any
 * non-player entity, falls straight through to vanilla's own result untouched — leather boots and
 * the walkable-mobs tag keep working exactly as before.
 *
 * <p>Reads {@link PlayerMovementTracker#canWalkOnPowderSnow(java.util.UUID)} rather than resolving
 * rank/config directly here — that class's javadoc documents why: this injector runs on both the
 * client and the integrated server, many times per tick, and {@code RankUtils}' rank cache is not
 * thread-safe across that boundary.
 */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {

    @Inject(method = "canEntityWalkOnPowderSnow", allow = 1, at = @At("HEAD"), cancellable = true)
    private static void mcmmo$parkourSnowWalker(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Player player && PlayerMovementTracker.canWalkOnPowderSnow(player.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
