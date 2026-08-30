package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.gmail.nossr50.util.McTestRegistries;

/**
 * Reflectively confirms {@link AbstractFurnaceGetBurnDurationMixin}'s single injector exists with
 * the correct shape, matching {@code AbstractFurnaceSmeltMixinTest}'s structural-verification
 * style. See that class and {@link com.gmail.nossr50.neoforge.listeners.SmeltingListener}'s own
 * javadoc for why this mixin exists at all (in short: {@code FurnaceFuelBurnTimeEvent} carries no
 * furnace context, so a context bridge is needed even though the design doc originally planned a
 * pure-event seam here).
 */
class AbstractFurnaceGetBurnDurationMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void handlerIsAnInstanceInjectorAtGetBurnDurationHead() throws NoSuchMethodException {
        final Method handler = AbstractFurnaceGetBurnDurationMixin.class.getDeclaredMethod(
                "mcmmo$rememberFuelBurnContext", ItemStack.class, CallbackInfoReturnable.class);
        assertNotNull(handler, "mcmmo$rememberFuelBurnContext must exist with getBurnDuration's "
                + "parameter shape");
        assertFalse(Modifier.isStatic(handler.getModifiers()),
                "getBurnDuration is an instance method, so its handler must be too");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$rememberFuelBurnContext must be annotated with @Inject");
        assertEquals("getBurnDuration", inject.method()[0]);
        assertEquals(1, inject.allow(), "allow = 1 so a silent second bind fails loudly");

        final At at = inject.at()[0];
        assertEquals("HEAD", at.value());
    }

    @Test
    void theTargetClassActuallyDeclaresGetBurnDurationWithTheExpectedShape()
            throws NoSuchMethodException {
        final Method getBurnDuration = AbstractFurnaceBlockEntity.class.getDeclaredMethod(
                "getBurnDuration", ItemStack.class);
        assertFalse(Modifier.isStatic(getBurnDuration.getModifiers()),
                "getBurnDuration must be an instance method, giving the injector access to `this`");
        assertTrue(Modifier.isProtected(getBurnDuration.getModifiers()),
                "getBurnDuration must be protected");
        assertEquals(int.class, getBurnDuration.getReturnType());
    }
}
