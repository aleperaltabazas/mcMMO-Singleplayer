package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformLivingEntity;
import com.gmail.nossr50.platform.SkillAttributeService;
import com.gmail.nossr50.skills.movement.MovementManager;
import com.gmail.nossr50.skills.movement.Medium;
import com.gmail.nossr50.skills.stealth.StealthManager;
import com.gmail.nossr50.skills.unarmored.UnarmoredManager;
import com.gmail.nossr50.util.player.UserManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The per-tick movement sampler behind Agility's Land, Water and Air domains and Stealth's sneak
 * travel (F1).
 *
 * <p>Everything ported in Pass 1 hangs off a discrete event — a block broken, an entity damaged, an
 * item used. Movement is not an event; it is a continuous state, and nothing in the codebase sampled
 * it before this. One {@link ServerTickEvent.Post} sweep measures how far each online player moved,
 * decides which medium they moved through, and hands that to
 * {@link MovementManager#onMovementTick} — or, when they are crouched, to
 * {@link StealthManager#onSneakTick}.
 *
 * <p><b>It has also become the mod's general per-tick per-player sweep</b>, which is a wider job than
 * the name says. Unarmored's Iron Skin rides it (see {@link #applyIronSkin}) purely because it needs
 * a modifier re-derived from live equipment state every tick and this is the only place that runs;
 * it samples no movement at all. Anything else that must be re-derived rather than remembered
 * belongs here too — a second tick sweep would be worse than a slightly misnamed one.
 *
 * <p><b>The two movement skills partition the same tick rather than sharing it.</b>
 * {@link #classifyMedium} returns {@code null} for every sneaking player in every medium, so a tick
 * of travel feeds exactly one of them and no movement state can ever pay twice. That also means the
 * Stealth dispatch must sit <em>above</em> the guard which acts on that {@code null} — see the
 * comment at the call site, which is the single most breakable thing in this class.
 *
 * <p><b>This class deliberately does not compute XP.</b> It owns only the platform-y guards below;
 * the speed clamp that turns distance into credited seconds is MC-free arithmetic in
 * {@link com.gmail.nossr50.skills.movement.MovementXpSettings}, where it can be unit-tested. Getting
 * that split wrong is how the most important formula in the skill ends up buried in a tick handler
 * that no test can reach.
 *
 * <p><b>Anti-AFK / anti-exploit is load-bearing here, not a nicety.</b> A bubble elevator, a
 * soul-sand column, flowing water, a minecart loop and a firework circuit all move a player who is
 * not at the keyboard. Four guards cover all of them:
 * <ul>
 *   <li><b>No vehicles.</b> Boats, horses and minecarts move the player; the player is not moving.</li>
 *   <li><b>No walking and no crouching.</b> Only sprinting, swimming and gliding are media at all —
 *       see {@link #classifyMedium}. Crouching belongs to Stealth, and walking pays nothing so that
 *       merely existing in the world never levels the skill.</li>
 *   <li><b>No teleport-scale deltas.</b> Anything past {@link #TELEPORT_DELTA} in a single tick is a
 *       teleport, a portal or a dimension change, not travel — skipped, and the baseline is reset so
 *       the <em>next</em> tick doesn't bill the whole jump either.</li>
 *   <li><b>Real movement required.</b> Sprinting into a wall, or a rubber-banded key, produces a
 *       delta of roughly zero and pays roughly zero.</li>
 * </ul>
 * Even if one of those leaks, the speed clamp caps what it can be worth per second — the guards and
 * the clamp are independent defences on purpose.
 *
 * <p>Cost: this runs 20×/s per online player. Singleplayer means one player, but it is written as if
 * it were not — no per-tick config parsing (the manager snapshots its tuning once) and no per-tick
 * allocation beyond one {@link Vec3} per player.
 *
 * <p><b>PORT (NeoForge, Phase 2 Task 1):</b> replaces Fabric's {@code ServerTickEvents.END_SERVER_TICK}
 * with {@link ServerTickEvent.Post} on the game bus ({@link NeoForge#EVENT_BUS}) — the same idiom
 * {@code neoforge.McMMOMod} already uses to pump {@code TickScheduler}; both are plain {@code
 * net.neoforged.bus.api.Event} subclasses (not {@code IModBusEvent}), posted by vanilla's server tick
 * loop to the game bus (see {@code McMMOMod}'s class javadoc for the source citation). Fabric's {@code
 * ServerPlayConnectionEvents.DISCONNECT} is replaced with a second, independent listener on {@link
 * PlayerEvent.PlayerLoggedOutEvent} — the same event {@code PlayerSessionListener} already listens on
 * for its own, unrelated cleanup. {@code PlayerEvent}'s own class javadoc states "All children of this
 * event are fired on the {@code NeoForge#EVENT_BUS}" (confirmed in {@code PlayerSessionListener}'s
 * class javadoc too), so this class registers its own handler here rather than piggy-backing on {@code
 * PlayerSessionListener#onQuit} — this keeps the two files decoupled exactly as the two separate Fabric
 * registrations did, and NeoForge allows any number of independent listeners on one event type.
 *
 * <p>Three call sites present in the Fabric original are omitted on this branch (see the omission
 * comments at each former call site below for why): {@code PetFollowTeleport.onPlayerMoved} and
 * {@code PetCombatSweep.tick} (Taming pet following/aggression — not ported), {@code
 * EntityDamageListener.forgetPlayer} in the disconnect handler (Stealth's Assassin combat side-table —
 * {@code EntityDamageListener} not ported), and {@code callTheHerd} (Husbandry's Herdsman's Call —
 * Husbandry out of scope for this port). Because the two pet call sites are gone, {@code LAST_WORLDS}
 * and the {@code sameWorld} computation that fed it — which the Fabric original's own javadoc says only
 * {@code PetFollowTeleport} ever read — are dead weight here and have been dropped.
 */
public final class PlayerMovementTracker {

    private PlayerMovementTracker() {
    }

    /**
     * Per-tick distance past which movement is treated as a teleport rather than travel.
     *
     * <p>Ten blocks per tick is 200 blocks/second — far above anything reachable by sprinting,
     * swimming or even a rocket-boosted dive, and far below a typical teleport. Being generous here
     * is safe because the speed clamp already caps the payout of any single tick; the guard exists to
     * stop a dimension change from registering as a lifetime of travel, not to police speed.
     */
    static final double TELEPORT_DELTA = 10.0;

    /**
     * Movement below this is treated as standing still. Floating-point position jitter and the
     * sub-pixel drift of a player pressed against a wall both land under it.
     */
    private static final double MIN_DELTA = 1.0E-4;

    /**
     * The per-tick horizontal distance below which crouched movement is not credited as sneak-travel.
     *
     * <p>Stealth's anti-AFK gate. {@link #MIN_DELTA} already refuses a player who is not moving at
     * all, which closes plain AFK farming; this closes the variant that {@code MIN_DELTA} does not —
     * a macro nudging the player a hair each tick to keep {@code travelled} true.
     *
     * <p>Set well under crouch-walk speed on purpose. A player walking while crouched covers roughly
     * {@code 0.065} blocks a tick, and genuine travel dips below that routinely — climbing a stair,
     * rounding a corner, the tick a jump lands. At about a third of crouch speed this passes all of
     * those and still refuses sub-perceptible jitter.
     */
    private static final double SELF_DRIVEN_MIN_DELTA = 0.02;

    /** Last tick's position per player. Not a session field, so it can be reset independently. */
    private static final Map<UUID, Vec3> LAST_POSITIONS = new HashMap<>();

    /** Ticks since each player's last Solar Wings repair, so the trickle is rate-limited. */
    private static final Map<UUID, Integer> SOLAR_WINGS_TICKS = new HashMap<>();

    /**
     * Players who currently qualify for Parkour's <b>Snow Walker</b>.
     *
     * <p>A published flag rather than a live gate check, because the consumer is
     * {@code PowderSnowBlock#canWalkOnPowderSnow} — a collision-shape path that runs many times per
     * tick for every entity near powder snow, <em>on both the client and the integrated server</em>.
     * Two things make a direct check there wrong rather than merely slow:
     * <ul>
     *   <li>{@code RankUtils} caches resolved ranks in a plain {@link HashMap}, populated lazily.
     *       Reaching it from the client thread as well as the server thread is a data race on a
     *       non-thread-safe map — the kind that corrupts quietly and is never reproducible.</li>
     *   <li>It would put a config-backed lookup in a hot geometry path, which is the per-tick
     *       config-read trap that already bit Alchemy's Catalysis.</li>
     * </ul>
     * So the answer is derived once per server tick on the server thread and published through a
     * concurrent set that the mixin can read from either side for the cost of a hash lookup. Keyed
     * by UUID because in singleplayer the client's player entity is a different object with the same
     * identity.
     *
     * <p><b>PORT note:</b> the consumer, {@code PowderSnowBlockMixin}, is not ported on this branch
     * yet. This state-tracking half is ported anyway — it is cheap, forward-compatible, and {@link
     * MovementManager#canSnowWalk()} already exists and compiles — but Snow Walker has no in-game
     * effect until that mixin lands.
     */
    private static final Set<UUID> SNOW_WALKERS = ConcurrentHashMap.newKeySet();

    /**
     * Whether this player may walk on powder snow (Parkour → Snow Walker).
     *
     * <p>Read by {@code PowderSnowBlockMixin} from both the client and the server thread (once that
     * mixin is ported — see the field javadoc above).
     */
    public static boolean canWalkOnPowderSnow(@NotNull UUID uuid) {
        return SNOW_WALKERS.contains(uuid);
    }

    /**
     * Whether real server-side movement input has ever been seen, so it is logged exactly once.
     *
     * <p>Deliberately <em>not</em> reset by {@link #clear()}: the question it answers ("does this
     * client actually send input packets?") is a property of the build, not of a world session, and
     * re-logging it on every world load would train people to ignore the line.
     */
    private static final AtomicBoolean MOVEMENT_INPUT_OBSERVED = new AtomicBoolean();

    /**
     * Register the sweep and its teardown. Called once at mod load from
     * {@link com.gmail.nossr50.neoforge.McMMOMod}'s constructor.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(PlayerMovementTracker::onQuit);
    }

    /** Drop all per-player movement state (server stop). */
    public static void clear() {
        LAST_POSITIONS.clear();
        SOLAR_WINGS_TICKS.clear();
        SNOW_WALKERS.clear();
    }

    private static void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return; // client-side event firing: ignore.
        }
        LAST_POSITIONS.remove(player.getUUID());
        SOLAR_WINGS_TICKS.remove(player.getUUID());
        SNOW_WALKERS.remove(player.getUUID());
        // OMISSION: the Fabric original also called EntityDamageListener.forgetPlayer(uuid) here,
        // cleaning up Stealth's Assassin combat side-table from the mod's one per-player disconnect
        // hook. EntityDamageListener is not ported on this branch, so there is no side table to
        // clean up.
        // Belt-and-braces: the modifiers are temporary and never persisted, but leaving nothing
        // behind on a player who is no longer online makes the invariant trivially checkable.
        SkillAttributeService.clearAll(player);
    }

    private static void onServerTick(@NotNull MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                tickPlayer(player);
            } catch (Exception e) {
                // One bad tick must never take the server tick loop down with it, and a movement
                // skill failing silently forever is worse than a log line.
                McMMOMod.LOGGER.error(
                        "Movement tick failed for {}", player.getName().getString(), e);
            }
        }
    }

    /**
     * One player's movement sweep.
     *
     * <p>Package-private rather than private so a test can drive the <em>whole</em> body. That
     * matters more here than usual: the ordering of the Stealth dispatch against the guard below is
     * the actual defect risk, and a test of the sneak-travel predicate on its own would pass with the
     * dispatch deleted entirely.
     */
    static void tickPlayer(@NotNull ServerPlayer player) {
        final UUID uuid = player.getUUID();
        final Vec3 current = player.position();
        final Vec3 previous = LAST_POSITIONS.put(uuid, current);

        // OMISSION: the Fabric original called PetFollowTeleport.onPlayerMoved(player, previous,
        // current, sameWorld) here, ABOVE the missing-profile return below — deliberately, since pet
        // following is a vanilla navigation override, not level-gated and not an mcMMO mechanic, so it
        // had to keep working during a fresh join or a failed profile load. Taming pet-follow-through-
        // portals is not ported on this branch (PetFollowTeleport does not exist here), so there is no
        // call to place.

        // OMISSION: the Fabric original also called PetCombatSweep.tick(player) here, for the same
        // "sits above the missing-profile return" reason. Taming pet aggression/pathing sweep is not
        // ported on this branch (PetCombatSweep does not exist here).

        final McMMOPlayer mmoPlayer = UserManager.getPlayer(uuid);
        if (mmoPlayer == null) {
            return; // Data not loaded yet (mid-join) — nothing to credit and nothing to buff.
        }

        // ⚠️ UNARMORED SITS ABOVE THE AGILITY GUARD BELOW, AND MUST STAY THERE — the same ordering
        // trap the Stealth dispatch further down carries. Iron Skin has nothing to do with Agility;
        // hanging it below a return that fires when the *Agility* manager is missing would make a
        // player's armour depend on an unrelated skill loading, and the failure would be silent.
        applyIronSkin(player, mmoPlayer);

        // OMISSION: the Fabric original also called callTheHerd(player, mmoPlayer) here, ABOVE the
        // Agility guard for the same "unrelated skill must not depend on Agility's manager loading"
        // reason Unarmored's Iron Skin does. Husbandry's Herdsman's Call is out of scope for this
        // port (Husbandry is not ported on this branch), so the method and its call site are both
        // omitted rather than stubbed.

        final MovementManager agility = mmoPlayer.getMovementManager();
        if (agility == null) {
            return;
        }

        final Medium medium = classifyMedium(player);
        // Re-derive the speed buffs from live state every tick rather than tracking whether they are
        // applied. Respawning and leaving the End both build a NEW ServerPlayer, silently dropping
        // every modifier on the old one, so cached "already applied" state goes wrong on the first
        // death while re-deriving self-heals on the next tick.
        applyFleetFooted(player, agility, medium);
        applyLeadLungs(player, agility);
        applySolarWings(player, agility);
        publishSnowWalker(player, agility);

        // Horizontal only, for every medium and for Stealth. The reference speeds are horizontal
        // figures, and it also means a player cannot bill a vertical elytra dive (or a fall) as
        // travel. Measured once here and shared, so the two skills can never disagree about how far
        // the player moved this tick.
        final double distance = previous == null ? 0.0 : horizontalDistance(previous, current);
        final boolean travelled =
                previous != null && distance >= MIN_DELTA && distance <= TELEPORT_DELTA;

        // ⚠️ STEALTH SITS ABOVE THE AGILITY RETURN BELOW, AND MUST STAY THERE. classifyMedium
        // returns null for every sneaking player in every medium (that is how crouched travel is
        // kept from paying Agility), so the guard below is taken on exactly the ticks Stealth cares
        // about. Moved underneath it, Padfoot and sneak XP become dead code that still compiles,
        // boots clean and passes every unit test.
        tickStealth(player, mmoPlayer, distance, travelled);

        if (medium == null || previous == null || player.isPassenger()) {
            // No qualifying medium, no baseline to measure against, or being carried by something
            // else. In the vehicle case the baseline was still refreshed above, so stepping out of a
            // boat does not bill the whole ride.
            return;
        }
        if (!travelled) {
            return;
        }
        agility.onMovementTick(medium, distance);
    }

    /**
     * Keep Unarmored's Iron Skin armour matched to what the player is (not) wearing right now.
     *
     * <p><b>Why a per-tick re-derivation and not an equipment-change hook</b> (D-U3): the skin has to
     * appear and vanish on armour being equipped or removed, on a level-up across a tier breakpoint,
     * on respawn — which builds a <em>new</em> {@code ServerPlayer} and silently discards every
     * modifier on the old one — and on logout. That is four separate lifecycles to get right, and
     * each one of them fails as a permanent free-armour bug rather than as a missing buff. Stating
     * the value the current tick's state implies, every tick, has exactly one of those bugs to get
     * wrong instead of four, and it self-heals on the tick after anything unexpected.
     *
     * <p>Cheap enough to mean it: {@code set} is a no-op when the value is unchanged, which is every
     * tick but the ones where something actually happened.
     *
     * <p>This is the one part of Unarmored that rides F1 at all — the skill is event-driven for XP
     * and needs no movement sampling. It is here because this is the mod's only per-tick per-player
     * sweep, not because it has anything to do with movement.
     */
    private static void applyIronSkin(@NotNull ServerPlayer player,
            @NotNull McMMOPlayer mmoPlayer) {
        final UnarmoredManager unarmored = mmoPlayer.getUnarmoredManager();
        if (unarmored == null) {
            return;
        }
        SkillAttributeService.set(player, SkillAttributeService.Managed.UNARMORED_IRON_SKIN,
                unarmored.getSkinArmorPoints(PlatformLivingEntity.isUnarmored(player)));
    }

    /**
     * Publish whether this player may currently walk on powder snow (Parkour → Snow Walker).
     *
     * <p>Written only when the answer changes, so the steady state costs one hash lookup per tick
     * rather than a write to a shared concurrent set 20 times a second.
     */
    private static void publishSnowWalker(@NotNull ServerPlayer player,
            @NotNull MovementManager agility) {
        final UUID uuid = player.getUUID();
        if (agility.canSnowWalk()) {
            SNOW_WALKERS.add(uuid);
        } else {
            SNOW_WALKERS.remove(uuid);
        }
    }

    /** Horizontal distance between two positions, in blocks. */
    private static double horizontalDistance(@NotNull Vec3 previous, @NotNull Vec3 current) {
        final double dx = current.x - previous.x;
        final double dz = current.z - previous.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Stealth's half of the sweep: keep Padfoot's speed buff matched to live state, and credit a tick
     * of qualifying sneak-travel.
     *
     * <p>Padfoot is set every tick including to {@code 0}, for the same reason Fleet Footed is — the
     * buff must die the instant the condition does, and a respawn silently discards it, so re-deriving
     * beats remembering. It is gated on {@link ServerPlayer#isShiftKeyDown()} anyway even though
     * vanilla only reads {@code sneaking_speed} while crouched: relying on that would mean a modifier
     * sitting on a walking player forever, which is indistinguishable from a leak the first time
     * somebody debugs this.
     */
    private static void tickStealth(@NotNull ServerPlayer player,
            @NotNull McMMOPlayer mmoPlayer, double distance, boolean travelled) {
        final StealthManager stealth = mmoPlayer.getStealthManager();
        if (stealth == null) {
            return;
        }

        final boolean sneaking = player.isShiftKeyDown();
        SkillAttributeService.set(player, SkillAttributeService.Managed.STEALTH_PADFOOT,
                sneaking ? stealth.getPadfootSpeedBonus() : 0.0);

        if (travelled && sneaking && qualifiesAsSneakTravel(player, distance)) {
            stealth.onSneakTick(distance);
        }
    }

    /**
     * Whether this tick of crouched movement is the player actually sneaking somewhere.
     *
     * <p>The wiki jokes that Sneaking is "sticky keys op", which is precisely the design brief: this
     * gate is the skill, and everything else about Stealth is downstream of it. Four conditions, each
     * closing a different farm:
     * <ul>
     *   <li><b>Ground only</b> (ruled 2026-07-27). Crouch-swimming moves at roughly 3 b/s against a
     *       1.295 b/s reference, so it would sit permanently at the speed clamp and make "hold shift
     *       in a water current" the single best way to level the skill. Excluding water <em>and</em>
     *       requiring ground closes it twice over; the same exclusion is why Agility stopped paying
     *       for crouched travel, and Stealth must not reopen the leak Agility closed.</li>
     *   <li><b>No gliding</b> — implied by requiring ground, but a crouched elytra descent is exactly
     *       the sort of thing that gets rediscovered later, so it is stated rather than inferred.</li>
     *   <li><b>No vehicles.</b> A boat, horse or minecart moves the player; the player is not
     *       moving.</li>
     *   <li><b>A real movement key must be held.</b> This is the one a position delta cannot give
     *       you: it separates "walking forward" from "being carried" by flowing water, a piston loop
     *       or a bubble column, none of which need a hand on the keyboard.</li>
     * </ul>
     */
    static boolean qualifiesAsSneakTravel(@NotNull ServerPlayer player, double distance) {
        if (player.isPassenger() || player.isFallFlying() || player.isInWater()
                || !player.onGround()) {
            return false;
        }
        return !requiresMovementInput() || isSelfDrivenTravel(distance);
    }

    /** Whether the anti-AFK input gate is armed (the {@code ExploitFix} escape hatch). */
    private static boolean requiresMovementInput() {
        final ExperienceConfig config = McMMOMod.getExperienceConfig();
        return config == null || config.isSneakInputRequired();
    }

    /**
     * Whether this tick's crouched movement is fast enough to be the player actually travelling.
     *
     * <p>This gate reads displacement, not key state, on purpose: nothing on this branch (NeoForge,
     * 1.21.1 only) offers a live server-side view of which movement keys a client currently has held,
     * so the stricter question — "is a directional key down right now" — is not available to ask.
     * (The Fabric original carried version-band-specific commentary about {@code
     * ServerPlayerEntity#updateInput} being dead code on certain Fabric bands; that reasoning is
     * Fabric-band-specific and does not apply to this single-version NeoForge branch, so it has been
     * dropped rather than carried over.)
     *
     * <p>What is lost: this cannot tell self-driven movement from being pushed. What is not lost is
     * the exploit the gate exists for — a stuck crouch key produces no displacement, and
     * {@link #SELF_DRIVEN_MIN_DELTA} refuses the jitter-macro variant. The three largest sources of
     * passive movement are already excluded by {@link #qualifiesAsSneakTravel} itself: vehicles,
     * water, and anything not on the ground.
     */
    private static boolean isSelfDrivenTravel(double distance) {
        final boolean moving = distance >= SELF_DRIVEN_MIN_DELTA;
        if (moving && MOVEMENT_INPUT_OBSERVED.compareAndSet(false, true)) {
            McMMOMod.LOGGER.info(
                    "Stealth: qualifying sneak-travel observed — the anti-AFK gate is live.");
        }
        return moving;
    }

    /**
     * Which medium this tick counts as, or {@code null} when the player is not doing anything that
     * earns Agility XP (walking, standing, falling).
     *
     * <p>Exactly one medium per tick, checked most-specialised first — a player can be gliding
     * <em>and</em> touching water, or sprinting <em>and</em> swimming, and paying both would double
     * the rate for a single tick of travel.
     *
     * <p>Public because Second Wind dispatches its body on the same classification: "which Agility
     * domain is this player in right now" must have exactly one answer, and two implementations of
     * that question would eventually disagree.
     *
     * <p><b>Walking is deliberately not a medium.</b> Only sprinting, swimming and gliding pay —
     * ordinary walking pays nothing at all, so simply existing in the world never levels the skill.
     *
     * <p><b>Crouched movement pays nothing either, in every medium.</b> Sneaking already excluded
     * itself on land (you cannot sneak and sprint at once), but it did <em>not</em> in water: holding
     * shift to sink is still {@code isInWater()}, so crouch-swimming used to earn Agility XP.
     * Sneaking is the Stealth skill's sensor, and one movement state must not feed two skills' XP —
     * that is the same double-pay problem the AIR &gt; WATER &gt; LAND priority exists to prevent,
     * one level up.
     *
     * <p>Because Second Wind and Fleet Footed read this same classification, crouching also drops the
     * speed buff and refuses the super ability (without burning its cooldown). Both are wanted:
     * Padfoot and Fleet Footed are the same mechanic on the same attribute and must never be live at
     * once (D-AG5), and this settles that overlap in one place rather than in each consumer.
     */
    public static @Nullable Medium classifyMedium(@NotNull ServerPlayer player) {
        if (player.isPassenger() || player.isShiftKeyDown()) {
            return null;
        }
        if (player.isFallFlying()) {
            return Medium.AIR;
        }
        if (player.isInWater()) {
            return Medium.WATER;
        }
        if (player.isSprinting()) {
            return Medium.LAND;
        }
        return null;
    }

    /**
     * Apply or clear the Fleet Footed speed buff for the medium the player is in right now.
     *
     * <p>Both media are set every tick — including to {@code 0} — so the buff is removed the instant
     * the medium ends rather than lingering until something notices. The air body is not here: elytra
     * flight is velocity-driven with no attribute behind it, so it would live in a glide mixin (not
     * ported on this branch — see the Fabric original's {@code LivingEntityGlideMixin}).
     */
    private static void applyFleetFooted(@NotNull ServerPlayer player,
            @NotNull MovementManager agility, @Nullable Medium medium) {
        SkillAttributeService.set(player, SkillAttributeService.Managed.MOVEMENT_FLEET_FOOTED_LAND,
                medium == Medium.LAND ? agility.getFleetFootedBonus(Medium.LAND) : 0.0);
        SkillAttributeService.set(player, SkillAttributeService.Managed.MOVEMENT_FLEET_FOOTED_WATER,
                medium == Medium.WATER ? agility.getFleetFootedBonus(Medium.WATER) : 0.0);
    }

    /**
     * Top up the player's air while submerged (Lead Lungs).
     *
     * <p>A per-tick top-up rather than a mixin on vanilla's air decrement: it is simpler, it stacks
     * sanely with Respiration (which reduces how often air is spent rather than how much), and if it
     * ever misbehaves the failure mode is "breath is slightly wrong", not "the drowning code is
     * broken". Clamped to the vanilla maximum so it can top up but never overfill.
     */
    private static void applyLeadLungs(@NotNull ServerPlayer player,
            @NotNull MovementManager agility) {
        if (!player.isUnderWater()) {
            return;
        }
        final int topUp = agility.consumeLeadLungsAirTopUp();
        if (topUp <= 0) {
            return;
        }
        final int maxAir = player.getMaxAirSupply();
        final int air = player.getAirSupply();
        if (air < maxAir) {
            player.setAirSupply(Math.min(maxAir, air + topUp));
        }
    }

    /**
     * Slowly repair a worn, damaged elytra in daylight (Solar Wings).
     *
     * <p>Rate-limited hard, and it must stay that way: elytra durability is one of the few real
     * resource pressures in late-game Minecraft, and a generous repair rate deletes it outright.
     * Repairs faster on the ground than in flight, so it is a reason to land rather than a reason to
     * never land.
     */
    private static void applySolarWings(@NotNull ServerPlayer player,
            @NotNull MovementManager agility) {
        final UUID uuid = player.getUUID();
        if (!agility.canSolarWings()) {
            SOLAR_WINGS_TICKS.remove(uuid);
            return;
        }
        final ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !chest.isDamaged()) {
            SOLAR_WINGS_TICKS.remove(uuid);
            return;
        }
        if (!player.level().isDay()
                || !player.level().canSeeSky(player.blockPosition())) {
            return; // Keep the counter: stepping through a tunnel shouldn't reset the progress.
        }

        final int elapsed = SOLAR_WINGS_TICKS.merge(uuid, 1, Integer::sum);
        if (elapsed < agility.getSolarWingsIntervalTicks()) {
            return;
        }
        SOLAR_WINGS_TICKS.put(uuid, 0);

        final int repair = agility.getSolarWingsRepairAmount(player.onGround());
        if (repair > 0) {
            chest.setDamageValue(Math.max(0, chest.getDamageValue() - repair));
        }
    }
}
