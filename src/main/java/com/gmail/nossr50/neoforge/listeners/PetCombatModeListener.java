package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.subskills.taming.PetCombatMode;
import com.gmail.nossr50.locale.LocaleLoader;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.Materials;
import com.gmail.nossr50.platform.text.TextUtils;
import com.gmail.nossr50.skills.taming.TamingManager;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.sounds.SoundManager;
import com.gmail.nossr50.util.sounds.SoundType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The gesture that flips a player's {@link PetCombatMode}: sneak and right-click a pet you own while
 * holding the configured item (a bone by default).
 *
 * <p>Ports Fabric's {@code fabric.listeners.PetCombatModeListener}, whose {@code UseEntityCallback}
 * seam (fires on both logical sides, ahead of vanilla's own {@code interactMob} dispatch, and any
 * non-{@code PASS} return cancels further processing) maps 1:1 onto NeoForge's
 * {@link PlayerInteractEvent.EntityInteract}: confirmed by reading
 * {@code net/neoforged/neoforge/event/entity/player/PlayerInteractEvent.java} in the extracted
 * NeoForge sources jar (this task's own read, not carried over from the design spec) — the class doc
 * states {@code EntityInteract} "is fired on both sides when the player right clicks an entity ...
 * This event's state affects whether {@link Entity#interact(Player, InteractionHand)} ... are
 * called", with a settable {@code cancellationResult} (defaulting to {@code PASS}) that is exactly
 * Fabric's {@code ActionResult} contract carried over. No mixin fallback was needed for this task.
 *
 * <h2>Claimed on BOTH logical sides</h2>
 * {@code EntityInteract} fires on the client too. The three possible outcomes mirror the Fabric
 * class doc this replaces:
 * <ul>
 *   <li><b>un-cancelled on the client</b> — the client falls through to its own local prediction and
 *       the pet visibly sits, then un-sits when the server disagrees.</li>
 *   <li><b>cancelled with {@code cancellationResult = FAIL}</b> — worse: the interaction packet never
 *       reaches the server, so the toggle silently does nothing.</li>
 *   <li><b>cancelled with {@code cancellationResult = CONSUME} on both</b> — the client suppresses
 *       its prediction and still sends the packet; the server does the work. This is the one used
 *       here, same "claim on both or on neither" rule already proven by
 *       {@link RepairSalvageListener#onUseBlock}.</li>
 * </ul>
 *
 * <p>{@link #isToggleGesture} is the identity test both sides run identically. It is MC-typed but
 * side-free — sneaking, the main-hand stack and a tameable's owner are all synced fields, so the two
 * sides cannot disagree about whose click this is. Every mutation (the profile write, the message,
 * the sound) stays behind the {@link ServerPlayer} check.
 *
 * <h2>An unresolved profile still consumes</h2>
 * If {@link UserManager} has no {@link McMMOPlayer} yet (a click during join, before the profile
 * loads), this still cancels with {@code CONSUME} and tells the player to try again. Handing the
 * click back mid-decision would desync the two sides about whose click it was, sitting the pet on a
 * gesture that was already claimed on the client.
 *
 * <p>The stance itself is player-wide — the clicked pet only proves intent and ownership — so every
 * string this sends is plural and player-scoped. See {@link PetCombatMode}.
 */
public final class PetCombatModeListener {

    private PetCombatModeListener() {
    }

    /** Register the pet-interaction hook. Called once at mod load from {@code McMMOMod}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(PetCombatModeListener::onEntityInteract);
    }

    /**
     * Right-click an entity → if this is the toggle gesture on a pet the player owns, claim the click
     * and (server side) flip the player's pet combat stance.
     *
     * <p>Package-private so the test can drive the real dispatch rather than the predicates alone. A
     * predicate-only test passes with the {@link #register} call deleted, which is the
     * {@code respawn-stale-handle} lesson.
     */
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        final Player player = event.getEntity();
        final InteractionHand hand = event.getHand();
        final Entity target = event.getTarget();

        if (!isToggleGesture(player, hand, target)) {
            return; // Not our gesture — leave the event untouched, letting vanilla sit the pet.
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            // Client-side fire: claim the click so the client does not predict the sit-toggle, and
            // touch no state. The server-side fire below owns the actual flip. See the class doc.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            // Profile not loaded (mid-join). Still claimed above: the client has already suppressed
            // its prediction, so leaving the event un-cancelled here would sit the pet on a gesture
            // that was claimed.
            //
            // Sent straight to the entity, NOT through NotificationManager. Every one of its methods
            // takes a @Nullable McMMOPlayer and returns silently when it is null -- which is exactly
            // the state we are in -- so routing this through it would log the problem and tell the
            // player nothing, leaving the toggle looking simply broken.
            McMMOMod.LOGGER.debug("Pet combat-mode toggle by {} arrived before their profile loaded;"
                            + " consuming the click and asking them to retry.",
                    serverPlayer.getName().getString());
            serverPlayer.sendSystemMessage(TextUtils.toText(LocaleLoader.getString("Profile.PendingLoad")));
            return;
        }

        final TamingManager taming = mmoPlayer.getTamingManager();
        if (taming == null) {
            // A resolved player with no Taming manager is a wiring bug, not a game state -- but the
            // click is already claimed, so log it loudly rather than desyncing the sit.
            McMMOMod.LOGGER.warn("Player {} has no TamingManager; pet combat-mode toggle ignored.",
                    serverPlayer.getName().getString());
            return;
        }

        announce(mmoPlayer, taming.togglePetCombatMode());
        SoundManager.sendSound(mmoPlayer.getPlayer(), SoundType.TOOL_READY);
    }

    /**
     * The identity test, run identically on both logical sides.
     *
     * <p>Deliberately asks nothing that only a server knows. Sneaking, the main-hand stack and a
     * tameable's owner are all synced to the client, so the two sides cannot disagree about whose
     * click this is -- which is the entire safety property behind cancelling with {@code CONSUME} on
     * both.
     *
     * <p>Package-private for the test, which needs to assert the negative cases (no bone, not
     * sneaking, someone else's pet, an untamed mob) without constructing a server.
     */
    static boolean isToggleGesture(@NotNull Player player, @NotNull InteractionHand hand,
            @NotNull Entity entity) {
        if (hand != InteractionHand.MAIN_HAND) {
            return false; // Avoid the off-hand dispatch double-firing the toggle.
        }
        if (!isFeatureEnabled()) {
            return false;
        }
        if (!player.isShiftKeyDown()) {
            return false; // A plain right-click still belongs to vanilla's sit-toggle.
        }
        if (!(entity instanceof TamableAnimal pet) || !pet.isTame() || !pet.isOwnedBy(player)) {
            return false; // Someone else's pet, or not a pet at all.
        }
        final Item toggleItem = toggleItem();
        return toggleItem != null && player.getMainHandItem().is(toggleItem);
    }

    /**
     * Tells the player which stance their <em>pets</em> -- plural, player-wide -- are now in, with
     * the one-line explanation of what that means.
     *
     * <p>The wording is the feature's most likely bug report. The gesture is aimed at one animal and
     * the stance is player-wide, so a message saying "this wolf" would be actively wrong for every
     * other pet the player owns.
     */
    private static void announce(@NotNull McMMOPlayer mmoPlayer, @NotNull PetCombatMode mode) {
        NotificationManager.sendPlayerInformationChatOnly(mmoPlayer, "Taming.PetMode.Toggled",
                LocaleLoader.getString(mode.localeKey()));
        NotificationManager.sendPlayerInformationChatOnly(mmoPlayer, mode.localeKey() + ".Detail");
    }

    /**
     * Whether the feature is on. Null-guarded like other listeners' {@code isFeatureEnabled}: the
     * config is absent in unit tests that exercise the dispatch without booting the mod, and a
     * feature defaulting to on there is what lets those tests drive the real callback.
     */
    private static boolean isFeatureEnabled() {
        return McMMOMod.getGeneralConfig() == null
                || McMMOMod.getGeneralConfig().isPetCombatModeEnabled();
    }

    /**
     * The configured toggle item, or {@code null} when the name does not resolve to a real item.
     *
     * <p>Resolving to null rather than throwing means a typo'd config makes the gesture inert -- the
     * pet sits as vanilla intends -- instead of taking down every entity interaction in the game.
     */
    private static @Nullable Item toggleItem() {
        final String name = McMMOMod.getGeneralConfig() == null
                ? "BONE"
                : McMMOMod.getGeneralConfig().getPetCombatModeToggleItem();
        return Materials.item(name).orElse(null);
    }
}
