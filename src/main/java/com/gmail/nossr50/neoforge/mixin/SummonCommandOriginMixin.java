package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.platform.MobOrigins;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stamps {@code MobOrigin.PLAYER_PLACED} on every {@code /summon}-ed mob, via the static {@code
 * SummonCommand#createEntity(CommandSourceStack, Holder.Reference, Vec3, CompoundTag, boolean)} —
 * the one method the command's handler calls to build the entity (confirmed via {@code javap}). A
 * {@code /summon}-ed mob does not reach {@code EntityType#create(ServerLevel, Consumer, BlockPos,
 * MobSpawnType, boolean, boolean)} the way a spawn egg does, so {@code
 * EntityTypeSpawnOriginMixin} alone would miss it — this is the exact per-path splitting {@link
 * MobOrigins}'s class doc warns is required when no single funnel exists.
 *
 * <p>{@code createEntity} is {@code static}, so the injector handler is {@code private static} to
 * match — this codebase's other origin mixins are all instance methods and didn't need to handle
 * this. {@code allow} is left at the {@code mcmmo.mixins.json} default ({@code defaultRequire = 1}):
 * bytecode-verified via {@code javap -c}, the method has exactly one {@code areturn}.
 */
@Mixin(SummonCommand.class)
public abstract class SummonCommandOriginMixin {

    @Inject(method = "createEntity", at = @At("RETURN"))
    private static void mcmmo$onCreateEntity(CommandSourceStack source,
            Holder.Reference<EntityType<?>> type, Vec3 pos, CompoundTag tag, boolean randomizePos,
            CallbackInfoReturnable<Entity> cir) {
        MobOrigins.stampOnSpawn(cir.getReturnValue(), MobSpawnType.COMMAND);
    }
}
