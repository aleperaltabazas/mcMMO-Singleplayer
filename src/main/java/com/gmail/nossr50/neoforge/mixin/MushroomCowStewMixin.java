package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Husbandry's milk hook (stew half) — {@code MushroomCow}'s bowl-of-stew branch. Ports the Fabric
 * original's {@code MooshroomStewMixin} onto Mojang's names, and shares
 * {@link HusbandryListener#onMilked} with {@link CowGoatMilkMixin} — one verb, one cooldown, so a
 * mooshroom cannot be milked and stewed for two awards in the same breath.
 *
 * <h2>The stew branch's own call shape</h2>
 * Confirmed by reading {@code net/minecraft/world/entity/animal/MushroomCow.java:100} directly:
 * inside an {@code itemstack.is(Items.BOWL) && !this.isBaby()} branch, it calls the <b>4-arg</b>
 * overload — {@code ItemUtils.createFilledResult(ItemStack bowl, Player player, ItemStack stew,
 * false)} — not the 3-arg one {@link CowGoatMilkMixin} hooks on {@code Cow}/{@code Goat}, so this
 * needs its own {@code @At} target with the extra {@code boolean} parameter in the descriptor.
 * {@code allow = 1}: called exactly once in {@code mobInteract} (the shear branch below it is
 * dead code on NeoForge — {@code false && itemstack.is(Items.SHEARS) && ...}, confirmed by source
 * read — so it can never fire and is not a second call site to worry about).
 *
 * <p>The bucket path is not this mixin's concern at all: {@code MushroomCow.mobInteract} never
 * reaches the bowl branch for a plain bucket (the {@code is(Items.BOWL)} check fails) and falls
 * through to {@code super.mobInteract} — {@code Cow}'s own method, which {@link CowGoatMilkMixin}
 * already hooks — for that item, exactly as the design spec (§7) and the Fabric doc both found.
 */
@Mixin(MushroomCow.class)
public abstract class MushroomCowStewMixin {

    @Inject(method = "mobInteract", allow = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemUtils;createFilledResult("
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            shift = At.Shift.AFTER))
    private void mcmmo$onStewBowled(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        HusbandryListener.onMilked((MushroomCow) (Object) this, player);
    }
}
