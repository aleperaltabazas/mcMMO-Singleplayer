# Alchemy Listener (NeoForge) Design

**Why a spec for this one:** two design questions have real wrong-answer risk — whether
`RegisterBrewingRecipesEvent`/`IBrewingRecipe` can replace the recipe-recognition mixin (it
cannot; verified below), and the exact lifecycle NeoForge's `PotionBrewEvent` fires at (it does
not replace recipe recognition, only the craft/XP seam). Both were checked against the real
NeoForge 21.1.248 merged jar's bundled sources, not assumed from the Fabric original.

## Source

Fabric original, recoverable at commit `d0764257671576525aedd97308be2f8c6d85e2fd`:
- `src/main/java/com/gmail/nossr50/fabric/listeners/AlchemyListener.java` (155 lines)
- `src/main/java/com/gmail/nossr50/fabric/mixin/BrewingStandBlockEntityMixin.java` (3 injectors:
  `canCraft` HEAD cancellable, `craft` HEAD cancellable, `tick` HEAD)
- `src/main/java/com/gmail/nossr50/fabric/mixin/BrewingStandBrewTimeAccessor.java` (pure accessor
  interface, 2 methods — already "clean" under the boot-crash lesson, no static members to move)

MC-free logic (current, unchanged, do not modify): `AlchemyPotionBrewer.isValidBrew(PlatformInventory)`,
`AlchemyPotionBrewer.finishBrewing(PlatformInventory, McMMOPlayer)`, `CatalysisTimer`.

## Verified NeoForge 1.21.1 signatures

Checked directly against `build/moddev/artifacts/neoforge-21.1.248-merged.jar` (javap on the
`.class`, and read the bundled `.java` sources for `net.neoforged.neoforge.event.brewing.*`
and `net.minecraft.world.item.alchemy.PotionBrewing`, both included in that jar).

