package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.skills.archery.Archery;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stashes the draw force of the bow shot currently in flight, for {@link Archery}'s per-hit XP
 * bonus (see {@code Archery#markBowForce} and {@code Archery#CURRENT_BOW_FORCE}'s own javadoc) —
 * mirrors the Fabric original's {@code BowShootMixin}.
 *
 * <p><b>Seam confirmed by bytecode read, not the plan's guessed Yarn name.</b> The Fabric reference
 * hooks {@code BowItem#onStoppedUsing(ItemStack, World, LivingEntity, int): boolean}. Neither the
 * class name ({@code World} &rarr; {@code Level}) nor the method name survive the Mojang mapping in
 * 1.21.1 — confirmed via {@code javap -p -c} against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}: the release hook is
 * {@code BowItem#releaseUsing(ItemStack, Level, LivingEntity, int)}, and — unlike the Yarn
 * signature's {@code boolean} — it returns {@code void}, so the injected handlers below take a
 * plain {@link CallbackInfo}, not a {@code CallbackInfoReturnable}.
 *
 * <p><b>{@code getPullProgress} does not exist on {@code BowItem} in 1.21.1.</b> The brief's
 * description names it as the source of the {@code 0..1} draw-force value; {@code javap -p} against
 * the same jar shows {@code BowItem} declares no {@code pull}-named member at all. The real
 * equivalent, read directly out of {@code releaseUsing}'s own bytecode (offsets 29-75, where vanilla
 * computes its own draw power), is {@code static float BowItem#getPowerForTime(int)} — called there
 * on exactly the same {@code useTicks = getUseDuration(stack, user) - remainingUseTicks} value this
 * mixin reproduces at {@code HEAD}. {@code getUseDuration} is likewise confirmed still
 * {@code public int BowItem#getUseDuration(ItemStack, LivingEntity)} (not the brief's guessed
 * {@code getMaxUseTime}, which does not exist on {@code LivingEntity} either).
 *
 * <p><b>Guarded on {@code Player}, mirroring the brief.</b> Skeletons and other mobs release bows
 * through their own attack goal, not a hand-held draw the player experiences — {@code releaseUsing}
 * still runs for them, but there is no mcMMO player to credit, so {@code mcmmo$beginBowShot} no-ops
 * for any non-{@link Player} shooter. {@code mcmmo$endBowShot} is left unguarded: it always clears
 * the same {@link ThreadLocal} {@code Archery#endBowShot} operates on, and clearing an already-unset
 * {@code ThreadLocal} is a harmless no-op (see {@code Archery#endBowShot}'s own body), so there is no
 * need to duplicate the guard on the clear side — the same asymmetric-bracket shape
 * {@code PlayerInteractionStashMixin} uses for its own begin/end pair.
 *
 * <p><b>Two {@code return}s, not one.</b> {@code releaseUsing}'s body has two {@code return}
 * statements (the early bail when the projectile is empty or {@code onArrowLoose} vetoes the shot,
 * and the trailing fall-through after the sound/stat bookkeeping) — confirmed by counting
 * {@code return} opcodes via {@code javap -c} against the merged jar (2). A RETURN injector with no
 * {@code ordinal} fires at both, which is exactly the desired behaviour (clear the in-flight force
 * regardless of which branch actually executes, matching {@code PlayerInteractionStashMixin}'s own
 * {@code allow = 5} precedent in this codebase for "multiple returns is not a mistake, count them and
 * say so").
 */
@Mixin(BowItem.class)
public abstract class BowShootStashMixin {

    @Inject(method = "releaseUsing", at = @At("HEAD"))
    private void mcmmo$beginBowShot(ItemStack stack, Level level, LivingEntity user, int remainingUseTicks,
            CallbackInfo ci) {
        if (!(user instanceof Player)) {
            return;
        }
        final int useTicks = ((BowItem) (Object) this).getUseDuration(stack, user) - remainingUseTicks;
        Archery.beginBowShot(BowItem.getPowerForTime(useTicks));
    }

    @Inject(method = "releaseUsing", allow = 2, at = @At("RETURN"))
    private void mcmmo$endBowShot(ItemStack stack, Level level, LivingEntity user, int remainingUseTicks,
            CallbackInfo ci) {
        Archery.endBowShot();
    }
}
