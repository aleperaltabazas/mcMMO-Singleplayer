package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.platform.MobOrigins;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Carries a mob's origin onto the mob it converts into (a zombie into a drowned, a villager into a
 * zombie villager, and back) via {@code Mob#convertTo(EntityType, boolean)}. Without this, a
 * zombie-spawner mob laundered through a water column would come out {@code NATURAL} and start
 * counting — see {@link MobOrigins#carryThroughConversion}'s own doc for why this is the largest
 * hole this plan closes.
 *
 * <p>{@code allow = 3}: bytecode-verified via {@code javap -c} — {@code convertTo} has three {@code
 * areturn} points (an early {@code null} when {@code isRemoved()}, a second {@code null} when the
 * internal {@code EntityType#create(Level)} call fails, and the normal exit). A bare {@code RETURN}
 * injector with no {@code ordinal} fires at all three; {@link
 * CallbackInfoReturnable#getReturnValue()} is {@code null} on the first two, which {@link
 * MobOrigins#carryThroughConversion} already null-guards.
 */
@Mixin(Mob.class)
public abstract class MobConversionOriginMixin {

    @Inject(method = "convertTo", allow = 3, at = @At("RETURN"))
    private <T extends Mob> void mcmmo$onConvertTo(EntityType<T> entityType, boolean keepEquipment,
            CallbackInfoReturnable<T> cir) {
        MobOrigins.carryThroughConversion((Entity) (Object) this, cir.getReturnValue());
    }
}
