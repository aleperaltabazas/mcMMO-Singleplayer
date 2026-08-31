package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import net.minecraft.advancements.critereon.BredAnimalsTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Husbandry's breeding-XP hook — mirrors the Fabric original's {@code BredAnimalsCriterionMixin}.
 *
 * <p><b>Why {@code BredAnimalsTrigger#trigger} and not {@code Animal#spawnChildFromBreeding}.</b>
 * The design spec (§1) and its own Fox/Turtle finding were independently re-verified for this
 * task: {@code Fox$FoxBreedGoal} (the Mojang rename of {@code FoxEntity$MateGoal}) re-implements
 * the whole breeding sequence inline and calls {@code CriteriaTriggers.BRED_ANIMALS.trigger(...)}
 * directly, never reaching {@code Animal#spawnChildFromBreeding} — so a mixin on
 * {@code spawnChildFromBreeding} (or a listener on NeoForge's {@code BabyEntitySpawnEvent}, which
 * that method fires) would silently pay zero Husbandry XP for foxes and turtles, exactly as the
 * Fabric port originally found for {@code AnimalEntity#breed}. {@code BredAnimalsTrigger#trigger}
 * remains the one point every breeding path shares.
 *
 * <p><b>Descriptor confirmed via {@code javap -p} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}</b> (not transcribed from the
 * spec): {@code public void trigger(ServerPlayer, Animal, Animal, AgeableMob)} — descriptor
 * {@code (Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/animal/Animal;
 * Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/AgeableMob;)V}. Spelled
 * out in full below rather than a bare {@code "trigger"} because {@code BredAnimalsTrigger}
 * inherits no other method by that name, but a bare name would still be ambiguous against
 * overloads a future version might add.
 */
@Mixin(BredAnimalsTrigger.class)
public abstract class BredAnimalsTriggerMixin {

    @Inject(
            method = "trigger(Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lnet/minecraft/world/entity/animal/Animal;"
                    + "Lnet/minecraft/world/entity/animal/Animal;"
                    + "Lnet/minecraft/world/entity/AgeableMob;)V", allow = 1,
            at = @At("HEAD"))
    private void mcmmo$onAnimalsBred(ServerPlayer breeder, Animal parent, Animal mate,
            AgeableMob child, CallbackInfo ci) {
        // child is null for the egg-laying breeders (frog, sniffer, turtle) — the listener pays
        // the breeding regardless and skips only Twins, which needs a baby to copy.
        HusbandryListener.onAnimalsBred(breeder, parent, mate, child);
    }
}
