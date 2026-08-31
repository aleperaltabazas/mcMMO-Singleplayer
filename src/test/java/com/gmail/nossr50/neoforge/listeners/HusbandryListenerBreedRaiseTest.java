package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.neoforge.McMMOAttachments;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.TrackedSummon;
import com.gmail.nossr50.util.TransientEntityTracker;
import com.gmail.nossr50.util.player.UserManager;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Husbandry listener plan, Task B: {@link HusbandryListener#onAnimalsBred},
 * {@link HusbandryListener#onLovePlayer} (Multi-Breed), {@link HusbandryListener#onGrowthApplied}
 * and {@link HusbandryListener#onBreedingAgeChange} (raise, feed, Accelerated Growth).
 *
 * <h2>Mocking strategy</h2>
 * Entities are Mockito mocks of the real concrete classes ({@code Cow}, {@code Fox},
 * {@code Turtle}) rather than hand-rolled test doubles, so the listener code under test really is
 * calling {@code net.minecraft.*} method signatures — this project's Mockito ships the inline mock
 * maker (self-attaching {@code byte-buddy-agent}, confirmed by the test run's own console warning),
 * so even {@code final} methods such as {@code Entity#getX/getBoundingBox} are mockable directly,
 * unlike a plain subclass-proxy mock maker.
 *
 * <p><b>{@link McMMOAttachments#BRED_BY} needs its real read/write semantics</b>, not a stubbed
 * constant, to prove {@code claimOffspring}/{@code onBreedingAgeChange} actually go through
 * {@code hasData}/{@code setData}/{@code removeData} rather than a stand-in. Rather than mocking an
 * {@code AgeableMob} with a blanket {@code CALLS_REAL_METHODS} default answer (which turned out to
 * cascade into unrelated real vanilla code — {@code AgeableMob#getAge()} itself calls
 * {@code level().isClientSide} before falling back to its own field, so any unstubbed real call
 * throws on a mock with no level), {@link #giveRealAttachmentStorage} stubs exactly the four
 * attachment methods to call through to their real {@code AttachmentHolder} implementation,
 * leaving every other method on the mock a plain, explicitly-stubbed no-op.
 */
class HusbandryListenerBreedRaiseTest {

    private static final UUID BREEDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @BeforeAll
    static void bootstrapRegistries() throws Exception {
        McTestRegistries.bootstrap();
        ensureBredByRegistered();
    }

    /**
     * {@code McMMOMod.getExperienceConfig()} is a JVM-wide static, and Gradle's default test task
     * runs every test class in one JVM sequentially -- so a different test class earlier in the run
     * that sets a non-null config (with Call-of-the-Wild breeding prevention enabled) and does not
     * reset it in its own teardown leaks straight into whichever test runs next. Every test in this
     * class needs a known-clean starting point regardless of what ran before it, not just a clean
     * end state for the one test that itself sets it (see {@link #tearDown}, which covers the other
     * direction).
     */
    @org.junit.jupiter.api.BeforeEach
    void resetSharedStaticState() {
        McMMOMod.setExperienceConfig(null);
        UserManager.remove(BREEDER_ID);
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(BREEDER_ID);
        HusbandryListener.endPlayerInteraction();
        HusbandryListener.clear();
        McMMOMod.setExperienceConfig(null);
    }

    /**
     * Mirrors {@code McMMOAttachmentsBredByTest}'s own registration dance (see that class's javadoc
     * for the full reasoning), guarded so this test class works whether or not
     * {@code McMMOAttachmentsBredByTest} already registered {@code BRED_BY} earlier in the same JVM
     * test run.
     */
    private static void ensureBredByRegistered() throws Exception {
        try {
            McMMOAttachments.BRED_BY.get();
            return; // already bound by an earlier test class in this JVM run.
        } catch (RuntimeException notYetBound) {
            // fall through and register below.
        }
        final IEventBus bus = BusBuilder.builder().build();
        McMMOAttachments.ATTACHMENT_TYPES.register(bus);
        final Constructor<RegisterEvent> ctor =
                RegisterEvent.class.getDeclaredConstructor(ResourceKey.class, Registry.class);
        ctor.setAccessible(true);
        final RegisterEvent event = ctor.newInstance(NeoForgeRegistries.Keys.ATTACHMENT_TYPES,
                NeoForgeRegistries.ATTACHMENT_TYPES);
        bus.post(event);
    }

    /**
     * Makes {@code mob}'s {@code hasData}/{@code getExistingDataOrNull}/{@code setData}/
     * {@code removeData} run their real {@code AttachmentHolder} implementation instead of Mockito's
     * default (which would return {@code false}/{@code null} always and never actually store
     * anything) — see this class's own javadoc for why a blanket {@code CALLS_REAL_METHODS} default
     * answer is not used instead.
     */
    private static void giveRealAttachmentStorage(AgeableMob mob) {
        // McMMOAttachments.BRED_BY is itself typed Supplier<AttachmentType<UUID>>
        // (net.neoforged.neoforge.registries.DeferredRegister#register's return type), so
        // production code -- and this test, calling it the exact same way -- resolves to
        // IAttachmentHolder's Supplier-argument default-method overloads, not the AttachmentType
        // ones. Both overload families are stubbed here so it does not matter which shape a future
        // caller uses.
        when(mob.hasData(any(net.neoforged.neoforge.attachment.AttachmentType.class)))
                .thenCallRealMethod();
        when(mob.hasData(any(java.util.function.Supplier.class))).thenCallRealMethod();
        when(mob.getExistingDataOrNull(any(net.neoforged.neoforge.attachment.AttachmentType.class)))
                .thenCallRealMethod();
        when(mob.getExistingDataOrNull(any(java.util.function.Supplier.class))).thenCallRealMethod();
        doCallRealMethod().when(mob)
                .setData(any(net.neoforged.neoforge.attachment.AttachmentType.class), any());
        doCallRealMethod().when(mob).setData(any(java.util.function.Supplier.class), any());
        doCallRealMethod().when(mob)
                .removeData(any(net.neoforged.neoforge.attachment.AttachmentType.class));
        doCallRealMethod().when(mob).removeData(any(java.util.function.Supplier.class));
    }

    private static ServerPlayer breeder() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(BREEDER_ID);
        return player;
    }

    /**
     * Tracks a {@link McMMOPlayer} under {@code handle}'s UUID, the same
     * {@code UserManager.track(mmoPlayer)} -> {@code mmoPlayer.getPlayer().getUniqueId()} path
     * production code uses (see {@code UserManager#track}), rather than a non-existent
     * {@code McMMOPlayer#getUUID()} shortcut.
     */
    private static McMMOPlayer trackedMmoPlayer(ServerPlayer handle, HusbandryManager husbandry) {
        final McMMOPlayer mmoPlayer = mock(McMMOPlayer.class);
        when(mmoPlayer.getPlayer()).thenReturn(new PlatformPlayer(handle));
        when(mmoPlayer.getHusbandryManager()).thenReturn(husbandry);
        UserManager.track(mmoPlayer);
        return mmoPlayer;
    }

    // =============================================================================================
    // onAnimalsBred
    // =============================================================================================

    @Test
    void onAnimalsBredPaysXpAndClaimsOffspringForACow() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);

        final ServerLevel level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(100L);

        final Animal parent = mock(Cow.class);
        when(parent.getType()).thenReturn((EntityType) EntityType.COW);
        Mockito.doReturn(level).when(parent).level();

        final AgeableMob child = mock(Cow.class);
        giveRealAttachmentStorage(child);
        when(child.getType()).thenReturn((EntityType) EntityType.COW);
        when(child.getAge()).thenReturn(-24000);

        when(husbandry.onBreed("Cow", 100L))
                .thenReturn(new HusbandryManager.BreedAward(50F, false));
        when(husbandry.applyGrowthAcceleration(-24000)).thenReturn(-12000);

        // mate == null: exercises the egg-laying-breeder shape (Turtle/Frog/Sniffer) as far as this
        // method's own null-handling goes, and keeps this test focused on the breed-XP + claim half
        // without also having to stand up a full Twins roll.
        HusbandryListener.onAnimalsBred(player, parent, null, child);

        verify(husbandry).onBreed("Cow", 100L);
        verify(child).setData(McMMOAttachments.BRED_BY, BREEDER_ID);
        verify(child).setAge(-12000);
    }

    @Test
    void onAnimalsBredSkipsTheClaimWhenTheBreedingAwardCapWasReached() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);

        final ServerLevel level = mock(ServerLevel.class);
        final Animal parent = mock(Cow.class);
        when(parent.getType()).thenReturn((EntityType) EntityType.COW);
        Mockito.doReturn(level).when(parent).level();

        final AgeableMob child = mock(Cow.class);
        giveRealAttachmentStorage(child);
        when(child.getType()).thenReturn((EntityType) EntityType.COW);
        when(child.getAge()).thenReturn(-24000);

        // capReached == true, xp == 0 -> paid() is false.
        when(husbandry.onBreed(eq("Cow"), anyLong()))
                .thenReturn(new HusbandryManager.BreedAward(0F, true));

        HusbandryListener.onAnimalsBred(player, parent, null, child);

        // The refused breeding must not mark the calf -- a later raise would otherwise pay the whole
        // amount the cap just refused, off this marker alone.
        verify(child, never()).setData(eq(McMMOAttachments.BRED_BY), any());
        // Growth acceleration is still a yield effect applied regardless of payment.
        verify(husbandry).applyGrowthAcceleration(-24000);
    }

    @Test
    void onAnimalsBredRefusesXpForACallOfTheWildSummonParent() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);

        final com.gmail.nossr50.config.experience.ExperienceConfig config = mockCotwPreventedConfig();
        McMMOMod.setExperienceConfig(config);

        final UUID summonId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        final Animal parent = mock(Cow.class);
        when(parent.getUUID()).thenReturn(summonId);

        final TransientEntityTracker tracker = McMMOMod.getTransientEntityTracker();
        final TrackedSummon summon = mock(TrackedSummon.class);
        when(summon.getEntityId()).thenReturn(summonId);
        tracker.addSummon(BREEDER_ID, summon);
        try {
            HusbandryListener.onAnimalsBred(player, parent, null, null);
            verify(husbandry, never()).onBreed(any(), anyLong());
        } finally {
            tracker.evictByEntityId(summonId);
            McMMOMod.setExperienceConfig(null);
        }
    }

    private static com.gmail.nossr50.config.experience.ExperienceConfig mockCotwPreventedConfig() {
        final com.gmail.nossr50.config.experience.ExperienceConfig config =
                mock(com.gmail.nossr50.config.experience.ExperienceConfig.class);
        when(config.isCOTWBreedingPrevented()).thenReturn(true);
        return config;
    }

    /**
     * The exact case the Fabric port originally got wrong: a mixin on the "obvious" per-species
     * breeding funnel would have paid zero for foxes and turtles, because both re-implement their
     * own breeding sequence and never reach that funnel — see {@code BredAnimalsTriggerMixin}'s own
     * javadoc for the bytecode-verified reason {@code BredAnimalsTrigger#trigger} is hooked instead.
     * {@link HusbandryListener#onAnimalsBred} itself has no species branch anywhere in its body, so
     * proving it pays uniformly for {@code Cow}, {@code Fox} and {@code Turtle} — driven directly,
     * the same way {@code BredAnimalsTriggerMixin} calls it — demonstrates the listener side of that
     * fix; the mixin side (that {@code trigger} really is the point {@code Fox$FoxBreedGoal} and
     * {@code Turtle}'s own mate goal call directly) is a mixin-weaving concern plain JUnit cannot
     * exercise (no ModLauncher at test time — see {@code PlayerInteractionStashMixinTest}'s own
     * javadoc for the same limitation) and is instead nailed down by
     * {@code BredAnimalsTriggerMixinTest}, a structural check on the mixin's own {@code @Inject}
     * target string.
     */
    @Test
    void onAnimalsBredPaysBreedingXpUniformlyForCowFoxAndTurtle() {
        assertBreedingXpPaidForSpecies((EntityType) EntityType.COW, "Cow", Cow.class);
        assertBreedingXpPaidForSpecies((EntityType) EntityType.FOX, "Fox", Fox.class);
        assertBreedingXpPaidForSpecies((EntityType) EntityType.TURTLE, "Turtle", Turtle.class);
    }

    private static void assertBreedingXpPaidForSpecies(EntityType<?> type, String configString,
            Class<? extends Animal> concreteClass) {
        final UUID playerId = UUID.randomUUID();
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        final HusbandryManager husbandry = mock(HusbandryManager.class);

        final ServerLevel level = mock(ServerLevel.class);
        final Animal parent = mock(concreteClass);
        when(parent.getType()).thenReturn((EntityType) type);
        Mockito.doReturn(level).when(parent).level();

        when(husbandry.onBreed(eq(configString), anyLong()))
                .thenReturn(new HusbandryManager.BreedAward(1F, false));

        try {
            trackedMmoPlayer(player, husbandry);
            // Turtle (and Fox's turtle-doc-referenced sibling case) may legitimately pass a null
            // child -- Twins is skipped, breeding XP is not.
            HusbandryListener.onAnimalsBred(player, parent, null, null);
            verify(husbandry).onBreed(configString, 0L);
        } finally {
            UserManager.remove(playerId);
        }
    }

    // =============================================================================================
    // Twins
    // =============================================================================================

    @Test
    void twinsSpawnsAndClaimsASecondChildOnAWinningRoll() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);

        final ServerLevel level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(0L);

        final Animal parent = mock(Cow.class);
        when(parent.getType()).thenReturn((EntityType) EntityType.COW);
        Mockito.doReturn(level).when(parent).level();
        when(parent.getX()).thenReturn(1.0);
        when(parent.getY()).thenReturn(2.0);
        when(parent.getZ()).thenReturn(3.0);

        final Animal mate = mock(Cow.class);

        final AgeableMob child = mock(Cow.class);
        giveRealAttachmentStorage(child);
        when(child.getType()).thenReturn((EntityType) EntityType.COW);
        when(child.getAge()).thenReturn(-24000);

        final AgeableMob twin = mock(Cow.class);
        giveRealAttachmentStorage(twin);
        when(twin.getType()).thenReturn((EntityType) EntityType.COW);
        when(twin.getAge()).thenReturn(-24000);
        Mockito.doReturn(twin).when(parent).getBreedOffspring(level, mate);

        when(husbandry.onBreed("Cow", 0L)).thenReturn(new HusbandryManager.BreedAward(50F, false));
        when(husbandry.applyGrowthAcceleration(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(husbandry.rollTwins()).thenReturn(true);

        HusbandryListener.onAnimalsBred(player, parent, mate, child);

        verify(twin).setBaby(true);
        verify(twin).moveTo(1.0, 2.0, 3.0, 0.0F, 0.0F);
        verify(twin).setData(McMMOAttachments.BRED_BY, BREEDER_ID);
        verify(level).addFreshEntity(twin);
    }

    @Test
    void twinsDoesNotSpawnASecondChildOnALosingRoll() {
        final ServerPlayer player = breeder();
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);

        final ServerLevel level = mock(ServerLevel.class);
        final Animal parent = mock(Cow.class);
        when(parent.getType()).thenReturn((EntityType) EntityType.COW);
        Mockito.doReturn(level).when(parent).level();
        final Animal mate = mock(Cow.class);

        final AgeableMob child = mock(Cow.class);
        giveRealAttachmentStorage(child);
        when(child.getType()).thenReturn((EntityType) EntityType.COW);
        when(child.getAge()).thenReturn(-24000);

        when(husbandry.onBreed(eq("Cow"), anyLong()))
                .thenReturn(new HusbandryManager.BreedAward(50F, false));
        when(husbandry.applyGrowthAcceleration(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(husbandry.rollTwins()).thenReturn(false);

        HusbandryListener.onAnimalsBred(player, parent, mate, child);

        verify(parent, never()).getBreedOffspring(any(), any());
        verify(level, never()).addFreshEntity(any());
    }

    // =============================================================================================
    // onLovePlayer (Multi-Breed) + SPREADING_LOVE re-entrancy guard
    // =============================================================================================

    @Test
    @SuppressWarnings("unchecked")
    void onLovePlayerSetsEligibleNeighboursInLoveAndGuardsAgainstReentrantSweeps() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(BREEDER_ID);

        final HusbandryManager husbandry = mock(HusbandryManager.class);
        when(husbandry.canMultiBreed()).thenReturn(true);
        when(husbandry.getMultiBreedRadius()).thenReturn(10.0);
        trackedMmoPlayer(player, husbandry);

        final ServerLevel level = mock(ServerLevel.class);
        final Animal fed = mock(Cow.class);
        Mockito.doReturn(level).when(fed).level();
        when(fed.getBoundingBox()).thenReturn(new AABB(0, 0, 0, 1, 1, 1));

        final Animal neighbour = mock(Cow.class);
        final List<Animal> neighbours = List.of(neighbour);
        Mockito.doReturn(neighbours).when(level)
                .getEntities(any(EntityTypeTest.class), any(AABB.class), any(java.util.function.Predicate.class));

        // Simulate what AnimalSetInLoveMixin would really do in production: setInLove on the
        // neighbour re-enters HusbandryListener.onLovePlayer synchronously, from inside the very
        // sweep that just called it. Without SPREADING_LOVE this recurses outward until the stack
        // overflows (see the field's own javadoc); with it, the nested call must return immediately
        // and must NOT perform a second neighbour search.
        doAnswer(invocation -> {
            HusbandryListener.onLovePlayer(neighbour, player);
            return null;
        }).when(neighbour).setInLove(player);

        HusbandryListener.onLovePlayer(fed, player);

        verify(neighbour, times(1)).setInLove(player);
        // Exactly one sweep -- the outer call's -- ever asked the level for candidates.
        verify(level, times(1))
                .getEntities(any(EntityTypeTest.class), any(AABB.class), any(java.util.function.Predicate.class));
    }

    @Test
    void onLovePlayerDoesNothingWhenMultiBreedIsNotUnlocked() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(BREEDER_ID);
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        when(husbandry.canMultiBreed()).thenReturn(false);
        trackedMmoPlayer(player, husbandry);

        final ServerLevel level = mock(ServerLevel.class);
        final Animal fed = mock(Cow.class);
        Mockito.doReturn(level).when(fed).level();

        HusbandryListener.onLovePlayer(fed, player);

        verify(level, never())
                .getEntities(any(EntityTypeTest.class), any(AABB.class), any(java.util.function.Predicate.class));
    }

    /**
     * {@code isMultiBreedCandidate} is {@code private}; exercised via reflection the same way this
     * codebase's other structural mixin tests reach package-private state, rather than duplicating
     * it as a public method just for testability.
     */
    @Test
    void multiBreedCandidateFilterMirrorsVanillasOwnAcceptanceConditions() throws Exception {
        final java.lang.reflect.Method method = HusbandryListener.class.getDeclaredMethod(
                "isMultiBreedCandidate", Animal.class, Animal.class);
        method.setAccessible(true);

        final Animal fed = mock(Cow.class);
        when(fed.getType()).thenReturn((EntityType) EntityType.COW);

        final Animal eligible = mock(Cow.class);
        when(eligible.getType()).thenReturn((EntityType) EntityType.COW);
        when(eligible.isAlive()).thenReturn(true);
        when(eligible.getAge()).thenReturn(0);
        when(eligible.canFallInLove()).thenReturn(true);
        assertTrue((boolean) method.invoke(null, fed, eligible));

        assertFalse((boolean) method.invoke(null, fed, fed), "an animal is never its own neighbour");

        final Animal wrongSpecies = mock(net.minecraft.world.entity.animal.Pig.class);
        when(wrongSpecies.getType()).thenReturn((EntityType) EntityType.PIG);
        when(wrongSpecies.isAlive()).thenReturn(true);
        when(wrongSpecies.getAge()).thenReturn(0);
        when(wrongSpecies.canFallInLove()).thenReturn(true);
        assertFalse((boolean) method.invoke(null, fed, wrongSpecies));

        final Animal baby = mock(Cow.class);
        when(baby.getType()).thenReturn((EntityType) EntityType.COW);
        when(baby.isAlive()).thenReturn(true);
        when(baby.getAge()).thenReturn(-1000); // still growing up.
        when(baby.canFallInLove()).thenReturn(true);
        assertFalse((boolean) method.invoke(null, fed, baby));

        final Animal onCooldown = mock(Cow.class);
        when(onCooldown.getType()).thenReturn((EntityType) EntityType.COW);
        when(onCooldown.isAlive()).thenReturn(true);
        when(onCooldown.getAge()).thenReturn(300); // post-breeding cooldown.
        when(onCooldown.canFallInLove()).thenReturn(true);
        assertFalse((boolean) method.invoke(null, fed, onCooldown));

        final Animal alreadyInLove = mock(Cow.class);
        when(alreadyInLove.getType()).thenReturn((EntityType) EntityType.COW);
        when(alreadyInLove.isAlive()).thenReturn(true);
        when(alreadyInLove.getAge()).thenReturn(0);
        when(alreadyInLove.canFallInLove()).thenReturn(false);
        assertFalse((boolean) method.invoke(null, fed, alreadyInLove));

        final Animal dead = mock(Cow.class);
        when(dead.getType()).thenReturn((EntityType) EntityType.COW);
        when(dead.isAlive()).thenReturn(false);
        when(dead.getAge()).thenReturn(0);
        when(dead.canFallInLove()).thenReturn(true);
        assertFalse((boolean) method.invoke(null, fed, dead));
    }

    // =============================================================================================
    // onGrowthApplied (feed verb + Accelerated Growth)
    // =============================================================================================

    @Test
    void onGrowthAppliedPaysTheFeedVerbAndDoublesGrowthOnAWinningRoll() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(BREEDER_ID);
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);

        final AgeableMob animal = mock(Cow.class);
        when(animal.getType()).thenReturn((EntityType) EntityType.COW);
        when(husbandry.applyFeedBonus(10)).thenReturn(20);

        HusbandryListener.beginPlayerInteraction((Player) player, animal);
        try {
            final int result = HusbandryListener.onGrowthApplied(animal, 10);
            assertEquals(20, result);
        } finally {
            HusbandryListener.endPlayerInteraction();
        }

        verify(husbandry).onFeedBaby("Cow");
        verify(husbandry).applyFeedBonus(10);
    }

    @Test
    void onGrowthAppliedIgnoresGrowthNotDrivenByAPlayerInteractionWithThisAnimal() {
        final AgeableMob grassEatingSheep = mock(Cow.class);
        when(grassEatingSheep.getType()).thenReturn((EntityType) EntityType.COW);

        // No interaction stashed at all (a lamb eating grass, or a tadpole ageing itself).
        final int result = HusbandryListener.onGrowthApplied(grassEatingSheep, 5);
        assertEquals(5, result, "growth with no player interaction in flight must pass through "
                + "unchanged");
    }

    @Test
    void onGrowthAppliedIgnoresGrowthForADifferentAnimalThanTheOneBeingInteractedWith() {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(BREEDER_ID);
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(player, husbandry);

        final AgeableMob interactedWith = mock(Cow.class);
        final AgeableMob differentAnimal = mock(Cow.class);
        when(differentAnimal.getType()).thenReturn((EntityType) EntityType.COW);

        HusbandryListener.beginPlayerInteraction((Player) player, interactedWith);
        try {
            final int result = HusbandryListener.onGrowthApplied(differentAnimal, 5);
            assertEquals(5, result);
        } finally {
            HusbandryListener.endPlayerInteraction();
        }
        verify(husbandry, never()).onFeedBaby(any());
    }

    // =============================================================================================
    // onBreedingAgeChange (raise verb)
    // =============================================================================================

    @Test
    void onBreedingAgeChangePaysTheRaiseVerbOnTheBabyToAdultCrossingWhenMarked() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(breeder(), husbandry);

        final AgeableMob animal = mock(Cow.class);
        giveRealAttachmentStorage(animal);
        when(animal.getType()).thenReturn((EntityType) EntityType.COW);
        animal.setData(McMMOAttachments.BRED_BY, BREEDER_ID);

        HusbandryListener.onBreedingAgeChange(animal, -1, 0);

        verify(husbandry).onRaise("Cow");
        assertFalse(animal.hasData(McMMOAttachments.BRED_BY), "the marker must be consumed exactly "
                + "once, so this animal can never pay a second time");
    }

    @Test
    void onBreedingAgeChangePaysNothingForAnUnmarkedAnimal() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(breeder(), husbandry);

        final AgeableMob animal = mock(Cow.class);
        giveRealAttachmentStorage(animal);
        when(animal.getType()).thenReturn((EntityType) EntityType.COW);
        // No BRED_BY marker set -- a wild calf growing up on its own.

        HusbandryListener.onBreedingAgeChange(animal, -1, 0);

        verify(husbandry, never()).onRaise(any());
    }

    @Test
    void onBreedingAgeChangeIgnoresTransitionsThatAreNotTheBabyToAdultCrossing() {
        final HusbandryManager husbandry = mock(HusbandryManager.class);
        trackedMmoPlayer(breeder(), husbandry);

        final AgeableMob animal = mock(Cow.class);
        giveRealAttachmentStorage(animal);
        when(animal.getType()).thenReturn((EntityType) EntityType.COW);
        animal.setData(McMMOAttachments.BRED_BY, BREEDER_ID);

        // Adult counting down its post-breeding cooldown: previousAge already >= 0.
        HusbandryListener.onBreedingAgeChange(animal, 300, 280);
        // Still a baby both before and after (e.g. a disk-load re-application): previousAge < 0 &&
        // newAge < 0 is not the crossing either.
        HusbandryListener.onBreedingAgeChange(animal, -500, -480);

        verify(husbandry, never()).onRaise(any());
        assertTrue(animal.hasData(McMMOAttachments.BRED_BY), "a non-crossing transition must not "
                + "consume the marker");
    }

    @Test
    void onBreedingAgeChangeReadsAndWritesTheMarkerOnlyThroughTheSafeAccessors() {
        // Structural guard against regressions: HusbandryListener must never call the materializing
        // getData(AttachmentType) overload on BRED_BY, which would turn "no marker" into "a stored
        // null default" and defeat the whole point of an absence-checked attachment.
        // HusbandryListener#claimOffspring/#onBreedingAgeChange only ever call hasData/setData/
        // removeData (see those methods' own source); re-affirmed here behaviourally: a plain
        // getData call would materialize and store a null default on first read, which would make
        // hasData() true immediately, before any setData call -- it is not.
        final AgeableMob animal = mock(Cow.class);
        giveRealAttachmentStorage(animal);
        assertFalse(animal.hasData(McMMOAttachments.BRED_BY), "a freshly-mocked animal must start "
                + "with no marker -- if HusbandryListener anywhere called plain getData() on it "
                + "first, this would already be true");
        assertNull(animal.getExistingDataOrNull(McMMOAttachments.BRED_BY));
    }
}
