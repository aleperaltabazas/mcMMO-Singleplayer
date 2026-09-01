# Food Listener (NeoForge) Design

**Status:** design for a genuinely unported seam — Farmer's Diet, Fisherman's Diet, and Power Cook
have no NeoForge presence at all (`grep -rl "FarmersDiet\|FishermansDiet\|PowerCook"
src/main/java/com/gmail/nossr50/neoforge/` returns nothing).

## What this ports

Fabric's `fabric.listeners.FoodListener` (full text recovered at commit `ef5fd3d1a~1`,
`src/main/java/com/gmail/nossr50/fabric/listeners/FoodListener.java`) plus its driving mixin,
`fabric.mixin.FoodComponentMixin`, both un-abbreviated. One seam, two tenants:

- **The diets** — Herbalism's Farmer's Diet, Fishing's Fisherman's Diet — restore one extra hunger
  point per rank on the foods their own skill claims, mutually exclusive with each other.
- **Cooking's Power Cook** — grants the eaten food's mapped status effect, unconditionally, for
  every food, run *after* the diet check on the same seam.

All rank math is already MC-free and NeoForge-agnostic on the managers
(`HerbalismManager.farmersDiet(int)`/`isFarmersDietFood`, `FishingManager.handleFishermanDiet(int)`/
`isFishermansDietFood`, `CookingManager.powerCookEffect(String)`) — these live under
`com.gmail.nossr50.skills.*`, not `fabric.*`, and need no changes. This port is purely the MC-typed
glue: item classification already done by the managers, one registry lookup, and the hunger-bar
mutation.

## The seam

Fabric hooked `LivingEntity#eatFood(World, ItemStack, FoodComponent)` (yarn name) at `TAIL`, because
`PlayerEntity` applies `getHungerManager().eat(...)` *before* delegating up via `super`, so a tail
injection on the `LivingEntity` declaration runs after vanilla has finished eating (bytecode-verified
in the Fabric original, see its javadoc for the exact reasoning).

**Confirmed via `javap` against this repo's extracted 1.21.1 client jar
(`/tmp/hunter-research/extracted/net/minecraft/world/entity/LivingEntity.class`):** the identical
funnel exists under Mojang mappings —

```
public net.minecraft.world.item.ItemStack eat(net.minecraft.world.level.Level,
    net.minecraft.world.item.ItemStack, net.minecraft.world.food.FoodProperties);
```

i.e. `LivingEntity#eat(Level, ItemStack, FoodProperties)` — same funnel, same three-argument shape,
just renamed and with `FoodComponent` renamed to `FoodProperties`. `Player` is expected to override
this the same way `PlayerEntity` did on Fabric (apply hunger, then `super`); **the implementer must
re-verify the override order via `javap -c` on `Player.class` before writing the mixin**, since the
"vanilla eats first, then we top up" ordering is the entire reason this hook is `TAIL` and not `HEAD`
— do not assume the Fabric bytecode finding transfers without checking Mojang's own compiled order.

The player's hunger state itself is reached through `Player#getFoodData()` → `FoodData`, not the
Fabric `HungerManager` name — confirm `FoodData`'s getter/setter names (`getFoodLevel`/
`setFoodLevel`/`getSaturationLevel`/`setSaturationLevel`, and whatever constant replaces
`HungerConstants.FULL_FOOD_LEVEL`, likely just the literal `20`) via `javap` before implementing
`applyBonus`.

## Mixin shape

One `@Mixin(LivingEntity.class)`, `@Inject` at `TAIL` on `eat(Level, ItemStack, FoodProperties)`,
`allow = 1`, forwarding to a new `FoodListener.onFoodConsumed(Level, LivingEntity, ItemStack,
FoodProperties)`. This is a concrete injector on a concrete class (`LivingEntity`), not an interface
— no risk under the target-type-inference boot-crash rule.

## Ordering trap (carried over verbatim — do not re-litigate)

The Fabric original's javadoc calls out, in its own words, "the fourth time this port has been bitten
by exactly this shape": the two diets are mutually exclusive *with each other only*. Any skill fired
on every food (Power Cook) must be its own unconditional call, never an `else if` appended to the
diet chain — the diets between them claim 17 of the 40 vanilla edible items, which are precisely the
cooked/crafted foods a cook cares about. Keep `applyDietBonus` and `applyPowerCook` as two sealed,
separately-called methods exactly as Fabric structured them; this is not an area for "simplification."

## Everything else

`applyPowerCook`'s effect-name-to-`Holder<MobEffect>` resolution (`Potions.matchEffect` in this
repo's platform layer — already NeoForge-adapted, used elsewhere), the upgrade-not-overwrite
delegation to vanilla's own `addEffect`/`MobEffectInstance` upgrade logic, and the unresolved-name
warn-once-per-name `ConcurrentHashMap.newKeySet()` guard all port unchanged — no MC-version-specific
behavior in any of them beyond type renames (`StatusEffect` → `MobEffect`, `StatusEffectInstance` →
`MobEffectInstance`, `RegistryEntry` → `Holder`).

`applyBonus`'s clamp arithmetic (food level `[0, 20]`, saturation `[0, newFoodLevel]`) and the
saturation-proportion formula (`saturation * bonusFood / nutrition`, justified in the Fabric javadoc
by the pre-1.20.5→post-1.20.5 `FoodData.eat`-product-becomes-absolute-saturation change) port
unchanged — this MC version already has that change, same as Fabric's target version, so no new
justification is needed, just the type/name renames.

## What NOT to change

- Do not fold the diet chain and the Power Cook call into one method or one loop. See "Ordering
  trap" above.
- Do not move the `nutrition <= 0` guard — it sits above everything so a consumable that restores no
  hunger is unreachable for every tenant, and a shipped Power Cook food test relies on this exact
  placement.
- Do not special-case mobs (non-player `LivingEntity`s eating, e.g. via items with a consumable
  component) beyond the existing `instanceof ServerPlayer` guard — mobs eat too, and have no mcMMO
  data.
