package com.gmail.nossr50.neoforge.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.platform.PlatformPlayer;
import com.gmail.nossr50.skills.cooking.CookingManager;
import com.gmail.nossr50.skills.fishing.FishingManager;
import com.gmail.nossr50.skills.herbalism.HerbalismManager;
import com.gmail.nossr50.util.McTestRegistries;
import com.gmail.nossr50.util.player.UserManager;
import com.gmail.nossr50.util.text.ConfigStringUtils;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The food-consumption seam — the one every skill that reacts to eating has to share.
 *
 * <p>What this class exists to stop is the <b>ordering trap</b>: {@code onFoodConsumed} must never
 * become a bare {@code if / else if / else} in which every arm returns, because a skill appended as
 * one more {@code else if} would silently never fire for the 17 foods the two diets already claim
 * — exactly the cooked and crafted foods. The tests here assert the diets still work <em>and</em>
 * that a food one of them claims still reaches Power Cook.
 */
class FoodListenerTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    /** Rank-driven diet bonus used throughout: +2 hunger points on top of the food's own. */
    private static final int DIET_BONUS = 2;

    /** The hunger bar starts here, well below full, so a bonus has room to land and be seen. */
    private static final int START_FOOD_LEVEL = 4;

    /**
     * A Power Cook duration in ticks. Deliberately not one of the shipped ladder's values, so a
     * wiring bug that reads the wrong field cannot coincidentally still look right.
     */
    private static final int POWER_COOK_TICKS = 137;

    private ServerLevel level;
    private ServerPlayer player;
    private FoodData hunger;
    private McMMOPlayer mmoPlayer;
    private HerbalismManager herbalism;
    private FishingManager fishing;
    private CookingManager cooking;

    @BeforeEach
    void setUp() {
        level = mock(ServerLevel.class);
        lenient().when(level.isClientSide()).thenReturn(false);

        // A real FoodData, not a mock: the bonus is applied through vanilla's own clamping setters,
        // and a mock would happily record a food level of 40 on a 20-point bar.
        hunger = new FoodData();
        hunger.setFoodLevel(START_FOOD_LEVEL);
        hunger.setSaturation(0.0f);

        final UUID uuid = UUID.randomUUID();
        player = mock(ServerPlayer.class);
        lenient().when(player.getUUID()).thenReturn(uuid);
        lenient().when(player.getFoodData()).thenReturn(hunger);

        final PlatformPlayer platformPlayer = mock(PlatformPlayer.class);
        lenient().when(platformPlayer.getUniqueId()).thenReturn(uuid);

        herbalism = mock(HerbalismManager.class);
        fishing = mock(FishingManager.class);
        cooking = mock(CookingManager.class);
        mmoPlayer = mock(McMMOPlayer.class);
        lenient().when(mmoPlayer.getPlayer()).thenReturn(platformPlayer);
        lenient().when(mmoPlayer.getHerbalismManager()).thenReturn(herbalism);
        lenient().when(mmoPlayer.getFishingManager()).thenReturn(fishing);
        lenient().when(mmoPlayer.getCookingManager()).thenReturn(cooking);
        UserManager.track(mmoPlayer);
    }

    @AfterEach
    void tearDown() {
        UserManager.cleanupPlayer(mmoPlayer);
    }

    // --- The diet half ----------------------------------------------------------------------------

    @Test
    void eatingAFarmersDietFoodTopsUpTheHungerBar() {
        rankedFarmer();

        eat(Items.BREAD);

        assertEquals(START_FOOD_LEVEL + nutritionOf(Items.BREAD) + DIET_BONUS, hunger.getFoodLevel(),
                "Farmer's Diet must add its bonus on top of the food vanilla already applied");
    }

    @Test
    void eatingAFishermansDietFoodTopsUpTheHungerBar() {
        rankedFisherman();

        eat(Items.COOKED_COD);

        assertEquals(START_FOOD_LEVEL + nutritionOf(Items.COOKED_COD) + DIET_BONUS,
                hunger.getFoodLevel());
    }

    @Test
    void theTwoDietsStayMutuallyExclusive() {
        // Both ranked, one food. Bread is Farmer's; Fisherman's must not also pay out on it, or the
        // seam has turned an exclusive chain into two additive bonuses on one bite.
        rankedFarmer();
        rankedFisherman();

        eat(Items.BREAD);

        assertEquals(START_FOOD_LEVEL + nutritionOf(Items.BREAD) + DIET_BONUS, hunger.getFoodLevel(),
                "a food claimed by one diet must be paid by exactly one diet");
    }

    @Test
    void aFoodNeitherDietClaimsGetsNoHungerBonus() {
        rankedFarmer();
        rankedFisherman();

        eat(Items.COOKED_BEEF);

        assertEquals(START_FOOD_LEVEL + nutritionOf(Items.COOKED_BEEF), hunger.getFoodLevel(),
                "cooked beef belongs to neither diet");
    }

    @Test
    void anUnrankedDietGrantsNothing() {
        when(herbalism.canUseFarmersDiet()).thenReturn(false);

        eat(Items.BREAD);

        assertEquals(START_FOOD_LEVEL + nutritionOf(Items.BREAD), hunger.getFoodLevel());
    }

    @Test
    void theClientHalfOfASingleplayerSessionIsIgnored() {
        // Singleplayer runs both logical sides in one process; the client's copy of the consumption
        // would double every diet bonus.
        rankedFarmer();
        when(level.isClientSide()).thenReturn(true);

        eat(Items.BREAD);

        assertEquals(START_FOOD_LEVEL, hunger.getFoodLevel(),
                "the client side must apply nothing at all -- vanilla has not eaten yet either");
    }

    @Test
    void aFoodWithNoNutritionIsUnreachableForEveryTenant() {
        // No shipped vanilla food has 0 nutrition, so the guard is exercised with a synthetic
        // FoodProperties rather than a real item -- it must still short-circuit both tenants.
        rankedFarmer();
        powerCooked(Items.BREAD, "STRENGTH", POWER_COOK_TICKS);
        final FoodProperties zeroNutrition = new FoodProperties.Builder().nutrition(0).build();
        final ItemStack stack = new ItemStack(Items.BREAD);

        FoodListener.onFoodConsumed(level, player, stack, zeroNutrition);

        assertEquals(START_FOOD_LEVEL, hunger.getFoodLevel(),
                "a 0-nutrition food must not receive a diet bonus");
        verify(player, never()).addEffect(any());
    }

    // --- Power Cook, and the ordering trap --------------------------------------------------------

    @Test
    void eatingBreadFiresBothTheDietBonusAndPowerCook() {
        // ⚠️⚠️ THE TEST THIS WHOLE SEAM EXISTS FOR. Bread is a Farmer's Diet food; if this were a
        // bare if/else-if where every arm returns, a skill appended as one more `else if` would never
        // fire for bread, cookie, pumpkin_pie, mushroom_stew, baked_potato, cooked_cod or
        // cooked_salmon -- 17 of the game's 40 edible items, precisely the set a cook cooks.
        //
        // Asserted OFF THE REFERENCE POINT: a food ONE of the diets already claims, not a food only
        // Cooking claims. A test on cooked beef would pass against a broken chain.
        rankedFarmer();
        powerCooked(Items.BREAD, "SPEED", POWER_COOK_TICKS);

        eat(Items.BREAD);

        assertEquals(START_FOOD_LEVEL + nutritionOf(Items.BREAD) + DIET_BONUS, hunger.getFoodLevel(),
                "the Farmer's Diet bonus must survive Cooking joining this seam");
        assertEffectApplied(MobEffects.MOVEMENT_SPEED, POWER_COOK_TICKS);
    }

    @Test
    void eatingACookedFoodAppliesItsMappedEffect() {
        powerCooked(Items.COOKED_BEEF, "STRENGTH", POWER_COOK_TICKS);

        eat(Items.COOKED_BEEF);

        assertEffectApplied(MobEffects.DAMAGE_BOOST, POWER_COOK_TICKS);
    }

    @Test
    void theEffectIsAlwaysAppliedAtAmplifierZero() {
        powerCooked(Items.COOKED_BEEF, "STRENGTH", POWER_COOK_TICKS);

        eat(Items.COOKED_BEEF);

        assertEquals(0, captureEffect().getAmplifier(), "no Strength II from a sandwich");
    }

    @Test
    void aFoodTheManagerDeclinesGrantsNoEffect() {
        when(cooking.powerCookEffect(anyString())).thenReturn(null);

        eat(Items.COOKED_BEEF);

        verify(player, never()).addEffect(any());
    }

    @Test
    void anUnknownEffectNameGrantsNothingAndDoesNotThrow() {
        powerCooked(Items.COOKED_BEEF, "STRENGTH_II_PLEASE", POWER_COOK_TICKS);

        eat(Items.COOKED_BEEF);

        verify(player, never()).addEffect(any());
    }

    @Test
    void theSeamIsKeyedOnTheConfigStringAndNotTheRegistryPath() {
        // Bonus_Drops.Cooking and Experience_Values.Cooking are both Config_String-keyed, so a seam
        // handing over "cooked_beef" would find no row and grant nothing -- silently, forever.
        powerCooked(Items.COOKED_BEEF, "STRENGTH", POWER_COOK_TICKS);

        eat(Items.COOKED_BEEF);

        verify(cooking).powerCookEffect("Cooked_Beef");
    }

    @Test
    void powerCookNeverDowngradesAStrongerEffectThePlayerAlreadyHas() {
        // The clause is delegated to vanilla rather than reimplemented -- pinned here because it is
        // an assumption about somebody else's code.
        final MobEffectInstance brewedPotion = new MobEffectInstance(MobEffects.DAMAGE_BOOST, 3600, 1);

        final boolean changed =
                brewedPotion.update(new MobEffectInstance(MobEffects.DAMAGE_BOOST, POWER_COOK_TICKS, 0));

        assertFalse(changed, "a weaker, shorter effect must not replace a brewed potion");
        assertEquals(3600, brewedPotion.getDuration(), "the potion's duration must be untouched");
        assertEquals(1, brewedPotion.getAmplifier(), "the potion's amplifier must be untouched");
    }

    @Test
    void powerCookStillExtendsAnEffectOfItsOwnStrength() {
        final MobEffectInstance running = new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20, 0);

        final boolean changed =
                running.update(new MobEffectInstance(MobEffects.DAMAGE_BOOST, POWER_COOK_TICKS, 0));

        assertTrue(changed, "a longer effect at the same strength must extend the running one");
        assertEquals(POWER_COOK_TICKS, running.getDuration());
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private void powerCooked(Item item, String effectName, int ticks) {
        final String key = ConfigStringUtils.getMaterialConfigString(
                BuiltInRegistries.ITEM.getKey(item).getPath());
        when(cooking.powerCookEffect(key))
                .thenReturn(new CookingManager.PowerCookEffect(effectName, ticks));
    }

    private MobEffectInstance captureEffect() {
        final ArgumentCaptor<MobEffectInstance> captor = ArgumentCaptor.forClass(MobEffectInstance.class);
        verify(player).addEffect(captor.capture());
        return captor.getValue();
    }

    private void assertEffectApplied(Holder<MobEffect> expected, int expectedTicks) {
        final MobEffectInstance applied = captureEffect();
        assertTrue(applied.getEffect().equals(expected),
                "expected " + expected + " but got " + applied.getEffect());
        assertEquals(expectedTicks, applied.getDuration());
    }

    private void rankedFarmer() {
        when(herbalism.canUseFarmersDiet()).thenReturn(true);
        when(herbalism.farmersDiet(anyInt())).thenAnswer(call -> (int) call.getArgument(0) + DIET_BONUS);
    }

    private void rankedFisherman() {
        when(fishing.canUseFishermansDiet()).thenReturn(true);
        when(fishing.handleFishermanDiet(anyInt()))
                .thenAnswer(call -> (int) call.getArgument(0) + DIET_BONUS);
    }

    /**
     * Eat one {@code item}, the way vanilla does: apply the food to the hunger bar first, then fire
     * the seam. The mixin injects at {@code TAIL} -- <b>after</b> vanilla's own {@code FoodData#eat}
     * -- so a test that skips that step measures a bar the listener never actually sees.
     */
    private void eat(Item item) {
        final ItemStack stack = new ItemStack(item);
        final FoodProperties food = foodPropertiesOf(item);
        if (!level.isClientSide()) {
            hunger.eat(food);
        }
        FoodListener.onFoodConsumed(level, player, stack, food);
    }

    private static FoodProperties foodPropertiesOf(Item item) {
        final FoodProperties food = new ItemStack(item).get(DataComponents.FOOD);
        if (food == null) {
            throw new AssertionError(item + " has no food properties; the seam can never fire for it");
        }
        return food;
    }

    private static int nutritionOf(Item item) {
        return foodPropertiesOf(item).nutrition();
    }
}
