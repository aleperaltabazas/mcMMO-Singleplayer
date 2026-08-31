package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * Structural regression test for {@link ShearsItemInteractMixin} — Task C, one of the six mixins in
 * this plan that had zero structural coverage until the final-review fix wave (Finding 3). Same
 * rationale/shape as {@code AbstractHorseChildAttributesMixinTest}: {@code @Local(argsOnly = true)}
 * has {@code RetentionPolicy.CLASS}, so its binding is invisible to reflection at test time and can
 * only be caught by reading the compiled handler's own bytecode. This class checks each handler's
 * annotation-visible shape (target method, {@code allow}, {@code index}/{@code ordinal}, the real
 * method's own descriptor) via reflection, plus the two forwarding-order call sites that a
 * transcription slip could silently reverse.
 */
class ShearsItemInteractMixinTest {

    private static final String INTERACT_LIVING_ENTITY = "interactLivingEntity";

    @Test
    void realInteractLivingEntityMatchesTheDescriptorEveryInjectorTargets() throws NoSuchMethodException {
        final Method real = ShearsItem.class.getDeclaredMethod(INTERACT_LIVING_ENTITY, ItemStack.class,
                Player.class, LivingEntity.class, net.minecraft.world.InteractionHand.class);
        assertNotNull(real);
    }

    @Test
    void beginShearModifiesIsShearableWithAllowOne() throws NoSuchMethodException {
        final Method handler = ShearsItemInteractMixin.class.getDeclaredMethod(
                "mcmmo$beginShear", boolean.class, Player.class, LivingEntity.class);
        final ModifyExpressionValue annotation = handler.getAnnotation(ModifyExpressionValue.class);
        assertNotNull(annotation, "mcmmo$beginShear must be @ModifyExpressionValue");
        assertEquals(INTERACT_LIVING_ENTITY, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals("Lnet/neoforged/neoforge/common/IShearable;isShearable("
                        + "Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/world/item/ItemStack;"
                        + "Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/core/BlockPos;)Z",
                annotation.at()[0].target());
    }

    @Test
    void beginShearForwardsEntityThenPlayerToHusbandryListener() throws IOException {
        // Declared params: (shearable [slot 1, boolean], player [slot 2, Player],
        // entity [slot 3, LivingEntity]) -- HusbandryListener.beginShear's own signature is
        // (LivingEntity sheared, Player player), i.e. entity FIRST, player SECOND: the reverse of the
        // handler's own declared parameter order. A transcription that pushed slot 2 then slot 3
        // (matching declaration order instead of the real call) would compile fine and silently swap
        // who got sheared for who did the shearing.
        final MethodNode handler = findHandlerMethodNode(ShearsItemInteractMixin.class,
                "mcmmo$beginShear");
        final MethodInsnNode call = findStaticCall(handler, "beginShear");
        assertNotNull(call, "mcmmo$beginShear must call HusbandryListener.beginShear");
        assertEquals("(Lnet/minecraft/world/entity/LivingEntity;"
                        + "Lnet/minecraft/world/entity/player/Player;)V", call.desc,
                "HusbandryListener.beginShear's descriptor drifted from what this test assumes");

        final VarInsnNode secondArg = (VarInsnNode) previousRealInsn(call);
        final VarInsnNode firstArg = (VarInsnNode) previousRealInsn(secondArg);
        assertEquals(Opcodes.ALOAD, firstArg.getOpcode());
        assertEquals(3, firstArg.var, "the first argument to beginShear (the sheared entity) must be "
                + "local slot 3 -- the handler's third declared parameter (entity), not slot 2 (player)");
        assertEquals(Opcodes.ALOAD, secondArg.getOpcode());
        assertEquals(2, secondArg.var, "the second argument to beginShear (the player) must be local "
                + "slot 2 -- the handler's second declared parameter (player)");
    }

    @Test
    void onShearDropsModifiesTheStoredDropsListAtOrdinalZero() throws NoSuchMethodException {
        final Method handler = ShearsItemInteractMixin.class.getDeclaredMethod(
                "mcmmo$onShearDrops", java.util.List.class);
        final ModifyVariable annotation = handler.getAnnotation(ModifyVariable.class);
        assertNotNull(annotation, "mcmmo$onShearDrops must be @ModifyVariable");
        assertEquals(INTERACT_LIVING_ENTITY, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals("STORE", annotation.at().value());
        assertEquals(0, annotation.at().ordinal(),
                "must bind the FIRST (only) List stored in interactLivingEntity -- the onSheared "
                        + "result -- not some other ordinal");
    }

    @Test
    void onShearToolDamagedModifiesTheDurabilityArgument() throws NoSuchMethodException {
        final Method handler = ShearsItemInteractMixin.class.getDeclaredMethod(
                "mcmmo$onShearToolDamaged", int.class, Player.class);
        final ModifyArg annotation = handler.getAnnotation(ModifyArg.class);
        assertNotNull(annotation, "mcmmo$onShearToolDamaged must be @ModifyArg");
        assertEquals(INTERACT_LIVING_ENTITY, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals(0, annotation.index(), "must modify hurtAndBreak's first argument (the amount)");
        assertEquals("Lnet/minecraft/world/item/ItemStack;hurtAndBreak("
                        + "ILnet/minecraft/world/entity/LivingEntity;"
                        + "Lnet/minecraft/world/entity/EquipmentSlot;)V",
                annotation.at().target());
    }

    @Test
    void onShearToolDamagedForwardsPlayerThenAmount() throws IOException {
        // Declared params: (damageAmount [slot 1, int], player [slot 2, Player]) --
        // HusbandryListener.onShearToolDamaged's own signature is (Player, int): the reverse of the
        // handler's own declared parameter order.
        final MethodNode handler = findHandlerMethodNode(ShearsItemInteractMixin.class,
                "mcmmo$onShearToolDamaged");
        final MethodInsnNode call = findStaticCall(handler, "onShearToolDamaged");
        assertNotNull(call, "mcmmo$onShearToolDamaged must call HusbandryListener.onShearToolDamaged");
        assertEquals("(Lnet/minecraft/world/entity/player/Player;I)I", call.desc,
                "HusbandryListener.onShearToolDamaged's descriptor drifted from what this test assumes");

        final VarInsnNode secondArg = (VarInsnNode) previousRealInsn(call);
        final VarInsnNode firstArg = (VarInsnNode) previousRealInsn(secondArg);
        assertEquals(Opcodes.ALOAD, firstArg.getOpcode());
        assertEquals(2, firstArg.var, "the player must be pushed first (slot 2)");
        assertEquals(Opcodes.ILOAD, secondArg.getOpcode());
        assertEquals(1, secondArg.var, "the damage amount must be pushed second (slot 1)");
    }

    @Test
    void endShearInjectsOnBothReturnsOfInteractLivingEntity() throws NoSuchMethodException {
        final Method handler = ShearsItemInteractMixin.class.getDeclaredMethod("mcmmo$endShear",
                ItemStack.class, Player.class, LivingEntity.class,
                net.minecraft.world.InteractionHand.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable.class);
        final Inject annotation = handler.getAnnotation(Inject.class);
        assertNotNull(annotation, "mcmmo$endShear must be @Inject");
        assertEquals(INTERACT_LIVING_ENTITY, annotation.method()[0]);
        assertEquals(2, annotation.allow(), "interactLivingEntity has two areturn sites (declined + "
                + "completed shear) and both must close the shear window");
        assertEquals("RETURN", annotation.at()[0].value());
    }

    private static AbstractInsnNode previousRealInsn(AbstractInsnNode from) {
        AbstractInsnNode current = from.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static MethodInsnNode findStaticCall(MethodNode handler, String calledMethodName) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode methodInsn
                    && calledMethodName.equals(methodInsn.name)
                    && methodInsn.owner.endsWith("HusbandryListener")) {
                return methodInsn;
            }
        }
        return null;
    }

    private static MethodNode findHandlerMethodNode(Class<?> mixinClass, String methodName)
            throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = mixinClass.getClassLoader().getResourceAsStream(
                mixinClass.getName().replace('.', '/') + ".class")) {
            assertNotNull(classBytes, "could not locate " + mixinClass.getSimpleName() + "'s own class file");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        for (MethodNode method : classNode.methods) {
            if (methodName.equals(method.name)) {
                return method;
            }
        }
        fail(methodName + " not found in " + mixinClass.getSimpleName());
        return null; // unreachable
    }
}
