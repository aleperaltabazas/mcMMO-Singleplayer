package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * Structural regression test for {@link ArmadilloBrushMixin} — Task C, one of the six mixins covered
 * by the final-review fix wave (Finding 3). Reflection pins both injectors' annotation-visible shape;
 * ASM verifies {@code mcmmo$onBrushed}'s three-argument
 * {@code HusbandryListener.onBrushed(self, player, brushed)} forwarding order — the riskiest call site
 * in this mixin, since {@code self} (a freshly-cast local), {@code player} (a {@code @Local(argsOnly
 * = true)}, invisible to reflection) and {@code brushed} (the modified boolean parameter) are three
 * independently-sourced values a transcription slip could reorder without any compile error.
 */
class ArmadilloBrushMixinTest {

    private static final String MOB_INTERACT = "mobInteract";

    @Test
    void armadilloDeclaresTheTwoArgMobInteractTheMixinTargets() throws NoSuchMethodException {
        assertNotNull(Armadillo.class.getDeclaredMethod(MOB_INTERACT, Player.class,
                InteractionHand.class));
    }

    @Test
    void onBrushedModifiesBrushOffScuteWithAllowOne() throws NoSuchMethodException {
        final Method handler = ArmadilloBrushMixin.class.getDeclaredMethod("mcmmo$onBrushed",
                boolean.class, Player.class);
        final ModifyExpressionValue annotation = handler.getAnnotation(ModifyExpressionValue.class);
        assertNotNull(annotation, "mcmmo$onBrushed must be @ModifyExpressionValue");
        assertEquals(MOB_INTERACT, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals(
                "Lnet/minecraft/world/entity/animal/armadillo/Armadillo;brushOffScute()Z",
                annotation.at()[0].target());
    }

    @Test
    void onBrushedForwardsSelfThenPlayerThenBrushed() throws IOException {
        // Declared params: (brushed [slot 1, boolean], player [slot 2, Player] via @Local). `self`
        // (the cast `this`) is stored to a fresh local after that -- HusbandryListener.onBrushed's
        // real signature is (Entity armadillo, Entity brusher, boolean brushed), so the call must push
        // self, then player, then brushed, in that exact order.
        final MethodNode handler = findHandlerMethodNode("mcmmo$onBrushed");
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode methodInsn && "onBrushed".equals(methodInsn.name)
                    && methodInsn.owner.endsWith("HusbandryListener")) {
                call = methodInsn;
            }
        }
        assertNotNull(call, "mcmmo$onBrushed must call HusbandryListener.onBrushed");
        assertEquals("(Lnet/minecraft/world/entity/Entity;"
                + "Lnet/minecraft/world/entity/Entity;Z)Z", call.desc,
                "HusbandryListener.onBrushed's descriptor drifted from what this test assumes");

        // Walking backward from the call: brushed (ILOAD), player (ALOAD 2), self (ALOAD >= 3, a
        // fresh local -- never slot 1 or 2, which are reserved for the two declared parameters).
        final AbstractInsnNode brushedArg = previousRealInsn(call);
        assertTrue(brushedArg instanceof VarInsnNode);
        assertEquals(Opcodes.ILOAD, ((VarInsnNode) brushedArg).getOpcode());
        assertEquals(1, ((VarInsnNode) brushedArg).var,
                "third argument (brushed) must be local slot 1 -- the handler's own boolean parameter");

        final AbstractInsnNode playerArg = previousRealInsnSkippingCasts(brushedArg);
        assertTrue(playerArg instanceof VarInsnNode);
        assertEquals(Opcodes.ALOAD, ((VarInsnNode) playerArg).getOpcode());
        assertEquals(2, ((VarInsnNode) playerArg).var,
                "second argument (player) must be local slot 2 -- the @Local(argsOnly = true) player");

        final AbstractInsnNode selfArg = previousRealInsnSkippingCasts(playerArg);
        assertTrue(selfArg instanceof VarInsnNode);
        assertEquals(Opcodes.ALOAD, ((VarInsnNode) selfArg).getOpcode());
        assertTrue(((VarInsnNode) selfArg).var >= 3,
                "first argument (self) must be a local distinct from slots 1 (brushed) and 2 "
                        + "(player) -- passing slot 1 or 2 here would mean self was mixed up with "
                        + "one of the other two arguments");
    }

    @Test
    void onBrushToolDamagedModifiesTheDurabilityArgument() throws NoSuchMethodException {
        final Method handler = ArmadilloBrushMixin.class.getDeclaredMethod(
                "mcmmo$onBrushToolDamaged", int.class);
        final ModifyArg annotation = handler.getAnnotation(ModifyArg.class);
        assertNotNull(annotation, "mcmmo$onBrushToolDamaged must be @ModifyArg");
        assertEquals(MOB_INTERACT, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals(0, annotation.index());
        assertEquals("Lnet/minecraft/world/item/ItemStack;hurtAndBreak("
                        + "ILnet/minecraft/world/entity/LivingEntity;"
                        + "Lnet/minecraft/world/entity/EquipmentSlot;)V",
                annotation.at().target());
    }

    @Test
    void onBrushToolDamagedForwardsSelfThenAmount() throws IOException {
        final MethodNode handler = findHandlerMethodNode("mcmmo$onBrushToolDamaged");
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode methodInsn
                    && "onBrushToolDamaged".equals(methodInsn.name)
                    && methodInsn.owner.endsWith("HusbandryListener")) {
                call = methodInsn;
            }
        }
        assertNotNull(call, "mcmmo$onBrushToolDamaged must call HusbandryListener.onBrushToolDamaged");
        assertEquals("(Lnet/minecraft/world/entity/Entity;I)I", call.desc);

        final VarInsnNode secondArg = (VarInsnNode) previousRealInsn(call);
        final VarInsnNode firstArg = (VarInsnNode) previousRealInsnSkippingCasts(secondArg);
        assertEquals(Opcodes.ILOAD, secondArg.getOpcode());
        assertEquals(1, secondArg.var, "damage amount pushed second (the handler's own int parameter)");
        assertEquals(Opcodes.ALOAD, firstArg.getOpcode());
        assertEquals(0, firstArg.var, "self (`this`) pushed first, at slot 0");
    }

    private static AbstractInsnNode previousRealInsn(AbstractInsnNode from) {
        AbstractInsnNode current = from.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
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
        try (InputStream classBytes = ArmadilloBrushMixin.class.getClassLoader()
                .getResourceAsStream(ArmadilloBrushMixin.class.getName().replace('.', '/')
                        + ".class")) {
            assertNotNull(classBytes, "could not locate ArmadilloBrushMixin's own class file");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        for (MethodNode method : classNode.methods) {
            if (methodName.equals(method.name)) {
                return method;
            }
        }
        fail(methodName + " not found in ArmadilloBrushMixin");
        return null; // unreachable
    }
}
