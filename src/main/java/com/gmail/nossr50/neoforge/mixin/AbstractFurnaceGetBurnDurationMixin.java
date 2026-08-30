package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.SmeltingListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Fuel Efficiency / Kitchen Efficiency context bridge. Ports the intent of the Fabric
 * original's {@code getFuelTime} {@code ModifyExpressionValue} injector, but is shaped differently
 * because the NeoForge seam it feeds — {@link net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent}
 * — carries no furnace position or block-entity context at all (confirmed via {@code javap} and by
 * reading the event's bundled source against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}: it exposes only the fuel
 * {@code ItemStack}, the {@code RecipeType}, and the burn time). See
 * {@link SmeltingListener}'s class javadoc (the Fuel Efficiency section) for the full rationale,
 * including why this is a mixin at all rather than the plain event listener the design doc
 * originally planned, and why it lives in its own file rather than growing
 * {@link AbstractFurnaceSmeltMixin} to a third injector.
 *
 * <p>{@code getBurnDuration(ItemStack)} is an <em>instance</em> method on
 * {@link AbstractFurnaceBlockEntity} — unlike {@code serverTick}, {@code burn} and
 * {@code setRecipeUsed}, which are all {@code static} — so its {@code HEAD} injector has
 * {@code this} in scope: the furnace block entity itself, exposing both
 * {@code BlockEntity#getBlockPos()} and the live input slot via {@code getItem(0)} (SLOT_INPUT;
 * see {@link AbstractFurnaceSmeltMixin}'s own javadoc for why the raw literal is used instead of
 * the protected constant). Both, plus the exact fuel {@link ItemStack} instance handed to
 * {@code getBurnDuration}, are bridged to {@link SmeltingListener#onFurnaceFuelBurnTime} via
 * {@link SmeltingListener#rememberFuelBurnContext}.
 *
 * <p>{@code allow = 1}: {@code getBurnDuration} is declared exactly once on this class.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceGetBurnDurationMixin {

    @Inject(method = "getBurnDuration", allow = 1, at = @At("HEAD"))
    private void mcmmo$rememberFuelBurnContext(ItemStack fuel,
            CallbackInfoReturnable<Integer> cir) {
        final AbstractFurnaceBlockEntity self = (AbstractFurnaceBlockEntity) (Object) this;
        SmeltingListener.rememberFuelBurnContext(self.getBlockPos(), fuel, self.getItem(0));
    }
}
