package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.EntityDamageListener;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The <b>pre-armor</b> half of the K1/K2 damage hook: capture the incoming damage on its way
 * <em>into</em> vanilla's armor mitigation, so Unarmored's XP can be paid on what was thrown at
 * the player rather than on what got through.
 *
 * <p><b>PORT correction (review round 1):</b> this class previously also carried a
 * {@code @ModifyReturnValue} injector on {@code LivingEntity#getDamageAfterMagicAbsorb} — the
 * naive translation of the Fabric original's {@code modifyAppliedDamage} seam. That seam is
 * <b>architecturally wrong on NeoForge</b>: bytecode-verified (via {@code javap -c} against the
 * actual compiled, NeoForge-patched class — not the unpatched vanilla decompile, which does not
 * reflect what runs) in both {@code LivingEntity#actuallyHurt} and {@code Player#actuallyHurt},
 * the call to {@code getDamageAfterMagicAbsorb} is immediately followed by a bare {@code pop}
 * opcode:
 * <pre>
 *   invokevirtual getDamageAfterMagicAbsorb:(Lnet/minecraft/world/damagesource/DamageSource;F)F
 *   pop
 * </pre>
 * NeoForge discards that method's return value outright. The applied damage instead comes from
 * {@code CommonHooks.onLivingDamagePre(LivingEntity, DamageContainer)} — called a few
 * instructions later and its float result actually consumed (stored, then used for the
 * absorption calculation) — which posts {@code LivingDamageEvent.Pre} on
 * {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS}. A
 * {@code @ModifyReturnValue} on {@code getDamageAfterMagicAbsorb} would therefore be a complete
 * runtime no-op: it compiles, the mixin applies, but the value it produces is thrown away before
 * the damage pipeline ever sees it. The main damage-modification seam is now a plain NeoForge
 * event listener instead — see {@code EntityDamageListener#register()} and its
 * {@code LivingDamageEvent.Pre} handler — and needs no mixin at all.
 *
 * <p>This class now carries only the pre-armor capture below, which is unaffected by that defect
 * ({@code @Inject(at = "HEAD")} only reads the method's <em>arguments</em>, never its return
 * value, so whether the caller uses that return value is irrelevant to this injector's
 * correctness).
 *
 * <p>The reason the pre-armor seam has to exist at all is that mcMMO's main damage-modification
 * window ({@code LivingDamageEvent.Pre}) fires <em>after</em> armor mitigation — and Unarmored's
 * Iron Skin <em>is</em> armor. Paying XP on the post-armor figure would make the skill throttle
 * its own progress: at the diamond tier vanilla soaks roughly two thirds of a hit, so the last
 * and longest stretch of the grind would crawl at a third rate. See
 * {@code UnarmoredManager#getUnarmoredXp}.
 *
 * <p><b>Why a stash-and-consume join is safe here.</b> Bytecode-verified (via {@code javap -c}
 * against the actual compiled, NeoForge-patched classes — re-verified in review round 1 after the
 * first pass had only checked the unpatched vanilla decompile) in both
 * {@code LivingEntity#actuallyHurt} and its {@code Player} override (the latter does not call
 * super, it re-implements — but calls both of these inherited methods itself):
 * <pre>
 *   // both classes, identical shape:
 *   container.setReduction(ARMOR, container.getNewDamage()
 *           - this.getDamageAfterArmorAbsorb(source, container.getNewDamage()));  // captured here
 *   this.getDamageAfterMagicAbsorb(source, container.getNewDamage());             // return discarded (see above)
 *   float newDamage = CommonHooks.onLivingDamagePre(this, container);             // LivingDamageEvent.Pre
 * </pre>
 * {@code getDamageAfterArmorAbsorb} is called exactly once, unconditionally (gated only by the
 * {@code isInvulnerableTo} check at the very top of {@code actuallyHurt}), on the same entity and
 * thread as {@code LivingDamageEvent.Pre}'s later post, with nothing between the two calls that
 * could re-enter the damage pipeline — the join is still single-frame-safe even though the two
 * seams are no longer literally adjacent bytecode (there are two more damage-container calls in
 * between). {@code getDamageAfterArmorAbsorb} is not overridden by {@code Player} or
 * {@code ServerPlayer}, so this injector is the only one needed to cover players. The consumer
 * still verifies entity <em>and</em> source identity before trusting the reading — see
 * {@code EntityDamageListener#recordPreArmorDamage}.
 *
 * <p>{@code getDamageAfterArmorAbsorb} has exactly one {@code freturn} in its bytecode (confirmed
 * via {@code javap -c}), so {@code allow = 1} below is correct — unlike
 * {@code getDamageAfterMagicAbsorb}, which has four (an early {@code BYPASSES_EFFECTS} return, an
 * {@code <= 0} return, a {@code BYPASSES_ENCHANTMENTS} return, and the tail return); that method's
 * {@code allow} count is moot now that it carries no injector at all, but the mismatch is the
 * reason a "copy the house style" {@code allow} value is wrong in general — it must be counted
 * per target method, not assumed from another injector on this branch.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

    @Inject(method = "getDamageAfterArmorAbsorb", allow = 1, at = @At("HEAD"))
    private void mcmmo$capturePreArmorDamage(DamageSource source, float amount,
            CallbackInfoReturnable<Float> cir) {
        EntityDamageListener.recordPreArmorDamage((LivingEntity) (Object) this, source, amount);
    }
}
