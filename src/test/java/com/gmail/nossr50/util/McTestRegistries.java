package com.gmail.nossr50.util;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.item.Item;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.common.BooleanAttribute;
import net.neoforged.neoforge.common.PercentageAttribute;

/**
 * Shared one-time Minecraft bootstrap for unit tests that touch live vanilla registries (item/block
 * id-path extraction in {@code ItemUtils}/{@code BlockUtils}, etc.). Call {@link #bootstrap()} from a
 * {@code @BeforeAll}.
 *
 * <p>Task 8: repointed from yarn to official mappings (NeoForge's {@code net.minecraft.server.Bootstrap}
 * /{@code net.minecraft.SharedConstants}, not Fabric Loom's yarn-mapped equivalents) as part of the
 * fabric/-deletion test-suite fix. Idempotent: {@link Bootstrap#bootStrap()} is itself guarded, and the
 * flag here avoids re-entry.
 *
 * <p><b>The {@link LoadingModList#of} call, and why it has to run first:</b> under plain JUnit (no
 * FML/ModLauncher), {@code LoadingModList.get()} returns {@code null} because nothing ever populated
 * FML's static mod-list holder — the Fabric original avoided this entirely by running tests under
 * {@code fabric-loader-junit}, a launcher that boots the real mod loader first. NeoForge's own
 * {@code Blocks.<clinit>} transitively reaches {@code FeatureFlags.<clinit>} ->
 * {@code FeatureFlagLoader.loadModdedFlags} -> {@code LoadingModList.get().getModFiles()}, so without
 * this line {@code Bootstrap.bootStrap()} itself throws {@code NullPointerException} wrapped in
 * {@code ExceptionInInitializerError} the first time <em>any</em> test in the JVM touches a block/item
 * constant — and because a failed class {@code <clinit>} is cached by the JVM, every other test in the
 * same worker then fails with an unrelated-looking {@code NoClassDefFoundError} for the rest of the
 * run. Feeding {@code of(...)} five empty collections is the minimal "no mods are loaded" answer that
 * satisfies the null check without pulling in FML's real mod-discovery machinery, which unit tests
 * have no need of and no classpath support for.
 *
 * <p><b>The {@code neoforge:*} attribute post-registration, and why it has to run AFTER
 * {@code Bootstrap.bootStrap()}, not before:</b> {@code Bootstrap.bootStrap()} eagerly builds an
 * {@link net.minecraft.world.entity.ai.attributes.AttributeSupplier} for every living entity type via
 * {@code DefaultAttributes.<clinit>} — including the Allay, whose {@code createAttributes()}
 * references two attributes NeoForge itself adds via
 * {@code net.neoforged.neoforge.common.NeoForgeMod}: {@code SWIM_SPEED} and
 * {@code NAMETAG_DISTANCE} (found by iterating: fixing {@code swim_speed} alone still left
 * {@code nametag_distance} throwing the identical way). Each is a {@code DeferredHolder}, which only
 * resolves once NeoForge's own {@code DeferredRegister} has fired a real {@code RegisterEvent} during
 * mod loading — machinery this headless unit-test JVM never runs (same root cause as the
 * {@code LoadingModList} gap above: no FML lifecycle at all). Without a fix,
 * {@code DefaultAttributes.<clinit>} itself throws the first time any test resolves a mob's attribute
 * supplier, and — same JVM-wide poisoned-{@code <clinit>} trap as the {@code LoadingModList} case —
 * every other test that ever touches a living-entity attribute fails for the rest of the run with an
 * unrelated-looking {@code NoClassDefFoundError}. {@code CREATIVE_FLIGHT}, {@code NeoForgeMod}'s third
 * and last custom attribute, is registered pre-emptively too even though no {@code DefaultAttributes}
 * entry threw on it in this branch's entity roster — cheaper than a third round-trip through this same
 * failure mode the moment a future Minecraft version's mob references it.
 *
 * <p>Registering <em>before</em> {@code Bootstrap.bootStrap()} does not work: touching
 * {@code BuiltInRegistries} at all before {@code Bootstrap.bootStrap()} has set its internal
 * "bootstrapped" flag throws {@code IllegalArgumentException: Not bootstrapped} from
 * {@code BuiltInRegistries.<clinit>} itself (verified — this was this fix's first, failing attempt).
 * Registering <em>after</em> a normal return from {@code Bootstrap.bootStrap()} does not work either:
 * {@code Bootstrap.bootStrap()}'s own last step, {@code BuiltInRegistries.bootStrap()}, freezes every
 * built-in registry, and a frozen {@link MappedRegistry} rejects new entries. So this briefly
 * {@link MappedRegistry#unfreeze() unfreeze}s just the attribute registry immediately after
 * {@code Bootstrap.bootStrap()} returns, registers the missing entries, and re-freezes — matching
 * NeoForge's own real definitions (verified against {@code NeoForgeMod.java}'s source for all three)
 * rather than replicating NeoForge's event-bus registration lifecycle, since a {@code DeferredHolder}
 * only cares that <em>a</em> value is bound at its resource location by the time something calls
 * {@code .value()} on it, not how it got there.
 */
public final class McTestRegistries {

    private static boolean bootstrapped;

