package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stashes the player-entity interaction currently in flight, for {@link HusbandryListener}'s feed
 * verb (Task C) — mirrors the Fabric original's {@code PlayerEntityInteractMixin}.
 *
 * <p><b>Seam confirmed by direct source/bytecode read, not by the plan's guessed name.</b> The
 * design spec (§4) flagged {@code Player#interact(Entity, InteractionHand)} as an unverified guess
 * — "expect ... the direct Mojang rename of {@code interact(Entity, Hand) -> ActionResult}". That
 * guess is wrong in 1.21.1: {@code Player} declares no {@code interact} method at all. The real
 * funnel every player-entity right-click passes through is
 * {@code Player#interactOn(Entity, InteractionHand)} (confirmed via
 * {@code net/minecraft/world/entity/player/Player.java:1050} in the merged sources jar, and via
 * {@code javap -p} against {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}, which shows
 * no {@code interact} member on {@code Player} at all). {@code interactOn} is what
 * {@code ServerboundInteractPacket}'s handler calls, and it is what calls
 * {@code target.interact(this, hand)} (an {@code Entity}-side method, not a {@code Player}-side
 * one) a few lines into its body — so hooking {@code interactOn} still brackets every
 * player/animal interaction the feed verb needs, just under its real Mojang name.
 *
 * <p><b>Five {@code areturn}s, not one.</b> {@code interactOn}'s body has five return statements
 * (spectator PASS, a NeoForge {@code CommonHooks.onInteractEntity} cancel, the
 * {@code target.interact(...)} success path, the item-use success path, and the trailing PASS) —
 * confirmed by both a source read and counting {@code areturn} opcodes via {@code javap -c} against
 * the merged jar (5). A RETURN injector with no {@code ordinal} fires at all of them, which is
 * exactly the desired behaviour (end the interaction regardless of which branch actually executes,
 * matching {@code AbstractHorse#createOffspringAttribute}'s own {@code allow = 3} precedent in this
 * codebase for "multiple returns is not a mistake, count them and say so").
 *
 * <p>No identity-matching safety net is needed on the read side the way
 * {@code EntityDamageListener#consumePreArmorDamage} needs one: this stash is consumed only by
 * {@link HusbandryListener#husbandryOfInteractionWith}, which itself compares the stashed
 * {@code target} against the entity it was asked about before trusting the stash (see that
 * method's own javadoc) — the safety net lives on the read side, not duplicated here on the write
 * side, because unlike the pre-armor join there is no return value to smuggle through a nested
 * re-entrant call; {@code beginPlayerInteraction}/{@code endPlayerInteraction} only ever bracket
 * one synchronous {@code interactOn} frame on the server thread, the same
 * {@code CombatUtils.IN_MCMMO_DAMAGE}-shaped guarantee every other {@code ThreadLocal} bridge in
 * this codebase relies on.
 */
@Mixin(Player.class)
public abstract class PlayerInteractionStashMixin {

    @Inject(method = "interactOn", at = @At("HEAD"))
    private void mcmmo$beginInteraction(Entity target, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        HusbandryListener.beginPlayerInteraction((Player) (Object) this, target);
    }

    @Inject(method = "interactOn", allow = 5, at = @At("RETURN"))
    private void mcmmo$endInteraction(Entity target, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        HusbandryListener.endPlayerInteraction();
    }
}
