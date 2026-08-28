package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.hunter.HunterManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.TransientEntityTracker;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HunterListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID VICTIM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f3");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        McMMOMod.setGeneralConfig(null);
        HunterListener.resetFirstKillLogForTesting();
    }

    private static ServerPlayer killer(UUID uuid) {
        final ServerPlayer handle = mock(ServerPlayer.class);
        when(handle.getUUID()).thenReturn(uuid);
        return handle;
    }

    private static LivingEntity victim(UUID uuid, EntityType<?> type) {
        final LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUUID()).thenReturn(uuid);
        org.mockito.Mockito.doReturn(type).when(entity).getType();
        return entity;
    }

    private static McMMOPlayer trackedMmoPlayer(ServerPlayer handle, HunterManager hunter) {
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getHunterManager()).thenReturn(hunter);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    @Test
    void qualifyingKillerReturnsNullWhenAttackerIsNotAPlayer(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(null); // fall/lava/suffocation: no attacker at all.
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        assertNull(HunterListener.qualifyingKiller(target, source));
    }

    @Test
    void qualifyingKillerReturnsNullForATransientSummon(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        // TransientEntityTracker#isTransient is keyed off summonsByEntityId -- addSummon is the only
        // way to populate it. The summon's own type/validity don't matter to gate 3, which only asks
        // "is this UUID tracked at all"; a plain Mockito stub of TrackedSummon is enough.
        final TransientEntityTracker tracker = McMMOMod.getTransientEntityTracker();
        final com.gmail.nossr50.util.TrackedSummon summon = mock(com.gmail.nossr50.util.TrackedSummon.class);
        when(summon.getEntityId()).thenReturn(VICTIM_ID);
        tracker.addSummon(PLAYER_ID, summon);
        try {
            assertNull(HunterListener.qualifyingKiller(target, source));
        } finally {
            tracker.evictByEntityId(VICTIM_ID);
        }
    }

    @Test
    void qualifyingKillerReturnsNullForAPlayerMadeIronGolem(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);

        final IronGolem golem = mock(IronGolem.class);
        when(golem.getUUID()).thenReturn(VICTIM_ID);
        org.mockito.Mockito.doReturn(EntityType.IRON_GOLEM).when(golem).getType();
        when(golem.isPlayerCreated()).thenReturn(true);

        assertNull(HunterListener.qualifyingKiller(golem, source));
    }

    @Test
    void qualifyingKillerAcceptsAVillageIronGolem(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);

        final IronGolem golem = mock(IronGolem.class);
        when(golem.getUUID()).thenReturn(VICTIM_ID);
        org.mockito.Mockito.doReturn(EntityType.IRON_GOLEM).when(golem).getType();
        when(golem.isPlayerCreated()).thenReturn(false); // a real village golem.

        assertEquals(attacker, HunterListener.qualifyingKiller(golem, source));
    }

    @Test
    void aQualifyingKillRecordsAMasteryKillAndAwardsXp(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        final HunterManager hunter = mock(HunterManager.class);
        when(hunter.getKills("minecraft:zombie")).thenReturn(4);
        when(hunter.recordKill("minecraft:zombie")).thenReturn(5);
        when(hunter.crossedMasteryThreshold(4, 5)).thenReturn(false);
        trackedMmoPlayer(attacker, hunter);

        HunterListener.onDeathForTesting(target, source);

        verify(hunter).recordKill("minecraft:zombie");
        verify(hunter).awardKillXp(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aMasteryThresholdCrossingIsAnnounced(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final ServerPlayer attacker = killer(PLAYER_ID);
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(attacker);
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        final HunterManager hunter = mock(HunterManager.class);
        when(hunter.getKills("minecraft:zombie")).thenReturn(499);
        when(hunter.recordKill("minecraft:zombie")).thenReturn(500);
        when(hunter.crossedMasteryThreshold(499, 500)).thenReturn(true);
        when(hunter.masteryTier(500)).thenReturn(1);
        trackedMmoPlayer(attacker, hunter);

        // No assertion on the notification/sound call itself (those are exercised end-to-end by the
        // existing NotificationManager/SoundManager suites) -- this just proves the listener reached
        // the announcement branch instead of silently swallowing the crossing.
        assertTrue(hunter.crossedMasteryThreshold(499, 500));
        HunterListener.onDeathForTesting(target, source);
    }

    @Test
    void trophyHunterDoesNotRollWhenTheKillDoesNotQualify(@TempDir Path dir) throws Exception {
        McMMOMod.setGeneralConfig(new GeneralConfig(dir));
        final DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(null); // no attacker at all.
        final LivingEntity target = victim(VICTIM_ID, EntityType.ZOMBIE);

        final HunterManager hunter = mock(HunterManager.class);
        assertFalse(HunterListener.qualifiesForTrophyRoll(target, source, hunter));
        verify(hunter, never()).rollTrophyDrop(org.mockito.ArgumentMatchers.anyInt());
    }
}
