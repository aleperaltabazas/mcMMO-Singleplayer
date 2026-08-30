package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmail.nossr50.util.McTestRegistries;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflectively confirms {@link ResultSlotMixin}'s shadow fields and its HEAD injector exist with
 * correct signatures, matching {@code FurnaceResultSlotMixinTest}'s structural-verification style.
 * See that test's own javadoc for why reflection is used instead of applying the mixin.
 */
class ResultSlotMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void shadowsThePlayerFieldAsFinal() throws NoSuchFieldException {
        final Field field = ResultSlotMixin.class.getDeclaredField("player");
        assertEquals(Player.class, field.getType());

        final Shadow shadow = field.getAnnotation(Shadow.class);
        assertNotNull(shadow, "player must be annotated with @Shadow");
        assertNotNull(field.getAnnotation(Final.class), "player must be annotated with @Final");
    }

    @Test
    void shadowsTheRemoveCountFieldAsMutable() throws NoSuchFieldException {
        final Field field = ResultSlotMixin.class.getDeclaredField("removeCount");
        assertEquals(int.class, field.getType());

        final Shadow shadow = field.getAnnotation(Shadow.class);
        assertNotNull(shadow, "removeCount must be annotated with @Shadow");
        assertEquals(null, field.getAnnotation(Final.class),
                "removeCount is reassigned (zeroed) by vanilla, so it must not be @Final");
    }

    @Test
    void onCraftedItemTakenHandlerIsAnAllowOneHeadInjector() throws NoSuchMethodException {
        final Method handler = ResultSlotMixin.class.getDeclaredMethod(
                "mcmmo$onCraftedItemTaken", ItemStack.class, CallbackInfo.class);
        assertNotNull(handler,
                "mcmmo$onCraftedItemTaken must exist with checkTakeAchievements' parameter shape");
        assertTrue(!Modifier.isStatic(handler.getModifiers()),
                "the handler for an instance target method must itself be an instance method");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$onCraftedItemTaken must be annotated with @Inject");
        assertEquals("checkTakeAchievements", inject.method()[0]);
        assertEquals(1, inject.allow(), "matching the Fabric original's own allow = 1 choice");

        final At at = inject.at()[0];
        assertEquals("HEAD", at.value());
    }

    @Test
    void theTargetClassActuallyDeclaresCheckTakeAchievementsWithTheExpectedShape()
            throws NoSuchMethodException {
        final Method checkTakeAchievements = ResultSlot.class.getDeclaredMethod(
                "checkTakeAchievements", ItemStack.class);
        assertTrue(Modifier.isProtected(checkTakeAchievements.getModifiers()),
                "checkTakeAchievements must be protected");
        assertTrue(!Modifier.isStatic(checkTakeAchievements.getModifiers()),
                "checkTakeAchievements is an instance method");
        assertEquals(void.class, checkTakeAchievements.getReturnType());
    }

    @Test
    void theTargetClassActuallyDeclaresAFinalPlayerFieldAndAMutableRemoveCountField()
            throws NoSuchFieldException {
        final Field player = ResultSlot.class.getDeclaredField("player");
        assertEquals(Player.class, player.getType());
        assertTrue(Modifier.isFinal(player.getModifiers()), "player must be final");
        assertTrue(Modifier.isPrivate(player.getModifiers()), "player must be private");

        final Field removeCount = ResultSlot.class.getDeclaredField("removeCount");
        assertEquals(int.class, removeCount.getType());
        assertTrue(!Modifier.isFinal(removeCount.getModifiers()),
                "removeCount is Mojang's name for Fabric's `amount` field, and it is reassigned");
        assertTrue(Modifier.isPrivate(removeCount.getModifiers()), "removeCount must be private");
    }
}
