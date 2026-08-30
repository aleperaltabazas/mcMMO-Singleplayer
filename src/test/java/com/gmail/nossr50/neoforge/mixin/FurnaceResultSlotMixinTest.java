package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmail.nossr50.util.McTestRegistries;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflectively confirms {@link FurnaceResultSlotMixin}'s shadow field and both HEAD/RETURN
 * injectors exist with correct signatures, matching {@code AbstractFurnaceSmeltMixinTest}'s
 * structural-verification style. See that test's own javadoc for why reflection is used instead
 * of applying the mixin (Mixin transformations only occur under ModLauncher, not plain JUnit).
 */
class FurnaceResultSlotMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void shadowsThePlayerFieldAsFinal() throws NoSuchFieldException {
        final Field field = FurnaceResultSlotMixin.class.getDeclaredField("player");
        assertEquals(Player.class, field.getType());

        final Shadow shadow = field.getAnnotation(Shadow.class);
        assertNotNull(shadow, "player must be annotated with @Shadow");
        assertNotNull(field.getAnnotation(Final.class), "player must be annotated with @Final");
    }

    @Test
    void beginFurnaceExtractHandlerIsAHeadInjector() throws NoSuchMethodException {
        final Method handler = FurnaceResultSlotMixin.class.getDeclaredMethod(
                "mcmmo$beginFurnaceExtract", ItemStack.class, CallbackInfo.class);
        assertNotNull(handler,
                "mcmmo$beginFurnaceExtract must exist with checkTakeAchievements' parameter shape");
        assertTrue(!Modifier.isStatic(handler.getModifiers()),
                "the handler for an instance target method must itself be an instance method");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$beginFurnaceExtract must be annotated with @Inject");
        assertEquals("checkTakeAchievements", inject.method()[0]);

        final At at = inject.at()[0];
        assertEquals("HEAD", at.value());
    }

    @Test
    void endFurnaceExtractHandlerIsAReturnInjector() throws NoSuchMethodException {
        final Method handler = FurnaceResultSlotMixin.class.getDeclaredMethod(
                "mcmmo$endFurnaceExtract", ItemStack.class, CallbackInfo.class);
        assertNotNull(handler,
                "mcmmo$endFurnaceExtract must exist with checkTakeAchievements' parameter shape");
        assertTrue(!Modifier.isStatic(handler.getModifiers()),
                "the handler for an instance target method must itself be an instance method");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$endFurnaceExtract must be annotated with @Inject");
        assertEquals("checkTakeAchievements", inject.method()[0]);

        final At at = inject.at()[0];
        assertEquals("RETURN", at.value());
    }

    @Test
    void theTargetClassActuallyDeclaresCheckTakeAchievementsWithTheExpectedShape()
            throws NoSuchMethodException {
        final Method checkTakeAchievements = FurnaceResultSlot.class.getDeclaredMethod(
                "checkTakeAchievements", ItemStack.class);
        assertTrue(Modifier.isProtected(checkTakeAchievements.getModifiers()),
                "checkTakeAchievements must be protected");
        assertTrue(!Modifier.isStatic(checkTakeAchievements.getModifiers()),
                "checkTakeAchievements is an instance method");
        assertEquals(void.class, checkTakeAchievements.getReturnType());
    }

    @Test
    void theTargetClassActuallyDeclaresAFinalPlayerField() throws NoSuchFieldException {
        final Field player = FurnaceResultSlot.class.getDeclaredField("player");
        assertEquals(Player.class, player.getType());
        assertTrue(Modifier.isFinal(player.getModifiers()), "player must be final");
        assertTrue(Modifier.isPrivate(player.getModifiers()), "player must be private");
    }
}
