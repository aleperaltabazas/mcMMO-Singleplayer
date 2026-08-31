package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.goat.Goat;
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
 * Structural regression test for {@link CowGoatMilkMixin} — Task C, one of the six mixins covered by
 * the final-review fix wave (Finding 3). Reflection confirms both {@code Cow#mobInteract} and
 * {@code Goat#mobInteract} still declare the 2-arg {@code (Player, InteractionHand)} shape this shared
 * {@code @Mixin({Cow.class, Goat.class})} targets, and that the single injector's {@code @At} target
 * still matches {@code ItemUtils.createFilledResult}'s real 3-arg descriptor; ASM confirms the handler
 * forwards {@code (self, player)} to {@code HusbandryListener.onMilked} in that order.
 */
class CowGoatMilkMixinTest {

    private static final String MOB_INTERACT = "mobInteract";

    @Test
    void cowAndGoatBothDeclareTheTwoArgMobInteractTheMixinTargets() throws NoSuchMethodException {
        assertNotNull(Cow.class.getDeclaredMethod(MOB_INTERACT, Player.class, InteractionHand.class));
        assertNotNull(Goat.class.getDeclaredMethod(MOB_INTERACT, Player.class, InteractionHand.class));
    }

    @Test
    void onMilkedInjectsAfterCreateFilledResultWithAllowOne() throws NoSuchMethodException {
        final Method handler = CowGoatMilkMixin.class.getDeclaredMethod("mcmmo$onMilked",
                Player.class, InteractionHand.class, CallbackInfoReturnable.class);
        final Inject annotation = handler.getAnnotation(Inject.class);
        assertNotNull(annotation, "mcmmo$onMilked must be @Inject");
        assertEquals(MOB_INTERACT, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals("Lnet/minecraft/world/item/ItemUtils;createFilledResult("
                        + "Lnet/minecraft/world/item/ItemStack;"
                        + "Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
                annotation.at()[0].target());
        assertEquals(At.Shift.AFTER, annotation.at()[0].shift());
    }

    @Test
    void onMilkedForwardsSelfThenPlayer() throws IOException {
        final MethodNode handler = findHandlerMethodNode("mcmmo$onMilked");
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode methodInsn && "onMilked".equals(methodInsn.name)
                    && methodInsn.owner.endsWith("HusbandryListener")) {
                call = methodInsn;
            }
        }
        assertNotNull(call, "mcmmo$onMilked must call HusbandryListener.onMilked");
        assertEquals("(Lnet/minecraft/world/entity/Entity;"
                + "Lnet/minecraft/world/entity/player/Player;)V", call.desc);

        final VarInsnNode secondArg = (VarInsnNode) previousRealInsnSkippingCasts(call);
        final VarInsnNode firstArg = (VarInsnNode) previousRealInsnSkippingCasts(secondArg);
        assertEquals(Opcodes.ALOAD, firstArg.getOpcode());
        assertEquals(0, firstArg.var, "first argument must be `this` (the cow/goat) at slot 0");
        assertEquals(Opcodes.ALOAD, secondArg.getOpcode());
        assertEquals(1, secondArg.var, "second argument must be the handler's own player parameter "
                + "(slot 1) -- the real mobInteract parameter, not a decoy");
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
        try (InputStream classBytes = CowGoatMilkMixin.class.getClassLoader()
                .getResourceAsStream(CowGoatMilkMixin.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(classBytes, "could not locate CowGoatMilkMixin's own class file");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        for (MethodNode method : classNode.methods) {
            if (methodName.equals(method.name)) {
                return method;
            }
        }
        fail(methodName + " not found in CowGoatMilkMixin");
        return null; // unreachable
    }
}
