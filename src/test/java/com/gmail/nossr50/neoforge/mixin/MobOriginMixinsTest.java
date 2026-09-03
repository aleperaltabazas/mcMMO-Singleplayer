package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Structural regression coverage for the mob-origin-tracking plan's four mixins — the same "does it
 * compile and bind" vs. "does it forward the right entity" distinction
 * {@code AbstractHorseChildAttributesMixinTest} was written for, and the exact failure mode
 * {@code MobOrigins}'s own class doc calls "strictly worse than the ZERO it replaced". Reflection
 * alone (target method, {@code allow}, {@code @At}) already rules out three of the four mixins'
 * only realistic mistake, since every value each of those three forwards is uniquely typed — the
 * compiler itself would refuse a swap. {@link #animalBreedChildOriginMixinForwardsChildNotMate()}
 * is the one that needed real bytecode inspection: {@code Animal} (the {@code mate} parameter) is a
 * subtype of {@code AgeableMob} (the {@code child} parameter's type), so a swapped argument would
 * have compiled silently.
 */
class MobOriginMixinsTest {

    // ---- EntityTypeSpawnOriginMixin ---------------------------------------------------------

    @Test
    void entityTypeSpawnOriginMixinTargetsTheSixArgCreateOverload() throws NoSuchMethodException {
        final Method handler = EntityTypeSpawnOriginMixin.class.getDeclaredMethod("mcmmo$onCreate",
                ServerLevel.class, java.util.function.Consumer.class, BlockPos.class,
                MobSpawnType.class, boolean.class, boolean.class, CallbackInfoReturnable.class);
        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject);
        assertEquals(3, inject.allow(), "create(...) has three areturn points, verified via javap");
        assertEquals("RETURN", inject.at()[0].value());
    }

    // ---- AnimalBreedChildOriginMixin --------------------------------------------------------

    @Test
    void animalBreedChildOriginMixinTargetsFinalizeSpawnChildFromBreedingHead()
            throws NoSuchMethodException {
        final Method handler = AnimalBreedChildOriginMixin.class.getDeclaredMethod(
                "mcmmo$onFinalizeSpawnChildFromBreeding", ServerLevel.class, Animal.class,
                AgeableMob.class, CallbackInfo.class);
        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject);
        assertEquals("HEAD", inject.at()[0].value());
    }

    @Test
    void animalBreedChildOriginMixinForwardsChildNotMate() throws IOException {
        // ⚠️ mate (Animal) is a subtype of child's declared type (AgeableMob), so a swapped local
        // would compile without error -- exactly the AbstractHorseChildAttributesMixin bug class.
        final MethodNode handler = findHandlerMethodNode(AnimalBreedChildOriginMixin.class,
                "mcmmo$onFinalizeSpawnChildFromBreeding");
        final MethodInsnNode call = findCall(handler, "stampOnSpawn",
                "com/gmail/nossr50/platform/MobOrigins");
        assertNotNull(call, "handler must call MobOrigins.stampOnSpawn");

        // Immediately before the call: ALOAD 3 (child, this handler's third parameter) then
        // GETSTATIC MobSpawnType.BREEDING. NOT ALOAD 2 (mate).
        final AbstractInsnNode beforeStatic = call.getPrevious();
        assertTrue(beforeStatic.getOpcode() == Opcodes.GETSTATIC,
                "expected MobSpawnType.BREEDING pushed immediately before the call");
        final AbstractInsnNode load = beforeStatic.getPrevious();
        assertTrue(load instanceof VarInsnNode, "expected an ALOAD before the BREEDING constant");
        assertEquals(Opcodes.ALOAD, ((VarInsnNode) load).getOpcode());
        assertEquals(3, ((VarInsnNode) load).var,
                "stampOnSpawn's first argument must be local slot 3 (child, this handler's third "
                        + "declared parameter) -- not slot 2 (mate), or every bred animal's origin "
                        + "would be stamped onto its own parent instead of its offspring");
    }

    // ---- MobConversionOriginMixin -----------------------------------------------------------

    @Test
    void mobConversionOriginMixinTargetsConvertToReturn() throws NoSuchMethodException {
        final Method handler = MobConversionOriginMixin.class.getDeclaredMethod("mcmmo$onConvertTo",
                EntityType.class, boolean.class, CallbackInfoReturnable.class);
        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject);
        assertEquals(3, inject.allow(), "convertTo has three areturn points, verified via javap");
        assertEquals("RETURN", inject.at()[0].value());
    }

    // ---- SummonCommandOriginMixin -----------------------------------------------------------

    @Test
    void summonCommandOriginMixinHandlerIsStatic() throws NoSuchMethodException {
        final Method handler = SummonCommandOriginMixin.class.getDeclaredMethod(
                "mcmmo$onCreateEntity", CommandSourceStack.class, Holder.Reference.class, Vec3.class,
                CompoundTag.class, boolean.class, CallbackInfoReturnable.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(handler.getModifiers()),
                "createEntity is a static method, so its @Inject handler must be static too");
        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject);
        assertEquals("RETURN", inject.at()[0].value());
    }

    @Test
    void summonCommandOriginMixinStampsCommandOrigin() throws IOException {
        final MethodNode handler = findHandlerMethodNode(SummonCommandOriginMixin.class,
                "mcmmo$onCreateEntity");
        final MethodInsnNode call = findCall(handler, "stampOnSpawn",
                "com/gmail/nossr50/platform/MobOrigins");
        assertNotNull(call, "handler must call MobOrigins.stampOnSpawn");
        final AbstractInsnNode beforeCall = call.getPrevious();
        assertTrue(beforeCall.getOpcode() == Opcodes.GETSTATIC,
                "expected MobSpawnType.COMMAND pushed immediately before the call -- a /summon-ed "
                        + "mob must be marked PLAYER_PLACED, not left unmarked as some other reason");
    }

    // ---- shared ASM helpers -------------------------------------------------------------------

    private static MethodInsnNode findCall(MethodNode handler, String name, String ownerSuffix) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode methodInsn && name.equals(methodInsn.name)
                    && methodInsn.owner.endsWith(ownerSuffix)) {
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
            assertNotNull(classBytes, "could not locate " + mixinClass.getSimpleName()
                    + "'s own class file");
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
