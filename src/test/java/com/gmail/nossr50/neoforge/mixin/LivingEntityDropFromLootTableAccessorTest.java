package com.gmail.nossr50.neoforge.mixin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.gmail.nossr50.util.McTestRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LivingEntityDropFromLootTableAccessorTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void theAccessorInvokesTheProtectedMethodWithoutThrowing() {
        // A mocked LivingEntity cannot be cast to the mixin interface -- @Invoker mixins are woven
        // into real bytecode, and Mockito's proxy class was never processed by Mixin. This test
        // exists to catch a misconfigured mixin (wrong method name/descriptor -> InjectionError at
        // mixin apply time, or a ClassCastException here) that no purely-mocked test could ever see.
        final LivingEntity zombie = McTestRegistries.newHeadlessEntity(EntityType.ZOMBIE);
        assertNotNull(zombie, "Entity creation must succeed");

        final DamageSource source = Mockito.mock(DamageSource.class);

        // This call verifies that the mixin was applied: the LivingEntity bytecode now
        // implements the LivingEntityDropFromLootTableAccessor interface, and the static method
        // can invoke the woven invoker without throwing an InjectionError or ClassCastException.
        assertDoesNotThrow(() ->
                LivingEntityDropFromLootTableAccessor.invokeDropFromLootTable(zombie, source, false));
    }
}
