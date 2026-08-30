package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gmail.nossr50.util.McTestRegistries;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Reflectively confirms {@link CampfireCookMixin}'s {@code @ModifyArg} injector exists with the
 * correct target descriptor, matching {@code AbstractFurnaceSmeltMixinTest}'s
 * structural-verification style. See that test's own javadoc for why reflection is used instead
 * of applying the mixin (Mixin transformations only occur under ModLauncher, not plain JUnit).
 *
 * <p><b>Why the {@code @Local} captures are verified via ASM, not {@code java.lang.reflect}:</b>
 * {@code com.llamalad7.mixinextras.sugar.Local} is declared {@code @Retention(RetentionPolicy.CLASS)}
 * (confirmed via {@code javap -v} against the resolved {@code mixinextras-neoforge} jar) — it is
 * kept in the compiled class file for Mixin's own annotation processor to read, but deliberately
 * not exposed to runtime reflection. {@code Parameter#getAnnotations()} therefore returns nothing
 * for it, and a first attempt at this test that used plain reflection failed with exactly that
 * symptom (an assertion that {@code @Local} was present came back null even though the source
 * clearly declares it). Reading the class file's own {@code RuntimeInvisibleParameterAnnotations}
 * attribute via ASM is the one way left to check this at test time.
 */
class CampfireCookMixinTest {

    private static final String LOCAL_DESCRIPTOR = "Lcom/llamalad7/mixinextras/sugar/Local;";

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    /**
     * {@code Containers.dropItemStack}'s exact descriptor, re-verified via {@code javap -p -c}
     * against the merged jar (read from {@code cookTick}'s own bytecode).
     */
    private static final String DROP_ITEM_STACK_TARGET =
            "Lnet/minecraft/world/Containers;dropItemStack("
                    + "Lnet/minecraft/world/level/Level;DDD"
                    + "Lnet/minecraft/world/item/ItemStack;)V";

    @Test
    void onCampfireCookHandlerIsAModifyArgOnDropItemStack() throws NoSuchMethodException {
        final Method handler = CampfireCookMixin.class.getDeclaredMethod(
                "mcmmo$onCampfireCook", ItemStack.class, Level.class, BlockPos.class,
                SingleRecipeInput.class);
        assertNotNull(handler, "mcmmo$onCampfireCook must exist taking cookTick's captured locals");
        assertTrue(Modifier.isStatic(handler.getModifiers()),
                "the handler for a static target method must itself be static");
        assertEquals(ItemStack.class, handler.getReturnType());

        final ModifyArg modifyArg = handler.getAnnotation(ModifyArg.class);
        assertNotNull(modifyArg, "mcmmo$onCampfireCook must be annotated with @ModifyArg");
        assertEquals("cookTick", modifyArg.method()[0]);
        assertEquals(1, modifyArg.allow(), "allow = 1 so a silent second bind fails loudly");
        assertEquals(4, modifyArg.index(), "the ItemStack is the fifth (last) argument to "
                + "Containers.dropItemStack");

        final At at = modifyArg.at();
        assertEquals("INVOKE", at.value());
        assertEquals(DROP_ITEM_STACK_TARGET, at.target());
    }

    @Test
    void worldAndPosAreArgsOnlyLocalsAndCookedIsAnImplicitlyTypedLocal() throws IOException {
        final MethodNode handler = findHandlerMethodNode();

        // Parameter indices on the static handler: 0 = result (the modified ItemStack argument,
        // no @Local), 1 = world, 2 = pos, 3 = cooked.
        assertLocalArgsOnly(handler, 1, "world");
        assertLocalArgsOnly(handler, 2, "pos");
        assertLocalImplicitlyTyped(handler, 3, "cooked");
    }

    private static void assertLocalArgsOnly(MethodNode handler, int paramIndex, String name) {
        final AnnotationNode local = findLocalAnnotation(handler, paramIndex);
        assertNotNull(local, name + " (parameter " + paramIndex + ") must carry @Local");
        assertTrue(readBoolean(local, "argsOnly"), name + " must be captured argsOnly=true, "
                + "matching cookTick's own parameter, not a method-body local");
    }

    private static void assertLocalImplicitlyTyped(MethodNode handler, int paramIndex, String name) {
        final AnnotationNode local = findLocalAnnotation(handler, paramIndex);
        assertNotNull(local, name + " (parameter " + paramIndex + ") must carry @Local");
        assertTrue(!readBoolean(local, "argsOnly"), name + " is a method-body local (the sole "
                + "SingleRecipeInput in cookTick), not one of cookTick's own parameters -- capturing "
                + "it argsOnly would fail at apply time");
    }

    private static AnnotationNode findLocalAnnotation(MethodNode handler, int paramIndex) {
        if (handler.invisibleParameterAnnotations == null
                || paramIndex >= handler.invisibleParameterAnnotations.length
                || handler.invisibleParameterAnnotations[paramIndex] == null) {
            return null;
        }
        for (AnnotationNode annotation : handler.invisibleParameterAnnotations[paramIndex]) {
            if (LOCAL_DESCRIPTOR.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    /** {@code argsOnly}'s AnnotationDefault is {@code false}, so an absent entry means false. */
    private static boolean readBoolean(AnnotationNode annotation, String key) {
        final List<Object> values = annotation.values;
        if (values == null) {
            return false;
        }
        for (int i = 0; i < values.size(); i += 2) {
            if (key.equals(values.get(i))) {
                return (Boolean) values.get(i + 1);
            }
        }
        return false;
    }

    private static MethodNode findHandlerMethodNode() throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = CampfireCookMixin.class.getClassLoader().getResourceAsStream(
                CampfireCookMixin.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(classBytes, "could not locate CampfireCookMixin's own class file");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        for (MethodNode method : classNode.methods) {
            if ("mcmmo$onCampfireCook".equals(method.name)) {
                return method;
            }
        }
        fail("mcmmo$onCampfireCook not found in CampfireCookMixin's class file");
        return null; // unreachable
    }

    @Test
    void theTargetClassActuallyDeclaresCookTickWithTheExpectedShape() throws NoSuchMethodException {
        final Method cookTick = CampfireBlockEntity.class.getDeclaredMethod("cookTick",
                Level.class, BlockPos.class, BlockState.class, CampfireBlockEntity.class);
        assertTrue(Modifier.isStatic(cookTick.getModifiers()), "cookTick must be static");
        assertTrue(Modifier.isPublic(cookTick.getModifiers()), "cookTick must be public");
        assertEquals(void.class, cookTick.getReturnType());
    }

    /**
     * {@link net.minecraft.server.level.ServerLevel} is not one of {@code cookTick}'s declared
     * parameter types -- the handler's own narrowing {@code instanceof ServerLevel} check is what
     * does the client-side guard, matching {@link CampfireCookMixin}'s own javadoc and
     * {@code AbstractFurnaceSmeltMixin}'s established pattern.
     */
    @Test
    void cookTicksWorldParameterIsLevelNotServerLevel() throws NoSuchMethodException {
        final Method cookTick = CampfireBlockEntity.class.getDeclaredMethod("cookTick",
                Level.class, BlockPos.class, BlockState.class, CampfireBlockEntity.class);
        assertEquals(Level.class, cookTick.getParameterTypes()[0]);
    }
}
