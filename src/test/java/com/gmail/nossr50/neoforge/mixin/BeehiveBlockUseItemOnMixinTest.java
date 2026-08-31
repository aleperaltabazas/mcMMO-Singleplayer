package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * Structural regression test for {@link BeehiveBlockUseItemOnMixin} — Task C, one of the six mixins
 * covered by the final-review fix wave (Finding 3). Reflection pins every injector's annotation-visible
 * shape (target method, {@code allow}, {@code index}, the real {@code useItemOn} descriptor each
 * targets); ASM reads the compiled {@code mcmmo$hiveHarvestLeavesBeesCalm} handler's actual bytecode,
 * because the one bug this class javadoc explicitly warns about — gating on {@code !isSmokeyPos}
 * instead of widening it with {@code ||} — compiles cleanly either way and reads as plausible from the
 * source alone. Only the bytecode shape tells the two apart.
 */
class BeehiveBlockUseItemOnMixinTest {

    private static final String USE_ITEM_ON = "useItemOn";

    @Test
    void realUseItemOnMatchesTheDescriptorEveryInjectorTargets() throws NoSuchMethodException {
        final Method real = BeehiveBlock.class.getDeclaredMethod(USE_ITEM_ON, ItemStack.class,
                BlockState.class, Level.class, BlockPos.class, Player.class,
                net.minecraft.world.InteractionHand.class, BlockHitResult.class);
        assertNotNull(real);
    }

    @Test
    void onHoneycombHarvestedInjectsAfterDropHoneycomb() throws NoSuchMethodException {
        final Method handler = BeehiveBlockUseItemOnMixin.class.getDeclaredMethod(
                "mcmmo$onHoneycombHarvested", ItemStack.class, BlockState.class, Level.class,
                BlockPos.class, Player.class, net.minecraft.world.InteractionHand.class,
                BlockHitResult.class, CallbackInfoReturnable.class);
        final Inject annotation = handler.getAnnotation(Inject.class);
        assertNotNull(annotation, "mcmmo$onHoneycombHarvested must be @Inject");
        assertEquals(USE_ITEM_ON, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals("Lnet/minecraft/world/level/block/BeehiveBlock;dropHoneycomb("
                        + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
                annotation.at()[0].target());
        assertEquals(At.Shift.AFTER, annotation.at()[0].shift());
    }

    @Test
    void onHoneyBottledInjectsAfterTheGlassShrink() throws NoSuchMethodException {
        final Method handler = BeehiveBlockUseItemOnMixin.class.getDeclaredMethod(
                "mcmmo$onHoneyBottled", ItemStack.class, BlockState.class, Level.class, BlockPos.class,
                Player.class, net.minecraft.world.InteractionHand.class, BlockHitResult.class,
                CallbackInfoReturnable.class);
        final Inject annotation = handler.getAnnotation(Inject.class);
        assertNotNull(annotation, "mcmmo$onHoneyBottled must be @Inject");
        assertEquals(USE_ITEM_ON, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals("Lnet/minecraft/world/item/ItemStack;shrink(I)V", annotation.at()[0].target());
        assertEquals(At.Shift.AFTER, annotation.at()[0].shift());
    }

    @Test
    void hiveHarvestLeavesBeesCalmModifiesIsSmokeyPos() throws NoSuchMethodException {
        final Method handler = BeehiveBlockUseItemOnMixin.class.getDeclaredMethod(
                "mcmmo$hiveHarvestLeavesBeesCalm", boolean.class, Player.class);
        final ModifyExpressionValue annotation = handler.getAnnotation(ModifyExpressionValue.class);
        assertNotNull(annotation, "mcmmo$hiveHarvestLeavesBeesCalm must be @ModifyExpressionValue");
        assertEquals(USE_ITEM_ON, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals("Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos("
                        + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z",
                annotation.at()[0].target());
    }

    /**
     * The reviewer's specific ask: assert the {@code smokey ||} shape, not {@code !smokey}. Both
     * compile; only one reads correctly as "widen the calm case", per the class javadoc's derivation
     * from the real 1.21.1 branch ({@code if (!isSmokeyPos) {...anger...} else {...calm...}}).
     *
     * <p>The handler body is {@code return smokey || HusbandryListener.hiveHarvestLeavesBeesCalm(player);}
     * — Java's {@code ||} short-circuit compiles to: load {@code smokey}, {@code IFNE} straight to a
     * branch that pushes {@code true} (skipping the listener call entirely when already smokey), else
     * fall through to the listener call and its result. A {@code !smokey} bug (i.e.
     * {@code !smokey && listener}, or simply returning the negation) would instead branch on the
     * listener call reachability in the opposite sense, or never reach an unconditional
     * {@code ICONST_1} push guarded directly on {@code smokey} being true.
     */
    @Test
    void hiveHarvestLeavesBeesCalmBytecodeIsAnOrNotAnAndOrNegation() throws IOException {
        final MethodNode handler = findHandlerMethodNode("mcmmo$hiveHarvestLeavesBeesCalm");
        final InsnList insns = handler.instructions;

        // First real instruction must load the boolean parameter `smokey` (slot 1) -- the left
        // operand of the ||, read directly and unmodified (not negated by an IFEQ-then-invert dance).
        AbstractInsnNode first = firstRealInsn(insns);
        assertTrue(first instanceof VarInsnNode, "expected the handler to open by loading `smokey`");
        final VarInsnNode loadSmokey = (VarInsnNode) first;
        assertEquals(Opcodes.ILOAD, loadSmokey.getOpcode());
        assertEquals(1, loadSmokey.var, "smokey is the handler's first declared parameter -> slot 1");

        // Immediately after loading it, `||` short-circuits on smokey being true (IFNE), which is the
        // opposite polarity from what a `!smokey`-gated bug would need (IFEQ jumping around the
        // "stay calm" branch instead of skipping past it).
        AbstractInsnNode next = nextRealInsn(loadSmokey);
        assertTrue(next instanceof JumpInsnNode, "expected a conditional jump right after loading smokey");
        assertEquals(Opcodes.IFNE, ((JumpInsnNode) next).getOpcode(),
                "must short-circuit on smokey being TRUE (IFNE) -- this is the || shape. An IFEQ here "
                        + "would mean the handler branches on smokey being false, which is the "
                        + "!smokey-gated bug the reviewer flagged");

        // And the fall-through path (smokey == false) must call HusbandryListener's method -- i.e.
        // the listener is only consulted when NOT already smokey, exactly what `||` means.
        boolean callsListener = false;
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof MethodInsnNode call
                    && "hiveHarvestLeavesBeesCalm".equals(call.name)
                    && call.owner.endsWith("HusbandryListener")) {
                callsListener = true;
                assertEquals("(Lnet/minecraft/world/entity/player/Player;)Z", call.desc);
            }
        }
        assertTrue(callsListener, "the fall-through (not-yet-smokey) path must call "
                + "HusbandryListener.hiveHarvestLeavesBeesCalm");
    }

    @Test
    void onHiveToolDamagedModifiesTheDurabilityArgument() throws NoSuchMethodException {
        final Method handler = BeehiveBlockUseItemOnMixin.class.getDeclaredMethod(
                "mcmmo$onHiveToolDamaged", int.class, Player.class);
        final ModifyArg annotation = handler.getAnnotation(ModifyArg.class);
        assertNotNull(annotation, "mcmmo$onHiveToolDamaged must be @ModifyArg");
        assertEquals(USE_ITEM_ON, annotation.method()[0]);
        assertEquals(1, annotation.allow());
        assertEquals(0, annotation.index());
        assertEquals("Lnet/minecraft/world/item/ItemStack;hurtAndBreak("
                        + "ILnet/minecraft/world/entity/LivingEntity;"
                        + "Lnet/minecraft/world/entity/EquipmentSlot;)V",
                annotation.at().target());
    }

    @Test
    void onHiveToolDamagedForwardsPlayerThenAmount() throws IOException {
        // Same shape as ShearsItemInteractMixin#onShearToolDamaged: declared params are
        // (damageAmount [slot 1], player [slot 2]), and HusbandryListener.onHiveToolDamaged's real
        // signature is (Player, int) -- the reverse of declaration order.
        final MethodNode handler = findHandlerMethodNode("mcmmo$onHiveToolDamaged");
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode methodInsn
                    && "onHiveToolDamaged".equals(methodInsn.name)
                    && methodInsn.owner.endsWith("HusbandryListener")) {
                call = methodInsn;
            }
        }
        assertNotNull(call, "mcmmo$onHiveToolDamaged must call HusbandryListener.onHiveToolDamaged");
        assertEquals("(Lnet/minecraft/world/entity/player/Player;I)I", call.desc);

