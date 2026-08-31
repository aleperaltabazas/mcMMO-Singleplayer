package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Husbandry's hive hook — honeycomb, honey bottle, {@code Beekeeper} and the hive durability save.
 * Ports the Fabric original's {@code BeehiveHarvestMixin} 4-for-4, retargeted onto Mojang's
 * {@code useItemOn} — but <b>do not transcribe the Fabric expressions verbatim</b>: several names
 * changed and one polarity inverted. All four confirmed by reading
 * {@code net/minecraft/world/level/block/BeehiveBlock.java} directly in
 * {@code build/moddev/artifacts/neoforge-21.1.248-sources.jar}, and the call counts confirmed via
 * {@code javap -p -c} against the merged jar (one {@code dropHoneycomb}, one {@code shrink}, one
 * {@code isSmokeyPos}, one {@code hurtAndBreak} — all {@code allow = 1}).
 *
 * <h2>The seam: {@code onUseWithItem} → {@code useItemOn}</h2>
 * Same shape, several renamed/restructured pieces:
 * <ul>
 *   <li>The honeycomb branch still calls {@code dropHoneycomb(Level, BlockPos)} — same signature,
 *       same anchor.</li>
 *   <li>The bottle branch no longer calls a bare {@code decrement(1)}; it calls
 *       {@code usedItem.shrink(1)} ({@code ItemStack#shrink}) — anchor there instead.</li>
 *   <li>The "5-arg automated overload" Fabric warned about is gone as a separate overload; it is
 *       inlined as {@code releaseBeesAndResetHoneyLevel(..., BeeReleaseStatus.EMERGENCY)} vs. the
 *       calm path's {@code resetHoneyLevel(...)} — same underlying trap (both reached from the one
 *       player path, gated on the same campfire check), so hooking {@code useItemOn} itself, not
 *       either sub-primitive, is still the right call.</li>
 *   <li>{@code CampfireBlock.isLitCampfireInRange} → {@code CampfireBlock.isSmokeyPos}, <b>and the
 *       polarity is inverted</b> — see {@link #mcmmo$hiveHarvestLeavesBeesCalm} below.</li>
 * </ul>
 */
@Mixin(BeehiveBlock.class)
public abstract class BeehiveBlockUseItemOnMixin {

    /** Honeycomb: paid, and the bonus helpings delivered, right after vanilla's own drop. */
    @Inject(method = "useItemOn", allow = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/BeehiveBlock;dropHoneycomb("
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
            shift = At.Shift.AFTER))
    private void mcmmo$onHoneycombHarvested(ItemStack usedItem, BlockState state, Level level,
            BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        HusbandryListener.onHoneycombHarvested(player, usedItem, state, level, pos);
    }

    /** Honey bottle: paid, and the bonus helpings delivered, right after vanilla shrinks the glass. */
    @Inject(method = "useItemOn", allow = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V", shift = At.Shift.AFTER))
    private void mcmmo$onHoneyBottled(ItemStack usedItem, BlockState state, Level level,
            BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        HusbandryListener.onHoneyBottled(player);
    }

    /**
     * {@code Beekeeper}: widen {@code CampfireBlock.isSmokeyPos}'s own return value toward the
     * angry branch <b>not</b> being taken.
     *
     * <h2>⚠️ The polarity, spelled out</h2>
     * 1.21.1's branch is {@code if (!CampfireBlock.isSmokeyPos(level, pos)) { ...anger the hive... }
     * else { ...calm reset... }} — the angry branch is guarded by "NOT smokey", the mirror image of
     * Fabric's {@code isLitCampfireInRange} shape (where a lit campfire directly gated the calm
     * path). Fabric's fix widened a {@code true} into "also calm if my sub-skill says so" —
     * {@code return litCampfireInRange || sheltered;} — and transcribing that expression verbatim
     * here, unchanged, happens to compile and happens to be correct <em>only because</em> both
     * conditions independently read "calm" as their {@code true} case. The trap is thinking about
     * it as a transcription at all: re-derived from the 1.21.1 branch shape (design spec §6), the
     * correct widening is {@code return smokey || husbandry.countsAsShelteredHiveHarvest();} —
     * gated on {@code isSmokeyPos}, not on any campfire-lit check of our own. Getting this backwards
     * (e.g. gating on {@code !isSmokeyPos}) would anger bees on a <em>sheltered</em> harvest and do
     * nothing on an unsheltered one, with no compile error to catch it.
     */
    @ModifyExpressionValue(method = "useItemOn", allow = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos("
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean mcmmo$hiveHarvestLeavesBeesCalm(boolean smokey,
            @Local(argsOnly = true) Player player) {
        return smokey || HusbandryListener.hiveHarvestLeavesBeesCalm(player);
    }

    /** The shears' own durability save — the shears branch's {@code hurtAndBreak} call only. */
    @ModifyArg(method = "useItemOn", allow = 1, index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak("
                    + "ILnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/entity/EquipmentSlot;)V"))
    private int mcmmo$onHiveToolDamaged(int damageAmount, @Local(argsOnly = true) Player player) {
        return HusbandryListener.onHiveToolDamaged(player, damageAmount);
    }
}
