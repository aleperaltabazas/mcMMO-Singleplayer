# Cooking + Smelting Listener (NeoForge) Design

## Goal

Port Cooking and Smelting from Fabric to NeoForge 1.21.1. Planned and implemented as **one
combined effort**, not two — see Background for why.

## Background

Cooking and Smelting are, mechanically, one furnace subsystem split by which item is in the
input slot: ore is Smelting's, food is Cooking's, never both. The Fabric originals
(`SmeltingListener.java`, `CookingListener.java`) share:

- one `BlockPos → UUID` furnace-owner map (`SmeltingListener.FURNACE_OWNERS`), recorded on
  right-click;
- one mixin injecting into the furnace's tick loop, whose three hooks (Smelting XP / Second
  Smelt / Fuel Efficiency) all resolve "which skill owns this input" the same way, checking
  Smelting first;
- a shared `materialConfigString` key-derivation helper that both listeners call, so the two
  key spaces (Smelting's input-keyed XP, Cooking's input/result-keyed XP) can never drift apart.

Cooking additionally owns two seams of its own that Smelting has no equivalent for: the
**campfire** (which does not share the furnace's tick loop or mixin at all — `CampfireBlockEntity`
is not an `AbstractFurnaceBlockEntity`) and the **crafting grid** (a player collecting a batch of
crafted food, `CraftingResultSlot` in Fabric / `ResultSlot` in Mojang mappings).

Splitting this into two separate plans would either duplicate the owner-map/mutual-exclusivity
design work or silently let the two skills' orderings drift apart — the same incoherence the
Fabric code's own comments explicitly warn against ("a furnace that paid Smelting's XP while
boosting Cooking's fuel would be incoherent").

## Mojang-Mapping Verification (against the patched 1.21.1 jars)

Verified via `javap -p -c` against
`build/moddev/artifacts/neoforge-21.1.248-merged.jar`, and by reading the decompiled `.java`
sources this jar ships alongside its `.class` files (present under the same package paths —
e.g. `net/neoforged/neoforge/event/EventHooks.java`), which let several of the findings below be
confirmed by reading real source rather than reconstructing bytecode by hand.

### `AbstractFurnaceBlockEntity` (furnace/smoker/blast furnace — Fabric's `tick`)

| Fabric (Yarn) | NeoForge 1.21.1 (Mojang) | Notes |
|---|---|---|
| `tick` (static) | `serverTick` (static) | Same 4-param shape: `Level, BlockPos, BlockState, AbstractFurnaceBlockEntity` |
| `craftRecipe` | `burn` (private static) | Called, then conditionally followed immediately by `setRecipeUsed` — **same two-call shape as Fabric**, confirmed by reading `serverTick`'s bytecode: `invokestatic burn` → `ifeq skip` → `aload_3; aload 10; invokevirtual setRecipeUsed`. |
| `setLastRecipe` | `setRecipeUsed(RecipeHolder<?>)` (public **instance**, not static) | Still only reached on the branch where `burn` returned true. |
| `getFuelTime` | `getBurnDuration(ItemStack)` (protected **instance**) | Still called inline in `serverTick` via `invokevirtual`; `ModifyExpressionValue` still applies. |

