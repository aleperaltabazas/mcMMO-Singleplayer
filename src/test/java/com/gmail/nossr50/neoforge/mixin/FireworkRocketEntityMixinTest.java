package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.ParticleEffectUtils;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Structural + behavioral regression test for {@link FireworkRocketEntityMixin}.
 *
 * <p>Confirms the {@code @Inject} handler's declared shape against
 * {@link FireworkRocketEntity#dealExplosionDamage()}'s real (private, no-arg, void) descriptor —
 * re-verified here via reflection rather than trusted from the plan's {@code javap} output — and
 * that {@link MetadataStore}'s {@code has}/{@code setFlag} round-trip on
 * {@link ParticleEffectUtils#COSMETIC_FIREWORK_KEY} behaves the way the handler's guard depends
 * on. Mixin weaving itself only happens under ModLauncher at real game launch, not under plain
 * JUnit — see {@code BowShootStashMixinTest}'s javadoc for the same reasoning — so the handler's
 * cancellation logic cannot be exercised end-to-end here; this test instead locks down every piece
 * it is built from.
 */
class FireworkRocketEntityMixinTest {

    @Test
    void dealExplosionDamageIsPrivateVoidNoArgs() throws NoSuchMethodException {
        final Method real = FireworkRocketEntity.class.getDeclaredMethod("dealExplosionDamage");
        assertNotNull(real);
        assertEquals(void.class, real.getReturnType());
        assertEquals(0, real.getParameterCount());
        assertTrue(java.lang.reflect.Modifier.isPrivate(real.getModifiers()),
                "dealExplosionDamage must stay private -- Sponge Mixin injects into private "
                        + "methods at the bytecode level with no reflection involved, so a "
                        + "visibility change here would be a real (if unlikely) API break to "
                        + "notice, not a test artifact");
    }

    @Test
    void explodeIsAStillSeparateMethodFromDealExplosionDamage() throws NoSuchMethodException {
        // explode() must still exist and remain distinct from dealExplosionDamage() -- the mixin
        // must never end up targeting explode() by accident, since that would also suppress the
        // client-side visual burst this fix is not supposed to touch.
        final Method explode = FireworkRocketEntity.class.getDeclaredMethod("explode");
        assertNotNull(explode);
        assertEquals(void.class, explode.getReturnType());
    }

    @Test
    void handlerInjectsAtDealExplosionDamageHeadCancellableAllowOne() throws NoSuchMethodException {
        final Method handler = FireworkRocketEntityMixin.class.getDeclaredMethod(
                "mcmmo$cancelCosmeticFireworkDamage", CallbackInfo.class);
        assertNotNull(handler, "mcmmo$cancelCosmeticFireworkDamage must exist with "
                + "(CallbackInfo) parameters");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$cancelCosmeticFireworkDamage must be @Inject");
        assertEquals("dealExplosionDamage", inject.method()[0]);
        assertEquals(1, inject.allow(), "dealExplosionDamage is declared exactly once on "
                + "FireworkRocketEntity");
        assertTrue(inject.cancellable(), "the injector must be cancellable -- that is the whole "
                + "point of this mixin");

        final At at = inject.at()[0];
        assertEquals("HEAD", at.value());
    }

    @Test
    void metadataStoreRoundTripsTheCosmeticFireworkKey() {
        final UUID fireworkId = UUID.randomUUID();
        assertFalse(MetadataStore.has(fireworkId, ParticleEffectUtils.COSMETIC_FIREWORK_KEY),
                "an untagged entity id must not read as cosmetic");

        MetadataStore.set(fireworkId, ParticleEffectUtils.COSMETIC_FIREWORK_KEY, Boolean.TRUE);
        assertTrue(MetadataStore.has(fireworkId, ParticleEffectUtils.COSMETIC_FIREWORK_KEY),
                "after ParticleEffectUtils.spawnFirework's MetadataStore.setFlag call, the "
                        + "mixin's MetadataStore.has(...) guard must see the tag");

        MetadataStore.remove(fireworkId, ParticleEffectUtils.COSMETIC_FIREWORK_KEY);
        assertFalse(MetadataStore.has(fireworkId, ParticleEffectUtils.COSMETIC_FIREWORK_KEY),
                "cleanup must actually clear the tag");
    }

    @Test
    void aDifferentEntityIdIsUnaffectedByAnotherEntitysCosmeticTag() {
        final UUID cosmeticFirework = UUID.randomUUID();
        final UUID ordinaryFirework = UUID.randomUUID();
        MetadataStore.set(cosmeticFirework, ParticleEffectUtils.COSMETIC_FIREWORK_KEY, Boolean.TRUE);

        assertTrue(MetadataStore.has(cosmeticFirework, ParticleEffectUtils.COSMETIC_FIREWORK_KEY));
        assertFalse(MetadataStore.has(ordinaryFirework, ParticleEffectUtils.COSMETIC_FIREWORK_KEY),
                "a vanilla player-crafted firework rocket must never read as cosmetic -- it has "
                        + "its own random UUID and was never passed to spawnFirework");
    }
}
