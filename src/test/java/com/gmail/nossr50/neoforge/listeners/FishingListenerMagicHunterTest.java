package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Task D's headline deliverable.</b> Proves {@link FishingListener#conflictsWithAny} — the
 * reimplementation of vanilla's removed static {@code Enchantment.canBeCombined(a, b)} built on each
 * enchantment's instance {@link Enchantment#exclusiveSet()} — never lets two mutually-exclusive
 * enchantments land on the same fished item, in every place the port checks for a conflict: against
 * what is already on the item, against what has already been picked in the same roll (this port's
 * documented deviation from the Fabric original, per {@link FishingManager#selectMagicHunterEnchants}'s
 * javadoc), and correctly does <b>not</b> block a legal, non-conflicting pair.
 *
 * <p><b>Registry setup.</b> 1.21's enchantment registry is dynamic (datapack-driven) and does not exist
 * in a plain-JUnit bootstrap ({@link McTestRegistries} only freezes the static built-in registries), so
 * these tests build a small real {@link MappedRegistry} and register genuinely-shaped
 * {@link Holder.Reference}s into it via the real {@code register}/{@link HolderSet#direct} production
 * code paths — only the {@link Enchantment} values themselves are Mockito mocks (stubbed only for
 * {@code exclusiveSet()}, the one member {@code conflictsWithAny} reads), so {@code Holder.equals},
 * {@code Holder.value()}, and {@code HolderSet.contains} all run their real implementations exactly as
 * production does. This sidesteps needing a live datapack/server-resources reload just to prove a pure
 * conflict predicate.
 *
 * <p><b>Which pairs are genuinely exclusive in 1.21.1, and how that was confirmed:</b> reading
 * {@code data/minecraft/enchantment/infinity.json} and {@code mending.json} plus
 * {@code data/minecraft/tags/enchantment/exclusive_set/bow.json} directly out of
 * {@code minecraft_1.21.1_client.jar} (the same jar this Gradle module resolves NeoForge's game
 * artifact from) shows {@code infinity} and {@code mending} both declare
 * {@code exclusive_set/bow}, whose tag file lists exactly {@code [minecraft:infinity,
 * minecraft:mending]} — so the two conflict in this exact registry, not by inheritance from an
 * older-version or Bedrock exclusivity list. {@code fortune}/{@code silk_touch}
 * ({@code exclusive_set/mining}) was confirmed the same way as a second real pair. {@code unbreaking}
 * has no {@code exclusive_set} entry at all in that same data, and does not appear in any exclusive-set
 * tag file, so it is used as the genuinely non-conflicting partner for {@code sharpness}.
 */
class FishingListenerMagicHunterTest {

    private MappedRegistry<Enchantment> registry;
    private Enchantment infinityMock;
    private Enchantment mendingMock;
    private Enchantment sharpnessMock;
    private Enchantment unbreakingMock;
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
     * Builds the small fake enchantment registry every test in this class shares: {@code infinity} and
     * {@code mending} mutually exclusive (real vanilla {@code exclusive_set/bow} membership, confirmed
     * per the class javadoc); {@code sharpness} and {@code unbreaking} not exclusive of each other
     * (real vanilla data: {@code sharpness} only belongs to {@code exclusive_set/damage} and
     * {@code unbreaking} has no {@code exclusive_set} at all, so neither names the other).
     */
    private void setUpEnchantments() {
        registry = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
        infinityMock = mock(Enchantment.class);
        mendingMock = mock(Enchantment.class);
        sharpnessMock = mock(Enchantment.class);
        unbreakingMock = mock(Enchantment.class);

        infinity = register("infinity", infinityMock);
        mending = register("mending", mendingMock);
        sharpness = register("sharpness", sharpnessMock);
        unbreaking = register("unbreaking", unbreakingMock);

        // Mirrors the real vanilla data: infinity/mending both belong to exclusive_set/bow, so each
        // side's exclusiveSet() names the other.
        when(infinityMock.exclusiveSet()).thenReturn(HolderSet.direct(mending));
        when(mendingMock.exclusiveSet()).thenReturn(HolderSet.direct(infinity));
        // sharpness (exclusive_set/damage) and unbreaking (no exclusive_set at all) never name each
        // other -- a genuinely legal pair.
        when(sharpnessMock.exclusiveSet()).thenReturn(HolderSet.direct());
        when(unbreakingMock.exclusiveSet()).thenReturn(HolderSet.direct());
    }

    // --- conflictsWithAny: direct pair checks ---

    @Test
    void aRealVanillaConflictingPairConflictsInBothDirections() {
        setUpEnchantments();
        final Map<String, Holder<Enchantment>> resolved = Map.of(
                "infinity", infinity, "mending", mending);

        // infinity already on the item, mending offered as a candidate.
        assertTrue(FishingListener.conflictsWithAny(Set.of(infinity), List.of(), resolved,
                new EnchantmentTreasure("mending", 1)));
        // The reverse direction: mending already on the item, infinity offered as a candidate --
        // proves the check is not one-sided (matters because vanilla's exclusive_set data is not
        // guaranteed to be declared symmetrically on both entries).
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
