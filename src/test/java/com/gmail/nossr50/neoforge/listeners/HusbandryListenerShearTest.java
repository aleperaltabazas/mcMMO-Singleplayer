package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Bogged;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Husbandry listener plan, Task C, shear half: {@link HusbandryListener#beginShear},
 * {@link HusbandryListener#endShear}, {@link HusbandryListener#onShearDropStack} and
 * {@link HusbandryListener#onShearToolDamaged}.
 *
 * <h2>Why no species appears in {@link HusbandryListener}'s shear methods, and why this test proves it</h2>
 * {@code ShearsItemInteractMixin} hooks {@code ShearsItem#interactLivingEntity} exactly once, with
 * no species enumeration anywhere — the entire point of the redesign documented on that mixin's own
 * javadoc (design spec §5). {@link HusbandryListener#beginShear} takes a plain {@link LivingEntity}
 * and never inspects its concrete type, so calling it with a {@link Bogged} (a shearable species
 * that is neither a sheep nor anything the Fabric port's old species-enumerated mixins would have
 * listed) exercises the same code path a real sheep shear would, with nothing species-specific to
 * stand up. That is the acceptance criterion this class's {@code beginShearPaysTheVerbForANonSheepSpecies}
 * test is built to demonstrate.
 */
class HusbandryListenerShearTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        HusbandryListener.endShear();
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
    // beginShear / endShear
    // =============================================================================================

    @Test
    void beginShearPaysTheVerbAndDecidesBountifulHarvestOnce() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        final LivingEntity sheared = mock(net.minecraft.world.entity.animal.Sheep.class);
        when(husbandry.rollBonusHarvestDrop()).thenReturn(true);

        HusbandryListener.beginShear(sheared, player);

        verify(husbandry, times(1)).onShear();
        verify(husbandry, times(1)).rollBonusHarvestDrop();
        // The window opened: a subsequent drop is doubled.
        final ItemStack wool = new ItemStack(Items.WHITE_WOOL, 1);
        final ItemStack doubled = HusbandryListener.onShearDropStack(wool);
        assertEquals(2, doubled.getCount());
    }

    /**
     * Acceptance criterion: a non-sheep shearable species pays exactly the same as a sheep would,
     * because {@link HusbandryListener#beginShear} has no species branch to diverge on.
     */
    @Test
    void beginShearPaysTheVerbForANonSheepSpecies() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        final Bogged bogged = mock(Bogged.class);

        HusbandryListener.beginShear(bogged, player);

        verify(husbandry, times(1)).onShear();
    }

    @Test
    void beginShearDoesNothingForAClientMirrorCallWithNoRealPlayer() {
        final LivingEntity sheared = mock(net.minecraft.world.entity.animal.Sheep.class);
        final Player clientPlayer = mock(Player.class); // not a ServerPlayer

        HusbandryListener.beginShear(sheared, clientPlayer);

        // No exception, and the bonus window stays closed.
        final ItemStack wool = new ItemStack(Items.WHITE_WOOL, 1);
        assertSame(wool, HusbandryListener.onShearDropStack(wool));
    }

    @Test
    void beginShearPaysNothingWhenPlayerDataIsNotLoaded() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        final LivingEntity sheared = mock(net.minecraft.world.entity.animal.Sheep.class);

        HusbandryListener.beginShear(sheared, player);

        final ItemStack wool = new ItemStack(Items.WHITE_WOOL, 1);
        assertSame(wool, HusbandryListener.onShearDropStack(wool));
    }

    // =============================================================================================
    // onShearDropStack — Bountiful Harvest's doubling
    // =============================================================================================

    @Test
    void onShearDropStackDoublesWhileTheWindowIsOpenAndWon() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        when(husbandry.rollBonusHarvestDrop()).thenReturn(true);
        HusbandryListener.beginShear(mock(net.minecraft.world.entity.animal.Sheep.class), player);

        final ItemStack stack = new ItemStack(Items.WHITE_WOOL, 3);
        final ItemStack doubled = HusbandryListener.onShearDropStack(stack);

        assertEquals(6, doubled.getCount());
    }

    @Test
    void onShearDropStackLeavesTheStackAloneOnALosingRoll() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        when(husbandry.rollBonusHarvestDrop()).thenReturn(false);
        HusbandryListener.beginShear(mock(net.minecraft.world.entity.animal.Sheep.class), player);

        final ItemStack stack = new ItemStack(Items.WHITE_WOOL, 3);
        assertSame(stack, HusbandryListener.onShearDropStack(stack));
    }

    @Test
    void onShearDropStackLeavesTheStackAloneOnceTheWindowIsClosed() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        when(husbandry.rollBonusHarvestDrop()).thenReturn(true);
        HusbandryListener.beginShear(mock(net.minecraft.world.entity.animal.Sheep.class), player);
        HusbandryListener.endShear();

        final ItemStack stack = new ItemStack(Items.WHITE_WOOL, 3);
        assertSame(stack, HusbandryListener.onShearDropStack(stack));
    }

    @Test
    void onShearDropStackIgnoresAnEmptyStack() {
        final ItemStack empty = ItemStack.EMPTY;
        assertSame(empty, HusbandryListener.onShearDropStack(empty));
    }

    // =============================================================================================
    // onShearToolDamaged
    // =============================================================================================

    @Test
    void onShearToolDamagedSparesDurabilityOnAWinningRoll() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        when(husbandry.rollToolDurabilitySave()).thenReturn(true);

        assertEquals(0, HusbandryListener.onShearToolDamaged(player, 1));
    }

    @Test
    void onShearToolDamagedTakesFullDamageOnALosingRoll() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);
        when(husbandry.rollToolDurabilitySave()).thenReturn(false);

        assertEquals(1, HusbandryListener.onShearToolDamaged(player, 1));
    }

    @Test
    void onShearToolDamagedIgnoresANonPlayer() {
        final Player clientPlayer = mock(Player.class);
        assertEquals(1, HusbandryListener.onShearToolDamaged(clientPlayer, 1));
    }

    @Test
    void onShearToolDamagedIgnoresAZeroOrNegativeAmount() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        final ServerPlayer player = trackedPlayer(husbandry);

        assertEquals(0, HusbandryListener.onShearToolDamaged(player, 0));
        verify(husbandry, never()).rollToolDurabilitySave();
    }
}
