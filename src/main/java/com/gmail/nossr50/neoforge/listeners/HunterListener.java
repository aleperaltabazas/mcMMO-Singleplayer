package com.gmail.nossr50.neoforge.listeners;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

/**
 * <b>PORT (NeoForge, Phase 2 Task D):</b> only {@link #masteryKeyOf} is ported here, per this task's
 * explicitly scoped dependency resolution
 * (docs/superpowers/sdd/2026-08-27-entity-damage-listener-plan/task-D-brief.md, Step 1). The Fabric
 * original's {@code fabric.listeners.HunterListener} is a much larger file — the kill-counter
 * listener ({@code AFTER_DEATH}), the four kill-qualification gates, and Trophy Hunter's bonus-loot
 * hook — none of which this task ports. That is a separate file with its own scope, out of this
 * plan; it can reuse this class (or replace it) when a dedicated Hunter task lands.
 */
final class HunterListener {

    private HunterListener() {
    }

    /**
     * The key one creature's mastery is filed under: its <b>full</b> registry id, namespace included
     * ({@code minecraft:zombie}).
     *
     * <p>Ported verbatim from the Fabric original's {@code HunterListener#masteryKeyOf} — see that
     * method's own javadoc for the full "one function on purpose" rationale: this key is also the one
     * the (not-yet-ported) kill-counter listener will file mastery kills under, so both sides must
     * call the same function rather than re-deriving the id, on pain of the counters and the damage
     * bonus silently disagreeing about what a species is.
     */
    static @NotNull String masteryKeyOf(@NotNull LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
