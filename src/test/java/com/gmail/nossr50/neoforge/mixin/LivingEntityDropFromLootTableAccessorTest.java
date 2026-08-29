package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.gen.Invoker;
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
        // The call-shape helper lives on LivingEntityDropFromLootTableAccessorCalls, a plain
        // (non-mixin) class -- see that class's own javadoc, and the mixin interface's, for why a
        // static method cannot live on the @Mixin interface itself without breaking Mixin's
        // target-type inference at boot.
        final Method method = LivingEntityDropFromLootTableAccessorCalls.class.getDeclaredMethod(
                "invokeDropFromLootTable",
                LivingEntity.class, DamageSource.class, boolean.class);
        assertNotNull(method, "Static invokeDropFromLootTable method must exist with exact signature");
    }

    @Test
    void theInvokerMethodIsDeclaredWithCorrectSignature() throws NoSuchMethodException {
        // Verify that the @Invoker abstract method was declared with the correct signature.
        // The @Invoker annotation creates an abstract method with the same signature as the
        // target method. Here we verify the interface declares the invoker method that will
        // be woven into LivingEntity at runtime.
        final Method invokerMethod = LivingEntityDropFromLootTableAccessor.class.getDeclaredMethod(
                "mcmmo$invokeDropFromLootTable",
                DamageSource.class, boolean.class);
        assertNotNull(invokerMethod, "Invoker method must exist with correct signature (source, causedByPlayer)");

        // Verify the @Invoker annotation actually targets "dropFromLootTable" -- a typo here (e.g.
        // "dropFromLootTables") would still leave the invoker method itself present and correctly
        // shaped, so the assertion above alone cannot catch it; only reading the annotation's own
        // value can.
        final Invoker invoker = invokerMethod.getAnnotation(Invoker.class);
        assertNotNull(invoker, "mcmmo$invokeDropFromLootTable must be annotated with @Invoker");
        assertEquals("dropFromLootTable", invoker.value());

        // Verify LivingEntity actually declares the exact target method this invoker expects --
        // otherwise a mismatch here is only caught at real game launch, as an InjectionError.
        final Method target = LivingEntity.class.getDeclaredMethod(
                "dropFromLootTable", DamageSource.class, boolean.class);
        assertTrue(Modifier.isProtected(target.getModifiers()),
                "LivingEntity#dropFromLootTable(DamageSource, boolean) must be protected");
    }

    @Test
    void theShouldDropLootAccessorStaticMethodExists() throws NoSuchMethodException {
        final Method method = LivingEntityDropFromLootTableAccessorCalls.class.getDeclaredMethod(
                "shouldDropLoot", LivingEntity.class);
        assertNotNull(method, "Static shouldDropLoot method must exist with exact signature");
    }

    @Test
    void theShouldDropLootInvokerMethodIsDeclaredWithCorrectSignature() throws NoSuchMethodException {
        final Method invokerMethod = LivingEntityDropFromLootTableAccessor.class.getDeclaredMethod(
                "mcmmo$invokeShouldDropLoot");
        assertNotNull(invokerMethod, "Invoker method must exist with correct signature (no args)");

        final Invoker invoker = invokerMethod.getAnnotation(Invoker.class);
        assertNotNull(invoker, "mcmmo$invokeShouldDropLoot must be annotated with @Invoker");
        assertEquals("shouldDropLoot", invoker.value());

        final Method target = LivingEntity.class.getDeclaredMethod("shouldDropLoot");
        assertTrue(Modifier.isProtected(target.getModifiers()),
                "LivingEntity#shouldDropLoot() must be protected");
    }
}
