package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.movement.Medium;
import com.gmail.nossr50.skills.movement.MovementManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Agility listener plan, Task A: {@link AgilityListener#scaleExhaustion} (Athlete) and
 * {@link AgilityListener#modifyGlideVelocity} (Fleet Footed air / Glide).
 */
class AgilityListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a9");

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @AfterEach
    void tearDown() throws Exception {
        UserManager.remove(PLAYER_ID);
        setServer(null);
    }

    // =============================================================================================
    // scaleExhaustion -- Athlete
    // =============================================================================================

    @Test
    void nonPositiveExhaustionIsReturnedUnchangedWithoutResolvingAnOwner() {
        final FoodData foodData = mock(FoodData.class);
        // No server installed at all -- if the fast path didn't short-circuit first, this would
        // NPE inside ownerOf rather than return cleanly.
        assertEquals(0f, AgilityListener.scaleExhaustion(foodData, 0f));
        assertEquals(-1f, AgilityListener.scaleExhaustion(foodData, -1f));
    }

    @Test
    void exhaustionForAFoodDataThatMatchesNoOnlinePlayerIsUnchanged() throws Exception {
        final FoodData foodData = mock(FoodData.class);
        installServer(List.of());
        assertEquals(0.4f, AgilityListener.scaleExhaustion(foodData, 0.4f));
    }

    @Test
    void exhaustionWhileNotSprintingIsUnchanged() throws Exception {
        final MovementManager movement = mock(MovementManager.class);
        final ServerPlayer player = trackedPlayer(movement);
        when(player.isSprinting()).thenReturn(false);
        installServer(List.of(player));

        assertEquals(0.4f, AgilityListener.scaleExhaustion(player.getFoodData(), 0.4f));
    }

    @Test
    void exhaustionWhileSprintingIsScaledByTheAthleteMultiplier() throws Exception {
        final MovementManager movement = mock(MovementManager.class);
        when(movement.getAthleteExhaustionMultiplier()).thenReturn(0.5);
        final ServerPlayer player = trackedPlayer(movement);
        when(player.isSprinting()).thenReturn(true);
        installServer(List.of(player));

        assertEquals(0.2f, AgilityListener.scaleExhaustion(player.getFoodData(), 0.4f), 1e-6f);
    }

    @Test
    void aSprintingPlayerWithNoMmoDataIsUnchanged() throws Exception {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(PLAYER_ID);
        final FoodData foodData = mock(FoodData.class);
        when(player.getFoodData()).thenReturn(foodData);
        when(player.isSprinting()).thenReturn(true);
        installServer(List.of(player)); // Deliberately never UserManager.track()'d.

        assertEquals(0.4f, AgilityListener.scaleExhaustion(foodData, 0.4f));
    }

    // =============================================================================================
    // modifyGlideVelocity -- Fleet Footed (air) + Glide
    // =============================================================================================

    @Test
    void nonPlayerEntitiesAreReturnedUnchanged() {
        final LivingEntity mob = mock(Cow.class);
        final Vec3 velocity = new Vec3(1, -1, 1);
        assertSame(velocity, AgilityListener.modifyGlideVelocity(mob, velocity));
    }

    @Test
    void aPlayerWithNoMmoDataIsReturnedUnchanged() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(PLAYER_ID); // Deliberately never tracked.
        final Vec3 velocity = new Vec3(1, -1, 1);
        assertSame(velocity, AgilityListener.modifyGlideVelocity(player, velocity));
    }

    @Test
    void nothingUnlockedReturnsTheSameReferenceRatherThanAllocating() {
        final MovementManager movement = mock(MovementManager.class);
        when(movement.getFleetFootedBonus(Medium.AIR)).thenReturn(0.0);
        when(movement.getGlideDescentReduction()).thenReturn(0.0);
        final ServerPlayer player = trackedPlayer(movement);
        final Vec3 velocity = new Vec3(1, -1, 1);

        assertSame(velocity, AgilityListener.modifyGlideVelocity(player, velocity));
    }

    @Test
    void fleetFootedScalesBothHorizontalComponents() {
        final MovementManager movement = mock(MovementManager.class);
        when(movement.getFleetFootedBonus(Medium.AIR)).thenReturn(0.5);
        when(movement.getGlideDescentReduction()).thenReturn(0.0);
        final ServerPlayer player = trackedPlayer(movement);

        final Vec3 result = AgilityListener.modifyGlideVelocity(player, new Vec3(2, -1, 4));

        assertEquals(3.0, result.x, 1e-9);
        assertEquals(6.0, result.z, 1e-9);
    }

    @Test
    void glideDampensOnlyDownwardMotion() {
        final MovementManager movement = mock(MovementManager.class);
        when(movement.getFleetFootedBonus(Medium.AIR)).thenReturn(0.0);
        when(movement.getGlideDescentReduction()).thenReturn(0.5);
        final ServerPlayer player = trackedPlayer(movement);

        final Vec3 descending = AgilityListener.modifyGlideVelocity(player, new Vec3(0, -2, 0));
        assertEquals(-1.0, descending.y, 1e-9, "downward motion must be dampened");

        final Vec3 ascending = AgilityListener.modifyGlideVelocity(player, new Vec3(0, 2, 0));
        assertEquals(2.0, ascending.y, 1e-9,
                "upward motion must never be boosted by the descent-reduction bonus");
    }

    // =============================================================================================
    // helpers
    // =============================================================================================

    private ServerPlayer trackedPlayer(MovementManager movement) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(PLAYER_ID);
        final FoodData foodData = mock(FoodData.class);
        when(player.getFoodData()).thenReturn(foodData);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        when(mmoPlayer.getMovementManager()).thenReturn(movement);
        UserManager.track(mmoPlayer);
        return player;
    }

    private void installServer(List<ServerPlayer> players) throws Exception {
        final MinecraftServer server = mock(MinecraftServer.class);
        final PlayerList playerList = mock(PlayerList.class);
        when(playerList.getPlayers()).thenReturn(players);
        when(server.getPlayerList()).thenReturn(playerList);
        setServer(server);
    }

    /**
     * {@link McMMOMod#getServer()} has no test-facing setter -- every other listener test on this
     * branch resolves its {@code ServerPlayer} straight from event/method parameters, so none of
     * them needed one. {@link AgilityListener#scaleExhaustion} is the first hook that must resolve
     * its owner by identity scan (matching Fabric's {@code AthleteListener}, since {@link FoodData}
     * carries no back-reference), which is what makes this reflection necessary here.
     */
    private static void setServer(MinecraftServer server) throws Exception {
        final Field field = McMMOMod.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }
}
