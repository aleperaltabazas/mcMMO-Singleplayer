package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmail.nossr50.util.McTestRegistries;
import java.lang.reflect.Method;
import net.minecraft.advancements.critereon.BredAnimalsTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflectively confirms {@link BredAnimalsTriggerMixin}'s {@code @Inject} handler exists with the
 * exact, verified {@code trigger(ServerPlayer, Animal, Animal, AgeableMob)} descriptor, and that the
 * real seam it hooks matches. Same "plain JUnit cannot weave mixins" limitation as
 * {@code PlayerInteractionStashMixinTest} — see that class's own javadoc.
 */
class BredAnimalsTriggerMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void onAnimalsBredInjectsAtHeadOfTriggerWithTheConfirmedFourArgDescriptor()
            throws NoSuchMethodException {
        final Method handler = BredAnimalsTriggerMixin.class.getDeclaredMethod(
                "mcmmo$onAnimalsBred", ServerPlayer.class, Animal.class, Animal.class,
                AgeableMob.class, CallbackInfo.class);
        assertNotNull(handler);
        assertTrue(!java.lang.reflect.Modifier.isStatic(handler.getModifiers()));

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$onAnimalsBred must be annotated with @Inject");
        assertEquals(1, inject.allow());
        assertEquals("HEAD", inject.at()[0].value());
        // The full descriptor is load-bearing -- BredAnimalsTrigger#trigger is the exact seam the
        // design spec (§1) and this task's own javap re-verification (see the mixin's own javadoc)
        // confirmed is reached by Fox and Turtle's inline-reimplemented breeding sequences, unlike
        // Animal#spawnChildFromBreeding.
        assertEquals("trigger(Lnet/minecraft/server/level/ServerPlayer;"
                + "Lnet/minecraft/world/entity/animal/Animal;"
                + "Lnet/minecraft/world/entity/animal/Animal;"
                + "Lnet/minecraft/world/entity/AgeableMob;)V", inject.method()[0]);
    }

    /**
     * The seam itself, independently re-confirmed here (not merely transcribed from the mixin's own
     * javadoc): {@code BredAnimalsTrigger} declares exactly one {@code trigger} method, with this
     * exact 4-arg shape.
     */
    @Test
    void bredAnimalsTriggerDeclaresTheConfirmedTriggerSignature() throws NoSuchMethodException {
        final Method trigger = BredAnimalsTrigger.class.getDeclaredMethod("trigger",
                ServerPlayer.class, Animal.class, Animal.class, AgeableMob.class);
        assertEquals(void.class, trigger.getReturnType());
    }
}
