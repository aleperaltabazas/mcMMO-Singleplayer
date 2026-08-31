package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.HusbandryListener;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Husbandry's {@code Brood} — thrown eggs that hatch, and sometimes hatch in fours. Ports the
 * Fabric original's {@code EggHatchMixin} onto Mojang's 1.21.1 names, re-verified against
 * {@code javap -p -c} on {@code net/minecraft/world/entity/projectile/ThrownEgg.class} in this jar.
 *
 * <h2>What vanilla does, and where the two dice are</h2>
 * {@code EggEntity#onCollision} survives 1.21.1 as {@code ThrownEgg#onHit(HitResult)}
 * (confirmed: {@code protected void onHit(HitResult)}, overriding
 * {@code ThrowableItemProjectile#onHit}). The bytecode shows exactly two
 * {@code RandomSource.nextInt(I)} call sites, in the same order Fabric's doc described:
 * {@code this.random.nextInt(8) == 0} first, and — nested inside that branch — a second
 * {@code this.random.nextInt(32) == 0} deciding between one chick and vanilla's rare four. No other
 * {@code nextInt} call exists anywhere in the method, so {@code ordinal} distinguishes the two
 * unambiguously and {@code allow = 1} keeps each injector honest about firing exactly once per call.
 *
 * <p>Both hooks return {@code 0} to force vanilla's own success branch rather than rewriting the
 * bound. That composes correctly: Brood's chance <b>layers on top of</b> the vanilla roll instead of
 * replacing it, so a configured value can only ever improve the odds. Rewriting {@code nextInt(8)}
 * to {@code nextInt(n)} would have made any {@code n > 8} a silent downgrade on vanilla.
 *
 * <h2>Why an egg farm still earns nothing</h2>
 * Laying is a passive timer, so a hopper under a coop is fully AFK. Brood therefore pays <b>no XP at
 * all</b> — it is a yield sub-skill. The chick it hatches is also given no bred-by marker: unlike
 * every calf/lamb/etc. this class claims elsewhere, nothing here ever calls
 * {@code setData(McMMOAttachments.BRED_BY, ...)} on the spawned {@code Chicken}, because a marker
 * would have quietly turned the same AFK egg farm into a raise-XP farm twenty minutes later, once
 * the chicks came of age. Both properties are pinned by
 * {@code HusbandryListenerBroodTest}.
 *
 * <h2>The dispenser exclusion — {@code Projectile#getOwner()}, confirmed still present</h2>
 * {@code javap -p} on {@code net/minecraft/world/entity/projectile/Projectile.class} confirms
 * {@code public Entity getOwner()} still exists, unchanged in shape from Fabric's
 * {@code ProjectileEntity#getOwner()} — the spec's one flagged-unverified item for this seam. The
 * thrower is resolved through it in {@link HusbandryListener#husbandryOfThrower}, which also closes
 * the dispenser gate: eggs are dispensable in vanilla, and a dispensed egg has no player owner, so
 * {@code getOwner()} returns something other than a {@code ServerPlayer} (typically {@code null})
 * and Brood never applies.
 */
@Mixin(ThrownEgg.class)
public abstract class ThrownEggHatchMixin {

    private static final String ON_HIT = "onHit(Lnet/minecraft/world/phys/HitResult;)V";
    private static final String NEXT_INT = "Lnet/minecraft/util/RandomSource;nextInt(I)I";

    /**
     * Rescue an egg vanilla was about to waste.
     *
     * <p>Returning {@code 0} makes vanilla take its own hatch branch, so this reads as "Brood's
     * chance that a failed egg hatches anyway" and the effective rate is
     * {@code 12.5% + chance × 87.5%}.
     */
    @ModifyExpressionValue(method = ON_HIT, allow = 1,
            at = @At(value = "INVOKE", target = NEXT_INT, ordinal = 0))
    private int mcmmo$broodHatchesMoreEggs(int roll, HitResult hitResult) {
        return HusbandryListener.onEggHatchRoll((ThrownEgg) (Object) this, roll);
    }

    /**
     * Turn a hatch into a full clutch.
     *
     * <p>Vanilla's own rare case, reached one time in thirty-two hatches; on a successful Brood roll
     * it is taken deliberately. This is the second of the sub-skill's two rolls, so the listener
     * scales it by hand — {@code ProbabilityUtil} keys its chance off the {@code SubSkillType}, and
     * only one effect per sub-skill can live there.
     */
    @ModifyExpressionValue(method = ON_HIT, allow = 1,
            at = @At(value = "INVOKE", target = NEXT_INT, ordinal = 1))
    private int mcmmo$broodHatchesFullClutches(int roll, HitResult hitResult) {
        return HusbandryListener.onFullClutchRoll((ThrownEgg) (Object) this, roll);
    }
}
