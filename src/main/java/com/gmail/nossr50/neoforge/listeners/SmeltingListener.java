package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.skills.cooking.CookingManager;
import com.gmail.nossr50.skills.smelting.SmeltingManager;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.text.ConfigStringUtils;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The K7 Smelting XP hook: awards Smelting XP when a furnace the player owns completes a smelt.
 * Ports {@code fabric.listeners.SmeltingListener} — see
 * docs/superpowers/specs/2026-08-30-cooking-smelting-listener-design.md for the full design
 * rationale.
 *
 * <p>Vanilla has no smelt event, so this has two parts:
 * <ul>
 *   <li><b>Owner tracking</b> — {@link #onUseBlock} records the last player to right-click each
 *       furnace (keyed by {@link BlockPos#asLong()}), mirroring the legacy furnace-owner map.
 *       Without an owner a furnace earns no one XP.</li>
 *   <li><b>Smelt detection</b> — {@code neoforge.mixin.AbstractFurnaceSmeltMixin} injects at the
 *       {@code burn} call inside {@code AbstractFurnaceBlockEntity#serverTick} (only reached when
 *       a smelt completes) and calls {@link #onFurnaceSmelt}.</li>
 * </ul>
 *
 * <p>The smelted <em>input</em> material's registry path ({@code minecraft:iron_ore} → {@code
 * "Iron_Ore"}) is the config string {@code ExperienceConfig.getSmeltingXP} is keyed on; the MC-free
 * award lives on {@link SmeltingManager#awardSmeltingXP(String)}.
 *
 * <p>Two more furnace behaviours ride the same owner map and the same mixin:
 * <ul>
 *   <li><b>Second Smelt</b> ({@link #onSmeltComplete}) — a chance at a second copy of the result,
 *       applied just after vanilla's {@code burn} has merged the first one in;</li>
 *   <li><b>Fuel Efficiency</b> ({@link #boostFuelTime}) — multiplies vanilla's burn time for the
 *       fuel item the furnace is about to consume.</li>
 * </ul>
 *
 * <p><b>All three now serve two skills, not one.</b> A furnace is split by its <em>input</em>: ore
 * is Smelting's, food is Cooking's, and never both. Each of the three checks Smelting first and
 * treats Cooking as the alternative — the same order in all three places, because they are three
 * answers to one question and a furnace that paid Smelting's XP while boosting Cooking's fuel
 * would be incoherent. Cooking's halves are {@code CookingManager#boostFuelTime} (Kitchen
 * Efficiency) and {@code CookingManager#canSecondHelping} (Master Chef). <b>One owner map, two
 * managers</b> — see {@link #owner}.
 *
 * <p><b>Understanding the Art</b> ({@link #beginFurnaceExtract} / {@link #boostVanillaXp}) does
 * <em>not</em> use the owner map: legacy's {@code FurnaceExtractEvent} boosted the vanilla XP of
 * the player doing the extracting, whoever that is. It rides a different pair of seams — the
 * extraction hooks below, driven from Task B's {@code FurnaceResultSlotMixin}, not this class's
 * own mixin.
 *
 * <h2>⚠️ Fuel Efficiency needed a mixin after all — {@code FurnaceFuelBurnTimeEvent} carries no
 * furnace context</h2>
 * The design doc's plan was a plain listener on {@link FurnaceFuelBurnTimeEvent}: confirmed
 * present, fires from every furnace-family burn-time lookup (verified via {@code javap} and by
 * reading {@code EventHooks.getItemBurnTime}'s and {@code IItemStackExtension#getBurnTime}'s
 * bundled sources against {@code build/moddev/artifacts/neoforge-21.1.248-merged.jar}). But
 * reading {@link FurnaceFuelBurnTimeEvent}'s own bundled source disproves the "no mixin needed"
 * half of that plan: the event carries only {@code getItemStack()} (the <em>fuel</em>, not the
 * furnace's input), {@code getRecipeType()} and the burn time itself — no {@code BlockPos}, no
 * block entity, nothing to resolve "which furnace, which owner" with. That is a strictly narrower
 * event than Alchemy's {@code PotionBrewEvent}, which at least handed over a copy of the stand's
 * slots.
 *
 * <p>The fix mirrors Alchemy's {@code BREW_POSITION} bridge:
 * {@code neoforge.mixin.AbstractFurnaceGetBurnDurationMixin} injects at the {@code HEAD} of
 * {@code AbstractFurnaceBlockEntity#getBurnDuration(ItemStack)} — an <em>instance</em> method,
 * so {@code this} is the furnace block entity itself, exposing both {@code getBlockPos()} and the
 * live input slot via {@code getItem(AbstractFurnaceBlockEntity.SLOT_INPUT)} — and stashes both
 * plus the fuel stack's own identity into {@link #FUEL_BURN_CONTEXT} via
 * {@link #rememberFuelBurnContext}. {@link #onFurnaceFuelBurnTime} then consumes it.
 *
 * <p><b>Identity safety net, and why it is load-bearing here (unlike {@code BREW_POSITION}):</b>
 * {@code getBurnDuration} is not the only call site that can trigger
 * {@link FurnaceFuelBurnTimeEvent} — {@code AbstractFurnaceBlockEntity#canPlaceItem} calls
 * {@code ItemStack#getBurnTime(RecipeType)} <em>directly</em>, bypassing this mixin entirely, and
 * {@code getBurnDuration} itself short-circuits (no event at all) when handed an empty fuel stack.
 * Either path can leave {@link #FUEL_BURN_CONTEXT} stale — set by one furnace's tick, never
 * consumed, then silently read by an unrelated {@code canPlaceItem} check on a different furnace.
 * {@link #onFurnaceFuelBurnTime} therefore checks {@code context.fuel() == event.getItemStack()}
 * (reference identity, verified reliable: {@code IItemStackExtension#getBurnTime} passes
 * {@code self()} — the same {@code ItemStack} instance — straight through to
 * {@code EventHooks.getItemBurnTime} with no copy in between) before trusting the stashed position
 * and input, exactly the {@code EntityDamageListener#PRE_ARMOR_DAMAGE} shape this port uses
 * whenever a mixin has to bridge context to an event that cannot carry it.
 *
 * <h2>⚠️ Reload-listener ordering for {@link #indexSmeltedOreProducts} — verified, not assumed</h2>
 * The design doc flagged NeoForge's reload-listener ordering as needing verification before
 * relying on {@code AddReloadListenerEvent#addListener} to rebuild the index after a mid-session
 * data-pack reload. Reading {@code ReloadableServerResources#loadResources}'s bundled source
 * settles it: the listener list is built as
 * {@code [tagManager, recipes, functionLibrary, advancements]} and mod listeners added via
 * {@code AddReloadListenerEvent} are appended <em>after</em> that fixed prefix — always last.
 * {@code SimpleReloadInstance}'s constructor (also read from its bundled source) chains every
 * listener's <em>apply</em> phase (the {@code PreparationBarrier} passed to {@code reload()}) to
 * the completion of the <em>previous</em> listener in list order, even though every listener's
 * <em>prepare</em> phase runs in parallel. So a listener registered through
 * {@code AddReloadListenerEvent} has its {@code apply} phase strictly ordered after
 * {@code RecipeManager}'s own apply — by the time {@link #register()}'s reload listener runs,
 * the just-reloaded recipe set is already live. No fallback to {@code ServerStartedEvent} alone
 * was needed; {@link #register()} still uses {@link ServerStartedEvent} for the initial index
 * (matching the design doc's rationale — it keeps the extraction path allocation-free and logs the
 * scan result at boot) and {@link AddReloadListenerEvent} for every reload thereafter, covering
 * both cases NeoForge's single reload-listener mechanism was expected to.
 *
 * <p><b>Port caveat:</b> owners are keyed by block position only (not dimension). In singleplayer
 * the owner is always the one player, so a same-coordinates furnace in another dimension awards
 * the same person — harmless. The map is cleared on server stop via {@link #clearOwners()}.
 */
public final class SmeltingListener {

    /** Furnace {@link BlockPos#asLong()} → owner UUID. See the class doc for the single-key caveat. */
    private static final Map<Long, UUID> FURNACE_OWNERS = new ConcurrentHashMap<>();

    /**
     * The Understanding the Art multiplier for the furnace extraction currently in flight, or
     * unset when there is none. Set at the head of {@code FurnaceResultSlot#checkTakeAchievements}
     * (Task B) and cleared at its return, so it is live for exactly the nested
     * {@code awardUsedRecipesAndPopExperience} call that spawns the orbs.
     */
    private static final ThreadLocal<Integer> VANILLA_XP_MULTIPLIER = new ThreadLocal<>();

    /**
     * The furnace position, fuel-stack identity and live input-slot stack for the
     * {@code getBurnDuration} call currently in flight on this thread, bridged from
     * {@code AbstractFurnaceGetBurnDurationMixin} to {@link #onFurnaceFuelBurnTime}. See this
     * class's own javadoc (the Fuel Efficiency section) for the full rationale, including why the
     * fuel-stack identity check is load-bearing rather than decorative.
     */
    private record FuelBurnContext(BlockPos pos, ItemStack fuel, ItemStack input) {
    }

    private static final ThreadLocal<FuelBurnContext> FUEL_BURN_CONTEXT = new ThreadLocal<>();

    /**
     * The items that come out of a smelting recipe whose input is an ore block — the port of
     * legacy {@code ItemUtils.isSmelted}. Derived once from the loaded recipe set (see
     * {@link #indexSmeltedOreProducts}) rather than per extraction, and rebuilt whenever data
     * packs are re-read. Empty until the server has started, which fails safe: no boost, vanilla
     * XP.
     */
    private static volatile Set<Item> smeltedOreProducts = Set.of();

    private SmeltingListener() {
    }

    /**
     * Register the owner-tracking interaction hook, the smelted-ore-product indexing hooks (see
     * step 3 in the class javadoc), and the Fuel Efficiency bridge's event half. Called once at
     * mod load from {@code McMMOMod}.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(SmeltingListener::onUseBlock);
        // The index is built the moment recipes are available rather than lazily on first
        // extraction: it is a one-off pass, it keeps the extraction path allocation-free, and it
        // means the boot log says out loud whether the scan found anything.
        NeoForge.EVENT_BUS.addListener(SmeltingListener::onServerStarted);
        // A data-pack reload can add, remove or re-target smelting recipes, so rebuild from the
        // new set. See this class's javadoc for why the reload-listener apply-phase ordering makes
        // this safe.
        NeoForge.EVENT_BUS.addListener(SmeltingListener::onAddReloadListener);
        NeoForge.EVENT_BUS.addListener(SmeltingListener::onFurnaceFuelBurnTime);
    }

    /**
     * Drop all per-world Smelting state — tracked furnace owners, the derived smelted-ore-product
     * index, and any in-flight thread-local bridges — so the next world starts clean. Called on
     * server stop.
     */
    public static void clearOwners() {
        FURNACE_OWNERS.clear();
        smeltedOreProducts = Set.of();
        VANILLA_XP_MULTIPLIER.remove();
        FUEL_BURN_CONTEXT.remove();
    }

    /**
     * Right-click a furnace → remember this player as its owner for XP-award purposes.
     *
     * <p>Package-private rather than private so {@code CookingListener}/tests can claim a furnace
     * the way a player does.
     */
    static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        final Player player = event.getEntity();
        if (!(player instanceof ServerPlayer)) {
            return; // client-side fire — the server copy does the bookkeeping.
        }
        final Level world = event.getLevel();
        final BlockPos pos = event.getPos();
        if (world.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity) {
            FURNACE_OWNERS.put(pos.asLong(), player.getUUID());
        }
        // observe only; never cancel opening the furnace.
    }

    /**
     * Award the furnace's owner for a freshly finished cook — <b>Smelting XP for an ore, Cooking
     * XP for a food</b>. Called from {@code AbstractFurnaceSmeltMixin}. A no-op when the furnace
     * has no tracked owner, the owner isn't loaded, or the input carries no configured XP under
     * either skill.
     *
     * <h2>⚠️ The two skills are mutually exclusive here, and the order is Smelting first</h2>
     * Both are keyed on the same thing — the furnace's <em>input</em> — so nothing in the config
     * format stops an operator listing one item under both {@code Experience_Values.Smelting} and
     * {@code Experience_Values.Cooking.Cook}. Left ambiguous, one smelt would pay twice.
     *
     * <p><b>Smelting wins, and Cooking is the {@code else}.</b> That is not an arbitrary
     * tie-break: it is the same gate {@link #boostFuelTime} has enforced since the Smelting port,
     * whose comment reads <em>"so cooking food burns at vanilla speed"</em>. Writing the XP branch
     * the other way round would leave the XP and the fuel bonus disagreeing about which skill owns
     * a given input.
     *
     * @param world the furnace's world — its time is the clock Cooking's rate cap is measured on
     * @param pos   the furnace position
     * @param input the item that was smelted (the input slot's stack, read before it is consumed)
     */
    public static void onFurnaceSmelt(ServerLevel world, BlockPos pos, ItemStack input) {
        if (input.isEmpty()) {
            return;
        }
        final McMMOPlayer owner = owner(pos);
        if (owner == null) {
            return;
        }
        final String inputConfigString = materialConfigString(input);
        if (SmeltingManager.isSmeltable(inputConfigString)) {
            owner.getSmeltingManager().awardSmeltingXP(inputConfigString);
            return; // An ore. Smelting's, and never also Cooking's.
        }
        final CookingManager.CookAward award =
                owner.getCookingManager().onCook(inputConfigString, world.getGameTime());
        if (award.capReached()) {
            // Once per window, not once per cook: a rate cap that pays nothing and says nothing is
            // indistinguishable from a broken skill, and an eight-smoker array would spam the chat.
            NotificationManager.sendPlayerInformation(owner, NotificationType.SUBSKILL_MESSAGE,
                    "Cooking.CookRateCap.Reached");
        }
    }

    /**
     * Second Smelt: give the owner a chance at one extra copy of a just-finished smelt. Called
     * from {@code AbstractFurnaceSmeltMixin} at the point vanilla has already merged the smelt
     * result into the output slot.
     *
     * <p>The room check reads the output stack <em>after</em> that merge — see
     * {@link SmeltingManager#hasRoomForSecondSmelt} for why that is the same test legacy made
     * against the pre-merge count. The stack is the furnace's live inventory entry, so
     * {@code output.grow(1)} mutates the furnace exactly as vanilla's own {@code burn} does, and
     * vanilla's end-of-tick {@code setChanged} covers it.
     *
     * <p><b>Deviation (deliberate, and non-observable under the shipped configs):</b> legacy
     * reached this seam only after {@code onFurnaceSmelt}'s smeltable gate on the furnace's
     * <em>input</em>. We cannot re-check that here — {@code burn} has already decremented the
     * input, which is empty whenever the last of it was just consumed — so the only gate is the
     * {@code Bonus_Drops.Smelting} entry for the <em>result</em>. Those two coincide for every
     * vanilla furnace recipe.
     *
     * <h2>⚠️ Master Chef shares this seam, and the dispatch is on TABLE MEMBERSHIP, not on a roll</h2>
     * Cooking's Master Chef is the same mechanic pointed at food, reading the same output slot on
     * the same block, and nothing in the config format stops an operator listing one result under
     * both {@code Bonus_Drops.Smelting} and {@code Bonus_Drops.Cooking}. <b>Smelting wins</b> —
     * the same order {@link #onFurnaceSmelt} and {@link #boostFuelTime} enforce.
     *
     * <p>The tie-break is resolved by asking which table the result is <em>in</em>, before anybody
     * rolls. Rolling Smelting's dice first and falling through to Cooking's on a miss would look
     * equivalent and is not: an item in both tables would get <b>two chances at one bonus</b>, and
     * the extra chance would be invisible in every log. One membership test, then exactly one
     * roll.
     *
     * @param output the furnace's output slot stack, holding the freshly smelted result
     */
    public static void onSmeltComplete(BlockPos pos, ItemStack output) {
        if (output.isEmpty()) {
            return; // burn always fills this; guard anyway so an odd recipe can't NPE the tick.
        }
        if (!SmeltingManager.hasRoomForSecondSmelt(output.getCount(), output.getMaxStackSize())) {
            return; // no room for the extra item — checked before the RNG, as legacy did.
        }
        final String resultConfigString = materialConfigString(output);
        final boolean smeltingResult = SmeltingManager.isSecondSmeltMaterial(resultConfigString);
        if (!smeltingResult && !CookingManager.isMasterChefMaterial(resultConfigString)) {
            return; // In neither table — cheaper than resolving the owner, so it goes first.
        }
        final McMMOPlayer owner = owner(pos);
        if (owner == null) {
            return;
        }
        final boolean bonus = smeltingResult
                ? owner.getSmeltingManager().canSecondSmelt(resultConfigString)
                : owner.getCookingManager().canSecondHelping(resultConfigString);
        if (bonus) {
            output.grow(1);
        }
    }

    /**
     * Fuel Efficiency <em>and</em> Kitchen Efficiency: multiply the burn time of the fuel item a
     * furnace is about to consume, for the furnace's owner — including its gate that the furnace
     * must actually be smelting something mcMMO counts as smeltable.
     *
     * <h2>⚠️ Cooking is NOT the blanket {@code else} of the smeltable gate</h2>
     * A furnace's input being non-smeltable does not make it food: <b>sand, cobblestone, logs,
     * clay balls, cactus, wet sponge and raw chorus fruit are all non-smeltable furnace
     * inputs</b>, and every one of them would have collected Cooking's fuel bonus for nothing. The
     * branch is therefore an explicit {@link CookingManager#isCookable} test, not a negation.
     *
     * <p>The two are mutually exclusive by construction — an input is either priced under
     * {@code Experience_Values.Smelting} or under {@code Experience_Values.Cooking.Cook}, and
     * Smelting is checked first, which is the same order {@link #onFurnaceSmelt} and
     * {@link #onSmeltComplete} use. All three furnace behaviours agree about who owns an input.
     *
     * @param burnTime vanilla's own burn time for the fuel
     * @param input    the furnace's input slot stack (what it is about to smelt)
     * @return the boosted burn time, or {@code burnTime} unchanged when the bonus does not apply
     */
    public static int boostFuelTime(int burnTime, BlockPos pos, ItemStack input) {
        if (burnTime <= 0 || input.isEmpty()) {
            return burnTime;
        }
        final String inputConfigString = materialConfigString(input);
        final boolean smeltable = SmeltingManager.isSmeltable(inputConfigString);
        if (!smeltable && !CookingManager.isCookable(inputConfigString)) {
            return burnTime; // Neither skill's business — sand, cobblestone, a log. Vanilla speed.
        }
        final McMMOPlayer owner = owner(pos);
        if (owner == null) {
            return burnTime;
        }
        return smeltable
                ? owner.getSmeltingManager().boostFuelTime(burnTime)
                : owner.getCookingManager().boostFuelTime(burnTime);
    }

    /**
     * Mixin seam: stash the context a {@code getBurnDuration} call needs {@link #boostFuelTime}
     * to resolve once {@link FurnaceFuelBurnTimeEvent} fires. Called from
     * {@code AbstractFurnaceGetBurnDurationMixin}'s {@code HEAD} injector — see this class's own
     * javadoc (the Fuel Efficiency section) for why the fuel-stack identity is captured too.
     *
     * @param pos   the furnace's position
     * @param fuel  the exact fuel {@link ItemStack} instance being evaluated (the argument
     *              {@code getBurnDuration} received), captured for the identity check
     * @param input the furnace's live input-slot stack
     */
    public static void rememberFuelBurnContext(BlockPos pos, ItemStack fuel, ItemStack input) {
        FUEL_BURN_CONTEXT.set(new FuelBurnContext(pos, fuel, input));
    }

    /**
     * The event half of the Fuel Efficiency bridge: consumes {@link #FUEL_BURN_CONTEXT} (get-then
     * -clear) and applies {@link #boostFuelTime} when the event's fuel stack matches what was just
     * bridged. See this class's own javadoc for why the identity check is load-bearing.
     */
    static void onFurnaceFuelBurnTime(FurnaceFuelBurnTimeEvent event) {
        final FuelBurnContext context = FUEL_BURN_CONTEXT.get();
        FUEL_BURN_CONTEXT.remove();
        if (context == null || context.fuel() != event.getItemStack()) {
            return; // no bridged context, or one left over from an unrelated burn-time lookup.
        }
        final int boosted = boostFuelTime(event.getBurnTime(), context.pos(), context.input());
        if (boosted != event.getBurnTime()) {
            event.setBurnTime(boosted);
        }
    }

    /**
     * Understanding the Art, half one: work out this extraction's vanilla-XP multiplier and park
     * it where {@link #boostVanillaXp} can find it. Called from Task B's
     * {@code FurnaceResultSlotMixin}.
     *
     * @param player    the player taking the item (legacy used the extractor, not the furnace
     *                  owner)
     * @param extracted the stack being taken out of the output slot
     */
    public static void beginFurnaceExtract(Player player, ItemStack extracted) {
        if (!(player instanceof ServerPlayer) || extracted.isEmpty()) {
            return; // client-side copy of the screen handler, or nothing actually taken.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(player.getUUID());
        if (mmoPlayer == null) {
            return; // player data not loaded — behave exactly like vanilla.
        }
        final int multiplier = mmoPlayer.getSmeltingManager().getVanillaXpBoostMultiplier();
        if (multiplier <= 1) {
            return; // unranked or sub-skill off — checked before the item gate, it is the cheaper test.
        }
        if (!smeltedOreProducts.contains(extracted.getItem())) {
            return; // legacy's ItemUtils.isSmelted gate: only ore smelts get the boost.
        }
        VANILLA_XP_MULTIPLIER.set(multiplier);
    }

    /** Clear the in-flight extraction multiplier. Pairs with {@link #beginFurnaceExtract}. */
    public static void endFurnaceExtract() {
        VANILLA_XP_MULTIPLIER.remove();
    }

    /**
     * Understanding the Art, half two: multiply the size of an XP orb the furnace is about to
     * drop. A no-op unless {@link #beginFurnaceExtract} put a multiplier in play for the
     * extraction currently running on this thread.
     *
     * @param amount the orb size vanilla computed for this batch of used recipes
     */
    public static int boostVanillaXp(int amount) {
        final Integer multiplier = VANILLA_XP_MULTIPLIER.get();
        if (multiplier == null || amount <= 0) {
            return amount;
        }
        return amount * multiplier;
    }

    /** {@link ServerStartedEvent} half of the smelted-ore-product index — the initial build. */
    private static void onServerStarted(ServerStartedEvent event) {
        indexSmeltedOreProducts(event.getServer().getRecipeManager().getRecipes(),
                event.getServer().registryAccess());
    }

    /**
     * {@link AddReloadListenerEvent} half of the smelted-ore-product index — rebuilds after every
     * data-pack reload (initial server start included, though {@link #onServerStarted} already
     * covers that case with a simpler call). See this class's javadoc for why the apply-phase
     * ordering this relies on is verified rather than assumed.
     */
    private static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier,
                    ResourceManager resourceManager, ProfilerFiller prepareProfiler,
                    ProfilerFiller applyProfiler, Executor backgroundExecutor,
                    Executor gameExecutor) {
                return barrier.wait(Unit.INSTANCE).thenRunAsync(
                        () -> indexSmeltedOreProducts(
                                event.getServerResources().getRecipeManager().getRecipes(),
                                event.getRegistryAccess()),
                        gameExecutor);
            }
        });
    }

    /**
     * Build the answer set for legacy {@code ItemUtils.isSmelted} — "is this item the product of a
     * furnace recipe whose input is an ore block?" — by walking the loaded recipes once. Legacy
     * asked that question per extraction; vanilla has no reverse index from result to recipe, and
     * building one costs a single pass, so it is done up front.
     *
     * <p>Runs on server start and again after any data-pack reload, both of which are the points
     * at which the recipe set is known-good. The count is logged because a silently empty index
     * would simply mean nobody ever gets the boost.
     */
    private static void indexSmeltedOreProducts(Collection<RecipeHolder<?>> recipes,
            HolderLookup.Provider registries) {
        final List<Item> ores = oreBlockItems();
        final Set<Item> products = new HashSet<>();
        for (RecipeHolder<?> holder : recipes) {
            // SmeltingRecipe specifically, not AbstractCookingRecipe: legacy matched Bukkit's
            // FurnaceRecipe, so blasting/smoking/campfire variants of the same ore are excluded.
            if (!(holder.value() instanceof SmeltingRecipe recipe) || !hasOreBlockInput(recipe, ores)) {
                continue;
            }
            final ItemStack result = recipe.getResultItem(registries);
            if (!result.isEmpty()) {
                products.add(result.getItem());
            }
        }
        smeltedOreProducts = Set.copyOf(products);
        McMMOMod.LOGGER.info("Smelting: {} ore block(s) smelt into {} product(s) eligible for the "
                + "Understanding the Art vanilla-XP boost", ores.size(), products.size());
    }

    /**
     * Legacy tested a recipe's single input with {@code getInput().getType().isBlock()} and
     * {@code MaterialUtils.isOre}. A vanilla {@link Ingredient} can accept several items (an ore
     * and its deepslate variant, say), so the test is asked the other way round — does the
     * ingredient accept any known ore block?
     */
    private static boolean hasOreBlockInput(SmeltingRecipe recipe, List<Item> ores) {
        // A cooking recipe has exactly one input; it is reached through the ingredient list at
        // this version, and Ingredient tests a stack rather than an item directly.
        final Ingredient ingredient = recipe.getIngredients().getFirst();
        return ores.stream().anyMatch(ore -> ingredient.test(new ItemStack(ore)));
    }

    /**
     * Every registered item that is both a placeable block and one of mcMMO's ores — legacy's
     * {@code isBlock() && MaterialUtils.isOre(...)} pair, resolved against the live item registry
     * so the store's stale pre-1.13 ids ({@code quartz_ore}, {@code lapis_lazuli_ore}) simply
     * never match.
     */
    private static List<Item> oreBlockItems() {
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> item instanceof BlockItem)
                .filter(item -> McMMOMod.getMaterialMapStore()
                        .isOre(BuiltInRegistries.ITEM.getKey(item).getPath()))
                .toList();
    }

    /**
     * The player who owns the furnace at {@code pos}, or {@code null} when nobody has interacted
     * with it this session or their data is not loaded. A furnace with no tracked owner behaves
     * exactly like vanilla.
     *
     * <p>Resolved once and handed out whole rather than per-skill, because the furnace is shared:
     * {@link #onFurnaceSmelt} has to choose between this player's Smelting and Cooking managers on
     * the same tick, and doing that through two skill-typed lookups would walk the owner map
     * twice. <b>One map, two managers</b> — do not duplicate {@link #FURNACE_OWNERS} for Cooking.
     */
    private static @Nullable McMMOPlayer owner(BlockPos pos) {
        final UUID ownerId = FURNACE_OWNERS.get(pos.asLong());
        if (ownerId == null) {
            return null; // no one has interacted with this furnace this session.
        }
        return UserManager.getPlayer(ownerId); // null when owner data is not loaded.
    }

    /**
     * Test seam: record a furnace owner directly, without needing a real
     * {@link PlayerInteractEvent.RightClickBlock}. See {@link #onUseBlock} for the real entry
     * point this bypasses.
     */
    static void trackOwnerForTesting(BlockPos pos, UUID ownerId) {
        FURNACE_OWNERS.put(pos.asLong(), ownerId);
    }

    /**
     * e.g. {@code minecraft:iron_ore} → {@code "Iron_Ore"}, the key the smelting and cooking
     * configs are both written against.
     *
     * <p><b>Package-private on purpose:</b> {@code CookingListener}'s crafting path derives the
     * same kind of key from a different stack, and the two must agree. Hunter's lesson — where two
     * paths derive the same key, give them one shared function rather than two tests.
     */
    static String materialConfigString(ItemStack stack) {
        return ConfigStringUtils.getMaterialConfigString(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
    }
}
