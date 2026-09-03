package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.platform.MobOrigins;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Stamps a disqualifying origin on every mob created through {@code EntityType#create(ServerLevel,
 * Consumer, BlockPos, MobSpawnType, boolean, boolean)} — the single funnel spawn eggs, dispensers,
 * and {@code NetherPortalBlock}'s zombified-piglin spawns all converge on in 1.21.1 (confirmed via
 * {@code javap}). See {@link MobOrigins}'s class doc for why this is the correct, narrow funnel and
 * not the general {@code EntityType#create(Level)} overload (that one carries no {@code
 * MobSpawnType} at all, and is the trap the class doc warns about).
 *
 * <p>{@code allow = 3}: bytecode-verified via {@code javap -c} — the method has three {@code
 * areturn} points (the two vanilla early-outs plus the normal exit), and a bare {@code RETURN}
 * injector with no {@code ordinal} fires at all of them, matching {@code
 * AbstractHorse#createOffspringAttribute}'s own {@code allow = 3} precedent in this codebase.
 * {@link CallbackInfoReturnable#getReturnValue()} already carries the constructed entity (or
 * {@code null}, which {@link MobOrigins#stampOnSpawn} null-guards) — no local-variable capture is
 * needed, since every value this injector reads is one of the method's own parameters.
 */
@Mixin(EntityType.class)
public abstract class EntityTypeSpawnOriginMixin<T extends Entity> {

    @Inject(method = "create(Lnet/minecraft/server/level/ServerLevel;Ljava/util/function/Consumer;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/MobSpawnType;ZZ)"
            + "Lnet/minecraft/world/entity/Entity;", allow = 3, at = @At("RETURN"))
    private void mcmmo$onCreate(ServerLevel level, Consumer<T> consumer, BlockPos pos,
            MobSpawnType spawnType, boolean alignPos, boolean forceUpdatePos,
            CallbackInfoReturnable<T> cir) {
        MobOrigins.stampOnSpawn(level, spawnType, cir.getReturnValue());
    }
}
