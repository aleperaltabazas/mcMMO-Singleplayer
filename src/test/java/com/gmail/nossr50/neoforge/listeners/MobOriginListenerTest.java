package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.mobs.MobOrigin;
import com.gmail.nossr50.neoforge.McMMOAttachments;
import java.util.UUID;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MobOriginListener#onFinalizeSpawn} is the SPAWNER/TRIAL_SPAWNER half of Hunter's D-HU1
 * gate — see {@link com.gmail.nossr50.platform.MobOrigins}'s class doc for why NeoForge needs no
 * mixin here, unlike the egg/dispenser/portal, breeding, conversion, and {@code /summon} paths.
 * This only proves the listener forwards {@link FinalizeSpawnEvent#getEntity()}/{@code
 * getSpawnType()} correctly into {@code MobOrigins.stampOnSpawn} — {@code MobOriginsTest} already
 * covers every classification/write-guard rule downstream of that call.
 */
class MobOriginListenerTest {

    @AfterEach
    void clearAttachments() {
        McMMOAttachments.clearAll();
    }

    private static FinalizeSpawnEvent spawnerEvent(Mob entity, MobSpawnType reason) {
        final FinalizeSpawnEvent event = mock(FinalizeSpawnEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getSpawnType()).thenReturn(reason);
        return event;
    }

    private static Mob zombie() {
        final Mob zombie = mock(Zombie.class);
        when(zombie.getUUID()).thenReturn(UUID.randomUUID());
        final Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(zombie.level()).thenReturn(level);
        return zombie;
    }

    @Test
    void spawnerReasonStampsTheSpawnerOrigin() {
        final Mob zombie = zombie();
        MobOriginListener.onFinalizeSpawn(spawnerEvent(zombie, MobSpawnType.SPAWNER));
        assertEquals(MobOrigin.SPAWNER.storageKey(), McMMOAttachments.getMobOrigin(zombie));
    }

    @Test
    void trialSpawnerReasonStampsTheSpawnerOrigin() {
        final Mob zombie = zombie();
        MobOriginListener.onFinalizeSpawn(spawnerEvent(zombie, MobSpawnType.TRIAL_SPAWNER));
        assertEquals(MobOrigin.SPAWNER.storageKey(), McMMOAttachments.getMobOrigin(zombie));
    }

    @Test
    void naturalReasonWritesNoMarker() {
        final Mob zombie = zombie();
        MobOriginListener.onFinalizeSpawn(spawnerEvent(zombie, MobSpawnType.NATURAL));
        assertNull(McMMOAttachments.getMobOrigin(zombie));
    }
}
