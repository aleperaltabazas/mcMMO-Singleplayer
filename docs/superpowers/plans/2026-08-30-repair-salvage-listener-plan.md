# Repair/Salvage Anvil Listener (NeoForge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Fabric mcMMO `RepairSalvageListener` — the anvil right-click dispatch for Repair
and Salvage, including Arcane Forging and Arcane Salvage — to NeoForge 1.21.1.

**No separate spec doc.** This is a straight mechanical port: no custom GUI, no mixins, and every
piece of MC-free skill math (`RepairManager`, `SalvageManager`, `Repairable`/`Salvageable`) already
exists in the current tree, fully NeoForge-adapted (`com.gmail.nossr50.skills.repair`/`.salvage`
import `com.gmail.nossr50.neoforge.McMMOMod` already). The only net-new work is the MC-typed glue
this doc's Task 1 covers. Comparable in size/risk to the Hunter port.

**Architecture:** mcMMO's two anvils are ordinary vanilla blocks (config-selected — an iron block for
Repair, a gold block for Salvage by default), not the real anvil screen. A right-click with a
repairable/salvageable item claims the click; a second click within the confirmation window performs
the action. This needs exactly one event: NeoForge's
`net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock`, already used
elsewhere in this repo (`SuperAbilityListener.onUseBlock`) for an unrelated right-click flow. No
mixin is needed — every method this listener calls (`ItemStack#getDamageValue`/`setDamageValue`,
`DataComponents.UNBREAKABLE`, `Block.popResource`, `EnchantmentHelper.updateEnchantments`,
`Materials.item`/`Materials.block`) is already exercised by existing neoforge/ code (see the
Verification appendix below for exact call sites).

**The click-claiming mechanism differs from the Fabric original and is more precise.** Fabric's
`UseBlockCallback` returns a single `ActionResult`; returning `SUCCESS` from the *client-side* fire
was the only way to stop the client falling through from "use block" to "use item" (which is what let
vanilla equip the armor being repaired — the regression `RepairSalvageListenerTest`'s
`clientSideFireOnTheRepairAnvilClaimsTheClick` guards). NeoForge's `RightClickBlock` instead exposes
`getUseItem()`/`setUseItem(TriState)`, read directly at the exact fork that caused the Fabric bug —
confirmed by reading both real call sites in the patched jar's decompiled sources
(`net/minecraft/server/level/ServerPlayerGameMode.java:355` and
`net/minecraft/client/multiplayer/MultiPlayerGameMode.java`, `useItemOn`, both call
`net.neoforged.neoforge.common.CommonHooks.onRightClickBlock(...)` and both gate the item-use
fallback — `p_9268_.useOn(useoncontext)` — on `event.getUseItem() != TriState.FALSE`, with an
explicit `if (event.getUseItem().isFalse()) return InteractionResult.PASS;` right before it). The
port therefore calls `event.setUseItem(TriState.FALSE)` at the same identity-check point Fabric
claimed the click — same effect, a more direct mechanism than replaying an `ActionResult` trick.

