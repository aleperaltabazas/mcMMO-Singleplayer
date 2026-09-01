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
import java.lang.reflect.Modifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reflectively confirms {@link ProjectileSpawnMixin}'s {@code @Inject} handler exists with the
 * correct target method/descriptor and injection point, and that the real seam it hooks --
 * {@code ServerLevel#addFreshEntity(Entity): boolean} -- matches this test's (and the mixin's own
 * javadoc's) claims about it.
 *
 * <p><b>Why reflection instead of applying the mixin:</b> Mixin bytecode transformations only occur
 * during game launch (via ModLauncher). The test suite runs under plain JUnit with no ModLauncher
 * wiring, so the mixin is never woven into {@code ServerLevel.class} bytecode at test time -- see
 * {@code BowShootStashMixinTest}'s own javadoc for the same reasoning. {@code @Inject} is a
 * concrete annotation with {@code RetentionPolicy.RUNTIME}, so it can be read directly via
 * {@code java.lang.reflect} here; ASM is used only to independently confirm {@code
 * addFreshEntity(Entity)} is not overloaded on {@code ServerLevel}, the fact the mixin's javadoc
 * leans on to justify skipping an explicit {@code allow}/{@code require}.
 */
class ProjectileSpawnMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void onAddFreshEntityInjectsAtTailOfTheConfirmedFunnel() throws NoSuchMethodException {
        final Method handler = ProjectileSpawnMixin.class.getDeclaredMethod(
                "mcmmo$onAddFreshEntity", Entity.class, CallbackInfoReturnable.class);
        assertNotNull(handler, "mcmmo$onAddFreshEntity must exist with (Entity, "
                + "CallbackInfoReturnable) parameters");
        assertFalse(Modifier.isStatic(handler.getModifiers()),
                "an @Inject handler on an instance target method must itself be an instance method");

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$onAddFreshEntity must be annotated with @Inject");
        assertEquals("addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", inject.method()[0],
                "must target the confirmed descriptor -- ServerLevel#addFreshEntity(Entity): boolean "
                        + "-- not a guessed Yarn-mapped static spawn funnel that does not exist on "
                        + "this mapping/version (see the mixin's own class javadoc)");

        final At at = inject.at()[0];
        assertEquals("TAIL", at.value());
    }

    /**
     * The seam itself: confirms {@code ServerLevel} declares {@code addFreshEntity(Entity):
     * boolean} -- the mixin's target -- with the shape the mixin relies on.
     */
    @Test
    void serverLevelDeclaresAddFreshEntityWithTheConfirmedSignature() throws NoSuchMethodException {
        final Method addFreshEntity = ServerLevel.class.getDeclaredMethod("addFreshEntity", Entity.class);
        assertEquals(boolean.class, addFreshEntity.getReturnType());
        assertTrue(Modifier.isPublic(addFreshEntity.getModifiers()), "addFreshEntity must be public");
    }

    /**
     * Independently re-confirms (via ASM, not trusting the implementer's own by-hand {@code javap}
     * read) that {@code ServerLevel} declares exactly one method named {@code addFreshEntity} --
     * i.e. the name is not overloaded, so the named-method {@code TAIL} injector above is
     * unambiguous without an explicit {@code allow}/{@code require} beyond {@code
     * mcmmo.mixins.json}'s {@code defaultRequire = 1}. If a future Minecraft version adds an
     * overload, this test fails loudly instead of the mixin silently matching more (or fewer) call
     * sites than intended.
     */
    @Test
    void addFreshEntityIsNotOverloadedOnServerLevel() throws IOException {
        final ClassNode classNode = new ClassNode();
        try (InputStream classBytes = ServerLevel.class.getClassLoader().getResourceAsStream(
                ServerLevel.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(classBytes, "could not locate ServerLevel's own class file on the test "
                    + "classpath");
            new ClassReader(classBytes).accept(classNode, 0);
        }
        int matchCount = 0;
        for (MethodNode method : classNode.methods) {
            if ("addFreshEntity".equals(method.name)) {
                matchCount++;
            }
        }
        if (matchCount == 0) {
            fail("addFreshEntity not found in ServerLevel's class file");
        }
        assertEquals(1, matchCount, "addFreshEntity is now overloaded on ServerLevel -- re-derive "
                + "the target descriptor (and possibly an explicit allow) for ProjectileSpawnMixin "
                + "before trusting the single-match TAIL injector");
    }
}
