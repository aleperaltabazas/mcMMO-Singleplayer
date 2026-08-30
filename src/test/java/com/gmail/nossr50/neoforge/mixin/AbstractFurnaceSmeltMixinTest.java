package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.gmail.nossr50.util.McTestRegistries;

/**
 * Reflectively confirms {@link AbstractFurnaceSmeltMixin}'s two injectors exist with correct
 * target descriptors, matching {@code LivingEntityDropFromLootTableAccessorTest}'s
 * structural-verification style.
 *
 * <p><b>Why reflection instead of applying the mixin:</b> Mixin bytecode transformations only
 * occur during game launch (via ModLauncher). The test suite runs under plain JUnit with no
 * ModLauncher wiring, so neither {@code @Inject} handler is ever woven into
 * {@link AbstractFurnaceBlockEntity#serverTick}'s bytecode at test time. This test instead
 * verifies compile-time structure — the annotated handler methods exist with the parameter shapes
 * {@code serverTick} requires, and their {@code @At("INVOKE")} target strings resolve to real
 * methods with the exact signatures {@code javap} confirmed against
 * {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}. In-game application is verified by
 * the boot-log check the task's design doc calls out separately (mixin errors surfaced at
 * {@code ./gradlew runServer}), not by this test.
 */
class AbstractFurnaceSmeltMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    /** {@code burn}'s exact descriptor, re-verified via {@code javap -p} against the merged jar. */
    private static final String BURN_TARGET =
            "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;"
                    + "burn(Lnet/minecraft/core/RegistryAccess;"
                    + "Lnet/minecraft/world/item/crafting/RecipeHolder;"
                    + "Lnet/minecraft/core/NonNullList;I"
                    + "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;)Z";

    /**
     * {@code setRecipeUsed}'s exact descriptor, re-verified via {@code javap -p} against the
     * merged jar.
     */
    private static final String SET_RECIPE_USED_TARGET =
            "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;"
                    + "setRecipeUsed(Lnet/minecraft/world/item/crafting/RecipeHolder;)V";

    @Test
    void furnaceSmeltHandlerIsAnInjectorAtTheBurnInvoke() throws NoSuchMethodException {
        final Method handler = AbstractFurnaceSmeltMixin.class.getDeclaredMethod(
                "mcmmo$onFurnaceSmelt", Level.class, BlockPos.class, BlockState.class,
                AbstractFurnaceBlockEntity.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        assertNotNull(handler, "mcmmo$onFurnaceSmelt must exist with serverTick's parameter shape");
        assertTrue(Modifier.isStatic(handler.getModifiers()),
                "the handler for a static target method must itself be static");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$onFurnaceSmelt must be annotated with @Inject");
        assertEquals("serverTick", inject.method()[0]);
        assertEquals(1, inject.allow(), "allow = 1 so a silent second bind fails loudly");

        final At at = inject.at()[0];
        assertEquals("INVOKE", at.value());
        assertEquals(BURN_TARGET, at.target());
    }

    @Test
    void smeltCompleteHandlerIsAnInjectorAtTheSetRecipeUsedInvoke() throws NoSuchMethodException {
        final Method handler = AbstractFurnaceSmeltMixin.class.getDeclaredMethod(
                "mcmmo$onSmeltComplete", Level.class, BlockPos.class, BlockState.class,
                AbstractFurnaceBlockEntity.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        assertNotNull(handler, "mcmmo$onSmeltComplete must exist with serverTick's parameter shape");
        assertTrue(Modifier.isStatic(handler.getModifiers()),
                "the handler for a static target method must itself be static");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$onSmeltComplete must be annotated with @Inject");
        assertEquals("serverTick", inject.method()[0]);
        assertEquals(1, inject.allow(), "allow = 1 so a silent second bind fails loudly");

        final At at = inject.at()[0];
        assertEquals("INVOKE", at.value());
        assertEquals(SET_RECIPE_USED_TARGET, at.target());
    }

    @Test
    void theTargetClassActuallyDeclaresServerTickWithTheExpectedShape() throws NoSuchMethodException {
        final Method serverTick = AbstractFurnaceBlockEntity.class.getDeclaredMethod("serverTick",
                Level.class, BlockPos.class, BlockState.class, AbstractFurnaceBlockEntity.class);
        assertTrue(Modifier.isStatic(serverTick.getModifiers()), "serverTick must be static");
        assertTrue(Modifier.isPublic(serverTick.getModifiers()), "serverTick must be public");
    }

    @Test
    void theTargetClassActuallyDeclaresBurnWithTheExpectedShape() throws NoSuchMethodException {
        final Method burn = AbstractFurnaceBlockEntity.class.getDeclaredMethod("burn",
                RegistryAccess.class, RecipeHolder.class, NonNullList.class, int.class,
                AbstractFurnaceBlockEntity.class);
        assertTrue(Modifier.isStatic(burn.getModifiers()), "burn must be static");
        assertTrue(Modifier.isPrivate(burn.getModifiers()), "burn must be private");
        assertEquals(boolean.class, burn.getReturnType());
    }

    @Test
    void theTargetClassActuallyDeclaresSetRecipeUsedWithTheExpectedShape() throws NoSuchMethodException {
        final Method setRecipeUsed = AbstractFurnaceBlockEntity.class.getDeclaredMethod(
                "setRecipeUsed", RecipeHolder.class);
        assertTrue(Modifier.isPublic(setRecipeUsed.getModifiers()), "setRecipeUsed must be public");
        assertTrue(!Modifier.isStatic(setRecipeUsed.getModifiers()),
                "setRecipeUsed is an instance method, unlike the Fabric original's setLastRecipe");
    }

    /**
     * {@link ServerLevel} is not one of {@code serverTick}'s declared parameter types — the
     * handler's own narrowing {@code instanceof ServerLevel} check is what does the client-side
     * guard, matching {@link AbstractFurnaceSmeltMixin}'s own javadoc.
     */
    @Test
    void serverTicksWorldParameterIsLevelNotServerLevel() throws NoSuchMethodException {
        final Method serverTick = AbstractFurnaceBlockEntity.class.getDeclaredMethod("serverTick",
                Level.class, BlockPos.class, BlockState.class, AbstractFurnaceBlockEntity.class);
        assertEquals(Level.class, serverTick.getParameterTypes()[0]);
    }
}
