package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Structural regression test for {@link PowderSnowBlockMixin} — Parkour Snow Walker fix.
 *
 * <p>Confirms the real method this mixin targets still exists under the exact Mojang-mapped name
 * and descriptor the injector declares, and that the injector's own shape ({@code allow = 1},
 * {@code HEAD}, cancellable) matches what the plan verified against the merged jar. Guards against
 * a future remap silently detaching the injector from {@code canEntityWalkOnPowderSnow}.
 */
class PowderSnowBlockMixinTest {

    @Test
    void realMethodMatchesTheDescriptorTheInjectorTargets() throws NoSuchMethodException {
        final Method real = PowderSnowBlock.class.getDeclaredMethod("canEntityWalkOnPowderSnow", Entity.class);
        assertNotNull(real);
        assertEquals(boolean.class, real.getReturnType());
    }

    @Test
    void handlerInjectsAtHeadWithAllowOneCancellable() throws NoSuchMethodException {
        final Method handler = PowderSnowBlockMixin.class.getDeclaredMethod(
                "mcmmo$parkourSnowWalker", Entity.class, CallbackInfoReturnable.class);
        final Inject annotation = handler.getAnnotation(Inject.class);
        assertNotNull(annotation, "mcmmo$parkourSnowWalker must be @Inject");
        assertEquals("canEntityWalkOnPowderSnow", annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals(true, annotation.cancellable());
        final At at = annotation.at()[0];
        assertEquals("HEAD", at.value());
    }
}
