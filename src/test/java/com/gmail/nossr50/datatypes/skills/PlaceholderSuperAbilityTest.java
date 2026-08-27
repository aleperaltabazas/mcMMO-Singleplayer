package com.gmail.nossr50.datatypes.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmail.nossr50.config.GeneralConfig;
import com.gmail.nossr50.neoforge.McMMOMod;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Closes item 3.2 of the 2026-08-06 wiring audit: the {@link SuperAbilityType} constants that exist
 * as enum entries and nothing else.
 *
 * <p>Upstream declares super abilities for weapons it never implemented. The port inherits them, and
 * that is <em>fine</em> — an unimplemented mechanic with no surface is honest. What is not fine is the
 * shape this project keeps re-finding: the mechanic stays dead while a cooldown slider, a
 * {@code /mcstats} line or a rank plaque quietly advertises it. This test fixes the boundary in both
 * directions so neither half can drift.
 *
 * <p>⚠️ <b>The audit said four; there are five.</b> It listed {@code EXPLOSIVE_SHOT},
 * {@code SUPER_SHOTGUN}, {@code TRIDENTS_SUPER_ABILITY} and {@code MACES_SUPER_ABILITY} and missed
 * {@code SPEARS_SUPER_ABILITY}. Enumerating the set by hand is exactly how it was missed, so
 * {@link #thePlaceholderSetIsDerivedNotHandKept()} derives membership from the enum itself.
 */
class PlaceholderSuperAbilityTest {

    /**
     * A super ability is "placeholder" iff it has no {@link SubSkillType} definition. That mapping is
     * what {@code McMMOPlayer#rankedSubSkillsOf} and {@code RankUtils} dereference to build a rank
     * plaque and a {@code /mcstats} entry, so having none is precisely what makes an ability invisible.
     */
    private static Set<SuperAbilityType> placeholders() {
        final Set<SuperAbilityType> placeholders = EnumSet.noneOf(SuperAbilityType.class);
        for (SuperAbilityType ability : SuperAbilityType.values()) {
            if (ability.getSubSkillTypeDefinition() == null) {
                placeholders.add(ability);
            }
        }
        return placeholders;
    }

    @BeforeEach
    void bindConfig(@TempDir Path dataFolder) {
        McMMOMod.setGeneralConfig(new GeneralConfig(dataFolder));
    }

    /**
     * The five, named — so that implementing one is a deliberate edit here rather than a silent
     * change, and so a <em>sixth</em> appearing (a new weapon's placeholder) fails loudly.
     */
    @Test
    void thePlaceholderSetIsDerivedNotHandKept() {
        assertEquals(
                EnumSet.of(SuperAbilityType.EXPLOSIVE_SHOT,
                        SuperAbilityType.SUPER_SHOTGUN,
                        SuperAbilityType.TRIDENTS_SUPER_ABILITY,
                        SuperAbilityType.MACES_SUPER_ABILITY,
                        SuperAbilityType.SPEARS_SUPER_ABILITY),
                placeholders(),
                "the set of super abilities with no SubSkillType changed. Implementing one? Give it "
                        + "a SubSkillType, a plaque and a cooldown key, and drop it from this list. "
                        + "Adding a new placeholder? It must reach no surface — see the tests below.");
    }

    // ⚠️ Task 8 note: this file originally carried a Tier-1 test here, `
    // noPlaceholderIsOfferedACooldownSlider`, asserting no placeholder super ability is offered a
    // cooldown slider by walking `fabric.client.modmenu.McMMOSettings`/`ConfigSetting`. The ModMenu
    // client integration is Phase 2+ work per this port's design spec (deferred back to
    // mc/1.21.1, not carried as dead code here) and was deleted with the rest of `fabric/` in this
    // task, so that surface does not exist to police on this branch. Removed rather than
    // translated — there is no NeoForge settings-screen equivalent yet to check against, and
    // faking one would test nothing real. Re-add the equivalent check once a NeoForge config
    // screen exists and offers per-ability cooldown sliders.

    /**
     * The converse, and the direction a one-sided guard misses: the shipped {@code config.yml} must
     * not carry timing keys for them either. A key can ship without ever reaching the catalogue —
     * that is how {@code Herdsmans_Call} was missed when Husbandry landed.
     */
    @Test
    void noPlaceholderShipsATimingKey() {
        final GeneralConfig config = McMMOMod.getGeneralConfig();
        final List<String> offenders = new ArrayList<>();
        for (SuperAbilityType ability : placeholders()) {
            if (config.getMaxLength(ability.toString()) != 0) {
                offenders.add("Abilities.Max_Seconds." + ability.toString() + " = "
                        + config.getMaxLength(ability.toString()));
            }
        }
        assertTrue(offenders.isEmpty(),
                "config.yml ships a timing value for a placeholder super ability, so editing it "
                        + "does nothing forever — the Damage_Limit shape: " + offenders);
    }

    /**
     * The real abilities must keep their definitions. Without this the two tests above pass trivially
     * if {@code subSkillTypeDefinition} ever stops being assigned — every ability would look like a
     * placeholder and every "no placeholder has a surface" assertion would sweep the wrong set.
     */
    @Test
    void everyImplementedSuperAbilityStillHasItsSubSkillDefinition() {
        for (SuperAbilityType ability : SuperAbilityType.values()) {
            if (placeholders().contains(ability)) {
                continue;
            }
            assertNotNull(ability.getSubSkillTypeDefinition(),
                    () -> ability + " lost its SubSkillType definition");
        }
        assertTrue(placeholders().size() < SuperAbilityType.values().length,
                "every super ability now looks like a placeholder — subSkillTypeDefinition is no "
                        + "longer being assigned, and the guards above are sweeping the wrong set");
    }
}
