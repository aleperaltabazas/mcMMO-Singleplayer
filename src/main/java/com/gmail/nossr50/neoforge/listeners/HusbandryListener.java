package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.text.ConfigStringUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Husbandry's MC-typed glue layer. Ports {@code fabric.listeners.HusbandryListener} — see
 * docs/superpowers/specs/2026-08-30-husbandry-listener-design.md for the full seam-by-seam
 * verification this port is built against. Every decision — what a breeding/raise/shear/hive/milk/
 * brush/hatch is worth, whether Twins/Multi-Breed/Accelerated Growth/Bountiful Harvest/Beekeeper/
 * Selective Breeding/Brood/Hidden Bounty fire — belongs to the MC-free
 * {@link com.gmail.nossr50.skills.husbandry.HusbandryManager}; this class only resolves entities,
 * reads/writes the world, and joins mixin-captured context to it.
 *
 * <p><b>PORT (NeoForge, Task A):</b> this task lands the foundations every later task's verb
 * methods call into — the interaction stash ({@link #PLAYER_INTERACTION} /
 * {@link #beginPlayerInteraction} / {@link #endPlayerInteraction}, fed by
 * {@code neoforge.mixin.PlayerInteractionStashMixin}) and the four shared helpers
 * ({@link #husbandryOfInteractionWith}, {@link #husbandryOf}, {@link #configStringOf},
 * {@link #giveOrDrop}). No verb (breed/raise/shear/hive/milk/brush/selective-breeding/brood/hidden
 * bounty) is wired yet — those land in Tasks B/C/D, each adding its own methods below without
 * needing to touch anything in this task.
 */
public final class HusbandryListener {

    /** One player-entity interaction: who, and with what. */
    private record Interaction(ServerPlayer player, Entity target) {
    }

    /**
     * The player-entity interaction currently in flight, or {@code null} outside one.
     *
     * <p><b>This exists because {@code AgeableMob#ageUp} has no player and no honest way to find
     * one.</b> The feed verb (Task C) has to know who fed the animal, and vanilla's several
     * feeding paths ({@code Animal#mobInteract}, {@code Dolphin#mobInteract},
     * {@code Panda#mobInteract}, and {@code receiveFood} on horse/camel/llama) share exactly one
     * callee — {@code AgeableMob#ageUp(int, boolean)} — which takes only an int, no entity
     * identity at all. Hooking those several entry points instead is the enumeration this port has
     * been burned by before (see the design spec's §3); hooking {@code ageUp} alone is worse
     * still, because at least one of its callers is not a player at all (a sheep aging itself by
     * eating grass). So the player is stashed at the one funnel every player-entity interaction
     * passes through — {@code Player#interactOn}, via {@code PlayerInteractionStashMixin} — and
     * will be consumed at the one funnel every growth passes through, once Task C wires it.
     *
     * <p>Scoped by the mixin's HEAD/RETURN pair, so it is set for exactly the duration of one
     * synchronous {@code interactOn} call on the server thread — the same
     * {@code CombatUtils.IN_MCMMO_DAMAGE} shape every other {@code ThreadLocal} bridge in this
     * codebase uses (see {@code EntityDamageListener#PRE_ARMOR_DAMAGE},
     * {@code AlchemyListener#BREW_POSITION}). A nested interaction would clear its parent's stash
     * early, which costs XP rather than paying it wrongly; vanilla has no such nesting today.
     *
     * <p><b>No identity-matching safety net on this stash itself</b> — unlike
     * {@code EntityDamageListener#PRE_ARMOR_DAMAGE}, which stores the entity/source identity
     * alongside the value so a mismatched read can be refused. That net belongs on the
     * <em>consumer</em> side here instead: {@link #husbandryOfInteractionWith} already compares
     * the stashed {@link Interaction#target()} against the entity a verb method asks about before
     * trusting the stash at all, so a second copy of the same check on the write side would be
     * redundant. The two bridges differ in shape for a reason: {@code PRE_ARMOR_DAMAGE} joins a
     * mixin injection to a *later, differently-triggered* event that could in principle fire for
     * an unrelated hit if some other mod re-entered the pipeline between the two; this stash is
     * read only by verb methods called synchronously from within the very {@code interactOn} frame
     * it brackets, so there is no gap for an unrelated read to land in.
     */
    private static final ThreadLocal<Interaction> PLAYER_INTERACTION = new ThreadLocal<>();

    private HusbandryListener() {
    }

    /**
     * A player has begun interacting with an entity; remember it for the feed verb (Task C).
     *
     * <p>Called from {@code PlayerInteractionStashMixin}'s {@code HEAD} injector on
     * {@code Player#interactOn}. See {@link #PLAYER_INTERACTION} for why the feed verb cannot
     * simply hook the feeding methods themselves.
     *
     * @param player the interacting player; only a {@link ServerPlayer} is stashed — a client-side
     *               mirror of this call (this mixin fires on both sides) is a no-op here
     * @param target the entity being interacted with
     */
    public static void beginPlayerInteraction(Player player, Entity target) {
        if (player instanceof ServerPlayer serverPlayer && target != null) {
            PLAYER_INTERACTION.set(new Interaction(serverPlayer, target));
        }
    }

    /**
     * The interaction has finished, successfully or not. Called from the mixin's {@code RETURN}
     * injector — once per actual invocation, even though the injector itself is woven at all five
     * of {@code interactOn}'s return statements (see the mixin's own javadoc for why that count is
     * correct and not a mistake).
     */
    public static void endPlayerInteraction() {
        PLAYER_INTERACTION.remove();
    }

    /**
     * The Husbandry manager of the player currently interacting with {@code target}, or
     * {@code null} if nobody is.
     *
     * <p>The shared real-player gate every harvest verb (Task B/C/D) uses. The identity check —
     * comparing {@code target} against the stashed {@link Interaction#target()} — is what makes
     * this a gate rather than a hint: without it, any harvest anywhere during a right-click (a
     * dispenser firing in the same tick, on the other side of the world) would bill to whoever
     * happened to have a hand out.
     */
    private static @Nullable HusbandryManager husbandryOfInteractionWith(Entity target) {
        final Interaction interaction = PLAYER_INTERACTION.get();
        if (interaction == null || interaction.target() != target) {
            return null;
        }
        return husbandryOf(interaction.player());
    }

    /** The Husbandry manager for a server player, or {@code null} if their data is not loaded. */
    private static @Nullable HusbandryManager husbandryOf(ServerPlayer player) {
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(player.getUUID());
        return mmoPlayer == null ? null : mmoPlayer.getHusbandryManager();
    }

    /** The animal's {@code experience.yml} key, e.g. {@code "Cow"}. */
    private static String configStringOf(Entity animal) {
        return ConfigStringUtils.getConfigEntityTypeString(
                BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath());
    }

    /** Hand a bonus stack to the player, dropping it at their feet if they have no room. */
    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /**
     * Drop the in-flight interaction stash. Called from {@code McMMOMod#onServerStopping},
     * belt-and-braces rather than a fix for a known leak — {@link #PLAYER_INTERACTION} is cleared
     * on every read via {@link #endPlayerInteraction}, so the only way one survives to here is an
     * {@code interactOn} call whose HEAD injector ran but whose RETURN injector never did (a crash
     * mid-interaction). That would strand a single player + entity reference on the server thread,
     * which in singleplayer outlives the world the player just left. Mirrors
     * {@code EntityDamageListener#clear}'s own belt-and-braces {@code PRE_ARMOR_DAMAGE.remove()}.
     */
    public static void clear() {
        PLAYER_INTERACTION.remove();
    }
}
