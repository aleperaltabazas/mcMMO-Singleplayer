package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.config.RankConfig;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.player.PlayerProfile;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.neoforge.McMMOMod;
import com.gmail.nossr50.platform.MetadataStore;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.husbandry.HusbandryManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Husbandry listener plan, Task E: confirms {@link HusbandryManager#isHerdsmansCallActive()}'s
 * cooldown-bypass effect on {@code HusbandryListener#harvestCooldownElapsed} (Task C) end-to-end,
 * now that Herdsman's Call itself is wired up in {@link PlayerMovementTracker} (this task).
 *
 * <h2>Why this is a separate test from {@code HusbandryListenerMilkBrushTest}</h2>
 * That class's own {@code herdsmansCallLetsAHarvestIgnoreItsCooldown} test proves the
 * {@code harvestCooldownElapsed} code path is reachable, but it does so against a <b>mocked</b>
 * {@link HusbandryManager} with {@code isHerdsmansCallActive()} stubbed directly — it never proves
 * the real manager, wired to a real {@link McMMOPlayer}'s ability-mode state (the same state
 * {@code /mcability}/the super-ability system itself flips), actually reports active. This test
 * uses a <b>real</b> {@link McMMOPlayer} and a <b>real</b> {@link HusbandryManager} (the same "real
 * config, real profile" fixture {@code HusbandryManagerTest} uses), and activates the ability the
 * only way production code can — {@link McMMOPlayer#setAbilityMode} — then confirms
 * {@link HusbandryListener#onMilked} actually pays a second time inside the real 300-second
 * cooldown window, observed the same way {@code HusbandryManagerTest} observes every other award:
 * a real rise in {@link PlayerProfile#getSkillXpLevelRaw}, not a mock verification.
 */
class HusbandryListenerHerdsmansCallIntegrationTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e5");

    private McMMOPlayer mmoPlayer;
    private PlayerProfile profile;
    private HusbandryManager husbandry;
    private ServerPlayer player;
    private ServerLevel level;

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @BeforeEach
    void setUp(@TempDir Path dataFolder) {
        McMMOMod.setExperienceConfig(new ExperienceConfig(dataFolder));
        McMMOMod.setGeneralConfig(new GeneralConfig(dataFolder));
        McMMOMod.setAdvancedConfig(new AdvancedConfig(dataFolder));
        McMMOMod.setRankConfig(new RankConfig(dataFolder));

        final PlatformPlayer platformPlayer = mock(PlatformPlayer.class);
        lenient().when(platformPlayer.getName()).thenReturn("Herdsman");
        lenient().when(platformPlayer.getUniqueId()).thenReturn(PLAYER_ID);
        lenient().when(platformPlayer.isCreative()).thenReturn(false);

        profile = new PlayerProfile("Herdsman", PLAYER_ID, 0);
        // Clear of the low-level XP cap so every award actually banks -- same reasoning as
        // HusbandryManagerTest's own setHusbandryLevel(500) fixture.
        profile.modifySkill(PrimarySkillType.HUSBANDRY, 500);

        mmoPlayer = new McMMOPlayer(platformPlayer, profile);
        husbandry = mmoPlayer.getHusbandryManager();
        UserManager.track(mmoPlayer);

        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(PLAYER_ID);

        level = mock(ServerLevel.class);
    }

    @AfterEach
    void tearDown() {
        UserManager.remove(PLAYER_ID);
        HusbandryListener.clear();
        MetadataStore.clearAll();
        McMMOMod.setExperienceConfig(null);
        McMMOMod.setGeneralConfig(null);
        McMMOMod.setAdvancedConfig(null);
        McMMOMod.setRankConfig(null);
    }

    private Entity cowAt(long gameTime) {
        final Entity cow = mock(Cow.class);
        lenient().when(cow.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.doReturn(level).when(cow).level();
        when(level.getGameTime()).thenReturn(gameTime);
        return cow;
    }

    @Test
    void soundingTheRealHerdsmansCallLetsMilkingBypassTheRealCooldownEndToEnd() {
        assertTrue(husbandry.getHarvestCooldownSeconds() > 0,
                "sanity: the shipped experience.yml really does configure a positive cooldown");
        assertTrue(husbandry.getHarvestCooldownSeconds() * 20L > 100L,
                "sanity: the test's own mid-window probe (tick 100) must fall inside the real "
                        + "cooldown window, or the second assertion below would pass for the wrong "
                        + "reason");

        final Entity cow = cowAt(0L);

        final float beforeAnyMilk = profile.getSkillXpLevelRaw(PrimarySkillType.HUSBANDRY);
        HusbandryListener.onMilked(cow, player);
        final float afterFirstMilk = profile.getSkillXpLevelRaw(PrimarySkillType.HUSBANDRY);
        assertTrue(afterFirstMilk > beforeAnyMilk,
                "the first milking, off cooldown, must actually pay");

        // Same cow, still well inside the real cooldown window, Herdsman's Call NOT active:
        // the real manager must refuse a second payout.
        assertTrue(!husbandry.isHerdsmansCallActive());
        when(level.getGameTime()).thenReturn(100L);
        HusbandryListener.onMilked(cow, player);
        assertEquals(afterFirstMilk, profile.getSkillXpLevelRaw(PrimarySkillType.HUSBANDRY),
                "milking the same cow inside the real cooldown, with the ability idle, must not "
                        + "pay a second time");

        // Sound the horn the ONLY way production code can -- flipping the real McMMOPlayer's own
        // ability-mode state, exactly what the super-ability activation path does -- and confirm
        // the manager built on top of it agrees before relying on it.
        mmoPlayer.setAbilityMode(SuperAbilityType.HERDSMANS_CALL, true);
        assertTrue(husbandry.isHerdsmansCallActive(),
                "sanity: the real HusbandryManager must see the ability-mode flip");

        HusbandryListener.onMilked(cow, player);
        final float afterBypass = profile.getSkillXpLevelRaw(PrimarySkillType.HUSBANDRY);
        assertTrue(afterBypass > afterFirstMilk,
                "Herdsman's Call being genuinely active -- via real ability-mode state, not a "
                        + "mocked HusbandryManager -- must let the harvest verb bypass its real "
                        + "cooldown, not merely leave the code path reachable in isolation");

        // The bypass must not have reset the animal's ordinary clock (Task C's own guarantee,
        // reconfirmed here now that the activation itself is real): end the ability and the
        // ORIGINAL window -- started at tick 0 -- must still be in force at tick 100.
        mmoPlayer.setAbilityMode(SuperAbilityType.HERDSMANS_CALL, false);
        HusbandryListener.onMilked(cow, player);
        assertEquals(afterBypass, profile.getSkillXpLevelRaw(PrimarySkillType.HUSBANDRY),
                "a bypassed harvest must not stamp the timestamp, so the ORIGINAL cooldown window "
                        + "is still in force once the ability ends");
    }
}
