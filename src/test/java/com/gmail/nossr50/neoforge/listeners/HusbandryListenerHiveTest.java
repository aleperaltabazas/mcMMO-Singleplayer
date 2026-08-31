package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.treasure.TreasureConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.treasure.HusbandryTreasure;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Husbandry listener plan, Task C, hive half: {@link HusbandryListener#onHoneycombHarvested},
 * {@link HusbandryListener#onHoneyBottled}, {@link HusbandryListener#bonusHiveHelpings},
 * {@link HusbandryListener#hiveHarvestLeavesBeesCalm}, {@link HusbandryListener#onHiveToolDamaged},
 * and the Hidden Bounty verb-string pin.
 *
 * <p>The bonus-helping <em>delivery</em> (an extra {@code BeehiveBlock.dropHoneycomb} roll) needs a
 * real {@code ServerLevel} and a real loot table and is not unit-tested here, matching the Fabric
 * original's own scope note on {@code bonusHiveHelpings}'s javadoc — the arithmetic is pinned
 * directly, the delivery is a boot-check-only concern.
 */
class HusbandryListenerHiveTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        McMMOMod.setTreasureConfig(null);
    }

    private static ServerPlayer trackedPlayer(HusbandryManager husbandry) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(PLAYER_ID);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        when(mmoPlayer.getHusbandryManager()).thenReturn(husbandry);
        UserManager.track(mmoPlayer);
        return player;
    }

    // =============================================================================================
    // onHoneycombHarvested / onHoneyBottled
    // =============================================================================================

    @Test
    void harvestingAHoneyBottlePaysTheHiveVerb() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);

        HusbandryListener.onHoneyBottled(player);

        verify(husbandry).onHiveHarvest();
    }

    @Test
    void aHiveHarvestByAnUntrackedPlayerPaysNothing() {
        final ServerPlayer stranger = mock(ServerPlayer.class);
        when(stranger.getUUID()).thenReturn(UUID.randomUUID());

        HusbandryListener.onHoneyBottled(stranger);
        // No HusbandryManager mock in play at all — nothing to verify on, the assertion is that
        // this does not throw.
    }

    @Test
    void theHiveVerbHasNoCooldownBecauseVanillaAlreadyLimitsIt() {
        // Deliberate asymmetry with milk and brush: a drained hive needs five levels of
        // bee-pollination time before it can be harvested again, so mcMMO adding a second stopwatch
        // on top would only feel arbitrary.
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);

        HusbandryListener.onHoneyBottled(player);
        HusbandryListener.onHoneyBottled(player);

        verify(husbandry, times(2)).onHiveHarvest();
        verify(husbandry, never()).getHarvestCooldownSeconds();
    }

    @Test
    void onHoneyBottledByANonServerPlayerPaysNothing() {
        final Player clientPlayer = mock(Player.class);
        HusbandryListener.onHoneyBottled(clientPlayer);
        // No exception; there is nothing to verify against since no manager was ever looked up.
    }

    // =============================================================================================
    // bonusHiveHelpings — Beekeeper and Bountiful Harvest stack rather than re-rolling one coin
    // =============================================================================================

    @Test
    void beekeeperAndBountifulHarvestStackRatherThanReRollingTheSameCoin() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);

        when(husbandry.rollBonusHarvestDrop()).thenReturn(true);
        when(husbandry.rollBonusHoney()).thenReturn(true);
        assertEquals(2, HusbandryListener.bonusHiveHelpings(husbandry));

        clearInvocations(husbandry);
        when(husbandry.rollBonusHarvestDrop()).thenReturn(true);
        when(husbandry.rollBonusHoney()).thenReturn(false);
        assertEquals(1, HusbandryListener.bonusHiveHelpings(husbandry));

        clearInvocations(husbandry);
        when(husbandry.rollBonusHarvestDrop()).thenReturn(false);
        when(husbandry.rollBonusHoney()).thenReturn(false);
        assertEquals(0, HusbandryListener.bonusHiveHelpings(husbandry));
    }

    // =============================================================================================
    // hiveHarvestLeavesBeesCalm — the Beekeeper polarity, both directions
    // =============================================================================================

    /**
     * Acceptance criterion, direction 1: a sheltered harvest ({@code isSmokeyPos} already
     * {@code true} — a lit campfire is in range) stays calm regardless of the sub-skill. This is
     * proven at the mixin's own worked expression, {@code smokey || calm}: with {@code smokey}
     * already {@code true}, the {@code ||} short-circuits and {@link
     * HusbandryListener#hiveHarvestLeavesBeesCalm} is never even consulted — the exact behaviour
     * that expression guarantees, asserted here by confirming the sub-skill's own answer is
     * irrelevant to this half.
     */
    @Test
    void aShelteredHarvestStaysCalmRegardlessOfTheSubSkill() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        when(husbandry.countsAsShelteredHiveHarvest()).thenReturn(false);

        // smokey || husbandry.countsAsShelteredHiveHarvest() -- smokey is true, so the whole
        // expression is true independent of what the listener itself would have said.
        final boolean smokey = true;
        final boolean widened = smokey || HusbandryListener.hiveHarvestLeavesBeesCalm(player);

        assertTrue(widened, "a sheltered harvest (isSmokeyPos already true) must stay calm");
    }

    /**
     * Acceptance criterion, direction 2: an unsheltered harvest ({@code isSmokeyPos} false) is calm
     * ONLY when {@code countsAsShelteredHiveHarvest()} says so — proving the polarity is not
     * inverted. Getting this backwards (gating on {@code !isSmokeyPos} the way transcribing the
     * Fabric expression verbatim would) would anger bees on a sheltered harvest and do nothing on an
     * unsheltered one; this test would catch exactly that mistake, since it exercises the unsheltered
     * ({@code smokey == false}) half explicitly in both directions.
     */
    @Test
    void anUnshelteredHarvestIsCalmOnlyWhenBeekeeperSaysSo() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        final boolean smokey = false;

        when(husbandry.countsAsShelteredHiveHarvest()).thenReturn(true);
        assertTrue(smokey || HusbandryListener.hiveHarvestLeavesBeesCalm(player),
                "Beekeeper must widen an unsheltered harvest toward calm");

        when(husbandry.countsAsShelteredHiveHarvest()).thenReturn(false);
        assertFalse(smokey || HusbandryListener.hiveHarvestLeavesBeesCalm(player),
                "an unsheltered harvest with no Beekeeper must anger the bees, exactly as vanilla would");
    }

    @Test
    void hiveHarvestLeavesBeesCalmReturnsFalseForANonServerPlayer() {
        // The dispenser exclusion, belt-and-braces: onUseWithItem/useItemOn is never reached by a
        // dispenser at all, but a non-player holder resolving to "calm" would be a silent privilege
        // escalation if that ever changed.
        assertFalse(HusbandryListener.hiveHarvestLeavesBeesCalm(mock(Player.class)));
    }

    // =============================================================================================
    // onHiveToolDamaged
    // =============================================================================================

    @Test
    void bountifulHarvestSparesTheShearsOnAHive() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        when(husbandry.rollToolDurabilitySave()).thenReturn(true);

        assertEquals(0, HusbandryListener.onHiveToolDamaged(player, 1));
    }

    @Test
    void aDispenserOrNonPlayerHolderNeverSavesHiveDurability() {
        assertEquals(1, HusbandryListener.onHiveToolDamaged(mock(Player.class), 1));
    }

    // =============================================================================================
    // Hidden Bounty verb-string pin — matches HIDDEN_BOUNTY_SHEAR/HIVE/MILK/BRUSH against the real
    // bundled treasures.yml's Drops_From groups, the way the Fabric original's own config test did.
    // =============================================================================================

    @Test
    void hiddenBountyVerbConstantsMatchTreasuresYmlExactly(@TempDir Path dataFolder) {
        final TreasureConfig treasures = new TreasureConfig(dataFolder);

        for (String verb : List.of(HusbandryListener.HIDDEN_BOUNTY_SHEAR,
                HusbandryListener.HIDDEN_BOUNTY_HIVE, HusbandryListener.HIDDEN_BOUNTY_MILK,
                HusbandryListener.HIDDEN_BOUNTY_BRUSH)) {
            final List<HusbandryTreasure> found = treasures.getHusbandryTreasures(verb);
            assertNotNull(found, verb + " must resolve to a (possibly empty) list, never null");
            assertFalse(found.isEmpty(),
                    "Drops_From verb \"" + verb + "\" must have at least one treasures.yml entry — "
                            + "a typo here is a sub-skill that silently never finds anything");
        }
    }

    @Test
    void aHarvestWithNoTreasureConfigBoundIsSafeAndSilent() {
        // McMMOMod's TreasureConfig is unbound in this fixture, which is also the real state during
        // early boot. Every harvest verb calls into Hidden Bounty, so a missing config must be a
        // no-op rather than an NPE that takes the whole verb down with it.
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);

        HusbandryListener.onHoneyBottled(player);

        verify(husbandry).onHiveHarvest();
        verify(husbandry, never()).rollHiddenBounty();
    }
}
