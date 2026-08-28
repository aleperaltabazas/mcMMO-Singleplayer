package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.gmail.nossr50.util.McTestRegistries;

/**
 * Verify that the {@link LivingEntityDropFromLootTableAccessor} mixin was registered and processed.
 *
 * <p><b>Why this test uses reflection instead of invoking the accessor on a real entity:</b>
 * Mixin bytecode transformations only occur during game launch (via ModLauncher). The test suite
 * runs under plain JUnit with no ModLauncher wiring, so the mixin is never applied to the
 * {@code LivingEntity.class} bytecode at test time. Therefore, runtime invocation of the
 * mixin-woven method cannot be tested here. Instead, this test verifies compile-time structure:
 * that the accessor interface and static wrapper method exist with the correct signatures,
 * proving the source was correctly written. In-game invocation is verified by manual testing
 * or integration tests that launch through the game.
 */
class LivingEntityDropFromLootTableAccessorTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void theAccessorStaticMethodExists() throws NoSuchMethodException {
        // Verify that the accessor interface's static method exists with the correct signature.
        // This proves the interface was declared correctly and will be callable by Task 2.
        final Method method = LivingEntityDropFromLootTableAccessor.class.getDeclaredMethod(
                "invokeDropFromLootTable",
                LivingEntity.class, DamageSource.class, boolean.class);
        assertNotNull(method, "Static invokeDropFromLootTable method must exist with exact signature");
    }

    @Test
    void theInvokerMethodIsDeclaradWithCorrectSignature() throws NoSuchMethodException {
        // Verify that the @Invoker abstract method was declared with the correct signature.
        // The @Invoker annotation creates an abstract method with the same signature as the
        // target method. Here we verify the interface declares the invoker method that will
        // be woven into LivingEntity at runtime.
        final Method invokerMethod = LivingEntityDropFromLootTableAccessor.class.getDeclaredMethod(
                "mcmmo$invokeDropFromLootTable",
                DamageSource.class, boolean.class);
        assertNotNull(invokerMethod, "Invoker method must exist with correct signature (source, causedByPlayer)");
    }
}