`SLOT_INPUT` / `SLOT_FUEL` / `SLOT_RESULT` are `protected static final int` fields on
`AbstractFurnaceBlockEntity` — use these constants rather than hardcoded `0`/`1`/`2` (Fabric's
mixin used raw literals with a comment; NeoForge's own mapped names give us the real constants,
so there's no reason not to reference them directly).

### `AbstractFurnaceBlockEntity#getBurnDuration` → real NeoForge event (no mixin needed)

`getBurnDuration` calls `ItemStack#getBurnTime(RecipeType)`
(`IItemStackExtension.getBurnTime`, a NeoForge extension default method), which ends in:

```java
return EventHooks.getItemBurnTime(self(), burnTime, recipeType);
```

and `EventHooks.getItemBurnTime` is a **thin, unconditional wrapper** that posts
`net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent` (cancellable,
`getItemStack()` / `getRecipeType()` / `getBurnTime()` / `setBurnTime(int)`) and returns
`event.getBurnTime()`. This fires for **every** furnace-family burn-time lookup — confirmed by
reading `EventHooks.java` and `IItemStackExtension.java` directly, not inferred. **Fuel
Efficiency and Kitchen Efficiency need no mixin at all** — a plain
`@SubscribeEvent`/`NeoForge.EVENT_BUS.addListener` on `FurnaceFuelBurnTimeEvent` replaces the
`ModifyExpressionValue` on `getFuelTime` outright. This is a genuine simplification over Fabric.

### `FurnaceResultSlot#checkTakeAchievements` (extraction — Fabric's `FurnaceOutputSlot#onCrafted`) — **still needs a mixin**

The initial sizing pass flagged `PlayerEvent.ItemSmeltedEvent` as a possible drop-in
replacement for `FurnaceOutputSlotMixin`. Reading `FurnaceResultSlot.java`'s real decompiled
source disproves that:

```java
@Override
protected void checkTakeAchievements(ItemStack p_39558_) {
    p_39558_.onCraftedBy(this.player.level(), this.player, this.removeCount);
    if (this.player instanceof ServerPlayer serverplayer && this.container instanceof AbstractFurnaceBlockEntity abstractfurnaceblockentity) {
        abstractfurnaceblockentity.awardUsedRecipesAndPopExperience(serverplayer);   // <-- XP orbs spawn HERE
    }
    this.removeCount = 0;
    net.neoforged.neoforge.event.EventHooks.firePlayerSmeltedEvent(this.player, p_39558_);  // <-- event fires AFTER
}
```

Two disqualifying facts, both confirmed by this source read:

1. **The event fires *after* `awardUsedRecipesAndPopExperience` has already spawned the XP
   orbs.** Understanding the Art needs its multiplier in place *before* that call, to scale the
   orb about to spawn (exactly the reason Fabric used a `ThreadLocal` set at HEAD and read from
   inside `dropExperience`/`createExperience`). An event that fires after the orbs already
   exist cannot do this.
2. **`PlayerEvent.ItemSmeltedEvent` carries only `getSmelting(): ItemStack`** — a single-item
   stack, not a batch count. (Confirmed: `ItemSmeltedEvent`'s only field is
   `private final ItemStack smelting`, set from the same `p_39558_` argument
   `checkTakeAchievements` receives — there is no `removeCount` on the event.) Understanding the
   Art's multiplier itself doesn't need a batch count (it scales the orb amount, not a per-item
   price), so this point doesn't independently block the event — point 1 alone does.

**Design: port `FurnaceOutputSlotMixin` as-is, retargeted.** Mixin
`FurnaceResultSlot#checkTakeAchievements(ItemStack)` (Mojang name, `protected`, still injectable
regardless of visibility) at HEAD/RETURN, `@Shadow @Final private Player player`, exactly
mirroring Fabric's structure. `PlayerEvent.ItemSmeltedEvent` is **not used** in this port.

### `ResultSlot#checkTakeAchievements` (crafting grid — Fabric's `CraftingResultSlot#onCrafted`) — **still needs a mixin**

Same disqualifying pattern, checked directly against `ResultSlot.java`:

```java
@Override
protected void checkTakeAchievements(ItemStack p_40185_) {
    if (this.removeCount > 0) {
        p_40185_.onCraftedBy(this.player.level(), this.player, this.removeCount);
        net.neoforged.neoforge.event.EventHooks.firePlayerCraftingEvent(this.player, p_40185_, this.craftSlots);
    }
    if (this.container instanceof RecipeCraftingHolder recipecraftingholder) {
        recipecraftingholder.awardUsedRecipes(this.player, this.craftSlots.getItems());
    }
    this.removeCount = 0;
}
```

`PlayerEvent.ItemCraftedEvent` fires *before* `removeCount` is reset this time (unlike the
furnace case), but it still only carries `getCrafting(): ItemStack` (single item) and
`getInventory(): Container` (the crafting grid, not the result slot) — **no batch count is
exposed**, and Cooking's crafting-grid XP is explicitly priced per item
(`Experience_Values.Cooking.Cook` × `items`, per the Fabric javadoc's own warning: *"a RETURN
injection would read a batch size of zero every time and pay nothing at all"*). Without the
batch count, `ItemCraftedEvent` cannot drive a correctly-priced award.

**Design: port `CraftingResultSlotMixin` as-is, retargeted to `ResultSlot`.** HEAD injection on
`checkTakeAchievements(ItemStack)`, `@Shadow @Final private Player player`,
`@Shadow private int amount` → renamed field is actually `removeCount` in 1.21.1 (Mojang name;
Fabric/Yarn called it `amount`) — shadow it as `removeCount`. HEAD is still mandatory, not
RETURN, for the same reason as Fabric: the last statement zeroes the field.
`PlayerEvent.ItemCraftedEvent` is **not used** in this port.

### `CampfireBlockEntity` (Fabric's `litServerTick`)

| Fabric (Yarn) | NeoForge 1.21.1 (Mojang) |
|---|---|
| `litServerTick` (private?) | `cookTick` (**public** static) |
| `ItemScatterer.spawn(World, D, D, D, ItemStack)` | `Containers.dropItemStack(Level, D, D, D, ItemStack)` |

Confirmed via bytecode read of `cookTick`: the scatter call is a direct `invokestatic`, not
inside a lambda (the recipe-matching `.map(craft)` call *is* a lambda, but it's not the
injection target). Same `@ModifyArg(index = 4)` shape as Fabric. The raw (pre-cook) input is
still uniquely recoverable as the sole `SingleRecipeInput` local in the method — same
implicit-`@Local` type-uniqueness trick Fabric's `CampfireCookMixin` used, for the same reason
documented there (capturing by numeric LVT index is a silent-swap risk; capturing by
`SingleRecipeInput` type is the only local of that type, so a future refactor that breaks the
assumption fails loudly at Mixin apply time instead of silently paying the wrong config key).

### Owner tracking (both skills)

`UseBlockCallback` → `PlayerInteractEvent.RightClickBlock`, the same real NeoForge event this
project already uses for `SuperAbilityListener`'s Herbalism right-click interactions
(`src/main/java/com/gmail/nossr50/neoforge/listeners/SuperAbilityListener.java`). Registered via
`NeoForge.EVENT_BUS.addListener(...)`, matching that file's existing `onUseBlock` pattern —
observe-only (never cancel), guard on `event.getEntity() instanceof ServerPlayer` /
`!event.getLevel().isClientSide()` the same way Fabric's `PASS`-on-client-side check did.

### Smelted-ore-product index (Smelting's `indexSmeltedOreProducts`)

Fabric used two separate hooks (`ServerLifecycleEvents.SERVER_STARTED` +
`ServerLifecycleEvents.END_DATA_PACK_RELOAD`). NeoForge has `ServerStartedEvent` (confirmed
present in this NeoForge version) for the first case. For "runs again after every data-pack
reload," the idiomatic NeoForge pattern (used by other mods for exactly this kind of
recipe-derived index) is registering a lightweight `PreparableReloadListener` via
`AddReloadListenerEvent#addListener` — reload listeners run as part of every resource/data-pack
reload, initial server start included, so **this can be a single hook covering both cases**
rather than Fabric's two. This must be verified against the real reload-listener execution
order during implementation (recipes must already be reloaded by the time our listener's
`apply()` runs) — flagged as a Task risk, not asserted as fact here.

### `SmeltingRecipe`, `RecipeHolder`, `Ingredient`

Confirmed present with these exact names in 1.21.1 Mojang mappings:
`net.minecraft.world.item.crafting.SmeltingRecipe`,
`net.minecraft.world.item.crafting.RecipeHolder<?>`,
`net.minecraft.world.item.crafting.Ingredient`. `SmeltingRecipe#craft` still exists for reading
a recipe's result the same way Fabric's `EMPTY_RECIPE_INPUT` trick did (recipe's own `result()`
field is not public); use `SingleRecipeInput` (the 1.21.1 rename of Fabric's
`SingleStackRecipeInput`) as the throwaway input.

## Design Decisions

- **One package, shared owner map.** Port `SmeltingListener` and `CookingListener` as two
  classes in `com.gmail.nossr50.neoforge.listeners`, exactly mirroring the Fabric split — the
  furnace-owner map (`FURNACE_OWNERS`) and `materialConfigString` stay on `SmeltingListener`,
  package-visible, exactly as Fabric structured it (Cooking's furnace-side logic is "the food
  branch of `SmeltingListener#onFurnaceSmelt`," per the Fabric javadoc — don't restructure this
  during the port).
- **Mixin interfaces stay pure-abstract.** Per the lesson from
  `LivingEntityDropFromLootTableAccessor` (a `static` helper inside a `@Mixin`-annotated
  interface broke Sponge Mixin's target-type inference and crashed the game at boot): none of
  this port's mixins are accessor/invoker interfaces needing static call-shape helpers (they're
  all `@Inject`/`@ModifyArg`/`@ModifyExpressionValue` on abstract mixin *classes*, which have no
  such constraint), but if any call-shape helper class becomes necessary, it must be a plain
  class, never a member of a `@Mixin` interface.
- **`FurnaceFuelBurnTimeEvent` replaces one mixin outright; the other three furnace-adjacent
  seams still need mixins**, each ported close to 1:1 from its Fabric original with renamed
  targets. This is a smaller net reduction than the initial sizing pass estimated (which
  expected 3 of 4 old seams to become pure events) — only 1 of 4 actually does, once the
  post-hoc-event and missing-batch-count problems above are accounted for.

## Files

- `src/main/java/com/gmail/nossr50/neoforge/listeners/SmeltingListener.java` (new)
- `src/main/java/com/gmail/nossr50/neoforge/listeners/CookingListener.java` (new)
- `src/main/java/com/gmail/nossr50/neoforge/mixin/AbstractFurnaceSmeltMixin.java` (new — 2
  injectors: smelt-complete via `burn`, second-smelt via `setRecipeUsed`; Fuel Efficiency is
  **not** here, it's the `FurnaceFuelBurnTimeEvent` listener in `SmeltingListener`)
- `src/main/java/com/gmail/nossr50/neoforge/mixin/FurnaceResultSlotMixin.java` (new — HEAD/RETURN
  on `checkTakeAchievements`, Understanding the Art thread-local)
- `src/main/java/com/gmail/nossr50/neoforge/mixin/ResultSlotMixin.java` (new — HEAD on
  `checkTakeAchievements`, Cooking crafting-grid XP)
- `src/main/java/com/gmail/nossr50/neoforge/mixin/CampfireCookMixin.java` (new — `@ModifyArg` on
  `Containers.dropItemStack` inside `cookTick`)
- `src/main/resources/mcmmo.mixins.json` (add the four new mixin classes)
- Corresponding test files under `src/test/java/com/gmail/nossr50/neoforge/{listeners,mixin}/`

## Global Constraints

- Every mixin target's exact signature must be re-verified with `javap` against
  `build/moddev/artifacts/neoforge-21.1.248-merged.jar` at implementation time — the signatures
  in this doc were read from that jar's own decompiled `.java` sources (bundled alongside the
  `.class` files) during planning, but implementers must re-confirm rather than copy blind,
  per this project's established practice.
- `allow = 1` / `require = 1` on every call-site-anchored injector (not HEAD/RETURN into a named
  method, which needs neither), matching the Fabric originals' own stated rationale: a silent
  second bind would double-apply a bonus rather than fail loudly.
- Boot verification is mandatory before this work is considered done: run
  `timeout 150 ./gradlew runServer --console=plain 2>&1 | tee /tmp/runserver-cooksmelt.log | grep -iE "mixin|InvalidMixinException|FATAL|MixinApplyError|Done \("`
  and confirm `Done (...)!` with zero mixin errors — unit tests cannot exercise Mixin
  application at all (confirmed in an earlier session: plain `./gradlew test` has no
  ModLauncher wiring), and this project has already hit one real boot crash
  (`LivingEntityDropFromLootTableAccessor`) that no unit test could have caught.