`PlayerInteractEvent` exposes `getSide(): LogicalSide` directly (verified via `javap`), a cleaner
client/server split than Fabric's `instanceof ServerPlayerEntity`, though `SuperAbilityListener`'s
existing convention (`resolve()` returning `null` for anything that isn't a `ServerPlayer`) is kept
for consistency with the rest of this listener's package — client-side identity gating uses the same
`instanceof ServerPlayer` fork, just answered on both branches instead of only the server one.

**Tech Stack:** Java 21, NeoForge 21.1.248 (Minecraft 1.21.1), JUnit 5 + Mockito. No Sponge Mixin
involvement in this plan.

## Global Constraints

- The click must be claimed (`event.setUseItem(TriState.FALSE)`) on **both** logical sides whenever
  the block is one of mcMMO's anvils and the held item is one the matching skill works on — claiming
  it only server-side reproduces the exact Fabric regression this port must not reintroduce.
- Only `InteractionHand.MAIN_HAND` is handled — the off-hand fire of the same event must be ignored,
  or the dual main/off-hand dispatch double-arms the confirmation gate on one physical click (see
  `SuperAbilityListener`'s existing `event.getHand() != InteractionHand.MAIN_HAND` convention).
- The identity check (is this an mcMMO anvil? does the held item qualify?) is the *entire* client-side
  decision, and the server side must gate on the exact same lookup — so a click is mcMMO's on both
  sides or neither. Anything else (durability, level, materials on hand) is a failure of an already-
  claimed action, reported to the player, not a reason to hand the click back to vanilla.
- Salvage grants no XP — it is a material-recovery skill only. Repair grants XP via
  `RepairManager#awardRepairXp`.
- Both Arcane Forging (`applyArcaneForging`) and Arcane Salvage (`buildArcaneSalvageBook`) must clamp
  an enchantment to `Enchantment#getMaxLevel()` before rolling, unless
  `ExperienceConfig#allowUnsafeEnchantments()` is set — ported from the Fabric original's identical
  clamp in both methods.
- Enchantments are read/written via `EnchantmentHelper.updateEnchantments` /
  `EnchantmentHelper.getEnchantments`/`ItemEnchantments` (1.21.1's component-based API — **not**
  `ItemEnchantmentsComponent`, the pre-1.21 class name the Fabric original used under yarn mappings);
  follow `FishingListener.applyBookEnchantment`'s existing exact usage pattern in this repo, including
  its `DataComponents.STORED_ENCHANTMENTS`-vs-`ENCHANTMENTS` handling for the Arcane Salvage book.

---

### Task 1: `RepairSalvageListener`, wired

**Files:**
- Create: `src/main/java/com/gmail/nossr50/neoforge/listeners/RepairSalvageListener.java`
- Modify: `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java` (add
  `RepairSalvageListener.register();` to the constructor's listener-wiring block, alongside
  `HunterListener.register();`)
- Modify: `src/main/java/com/gmail/nossr50/neoforge/mixin/BlockPlaceMixin.java` (wire the
  previously-deferred `onAnvilPlaced` call — see Step 4)
- Test: `src/test/java/com/gmail/nossr50/neoforge/listeners/RepairSalvageListenerTest.java`

**Interfaces:**
- Consumes (all existing, unchanged): `RepairManager` (`checkConfirmation`, `repairCalculate`,
  `awardRepairXp`, `canKeepEnchants`, `getKeepEnchantChance`, `getDowngradeEnchantChance`,
  `resolveEnchantOutcome`, `isArcaneForgingEnchantLossEnabled`, `placedAnvilCheck`) —
  `src/main/java/com/gmail/nossr50/skills/repair/RepairManager.java`. `SalvageManager`
  (`checkConfirmation`, `calculateSalvageableAmount` (static), `getSalvageLimit` (static),
  `canArcaneSalvage`, `getExtractFullEnchantChance`, `getExtractPartialEnchantChance`,
  `resolveEnchantOutcome`, `failedAllEnchants`, `placedAnvilCheck`) —
  `src/main/java/com/gmail/nossr50/skills/salvage/SalvageManager.java`. `Materials.item(String):
  Optional<Item>` / `Materials.block(String): Optional<Block>` (existing,
  `src/main/java/com/gmail/nossr50/platform/Materials.java`). `PlatformPlayer#removeSuperAbilityBoost
  (ItemStack): void` (existing, `src/main/java/com/gmail/nossr50/platform/PlatformPlayer.java:506`).
  `Permissions.hasRepairEnchantBypassPerk`/`arcaneBypass`/`hasSalvageEnchantBypassPerk` (existing).
  `ProbabilityUtil.isStaticSkillRNGSuccessful`/`isSkillRNGSuccessful` (existing, same pattern already
  used by `FishingListener`). `RankUtils.hasUnlockedSubskill` (existing).
- Produces: `RepairSalvageListener.register(): void`, called once from `McMMOMod`.
  `RepairSalvageListener.onAnvilPlaced(ServerLevel, BlockPos, ServerPlayer): void`, called from
  `BlockPlaceMixin` (Step 4).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/gmail/nossr50/neoforge/listeners/RepairSalvageListenerTest.java`, porting
the Fabric original's own test file 1:1 in intent (its regression is the load-bearing case — port it
first). Follow this codebase's Mockito/`McTestRegistries`/`UserManager.track`/`GeneralConfig`-via-
`@TempDir` pattern already established in `HunterListenerTest`/`FishingListenerCatchTest`. Key
adaptations from the Fabric test (types only — the scenarios themselves port unchanged):

- `UseBlockCallback`/`ActionResult`/`Hand`/`World`/`BlockHitResult` → the real
  `PlayerInteractEvent.RightClickBlock` (Mojang-mapped `net.neoforged.neoforge.event.entity.player`,
  `net.minecraft.world.InteractionHand`, `net.minecraft.world.level.Level`,
  `net.minecraft.world.phys.BlockHitResult`). Since `RightClickBlock`'s constructor takes a `Player`,
  a `InteractionHand`, a `BlockPos`, and a `BlockHitResult`, build a real event instance (not a mock —
  its getters are plain field reads, and the "claimed" assertion needs to read
  `event.getUseItem()`/`event.isCanceled()` back after dispatch, which only a real instance supports).
- `PlayerEntity`/`ServerPlayerEntity` mocks → `Player`/`ServerPlayer` mocks, `getMainHandStack()` →
  `getMainHandItem()`.
- The "client-side fire claims the click" assertion becomes: after calling
  `RepairSalvageListener.onUseBlock(event)` with a non-`ServerPlayer` `Player` mock, assert
  `event.getUseItem() == TriState.FALSE` (not an `ActionResult` return value — this listener's
  `onUseBlock` returns `void`, consuming the event object instead, matching
  `SuperAbilityListener.onUseBlock`'s existing shape in this repo).
- The "ordinary block passes" / "empty hand passes" / "off-hand passes" assertions become:
  `event.getUseItem() == TriState.DEFAULT` (untouched) and `!event.isCanceled()`.
- The "server-side fire arms the confirmation" assertion drives a real `ServerPlayer` mock and asserts
  `repairManager.checkConfirmation(true)` was called, exactly as the Fabric test does.

```java
package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.repair.RepairManager;
import com.gmail.nossr50.skills.repair.repairables.Repairable;
import com.gmail.nossr50.skills.repair.repairables.RepairableManager;
import com.gmail.nossr50.skills.salvage.salvageables.Salvageable;
import com.gmail.nossr50.skills.salvage.salvageables.SalvageableManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The anvil dispatch in {@link RepairSalvageListener} — specifically <em>who the click belongs to</em>,
 * ported from the Fabric original's regression test. See that class's own javadoc for the mechanism:
 * a client-side fire must set {@link TriState#FALSE} on {@code event.getUseItem()} or the client
 * falls through to using the item (equipping armor being repaired).
 */
class RepairSalvageListenerTest {

    private static final BlockPos ANVIL_POS = new BlockPos(4, 64, -7);

    private GeneralConfig generalConfig;
    private Level world;
    private McMMOPlayer mmoPlayer;
    private RepairManager repairManager;

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        generalConfig = new GeneralConfig(dir);
        McMMOMod.setGeneralConfig(generalConfig);

        final RepairableManager repairables = mock(RepairableManager.class);
        lenient().when(repairables.getRepairable("iron_chestplate"))
                .thenReturn(mock(Repairable.class));
        McMMOMod.setRepairableManager(repairables);

        final SalvageableManager salvageables = mock(SalvageableManager.class);
        lenient().when(salvageables.getSalvageable("iron_chestplate"))
                .thenReturn(mock(Salvageable.class));
        McMMOMod.setSalvageableManager(salvageables);

        world = mock(Level.class);
        placeAnvil(Blocks.STONE);
    }

    @AfterEach
    void tearDown() {
        if (mmoPlayer != null) {
            UserManager.cleanupPlayer(mmoPlayer);
            mmoPlayer = null;
        }
        McMMOMod.setGeneralConfig(null);
        McMMOMod.setRepairableManager(null);
        McMMOMod.setSalvageableManager(null);
    }

    @Test
    void clientSideFireOnTheRepairAnvilClaimsTheClick() {
        placeAnvil(Blocks.IRON_BLOCK);
        final PlayerInteractEvent.RightClickBlock event =
                anvilEvent(clientPlayer(damagedChestplate()));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.FALSE, event.getUseItem(),
                "the client fire must claim the anvil click, or the client falls through to "
                        + "\"use item\" and equips the armour being repaired");
    }

    @Test
    void clientSideFireOnTheSalvageAnvilClaimsTheClick() {
        placeAnvil(Blocks.GOLD_BLOCK);
        final PlayerInteractEvent.RightClickBlock event =
                anvilEvent(clientPlayer(damagedChestplate()));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.FALSE, event.getUseItem());
    }

    @Test
    void clientSideFireWithAnItemMcmmoDoesNotWorkOnPasses() {
        placeAnvil(Blocks.IRON_BLOCK);
        final PlayerInteractEvent.RightClickBlock event =
                anvilEvent(clientPlayer(new ItemStack(Items.GOLDEN_APPLE)));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.DEFAULT, event.getUseItem());
    }

    @Test
    void clientSideFireWithAnEmptyHandPasses() {
        placeAnvil(Blocks.IRON_BLOCK);
        final PlayerInteractEvent.RightClickBlock event = anvilEvent(clientPlayer(ItemStack.EMPTY));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.DEFAULT, event.getUseItem());
    }

    @Test
    void clientSideFireOnAnOrdinaryBlockPasses() {
        placeAnvil(Blocks.STONE);
        final PlayerInteractEvent.RightClickBlock event =
                anvilEvent(clientPlayer(damagedChestplate()));

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.DEFAULT, event.getUseItem());
    }

    @Test
    void offHandFirePasses() {
        placeAnvil(Blocks.IRON_BLOCK);
        final PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                clientPlayer(damagedChestplate()), InteractionHand.OFF_HAND, ANVIL_POS, anvilHit());

        RepairSalvageListener.onUseBlock(event);

        assertEquals(TriState.DEFAULT, event.getUseItem());
        assertFalse(event.isCanceled());
    }

    @Test
    void serverSideFireOnTheRepairAnvilArmsTheConfirmation() {
        placeAnvil(Blocks.IRON_BLOCK);
        final ServerPlayer player = trackedServerPlayer(damagedChestplate());
        when(repairManager.checkConfirmation(true)).thenReturn(false);
        final PlayerInteractEvent.RightClickBlock event = anvilEvent(player);

        RepairSalvageListener.onUseBlock(event);

        verify(repairManager).checkConfirmation(true);
    }

    private void placeAnvil(Block block) {
        final BlockState state = block.defaultBlockState();
        lenient().when(world.getBlockState(ANVIL_POS)).thenReturn(state);
    }

    private static BlockHitResult anvilHit() {
        return new BlockHitResult(Vec3.atCenterOf(ANVIL_POS), Direction.UP, ANVIL_POS, false);
    }

    private PlayerInteractEvent.RightClickBlock anvilEvent(Player player) {
        return new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, ANVIL_POS,
                anvilHit());
    }

    private static ItemStack damagedChestplate() {
        final ItemStack stack = new ItemStack(Items.IRON_CHESTPLATE);
        stack.setDamageValue(100);
        return stack;
    }

    private Player clientPlayer(ItemStack mainHand) {
        final Player player = mock(Player.class);
        lenient().when(player.getMainHandItem()).thenReturn(mainHand);
        lenient().when(player.level()).thenReturn(world);
        return player;
    }

    private ServerPlayer trackedServerPlayer(ItemStack mainHand) {
        final UUID uuid = UUID.randomUUID();
        final ServerPlayer player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(uuid);
        lenient().when(player.getMainHandItem()).thenReturn(mainHand);
        lenient().when(player.level()).thenReturn(world);

        repairManager = mock(RepairManager.class);
        final PlatformPlayer platformPlayer = mock(PlatformPlayer.class);
        lenient().when(platformPlayer.getUniqueId()).thenReturn(uuid);
        mmoPlayer = mock(McMMOPlayer.class);
        lenient().when(mmoPlayer.getPlayer()).thenReturn(platformPlayer);
        lenient().when(mmoPlayer.getRepairManager()).thenReturn(repairManager);
        UserManager.track(mmoPlayer);
        return player;
    }
}
```

Check `PlatformPlayer`'s real constructor/factory before committing to `mock(PlatformPlayer.class)` +
stubbing `getUniqueId()` — `HunterListenerTest`'s `trackedMmoPlayer` helper constructs a real
`new PlatformPlayer(handle)` instead of mocking it (see `HunterListenerTest.java:293`); prefer that
same real-construction pattern here for consistency unless it doesn't fit this test's needs.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.RepairSalvageListenerTest"`
Expected: FAIL to compile — `RepairSalvageListener` does not exist yet.

