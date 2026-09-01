package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Structural regression test for {@link LivingEntityGlideMixin} — Agility listener plan, Task A.
 *
 * <p>Confirms two things, both re-derived from the real compiled class rather than assumed:
 * the injector's declared shape against {@link LivingEntity#travel(Vec3)}'s real descriptor, and —
 * the load-bearing fact this mixin's own javadoc calls out — that {@code travel} still has exactly
 * one {@code RETURN} opcode. A future Minecraft version adding an early-return branch to {@code
 * travel} would silently leave a gliding player unboosted on that branch while this injector stayed
 * green everywhere else; this test turns that into a build failure instead.
 */
class LivingEntityGlideMixinTest {

    @Test
    void realTravelMatchesTheDescriptorTheInjectorTargets() throws NoSuchMethodException {
        final Method real = LivingEntity.class.getDeclaredMethod("travel", Vec3.class);
        assertNotNull(real);
        assertEquals(void.class, real.getReturnType());
    }

    @Test
    void handlerInjectsAtTravelsTailWithAllowOne() throws NoSuchMethodException {
        final Method handler = LivingEntityGlideMixin.class.getDeclaredMethod(
                "mcmmo$applyGlideBonus", Vec3.class, CallbackInfo.class);
        final Inject annotation = handler.getAnnotation(Inject.class);
        assertNotNull(annotation, "mcmmo$applyGlideBonus must be @Inject");
        assertEquals("travel", annotation.method()[0]);
        assertEquals(1, annotation.allow());
        final At at = annotation.at()[0];
        assertEquals("TAIL", at.value());
    }

    @Test
    void travelHasExactlyOneReturnSoTailIsReachableOnEveryBranch() throws IOException {
        final MethodNode travel = findTravelMethodNode();
        int returnCount = 0;
        for (AbstractInsnNode insn : travel.instructions) {
            if (insn instanceof InsnNode insnNode && isReturnOpcode(insnNode.getOpcode())) {
                returnCount++;
            }
        }
        assertEquals(1, returnCount, "LivingEntity#travel must have exactly one RETURN for "
                + "@At(\"TAIL\") to be reachable on every branch, gliding included");
    }

    private static boolean isReturnOpcode(int opcode) {
        return opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN || opcode == Opcodes.IRETURN
                || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
                || opcode == Opcodes.DRETURN;
    }

    private static MethodNode findTravelMethodNode() throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = Player.class.getClassLoader().getResourceAsStream(
                LivingEntity.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(classBytes, "could not locate LivingEntity's own class file on the "
                    + "runtime classpath");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        for (MethodNode method : classNode.methods) {
            if ("travel".equals(method.name)
                    && "(Lnet/minecraft/world/phys/Vec3;)V".equals(method.desc)) {
                return method;
            }
        }
        fail("travel(Vec3) not found in LivingEntity's class file");
        return null; // unreachable
    }
}