        final VarInsnNode secondArg = (VarInsnNode) previousRealInsn(call);
        final VarInsnNode firstArg = (VarInsnNode) previousRealInsn(secondArg);
        assertEquals(Opcodes.ALOAD, firstArg.getOpcode());
        assertEquals(2, firstArg.var, "player pushed first (slot 2)");
        assertEquals(Opcodes.ILOAD, secondArg.getOpcode());
        assertEquals(1, secondArg.var, "damage amount pushed second (slot 1)");
    }

    private static AbstractInsnNode previousRealInsn(AbstractInsnNode from) {
        AbstractInsnNode current = from.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static AbstractInsnNode nextRealInsn(AbstractInsnNode from) {
        AbstractInsnNode current = from.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static AbstractInsnNode firstRealInsn(InsnList insns) {
        AbstractInsnNode current = insns.getFirst();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static MethodNode findHandlerMethodNode(String methodName) throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = BeehiveBlockUseItemOnMixin.class.getClassLoader()
                .getResourceAsStream(BeehiveBlockUseItemOnMixin.class.getName().replace('.', '/')
                        + ".class")) {
            assertNotNull(classBytes, "could not locate BeehiveBlockUseItemOnMixin's own class file");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        for (MethodNode method : classNode.methods) {
            if (methodName.equals(method.name)) {
                return method;
            }
        }
        fail(methodName + " not found in BeehiveBlockUseItemOnMixin");
        return null; // unreachable
    }
}