- [ ] **Step 3: Write `RepairSalvageListener.java`**

Port the Fabric original method-for-method (fetched via `git show
d0764257671576525aedd97308be2f8c6d85e2fd:src/main/java/com/gmail/nossr50/fabric/listeners/RepairSalvageListener.java`),
retargeting every Fabric/yarn type to its Mojang-mapped NeoForge equivalent. The skill-manager calls
(`RepairManager`/`SalvageManager`) are already exact matches — no signature changes needed on that
side. The MC-typed retargets:

| Fabric (yarn) | NeoForge (Mojang) |
|---|---|
| `UseBlockCallback` / `ActionResult` | `PlayerInteractEvent.RightClickBlock`, `void`-returning listener that mutates the event (see `SuperAbilityListener.onUseBlock` for the exact shape) |
| `PlayerEntity` / `ServerPlayerEntity` | `Player` / `ServerPlayer` |
| `player.getMainHandStack()` | `player.getMainHandItem()` |
| `World` | `Level` |
| `Hand` / `Hand.MAIN_HAND` | `InteractionHand` / `InteractionHand.MAIN_HAND` |
| `BlockHitResult.getBlockPos()` | same name, `net.minecraft.world.phys.BlockHitResult` |
| `world.getBlockState(pos).getBlock()` | same, `net.minecraft.world.level.block.state.BlockState` |
| `PlayerInventory` | `net.minecraft.world.entity.player.Inventory` |
| `inventory.getStack(slot)` / `removeStack(slot, n)` | `inventory.getItem(slot)` / `inventory.removeItem(slot, n)` (mirror `SuperAbilityListener.findItemSlot`'s existing use of this API) |
| `item.getDamage()` / `item.setDamage(n)` | `item.getDamageValue()` / `item.setDamageValue(n)` |
| `item.contains(DataComponentTypes.UNBREAKABLE)` | `item.has(DataComponents.UNBREAKABLE)` (see `PlatformItem.java:111` for this exact call already in use) |
| `ItemEnchantmentsComponent` / `EnchantmentHelper.getEnchantments`/`.set` | `net.minecraft.world.item.enchantment.ItemEnchantments` + `EnchantmentHelper.updateEnchantments(stack, mutable -> ...)` — follow `FishingListener.applyBookEnchantment`/`maybeApplyMagicHunter`'s exact usage, including its `mutable.set(holder, level)`/`mutable.removeIf(...)` idioms; there is no single `EnchantmentHelper.set(item, component)` replacement call — enchantments are mutated in place via the `Consumer<ItemEnchantments.Mutable>` callback |
| `RegistryEntry<Enchantment>` | `net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>` |
| `Registries.ITEM.getId(item).getPath()` | `net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath()` (mirror `SuperAbilityListener.itemPath`) |
| `serverPlayer.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY)` | `serverPlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)` |
| `Block.dropStack(world, pos.up(), stack)` | `Block.popResource(world, pos.above(), stack)` (mirror `BlockBreakListener`/`BlastMiningListener`'s existing calls) |
| `Items.ENCHANTED_BOOK` | same name, `net.minecraft.world.item.Items` |
| `DataComponentTypes.STORED_ENCHANTMENTS` | `net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS` |

Iterating an `ItemEnchantments`'s entries: use `enchants.entrySet()` (returns
`Set<Entry<Holder<Enchantment>, Integer>>`) or `enchants.keySet()` +
`enchants.getLevel(holder)`, matching whichever idiom `FishingListener` already uses for its own
enchantment-conflict logic (`treasureStack.getTagEnchantments()` there) — confirm the exact accessor
name on `ItemEnchantments` via `javap -p -classpath
build/moddev/artifacts/neoforge-21.1.248-merged.jar net.minecraft.world.item.enchantment.ItemEnchantments`
before writing this loop, rather than assuming yarn's `getEnchantments()`/`getLevel()` names carry
over unchanged.

`onUseBlock`'s signature and body (the click-claiming half — the part the test above drives) is
package-private, `static void onUseBlock(PlayerInteractEvent.RightClickBlock event)`:

```java
static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
    if (event.getHand() != InteractionHand.MAIN_HAND) {
        return;
    }

    final Level world = event.getLevel();
    final BlockPos pos = event.getPos();
    final AnvilKind kind = anvilKindAt(world, pos);
    final Player player = event.getEntity();
    if (kind == null || !isAnvilAction(kind, player.getMainHandItem())) {
        return; // not an mcMMO anvil action — leave the event untouched for vanilla.
    }

    if (!(player instanceof ServerPlayer serverPlayer)) {
        // Client-side fire: claim the click so the client does not fall through to using the item
        // (which would equip/consume/cast whatever is being repaired/salvaged). No player state is
        // touched here -- the confirmation clock and the action itself belong to the server side.
        event.setUseItem(TriState.FALSE);
        return;
    }

    event.setUseItem(TriState.FALSE);
    switch (kind) {
        case REPAIR -> handleRepairInteraction(serverPlayer);
        case SALVAGE -> handleSalvageInteraction(serverPlayer, world, pos);
    }
}
```

Everything below `onUseBlock` (`AnvilKind`, `anvilKindAt`, `isAnvilAction`,
`repairableInHand`/`salvageableInHand`, `itemPath`, `handleRepairInteraction`/`performRepair`,
`applyArcaneForging`, `handleSalvageInteraction`/`performSalvage`, `buildArcaneSalvageBook`,
`componentOf`→ replaced by the `updateEnchantments` mutator per the table above, `allowUnsafeEnchantments`,
`rollSuperRepair`, `anvilBlock`, `findMaterialSlot`, `isEnchanted`, `notifyMissingRepairMaterial`) ports
1:1 in logic from the Fabric original, retargeted per the table. Keep the class's overall doc
structure and per-method javadoc — they explain *why*, not just *what*, and none of that reasoning is
platform-specific.

`register()`:

```java
static void register() {
    NeoForge.EVENT_BUS.addListener(RepairSalvageListener::onUseBlock);
}
```

- [ ] **Step 4: Wire `onAnvilPlaced` from `BlockPlaceMixin`**

`BlockPlaceMixin`'s own javadoc already documents this exact gap (see its "PORT gap, explicitly
deferred" paragraph) — it was deliberately left out because `RepairSalvageListener` didn't exist yet
on this branch. Now it does. Edit
`src/main/java/com/gmail/nossr50/neoforge/mixin/BlockPlaceMixin.java`:

```java
    @Inject(
            method = "placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)Z", allow = 1,
            at = @At("RETURN"))
    private void mcmmo$onBlockPlaced(BlockPlaceContext context, BlockState state,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return; // setBlock reported no change: nothing was placed.
        }
        final Level world = context.getLevel();
        if (world instanceof ServerLevel serverWorld) {
            BlockUtils.markPlaced(serverWorld, context.getClickedPos());
            if (context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                com.gmail.nossr50.neoforge.listeners.RepairSalvageListener.onAnvilPlaced(serverWorld,
                        context.getClickedPos(), serverPlayer);
            }
        }
    }
```

Verify `BlockPlaceContext#getPlayer()` returns the placing `Player` (nullable — a dispenser-placed
block has none) via `javap -p` against the merged jar before committing to this call shape; use a
fully-qualified reference for `RepairSalvageListener`/`ServerPlayer` only if it avoids an import
collision with existing names in `BlockPlaceMixin`, otherwise add proper imports. Remove the now-
stale "PORT gap, explicitly deferred" paragraph from the class javadoc once this is wired — replace it
with a short note that anvil-placement tracking now flows through `RepairSalvageListener.onAnvilPlaced`.

Update `BlockPlaceMixin`'s existing test (if any references the old deferred behavior) — check
`src/test/java/com/gmail/nossr50/neoforge/mixin/` for a `BlockPlaceMixinTest` or similar before
assuming none exists.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.gmail.nossr50.neoforge.listeners.RepairSalvageListenerTest"`
Expected: PASS.

Then the full suite:

Run: `./gradlew test`
Expected: PASS, total test count higher than the pre-task baseline.

- [ ] **Step 6: Wire `RepairSalvageListener.register()` into `McMMOMod`**

In `src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java`, immediately after the existing
`HunterListener.register();` line, add:

```java
        // Repair/Salvage anvil dispatch (docs/superpowers/plans/2026-08-30-repair-salvage-listener-plan.md).
        RepairSalvageListener.register();
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/gmail/nossr50/neoforge/listeners/RepairSalvageListener.java \
        src/main/java/com/gmail/nossr50/neoforge/mixin/BlockPlaceMixin.java \
        src/main/java/com/gmail/nossr50/neoforge/McMMOMod.java \
        src/test/java/com/gmail/nossr50/neoforge/listeners/RepairSalvageListenerTest.java
git commit -m "feat(neoforge): port Repair/Salvage anvil listener"
```

---

## Manual In-Game Verification (after the task lands)

The automated tests exercise the click-claim mechanism and the confirmation-gate dispatch, but not
the full repair/salvage payout end-to-end (durability math, material consumption, Arcane
Forging/Salvage enchantment rolls) against a real inventory. Before considering this plan done, boot a
dev client/server and:

1. Place an iron block, right-click it holding a damaged, repairable item with a repair material in
   inventory. Confirm the first click prompts "confirm or cancel" and the second click repairs the
   item, consumes one material, and awards Repair XP.
2. Repeat with an enchanted item and no Arcane Forging rank; confirm every enchantment is stripped.
   Repeat again with a high Arcane Forging rank; confirm enchantments mostly survive.
3. Place a gold block, right-click it holding a damaged, salvageable item. Confirm the item is
   consumed and the expected quantity of salvage material drops on top of the anvil.
4. Repeat with an enchanted item and a sufficient Arcane Salvage rank; confirm an enchanted book with
   some subset of the original enchantments drops alongside the salvage materials.
5. **The regression check the whole click-claim mechanism exists for:** hold a damaged piece of armor,
   right-click the repair anvil once. Confirm the armor is **not** equipped and instead the repair
   confirmation prompt appears.
6. Place a fresh iron block and gold block; confirm each shows its one-time "this is a repair/salvage
   anvil" hint.

## Verification Appendix (for the plan author / reviewer, not a task step)

Confirmed by direct inspection of `build/moddev/artifacts/neoforge-21.1.248-merged.jar` and its
`-sources.jar` counterpart during this plan's drafting:

- `PlayerInteractEvent.RightClickBlock` fires from both
  `net.minecraft.server.level.ServerPlayerGameMode#useItemOn` (server) and
  `net.minecraft.client.multiplayer.MultiPlayerGameMode#useItemOn` (client), both via
  `net.neoforged.neoforge.common.CommonHooks.onRightClickBlock(...)` — confirming the dual-fire
  semantics this plan's click-claim mechanism depends on.
- `event.setUseItem(TriState.FALSE)` is read back at `ServerPlayerGameMode.java:379`
  (`if (event.getUseItem().isFalse()) return InteractionResult.PASS;`), immediately before the
  `useOn(useoncontext)` call that is vanilla's item-use fallback — this is the exact fork Fabric's
  `ActionResult.SUCCESS` return used to short-circuit, confirmed present symmetrically in both the
  client and server `useItemOn` methods.
- `Materials.item`/`Materials.block`, `Block.popResource`, `ItemStack#getDamageValue`/`setDamageValue`,
  `DataComponents.UNBREAKABLE`, and `EnchantmentHelper.updateEnchantments` are all already exercised
  by existing code elsewhere in this repo's `neoforge/` tree (see the Fabric-to-NeoForge retarget
  table in Task 1 Step 3 for exact file:line references) — no new API surface for this port to
  discover, only to reuse.
