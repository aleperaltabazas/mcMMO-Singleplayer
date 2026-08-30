package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.SmeltingListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Understanding the Art, half one: brackets a furnace extraction so
 * {@link SmeltingListener#boostVanillaXp} has a multiplier to apply while vanilla's own
 * {@code awardUsedRecipesAndPopExperience} spawns the XP orb. Ports the Fabric original's
 * {@code FurnaceOutputSlotMixin} — retargeted to Mojang's {@code FurnaceResultSlot}, see
 * docs/superpowers/specs/2026-08-30-cooking-smelting-listener-design.md (the
 * {@code FurnaceResultSlot#checkTakeAchievements} section) for why a mixin is still required here
 * despite {@code PlayerEvent.ItemSmeltedEvent} existing: that event fires <em>after</em>
 * {@code awardUsedRecipesAndPopExperience} has already spawned the orb, too late to scale it.
 *
 * <p>{@code checkTakeAchievements(ItemStack)} (verified via {@code javap} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}: {@code protected}, taking a single
 * {@link ItemStack}) is the real seam — decompiled source confirms it calls
 * {@code abstractfurnaceblockentity.awardUsedRecipesAndPopExperience(serverplayer)} partway
 * through, which is what ultimately reaches {@code AbstractFurnaceBlockEntity#createExperience}
 * and spawns the orb. HEAD/RETURN on the whole method brackets that nested call exactly:
 * <ul>
 *   <li><b>HEAD</b> — {@link SmeltingListener#beginFurnaceExtract} resolves and stashes the
 *       multiplier for this extraction (or does nothing, if there is none) before any orb math
 *       runs.</li>
 *   <li><b>RETURN</b> — {@link SmeltingListener#endFurnaceExtract} clears it, so a later,
 *       unrelated extraction on the same thread never inherits a stale multiplier.</li>
 * </ul>
 *
 * <p>Named-method HEAD/RETURN injectors, not a call-site anchor — like {@code checkTakeAchievements}
 * itself, no {@code allow}/{@code require} is needed (matching the Fabric original's own stated
 * rationale: there is exactly one HEAD and one RETURN in a single-exit-shaped method, nothing to
 * double-bind).
 */
@Mixin(FurnaceResultSlot.class)
public abstract class FurnaceResultSlotMixin {

    @Shadow
    @Final
    private Player player;

    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void mcmmo$beginFurnaceExtract(ItemStack stack, CallbackInfo ci) {
        SmeltingListener.beginFurnaceExtract(player, stack);
    }

    @Inject(method = "checkTakeAchievements", at = @At("RETURN"))
    private void mcmmo$endFurnaceExtract(ItemStack stack, CallbackInfo ci) {
        SmeltingListener.endFurnaceExtract();
    }
}
