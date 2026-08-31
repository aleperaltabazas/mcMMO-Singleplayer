package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Structural regression test for the argument-order bug a Task D code review caught: the HEAD
 * injector's handler named its two {@code setOffspringAttributes} parameters backwards
 * ({@code child} first, {@code mate} second) and forwarded the wrong local to
 * {@code HusbandryListener.beginSelectiveBreeding}. Calling {@code beginSelectiveBreeding} directly
 * with correctly-labeled mocks (as {@code HusbandryListenerSelectiveBreedingTest} does) can never
 * catch this class of bug, because it never exercises the mixin's own parameter forwarding — hence
 * this test reads the compiled handler's bytecode instead of calling it.
 *
 * <p>Two things are checked, both re-verified against {@code javap -p -c} on
 * {@code AbstractHorse#setOffspringAttributes} rather than assumed from the source: the handler's
 * declared parameter types match that real method's descriptor order ({@code AgeableMob} first,
 * {@code AbstractHorse} second — first is the true other breeding parent, second is the brand-new
 * foal), and the handler's own bytecode passes the <em>first</em> parameter (local variable slot 1,
 * the real mate) as {@code HusbandryListener.beginSelectiveBreeding}'s second argument — not the
 * second parameter (slot 2, the valueless just-created foal).
 */
class AbstractHorseChildAttributesMixinTest {

    private static final String BEGIN_SELECTIVE_BREEDING_TARGET =
            "Lcom/gmail/nossr50/neoforge/listeners/HusbandryListener;beginSelectiveBreeding("
                    + "Lnet/minecraft/world/entity/animal/Animal;"
                    + "Lnet/minecraft/world/entity/animal/Animal;)V";

    @Test
    void headHandlerParametersMatchSetOffspringAttributesRealDescriptorOrder()
            throws NoSuchMethodException {
        final Method handler = AbstractHorseChildAttributesMixin.class.getDeclaredMethod(
                "mcmmo$beginSelectiveBreeding", AgeableMob.class, AbstractHorse.class,
                CallbackInfo.class);
        assertNotNull(handler, "mcmmo$beginSelectiveBreeding must declare (AgeableMob mate, "
                + "AbstractHorse child, CallbackInfo) -- matching setOffspringAttributes' own "
                + "descriptor order, first param = real mate, second = newly-created foal");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$beginSelectiveBreeding must be annotated with @Inject");
        assertEquals(1, inject.allow());
        final At at = inject.at()[0];
        assertEquals("HEAD", at.value());
    }

    @Test
    void headHandlerForwardsTheFirstParameterNotTheSecondAsTheMate() throws IOException {
        final MethodNode handler = findHandlerMethodNode("mcmmo$beginSelectiveBreeding");

        final MethodInsnNode call = findBeginSelectiveBreedingCall(handler);
        assertNotNull(call, "mcmmo$beginSelectiveBreeding's body must call "
                + "HusbandryListener.beginSelectiveBreeding");

        // Immediately before the call, the second argument (the "mate" HusbandryListener sees) is
        // pushed last -- an ALOAD of local slot 1 (the handler's own first declared parameter, which
        // setOffspringAttributes' real descriptor binds to the true other parent), optionally
        // followed by a CHECKCAST narrowing AgeableMob down to Animal. It must NOT be slot 2, which
        // is the brand-new foal that never has a love cause.
        final AbstractInsnNode beforeCall = previousRealInsnSkippingCasts(call);
        assertTrue(beforeCall instanceof VarInsnNode,
                "expected an ALOAD (possibly followed by a CHECKCAST) immediately before the "
                        + "beginSelectiveBreeding call, found " + beforeCall);
        final VarInsnNode load = (VarInsnNode) beforeCall;
        assertEquals(Opcodes.ALOAD, load.getOpcode());
        assertEquals(1, load.var, "the second argument passed to "
                + "HusbandryListener.beginSelectiveBreeding must be local slot 1 (the handler's "
                + "first parameter, the real mate) -- not slot 2 (the newly-created foal), or the "
                + "listener's \"fall back to the mate\" branch can never see a valid mate");
    }

    private static MethodInsnNode findBeginSelectiveBreedingCall(MethodNode handler) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode methodInsn
                    && "beginSelectiveBreeding".equals(methodInsn.name)
                    && methodInsn.owner.endsWith("HusbandryListener")) {
                final String actualTarget = "L" + methodInsn.owner + ";" + methodInsn.name
                        + methodInsn.desc;
                assertEquals(BEGIN_SELECTIVE_BREEDING_TARGET, actualTarget,
                        "beginSelectiveBreeding's descriptor drifted from what this test assumes");
                return methodInsn;
            }
        }
        return null;
    }

    private static AbstractInsnNode previousRealInsnSkippingCasts(AbstractInsnNode from) {
        AbstractInsnNode current = from.getPrevious();
        while (current != null
                && (current.getOpcode() < 0 || current.getOpcode() == Opcodes.CHECKCAST)) {
            current = current.getPrevious();
        }
        return current;
    }

    private static MethodNode findHandlerMethodNode(String name) throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = AbstractHorseChildAttributesMixin.class.getClassLoader()
                .getResourceAsStream(AbstractHorseChildAttributesMixin.class.getName()
                        .replace('.', '/') + ".class")) {
            assertNotNull(classBytes, "could not locate AbstractHorseChildAttributesMixin's own "
                    + "class file");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        for (MethodNode method : classNode.methods) {
            if (name.equals(method.name)) {
                return method;
            }
        }
        fail(name + " not found in AbstractHorseChildAttributesMixin's class file");
        return null; // unreachable
    }
}
