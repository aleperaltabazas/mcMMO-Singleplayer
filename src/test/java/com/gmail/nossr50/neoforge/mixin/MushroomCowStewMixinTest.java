package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.player.Player;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Structural regression test for {@link MushroomCowStewMixin} — Task C, one of the six mixins covered
 * by the final-review fix wave (Finding 3). Confirms via reflection that the injector targets the
 * <b>4-arg</b> {@code ItemUtils.createFilledResult} overload (the bowl-of-stew call, not
 * {@link CowGoatMilkMixin}'s 3-arg bucket overload — the class javadoc calls out this exact
 * transcription trap), and via ASM that the handler forwards {@code (self, player)} in that order to
 * the shared {@code HusbandryListener.onMilked}.
 */
class MushroomCowStewMixinTest {

    @Test
    void mushroomCowDeclaresTheTwoArgMobInteractTheMixinTargets() throws NoSuchMethodException {
        assertNotNull(MushroomCow.class.getDeclaredMethod("mobInteract", Player.class,
                InteractionHand.class));
    }

    @Test
    void onStewBowledInjectsAfterTheFourArgCreateFilledResultOverload() throws NoSuchMethodException {
        final Method handler = MushroomCowStewMixin.class.getDeclaredMethod("mcmmo$onStewBowled",
                Player.class, InteractionHand.class, CallbackInfoReturnable.class);
        final Inject annotation = handler.getAnnotation(Inject.class);
        assertNotNull(annotation, "mcmmo$onStewBowled must be @Inject");
        assertEquals("mobInteract", annotation.method()[0]);
        assertEquals(1, annotation.allow());
        // The 4-arg overload (trailing boolean) -- distinct from CowGoatMilkMixin's 3-arg target.
        assertEquals("Lnet/minecraft/world/item/ItemUtils;createFilledResult("
                        + "Lnet/minecraft/world/item/ItemStack;"
                        + "Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
                annotation.at()[0].target());
        assertEquals(At.Shift.AFTER, annotation.at()[0].shift());
    }

    @Test
    void onStewBowledForwardsSelfThenPlayer() throws IOException {
        final MethodNode handler = findHandlerMethodNode("mcmmo$onStewBowled");
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode methodInsn && "onMilked".equals(methodInsn.name)
                    && methodInsn.owner.endsWith("HusbandryListener")) {
                call = methodInsn;
            }
        }
        assertNotNull(call, "mcmmo$onStewBowled must call the shared HusbandryListener.onMilked");
        assertEquals("(Lnet/minecraft/world/entity/Entity;"
                + "Lnet/minecraft/world/entity/player/Player;)V", call.desc);

        final VarInsnNode secondArg = (VarInsnNode) previousRealInsnSkippingCasts(call);
        final VarInsnNode firstArg = (VarInsnNode) previousRealInsnSkippingCasts(secondArg);
        assertEquals(Opcodes.ALOAD, firstArg.getOpcode());
        assertEquals(0, firstArg.var, "first argument must be `this` (the mooshroom) at slot 0");
        assertEquals(Opcodes.ALOAD, secondArg.getOpcode());
        assertEquals(1, secondArg.var, "second argument must be the handler's own player parameter "
                + "(slot 1)");
    }

    private static AbstractInsnNode previousRealInsnSkippingCasts(AbstractInsnNode from) {
        AbstractInsnNode current = from.getPrevious();
        while (current != null
                && (current.getOpcode() < 0 || current.getOpcode() == Opcodes.CHECKCAST)) {
            current = current.getPrevious();
        }
        return current;
    }

    private static MethodNode findHandlerMethodNode(String methodName) throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = MushroomCowStewMixin.class.getClassLoader()
                .getResourceAsStream(MushroomCowStewMixin.class.getName().replace('.', '/')
                        + ".class")) {
            assertNotNull(classBytes, "could not locate MushroomCowStewMixin's own class file");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        for (MethodNode method : classNode.methods) {
            if (methodName.equals(method.name)) {
                return method;
            }
        }
        fail(methodName + " not found in MushroomCowStewMixin");
        return null; // unreachable
    }
}
