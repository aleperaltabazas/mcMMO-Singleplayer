package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.platform.MobOrigins;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stamps {@code MobOrigin.BRED} on every animal's offspring, via {@code
 * Animal#finalizeSpawnChildFromBreeding(ServerLevel, Animal, AgeableMob)} — the one method every
 * {@code createChild} implementation hands its freshly-built child to (confirmed by reading the
 * decompiled 1.21.1 source: this is where the parents are aged, the babies-bred stat is
 * incremented, and the child is added to the level). Injected at {@code HEAD}: the child is already
 * fully constructed by the time this method runs, so there is nothing to wait for.
 *
 * <p>{@code Shulker} self-duplication, called out in {@link MobOrigins}'s class doc as a second
 * source of {@code BRED} origins, does <b>not</b> route through this method on 1.21.1 — {@code
 * Shulker} extends {@code AbstractGolem}, not {@code Animal}, and this jar's {@code Shulker} class
 * declares no self-duplication method at all (confirmed via {@code javap}). That half of the class
 * doc's mapping describes a much older codebase; nothing needs wiring for it here.
 */
@Mixin(Animal.class)
public abstract class AnimalBreedChildOriginMixin {

    @Inject(method = "finalizeSpawnChildFromBreeding", at = @At("HEAD"))
    private void mcmmo$onFinalizeSpawnChildFromBreeding(ServerLevel level, Animal mate,
            AgeableMob child, CallbackInfo ci) {
        MobOrigins.stampOnSpawn(child, MobSpawnType.BREEDING);
    }
}
