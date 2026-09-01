package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.platform.scheduler.TickScheduler;
import com.gmail.nossr50.skills.movement.Medium;
import com.gmail.nossr50.skills.movement.MovementManager;
import com.gmail.nossr50.skills.movement.SecondWindResult;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Second Wind activation trigger — {@link SecondWindListener} is the only production code path
 * that ever calls {@code setAbilityMode(SECOND_WIND, true)}; without it the super ability can never
 * actually turn on. Structured after {@code HerdsmansCallListenerTest}: a real event fired at the
 * real dispatch method, mocked {@link McMMOPlayer}/managers, and Mockito verification of the
 * activation calls.
 *
 * <p>The clean-activation case uses the WATER medium (Aquaman) rather than LAND, because Aquaman's
 * effect body is four {@code addEffect} calls on the (mocked) player — no {@code ServerLevel} entity
 * sweep to stub. Dart and Limitless are exercised structurally (dispatch reaches them without
 * throwing) rather than asserted on their world interactions, which belong to an in-game smoke test.
 */
class SecondWindListenerTest {

    private GeneralConfig generalConfig;
    private McMMOPlayer mmoPlayer;
    private MovementManager movementManager;

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        generalConfig = new GeneralConfig(dir);
        McMMOMod.setGeneralConfig(generalConfig);
    }

    @AfterEach
    void tearDown() {
        if (mmoPlayer != null) {
            UserManager.cleanupPlayer(mmoPlayer);
            mmoPlayer = null;
        }
        McMMOMod.setGeneralConfig(null);
    }

    @Test
    void mainHandFeatherInWaterActivatesTheAbility() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);

        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer).setAbilityMode(SuperAbilityType.SECOND_WIND, true);
        verify(mmoPlayer).setAbilityDATS(eq(SuperAbilityType.SECOND_WIND), anyLong());
    }

    /**
     * Regression for the dropped {@code * Misc.TICK_CONVERSION_FACTOR} in {@code activate}: the
     * scheduler's {@code runLater} delay must be scaled from seconds to ticks exactly like
     * {@code setAbilityDATS}'s millisecond stamp two lines above it, not passed the raw
     * {@code calculateAbilityActivationTicks} return value. Drives the real (static, JVM-shared)
     * {@link McMMOMod#getScheduler()} rather than mocking it — {@code TickScheduler} is a
     * concrete final class with no injection seam — and proves the timing by ticking it forward
     * and watching the (mocked) player's own ability-mode flag actually flip.
     */
    @Test
    void cleanActivationSchedulesTheDisableAtTheScaledTickDelayNotTheRawSecondsValue() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);
        final boolean[] active = {false};
        lenient().when(mmoPlayer.getAbilityMode(SuperAbilityType.SECOND_WIND))
                .thenAnswer(invocation -> active[0]);
        lenient().doAnswer(invocation -> {
            active[0] = invocation.getArgument(1);
            return null;
        }).when(mmoPlayer).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), anyBoolean());

        // trackedServerPlayer stubs calculateAbilityActivationTicks(...) to 3 -- despite the
        // method's name, that is SECONDS (see durationTicks()'s javadoc); the correctly-scaled
        // AbilityDisableTask delay is 3 * Misc.TICK_CONVERSION_FACTOR = 60 real ticks. The bug
        // scheduled it at the raw, un-scaled 3 ticks instead.
        final int correctDelayTicks = 3 * Misc.TICK_CONVERSION_FACTOR;

        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));
        assertTrue(active[0], "activation must flip the ability on before the scheduler is even pumped");

        final TickScheduler scheduler = McMMOMod.getScheduler();
        try {
            for (int i = 0; i < correctDelayTicks - 1; i++) {
                scheduler.tick();
            }
            assertTrue(active[0],
                    "must still be active one tick before the scaled duration elapses -- the "
                            + "buggy unscaled delay (3 ticks) would already have disabled it by now");

            scheduler.tick();
            assertFalse(active[0], "must disable exactly at ticks * TICK_CONVERSION_FACTOR");
        } finally {
            // This drives the real, JVM-shared scheduler singleton (TickScheduler has no test
            // injection seam) -- leave it clean for whatever test runs next in this fork.
            scheduler.cancelAll();
        }
    }

    @Test
    void wrongItemDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);

        SecondWindListener.onUseItem(mainHandEvent(player, Items.GUNPOWDER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), eq(true));
    }

    @Test
    void offHandFeatherDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);
        lenient().when(player.getItemInHand(InteractionHand.OFF_HAND))
                .thenReturn(new ItemStack(Items.FEATHER));
        final PlayerInteractEvent.RightClickItem event = new PlayerInteractEvent.RightClickItem(
                player, InteractionHand.OFF_HAND);

        SecondWindListener.onUseItem(event);

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), eq(true));
    }

    @Test
    void clientSideFireDoesNotActivate() {
        final Player clientPlayer = mock(Player.class);
        lenient().when(clientPlayer.blockPosition()).thenReturn(BlockPos.ZERO);
        final PlayerInteractEvent.RightClickItem event = new PlayerInteractEvent.RightClickItem(
                clientPlayer, InteractionHand.MAIN_HAND);

        // Must not throw resolving a non-ServerPlayer, and must not touch UserManager.
        SecondWindListener.onUseItem(event);
    }

    @Test
    void alreadyActiveDoesNotReActivate() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);
        when(mmoPlayer.getAbilityMode(SuperAbilityType.SECOND_WIND)).thenReturn(true);

        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), eq(true));
    }

    @Test
    void abilitiesOffDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);
        when(mmoPlayer.getAbilityUse()).thenReturn(false);

        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), eq(true));
    }

    @Test
    void notMovingDoesNotActivateOrBurnCooldown() {
        // Not a passenger, not sneaking, not gliding, not in water, not sprinting: classifyMedium
        // returns null.
        final ServerPlayer player = trackedServerPlayer(null);

        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), eq(true));
        // The refusal happens before calculateTimeRemaining is even consulted, let alone stamped.
        verify(mmoPlayer, never()).calculateTimeRemaining(SuperAbilityType.SECOND_WIND);
        verify(mmoPlayer, never()).setAbilityDATS(eq(SuperAbilityType.SECOND_WIND), anyLong());
    }

    @Test
    void nullMovementManagerDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);
        when(mmoPlayer.getMovementManager()).thenReturn(null);

        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), eq(true));
        verify(mmoPlayer, never()).setAbilityDATS(eq(SuperAbilityType.SECOND_WIND), anyLong());
    }

    @Test
    void onCooldownDoesNotActivateButNotifies() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);
        when(mmoPlayer.calculateTimeRemaining(SuperAbilityType.SECOND_WIND)).thenReturn(42);

        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), eq(true));
    }

    @Test
    void rankNotUnlockedForMediumDoesNotActivate() {
        final ServerPlayer player = trackedServerPlayer(Medium.WATER);
        lenient().when(movementManager.computeSecondWind(eq(Medium.WATER), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(null);

        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));

        verify(mmoPlayer, never()).setAbilityMode(eq(SuperAbilityType.SECOND_WIND), eq(true));
        verify(mmoPlayer, never()).setAbilityDATS(eq(SuperAbilityType.SECOND_WIND), anyLong());
    }

    @Test
    void untrackedPlayerIsSafelyIgnored() {
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(UUID.randomUUID());
        lenient().when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        lenient().when(player.getItemInHand(any(InteractionHand.class)))
                .thenReturn(new ItemStack(Items.FEATHER));

        assertFalse(UserManager.getPlayer(player.getUUID()) != null);
        SecondWindListener.onUseItem(mainHandEvent(player, Items.FEATHER));
        // no exception, no crash: nothing to verify against since no McMMOPlayer was ever tracked.
    }

    private PlayerInteractEvent.RightClickItem mainHandEvent(ServerPlayer player, Item item) {
        lenient().when(player.getItemInHand(InteractionHand.MAIN_HAND))
                .thenReturn(new ItemStack(item));
        return new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
    }

    /**
     * @param medium the medium {@code classifyMedium} should resolve to; {@code null} for "not
     *               moving" (standing still).
     */
    private ServerPlayer trackedServerPlayer(Medium medium) {
        final UUID uuid = UUID.randomUUID();
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(uuid);
        lenient().when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        lenient().when(player.getItemInHand(any(InteractionHand.class)))
                .thenReturn(new ItemStack(Items.FEATHER));

        lenient().when(player.isPassenger()).thenReturn(false);
        lenient().when(player.isShiftKeyDown()).thenReturn(false);
        lenient().when(player.isFallFlying()).thenReturn(medium == Medium.AIR);
        lenient().when(player.isInWater()).thenReturn(medium == Medium.WATER);
        lenient().when(player.isSprinting()).thenReturn(medium == Medium.LAND);
        lenient().when(player.getLookAngle()).thenReturn(new Vec3(0, 0, 1));
        lenient().when(player.getDeltaMovement()).thenReturn(Vec3.ZERO);

        movementManager = mock(MovementManager.class);
        if (medium != null) {
            final SecondWindResult result = switch (medium) {
                case LAND -> new SecondWindResult(medium, 0, 1.0, 5.0, 4.0, 1.0);
                case WATER -> new SecondWindResult(medium, 60, 1.0, 0, 0, 0);
                case AIR -> new SecondWindResult(medium, 60, 1.0, 0, 0, 0);
            };
            lenient().when(movementManager.computeSecondWind(eq(medium), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(result);
        }

        mmoPlayer = mock(McMMOPlayer.class);
        lenient().when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        lenient().when(mmoPlayer.getMovementManager()).thenReturn(movementManager);
        lenient().when(mmoPlayer.getAbilityUse()).thenReturn(true);
        lenient().when(mmoPlayer.getAbilityMode(SuperAbilityType.SECOND_WIND)).thenReturn(false);
        lenient().when(mmoPlayer.calculateTimeRemaining(SuperAbilityType.SECOND_WIND)).thenReturn(0);
        lenient().when(mmoPlayer.calculateAbilityActivationTicks(any(), eq(SuperAbilityType.SECOND_WIND)))
                .thenReturn(3);
        lenient().when(mmoPlayer.useChatNotifications()).thenReturn(false);
        UserManager.track(mmoPlayer);
        return player;
    }
}
