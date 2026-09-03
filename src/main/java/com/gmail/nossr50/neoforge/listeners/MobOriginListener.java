package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.platform.MobOrigins;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Stamps {@code MobOrigin.SPAWNER} on mobs that come out of a monster spawner or a trial spawner —
 * one of the seams {@link MobOrigins}'s own class doc warns is a trap on Fabric (no single funnel
 * carries the spawn reason there). NeoForge already solves it: both {@code BaseSpawner#serverTick}
 * and {@code TrialSpawner#spawnMob} route through {@code EventHooks.finalizeMobSpawnSpawner}, which
 * fires {@link FinalizeSpawnEvent} carrying {@link FinalizeSpawnEvent#getSpawnType()} — confirmed
 * via {@code javap} against {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar} as real,
 * always-fired vanilla plumbing (not a mod-only utility), so no mixin is needed for this origin.
 *
 * <p>The other origins {@link MobOrigins}'s trap analysis warns about (egg/dispenser/portal,
 * breeding, conversion, {@code /summon}) still have no such event and are wired by mixins instead
 * — see {@code EntityTypeSpawnOriginMixin}, {@code AnimalBreedChildOriginMixin},
 * {@code MobConversionOriginMixin}, {@code SummonCommandOriginMixin}.
 */
public final class MobOriginListener {

    private MobOriginListener() {
    }

    /** Register the spawner/trial-spawner origin hook. Called once from {@code McMMOMod}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(MobOriginListener::onFinalizeSpawn);
    }

    static void onFinalizeSpawn(@NotNull FinalizeSpawnEvent event) {
        MobOrigins.stampOnSpawn(event.getEntity(), event.getSpawnType());
    }
}
