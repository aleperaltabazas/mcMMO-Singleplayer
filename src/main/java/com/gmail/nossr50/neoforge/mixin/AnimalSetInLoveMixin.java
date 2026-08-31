package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Husbandry's {@code Multi-Breed} hook — mirrors the Fabric original's
 * {@code AnimalLovePlayerMixin}.
 *
 * <p><b>Why {@code setInLove} and not {@code mobInteract}.</b> {@code AbstractHorse},
 * {@code Camel}, {@code Llama} and {@code Panda} (the Mojang renames of the Fabric doc's
 * {@code AbstractHorseEntity}/{@code CamelEntity}/{@code LlamaEntity}/{@code PandaEntity}) each
 * override {@code mobInteract} and call {@code setInLove} themselves rather than deferring to
 * {@code Animal}'s own implementation — confirmed structurally unchanged per the design spec
 * (§2): {@code setInLove} is still the only method vanilla uses to attribute an animal's love to
 * a player, so a {@code mobInteract} hook would leave Multi-Breed dead on those four species,
 * horses included.
 *
 * <p>Signature confirmed via {@code javap -p}: {@code public void setInLove(@Nullable Player)}
 * on {@code net.minecraft.world.entity.animal.Animal}. By {@code TAIL} the fed animal's own love
 * ticks and loving-player reference are already set, so the spread below runs against settled
 * state.
 *
 * <p>The re-entrancy guard ({@link HusbandryListener} field {@code SPREADING_LOVE}) is not
 * optional here: the spread is implemented by calling {@code setInLove} on each neighbour — this
 * exact method — so without it one piece of wheat would propagate outward animal by animal until
 * the stack overflowed.
 */
@Mixin(Animal.class)
public abstract class AnimalSetInLoveMixin {

    @Inject(method = "setInLove", allow = 1, at = @At("TAIL"))
    private void mcmmo$onLovePlayer(Player player, CallbackInfo ci) {
        HusbandryListener.onLovePlayer((Animal) (Object) this, player);
    }
}
