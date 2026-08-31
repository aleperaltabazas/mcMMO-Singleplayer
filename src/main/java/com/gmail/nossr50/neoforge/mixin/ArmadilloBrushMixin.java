package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Husbandry's brush hook — {@code Armadillo}. Ports the Fabric original's
 * {@code ArmadilloBrushMixin} onto Mojang's names, and simpler than the Fabric doc's own account of
 * the seam: there is <b>no {@code forEachBrushedItem} funnel in 1.21.1</b> (confirmed absent from
 * {@code Armadillo.java} by source read) — the Fabric doc's own "where that funnel does not exist"
 * fallback branch is the one that applies here, unconditionally (design spec §8).
 *
 * <h2>The seam ({@code javap -c -p} / source read on {@code Armadillo.java})</h2>
 * {@code mobInteract} (Mojang rename of {@code interactMob}) gates and drives both hooks in one
 * method body:
 * <pre>
 * if (itemstack.canPerformAction(ItemAbilities.BRUSH_BRUSH) &amp;&amp; this.brushOffScute()) {  // &lt;-- (1)
 *     itemstack.hurtAndBreak(16, player, getSlotForHand(hand));                        // &lt;-- (2)
 *     return InteractionResult.sidedSuccess(this.level().isClientSide);
 * } else {
 *     return this.isScared() ? InteractionResult.FAIL : super.mobInteract(player, hand);
 * }
 * </pre>
 * {@code brushOffScute()} (Mojang rename of {@code brushScute()}) itself both (a) returns
 * {@code false} for a baby and {@code true} otherwise, and (b) calls
 * {@code this.spawnAtLocation(new ItemStack(Items.ARMADILLO_SCUTE))} — the first scute's delivery
 * is baked into the method vanilla calls, same as Fabric.
 *
 * <p>Two injectors, one call site each, {@code allow = 1}:
 * <ol>
 *   <li>{@code @ModifyExpressionValue} on {@code brushOffScute()}'s own boolean result — passed to
 *       {@link HusbandryListener#onBrushed(net.minecraft.world.entity.Entity,
 *       net.minecraft.world.entity.Entity, boolean)} unchanged (the real-delivery gate lives in
 *       that method, not here), and on a winning {@code Bountiful Harvest} roll a second scute is
 *       dropped the same way vanilla drops the first — {@code spawnAtLocation}.</li>
 *   <li>{@code @ModifyArg} on {@code hurtAndBreak}'s first argument (16) for the durability save.</li>
 * </ol>
 *
 * <h2>The real-player gate is the call site, not the signature</h2>
 * {@code mobInteract} is only ever reached by a player; vanilla's own armadillo-brushing behaviour
 * does not go through it (not independently re-verified for a 1.21.1 armadillo-brushing dispenser
 * in this pass — structurally the same "mobInteract is the only path with a Player in scope" shape
 * the design spec carries over from Fabric's own finding).
 */
@Mixin(Armadillo.class)
public abstract class ArmadilloBrushMixin {

    @ModifyExpressionValue(method = "mobInteract", allow = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/animal/armadillo/Armadillo;brushOffScute()Z"))
    private boolean mcmmo$onBrushed(boolean brushed, @Local(argsOnly = true) Player player) {
        final Armadillo self = (Armadillo) (Object) this;
        if (HusbandryListener.onBrushed(self, player, brushed)) {
            self.spawnAtLocation(new ItemStack(Items.ARMADILLO_SCUTE));
        }
        return brushed;
    }

    @ModifyArg(method = "mobInteract", allow = 1, index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak("
                    + "ILnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/entity/EquipmentSlot;)V"))
    private int mcmmo$onBrushToolDamaged(int damageAmount) {
        return HusbandryListener.onBrushToolDamaged((Armadillo) (Object) this, damageAmount);
    }
}
