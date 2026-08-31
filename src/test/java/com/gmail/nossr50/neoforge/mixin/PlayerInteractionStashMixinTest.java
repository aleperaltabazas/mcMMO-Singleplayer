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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * Reflectively confirms {@link PlayerInteractionStashMixin}'s two {@code @Inject} handlers exist
 * with the correct target method and injection points, and that the real seam they hook —
 * {@code Player#interactOn(Entity, InteractionHand)} — matches this test's (and the mixin's own
 * javadoc's) claims about it.
 *
 * <p><b>Why reflection instead of applying the mixin:</b> Mixin bytecode transformations only
 * occur during game launch (via ModLauncher). The test suite runs under plain JUnit with no
 * ModLauncher wiring, so the mixin is never woven into {@code Player.class} bytecode at test time
 * — see {@code LivingEntityDropFromLootTableAccessorTest}'s own javadoc for the same reasoning.
 * Unlike that test's {@code @Invoker} interface, {@code @Inject} is a concrete annotation with
 * {@code RetentionPolicy.RUNTIME} (confirmed by {@code CampfireCookMixinTest}'s own precedent of
 * reading {@code @ModifyArg} via plain {@code Method#getAnnotation}), so it can be read directly
 * via {@code java.lang.reflect} here, no ASM needed for the annotation itself — ASM is used only
 * to independently re-count {@code interactOn}'s {@code areturn} opcodes, the same verification
 * this class's own bytecode read performed by hand during implementation.
 */
class PlayerInteractionStashMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void beginInteractionInjectsAtHeadOfInteractOn() throws NoSuchMethodException {
        final Method handler = PlayerInteractionStashMixin.class.getDeclaredMethod(
                "mcmmo$beginInteraction", Entity.class, InteractionHand.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable.class);
        assertNotNull(handler, "mcmmo$beginInteraction must exist with (Entity, InteractionHand, "
                + "CallbackInfoReturnable) parameters");
        assertTrue(!Modifier.isStatic(handler.getModifiers()),
                "an @Inject handler on an instance target method must itself be an instance method");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$beginInteraction must be annotated with @Inject");
        assertEquals("interactOn", inject.method()[0]);

        final At at = inject.at()[0];
        assertEquals("HEAD", at.value());
    }

    @Test
    void endInteractionInjectsAtEveryReturnOfInteractOn() throws NoSuchMethodException {
        final Method handler = PlayerInteractionStashMixin.class.getDeclaredMethod(
                "mcmmo$endInteraction", Entity.class, InteractionHand.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable.class);
        assertNotNull(handler, "mcmmo$endInteraction must exist with (Entity, InteractionHand, "
                + "CallbackInfoReturnable) parameters");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$endInteraction must be annotated with @Inject");
        assertEquals("interactOn", inject.method()[0]);
        assertEquals(5, inject.allow(), "interactOn has five areturn opcodes -- see "
                + "endInteractOnHasExactlyFiveAreturnOpcodes, which independently re-counts them "
                + "via ASM -- so allow must be 5, not the implicit single-match default");

        final At at = inject.at()[0];
        assertEquals("RETURN", at.value());
    }

    /**
     * The seam itself: confirms {@code Player} declares {@code interactOn(Entity,
     * InteractionHand)} returning {@code InteractionResult} -- the exact signature the design
     * spec (§4) flagged as unverified ("the implementer must confirm the exact signature ... via
     * javap or a source read before writing Task A's mixin"). Note the spec's own guessed name,
     * {@code interact}, does not exist on {@code Player} at all in 1.21.1 -- see the mixin's class
     * javadoc for the full correction.
     */
    @Test
    void playerDeclaresInteractOnWithTheConfirmedSignature() throws NoSuchMethodException {
        final Method interactOn = Player.class.getDeclaredMethod("interactOn", Entity.class,
                InteractionHand.class);
        assertEquals(InteractionResult.class, interactOn.getReturnType());
        assertTrue(Modifier.isPublic(interactOn.getModifiers()), "interactOn must be public");
    }

    /**
     * Independently re-counts {@code interactOn}'s {@code areturn} opcodes via ASM (rather than
     * trusting the implementer's own by-hand {@code javap -c} count baked into {@code allow = 5}
     * above) -- if a future Minecraft version restructures the method's control flow, this test
     * fails loudly instead of the mixin silently under- or over-matching at boot.
     */
    @Test
    void interactOnHasExactlyFiveAreturnOpcodes() throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = Player.class.getClassLoader().getResourceAsStream(
                Player.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(classBytes, "could not locate Player's own class file on the test classpath");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        MethodNode interactOn = null;
        for (MethodNode method : classNode.methods) {
            if ("interactOn".equals(method.name)) {
                interactOn = method;
                break;
            }
        }
        if (interactOn == null) {
            fail("interactOn not found in Player's class file");
            return; // unreachable
        }
        int areturnCount = 0;
        for (AbstractInsnNode insn : interactOn.instructions) {
            if (insn.getOpcode() == Opcodes.ARETURN) {
                areturnCount++;
            }
        }
        assertEquals(5, areturnCount, "interactOn's areturn count changed -- re-derive allow "
                + "for PlayerInteractionStashMixin's RETURN injector before trusting it");
    }
}
