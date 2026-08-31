package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.config.treasure.TreasureConfig;
import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.treasure.HusbandryTreasure;
import com.gmail.nossr50.neoforge.McMMOAttachments;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.ItemSpecBuilder;
import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.LogUtils;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.random.ProbabilityUtil;
import com.gmail.nossr50.util.text.ConfigStringUtils;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
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
 *
 * <p><b>PORT (NeoForge, Task D):</b> this task lands the last two verbs, Selective Breeding and
 * Brood, and is the final task adding to this file's harvest/breeding section (Task E wires a call
 * from {@code PlayerMovementTracker} instead). Both are the same one-hook-on-a-return-value shape
 * as the rest of this class's stashes: Selective Breeding stashes the breeder around
 * {@code AbstractHorse#setOffspringAttributes} and biases {@code createOffspringAttribute}'s return
 * value; Brood layers two extra-chance rolls on top of {@code ThrownEgg#onHit}'s own two dice. See
 * {@code neoforge.mixin.AbstractHorseChildAttributesMixin} / {@code neoforge.mixin.ThrownEggHatchMixin}
 * for the seams.
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

    // =============================================================================================
    // Task C: Harvest family — Shear, Hive, Milk, Brush, Hidden Bounty, D-H5 cooldown. Ports the
    // Fabric original's stage 3/4/5-harvest sections onto Mojang-mapped 1.21.1 names — see
    // neoforge.mixin.ShearsItemInteractMixin / BeehiveBlockUseItemOnMixin / CowGoatMilkMixin /
    // MushroomCowStewMixin / ArmadilloBrushMixin for the seams these methods are called from.
    //
    // Shear is a genuine redesign, not a transcription (design spec §5): NeoForge unifies every
    // shearable species behind ShearsItem#interactLivingEntity + IShearable, so there is no
    // per-species mixin here at all, and the player is already a direct method parameter at that
    // seam — unlike every other verb in this class, shear needs no interaction-stash lookup.
    // =============================================================================================

    /**
     * Open for exactly the duration of one {@code interactLivingEntity} call that actually sheared
     * something, and {@code true} only when that shear both belongs to a player and won its
     * {@code Bountiful Harvest} roll.
     *
     * <p>A {@link ThreadLocal} for the same reason as {@link #PLAYER_INTERACTION}: the value is
     * only ever read on the thread that set it, on the same call stack, and it must not survive an
     * exception on an unrelated one.
     *
     * <p>🔑 <b>The window is what makes hooking the {@code drops} local safe.</b> Doubling every
     * item in that list unconditionally would double drops from a shear this player did not pay
     * for (there is none such today — this is not the dispenser gate, which is structural per the
     * class javadoc above — but gating on an explicitly opened window is what keeps the two
     * concerns separate rather than folding "is this a real harvest" into the doubling logic
     * itself).
     */
    private static final ThreadLocal<Boolean> SHEAR_BONUS = ThreadLocal.withInitial(() -> false);

    /**
     * A shear is starting and vanilla has already confirmed {@code sheared} is willing to be
     * sheared ({@code IShearable#isShearable} returned {@code true}): pay the shear verb, roll
     * Hidden Bounty, and decide {@code Bountiful Harvest} once for the whole shear.
     *
     * <p>Called from {@code ShearsItemInteractMixin}, which — unlike every other verb hook in this
     * class — hands the player over as a direct parameter rather than through
     * {@link #PLAYER_INTERACTION}: {@code ShearsItem#interactLivingEntity(ItemStack, Player,
     * LivingEntity, InteractionHand)} already has one in scope, and no dispenser reaches this
     * method at all (see the mixin's own javadoc for the bytecode confirming
     * {@code ShearsDispenseItemBehavior} calls {@code IShearable#onSheared} directly with a
     * {@code null} player, never through here).
     *
     * <p>⚠️ <b>Rolled ONCE per shear rather than per item</b>, matching the Fabric original: a sheep
     * that yields three wool would otherwise resolve the sub-skill three times and turn a clean
     * "this shear paid double" into a noisy partial one.
     */
    public static void beginShear(LivingEntity sheared, Player player) {
        SHEAR_BONUS.set(false);
        if (sheared == null || !(player instanceof ServerPlayer serverPlayer)) {
            return; // Client-side mirror of this call, or (structurally impossible) no player.
        }
        final HusbandryManager husbandry = husbandryOf(serverPlayer);
        if (husbandry == null) {
            return; // Player data not loaded.
        }
        husbandry.onShear();
        rollHiddenBounty(husbandry, serverPlayer, HIDDEN_BOUNTY_SHEAR);
        SHEAR_BONUS.set(husbandry.rollBonusHarvestDrop());
    }

    /**
     * The shear call is over, whichever branch it returned from — {@code IShearable} declined, or a
     * real shear happened. Called from both of {@code interactLivingEntity}'s {@code areturn}s.
     */
    public static void endShear() {
        SHEAR_BONUS.remove();
    }

    /**
     * {@code Bountiful Harvest}: hand back a doubled stack while a winning shear window is open.
     *
     * <p>Doubling the stack rather than dropping a second one is deliberate — it is one
     * {@code ItemEntity} instead of two (via {@code IShearable#spawnShearedDrop}, which the mixin
     * lets run unmodified against whatever this returns), and it cannot desynchronise from the
     * first drop's position or pickup delay.
     *
     * @return {@code stack} untouched unless a shear window is open and won its roll
     */
    public static ItemStack onShearDropStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !Boolean.TRUE.equals(SHEAR_BONUS.get())) {
            return stack;
        }
        final ItemStack doubled = stack.copy();
        doubled.setCount(stack.getCount() * 2);
        return doubled;
    }

    /**
     * A shearing tool is about to take {@code damageAmount} durability: let {@code Bountiful
     * Harvest} spare it.
     *
     * <p>Called from {@code ShearsItemInteractMixin}'s {@code @ModifyArg} on
     * {@code stack.hurtAndBreak(1, player, ...)} — reached only inside the same server-side,
     * already-shearable branch {@link #beginShear} opened its window for, so no extra gate is
     * needed here beyond the real-player check.
     *
     * @param player       whoever is holding the shears
     * @param damageAmount the durability vanilla was about to take
     * @return {@code damageAmount}, or {@code 0} on a successful save
     */
    public static int onShearToolDamaged(Player player, int damageAmount) {
        if (damageAmount <= 0 || !(player instanceof ServerPlayer serverPlayer)) {
            return damageAmount;
        }
        final HusbandryManager husbandry = husbandryOf(serverPlayer);
        if (husbandry == null) {
            return damageAmount;
        }
        return husbandry.rollToolDurabilitySave() ? 0 : damageAmount;
    }

    // --- Hive, Beekeeper and the hive durability save ----------------------------------------------

    /**
     * The {@code treasures.yml} {@code Drops_From} verb groups {@code Hidden Bounty} is keyed on.
     * Named constants rather than inline literals because they must match the shipped YAML exactly
     * and a typo would be a sub-skill that silently never finds anything — pinned by
     * {@code HusbandryListenerHiveTest}.
     */
    static final String HIDDEN_BOUNTY_SHEAR = "Shear";
    static final String HIDDEN_BOUNTY_HIVE = "Hive";
    static final String HIDDEN_BOUNTY_MILK = "Milk";
    static final String HIDDEN_BOUNTY_BRUSH = "Brush";

    /**
     * A hive gave up its honeycomb to a player's shears: pay the hive verb, and let
     * {@code Bountiful Harvest} and {@code Beekeeper} add to the haul.
     *
     * <p>Called from {@code BeehiveBlockUseItemOnMixin}, right after {@code BeehiveBlock}'s own
     * {@code dropHoneycomb(Level, BlockPos)} call — the one point in {@code useItemOn} that is
     * reached only when a real player's shears actually harvested a full hive (see the mixin's own
     * javadoc for why {@code useItemOn} itself, not either sub-primitive, is the hook, and for the
     * bytecode confirming neither {@code ShearsDispenseItemBehavior} nor any hive dispenser
     * behaviour reaches this path).
     *
     * @param player   the harvester
     * @param usedItem the shears in their hand, already worn by this harvest
     * @param state    the hive's state, still reading a full honey level at this point
     * @param level    the hive's world
     * @param pos      the hive
     */
    public static void onHoneycombHarvested(Player player, ItemStack usedItem, BlockState state,
            Level level, BlockPos pos) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final HusbandryManager husbandry = husbandryOf(serverPlayer);
        if (husbandry == null) {
            return;
        }
        husbandry.onHiveHarvest();

        // Delivered as extra rolls of vanilla's own harvest loot rather than as honeycombs we
        // fabricate, exactly as the shear bonus re-runs the species' own drop handler: the yield
        // stays whatever the game says a hive yields, including any future change to it.
        for (int helping = bonusHiveHelpings(husbandry); helping > 0; helping--) {
            BeehiveBlock.dropHoneycomb(serverLevel, pos);
        }
        rollHiddenBounty(husbandry, serverPlayer, HIDDEN_BOUNTY_HIVE);
    }

    /**
     * A hive gave up a bottle of honey to a player: pay the hive verb, and let {@code Bountiful
     * Harvest} and {@code Beekeeper} add to the haul.
     *
     * <p>The bottle half of the same verb, hooked separately from {@link #onHoneycombHarvested}
     * because the two halves of {@code useItemOn} produce their yields by completely different
     * means — the shears roll a loot table, the bottle hands over one hard-coded
     * {@code HONEY_BOTTLE} — and because splitting them is what makes each hook unambiguous about
     * which harvest it is looking at.
     *
     * @param player the harvester
     */
    public static void onHoneyBottled(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final HusbandryManager husbandry = husbandryOf(serverPlayer);
        if (husbandry == null) {
            return;
        }
        husbandry.onHiveHarvest();

        for (int helping = bonusHiveHelpings(husbandry); helping > 0; helping--) {
            giveOrDrop(serverPlayer, new ItemStack(Items.HONEY_BOTTLE));
        }
        rollHiddenBounty(husbandry, serverPlayer, HIDDEN_BOUNTY_HIVE);
    }

    /**
     * How many <em>extra</em> helpings this hive harvest yields, 0–2.
     *
     * <p>The two rolls stack rather than being an either/or, which is the deliberate difference
     * between the harvest family's general bonus and the bee specialist's: a maxed beekeeper should
     * visibly out-yield a maxed generalist at a hive, and cannot if their sub-skill only re-rolls
     * the same coin.
     *
     * <p>Package-private so the stacking can be asserted directly. The delivery it feeds cannot be
     * unit-tested — an extra {@code dropHoneycomb} roll needs a real {@code ServerLevel} — so the
     * arithmetic is pinned here and the delivery is a boot-check-only concern.
     */
    static int bonusHiveHelpings(HusbandryManager husbandry) {
        return (husbandry.rollBonusHarvestDrop() ? 1 : 0) + (husbandry.rollBonusHoney() ? 1 : 0);
    }

    /**
     * Whether this player's hive harvests leave the bees alone — {@code Beekeeper}.
     *
     * <p>Called from {@code BeehiveBlockUseItemOnMixin}, which feeds the answer into vanilla's own
     * {@code CampfireBlock.isSmokeyPos} test via a {@code @ModifyExpressionValue}.
     *
     * <h2>⚠️ Polarity, spelled out because transcribing the Fabric expression here is backwards</h2>
     * Fabric's hook widened a {@code true} ("lit campfire in range") into "also calm if my
     * sub-skill says so" — {@code return litCampfireInRange || sheltered;}. 1.21.1's branch is
     * gated the other way round: {@code if (!CampfireBlock.isSmokeyPos(...)) { ...anger the hive... }
     * else { ...calm reset... }} — the <b>angry</b> branch is guarded by "NOT smokey". So the mixin
     * must widen {@code isSmokeyPos}'s own return value toward the angry branch <em>not</em> being
     * taken: {@code return smokey || husbandry.countsAsShelteredHiveHarvest();}. Getting this
     * backwards — gating on {@code !isSmokeyPos} the way the Fabric expression's shape suggests —
     * would anger bees on a <em>sheltered</em> harvest and do nothing on an unsheltered one.
     */
    public static boolean hiveHarvestLeavesBeesCalm(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        final HusbandryManager husbandry = husbandryOf(serverPlayer);
        return husbandry != null && husbandry.countsAsShelteredHiveHarvest();
    }

    /**
     * A hive is about to wear the shears that robbed it: let {@code Bountiful Harvest} spare them.
     *
     * <p>Called from {@code BeehiveBlockUseItemOnMixin}. The gate is the tool's own holder, which
     * vanilla hands to the {@code hurtAndBreak} call — no interaction stash needed, because a
     * dispenser harvesting a hive never reaches {@code useItemOn} at all.
     *
     * @param holder       whoever is holding the shears
     * @param damageAmount the durability vanilla was about to take
     * @return {@code damageAmount}, or {@code 0} on a successful save
     */
    public static int onHiveToolDamaged(Player holder, int damageAmount) {
        if (damageAmount <= 0 || !(holder instanceof ServerPlayer serverPlayer)) {
            return damageAmount;
        }
        final HusbandryManager husbandry = husbandryOf(serverPlayer);
        if (husbandry == null) {
            return damageAmount;
        }
        return husbandry.rollToolDurabilitySave() ? 0 : damageAmount;
    }

    // --- Milk and Brush -----------------------------------------------------------------------------

    /**
     * A player milked a cow or a goat, or bowled a mooshroom's stew: pay the milk verb.
     *
     * <p>Called from {@code CowGoatMilkMixin} — which targets {@code Cow} <b>and</b> {@code Goat},
     * since a goat re-implements the bucket branch inline rather than inheriting it — and from
     * {@code MushroomCowStewMixin}. Every route is the same verb and shares this one body and one
     * cooldown, so a mooshroom cannot be milked and stewed for two awards in the same breath, and a
     * newly added target inherits the gate rather than needing it wired again.
     *
     * <p><b>Vanilla rate-limits this verb by nothing at all</b> — right-clicking the same cow with a
     * bucket is free and repeatable as fast as a player can click — so it is the D-H5 cooldown, not
     * any game mechanic, that bounds it. Unlike the shear and hive verbs there is no interaction
     * stash to check: {@code mobInteract} takes the {@link Player} directly and no dispenser reaches
     * it, so the real-player gate is the signature.
     *
     * @param animal the cow, goat or mooshroom
     * @param player the milker
     */
    public static void onMilked(Entity animal, Player player) {
        if (animal == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final HusbandryManager husbandry = husbandryOf(serverPlayer);
        if (husbandry == null || !harvestCooldownElapsed(husbandry, animal)) {
            return;
        }
        husbandry.onMilk();
        rollHiddenBounty(husbandry, serverPlayer, HIDDEN_BOUNTY_MILK);
    }

    /**
     * A player brushed an armadillo and a scute was actually delivered: pay the brush verb, and let
     * {@code Bountiful Harvest} deliver a second one.
     *
     * <h2>⚠️ Why this pays on the drop and the shear verb pays on the attempt</h2>
     * Shearing is gated upstream by {@code isShearable()} — a sheep with no wool cannot be sheared
     * at all — so by the time the shear seam is reached, a harvest has definitely happened.
     * <b>Brushing has no such gate</b> beyond age: {@code brushOffScute} refuses a baby and
     * succeeds for any adult, and vanilla's own passive-shed cooldown is never read or reset on
     * this path (design spec §8). So the caller hands us {@code brushOffScute}'s own result and
     * this pays only on a real delivery.
     *
     * <h2>🔑 The real-player gate is the call site, not the signature</h2>
     * {@code Armadillo#mobInteract} is only ever reached by a player; vanilla's own
     * armadillo-brushing behaviour does not go through it. That is a stricter gate, not a weaker
     * one — but it is a different reason, and it lives at the call site (the mixin only applies to
     * {@code mobInteract}, never to {@code brushOffScute} itself).
     *
     * @param armadillo the animal being brushed
     * @param brusher   whoever is brushing
     * @param brushed   whether vanilla's own {@code brushOffScute} actually handed over a scute
     * @return {@code true} if {@code Bountiful Harvest} won and a second scute is owed
     */
    public static boolean onBrushed(Entity armadillo, Entity brusher, boolean brushed) {
        // 🔑 The "a scute really changed hands" gate lives HERE rather than in the mixin, so that a
        // test can reach it. It is the whole basis of this verb -- brushing has no upstream gate the
        // way isShearable() gates shearing -- and a guard the caller owns is a guard nothing proves.
        if (!brushed) {
            return false;
        }
        if (armadillo == null || !(brusher instanceof ServerPlayer player)) {
            return false;
        }
        final HusbandryManager husbandry = husbandryOf(player);
        if (husbandry == null) {
            return false;
        }
        // The cooldown gates the reward, never the drop: a brush inside the window still yields its
        // scute, it simply does not pay again. Refusing vanilla's own loot would be a mod quietly
        // breaking the game to enforce its own balance.
        if (!harvestCooldownElapsed(husbandry, armadillo)) {
            return false;
        }
        husbandry.onBrush();
        rollHiddenBounty(husbandry, player, HIDDEN_BOUNTY_BRUSH);
        return husbandry.rollBonusHarvestDrop();
    }

    /**
     * A brush is about to take {@code damageAmount} durability: let {@code Bountiful Harvest} spare
     * it.
     *
     * <p>Called from {@code ArmadilloBrushMixin}. The shear verb's durability save has an exact
     * sibling here for the same reason it had to name a species there — vanilla wears the tool back
     * in {@code mobInteract}, after {@code brushOffScute} has returned. It is worth 16 durability a
     * brush, against a brush's total of 64, so this is a much larger effect than the shear save.
     *
     * @param armadillo    the animal being brushed
     * @param damageAmount the durability vanilla was about to take
     * @return {@code damageAmount}, or {@code 0} on a successful save
     */
    public static int onBrushToolDamaged(Entity armadillo, int damageAmount) {
        if (armadillo == null || damageAmount <= 0) {
            return damageAmount;
        }
        final HusbandryManager husbandry = husbandryOfInteractionWith(armadillo);
        if (husbandry == null) {
            return damageAmount;
        }
        return husbandry.rollToolDurabilitySave() ? 0 : damageAmount;
    }

    // --- Hidden Bounty and the D-H5 harvest cooldown -------------------------------------------------

    /**
     * The world tick at which one animal last paid a Husbandry harvest award.
     *
     * <p>Transient on purpose, and that is the whole reason it is a {@link MetadataStore} entry
     * rather than a persistent attachment the way {@link McMMOAttachments#BRED_BY} had to become:
     * this is a five-minute window, so losing it when the world closes costs a player one early
     * payout and nothing else. The bred-by marker had to persist because <em>twenty minutes of
     * growth</em> would otherwise hinge invisibly on whether you quit in between; five minutes of
     * cooldown does not have that problem.
     */
    private static final String HARVEST_COOLDOWN_KEY = "mcMMO_husbandryHarvestTick";

    /**
     * {@code Hidden Bounty}: roll for a rare find on a harvest, and hand it over.
     *
     * <p>One body shared by all four harvest verbs. The verb arrives as the {@code treasures.yml}
     * {@code Drops_From} group name, which is what lets the table be keyed on the <em>act</em>
     * rather than on the species: keying on the animal would need a row per mob and would rot the
     * first time a version added one.
     *
     * <p>The selection itself is MC-free and lives in the manager, taking both random draws as
     * arguments; this method owns only the config read and the item spawn. Same split as Hylian
     * Luck's and Fishing's treasure rolls.
     *
     * @param husbandry the harvester's manager
     * @param player    the harvester, who the find is given to
     * @param verb      {@link #HIDDEN_BOUNTY_SHEAR}, {@link #HIDDEN_BOUNTY_HIVE},
     *                  {@link #HIDDEN_BOUNTY_MILK} or {@link #HIDDEN_BOUNTY_BRUSH}
     */
    private static void rollHiddenBounty(HusbandryManager husbandry, ServerPlayer player,
            String verb) {
        final TreasureConfig treasures = McMMOMod.getTreasureConfig();
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(player.getUUID());
        if (treasures == null || mmoPlayer == null) {
            return; // Not loaded (early boot, or a unit test with no config bound).
        }
        final List<HusbandryTreasure> candidates = treasures.getHusbandryTreasures(verb);
        if (candidates.isEmpty()) {
            return;
        }
        final Optional<HusbandryTreasure> won = husbandry.selectHiddenBounty(candidates,
                husbandry.rollHiddenBounty(),
                chance -> ProbabilityUtil.isStaticSkillRNGSuccessful(PrimarySkillType.HUSBANDRY,
                        mmoPlayer, chance));
        if (won.isEmpty()) {
            return;
        }

        final HusbandryTreasure treasure = won.get();
        final Optional<ItemStack> built = ItemSpecBuilder.build(treasure.getDrop());
        if (built.isEmpty()) {
            // The treasure names a material with no vanilla item (already logged by Materials).
            // Deliberately silent beyond that: announcing a find nobody received would be worse.
            return;
        }
        giveOrDrop(player, built.get());
        husbandry.onHiddenBountyFound(treasure.getXp());
        NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                "Husbandry.SubSkill.HiddenBounty.Proc");
    }

    /**
     * Whether {@code animal} is off its Husbandry harvest cooldown, consuming the window if it is.
     *
     * <p>Measured in <b>world ticks, not wall-clock milliseconds</b>, which is the right clock for a
     * singleplayer game: a cooldown counted in real time would keep draining while the world is
     * paused in the menu, so a player could stand at a cow, open the pause menu for five minutes and
     * come back to a fresh payout. It also makes the arithmetic testable without a fake clock.
     *
     * <p>A negative elapsed time counts as elapsed. The world's clock can legitimately move
     * backwards ({@code /time set}, or an animal led into a dimension keeping its own count), and
     * the failure mode of ignoring that is the worst one available — the animal would be locked out
     * of paying anything ever again, silently.
     */
    private static boolean harvestCooldownElapsed(HusbandryManager husbandry, Entity animal) {
        // Herdsman's Call's cooldown-bypass half. Placed here rather than at the two call sites so
        // it cannot be wired into milking and forgotten for brushing, and it deliberately does NOT
        // stamp the animal's timestamp: a bypassed harvest leaves the ordinary cooldown exactly
        // where it was, so blowing the horn over a herd cannot also reset every animal's clock and
        // hand the player a second full round the moment the ability ends.
        if (husbandry.isHerdsmansCallActive()) {
            return true;
        }
        final int seconds = husbandry.getHarvestCooldownSeconds();
        if (seconds <= 0) {
            return true; // Gate configured off.
        }
        final long now = animal.level().getGameTime();
        final Long lastAward = MetadataStore.get(animal, HARVEST_COOLDOWN_KEY, Long.class);
        if (lastAward != null) {
            final long elapsed = now - lastAward;
            if (elapsed >= 0 && elapsed < (long) seconds * Misc.TICK_CONVERSION_FACTOR) {
                return false;
            }
        }
        MetadataStore.set(animal, HARVEST_COOLDOWN_KEY, now);
        return true;
    }

    // =============================================================================================
    // Task D: Selective Breeding + Brood — beginSelectiveBreeding/endSelectiveBreeding/
    // applySelectiveBreedingBias (foal stat bias) and onEggHatchRoll/onFullClutchRoll (hatched
    // chicks, sometimes four at once). Ports the Fabric original's stage 5 sections onto the
    // Mojang-mapped 1.21.1 names — see neoforge.mixin.AbstractHorseChildAttributesMixin /
    // ThrownEggHatchMixin for the seams these methods are called from.
    // =============================================================================================

    /**
     * The Selective Breeding bias in force for the breeding currently being resolved, or
     * {@code null} outside one.
     *
     * <p><b>This exists because vanilla's inheritance roll is {@code static} and holds no
     * player.</b> {@code AbstractHorse#createOffspringAttribute} is where a foal's health, speed and
     * jump strength are actually decided, and it takes five numbers and a {@code RandomSource} —
     * there is nobody in it to ask. So the bias is computed once, at the one point on the path that
     * <em>is</em> an instance method on a parent, and read back inside the static call.
     *
     * <p>Same {@link ThreadLocal} HEAD/RETURN shape as {@link #PLAYER_INTERACTION} and
     * {@link #SPREADING_LOVE}, and for the same reason: the whole window is one synchronous call on
     * the server thread.
     *
     * <p>It holds the breeder's <b>manager</b> rather than a pre-computed bias so that the biasing
     * arithmetic lives in exactly one place — {@link HusbandryManager#applyStatBias} — where it is
     * MC-free and unit-tested. Two copies of a balance formula is how the two halves drift apart, and
     * re-reading the config three times per breeding (once per inherited attribute) costs nothing.
     */
    private static final ThreadLocal<HusbandryManager> SELECTIVE_BREEDING = new ThreadLocal<>();

    /**
     * A horse-family pair is about to roll their foal's stats: work out whose Selective Breeding
     * applies.
     *
     * <p>Called from {@code AbstractHorseChildAttributesMixin}. Either parent will do — vanilla sets
     * the loving player ({@code Animal#getLoveCause}) on whichever animal was fed, and it only
     * reaches breeding when at least one has one. Resolving the bias here rather than in the static
     * call is the whole point of the stash.
     *
     * @param parent one parent, whose {@code setOffspringAttributes} is the call this stash brackets
     * @param mate   the other parent
     */
    public static void beginSelectiveBreeding(Animal parent, Animal mate) {
        final HusbandryManager husbandry = husbandryOfBreeder(parent, mate);
        if (husbandry == null) {
            return; // AI-driven or command-driven breeding: nobody's sub-skill applies.
        }
        SELECTIVE_BREEDING.set(husbandry);
    }

    /** The breeding has finished rolling its stats. Called from the mixin's {@code RETURN} injector. */
    public static void endSelectiveBreeding() {
        SELECTIVE_BREEDING.remove();
    }

    /**
     * Apply the stashed bias to one rolled offspring stat.
     *
     * <p>Called from {@code AbstractHorseChildAttributesMixin}, three times per breeding — once per
     * attribute {@code AbstractHorse#setOffspringAttribute} inherits. Returns {@code rolled}
     * untouched when no breeding is in flight, which is the common case by a wide margin: this same
     * static method runs for every horse bred anywhere in the world, including with no player
     * involved.
     */
    public static double applySelectiveBreedingBias(double rolled, double min, double max) {
        final HusbandryManager husbandry = SELECTIVE_BREEDING.get();
        return husbandry == null ? rolled : husbandry.applyStatBias(rolled, min, max);
    }

    /**
     * The Husbandry manager of whichever parent vanilla credits with the breeding, or {@code null}.
     *
     * <p><b>No Fox/Turtle-style per-species bypass applies here</b> — unlike the breed-XP seam
     * {@link #onAnimalsBred} reaches through {@code BredAnimalsTrigger#trigger} (which foxes and
     * turtles skip entirely by re-implementing their own breeding sequence),
     * {@code setOffspringAttributes} is declared exactly once, on {@code AbstractHorse}, and nothing
     * in the horse family overrides it (confirmed: no subclass in this jar declares its own
     * {@code setOffspringAttributes}). Every horse-family breeding — horse, donkey, mule, zombie
     * horse, skeleton horse — reaches this same method, so there is no enumeration gap the way the
     * breed-XP funnel had one. Positively confirmed by
     * {@code HusbandryListenerSelectiveBreedingTest}, not assumed by analogy.
     */
    private static @Nullable HusbandryManager husbandryOfBreeder(Animal parent, Animal mate) {
        for (Animal candidate : new Animal[] {parent, mate}) {
            if (candidate == null) {
                continue;
            }
            final ServerPlayer breeder = candidate.getLoveCause();
            if (breeder != null) {
                final HusbandryManager husbandry = husbandryOf(breeder);
                if (husbandry != null) {
                    return husbandry;
                }
            }
        }
        return null;
    }

    /**
     * {@code Brood}: rescue a thrown egg vanilla was about to waste.
     *
     * <p>Called from {@code ThrownEggHatchMixin}. Returning {@code 0} makes vanilla take its own
     * hatch branch, so Brood's chance <em>adds to</em> the vanilla 1-in-8 rather than replacing it.
     *
     * @param egg  the thrown egg, whose owner is the thrower
     * @param roll vanilla's own {@code nextInt(8)}; {@code 0} already means "hatch"
     * @return {@code roll}, or {@code 0} to force a hatch
     */
    public static int onEggHatchRoll(Entity egg, int roll) {
        if (roll == 0) {
            return roll; // Vanilla is already hatching it; nothing to add.
        }
        final HusbandryManager husbandry = husbandryOfThrower(egg);
        return husbandry != null && husbandry.rollEggHatch() ? 0 : roll;
    }

    /**
     * {@code Brood}: turn a hatch into vanilla's rare full clutch.
     *
     * <p>Called from {@code ThrownEggHatchMixin}. <b>The hatched chick carries no {@code BRED_BY}
     * marker</b> either way — nothing on this path, or on {@code ThrownEgg#onHit} itself, ever calls
     * {@code setData(McMMOAttachments.BRED_BY, ...)} on the spawned {@code Chicken}. Brood pays no
     * XP and marks no chick, deliberately: a hopper under a coop is fully AFK income (laying is a
     * passive timer), so marking the hatched chick would have quietly turned that same AFK egg farm
     * into a raise-XP farm twenty minutes later. Both properties are pinned by
     * {@code HusbandryListenerBroodTest}.
     *
     * @param egg  the thrown egg
     * @param roll vanilla's own {@code nextInt(32)}; {@code 0} already means "four chicks"
     * @return {@code roll}, or {@code 0} to force a full clutch
     */
    public static int onFullClutchRoll(Entity egg, int roll) {
        if (roll == 0) {
            return roll;
        }
        final HusbandryManager husbandry = husbandryOfThrower(egg);
        return husbandry != null && husbandry.rollMultipleChicks() ? 0 : roll;
    }

    /**
     * The Husbandry manager of whoever threw a projectile, or {@code null}.
     *
     * <p>The owner check is also the dispenser gate: eggs are dispensable in vanilla, and a dispensed
     * egg has no player owner. {@code Projectile#getOwner()} is confirmed present in this jar (see
     * {@code ThrownEggHatchMixin}'s own javadoc for the {@code javap} check) — the spec's one
     * flagged-unverified item for this seam.
     */
    private static @Nullable HusbandryManager husbandryOfThrower(Entity projectile) {
        if (!(projectile instanceof net.minecraft.world.entity.projectile.Projectile thrown)) {
            return null;
        }
        return thrown.getOwner() instanceof ServerPlayer thrower ? husbandryOf(thrower) : null;
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
