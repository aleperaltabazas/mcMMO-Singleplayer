package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.AgilityListener;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Agility → <b>Athlete</b>: sprinting costs less hunger. Ports the Fabric original's
 * {@code HungerManagerExhaustionMixin} onto Mojang's {@link FoodData}.
 *
 * <p>{@code FoodData#addExhaustion(float)} confirmed via {@code javap -p -c} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar} — same descriptor as Fabric's
 * {@code HungerManager#addExhaustion(float)}, so the seam carries over unchanged. The maths lives
 * in {@link AgilityListener#scaleExhaustion}; this file is only the seam.
 */
@Mixin(FoodData.class)
public abstract class FoodDataExhaustionMixin {

    @ModifyVariable(method = "addExhaustion", allow = 1, at = @At("HEAD"), argsOnly = true)
    private float mcmmo$applyAthlete(float exhaustion) {
        return AgilityListener.scaleExhaustion((FoodData) (Object) this, exhaustion);
    }
}
