package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gmail.nossr50.util.McTestRegistries;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.npc.Villager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflectively confirms {@link AgeableMobGrowthMixin}'s two injectors target the confirmed seams
 * ({@code setAge(int)} for the raise verb, {@code ageUp(int, boolean)}'s first parameter for the
 * feed verb), and independently re-derives — via ASM, not by trusting the mixin's own javadoc —
 * the bytecode fact that makes {@code setAge} the correct seam and {@code ageBoundaryReached} the
 * wrong one: {@link Goat#ageBoundaryReached()} and {@link Hoglin#ageBoundaryReached()} both skip
 * calling {@code super.ageBoundaryReached()}, while {@link Villager}'s override (control case)
 * does not.
 */
class AgeableMobGrowthMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void onBreedingAgeChangeInjectsAtHeadOfSetAgeNotAgeBoundaryReached()
            throws NoSuchMethodException, NoSuchFieldException {
        final Method handler = AgeableMobGrowthMixin.class.getDeclaredMethod(
                "mcmmo$onBreedingAgeChange", int.class, CallbackInfo.class);
        assertNotNull(handler);

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$onBreedingAgeChange must be annotated with @Inject");
        assertEquals("setAge(I)V", inject.method()[0]);
        assertEquals(1, inject.allow());
        assertEquals("HEAD", inject.at()[0].value());

        final org.spongepowered.asm.mixin.Shadow shadow =
                AgeableMobGrowthMixin.class.getDeclaredField("age").getAnnotation(Shadow.class);
        assertNotNull(shadow, "the previous age must be @Shadow-read from the age field, not "
                + "re-derived through a getter that a subclass could override");
    }

    @Test
    void onGrowthAppliedModifiesTheFirstArgOfAgeUpTwoArgOverload() throws NoSuchMethodException {
        final Method handler = AgeableMobGrowthMixin.class.getDeclaredMethod(
                "mcmmo$onGrowthApplied", int.class);
        assertNotNull(handler);

        final ModifyVariable modifyVariable = handler.getAnnotation(ModifyVariable.class);
        assertNotNull(modifyVariable, "mcmmo$onGrowthApplied must be annotated with @ModifyVariable");
        assertEquals("ageUp(IZ)V", modifyVariable.method()[0]);
        assertEquals(1, modifyVariable.allow());
        assertTrue(modifyVariable.argsOnly());
        assertEquals(0, modifyVariable.ordinal());
        assertEquals("HEAD", modifyVariable.at().value());
    }

    @Test
    void ageableMobDeclaresBothConfirmedSeams() throws NoSuchMethodException {
        assertEquals(void.class,
                AgeableMob.class.getDeclaredMethod("setAge", int.class).getReturnType());
        assertEquals(void.class,
                AgeableMob.class.getDeclaredMethod("ageUp", int.class, boolean.class).getReturnType());
    }

    /**
     * The bytecode fact {@code AgeableMobGrowthMixin}'s javadoc cites re-derived independently here:
     * neither override calls {@code super.ageBoundaryReached()} anywhere in its body, so a mixin on
     * {@code ageBoundaryReached} would pay zero raise XP for goats and hoglins.
     */
    @Test
    void goatAndHoglinAgeBoundaryReachedNeverCallSuper() throws IOException {
        assertFalse(callsSuperAgeBoundaryReached(Goat.class),
                "Goat#ageBoundaryReached must NOT call super -- if this starts failing, "
                        + "ageBoundaryReached may have become a viable seam again and this mixin's "
                        + "own javadoc needs revisiting, not just this test");
        assertFalse(callsSuperAgeBoundaryReached(Hoglin.class),
                "Hoglin#ageBoundaryReached must NOT call super, for the same reason as Goat above");
    }

    /**
     * Control case: {@link Villager}'s own {@code ageBoundaryReached} override DOES call
     * {@code super.ageBoundaryReached()} — proving this test's bytecode-scanning method itself can
     * detect a present {@code super} call, not just its absence.
     */
    @Test
    void villagerAgeBoundaryReachedDoesCallSuperControlCase() throws IOException {
        assertTrue(callsSuperAgeBoundaryReached(Villager.class),
                "Villager#ageBoundaryReached is expected to call super -- if this now fails, the "
                        + "detection method below is broken, not the fact it is checking");
    }

    private static boolean callsSuperAgeBoundaryReached(Class<?> declaringClass) throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = declaringClass.getClassLoader().getResourceAsStream(
                declaringClass.getName().replace('.', '/') + ".class")) {
            assertNotNull(classBytes, () -> "could not locate " + declaringClass.getName()
                    + "'s own class file on the test classpath");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        MethodNode ageBoundaryReached = null;
        for (MethodNode method : classNode.methods) {
            if ("ageBoundaryReached".equals(method.name) && "()V".equals(method.desc)) {
                ageBoundaryReached = method;
                break;
            }
        }
        if (ageBoundaryReached == null) {
            fail(declaringClass.getName() + " declares no ageBoundaryReached()V of its own");
            return false; // unreachable
        }
        for (AbstractInsnNode insn : ageBoundaryReached.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL
                    && insn instanceof MethodInsnNode methodInsn
                    && "ageBoundaryReached".equals(methodInsn.name)) {
                return true;
            }
        }
        return false;
    }
}
