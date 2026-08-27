package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.neoforge.listeners.EntityDamageListener;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The K1/K2 damage hook. mcMMO needs to see and <em>reduce</em> the final damage applied to a
 * living entity (Parkour Roll cuts fall damage), which no NeoForge event exposes —
 * {@code LivingIncomingDamageEvent} fires <em>before</em> armor/magic mitigation, and cancelling it
 * vetoes the hit outright rather than merely shrinking it. So we intercept the return of
 * {@link LivingEntity#getDamageAfterMagicAbsorb(DamageSource, float)} — official mappings' name for
 * the Fabric original's yarn-named {@code modifyAppliedDamage} — the vanilla method that yields the
 * post-armor/enchantment damage about to be dealt, and route it through
 * {@link EntityDamageListener}.
 *
 * <p>Uses MixinExtras {@link ModifyReturnValue}. On this NeoForge branch,
 * {@code mixinextras-neoforge} is pulled in transitively by {@code net.neoforged.moddev}'s own
 * NeoForge/NeoForm dependency graph (confirmed via {@code ./gradlew dependencies --configuration
 * compileClasspath}, which lists it as a sibling of {@code net.fabricmc:sponge-mixin} under the
 * same NeoForge parent node) — no explicit {@code build.gradle} dependency was needed. The handler
 * simply transforms the returned float, composing cleanly with any other mod that touches the same
 * method. The listener no-ops for everything this task does not yet wire up.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

    @ModifyReturnValue(method = "getDamageAfterMagicAbsorb", allow = 1, at = @At("RETURN"))
    private float mcmmo$reduceAppliedDamage(float appliedDamage, DamageSource source, float amount) {
        return EntityDamageListener.onModifyAppliedDamage(
                (LivingEntity) (Object) this, source, appliedDamage);
    }

    /**
     * The <b>pre-armor</b> half of the same hook: capture the incoming damage on its way <em>into</em>
     * vanilla's armor mitigation, so Unarmored's XP can be paid on what was thrown at the player
     * rather than on what got through.
     *
     * <p>The reason this second seam has to exist is that mcMMO's only damage window is
     * {@code getDamageAfterMagicAbsorb}, which is post-armor — and Unarmored's Iron Skin <em>is</em>
     * armor. Paying XP on the post-armor figure would make the skill throttle its own progress: at
     * the diamond tier vanilla soaks roughly two thirds of a hit, so the last and longest stretch of
     * the grind would crawl at a third rate. See {@code UnarmoredManager#getUnarmoredXp}.
     *
     * <p><b>Why a stash-and-consume join is safe here.</b> Bytecode-verified (via the decompiled
     * NeoForm source) in both {@code LivingEntity#actuallyHurt} and its {@code Player} override (the
     * latter does not call super, it re-implements — but calls both of these inherited methods
     * itself):
     * <pre>
     *   amount = getDamageAfterArmorAbsorb(source, amount);   // captured here
     *   amount = getDamageAfterMagicAbsorb(source, amount);   // consumed there
     * </pre>
     * The two calls are adjacent, unconditional (once past the invulnerability guard) and on the
     * same entity and thread, with nothing between them that could re-enter the damage pipeline.
     * {@code getDamageAfterArmorAbsorb} is not overridden by {@code Player} or {@code ServerPlayer},
     * so this injector is the only one needed to cover players. The consumer still verifies entity
     * <em>and</em> source identity before trusting the reading — see
     * {@code EntityDamageListener#recordPreArmorDamage}.
     */
    @Inject(method = "getDamageAfterArmorAbsorb", allow = 1, at = @At("HEAD"))
    private void mcmmo$capturePreArmorDamage(DamageSource source, float amount,
            CallbackInfoReturnable<Float> cir) {
        EntityDamageListener.recordPreArmorDamage((LivingEntity) (Object) this, source, amount);
    }
}
