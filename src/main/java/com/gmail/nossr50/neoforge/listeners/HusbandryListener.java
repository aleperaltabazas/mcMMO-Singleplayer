package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOAttachments;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.LogUtils;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.text.ConfigStringUtils;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
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

    // =============================================================================================
    // Task B: Breed + Raise — onAnimalsBred, onLovePlayer (Multi-Breed), onGrowthApplied/
    // onBreedingAgeChange (raise, feed, Accelerated Growth). Ports the Fabric original's stage 1/2
    // sections near-verbatim onto the Mojang-mapped 1.21.1 names — see
    // neoforge.mixin.BredAnimalsTriggerMixin / AnimalSetInLoveMixin / AgeableMobGrowthMixin for the
    // seams these methods are called from.
    // =============================================================================================

    /**
     * Set while Multi-Breed is spreading love to an animal's neighbours.
     *
     * <p>Load-bearing: the spread is implemented by calling {@code setInLove} on each neighbour,
     * which is the very method {@link com.gmail.nossr50.neoforge.mixin.AnimalSetInLoveMixin} hooks.
     * Without the guard the first fed animal would set its neighbours in love, each of those would
     * run the sweep again from its own position, and one piece of wheat would walk outward across
     * every animal in the world until the stack overflowed.
     *
     * <p>A {@link ThreadLocal} rather than a plain field for the same reason
     * {@link #PLAYER_INTERACTION} is one — the entire window is a single synchronous call on the
     * server thread, so it covers the whole re-entrant region exactly.
     */
    private static final ThreadLocal<Boolean> SPREADING_LOVE = ThreadLocal.withInitial(() -> false);

    /**
     * Whether this animal is a live Call-of-the-Wild summon, and so may not price a breeding.
     *
     * <p>Answered from the transient summon tracker rather than a mob flag: it already holds every
     * live summon for exactly as long as the summon exists. {@code CombatUtils#processCombatXP}
     * closes the combat half of the same exploit off the same tracker.
     *
     * <p>A {@code null} mate is treated as not-a-summon: the egg-laying breeders reach
     * {@link #onAnimalsBred} with only one parent, and refusing those would break turtles and frogs
     * for everyone.
     */
    private static boolean isCallOfTheWildSummon(@Nullable Animal animal) {
        final ExperienceConfig config = McMMOMod.getExperienceConfig();
        if (animal == null || config == null || !config.isCOTWBreedingPrevented()) {
            return false;
        }
        return McMMOMod.getTransientEntityTracker().isTransient(animal.getUUID());
    }

    /**
     * A player bred two animals: award Husbandry XP, then roll {@code Twins}.
     *
     * <p>Called from {@code BredAnimalsTriggerMixin}. See that mixin's javadoc for why that is the
     * seam.
     *
     * @param breeder the player vanilla credits with the breeding — never null at this call site
     * @param parent  the animal whose species prices the breeding
     * @param mate    the other parent
     * @param child   the baby about to be spawned; {@code null} for the egg-laying breeders
     */
    public static void onAnimalsBred(ServerPlayer breeder, Animal parent, Animal mate,
            AgeableMob child) {
        if (breeder == null || parent == null) {
            return;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(breeder.getUUID());
        if (mmoPlayer == null) {
            return; // data not loaded (e.g. mid-join).
        }
        final HusbandryManager husbandry = mmoPlayer.getHusbandryManager();
        if (husbandry == null) {
            return;
        }

        // Breeding your own Call-of-the-Wild summons pays nothing. Taming conjures the parents out
        // of a few bones, so paying for their offspring turns one skill's ability into another
        // skill's XP tap.
        //
        // It must refuse the calf as well as the XP, exactly as the award cap does: leaving the
        // child claimed would let the raise verb pay for it twenty minutes later, making this a
        // delay rather than a gate.
        if (isCallOfTheWildSummon(parent) || isCallOfTheWildSummon(mate)) {
            LogUtils.debug("Refusing Husbandry breed XP for " + breeder.getUUID()
                    + ": a parent is a Call of the Wild summon.");
            return;
        }

        final String entityConfigString = configStringOf(parent);
        final HusbandryManager.BreedAward award =
                husbandry.onBreed(entityConfigString, parent.level().getGameTime());
        if (award.capReached()) {
            // Once per window, not once per breeding. A gate that pays nothing and says nothing is
            // indistinguishable from a broken one.
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                    "Husbandry.BreedAwardCap.Reached");
        }

        claimOffspring(husbandry, breeder, child, award.paid());
        maybeBearTwin(mmoPlayer, husbandry, breeder, parent, mate, child, award.paid());
    }

    /**
     * Record a newborn as this player's, and shorten its childhood by {@code Accelerated Growth}.
     *
     * <p>Both halves belong together and both belong here: the marker is what lets the raise verb
     * pay the right player twenty minutes later, and the acceleration is applied once at birth
     * rather than by speeding the animal's ageing up every tick.
     *
     * <p>Called for the twin as well as for vanilla's child. A twin that carried no marker would be
     * the only baby in the game its breeder could not be paid for, which reads as a bug rather than
     * as balance.
     *
     * <p>The marker is a real, registered {@link McMMOAttachments#BRED_BY} data attachment, written
     * into the calf's own NBT via {@code setData} — see that field's javadoc for why this must never
     * be read/written through plain {@code getData}.
     *
     * <h2>⚠️ A breeding the award cap refused marks nothing</h2>
     * {@code paid} is what carries that, and it is the difference between a cap and a twenty-minute
     * delay. The raise verb pays a full breeding's worth of XP off this marker alone, so marking a
     * calf whose breeding paid nothing would let a pen bred past the cap collect the whole refused
     * amount anyway — later, invisibly, and with the cap's own log line saying it had been stopped.
     *
     * <p>The growth acceleration is applied either way. It is a yield effect, not a payout — no XP
     * flows from a calf growing up sooner unless it also carries the marker — so withholding it
     * would only make a refused breeding feel arbitrarily punished.
     *
     * @param paid whether the breeding that produced this child actually paid Husbandry XP
     */
    private static void claimOffspring(HusbandryManager husbandry, ServerPlayer breeder,
            AgeableMob child, boolean paid) {
        if (child == null) {
            return; // Egg-laying breeder: the clutch is not an entity we can mark.
        }
        if (paid) {
            child.setData(McMMOAttachments.BRED_BY, breeder.getUUID());
        }

        final int acceleratedAge = husbandry.applyGrowthAcceleration(child.getAge());
        if (acceleratedAge != child.getAge()) {
            child.setAge(acceleratedAge);
        }
    }

    /**
     * {@code Twins}: on a successful roll, create and spawn a second baby alongside the first.
     *
     * <p>The twin is built with a fresh {@code getBreedOffspring} call rather than by copying the
     * child vanilla made, so every species' own offspring logic — a horse's inherited attribute
     * roll, a sheep's dyed-wool colour blend, a mooshroom's variant — runs a second time and the
     * twins are siblings rather than clones.
     *
     * <p><b>Structurally satisfied, not by a guard.</b> A Twins baby cannot itself pay Twins in a
     * self-sustaining loop: the twin is spawned directly rather than through the breed trigger, so
     * no {@code BredAnimalsTrigger#trigger} fires for it, so nothing re-enters this method. There is
     * deliberately no flag to forget to set.
     *
     * <p><b>Known deviation, foxes only.</b> {@code Fox$FoxBreedGoal} calls {@code trust()} on the
     * child it made, so a fox twin is born untrusting where its sibling is not. Left as-is: the
     * alternative is a species branch here, which is exactly the "lookup table that will rot" the
     * skill's boundary rule exists to avoid.
     */
    private static void maybeBearTwin(McMMOPlayer mmoPlayer, HusbandryManager husbandry,
            ServerPlayer breeder, Animal parent, Animal mate, AgeableMob child, boolean paid) {
        if (child == null || mate == null) {
            return; // Egg-laying breeder: vanilla produced no baby for us to double.
        }
        if (!(parent.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!husbandry.rollTwins()) {
            return;
        }

        final AgeableMob twin = parent.getBreedOffspring(serverLevel, mate);
        if (twin == null) {
            return; // Species declined to make a second child; nothing to report.
        }
        twin.setBaby(true);
        twin.moveTo(parent.getX(), parent.getY(), parent.getZ(), 0.0F, 0.0F);
        // Claimed exactly like its sibling, and after setBaby so there is a childhood to shorten. A
        // twin with no bred-by marker would be the one baby in the game whose breeder could never be
        // paid for raising it -- and by the same token it inherits its sibling's refusal, so a
        // breeding the award cap turned down cannot smuggle a payable calf out through Twins.
        claimOffspring(husbandry, breeder, twin, paid);
        serverLevel.addFreshEntity(twin);

        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                "Husbandry.SubSkill.Twins.Proc");
    }

    /**
     * {@code Multi-Breed}: a player has just set one animal in love, so set its nearby same-species
     * neighbours in love too, from that one breeding item.
     *
     * <p>Called from {@code AnimalSetInLoveMixin}. Bounded by the radius alone — every eligible
     * animal it reaches is set in love, however many that is; the sweep's own cost is bounded by
     * {@link HusbandryManager#HARD_MAX_MULTI_BREED_RADIUS} regardless.
     *
     * @param fed    the animal the player actually fed
     * @param player the feeder; ignored unless it is a real server player
     */
    public static void onLovePlayer(Animal fed, Player player) {
        if (SPREADING_LOVE.get()) {
            return; // We are the ones doing the spreading — see the field javadoc.
        }
        if (fed == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final Level level = fed.level();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return;
        }
        final HusbandryManager husbandry = mmoPlayer.getHusbandryManager();
        if (husbandry == null || !husbandry.canMultiBreed()) {
            return;
        }

        final double radius = husbandry.getMultiBreedRadius();
        if (radius <= 0) {
            return;
        }

        final AABB searchBox = fed.getBoundingBox().inflate(radius);
        final List<Animal> neighbours = level.getEntities(EntityTypeTest.forClass(Animal.class),
                searchBox, candidate -> isMultiBreedCandidate(fed, candidate));

        SPREADING_LOVE.set(true);
        try {
            for (Animal neighbour : neighbours) {
                neighbour.setInLove(serverPlayer);
            }
        } finally {
            SPREADING_LOVE.set(false);
        }
    }

    /**
     * Whether one nearby animal should be swept up by Multi-Breed.
     *
     * <p>Mirrors the conditions vanilla's own {@code Animal#mobInteract} requires before it will
     * accept a breeding item: an adult, off its post-breeding cooldown, not already courting.
     * {@code getAge() == 0} covers both halves of that cooldown — negative while the animal is
     * still a baby, positive for the five minutes after a breeding — and {@code canFallInLove()} is
     * vanilla's own name for "not already in love" ({@code inLove <= 0}, confirmed via
     * {@code javap -c}).
     */
    private static boolean isMultiBreedCandidate(Animal fed, Animal candidate) {
        return candidate != fed
                && candidate.isAlive()
                && candidate.getType() == fed.getType()
                && candidate.getAge() == 0
                && candidate.canFallInLove();
    }

    // --- Raise, feed and Accelerated Growth ------------------------------------------------------

    /**
     * An animal is about to be grown along by {@code growthSeconds}: pay the feed verb, and let
     * {@code Accelerated Growth} double the growth.
     *
     * <p>Called from {@code AgeableMobGrowthMixin}. <b>Everything hinges on the identity check
     * below.</b> {@code ageUp} is reached by plenty of things that are not a player feeding an
     * animal — a lamb eating grass calls it, and a tadpole ages itself through it — so growth only
     * counts as a feed when a player is mid-interaction <em>with this very animal</em>. Without that
     * second half, the AFK wool-farm shape this skill's plan warns about turns into an AFK
     * grass-farm one.
     *
     * @param animal        the animal being grown
     * @param growthSeconds the seconds of growth vanilla was about to apply; positive
     * @return the seconds to actually apply — doubled on a successful Accelerated Growth roll
     */
    public static int onGrowthApplied(AgeableMob animal, int growthSeconds) {
        if (animal == null || growthSeconds <= 0) {
            return growthSeconds;
        }
        final Interaction interaction = PLAYER_INTERACTION.get();
        if (interaction == null || interaction.target() != animal) {
            return growthSeconds; // Not a player feed: grass, or a tadpole ageing itself.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(interaction.player().getUUID());
        if (mmoPlayer == null) {
            return growthSeconds;
        }
        final HusbandryManager husbandry = mmoPlayer.getHusbandryManager();
        if (husbandry == null) {
            return growthSeconds;
        }

        husbandry.onFeedBaby(configStringOf(animal));

        // Announced rather than silent, and the comparison is what makes it honest: applyFeedBonus
        // owns the roll, so asking whether it actually doubled beats re-rolling here and reporting a
        // different outcome from the one vanilla is about to act on.
        final int boostedSeconds = husbandry.applyFeedBonus(growthSeconds);
        if (boostedSeconds > growthSeconds) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                    "Husbandry.SubSkill.AcceleratedGrowth.Proc");
        }
        return boostedSeconds;
    }

    /**
     * An animal's breeding age is changing: if this is the moment it grows up, pay whoever bred it.
     *
     * <p>Called from {@code AgeableMobGrowthMixin} at the head of {@code AgeableMob#setAge}, so
     * {@code previousAge} is the value still in the field.
     *
     * <h2>⚠️ Why the transition is computed here rather than hooking {@code ageBoundaryReached}</h2>
     * See {@code AgeableMobGrowthMixin}'s own javadoc for the bytecode-verified reason:
     * {@code Goat#ageBoundaryReached} and {@code Hoglin#ageBoundaryReached} do not call
     * {@code super}, so a mixin there would pay zero raise XP for goats and hoglins. {@code setAge}
     * is declared only on {@code AgeableMob}, is overridden by nothing, and is where the
     * {@code ageBoundaryReached()} call itself lives, so every path arrives here.
     *
     * <h2>The two gates, both load-bearing</h2>
     * <b>The transition gate</b> is what makes this "grew up" rather than "the age changed":
     * vanilla runs the same code when an adult becomes a baby (a spawn egg) and, because save-data
     * loading routes through {@code setAge}, every single time a baby animal loads from disk.
     * Without it, flying away and back would pay the raise verb again. <b>The marker gate</b> is
     * what makes it <em>this player's</em> livestock rather than every wild baby in the world
     * coming of age. Because the marker is a persisted data attachment it also survives the world
     * being closed and reopened.
     *
     * @param animal      the animal whose age is changing
     * @param previousAge the age it currently has — negative while it is a baby
     * @param newAge      the age it is about to have
     */
    public static void onBreedingAgeChange(AgeableMob animal, int previousAge, int newAge) {
        if (animal == null || previousAge >= 0 || newAge < 0) {
            return; // Not the baby -> adult crossing.
        }
        if (!animal.hasData(McMMOAttachments.BRED_BY)) {
            return; // Nobody bred this one; it grew up on its own.
        }
        // Read and consumed in one call, so this animal can never pay a second time even if
        // something later drives it back across the boundary.
        final UUID breederId = animal.removeData(McMMOAttachments.BRED_BY);
        if (breederId == null) {
            return;
        }

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(breederId);
        if (mmoPlayer == null) {
            return;
        }
        final HusbandryManager husbandry = mmoPlayer.getHusbandryManager();
        if (husbandry != null) {
            husbandry.onRaise(configStringOf(animal));
        }
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
