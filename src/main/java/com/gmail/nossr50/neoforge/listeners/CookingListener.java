package com.gmail.nossr50.neoforge.listeners;

import com.gmail.nossr50.datatypes.interactions.NotificationType;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.skills.cooking.CookingManager;
import com.gmail.nossr50.util.player.NotificationManager;
import com.gmail.nossr50.util.player.UserManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Cooking's own two seams: the <b>crafting grid</b> (a player taking a crafted food out of a result
 * slot) and the <b>campfire</b> (a cook finishing on a lit campfire or soul campfire). Ports
 * {@code fabric.listeners.CookingListener} — see
 * docs/superpowers/specs/2026-08-30-cooking-smelting-listener-design.md for the full design
 * rationale.
 *
 * <p>The furnace half of the skill does <em>not</em> live here — it rides the furnace-owner map and
 * the {@code burn} injector that Smelting already owns, so it is the food branch of
 * {@link SmeltingListener#onFurnaceSmelt}. This class calls
 * {@link SmeltingListener#materialConfigString} (package-visible for exactly this purpose) so the
 * furnace-side, crafting-grid and campfire config keys can never drift apart.
 *
 * <h2>Why {@code ResultSlot#checkTakeAchievements(ItemStack)} and not a recipe- or item-level hook</h2>
 * It is the funnel and it is player-only by construction — see {@code ResultSlotMixin}'s javadoc
 * for the bytecode-verified reasoning (both a normal take and a shift-click reach it, and the
 * 1.21 auto-crafter, {@code CrafterBlock}, never references {@code ResultSlot} at all).
 *
 * <h2>The count comes from the slot's own {@code removeCount} field, and it is the whole batch</h2>
 * {@code checkTakeAchievements(ItemStack)} is called once per take, and the stack it is handed is a
 * single result — the batch size lives in the slot's private {@code removeCount}. Cooking XP is
 * priced per item and multiplied by that count, or one take of the cookie recipe pays for one
 * cookie instead of eight (and a shift-clicked stack pays 1/64th).
 */
public final class CookingListener {

    /**
     * Campfire {@link BlockPos#asLong()} → owner UUID — the campfire twin of Smelting's
     * {@code FURNACE_OWNERS}, with the same position-only keying caveat (singleplayer has one player,
     * so a same-coordinates campfire in another dimension awards the same person).
     *
     * <h2>Why a plain owner map is enough</h2>
     * {@code CampfireBlockEntity#addItem} — the only place a cooking slot's owner would otherwise be
     * known — has exactly one caller in the whole jar, {@code CampfireBlock#useWithoutItem}/
     * {@code useItemOn}, which is a player interaction; and {@code CampfireBlockEntity} is not an
     * {@code Inventory}, so no hopper, dropper or minecart can put anything in it. A campfire is the
     * one cooking block in the game that cannot be automated, which is also why it needs no rate
     * gate of its own beyond the shared {@code Max_Cooks_Per_Hour}.
     */
    private static final Map<Long, UUID> CAMPFIRE_OWNERS = new ConcurrentHashMap<>();

    private CookingListener() {
    }

    /** Register campfire owner tracking. Called once at mod load from {@code McMMOMod}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(CookingListener::onUseBlock);
    }

    /** Drop the tracked campfire owners so the next world session starts clean. */
    public static void clearOwners() {
        CAMPFIRE_OWNERS.clear();
    }

    /**
     * Right-click a campfire → remember this player as its owner for XP-award purposes. The exact
     * shape of {@code SmeltingListener#onUseBlock}, deliberately: a campfire is not an
     * {@code AbstractFurnaceBlockEntity}, so that hook does not reach it.
     *
     * <p>Registered as a <em>second</em> {@code PlayerInteractEvent.RightClickBlock} listener rather
     * than folded into Smelting's, because the campfire is Cooking's alone — Smelting has no
     * business in this map and a shared one would have to be cleared by whichever listener happened
     * to own it.
     *
     * <p>Package-private rather than private so {@code CookingListenerTest} can claim a campfire the
     * way a player does; there is no other route into {@link #CAMPFIRE_OWNERS}, and a hook with no
     * test route into it is how a mechanic ends up wired to nothing.
     */
    static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        final Player player = event.getEntity();
        if (!(player instanceof ServerPlayer)) {
            return; // client-side fire -- the server copy does the bookkeeping.
        }
        final Level world = event.getLevel();
        final BlockPos pos = event.getPos();
        if (world.getBlockEntity(pos) instanceof CampfireBlockEntity) {
            CAMPFIRE_OWNERS.put(pos.asLong(), player.getUUID());
        }
        // observe only; never cancel the interaction.
    }

    /**
     * Credit a finished campfire cook, and give Master Chef its roll at a second helping.
     *
     * <p>Called from {@code CampfireCookMixin} at the {@code Containers.dropItemStack} call inside
     * {@code CampfireBlockEntity#cookTick} — the one point at which a campfire cook has finished.
     * There is no output slot: the campfire throws the cooked item on the floor, so the returned
     * stack is what gets scattered and Master Chef's extra copy is a {@code grow(1)} on it rather
     * than a merge into an inventory.
     *
     * <h2>Why the XP is keyed on the input and the bonus drop on the result</h2>
     * Exactly as on the furnace, and for the same reason: {@code Experience_Values.Cooking.Cook} is
     * written against the raw input ({@code Beef}, {@code Kelp}) while {@code Bonus_Drops.Cooking} is
     * written against the result ({@code Cooked_Beef}). The campfire hands us both at once, so this
     * is the one seam where the two key spaces sit in the same method — do not "unify" them.
     *
     * <p>Kitchen Efficiency has no campfire arm on purpose: a campfire burns no fuel, so there is no
     * burn time to multiply. Master Chef does have one, because a campfire is heat all the same.
     *
     * <p>The rate cap is deliberately not a condition of the bonus drop, matching the furnace path.
     *
     * @param world  the campfire's world — its time is the clock the rate cap is measured on
     * @param pos    the campfire position, the owner-map key
     * @param input  the raw stack that was being cooked, still intact at this point (vanilla clears
     *               the slot after the scatter)
     * @param result the stack vanilla is about to scatter
     * @return {@code result}, possibly grown by Master Chef
     */
    public static ItemStack onCampfireCook(ServerLevel world, BlockPos pos, ItemStack input,
            ItemStack result) {
        if (input == result) {
            // Identity, not equality, and it is the precise test. cookTick resolves the result as
            // `getRecipeFor(...).map(craft).orElse(rawStack)`, and the recipe's own assemble always
            // returns a fresh copy -- so the result IS the input object only when no campfire recipe
            // matched and the raw item is being spat back out. That can only happen if a data pack
            // reload removed the recipe mid-cook, and it must pay nothing: nothing was cooked.
            return result;
        }
        if (input.isEmpty() || result.isEmpty()) {
            return result;
        }
        final McMMOPlayer owner = campfireOwner(pos);
        if (owner == null) {
            return result; // nobody has touched this campfire this session, or their data isn't loaded.
        }
        final CookingManager cooking = owner.getCookingManager();
        final CookingManager.CookAward award =
                cooking.onCook(SmeltingListener.materialConfigString(input), world.getGameTime());
        if (award.capReached()) {
            // Once per window, not once per cook -- see SmeltingListener#onFurnaceSmelt.
            NotificationManager.sendPlayerInformation(owner, NotificationType.SUBSKILL_MESSAGE,
                    "Cooking.CookRateCap.Reached");
        }
        if (cooking.canSecondHelping(SmeltingListener.materialConfigString(result))) {
            result.grow(1);
        }
        return result;
    }

    /**
     * The player who owns the campfire at {@code pos}, or {@code null} when nobody has interacted
     * with it this session or their data is not loaded. An unowned campfire behaves exactly like
     * vanilla.
     */
    private static @Nullable McMMOPlayer campfireOwner(BlockPos pos) {
        final UUID ownerId = CAMPFIRE_OWNERS.get(pos.asLong());
        return ownerId == null ? null : UserManager.getPlayer(ownerId);
    }

    /**
     * Award Cooking XP for a batch of crafted food. Called from {@code ResultSlotMixin} at the
     * <b>head</b> of {@code checkTakeAchievements(ItemStack)}.
     *
     * <p>The head, not the return: the method's last act on this path is {@code removeCount = 0}, so
     * a RETURN injection would read a batch size of zero every time and pay nothing at all --
     * silently, and with a green compile.
     *
     * @param player the slot's owner; a non-{@link ServerPlayer} is the client's own copy of the
     *               screen handler and is ignored, exactly as the furnace-extract hook does
     * @param result the crafted result stack (one item's worth -- the count is in {@code items})
     * @param items  the slot's accumulated {@code removeCount}: how many items this take produced
     */
    public static void onCraftedItemTaken(Player player, ItemStack result, int items) {
        if (!(player instanceof ServerPlayer) || result.isEmpty() || items <= 0) {
            return; // client-side copy, an empty slot, or nothing actually taken.
        }
        final McMMOPlayer mmoPlayer = UserManager.getPlayer(player.getUUID());
        if (mmoPlayer == null) {
            return; // player data not loaded -- behave exactly like vanilla.
        }
        // Keyed on the RESULT, unlike the furnace path which is keyed on the input. The key
        // derivation itself is shared so the two can never drift apart.
        final String resultConfigString = SmeltingListener.materialConfigString(result);
        final CookingManager.CookAward award = mmoPlayer.getCookingManager()
                .onCraft(resultConfigString, items, player.level().getGameTime());
        if (award.capReached()) {
            // Once per window, not once per craft -- see SmeltingListener#onFurnaceSmelt.
            NotificationManager.sendPlayerInformation(mmoPlayer, NotificationType.SUBSKILL_MESSAGE,
                    "Cooking.CookRateCap.Reached");
        }
    }

    /**
     * Test seam: record a campfire owner directly, without needing a real
     * {@link PlayerInteractEvent.RightClickBlock}. See {@link #onUseBlock} for the real entry point
     * this bypasses.
     */
    static void trackOwnerForTesting(BlockPos pos, UUID ownerId) {
        CAMPFIRE_OWNERS.put(pos.asLong(), ownerId);
    }
}
