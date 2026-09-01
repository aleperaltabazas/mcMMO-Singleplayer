package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.FoodListener;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Food seam — ports the Fabric original's {@code FoodComponentMixin} onto Mojang's
 * {@link LivingEntity#eat(Level, ItemStack, FoodProperties)}.
 *
 * <p>Confirmed via {@code javap -c -p} against this branch's extracted 1.21.1 jar:
 * {@code Player#eat} applies {@code getFoodData().eat(food)} first, then delegates to
 * {@code super.eat(...)} (i.e. this {@code LivingEntity} method) via {@code invokespecial} — so a
 * {@code TAIL} injection here runs after vanilla hunger has already been applied, same ordering as
 * the Fabric original's {@code yarn}-mapped hook. {@code LivingEntity#eat} has a single
 * {@code areturn}, so {@code allow = 1} is safe.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEatMixin {

    @Inject(method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;",
            allow = 1, at = @At("TAIL"))
    private void mcmmo$onFoodConsumed(Level level, ItemStack stack, FoodProperties food,
            CallbackInfoReturnable<ItemStack> cir) {
        FoodListener.onFoodConsumed(level, (LivingEntity) (Object) this, stack, food);
    }
}
