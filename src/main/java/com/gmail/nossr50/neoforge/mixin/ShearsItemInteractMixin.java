package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Husbandry's shear hook — XP, {@code Bountiful Harvest}'s double-drop, and its durability save.
 *
 * <h2>⚠️ Genuine redesign, not a transcription of the Fabric original</h2>
 * The Fabric port needed two mixins, {@code EntityShearDropMixin} (hooking
 * {@code LivingEntity#forEachShearedItem}) and {@code ShearableInteractMixin} (a
 * {@code @Mixin({Sheep.class, MushroomCow.class, SnowGolem.class, Bogged.class})} species list, for
 * the durability save only). Neither exists here. 1.21.1 NeoForge's own doc comment on
 * {@code ShearsItem#interactLivingEntity} says exactly why: <i>"Neo: Migrate shear behavior into
 * {@code ShearsItem#interactLivingEntity} to call into IShearable instead of relying on
 * {@code Mob#mobInteract}"</i> — confirmed by reading the real method body in
 * {@code build/moddev/artifacts/neoforge-21.1.248-sources.jar} (not decompiled bytecode guesses),
 * reproduced here for reference (Mojang names):
 * <pre>
 * if (entity instanceof IShearable target) {
 *     if (target.isShearable(player, stack, entity.level(), pos)) {         // &lt;-- (1) below
 *         List&lt;ItemStack&gt; drops = target.onSheared(player, stack, entity.level(), pos);  // &lt;-- (2)
 *         if (!isClient) for (ItemStack drop : drops) target.spawnShearedDrop(level, pos, drop);
 *         entity.gameEvent(GameEvent.SHEAR, player);
 *         if (!isClient) stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));  // &lt;-- (3)
 *         return InteractionResult.sidedSuccess(isClient);
 *     }
 * }
 * return InteractionResult.PASS;
 * </pre>
 * One funnel now covers every current and future {@code IShearable} — {@code Sheep},
 * {@code MushroomCow}, {@code SnowGolem}, {@code Bogged}, {@code CopperGolem}, and anything a later
 * version adds — with <b>no species enumeration anywhere</b>, which is strictly better than the
 * Fabric port's already-good "one funnel" seam.
 *
 * <h2>The three injection points, verified against the real bytecode (not just the source)</h2>
 * {@code javap -p -c} against {@code neoforge-21.1.248-merged.jar} confirms all three of the
 * design spec's flagged-uncertain claims:
 * <ol>
 *   <li><b>(1) {@code IShearable.isShearable(...)}'s boolean result</b> is a single
 *       {@code invokeinterface}, occurring exactly once — the natural {@code @ModifyExpressionValue}
 *       point to open the shear window (pay XP, roll Hidden Bounty, decide Bountiful Harvest) only
 *       when vanilla itself is about to accept the shear.</li>
 *   <li><b>(2) {@code drops} survives as a genuine local variable</b> — {@code astore 8}
 *       immediately after the {@code onSheared} call, with no intervening store to another
 *       {@code List}-typed local anywhere in the method — so it <em>is</em>
 *       {@code @ModifyVariable}-reachable (the spec's named fallback, a {@code @Redirect}/
 *       {@code @WrapOperation} on {@code spawnShearedDrop}, is not needed). Selected here by
 *       {@code STORE} ordinal rather than by LVT name/index, since the merged jar carries no debug
 *       names — {@code ordinal = 0} is unambiguous because it is the only {@code List} ever stored
 *       in this method.</li>
 *   <li><b>(3) {@code stack.hurtAndBreak(1, player, ...)}</b> is a single call, reached only inside
 *       the same server-side (post-{@code !isClient}), already-{@code isShearable} branch — exactly
 *       the durability-save shape {@code ShearableInteractMixin} needed a species list for on
 *       Fabric, needed here from one injector instead.</li>
 * </ol>
 *
 * <h2>The dispenser exploit is closed structurally, not by a gate here</h2>
 * {@code ShearsDispenseItemBehavior#tryShearLivingEntity} calls {@code IShearable#onSheared}
 * <b>directly</b>, with a {@code null} player, never through {@code interactLivingEntity} at all
 * (confirmed via source read) — so every call this mixin ever sees carries a real {@link Player},
 * and {@link HusbandryListener#beginShear} only needs the ordinary real-player-data-loaded check,
 * not an interaction-stash lookup the way every other verb in this skill needs one.
 */
@Mixin(ShearsItem.class)
public abstract class ShearsItemInteractMixin {

    /**
     * Open the shear window the instant vanilla itself confirms {@code entity} is willing to be
     * sheared. Runs on both sides (the method itself is client+server), but
     * {@link HusbandryListener#beginShear} only ever acts on a {@code ServerPlayer}, so the client
     * mirror of this call is inert.
     */
    @ModifyExpressionValue(method = "interactLivingEntity", allow = 1,
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/IShearable;isShearable("
                            + "Lnet/minecraft/world/entity/player/Player;"
                            + "Lnet/minecraft/world/item/ItemStack;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/core/BlockPos;)Z"))
    private boolean mcmmo$beginShear(boolean shearable, @Local(argsOnly = true) Player player,
            @Local(argsOnly = true) LivingEntity entity) {
        if (shearable) {
            HusbandryListener.beginShear(entity, player);
        }
        return shearable;
    }

    /**
     * {@code Bountiful Harvest}: double every stack {@code IShearable#onSheared} just produced,
     * before vanilla hands each one to {@code spawnShearedDrop}.
     */
    @ModifyVariable(method = "interactLivingEntity", allow = 1,
            at = @At(value = "STORE", ordinal = 0))
    private List<ItemStack> mcmmo$onShearDrops(List<ItemStack> drops) {
        return drops.stream().map(HusbandryListener::onShearDropStack).toList();
    }

    /** The shears' own durability save. */
    @ModifyArg(method = "interactLivingEntity", allow = 1, index = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak("
                            + "ILnet/minecraft/world/entity/LivingEntity;"
                            + "Lnet/minecraft/world/entity/EquipmentSlot;)V"))
    private int mcmmo$onShearToolDamaged(int damageAmount, @Local(argsOnly = true) Player player) {
        return HusbandryListener.onShearToolDamaged(player, damageAmount);
    }

    /**
     * Close the shear window on either of {@code interactLivingEntity}'s two {@code areturn}s —
     * {@code isShearable} declined (nothing opened, harmless no-op), or a real shear completed.
     */
    @Inject(method = "interactLivingEntity", allow = 2, at = @At("RETURN"))
    private void mcmmo$endShear(ItemStack stack, Player player, LivingEntity entity,
            net.minecraft.world.InteractionHand hand,
            CallbackInfoReturnable<net.minecraft.world.InteractionResult> cir) {
        HusbandryListener.endShear();
    }
}
