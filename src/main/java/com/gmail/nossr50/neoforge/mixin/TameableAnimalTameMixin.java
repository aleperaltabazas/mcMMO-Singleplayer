package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.TamingListener;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taming's tame-XP hook for wolves/cats/parrots — mirrors the Fabric original's
 * {@code TameableEntityMixin} (see docs/superpowers/specs/2026-09-01-taming-listener-design.md
 * §1).
 *
 * <p><b>Descriptor confirmed via {@code javap -p} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}</b>: {@code public void
 * tame(Player)} — descriptor {@code (Lnet/minecraft/world/entity/player/Player;)V}.
 */
@Mixin(TamableAnimal.class)
public abstract class TameableAnimalTameMixin {

    @Inject(method = "tame(Lnet/minecraft/world/entity/player/Player;)V", at = @At("TAIL"))
    private void mcmmo$onTame(Player player, CallbackInfo ci) {
        TamingListener.onEntityTamed(player, (TamableAnimal) (Object) this);
    }
}
