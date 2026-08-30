package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.CookingListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Cooking crafting-XP hook — the crafting-grid twin of {@code FurnaceResultSlotMixin}, and
 * deliberately the same shape. Ports the Fabric original's {@code CraftingResultSlotMixin},
 * retargeted to Mojang's {@code ResultSlot}.
 *
 * <p>{@code checkTakeAchievements(ItemStack)} is the funnel: both ways of getting an item out of a
 * crafting result slot go through it (mirroring {@code FurnaceResultSlot}'s own shape — a normal
 * take is {@code onTake} → {@code checkTakeAchievements(stack)}, a shift-click accumulates into
 * {@code removeCount} first). See {@link CookingListener} for why this seam, rather than a recipe-
 * or item-level one, is what keeps the 1.21 auto-crafter out.
 *
 * <h2>HEAD is mandatory, and RETURN would fail silently</h2>
 * The batch size is the slot's private {@code removeCount}, and the method's last act on the
 * common path is to zero it (verified via {@code javap -p -c} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}: {@code checkTakeAchievements}
 * ends {@code aload_0; iconst_0; putfield removeCount}). A RETURN injection would therefore read
 * {@code 0} for every craft ever made, award nothing, and compile perfectly.
 *
 * <p>{@code allow = 1}, matching the Fabric original's own choice: this is a HEAD injection into a
 * named method rather than a call-site anchor, so there is no slice to be silently dropped, but the
 * Fabric mixin pinned it anyway and this port keeps that choice rather than diverging from it
 * without a specific reason to.
 *
 * <p>No environment guard is used — {@link CookingListener#onCraftedItemTaken} bails on its first
 * line for a non-{@code ServerPlayer}, which is the client's own copy of the screen handler.
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    /** The slot's owner. Vanilla reads this same field to attribute the craft. */
    @Shadow
    @Final
    private Player player;

    /**
     * How many items this take produced. Accumulated before {@code checkTakeAchievements} is
     * called, and zeroed on the way out — which is why the injection below is at HEAD.
     */
    @Shadow
    private int removeCount;

    @Inject(method = "checkTakeAchievements", allow = 1, at = @At("HEAD"))
    private void mcmmo$onCraftedItemTaken(ItemStack stack, CallbackInfo ci) {
        CookingListener.onCraftedItemTaken(player, stack, removeCount);
    }
}
