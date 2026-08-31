package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Husbandry's milk hook (bucket half) — {@code Cow} and {@code Goat}. Ports the Fabric original's
 * {@code CowMilkMixin} onto Mojang's names.
 *
 * <h2>Call shape changed: {@code ItemUtils.createFilledResult}, not {@code ItemUsage.exchangeStack}</h2>
 * Confirmed by reading {@code net/minecraft/world/entity/animal/Cow.java:92} and
 * {@code net/minecraft/world/entity/animal/goat/Goat.java:220} directly in
 * {@code build/moddev/artifacts/neoforge-21.1.248-sources.jar}: both call the 3-arg
 * {@code ItemUtils.createFilledResult(ItemStack bucket, Player player, ItemStack
 * Items.MILK_BUCKET.getDefaultInstance())} inside an {@code itemstack.is(Items.BUCKET) &&
 * !this.isBaby()} branch — a different helper, different argument order, from Fabric's
 * {@code ItemUsage.exchangeStack}, so the Fabric {@code @At} target string does not carry over
 * unchanged; this one is re-derived from the real 3-arg descriptor. {@code Goat} re-implements the
 * branch inline exactly as the Fabric doc found (not inherited from {@code Cow}), so it needs its
 * own target in this shared {@code @Mixin({Cow.class, Goat.class})}, same as Fabric.
 *
 * <p>{@code allow = 1}: the 3-arg overload is called exactly once in each of {@code Cow#mobInteract}
 * and {@code Goat#mobInteract} (confirmed by source read; no second call site in either body).
 *
 * <h2>No milking dispenser exists in 1.21.1 either</h2>
 * Re-confirmed for this task (design spec §7 flagged it for re-checking, not just carrying over the
 * Fabric jar-grep): a full {@code jar tf} listing of every class under
 * {@code net/minecraft/core/dispenser/} in the sources jar, grepped for {@code MILK_BUCKET}, comes
 * back empty. {@code interactMob}'s real-player gate (the signature itself — {@code mobInteract}
 * takes a {@link Player} directly, never a dispenser) is therefore not defending against anything
 * that currently exists, but is exactly as cheap to keep as Fabric's was.
 *
 * <p>{@code MushroomCow} extends {@code Cow} and does not override the bucket branch — its own
 * {@code mobInteract} falls through to {@code super.mobInteract} whenever the held item is not a
 * bowl (confirmed by source read of {@code MushroomCow.java}), so this one mixin on {@code Cow}
 * already covers a mooshroom's plain-bucket path with no mooshroom-specific code here at all; the
 * stew branch is {@link MushroomCowStewMixin}'s own, separate hook.
 */
@Mixin({Cow.class, Goat.class})
public abstract class CowGoatMilkMixin {

    @Inject(method = "mobInteract", allow = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemUtils;createFilledResult("
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
            shift = At.Shift.AFTER))
    private void mcmmo$onMilked(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        HusbandryListener.onMilked((Animal) (Object) this, player);
    }
}
