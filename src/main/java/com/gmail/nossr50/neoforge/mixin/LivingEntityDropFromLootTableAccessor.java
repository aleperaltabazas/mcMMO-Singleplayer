package com.gmail.nossr50.neoforge.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker access to {@code LivingEntity#dropFromLootTable(DamageSource, boolean)}, so Hunter's
 * Trophy Hunter subskill can re-roll a creature's own loot table a second time.
 *
 * <p>{@code dropFromLootTable} is {@code protected}, so an {@code @Invoker} is the only way to call
 * it from outside {@link LivingEntity}'s own class hierarchy — the same reasoning as
 * {@link HoeTillingActionsAccessor}. Calling this directly (rather than the outer
 * {@code dropAllDeathLoot}, which is what actually posts NeoForge's {@code LivingDropsEvent}) is
 * what lets the bonus roll run with no re-entrancy risk: {@code dropFromLootTable} does not itself
 * post any event, so a second call from inside a {@code LivingDropsEvent} listener cannot recurse.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityDropFromLootTableAccessor {

    @Invoker("dropFromLootTable")
    void mcmmo$invokeDropFromLootTable(DamageSource source, boolean causedByPlayer);

    /**
     * Casts {@code self} to this interface and invokes the accessor — the call shape every caller
     * outside this file should use, so nobody has to remember the {@code (Object)} cast mixins need.
     */
    static void invokeDropFromLootTable(LivingEntity self, DamageSource source,
            boolean causedByPlayer) {
        ((LivingEntityDropFromLootTableAccessor) self).mcmmo$invokeDropFromLootTable(source,
                causedByPlayer);
    }
}
