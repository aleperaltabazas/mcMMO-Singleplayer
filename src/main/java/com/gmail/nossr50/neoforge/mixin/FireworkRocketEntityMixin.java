package com.gmail.nossr50.neoforge.mixin;

import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.ParticleEffectUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the blast-damage half of a firework's detonation when
 * {@link ParticleEffectUtils#spawnFirework} tagged it as cosmetic
 * ({@link ParticleEffectUtils#COSMETIC_FIREWORK_KEY}). Without this, every level-up/ability
 * firework mcMMO spawns deals its full {@code 5 + 2 × explosions} damage to the celebrating
 * player and anyone within 5 blocks — {@code spawnFirework}'s own javadoc already documents this
 * mixin as the other half of that design; it was simply never ported.
 *
 * <p>Targets {@code dealExplosionDamage} specifically, not {@code explode()} — {@code explode()}
 * also broadcasts the client-side visual burst (entity status {@code 17}) and fires
 * {@code gameEvent}/{@code discard()}, none of which are the bug and all of which must keep
 * running unchanged for a cosmetic firework.
 */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {

    @Inject(method = "dealExplosionDamage", allow = 1, at = @At("HEAD"), cancellable = true)
    private void mcmmo$cancelCosmeticFireworkDamage(CallbackInfo ci) {
        if (MetadataStore.has((Entity) (Object) this, ParticleEffectUtils.COSMETIC_FIREWORK_KEY)) {
            ci.cancel();
        }
    }
}
