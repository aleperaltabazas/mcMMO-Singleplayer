package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.gmail.nossr50.util.McTestRegistries;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Verify that the {@link BrewingStandBrewTimeAccessor} mixin interface was written correctly.
 *
 * <p><b>Why this test uses reflection instead of invoking the accessor on a real block entity:</b>
 * Mixin bytecode transformations only occur during game launch (via ModLauncher). The test suite
 * runs under plain JUnit with no ModLauncher wiring, so the mixin is never applied to the
 * {@code BrewingStandBlockEntity.class} bytecode at test time. Therefore, runtime invocation of
 * the mixin-woven accessor methods cannot be tested here. Instead, this test verifies
 * compile-time structure: that the accessor interface declares both methods with the correct
 * signatures and {@code @Accessor} target, and that {@code BrewingStandBlockEntity} actually
 * declares the {@code brewTime} field the accessor targets — proving the source was correctly
 * written. In-game invocation is verified by manual testing or integration tests that launch
 * through the game. Same shape as {@code LivingEntityDropFromLootTableAccessorTest}.
 */
class BrewingStandBrewTimeAccessorTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void getBrewTimeIsDeclaredWithCorrectSignatureAndAccessorValue() throws NoSuchMethodException {
        final Method method = BrewingStandBrewTimeAccessor.class.getDeclaredMethod("getBrewTime");
        assertNotNull(method, "getBrewTime() must exist with no arguments");
        assertEquals(int.class, method.getReturnType(), "getBrewTime() must return int");

        final Accessor accessor = method.getAnnotation(Accessor.class);
        assertNotNull(accessor, "getBrewTime() must be annotated with @Accessor");
        assertEquals("brewTime", accessor.value());
    }

    @Test
    void setBrewTimeIsDeclaredWithCorrectSignatureAndAccessorValue() throws NoSuchMethodException {
        final Method method =
                BrewingStandBrewTimeAccessor.class.getDeclaredMethod("setBrewTime", int.class);
        assertNotNull(method, "setBrewTime(int) must exist");
        assertEquals(void.class, method.getReturnType(), "setBrewTime(int) must return void");

        final Accessor accessor = method.getAnnotation(Accessor.class);
        assertNotNull(accessor, "setBrewTime(int) must be annotated with @Accessor");
        assertEquals("brewTime", accessor.value());
    }

    @Test
    void brewingStandBlockEntityActuallyDeclaresTheBrewTimeField() throws NoSuchFieldException {
        final Field field = BrewingStandBlockEntity.class.getDeclaredField("brewTime");
        assertNotNull(field, "BrewingStandBlockEntity must declare a brewTime field");
        assertEquals(int.class, field.getType(), "brewTime must be an int field");
    }
}
