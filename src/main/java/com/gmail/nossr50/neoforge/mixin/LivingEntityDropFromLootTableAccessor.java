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
 *
 * <p>Also exposes {@code LivingEntity#shouldDropLoot()} (also {@code protected}, also {@code false}
 * for a baby mob) so a Trophy Hunter reroll can be gated the same way vanilla's own
 * {@code dropAllDeathLoot} gates its first call to {@code dropFromLootTable} — see
 * {@link com.gmail.nossr50.neoforge.listeners.HunterListener#onLivingDrops} for the caller.
 *
 * <p><b>Pure accessor interface — no static/default members here.</b> Sponge Mixin infers a
 * mixin's target-type requirement (class vs. interface) from whether the mixin itself carries any
 * concretely-implemented member. A {@code static} helper on this interface (even one with nothing
 * to do with the {@code @Invoker}s) made Mixin classify this as an interface-to-interface mixin and
 * reject {@link LivingEntity} — a concrete class — as an invalid target at boot
 * ({@code InvalidMixinException: @Mixin target type mismatch}), a real crash caught only once
 * someone actually launched the game rather than ran the unit tests (which never apply mixins at
 * all — see this class's own test). The call-shape helpers that used to live here moved to
 * {@link LivingEntityDropFromLootTableAccessorCalls}, a plain (non-mixin) utility class.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityDropFromLootTableAccessor {

    @Invoker("dropFromLootTable")
    void mcmmo$invokeDropFromLootTable(DamageSource source, boolean causedByPlayer);

    @Invoker("shouldDropLoot")
    boolean mcmmo$invokeShouldDropLoot();
}