- `BrewingStandBlockEntity`: `int brewTime;` — package-private field, declared directly on the
  class (not a nested nonstatic type the way Fabric's was). No getter/setter. Same shape as
  Fabric: still needs an accessor mixin.
- `private static boolean isBrewable(PotionBrewing, NonNullList<ItemStack>)` — private static,
  exact Fabric-equivalent shape (`canCraft(BrewingRecipeRegistry, DefaultedList<ItemStack>)`
  renamed/retyped). No event fires here. **Still needs a mixin** (see verdict below).
- `private static void doBrew(Level, BlockPos, NonNullList<ItemStack>)` — private static, the
  Fabric-equivalent of `craft`. Fires `EventHooks.onPotionAttemptBrew(stacks)` →
  `PotionBrewEvent.Pre` at its own head (confirmed by reading `PotionBrewEvent.java`'s own
  javadoc: "The event is fired during the `BrewingStandBlockEntity#doBrew(...)` method
  invocation").
- `public static void serverTick(Level, BlockPos, BlockState, BrewingStandBlockEntity)` — the
  Fabric-equivalent of `tick`, unchanged shape (still calls `isBrewable`/`doBrew`/decrements
  `brewTime` inline).

## `PotionBrewEvent.Pre`/`.Post` — genuine simplification for the craft/XP seam

`PotionBrewEvent.Pre` is `ICancellableEvent`, exposes `getItem(int)`/`setItem(int, ItemStack)`
over the *same 5-slot array* `doBrew` itself operates on (its own javadoc: "holding all items in
Brewer"), and per `PotionBrewEvent.Pre`'s javadoc: **"If this event is canceled, and items have
been modified, PotionBrewEvent.Post will automatically be fired."** — NeoForge writes the
mutated array back into the real block entity and posts `Post` itself; nothing else to wire.

This replaces the Fabric `craft` mixin outright: a plain `NeoForge.EVENT_BUS` listener on
`PotionBrewEvent.Pre`, mirroring `AlchemyListener.onBrewCraft`'s current body 1:1 (mutate the 5
slots via `AlchemyPotionBrewer.finishBrewing`, then cancel the event). No mixin needed for this
seam — a genuine win, same shape as Cooking/Smelting's `ItemSmeltedEvent`/`ItemCraftedEvent`
findings.

**Slot indexing caveat to verify at implementation time:** confirm `PotionBrewEvent`'s 5-slot
array is index-identical to the `DefaultedList<ItemStack>` Fabric's `craft` mixin handed
`AlchemyPotionBrewer` (bottle slots 0-2, ingredient 3, fuel 4) — the javadoc says "all items in
Brewer" but does not spell out slot order; cross-check against `BrewingStandBlockEntity`'s own
slot-count constants (`FUEL_SLOT`, `INGREDIENT_SLOT` if present) in the merged jar before wiring
`onBrewCraft`.

## Verdict: `RegisterBrewingRecipesEvent`/`IBrewingRecipe` cannot replace the `isBrewable` mixin

Investigated because it looked like it might eliminate the last mixin. Rejected — confirmed by
reading the actual NeoForge source in the merged jar:

- `IBrewingRecipe` (`net.neoforged.neoforge.common.brewing.IBrewingRecipe`) has exactly three
  methods: `isInput(ItemStack)`, `isIngredient(ItemStack)`, `getOutput(ItemStack input,
  ItemStack ingredient)`. Registered via `PotionBrewing.Builder#addRecipe(IBrewingRecipe)` inside
  `RegisterBrewingRecipesEvent`.
- Vanilla's `PotionBrewing` calls this per-slot, per-pair — `hasMix`/`mix` iterate the 3 bottle
  slots independently against the 1 ingredient slot, with no access to the stand's full inventory,
  no `Level`/`BlockPos`, no player/owner, and no ability to run arbitrary side effects (award XP,
  read Catalysis state) beyond returning a transformed `ItemStack`.
- `AlchemyPotionBrewer.isValidBrew(PlatformInventory)` and `.finishBrewing(PlatformInventory,
  McMMOPlayer)` both take the **whole inventory** (confirmed by reading their real signatures in
  `src/main/java/com/gmail/nossr50/skills/alchemy/AlchemyPotionBrewer.java:93,129`) and
  `finishBrewing` needs the resolved owner for XP — neither decomposes into the
  per-slot-pair shape `IBrewingRecipe` requires, and `IBrewingRecipe` has no owner/XP path at all.

**Conclusion: the `isBrewable` mixin stays.** `RegisterBrewingRecipesEvent` is the wrong tool —
it's for recipes expressible as an independent per-slot predicate/transform, not for mcMMO's
whole-inventory, side-effecting brew logic.

## Architecture

Mixed, one net mixin fewer than Fabric's three:

- **Owner tracking** (unchanged in shape from Fabric): `PlayerInteractEvent.RightClickBlock`,
  same event and pattern `SuperAbilityListener.onUseBlock` already uses on this branch.
- **Craft + XP** (was a mixin, now a plain listener): `PotionBrewEvent.Pre`, cancel-and-mutate,
  as above.
- **Recipe recognition** (still a mixin): one `@Inject(cancellable, HEAD)` on `isBrewable`,
  forcing `true` for a recognized mcMMO brew — direct analogue of Fabric's `canCraft` injector.
- **Catalysis** (still a mixin): the existing accessor-mixin shape for `brewTime` (unchanged from
  Fabric, already "pure" — 2 abstract `@Accessor` methods, no static members, no boot-crash risk)
  plus one `@Inject(HEAD)` on `serverTick`, direct analogue of Fabric's `tick` injector.

Net: 1 mixin file with 2 injectors (`isBrewable` force-true, `serverTick` Catalysis) + 1 pure
accessor mixin (`brewTime`) + 1 listener class with 2 real-event methods (owner tracking,
`PotionBrewEvent.Pre`) plus the same MC-free-delegating internals Fabric had
(`isValidBrew`/`onBrewCraft`/`applyCatalysis`/`resolveBrewSpeed`).
