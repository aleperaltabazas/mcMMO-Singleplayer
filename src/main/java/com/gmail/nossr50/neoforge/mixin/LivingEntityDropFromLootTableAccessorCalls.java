package com.gmail.nossr50.neoforge.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

/**
 * The call shape every caller of {@link LivingEntityDropFromLootTableAccessor} outside this
 * package should use, so nobody has to remember the {@code (Object)} cast mixins need.
 *
 * <p>Deliberately a plain class, not a member of the mixin interface itself — see that interface's
 * own javadoc for why a {@code static} method there broke Mixin's target-type inference at boot.
 */
public final class LivingEntityDropFromLootTableAccessorCalls {

    private LivingEntityDropFromLootTableAccessorCalls() {
    }

    /** Casts {@code self} to the accessor interface and invokes {@code dropFromLootTable}. */
    public static void invokeDropFromLootTable(@NotNull LivingEntity self,
            @NotNull DamageSource source, boolean causedByPlayer) {
        ((LivingEntityDropFromLootTableAccessor) self).mcmmo$invokeDropFromLootTable(source,
                causedByPlayer);
    }

    /** Casts {@code self} to the accessor interface and invokes {@code shouldDropLoot}. */
    public static boolean shouldDropLoot(@NotNull LivingEntity self) {
        return ((LivingEntityDropFromLootTableAccessor) self).mcmmo$invokeShouldDropLoot();
    }
}
