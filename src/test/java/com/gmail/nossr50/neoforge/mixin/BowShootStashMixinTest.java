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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflectively confirms {@link BowShootStashMixin}'s two {@code @Inject} handlers exist with the
 * correct target method and injection points, and that the real seam they hook —
 * {@code BowItem#releaseUsing(ItemStack, Level, LivingEntity, int)} — matches this test's (and the
 * mixin's own javadoc's) claims about it.
 *
 * <p><b>Why reflection instead of applying the mixin:</b> Mixin bytecode transformations only occur
 * during game launch (via ModLauncher). The test suite runs under plain JUnit with no ModLauncher
 * wiring, so the mixin is never woven into {@code BowItem.class} bytecode at test time — see
 * {@code PlayerInteractionStashMixinTest}'s own javadoc for the same reasoning. {@code @Inject} is a
 * concrete annotation with {@code RetentionPolicy.RUNTIME}, so it can be read directly via
 * {@code java.lang.reflect} here, no ASM needed for the annotation itself — ASM is used only to
 * independently re-count {@code releaseUsing}'s {@code return} opcodes, the same verification this
 * class's own bytecode read performed by hand during implementation.
 */
class BowShootStashMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void beginBowShotInjectsAtHeadOfReleaseUsing() throws NoSuchMethodException {
        final Method handler = BowShootStashMixin.class.getDeclaredMethod(
                "mcmmo$beginBowShot", ItemStack.class, Level.class, LivingEntity.class, int.class,
                CallbackInfo.class);
        assertNotNull(handler, "mcmmo$beginBowShot must exist with (ItemStack, Level, LivingEntity, "
                + "int, CallbackInfo) parameters");
        assertTrue(!Modifier.isStatic(handler.getModifiers()),
                "an @Inject handler on an instance target method must itself be an instance method");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$beginBowShot must be annotated with @Inject");
        assertEquals("releaseUsing", inject.method()[0]);

        final At at = inject.at()[0];
        assertEquals("HEAD", at.value());
    }

    @Test
    void endBowShotInjectsAtEveryReturnOfReleaseUsing() throws NoSuchMethodException {
        final Method handler = BowShootStashMixin.class.getDeclaredMethod(
                "mcmmo$endBowShot", ItemStack.class, Level.class, LivingEntity.class, int.class,
                CallbackInfo.class);
        assertNotNull(handler, "mcmmo$endBowShot must exist with (ItemStack, Level, LivingEntity, "
                + "int, CallbackInfo) parameters");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$endBowShot must be annotated with @Inject");
        assertEquals("releaseUsing", inject.method()[0]);
        assertEquals(2, inject.allow(), "releaseUsing has two return opcodes -- see "
                + "releaseUsingHasExactlyTwoReturnOpcodes, which independently re-counts them via "
                + "ASM -- so allow must be 2, not the implicit single-match default");

        final At at = inject.at()[0];
        assertEquals("RETURN", at.value());
    }

    /**
     * The seam itself: confirms {@code BowItem} declares {@code releaseUsing(ItemStack, Level,
     * LivingEntity, int)} returning {@code void} -- the brief's guessed Yarn-mapped signature
     * ({@code onStoppedUsing(ItemStack, World, LivingEntity, int): boolean}) does not survive the
     * Mojang mapping: the method is renamed, and its return type is {@code void}, not
     * {@code boolean} -- see the mixin's class javadoc for the full bytecode evidence.
     */
    @Test
    void bowItemDeclaresReleaseUsingWithTheConfirmedSignature() throws NoSuchMethodException {
        final Method releaseUsing = BowItem.class.getDeclaredMethod("releaseUsing", ItemStack.class,
                Level.class, LivingEntity.class, int.class);
        assertEquals(void.class, releaseUsing.getReturnType());
        assertTrue(Modifier.isPublic(releaseUsing.getModifiers()), "releaseUsing must be public");
    }

    /**
     * Confirms {@code BowItem#getUseDuration(ItemStack, LivingEntity)} and
     * {@code BowItem#getPowerForTime(int)} -- the two real calls
     * {@code mcmmo$beginBowShot} reproduces from {@code releaseUsing}'s own body -- still exist with
     * the shapes the mixin relies on: {@code getPowerForTime} must still be {@code static} and take
     * a single {@code int}, matching the brief's demand to confirm this before relying on it (the
     * brief's guessed name, {@code getPullProgress}, does not exist on {@code BowItem} at all).
     */
    @Test
    void bowItemDeclaresTheConfirmedForceCalculationMethods() throws NoSuchMethodException {
        final Method getUseDuration = BowItem.class.getDeclaredMethod("getUseDuration", ItemStack.class,
                LivingEntity.class);
        assertEquals(int.class, getUseDuration.getReturnType());
        assertTrue(Modifier.isPublic(getUseDuration.getModifiers()), "getUseDuration must be public");

        final Method getPowerForTime = BowItem.class.getDeclaredMethod("getPowerForTime", int.class);
        assertEquals(float.class, getPowerForTime.getReturnType());
        assertTrue(Modifier.isStatic(getPowerForTime.getModifiers()), "getPowerForTime must be static");
        assertTrue(Modifier.isPublic(getPowerForTime.getModifiers()), "getPowerForTime must be public");
    }

    /**
     * Independently re-counts {@code releaseUsing}'s {@code return} opcodes via ASM (rather than
     * trusting the implementer's own by-hand {@code javap -c} count baked into {@code allow = 2}
     * above) -- if a future Minecraft version restructures the method's control flow, this test
     * fails loudly instead of the mixin silently under- or over-matching at boot.
     */
    @Test
    void releaseUsingHasExactlyTwoReturnOpcodes() throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = BowItem.class.getClassLoader().getResourceAsStream(
                BowItem.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(classBytes, "could not locate BowItem's own class file on the test classpath");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        MethodNode releaseUsing = null;
        for (MethodNode method : classNode.methods) {
            if ("releaseUsing".equals(method.name)) {
                releaseUsing = method;
                break;
            }
        }
        if (releaseUsing == null) {
            fail("releaseUsing not found in BowItem's class file");
            return; // unreachable
        }
        int returnCount = 0;
        for (AbstractInsnNode insn : releaseUsing.instructions) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                returnCount++;
            }
        }
        assertEquals(2, returnCount, "releaseUsing's return count changed -- re-derive allow for "
                + "BowShootStashMixin's RETURN injector before trusting it");
    }
}
