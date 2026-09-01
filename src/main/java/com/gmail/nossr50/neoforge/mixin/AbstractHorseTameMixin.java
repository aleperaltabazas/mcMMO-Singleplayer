package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.TamingListener;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Taming's tame-XP hook for horses/donkeys/mules/llamas/camels — mirrors the Fabric original's
 * {@code AbstractHorseEntityMixin} (see docs/superpowers/specs/2026-09-01-taming-listener-design.md
 * §1).
 *
 * <p><b>Descriptor confirmed via {@code javap -p} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}</b>: {@code public boolean
 * tameWithName(Player)} — descriptor {@code (Lnet/minecraft/world/entity/player/Player;)Z}. Not
 * {@code tame}, per the spec's explicit warning: {@code AbstractHorse} extends {@code Animal}, not
 * {@code TamableAnimal}, so it has no inherited {@code tame(Player)} at all — the player-facing
 * bond method here is {@code tameWithName}, confirmed as the only tame-shaped method {@code javap}
 * lists on this class.
 *
 * <p><b>{@code TAIL}, not {@code RETURN} with a return-value check:</b> Fabric's equivalent
 * ({@code AbstractHorseBondMixin}) injected at {@code RETURN} and only awarded XP when
 * {@code cir.getReturnValue()} was {@code true}, to avoid crediting a failed bond attempt. That
 * guard is dead weight on 1.21.1: {@code tameWithName}'s real 1.21.1 body (read from the extracted
 * sources) unconditionally sets the owner/tamed flags and returns {@code true} — it has no internal
 * failure path. The one caller that exists, {@code RunAroundLikeCrazyGoal#tick}, already gates the
 * call behind its own temper-roll RNG check before invoking {@code tameWithName} at all, so by the
 * time this injector fires the tame has already succeeded.
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseTameMixin {

    @Inject(method = "tameWithName(Lnet/minecraft/world/entity/player/Player;)Z", at = @At("TAIL"))
    private void mcmmo$onTameWithName(Player player, CallbackInfoReturnable<Boolean> cir) {
        TamingListener.onEntityTamed(player, (AbstractHorse) (Object) this);
    }
}