    private McTestRegistries() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registerMissingNeoForgeAttributes();
        bootstrapped = true;
    }

    /** See the class javadoc's "neoforge:* attribute" section for why this exists and its ordering. */
    @SuppressWarnings("unchecked")
    private static void registerMissingNeoForgeAttributes() {
        final MappedRegistry<Attribute> attributes = (MappedRegistry<Attribute>) BuiltInRegistries.ATTRIBUTE;
        attributes.unfreeze();
        try {
            registerIfMissing("swim_speed",
                    () -> new PercentageAttribute("neoforge.swim_speed", 1.0D, 0.0D, 1024.0D)
                            .setSyncable(true));
            registerIfMissing("nametag_distance",
                    () -> new RangedAttribute("neoforge.name_tag_distance", 64.0D, 0.0D, 64.0D)
                            .setSyncable(true).setSentiment(Attribute.Sentiment.NEUTRAL));
            registerIfMissing("creative_flight",
                    () -> new BooleanAttribute("neoforge.creative_flight", false).setSyncable(true));
        } finally {
            attributes.freeze();
        }
    }

    private static void registerIfMissing(String path, java.util.function.Supplier<Attribute> factory) {
        final ResourceLocation id = ResourceLocation.fromNamespaceAndPath("neoforge", path);
        if (!BuiltInRegistries.ATTRIBUTE.containsKey(id)) {
            Registry.register(BuiltInRegistries.ATTRIBUTE, id, factory.get());
        }
    }

    /**
     * The vanilla item with this id path, or empty if <em>this</em> Minecraft version does not have
     * it.
     *
     * <p>Exists for items that arrive part-way through the supported range, so a test can assert the
     * right thing on every band from one source tree. The spears
     * ({@code wooden_spear} … {@code netherite_spear}) are the live case: they ship from
     * {@code 1.21.11} and do not exist on the {@code mc/1.21.10} band at all, where naming
     * {@code Items.IRON_SPEAR} is a compile error rather than a failing assertion.
     *
     * <p>⚠️⚠️ <b>{@code containsId} first — never {@code get} alone.</b> {@code Registries.ITEM} is a
     * <em>defaulted</em> registry: {@code get} on an unknown id returns {@code AIR}, not
     * {@code null}. A caller that null-checks the result of {@code get} therefore sees a perfectly
     * valid item and carries on with the wrong one. That exact trap shipped once already — the Hunter
     * skill read {@code Registries.ENTITY_TYPE} the same way and every unrecognised id silently
     * became a {@code PIG}. {@code platform/Materials} guards the same way for the same reason.
     */
    public static Optional<Item> optionalVanillaItem(String path) {
        final ResourceLocation id = ResourceLocation.withDefaultNamespace(path);
        return BuiltInRegistries.ITEM.containsKey(id) ? Optional.of(BuiltInRegistries.ITEM.get(id))
                : Optional.empty();
    }

    /**
     * True if the item registry actually populated.
     *
     * <p>The point of this is negative assertions. "This band has no spears" and "the registry is
     * empty" are the same observation, so any test that concludes something from an <em>absence</em>
     * has to rule the second one out first — otherwise a broken bootstrap reads as a clean pass on
     * every band.
     */
    public static boolean itemRegistryIsPopulated() {
        return BuiltInRegistries.ITEM.containsKey(ResourceLocation.withDefaultNamespace("iron_sword"))
                && BuiltInRegistries.ITEM.containsKey(ResourceLocation.withDefaultNamespace("stone"));
    }

    /**
     * The vanilla entity type with this id path, or empty if <em>this</em> Minecraft version does not
     * have it — {@link #optionalVanillaItem} for creatures.
     *
     * <p>The live case is the copper golem, which arrives part-way through the supported range. Below
     * that, {@code EntityType.COPPER_GOLEM} and {@code CopperGolemEntity} are both a compile error
     * rather than a failing assertion, so a test that names either cannot be built from one source
     * tree across bands.
     *
     * <p>⚠️⚠️ <b>{@code containsId} first — and here that is not merely good practice, it is the
     * exact trap this mod already shipped once.</b> {@code Registries.ENTITY_TYPE} is a
     * <em>defaulted</em> registry whose default is {@code PIG}, so {@code get} on an unknown id
     * hands back a perfectly valid pig. A test resolving {@code copper_golem} on a version without
     * one would therefore receive a pig, stub it as the victim, and assert happily that mcMMO
     * excluded a "copper golem" from Hunter XP — while actually proving that <b>pigs pay nothing</b>,
     * which is false and would have gone green. That is the same defaulted-registry bug that made
     * every unrecognised mob id a pig in Hunter's own first cut.
     */
    public static Optional<EntityType<?>> optionalVanillaEntityType(String path) {
        final ResourceLocation id = ResourceLocation.withDefaultNamespace(path);
        return BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                ? Optional.of(BuiltInRegistries.ENTITY_TYPE.get(id))
                : Optional.empty();
    }

    /**
     * True if the entity-type registry actually populated.
     *
     * <p>The {@link #itemRegistryIsPopulated} argument, for the other registry: "this version has no
     * copper golem" and "the bootstrap never ran" are the same observation from the outside, so a
     * test concluding anything from an absence has to rule the second out first. {@code zombie} and
     * {@code cow} are chosen because they predate every version in scope by roughly a decade.
     */
    public static boolean entityTypeRegistryIsPopulated() {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.withDefaultNamespace("zombie"))
                && BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.withDefaultNamespace("cow"));
    }
}
