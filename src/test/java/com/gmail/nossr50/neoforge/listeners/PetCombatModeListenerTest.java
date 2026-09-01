package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.subskills.taming.PetCombatMode;
import com.gmail.nossr50.locale.LocaleLoader;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.platform.text.TextUtils;
import com.gmail.nossr50.skills.taming.TamingManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * {@link PetCombatModeListener} — the sneak-right-click gesture, and specifically <em>who the click
 * belongs to</em>.
 *
 * <p>This is {@link RepairSalvageListenerTest} one level up, and for the same reason:
 * {@link PlayerInteractEvent.EntityInteract} fires on both logical sides, and answering only on the
 * server leaves the client running vanilla's own prediction — here, visibly sitting the pet and then
 * snapping it back upright when the server disagrees. So the first tests drive the event with a
 * plain (non-server) player and assert the click is claimed with {@link InteractionResult#CONSUME},
 * and the rest pin the boundary of that claim: a listener that swallowed every entity right-click
 * would break feeding, breeding, shearing, leashing and trading.
 *
 * <p>Every test drives the real {@code onEntityInteract} dispatch rather than
 * {@code isToggleGesture} alone. A predicate-only suite goes green with
 * {@link PetCombatModeListener#register} deleted and the feature entirely unreachable in game — the
 * {@code respawn-stale-handle} lesson.
 */
class PetCombatModeListenerTest {

    private static final BlockPos TARGET_POS = new BlockPos(4, 64, -7);

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    private McMMOPlayer mmoPlayer;
    private TamingManager tamingManager;

    @BeforeEach
    void setUp() {
        final GeneralConfig config = mock(GeneralConfig.class);
        lenient().when(config.isPetCombatModeEnabled()).thenReturn(true);
        lenient().when(config.getPetCombatModeToggleItem()).thenReturn("BONE");
        McMMOMod.setGeneralConfig(config);
    }

    @AfterEach
    void tearDown() {
        if (mmoPlayer != null) {
            UserManager.cleanupPlayer(mmoPlayer);
            mmoPlayer = null;
        }
        McMMOMod.setGeneralConfig(null);
    }

    // --- the both-sides claim -------------------------------------------------------------------

    /**
     * ⚠️⚠️ The regression guard. A client-side fire on the toggle gesture must claim the click with
     * {@link InteractionResult#CONSUME}.
     *
     * <p>Leaving the event un-cancelled lets the client predict vanilla's sit-toggle: the pet
     * visibly sits, then pops back up a tick later when the server's state arrives. Cancelling with
     * {@code FAIL} is worse — it cancels the packet outright, so the server never hears the gesture
     * and the toggle silently does nothing at all.
     */
    @Test
    void clientSideFireOnTheGestureClaimsTheClick() {
        final PlayerInteractEvent.EntityInteract event =
                gestureEvent(sneakingClientPlayer(new ItemStack(Items.BONE)), ownedWolf());

        PetCombatModeListener.onEntityInteract(event);

        assertEquals(true, event.isCanceled(),
                "the client fire must claim the gesture, or the client predicts vanilla's "
                        + "sit-toggle");
        assertEquals(InteractionResult.CONSUME, event.getCancellationResult());
    }

    /** The client-side fire must touch no state — the server fire owns the flip. */
    @Test
    void clientSideFireMutatesNothing() {
        final Player player = sneakingClientPlayer(new ItemStack(Items.BONE));
        // A tracked profile exists; the point is that the CLIENT fire must not reach it even so.
        trackedServerPlayer(new ItemStack(Items.BONE));

        PetCombatModeListener.onEntityInteract(gestureEvent(player, ownedWolf()));

        verify(tamingManager, never()).togglePetCombatMode();
    }

    // --- the server side does the work ----------------------------------------------------------

    @Test
    void serverSideFireTogglesTheMode() {
        final ServerPlayer player = trackedServerPlayer(new ItemStack(Items.BONE));
        when(tamingManager.togglePetCombatMode()).thenReturn(PetCombatMode.AGGRESSIVE);

        final PlayerInteractEvent.EntityInteract event = gestureEvent(player, ownedWolf(player));
        PetCombatModeListener.onEntityInteract(event);

        verify(tamingManager).togglePetCombatMode();
        assertEquals(true, event.isCanceled());
        assertEquals(InteractionResult.CONSUME, event.getCancellationResult());
    }

    /**
     * A resolved profile with no {@link TamingManager} is a wiring bug, not a game state — but the
     * click is already claimed on the client, so the listener must still cancel with
     * {@code CONSUME} rather than desyncing the sit. Pins the warn-log-and-cancel branch at
     * {@code PetCombatModeListener#onEntityInteract}, distinct from the pending-profile-load branch
     * above (a tracked {@link McMMOPlayer} whose {@code getTamingManager()} answers {@code null}).
     */
    @Test
    void aMissingTamingManagerStillConsumesTheClick() {
        final ServerPlayer player = trackedServerPlayerWithNoTamingManager(new ItemStack(Items.BONE));

        final PlayerInteractEvent.EntityInteract event = gestureEvent(player, ownedWolf(player));
        PetCombatModeListener.onEntityInteract(event);

        assertEquals(true, event.isCanceled(),
                "a wiring bug (no TamingManager) must not desync the sit — the click is already "
                        + "claimed on the client");
        assertEquals(InteractionResult.CONSUME, event.getCancellationResult());
    }

    /**
     * A click that arrives before the profile has loaded still consumes, and tells the player why —
     * the profile-pending message, sent straight to the entity rather than through
     * {@link com.gmail.nossr50.util.player.NotificationManager} (see the listener's own class doc:
     * every one of that class's methods no-ops on a null {@code McMMOPlayer}, which is exactly the
     * state here).
     *
     * <p>The client has already suppressed its sit prediction by the time this runs, so leaving the
     * event un-cancelled here makes the two sides disagree about whose click it was and sits the pet
     * on a gesture that was claimed. That mid-decision fall-through is exactly the repair-anvil bug.
     */
    @Test
    void anUnloadedProfileStillConsumesTheClickAndSendsThePendingLoadMessage() {
        // Deliberately NOT tracked in UserManager: this is the mid-join state.
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(UUID.randomUUID());
        lenient().when(player.getMainHandItem()).thenReturn(new ItemStack(Items.BONE));
        lenient().when(player.isShiftKeyDown()).thenReturn(true);
        lenient().when(player.getName()).thenReturn(Component.literal("mid-join"));

        final PlayerInteractEvent.EntityInteract event = gestureEvent(player, ownedWolf(player));
        PetCombatModeListener.onEntityInteract(event);

        assertEquals(true, event.isCanceled(),
                "handing the click back mid-decision sits the pet on a gesture already claimed");
        assertEquals(InteractionResult.CONSUME, event.getCancellationResult());

        final ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendSystemMessage(sent.capture());
        final String expectedText =
                TextUtils.toText(LocaleLoader.getString("Profile.PendingLoad")).getString();
        assertEquals(expectedText, sent.getValue().getString(),
                "the player must be told their profile has not loaded, not left guessing why the "
                        + "toggle silently did nothing");
    }

    // --- the boundary of the claim --------------------------------------------------------------

    /**
     * ⚠️ The single most important negative. Without the sneak requirement this listener would
     * swallow <em>every</em> right-click on a pet made while holding a bone, and a player could never
     * sit their wolf again.
     */
    @Test
    void aPlainRightClickWithoutSneakingPasses() {
        final Player player = clientPlayer(new ItemStack(Items.BONE), false);

        final PlayerInteractEvent.EntityInteract event = gestureEvent(player, ownedWolf());
        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /** Any other item is vanilla's click — feeding, breeding, shearing, leashing all still work. */
    @Test
    void sneakClickingWithTheWrongItemPasses() {
        final Player player = sneakingClientPlayer(new ItemStack(Items.WHEAT));

        final PlayerInteractEvent.EntityInteract event = gestureEvent(player, ownedWolf());
        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /** An empty hand is not the gesture either. */
    @Test
    void sneakClickingWithAnEmptyHandPasses() {
        final Player player = sneakingClientPlayer(ItemStack.EMPTY);

        final PlayerInteractEvent.EntityInteract event = gestureEvent(player, ownedWolf());
        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /**
     * Someone else's pet proves nothing about who is clicking, so the gesture must not claim it.
     * (Singleplayer today — but the check is one {@code isOwnedBy} call and the alternative is a
     * listener that toggles your stance by clicking a wolf you have never met.)
     */
    @Test
    void sneakClickingAPetYouDoNotOwnPasses() {
        final Wolf someoneElsesWolf = mock(Wolf.class);
        lenient().when(someoneElsesWolf.blockPosition()).thenReturn(TARGET_POS);
        lenient().when(someoneElsesWolf.isTame()).thenReturn(true);
        lenient().when(someoneElsesWolf.isOwnedBy(ArgumentMatchers.any())).thenReturn(false);

        final PlayerInteractEvent.EntityInteract event =
                gestureEvent(sneakingClientPlayer(new ItemStack(Items.BONE)), someoneElsesWolf);
        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /** An untamed wolf is a wild animal, not a pet. */
    @Test
    void sneakClickingAnUntamedWolfPasses() {
        final Wolf wild = mock(Wolf.class);
        lenient().when(wild.blockPosition()).thenReturn(TARGET_POS);
        lenient().when(wild.isTame()).thenReturn(false);
        lenient().when(wild.isOwnedBy(ArgumentMatchers.any())).thenReturn(false);

        final PlayerInteractEvent.EntityInteract event =
                gestureEvent(sneakingClientPlayer(new ItemStack(Items.BONE)), wild);
        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /** A hostile mob is not a {@link TamableAnimal} at all. */
    @Test
    void sneakClickingANonTameableMobPasses() {
        final Entity zombie = mock(Zombie.class);
        lenient().when(zombie.blockPosition()).thenReturn(TARGET_POS);

        final PlayerInteractEvent.EntityInteract event =
                gestureEvent(sneakingClientPlayer(new ItemStack(Items.BONE)), zombie);
        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /** The off-hand dispatch stays ignored, so one right-click cannot toggle the stance twice. */
    @Test
    void offHandFirePasses() {
        final PlayerInteractEvent.EntityInteract event = new PlayerInteractEvent.EntityInteract(
                sneakingClientPlayer(new ItemStack(Items.BONE)), InteractionHand.OFF_HAND,
                ownedWolf());

        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /**
     * The config switch has to reach the gesture, not only the sweep. A half-disabled state that
     * swallows the click but does nothing with it is worse than either extreme.
     */
    @Test
    void theGesturePassesWhenTheFeatureIsDisabled() {
        final GeneralConfig off = mock(GeneralConfig.class);
        lenient().when(off.isPetCombatModeEnabled()).thenReturn(false);
        lenient().when(off.getPetCombatModeToggleItem()).thenReturn("BONE");
        McMMOMod.setGeneralConfig(off);

        final PlayerInteractEvent.EntityInteract event =
                gestureEvent(sneakingClientPlayer(new ItemStack(Items.BONE)), ownedWolf());
        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /**
     * A toggle item that does not resolve to a real item makes the gesture inert rather than taking
     * down every entity interaction in the game.
     */
    @Test
    void anUnresolvableToggleItemPasses() {
        final GeneralConfig typo = mock(GeneralConfig.class);
        lenient().when(typo.isPetCombatModeEnabled()).thenReturn(true);
        lenient().when(typo.getPetCombatModeToggleItem()).thenReturn("NOT_A_REAL_ITEM");
        McMMOMod.setGeneralConfig(typo);

        final PlayerInteractEvent.EntityInteract event =
                gestureEvent(sneakingClientPlayer(new ItemStack(Items.BONE)), ownedWolf());
        PetCombatModeListener.onEntityInteract(event);

        assertFalse(event.isCanceled());
    }

    /** Cats are {@link TamableAnimal} too, so the gesture reads on them even though only wolves act. */
    @Test
    void theGestureWorksOnAnyTameableYouOwn() {
        final Cat cat = mock(Cat.class);
        lenient().when(cat.blockPosition()).thenReturn(TARGET_POS);
        lenient().when(cat.isTame()).thenReturn(true);
        lenient().when(cat.isOwnedBy(ArgumentMatchers.any())).thenReturn(true);

        final PlayerInteractEvent.EntityInteract event =
                gestureEvent(sneakingClientPlayer(new ItemStack(Items.BONE)), cat);
        PetCombatModeListener.onEntityInteract(event);

        assertEquals(true, event.isCanceled());
        assertEquals(InteractionResult.CONSUME, event.getCancellationResult());
    }

    // --- fixture --------------------------------------------------------------------------------

    private static PlayerInteractEvent.EntityInteract gestureEvent(Player player, Entity target) {
        return new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, target);
    }

    private static Player clientPlayer(ItemStack mainHand, boolean sneaking) {
        final Player player = mock(Player.class);
        lenient().when(player.getMainHandItem()).thenReturn(mainHand);
        lenient().when(player.isShiftKeyDown()).thenReturn(sneaking);
        return player;
    }

    private static Player sneakingClientPlayer(ItemStack mainHand) {
        return clientPlayer(mainHand, true);
    }

    /** A tamed wolf that answers "yes" to any owner asked about it. */
    private static Wolf ownedWolf() {
        final Wolf wolf = mock(Wolf.class);
        lenient().when(wolf.blockPosition()).thenReturn(TARGET_POS);
        lenient().when(wolf.isTame()).thenReturn(true);
        lenient().when(wolf.isOwnedBy(ArgumentMatchers.any())).thenReturn(true);
        return wolf;
    }

    /** A tamed wolf owned by exactly {@code owner} and nobody else. */
    private static Wolf ownedWolf(Player owner) {
        final Wolf wolf = mock(Wolf.class);
        lenient().when(wolf.blockPosition()).thenReturn(TARGET_POS);
        lenient().when(wolf.isTame()).thenReturn(true);
        lenient().when(wolf.isOwnedBy(ArgumentMatchers.any())).thenReturn(false);
        lenient().when(wolf.isOwnedBy(owner)).thenReturn(true);
        return wolf;
    }

    /** A sneaking server-side player holding {@code mainHand}, with a profile in {@link UserManager}. */
    private ServerPlayer trackedServerPlayer(ItemStack mainHand) {
        final UUID uuid = UUID.randomUUID();

        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(uuid);
        lenient().when(player.getMainHandItem()).thenReturn(mainHand);
        lenient().when(player.isShiftKeyDown()).thenReturn(true);
        lenient().when(player.getName()).thenReturn(Component.literal("tester"));

        tamingManager = mock(TamingManager.class);
        mmoPlayer = mock(McMMOPlayer.class);
        lenient().when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        lenient().when(mmoPlayer.getTamingManager()).thenReturn(tamingManager);
        UserManager.track(mmoPlayer);
        return player;
    }

    /**
     * A tracked profile whose {@link McMMOPlayer#getTamingManager()} answers {@code null} — the
     * resolved-but-unwired state {@link #aMissingTamingManagerStillConsumesTheClick} exercises.
     */
    private ServerPlayer trackedServerPlayerWithNoTamingManager(ItemStack mainHand) {
        final UUID uuid = UUID.randomUUID();

        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(uuid);
        lenient().when(player.getMainHandItem()).thenReturn(mainHand);
        lenient().when(player.isShiftKeyDown()).thenReturn(true);
        lenient().when(player.getName()).thenReturn(Component.literal("tester-no-taming"));

        mmoPlayer = mock(McMMOPlayer.class);
        lenient().when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(player));
        lenient().when(mmoPlayer.getTamingManager()).thenReturn(null);
        UserManager.track(mmoPlayer);
        return player;
    }
}
