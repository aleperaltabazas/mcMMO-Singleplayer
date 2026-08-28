package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.SubSkillType;
import com.gmail.nossr50.datatypes.treasure.FishingTreasure;
import com.gmail.nossr50.datatypes.treasure.FishingTreasureBook;
import com.gmail.nossr50.datatypes.treasure.ShakeTreasure;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.CombatUtils;
import com.gmail.nossr50.platform.ItemSpecBuilder;
import com.gmail.nossr50.platform.PlatformItem;
import com.gmail.nossr50.skills.fishing.FishingManager;
import com.gmail.nossr50.skills.fishing.FishingManager.MasterAnglerWaitTimes;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.skills.RankUtils;
import com.gmail.nossr50.util.text.ConfigStringUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * <b>PORT (NeoForge):</b> the catch/treasure/overfishing arm of the Fabric original's
 * {@code fabric.listeners.FishingListener} (deleted from this repo's {@code fabric/} tree at commit
 * {@code d28422305}; recoverable at {@code mc/1.21.1} commit {@code ef5fd3d1a~1}), ported to official
 * mappings and driven by {@link com.gmail.nossr50.neoforge.mixin.FishingHookRetrieveMixin} and
 * {@link com.gmail.nossr50.neoforge.mixin.FishingHookWaitTimeMixin}.
 *
 * <p>See docs/superpowers/specs/2026-08-28-fishing-listener-design.md for the full design rationale,
 * including why vanilla's own {@code ItemFishedEvent} is not used (its drop list is a construction-
 * time copy that NeoForge's own spawn loop never reads — mutating it is a silent no-op).
 *
 * <p>Vanilla fires no fishing event; {@code FishingHookRetrieveMixin} injects at the
 * {@code CriteriaTriggers.FISHING_ROD_HOOKED.trigger} call inside {@code FishingHook#retrieve} and
 * hands us the caught-loot {@code Collection<ItemStack>} plus the hook. That criterion also fires for
 * the reel-in-a-hooked-entity branch, but there vanilla passes an empty collection — so an empty
 * catch is a graceful no-op.
 *
 * <p>The base XP is keyed on the caught item's material config string
 * ({@code minecraft:cod} → {@code "Cod"}) via {@code ExperienceConfig.getXp(FISHING, ...)}; the
 * MC-free award lives on {@link FishingManager#awardFishingXP(String)}. The anti-exploit gating
 * (spam-throttle {@link FishingManager#isFishingTooOften()} and same-spot
 * {@link FishingManager#processExploiting}/{@link FishingManager#isExploitingFishing()}) is
 * replicated here using the hook's position.
 *
 * <p><b>Treasure Hunter loot is wired</b> ({@link #maybeCatchTreasure}): after the base XP award, we
 * roll {@link FishingManager#rollFishingTreasure} and, on a hit, build the reward with
 * {@link ItemSpecBuilder} and inject it into the same caught-loot collection the mixin handed us —
 * the exact list {@code FishingHook#retrieve} iterates to spawn the reeled-in item entities and orbs
 * (verified against the patched source), so the treasure flies to the player like a normal catch with
 * no bespoke entity-spawn glue. With {@code Extra_Fish} off (the shipped default) the treasure
 * <i>replaces</i> the fish, with it on both are kept; the base catch XP is awarded either way.
 *
 * <p><b>The overfishing punishment is wired</b> ({@link #punishOverfishing}): once a player is past
 * the {@code OverFishLimit} on one spot the catch is confiscated outright, on top of the XP and
 * treasure roll the exploit gate already skipped.
 *
 * <p><b>Shake is wired</b> ({@link #onEntityHooked} + {@link #shearIfWool}): reeling in a hooked mob
 * rolls the Shake sub-skill, spawns its configured drop, and deals mcMMO's own damage — see
 * {@link #onEntityHooked}'s javadoc.
 *
 * <p><b>Ice Fishing is wired</b> ({@link #tryIceFishing} + {@link #sitsOverWater} + {@link #meltIce}):
 * reeling in a hook stuck on an ice sheet over water melts a 3&times;3 hole — see
 * {@link #tryIceFishing}'s javadoc, including its documented "no auto-recast" deviation.
 *
 * <p><b>Master Angler is wired</b> ({@link #resolveWaitCountdown} + {@link #masterAnglerWaitTimes}):
 * see {@link #resolveWaitCountdown}'s javadoc.
 *
 * <p><b>Magic Hunter and enchanted-book treasures are deferred to a later task</b> — this task wires
 * the {@code maybeCatchTreasure} control flow that reaches the enchant hooks, but their bodies are
 * still stubs: {@link #applyBookEnchantment} and {@link #maybeApplyMagicHunter} always return
 * {@code false} — no enchantment applied (Task D). A no-op/false stub means the corresponding perk is
 * simply inert, not broken, until its task lands.
 */
public final class FishingListener {

    private FishingListener() {
    }

    /**
     * Award base Fishing XP to the hook's owner for a fresh catch. A no-op when the catch is empty
     * (reel-in-a-hooked-entity branch), the owner is not a loaded server player, or the anti-exploit
     * gate trips. Called from {@link com.gmail.nossr50.neoforge.mixin.FishingHookRetrieveMixin}.
     *
     * @param hook   the fishing hook being reeled in (source of the owner and cast position)
     * @param caught the items the vanilla loot roll produced for this catch
     */
    public static void onFishCaught(FishingHook hook, Collection<ItemStack> caught) {
        if (caught.isEmpty()) {
            return; // reel-in-a-hooked-entity branch: no fishing loot, no XP (legacy CAUGHT_ENTITY).
        }
        if (!(hook.getPlayerOwner() instanceof ServerPlayer serverPlayer)) {
            return; // client-side / null owner — the authoritative award happens on the server.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return; // data not loaded (e.g. mid-join).
        }
        final FishingManager fishingManager = mmoPlayer.getFishingManager();
        if (fishingManager == null) {
            return;
        }

        // Legacy replaced the vanilla catch in a HIGH-priority handler that ran before the MONITOR
        // one holding the exploit gate and the XP award, so this sits ahead of both — and the base
        // XP below is therefore keyed on the *replacement*, exactly as upstream.
        overrideVanillaTreasures(caught);

        // Anti-exploit gating, faithful to the legacy CAUGHT_FISH handler.
        if (McMMOMod.getExperienceConfig().isFishingExploitingPrevented()) {
            if (fishingManager.isFishingTooOften()) {
                return; // recast spam within the 1s window — no XP.
            }
            fishingManager.processExploiting(hook.getX(), hook.getY(), hook.getZ());
            if (fishingManager.isExploitingFishing()) {
                punishOverfishing(mmoPlayer, caught);
                return; // fishing the same spot past the OverFishLimit — catch confiscated, no XP.
            }
        }

        // Base catch XP is awarded on the *original* caught items, before the treasure roll can replace
        // them below — legacy pays base + treasure XP even when the treasure supplants the fish.
        for (ItemStack stack : caught) {
            if (stack.isEmpty()) {
                continue;
            }
            final String materialConfigString = ConfigStringUtils.getMaterialConfigString(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
            fishingManager.awardFishingXP(materialConfigString);
        }

        maybeCatchTreasure(serverPlayer, mmoPlayer, fishingManager, caught);
    }

    /**
     * {@code Skills.Fishing.Override_Vanilla_Treasures}: turn every non-fish item vanilla's loot table
     * produced into a plain salmon, so mcMMO's Treasure Hunter table is the <i>only</i> source of
     * fished treasure.
     *
     * <p>The four exempt items are legacy's exact list — the four vanilla fish. Rebuilt rather than
     * mutated in place because an {@link ItemStack}'s item cannot be reassigned; {@code caught} is
     * the live list {@code FishingHook#retrieve} iterates, so clearing and refilling it is what
     * reaches the player. A catch that is already all fish is left untouched.
     */
    @VisibleForTesting
    static void overrideVanillaTreasures(Collection<ItemStack> caught) {
        if (!McMMOMod.getGeneralConfig().getFishingOverrideTreasures()) {
            return;
        }

        final List<ItemStack> replacement = new ArrayList<>(caught.size());
        boolean changed = false;
        for (ItemStack stack : caught) {
            if (stack.isEmpty() || isVanillaFish(stack)) {
                replacement.add(stack);
                continue;
            }
            replacement.add(new ItemStack(Items.SALMON, 1));
            changed = true;
        }

        if (changed) {
            caught.clear();
            caught.addAll(replacement);
        }
    }

    /** The four fish legacy's treasure override leaves alone. */
    private static boolean isVanillaFish(ItemStack stack) {
        return stack.is(Items.SALMON) || stack.is(Items.COD)
                || stack.is(Items.TROPICAL_FISH) || stack.is(Items.PUFFERFISH);
    }

    /**
     * The overfishing punishment (legacy's {@code CAUGHT_FISH} exploit branch): tell the player the
     * area is fished out and confiscate the catch.
     *
     * <p>Emptying the loot collection destroys both the already-spawned item entity and suppresses
     * the vanilla XP orb: vanilla spawns the orb <em>inside</em> its loop over this very collection
     * (bytecode-verified in {@code FishingHook#retrieve} — the {@code ExperienceOrb} constructor sits
     * inside the per-item loop), so an emptied catch yields neither items nor orbs. The rod still
     * takes its durability hit and the hook still reels in, exactly as when legacy left the event
     * uncancelled.
     */
    @VisibleForTesting
    static void punishOverfishing(McMMOPlayer mmoPlayer, Collection<ItemStack> caught) {
        NotificationManager.sendPlayerInformationChatOnly(mmoPlayer, "Fishing.ScarcityTip",
                String.valueOf(
                        McMMOMod.getExperienceConfig().getFishingExploitingOptionMoveRange()));
        caught.clear();
    }

    /**
     * Treasure Hunter's vanilla-XP boost: multiply the experience orb a catch drops by the player's
     * loot-tier multiplier ({@code advanced.yml} → {@code Skills.Fishing.VanillaXPMultiplier}).
     *
     * <p>The arithmetic — including legacy's load-bearing "don't modify XP below vanilla values"
     * guard, which is what stops an unranked player's fishing XP being multiplied by zero — lives
     * MC-free on {@link FishingManager#applyVanillaXpBoost(int)}. See that method for why the guard
     * matters.
     *
     * <p>Legacy applied this once per {@code PlayerFishEvent}; vanilla spawns one orb per caught stack,
     * so this runs once per stack. The fishing loot table yields a single stack and the Treasure Hunter
     * path either replaces it or adds one, so the arithmetic matches in practice.
     *
     * <p>Legacy also gated on {@code Permissions.vanillaXpBoost}; singleplayer grants every mcMMO
     * permission (Phase 6, by design), so that gate is dropped as everywhere else.
     *
     * @param hook       the hook whose owner made the catch
     * @param experience vanilla's own orb amount for this catch
     * @return the boosted orb amount, or {@code experience} unchanged when the boost does not apply
     */
    public static int boostVanillaXp(FishingHook hook, int experience) {
        if (!(hook.getPlayerOwner() instanceof ServerPlayer serverPlayer)) {
            return experience; // client-side / null owner.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return experience; // data not loaded (e.g. mid-join).
        }
        final FishingManager fishingManager = mmoPlayer.getFishingManager();
        if (fishingManager == null) {
            return experience;
        }
        return fishingManager.applyVanillaXpBoost(experience);
    }

    /**
     * Roll the Treasure Hunter reward and, on a hit, inject it into {@code caught} (the loot collection
     * vanilla spawns) and award its bonus XP. Ports the treasure half of legacy {@code processFishing}:
     * with {@code Extra_Fish} off the treasure replaces the fish, with it on the fish is kept too.
     *
     * <p>The enchant branch (a book takes its own mechanism, everything else goes through Magic Hunter)
     * is real control flow in this task; only the two methods it calls are stubs until Task D lands —
     * see the class javadoc.
     */
    private static void maybeCatchTreasure(ServerPlayer serverPlayer, McMMOPlayer mmoPlayer,
            FishingManager fishingManager, Collection<ItemStack> caught) {
        final ThreadLocalRandom rng = ThreadLocalRandom.current();
        final Optional<FishingTreasure> rolled = fishingManager.rollFishingTreasure(
                rng.nextDouble() * 100.0, luckOfTheSeaLevel(serverPlayer), rng::nextInt);
        if (rolled.isEmpty()) {
            return;
        }

        final Optional<ItemStack> built = ItemSpecBuilder.build(rolled.get().getDrop());
        if (built.isEmpty()) {
            return; // unknown material or potion type (logged by the builder) — no drop, no XP.
        }
        final ItemStack treasureStack = built.get();
        applyRandomWear(treasureStack, rng);

        // A book is enchanted by its own mechanism and never by Magic Hunter — legacy's
        // `treasure instanceof FishingTreasureBook` branch in processFishing. Both paths report
        // whether anything landed, and both send the same notification when it did.
        final boolean enchanted = rolled.get() instanceof FishingTreasureBook book
                ? applyBookEnchantment(serverPlayer, fishingManager, book, treasureStack, rng)
                : maybeApplyMagicHunter(serverPlayer, fishingManager, treasureStack, rng);

        if (enchanted) {
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                    "Fishing.Ability.TH.MagicFound");
        }

        // Extra_Fish off (the shipped default) => the treasure supplants the fish; on => keep both.
        if (!McMMOMod.getGeneralConfig().getFishingExtraFish()) {
            caught.clear();
        }
        caught.add(treasureStack);

        fishingManager.awardFishingTreasureXP(rolled.get().getXp());
    }

    /**
     * <b>Stub — Task D.</b> Enchant a fished {@link FishingTreasureBook}. Always returns {@code false}
     * (no enchantment applied) until Task D lands; {@link #maybeCatchTreasure} still builds and awards
     * the book itself, it just arrives unenchanted in the meantime. Parameter list matches the Fabric
     * original's (retargeted to official mappings) so Task D does not have to touch this call site.
     *
     * @return whether an enchantment was applied (the caller sends the notification if so)
     */
    private static boolean applyBookEnchantment(ServerPlayer serverPlayer,
            FishingManager fishingManager, FishingTreasureBook book, ItemStack treasureStack,
            ThreadLocalRandom rng) {
        return false;
    }

    /**
     * <b>Stub — Task D.</b> Magic Hunter: enchant a caught treasure. Always returns {@code false} (no
     * enchantment applied) until Task D lands. Parameter list matches the Fabric original's
     * (retargeted to official mappings) so Task D does not have to touch this call site.
     *
     * @return whether any enchantment was applied (the caller sends the notification if so)
     */
    private static boolean maybeApplyMagicHunter(ServerPlayer serverPlayer,
            FishingManager fishingManager, ItemStack treasureStack, ThreadLocalRandom rng) {
        return false;
    }

    /**
     * A fished tool/armor piece arrives worn: legacy set a random durability on any damageable treasure.
     * Bukkit durability maps to vanilla damage, so a random damage in {@code [0, maxDamage)} reproduces
     * it. A no-op for non-damageable items.
     */
    private static void applyRandomWear(ItemStack stack, ThreadLocalRandom rng) {
        final int maxDamage = stack.getMaxDamage();
        if (maxDamage > 0) {
            stack.setDamageValue(rng.nextInt(maxDamage));
        }
    }

    /**
     * Shake: a player reels in a mob they hooked, and it drops something. Ports legacy
     * {@code FishingManager#shakeCheck}, reached from the {@code CAUGHT_ENTITY} arm of the legacy
     * {@code PlayerFishEvent} monitor. Called from
     * {@link com.gmail.nossr50.neoforge.mixin.FishingHookRetrieveMixin} at the {@code pullEntity}
     * call inside {@code FishingHook#retrieve} — i.e. <i>before</i> vanilla yanks the mob, which is
     * exactly where CraftBukkit fired that event.
     *
     * <p>Unlike {@link #onFishCaught}, no anti-exploit gate applies: legacy's spam/same-spot checks
     * guard only the {@code CAUGHT_FISH} state.
     *
     * <p>Legacy's trailing {@code setFishingTarget()} is dropped for the same reason Master Angler
     * drops it — it discards the value it computes. Dropped with it: the {@code PLAYER} arm (the
     * player-head owner stamp and the {@code INVENTORY} steal), unreachable in singleplayer where the
     * only player is the angler — an honest collapse, the same call made for Daze and for Unarmed's
     * Disarm/Iron Grip (which were removed outright rather than left as a dead surface; see
     * {@code SubSkillType}).
     *
     * @param hook the hook being reeled in (source of the owner and the hooked entity)
     */
    public static void onEntityHooked(FishingHook hook) {
        if (!(hook.getHookedIn() instanceof LivingEntity target)) {
            return; // legacy canShake's `target instanceof LivingEntity` half (a hooked boat/item).
        }
        if (!(hook.getPlayerOwner() instanceof ServerPlayer serverPlayer)) {
            return; // client-side / null owner.
        }
        if (!(target.level() instanceof ServerLevel level)) {
            return; // the drop spawn and the damage are server-side only.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return; // data not loaded (e.g. mid-join).
        }
        final FishingManager fishingManager = mmoPlayer.getFishingManager();
        if (fishingManager == null || !fishingManager.canShake()
                || !fishingManager.rollShakeSuccess()) {
            return;
        }

        final String entityPath = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath();
        final Optional<ShakeTreasure> rolled = fishingManager.rollShakeTreasure(entityPath,
                ThreadLocalRandom.current().nextInt(100));
        if (rolled.isEmpty()) {
            return; // this mob has no configured drops, or the roll cleared them all.
        }
        final Optional<ItemStack> built = ItemSpecBuilder.build(rolled.get().getDrop());
        if (built.isEmpty()) {
            return; // unknown material or potion type (logged by the builder) — no drop, no damage.
        }
        // Built before shearing so an unresolvable material can never shear a sheep for nothing.
        // Legacy could not hit that case (it held a real ItemStack from config load), so this is
        // order-only.
        if (!shearIfWool(target, rolled.get().getDrop().getMaterialId())) {
            return; // an already-sheared sheep: legacy bails entirely — no drop, no damage, no XP.
        }

        final ItemEntity drop = new ItemEntity(level, target.getX(), target.getY(), target.getZ(),
                built.get());
        drop.setDefaultPickUpDelay(); // Bukkit's World#dropItem behaviour.
        level.addFreshEntity(drop);

        // Attributed to the player, so it is mcMMO's own damage: the K1 seam passes it through and it
        // pays no combat XP — the role legacy's CUSTOM_DAMAGE marker played.
        CombatUtils.safeDealDamage(target, FishingManager.shakeDamage(target.getMaxHealth()),
                serverPlayer);
        fishingManager.awardShakeXP();
    }

    /**
     * Legacy's {@code SHEEP} arm of {@code shakeCheck}: shaking wool off a sheep shears it, and a
     * sheep that is already sheared yields nothing. A no-op (returning {@code true}) for any other
     * mob, or for a non-wool drop off a sheep.
     *
     * @param target     the shaken mob
     * @param materialId the rolled drop's registry path, e.g. {@code "white_wool"}
     * @return whether the shake may proceed
     */
    @VisibleForTesting
    static boolean shearIfWool(LivingEntity target, String materialId) {
        if (!(target instanceof Sheep sheep) || !materialId.endsWith("wool")) {
            return true;
        }
        if (sheep.isSheared()) {
            return false;
        }
        sheep.setSheared(true);
        return true;
    }

    /**
     * Ice Fishing: reeling in a rod whose bobber is stuck on an ice sheet over water melts a
     * 3&times;3 hole so the player can fish there. Ports legacy {@code FishingManager#iceFishing},
     * reached from the {@code IN_GROUND} arm of the legacy {@code PlayerFishEvent} monitor.
     *
     * <p><b>Seam.</b> Modern vanilla has no {@code IN_GROUND} state (the {@code State} enum is only
     * {@code FLYING/HOOKED_IN_ENTITY/BOBBING}); that state was a CraftBukkit synthesis fired when the
     * player reeled a bobber resting on solid ground. So this runs at the {@code HEAD} of
     * {@code FishingHook#retrieve} (the reel), the same method the catch/shake seams use, and
     * reconstructs the precondition without the private state: the hook has no hooked entity (that is
     * the Shake path) and is <em>not</em> sitting in water (a bobbing/caught hook is, a stuck-on-ice
     * one is not). Legacy resolved the ice block from {@code player.getTargetBlock(null, 100)} — the
     * player's crosshair, not the bobber — so this raycasts (now {@link net.minecraft.world.entity.Entity#pick})
     * the player likewise.
     *
     * <p><b>Body-of-water check.</b> Legacy required the block 3 below to be water <em>or</em> an icy
     * biome. There is no stable vanilla "icy biome" tag, and a genuine frozen lake/ocean has water
     * within a few blocks of its surface regardless of biome, so this scans the 1–4 blocks under the
     * ice for water and drops the biome-OR shortcut (documented deviation; guards against melting a
     * decorative ice block with nothing beneath it). Legacy's {@code EventUtils.simulateBlockBreak}
     * protection probe is dropped on {@link FishingManager#canIceFish()} as elsewhere.
     *
     * <p><b>Deviation — no auto-recast.</b> Legacy recast the hook in place
     * ({@code EventUtils.callFakeFishEvent}) so the reel that melted the ice immediately dropped the
     * line into the new water. That needs bespoke bobber-spawn glue (a fresh {@code FishingHook} wired
     * to the player's active hook); it is deferred as a UX nicety. The reel still discards its hook as
     * normal, so the player simply casts again into the fresh hole. <b>§G:</b> the whole path is
     * interaction-driven and unverified headless — confirm the reel-on-ice melt and the recast feel.
     *
     * @param hook the hook being reeled in (source of the owner and the world)
     */
    public static void tryIceFishing(FishingHook hook) {
        if (hook.getHookedIn() != null) {
            return; // a hooked mob is the Shake path, not a stuck-on-ice reel.
        }
        if (!(hook.getPlayerOwner() instanceof ServerPlayer serverPlayer)) {
            return; // client-side / null owner.
        }
        if (!(hook.level() instanceof ServerLevel level)) {
            return; // the block reads and the melt are server-side only.
        }
        // Reconstruct legacy's IN_GROUND precondition: a hook in water is bobbing/caught, not stuck.
        if (level.getFluidState(hook.blockPosition()).is(FluidTags.WATER)) {
            return;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return; // data not loaded (e.g. mid-join).
        }
        final FishingManager fishingManager = mmoPlayer.getFishingManager();
        if (fishingManager == null || !fishingManager.canIceFish()) {
            return;
        }

        final HitResult hit = serverPlayer.pick(100.0, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        final BlockPos target = ((BlockHitResult) hit).getBlockPos();
        if (!level.getBlockState(target).is(Blocks.ICE) || !sitsOverWater(level, target)) {
            return;
        }

        // Melt the clicked ice and its 8 horizontal neighbours into water — legacy's 3x3 hole.
        meltIce(level, target);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                final BlockPos neighbour = target.offset(dx, 0, dz);
                if (level.getBlockState(neighbour).is(Blocks.ICE)) {
                    meltIce(level, neighbour);
                }
            }
        }
    }

    /**
     * Whether an ice block sits over a body of water: any of the 1–4 blocks directly beneath it is
     * water (see {@link #tryIceFishing} for why the scan replaces legacy's exact "3 below or icy
     * biome").
     */
    @VisibleForTesting
    static boolean sitsOverWater(ServerLevel level, BlockPos icePos) {
        for (int dy = 1; dy <= 4; dy++) {
            if (level.getFluidState(icePos.below(dy)).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** Turns one block into a water source — legacy {@code block.setType(Material.WATER)}. */
    private static void meltIce(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
    }

    /**
     * Draw the hook's next wait countdown, applying Master Angler when the owner qualifies. Ports
     * legacy {@code processMasterAngler}; called from
     * {@link com.gmail.nossr50.neoforge.mixin.FishingHookWaitTimeMixin} in place of vanilla's own
     * {@code Mth.nextInt(random, 100, 600)}.
     *
     * <p>Any gate miss falls through to an unmodified vanilla draw, so a non-mcMMO player, an
     * unqualified one, or a hook whose owner has left all fish exactly as they would without the mod.
     *
     * @param hook                the hook drawing a new wait (source of the owner)
     * @param random              the hook's own RNG — used for both the vanilla and the reduced draw
     * @param vanillaMinWaitTicks vanilla's minimum wait bound
     * @param vanillaMaxWaitTicks vanilla's maximum wait bound
     * @param lureReductionTicks  the hook's Lure reduction, which vanilla subtracts after this call
     * @return the wait countdown to store, in ticks
     */
    public static int resolveWaitCountdown(FishingHook hook, RandomSource random,
            int vanillaMinWaitTicks, int vanillaMaxWaitTicks, int lureReductionTicks) {
        final MasterAnglerWaitTimes times = masterAnglerWaitTimes(hook, vanillaMinWaitTicks,
                vanillaMaxWaitTicks, lureReductionTicks);
        if (times == null) {
            return Mth.nextInt(random, vanillaMinWaitTicks, vanillaMaxWaitTicks);
        }

        final int drawn = Mth.nextInt(random, times.minWaitTicks(), times.maxWaitTicks());
        // Legacy's fishHook.setApplyLure(false): the Lure reduction has already been folded into the
        // max-wait reduction, so cancel the subtraction vanilla performs immediately after this call
        // rather than letting it apply twice.
        return times.disableLure() ? drawn + lureReductionTicks : drawn;
    }

    /**
     * The Master Angler gates from the legacy {@code PlayerFishEvent} {@code FISHING} arm, plus the
     * owner lookup. Returns {@code null} when Master Angler must not apply.
     *
     * <p>Legacy required a fishing rod in the main hand and skipped entirely when one was also in the
     * off hand ("prevent any potential odd behavior"); both are kept. Legacy read them at cast time and
     * we read them at wait-draw time — see {@code FishingHookWaitTimeMixin} for that deviation. Legacy's
     * trailing {@code setFishingTarget()} call is dropped: it discards the value it computes
     * ({@code getTargetBlock(...)} with no assignment), so it is dead code upstream.
     */
    @VisibleForTesting
    static MasterAnglerWaitTimes masterAnglerWaitTimes(FishingHook hook, int minWaitTicks,
            int maxWaitTicks, int lureReductionTicks) {
        if (!(hook.getPlayerOwner() instanceof ServerPlayer serverPlayer)) {
            return null; // client-side / null owner.
        }
        if (!serverPlayer.getMainHandItem().is(Items.FISHING_ROD)
                || serverPlayer.getOffhandItem().is(Items.FISHING_ROD)) {
            return null;
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(serverPlayer.getUUID());
        if (mmoPlayer == null) {
            return null; // data not loaded (e.g. mid-join).
        }
        final FishingManager fishingManager = mmoPlayer.getFishingManager();
        if (fishingManager == null || !fishingManager.canMasterAngler()) {
            return null;
        }

        final boolean boatBonus = serverPlayer.getVehicle() instanceof Boat;
        return fishingManager.resolveMasterAnglerWaitTimesFromLureTicks(minWaitTicks, maxWaitTicks,
                RankUtils.getRank(mmoPlayer, SubSkillType.FISHING_MASTER_ANGLER), boatBonus,
                lureReductionTicks);
    }

    /**
     * The Luck of the Sea level on the rod the player is fishing with. Reads the main hand when it
     * holds a fishing rod, otherwise the off hand (a catch guarantees the rod is in one of them) —
     * legacy's exact lookup. Enchantment level resolves off the stack's component with no world
     * context needed.
     */
    static int luckOfTheSeaLevel(ServerPlayer player) {
        final ItemStack main = player.getMainHandItem();
        final ItemStack rod = main.is(Items.FISHING_ROD) ? main : player.getOffhandItem();
        return new PlatformItem(rod).getEnchantmentLevel(Enchantments.LUCK_OF_THE_SEA);
    }
}
