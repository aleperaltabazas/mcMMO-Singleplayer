package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import net.minecraft.world.food.FoodData;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Structural regression test for {@link FoodDataExhaustionMixin} — Agility listener plan, Task A.
 * Confirms the injector's declared shape (target method, {@code allow}, {@code at}, {@code
 * argsOnly}) against the real {@link FoodData#addExhaustion(float)} descriptor, so a future
 * refactor that changes either side is caught here rather than at boot.
 */
class FoodDataExhaustionMixinTest {

    @Test
    void realAddExhaustionMatchesTheDescriptorTheInjectorTargets() throws NoSuchMethodException {
        final Method real = FoodData.class.getDeclaredMethod("addExhaustion", float.class);
        assertNotNull(real);
    }

    @Test
    void handlerModifiesAddExhaustionsFloatArgumentAtHeadWithAllowOne()
            throws NoSuchMethodException {
        final Method handler = FoodDataExhaustionMixin.class.getDeclaredMethod(
                "mcmmo$applyAthlete", float.class);
        final ModifyVariable annotation = handler.getAnnotation(ModifyVariable.class);
        assertNotNull(annotation, "mcmmo$applyAthlete must be @ModifyVariable");
        assertEquals("addExhaustion", annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals(true, annotation.argsOnly(),
                "must target the incoming argument, not a later local also named exhaustion");
        final At at = annotation.at();
        assertEquals("HEAD", at.value());
    }
}
