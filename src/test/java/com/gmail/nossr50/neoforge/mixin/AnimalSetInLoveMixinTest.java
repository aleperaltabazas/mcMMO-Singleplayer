package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.gmail.nossr50.util.McTestRegistries;
import java.lang.reflect.Method;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflectively confirms {@link AnimalSetInLoveMixin}'s {@code @Inject} handler targets
 * {@code Animal#setInLove(Player)} at {@code TAIL} — the Multi-Breed seam confirmed via
 * {@code javap -p} against the merged jar (see the mixin's own javadoc). Same plain-JUnit
 * mixin-weaving limitation as {@code PlayerInteractionStashMixinTest}.
 */
class AnimalSetInLoveMixinTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void onLovePlayerInjectsAtTailOfSetInLove() throws NoSuchMethodException {
        final Method handler = AnimalSetInLoveMixin.class.getDeclaredMethod(
                "mcmmo$onLovePlayer", Player.class, CallbackInfo.class);
        assertNotNull(handler);

        final Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject, "mcmmo$onLovePlayer must be annotated with @Inject");
        assertEquals("setInLove", inject.method()[0]);
        assertEquals(1, inject.allow());
        assertEquals("TAIL", inject.at()[0].value());
    }

    @Test
    void animalDeclaresSetInLoveWithTheConfirmedSignature() throws NoSuchMethodException {
        final Method setInLove = Animal.class.getDeclaredMethod("setInLove", Player.class);
        assertEquals(void.class, setInLove.getReturnType());
    }
}
