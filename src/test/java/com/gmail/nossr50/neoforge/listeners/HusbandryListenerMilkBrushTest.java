package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Husbandry listener plan, Task C, milk + brush + D-H5 cooldown:
 * {@link HusbandryListener#onMilked}, {@link HusbandryListener#onBrushed},
 * {@link HusbandryListener#onBrushToolDamaged}, and the shared
 * {@code harvestCooldownElapsed} gate they both go through.
 */
class HusbandryListenerMilkBrushTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
    private static final int COOLDOWN_SECONDS = 300;
    private static final long COOLDOWN_TICKS = COOLDOWN_SECONDS * 20L;

    private ServerLevel level;

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @BeforeEach
    void setUp() {
        level = mock(ServerLevel.class);
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        HusbandryListener.endPlayerInteraction();
        HusbandryListener.clear();
        MetadataStore.clearAll();
    }

    private ServerPlayer trackedPlayer(HusbandryManager husbandry) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(PLAYER_ID);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        when(mmoPlayer.getHusbandryManager()).thenReturn(husbandry);
        UserManager.track(mmoPlayer);
        return player;
    }

    private void worldTime(long ticks) {
        lenient().when(level.getGameTime()).thenReturn(ticks);
    }

    private void allowHarvestCooldown(HusbandryManager husbandry) {
        lenient().when(husbandry.getHarvestCooldownSeconds()).thenReturn(COOLDOWN_SECONDS);
    }

    /** A distinct animal with a UUID, which the cooldown's side-table keys on. */
    private Entity harvestable(Class<? extends Entity> type) {
        final Entity animal = mock(type);
        lenient().when(animal.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.doReturn(level).when(animal).level();
        return animal;
    }

    // =============================================================================================
    // onMilked
    // =============================================================================================

    @Test
    void milkingACowPaysTheMilkVerb() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity cow = harvestable(Cow.class);
        worldTime(0L);

        HusbandryListener.onMilked(cow, player);

        verify(husbandry).onMilk();
    }

    @Test
    void milkingAMooshroomsStewBowlPaysTheSameMilkVerb() {
        // MushroomCowStewMixin calls this exact method too -- one verb, one cooldown, shared with
        // the bucket path CowGoatMilkMixin hooks, so a mooshroom cannot be milked and stewed for two
        // awards in the same breath.
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity mooshroom = harvestable(MushroomCow.class);
        worldTime(0L);

        HusbandryListener.onMilked(mooshroom, player);

        verify(husbandry).onMilk();
    }

    @Test
    void milkingTheSameCowInsideTheCooldownPaysOnlyOnce() {
        // D-H5: vanilla puts NO cooldown on milking, so the same cow can be milked as fast as a
        // player can click, forever, for free, without this gate.
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity cow = harvestable(Cow.class);

        worldTime(0L);
        HusbandryListener.onMilked(cow, player);
        worldTime(COOLDOWN_TICKS - 1);
        HusbandryListener.onMilked(cow, player);

        verify(husbandry, times(1)).onMilk();
    }

    @Test
    void milkingTheSameCowAfterTheCooldownPaysAgain() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity cow = harvestable(Cow.class);

        worldTime(0L);
        HusbandryListener.onMilked(cow, player);
        worldTime(COOLDOWN_TICKS);
        HusbandryListener.onMilked(cow, player);

        verify(husbandry, times(2)).onMilk();
    }

    @Test
    void theCooldownIsPerAnimalAndNotPerPlayer() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        worldTime(0L);

        HusbandryListener.onMilked(harvestable(Cow.class), player);
        HusbandryListener.onMilked(harvestable(Cow.class), player);

        verify(husbandry, times(2)).onMilk();
    }

    @Test
    void aZeroCooldownDisablesTheGateEntirely() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        lenient().when(husbandry.getHarvestCooldownSeconds()).thenReturn(0);
        final Entity cow = harvestable(Cow.class);
        worldTime(0L);

        HusbandryListener.onMilked(cow, player);
        HusbandryListener.onMilked(cow, player);

        verify(husbandry, times(2)).onMilk();
    }

    @Test
    void aClockThatMovedBackwardsCountsAsElapsedRatherThanLockingTheAnimalOut() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity cow = harvestable(Cow.class);

        worldTime(1_000_000L);
        HusbandryListener.onMilked(cow, player);
        worldTime(5L);
        HusbandryListener.onMilked(cow, player);

        verify(husbandry, times(2)).onMilk();
    }

    @Test
    void milkingByANonServerPlayerPaysNothing() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity cow = harvestable(Cow.class);
        worldTime(0L);

        HusbandryListener.onMilked(cow, mock(Player.class));
        verify(husbandry, never()).onMilk();
    }

    // =============================================================================================
    // Herdsman's Call — the cooldown-bypass half, shared by milk and brush
    // =============================================================================================

    @Test
    void herdsmansCallLetsAHarvestIgnoreItsCooldown() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity cow = harvestable(Cow.class);
        worldTime(0L);

        HusbandryListener.onMilked(cow, player);
        when(husbandry.isHerdsmansCallActive()).thenReturn(true);
        HusbandryListener.onMilked(cow, player);

        verify(husbandry, times(2)).onMilk();
    }

    @Test
    void aBypassedHarvestDoesNotResetTheOrdinaryCooldown() {
        // Otherwise blowing the horn over a herd would stamp every animal's clock, handing the
        // player a second full round the instant the ability ended.
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity cow = harvestable(Cow.class);

        worldTime(0L);
        HusbandryListener.onMilked(cow, player); // Normal award, stamps tick 0.

        when(husbandry.isHerdsmansCallActive()).thenReturn(true);
        worldTime(100L);
        HusbandryListener.onMilked(cow, player); // Bypassed; must NOT stamp tick 100.

        when(husbandry.isHerdsmansCallActive()).thenReturn(false);
        worldTime(COOLDOWN_TICKS - 1);
        HusbandryListener.onMilked(cow, player); // Still inside the ORIGINAL window.

        verify(husbandry, times(2)).onMilk();
    }

    // =============================================================================================
    // onBrushed
    // =============================================================================================

    @Test
    void brushingAnArmadilloPaysTheBrushVerbWhenAScuteIsActuallyDelivered() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity armadillo = harvestable(Armadillo.class);
        worldTime(0L);

        // `true` is what brushOffScute() returns when it really handed over a scute.
        HusbandryListener.onBrushed(armadillo, player, true);

        verify(husbandry).onBrush();
    }

    @Test
    void aBrushThatDeliversNothingPaysNothing() {
        // The reason this verb pays on the DROP where shearing pays on the attempt: brushOffScute
        // refuses only a baby and succeeds for any adult, so a real delivery is the only available
        // proof a harvest happened.
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity armadillo = harvestable(Armadillo.class);
        worldTime(0L);

        assertFalse(HusbandryListener.onBrushed(armadillo, player, false),
                "a brush that delivered nothing must not owe a bonus scute either");

        verify(husbandry, never()).onBrush();
        verify(husbandry, never()).getHarvestCooldownSeconds();
    }

    @Test
    void aDispenserOrNonPlayerBrusherPaysNothing() {
        final Entity armadillo = harvestable(Armadillo.class);
        assertFalse(HusbandryListener.onBrushed(armadillo, null, true));
    }

    @Test
    void aBrushInsideTheCooldownStillDropsButPaysNothing() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity armadillo = harvestable(Armadillo.class);
        worldTime(0L);

        HusbandryListener.onBrushed(armadillo, player, true);
        HusbandryListener.onBrushed(armadillo, player, true);

        verify(husbandry, times(1)).onBrush();
    }

    @Test
    void oneBrushResolvesBountifulHarvestExactlyOnce() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity armadillo = harvestable(Armadillo.class);
        worldTime(0L);
        when(husbandry.rollBonusHarvestDrop()).thenReturn(true);

        assertTrue(HusbandryListener.onBrushed(armadillo, player, true),
                "a winning roll must tell the caller a second scute is owed");

        verify(husbandry, times(1)).rollBonusHarvestDrop();
        verify(husbandry, times(1)).onBrush();
    }

    @Test
    void aBrushInsideTheCooldownRollsNoBonusEither() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        allowHarvestCooldown(husbandry);
        final Entity armadillo = harvestable(Armadillo.class);
        worldTime(0L);
        lenient().when(husbandry.rollBonusHarvestDrop()).thenReturn(true);

        HusbandryListener.onBrushed(armadillo, player, true);
        clearInvocations(husbandry);
        assertFalse(HusbandryListener.onBrushed(armadillo, player, true),
                "a brush inside the cooldown must owe no bonus scute");

        verify(husbandry, never()).rollBonusHarvestDrop();
    }

    // =============================================================================================
    // onBrushToolDamaged — the interaction-stash gate, shared shape with the raise verb's stash
    // =============================================================================================

    @Test
    void bountifulHarvestSparesTheBrushOnASuccessfulRoll() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        final Entity armadillo = harvestable(Armadillo.class);
        when(husbandry.rollToolDurabilitySave()).thenReturn(true);

        HusbandryListener.beginPlayerInteraction(player, armadillo);
        try {
            assertEquals(0, HusbandryListener.onBrushToolDamaged(armadillo, 16),
                    "a saved brush must cost the tool nothing");
        } finally {
            HusbandryListener.endPlayerInteraction();
        }
    }

    @Test
    void aDispenserOrOutOfWindowCallNeverSavesBrushDurability() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedPlayer(husbandry);
        final Entity armadillo = harvestable(Armadillo.class);
        lenient().when(husbandry.rollToolDurabilitySave()).thenReturn(true);

        // No beginPlayerInteraction: no window is open, exactly the dispenser shape.
        assertEquals(16, HusbandryListener.onBrushToolDamaged(armadillo, 16));
        verify(husbandry, never()).rollToolDurabilitySave();
    }
}
