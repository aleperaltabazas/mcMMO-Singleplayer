package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.treasure.EnchantmentTreasure;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.skills.fishing.FishingManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.mojang.serialization.Lifecycle;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Task D's headline deliverable.</b> Proves {@link FishingListener#conflictsWithAny} — which calls
 * vanilla's own
 * {@link Enchantment#areCompatible(net.minecraft.core.Holder, net.minecraft.core.Holder)} (the Fabric
 * original's static {@code Enchantment.canBeCombined(a, b)}, renamed — not removed — by 1.21's
 * data-driven enchantment rework; bytecode-confirmed present via {@code javap} against the patched
 * jar) — never lets two mutually-exclusive enchantments land on the same fished item, in every place
 * the port checks for a conflict: against what is already on the item, against what has already been
 * picked in the same roll (this port's documented deviation from the Fabric original, per
 * {@link FishingManager#selectMagicHunterEnchants}'s javadoc), and correctly does <b>not</b> block a
 * legal, non-conflicting pair.
 *
 * <p><b>Registry setup.</b> 1.21's enchantment registry is dynamic (datapack-driven) and does not exist
 * in a plain-JUnit bootstrap ({@link McTestRegistries} only freezes the static built-in registries), so
 * these tests build a small real {@link MappedRegistry} and register genuinely-real
 * {@link Enchantment} instances into it — constructed directly via {@code Enchantment}'s public
 * constructor with the confirmed real {@code exclusiveSet} passed straight in as the constructor
 * argument, rather than mocked. <b>This isn't a style choice — mocking doesn't work here.</b>
 * {@link Enchantment#areCompatible} (which {@code conflictsWithAny} now delegates to directly, per
 * code review) reads the private {@code exclusiveSet} <i>field</i> directly, not the public
 * {@link Enchantment#exclusiveSet()} accessor (confirmed reading {@code Enchantment.java} out of the
 * patched source jar), so a Mockito-stubbed accessor is invisible to it; and the field is
 * {@code final}, so it cannot be poked onto a mock via reflection after construction either (the JVM
 * rejects that write even with {@code setAccessible(true)} — verified the hard way, this class's
 * second cut). Building real instances sidesteps both problems and needs no live
 * datapack/server-resources reload just to prove a pure conflict predicate — {@code Holder.equals},
 * {@code Holder.value()}, {@code HolderSet.contains}, and now {@code Enchantment.areCompatible}
 * itself all run their real, unmocked implementations.
 *
 * <p><b>Which pairs are genuinely exclusive in 1.21.1, and how that was confirmed:</b> reading
 * {@code data/minecraft/enchantment/infinity.json} and {@code mending.json} plus
 * {@code data/minecraft/tags/enchantment/exclusive_set/bow.json} directly out of
 * {@code minecraft_1.21.1_client.jar} (the same jar this Gradle module resolves NeoForge's game
 * artifact from) shows the real registry is <b>asymmetric</b>: only {@code infinity.json} declares
 * {@code exclusive_set/bow} — {@code mending.json} has no {@code exclusive_set} key at all. The tag
 * file itself still lists exactly {@code [minecraft:infinity, minecraft:mending]}, so
 * {@code infinity.exclusiveSet()} contains {@code mending} while {@code mending.exclusiveSet()} is
 * empty — the two still conflict (via {@code areCompatible}'s own two-directional check), just not
 * because both sides "declare" the tag. The mock setup below mirrors this asymmetry exactly rather
 * than a convenient symmetric approximation, so the "checked both directions" tests below only pass
 * because {@code areCompatible} genuinely checks both {@code a.exclusiveSet().contains(b)} and
 * {@code b.exclusiveSet().contains(a)} — collapsing either check would fail one of the two
 * assertions in {@link #aRealVanillaConflictingPairConflictsInBothDirections()}.
 * {@code fortune}/{@code silk_touch} ({@code exclusive_set/mining}) was confirmed the same way as a
 * second real pair — and unlike {@code infinity}/{@code mending}, that one <i>is</i> genuinely
 * symmetric: both {@code fortune.json} and {@code silk_touch.json} independently declare
 * {@code exclusive_set/mining}. It is not used below only because {@code infinity}/{@code mending}'s
 * asymmetry makes the more exacting test. {@code unbreaking} has no {@code exclusive_set} entry at all
 * in that same data, and does not appear in any exclusive-set tag file, so it is used as the genuinely
 * non-conflicting partner for {@code sharpness}.
 */
class FishingListenerMagicHunterTest {

    private MappedRegistry<Enchantment> registry;
    private Holder.Reference<Enchantment> infinity;
    private Holder.Reference<Enchantment> mending;
    private Holder.Reference<Enchantment> sharpness;
    private Holder.Reference<Enchantment> unbreaking;

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    // FishingManager's constructor reads AdvancedConfig (Master Angler wait-cap bounds) even though
    // selectMagicHunterEnchants itself never touches it -- a real bundled config is enough since these
    // tests never exercise Master Angler.
    @BeforeEach
    void setUpAdvancedConfig(@TempDir Path dataFolder) {
        McMMOMod.setAdvancedConfig(new AdvancedConfig(dataFolder));
    }

    @AfterEach
    void tearDownAdvancedConfig() {
        McMMOMod.setAdvancedConfig(null);
    }

    private Holder.Reference<Enchantment> register(String path, Enchantment enchantment) {
        return registry.register(
                ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.withDefaultNamespace(path)),
                enchantment, RegistrationInfo.BUILT_IN);
    }

    /**
     * A minimal but genuinely real {@link Enchantment} instance, built via its actual public
     * constructor rather than mocked (see the class javadoc for why mocking cannot work here). Every
     * field besides {@code exclusiveSet} is a harmless placeholder — {@code conflictsWithAny}/
     * {@code areCompatible} never read {@code supportedItems}, cost curves, weight, or the
     * description — only {@code exclusiveSet} and identity (via {@code Holder.equals}) matter to the
     * logic under test.
     */
    private static Enchantment buildEnchantment(HolderSet<Enchantment> exclusiveSet) {
        final Enchantment.EnchantmentDefinition definition = new Enchantment.EnchantmentDefinition(
                HolderSet.direct(), Optional.empty(), 1, 1,
                new Enchantment.Cost(1, 1), new Enchantment.Cost(1, 1), 1, List.of());
        return new Enchantment(Component.empty(), definition, exclusiveSet, DataComponentMap.EMPTY);
    }

    /**
     * Builds the small fake enchantment registry every test in this class shares: {@code infinity} and
     * {@code mending} mutually exclusive via the real, <b>asymmetric</b> vanilla data (only
     * {@code infinity} declares {@code exclusive_set/bow}; {@code mending} has no {@code exclusive_set}
     * of its own — see the class javadoc for how this was confirmed against the real registry);
     * {@code sharpness} and {@code unbreaking} not exclusive of each other (real vanilla data:
     * {@code sharpness} only belongs to {@code exclusive_set/damage} and {@code unbreaking} has no
     * {@code exclusive_set} at all, so neither names the other).
     *
     * <p>{@code mending} is registered first, empty-{@code exclusiveSet}, so its real {@code Holder}
     * exists to hand to {@code infinity}'s constructor — the constructor takes {@code exclusiveSet} as
     * a plain argument (no mutation after construction), so the one-way reference has to be built in
     * dependency order.
     */
    private void setUpEnchantments() {
        registry = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());

        mending = register("mending", buildEnchantment(HolderSet.direct()));
        // Mirrors the real, asymmetric vanilla data exactly: only infinity declares exclusive_set/bow
        // (which names mending); mending itself declares no exclusive_set at all (set above). The two
        // still conflict -- via areCompatible's OTHER direction -- which is the entire point of this
        // asymmetric setup (see class javadoc).
        infinity = register("infinity", buildEnchantment(HolderSet.direct(mending)));
        // sharpness (exclusive_set/damage) and unbreaking (no exclusive_set at all) never name each
        // other -- a genuinely legal pair.
        sharpness = register("sharpness", buildEnchantment(HolderSet.direct()));
        unbreaking = register("unbreaking", buildEnchantment(HolderSet.direct()));
    }

    // --- conflictsWithAny: direct pair checks ---

    @Test
    void aRealVanillaConflictingPairConflictsInBothDirections() {
        setUpEnchantments();
        final Map<String, Holder<Enchantment>> resolved = Map.of(
                "infinity", infinity, "mending", mending);

        // infinity already on the item, mending offered as a candidate: caught by
        // infinity.exclusiveSet().contains(mending) -- the "existing's own exclusiveSet names the
        // candidate" direction.
        assertTrue(FishingListener.conflictsWithAny(Set.of(infinity), List.of(), resolved,
                new EnchantmentTreasure("mending", 1)));
        // The reverse direction: mending already on the item, infinity offered as a candidate.
        // mending's own exclusiveSet() is empty (real vanilla data -- see setUpEnchantments), so this
        // assertion can ONLY pass via the other direction -- the candidate's (infinity's) exclusiveSet
        // naming the existing enchantment (mending). Proves the check genuinely isn't one-sided: if
        // areCompatible's `!b.exclusiveSet().contains(a)` clause were ever dropped, this specific
        // assertion (not the one above) is the one that would go from true back to false.
        assertTrue(FishingListener.conflictsWithAny(Set.of(mending), List.of(), resolved,
                new EnchantmentTreasure("infinity", 1)));
    }

    @Test
    void alreadyOnItemAloneBlocksAConflictingCandidateWithNoSelectedSoFarInvolved() {
        setUpEnchantments();
        final Map<String, Holder<Enchantment>> resolved = Map.of(
                "infinity", infinity, "mending", mending);

        // selectedSoFar is empty here on purpose: this isolates the alreadyOnItem loop from the
        // selectedSoFar loop, proving alreadyOnItem alone is sufficient to block the candidate.
        final boolean conflicts = FishingListener.conflictsWithAny(Set.of(infinity), List.of(), resolved,
                new EnchantmentTreasure("mending", 1));

        assertTrue(conflicts, "an enchantment already on the item must block a conflicting candidate");
    }

    @Test
    void aNonConflictingPairIsCorrectlyAllowed() {
        setUpEnchantments();
        final Map<String, Holder<Enchantment>> resolved = Map.of(
                "sharpness", sharpness, "unbreaking", unbreaking);

        // Neither exclusiveSet names the other, and they are not the same enchantment -- must be
        // allowed. A conflictsWithAny that always returned true would fail this assertion, which is
        // the point: the exclusion-only tests above would still pass even if the predicate were
        // permanently true, so this permissive case has to be asserted too.
        assertFalse(FishingListener.conflictsWithAny(Set.of(sharpness), List.of(), resolved,
                new EnchantmentTreasure("unbreaking", 1)));
    }

    @Test
    void theSameEnchantmentAlreadyOnTheItemConflictsWithItself() {
        setUpEnchantments();
        final Map<String, Holder<Enchantment>> resolved = Map.of("sharpness", sharpness);

        // sharpness's own exclusiveSet is empty (stubbed above), so this can only be caught by the
        // identity/self check -- exercising the "or they are the same enchantment" half of
        // conflictsWithAny's contract, not the exclusiveSet half.
        assertTrue(FishingListener.conflictsWithAny(Set.of(sharpness), List.of(), resolved,
                new EnchantmentTreasure("sharpness", 1)));
    }

    // --- the selectedSoFar half: this port's documented deviation from the Fabric original ---

    @Test
    void twoConflictingCandidatesInTheSamePoolNeverBothLand() {
        setUpEnchantments();
        final Map<String, Holder<Enchantment>> resolved = Map.of(
                "infinity", infinity, "mending", mending);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        final FishingManager fishingManager = new FishingManager(mmoPlayer);

        // Both candidates present in one roll's pool, nothing already on the item. A chanceRoller that
        // always draws 0 means every candidate would land on the halving walk alone -- if
        // conflictsWithAny's selectedSoFar loop did nothing, both would be selected.
        final List<EnchantmentTreasure> candidates = List.of(
                new EnchantmentTreasure("infinity", 1), new EnchantmentTreasure("mending", 1));

        final List<EnchantmentTreasure> selected = fishingManager.selectMagicHunterEnchants(candidates,
                (selectedSoFar, candidate) -> FishingListener.conflictsWithAny(Set.of(), selectedSoFar,
                        resolved, candidate),
                bound -> 0);

        assertEquals(List.of(new EnchantmentTreasure("infinity", 1)), selected,
                "the first candidate wins the slot; the second must be excluded by the running "
                        + "selectedSoFar conflict check, not merely by the alreadyOnItem check");
    }

    @Test
    void twoNonConflictingCandidatesInTheSamePoolBothLand() {
        setUpEnchantments();
        final Map<String, Holder<Enchantment>> resolved = Map.of(
                "sharpness", sharpness, "unbreaking", unbreaking);
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        final FishingManager fishingManager = new FishingManager(mmoPlayer);

        final List<EnchantmentTreasure> candidates = List.of(
                new EnchantmentTreasure("sharpness", 1), new EnchantmentTreasure("unbreaking", 1));

        final List<EnchantmentTreasure> selected = fishingManager.selectMagicHunterEnchants(candidates,
                (selectedSoFar, candidate) -> FishingListener.conflictsWithAny(Set.of(), selectedSoFar,
                        resolved, candidate),
                bound -> 0);

        assertEquals(candidates, selected,
                "a legal, non-conflicting pair must both be able to land in the same roll -- a "
                        + "conflictsWithAny that always returned true would wrongly shrink this to one");
    }
}
