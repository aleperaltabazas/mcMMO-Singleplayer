package com.gmail.nossr50.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmail.nossr50.util.McTestRegistries;
import java.lang.reflect.Constructor;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Confirms {@link McMMOAttachments#BRED_BY} is a real, registered
 * {@code AttachmentType<UUID>} — not a stand-in like {@link McMMOAttachments} still keeps for
 * {@code MOB_ORIGIN} — and round-trips through the real NeoForge attachment API's
 * {@code hasData}/{@code getExistingDataOrNull}/{@code setData}/{@code removeData} accessors.
 *
 * <p><b>This runs under plain JUnit, not a reflection-only structural check, because it turned out
 * to be possible without a live ModLauncher game context</b> — two real NeoForge implementation
 * details make it so, both verified against the merged/patched jar before relying on them:
 * <ul>
 *   <li>{@code NeoForgeRegistries.ATTACHMENT_TYPES} is a plain {@code static final} field built at
 *       class-load time via {@code new RegistryBuilder<>(Keys.ATTACHMENT_TYPES).create()}
 *       ({@code net/neoforged/neoforge/registries/NeoForgeRegistries.java:43}) — unlike vanilla's
 *       {@code BuiltInRegistries}, it needs no {@code Bootstrap.bootStrap()} call and is never
 *       frozen, so a plain reference to the class is enough to have a live, mutable registry to
 *       register into.</li>
 *   <li>{@code net.neoforged.neoforge.attachment.AttachmentHolder} (the abstract implementation
 *       backing every real {@code IAttachmentHolder}, including {@code Entity}) has a public
 *       no-arg constructor and needs no {@code Entity}/{@code Level} at all to exercise the
 *       accessor contract — an anonymous subclass here stands in for "a constructed entity" per
 *       the task brief's acceptance criterion, exercising the exact same
 *       {@code validateAttachmentType}/{@code hasData}/{@code getData}/{@code setData}/
 *       {@code removeData} code real entities run through ({@code Entity} itself delegates to
 *       this same base class).</li>
 * </ul>
 *
 * <p>What plain JUnit still cannot do is fire {@code McMMOMod}'s constructor and let
 * {@code RegisterEvent} arrive through the real mod-loading lifecycle — so this test constructs
 * one directly. {@link RegisterEvent}'s constructor is package-private
 * ({@code net.neoforged.neoforge.registries}), reached here via reflection with
 * {@code setAccessible(true)}; both jars are plain classpath jars (no Java Platform Module System
 * {@code opens} restriction applies), so this does not require any special test-runner wiring.
 * This exercises {@link McMMOAttachments#ATTACHMENT_TYPES}'s real
 * {@code DeferredRegister#register(IEventBus)} listener wiring end to end, the same path
 * {@code McMMOMod}'s constructor drives in a real game session.
 */
class McMMOAttachmentsBredByTest {

    @BeforeAll
    static void bootstrapRegistries() {
        McTestRegistries.bootstrap();
    }

    @Test
    void bredByRegistersAndRoundTripsThroughTheRealAttachmentApi() throws Exception {
        // Drive McMMOAttachments.ATTACHMENT_TYPES.register(...) through a real RegisterEvent, the
        // same path McMMOMod's constructor takes via McMMOAttachments.register(modEventBus) --
        // proving BRED_BY actually binds through NeoForgeRegistries.Keys.ATTACHMENT_TYPES rather
        // than merely compiling.
        final IEventBus bus = BusBuilder.builder().build();
        McMMOAttachments.ATTACHMENT_TYPES.register(bus);

        final Constructor<RegisterEvent> registerEventCtor = RegisterEvent.class
                .getDeclaredConstructor(ResourceKey.class, Registry.class);
        registerEventCtor.setAccessible(true);
        final RegisterEvent event = registerEventCtor.newInstance(
                NeoForgeRegistries.Keys.ATTACHMENT_TYPES, NeoForgeRegistries.ATTACHMENT_TYPES);
        bus.post(event);

        final AttachmentType<UUID> bredBy = McMMOAttachments.BRED_BY.get();
        assertNotNull(bredBy, "BRED_BY must bind once RegisterEvent has fired for "
                + "NeoForgeRegistries.Keys.ATTACHMENT_TYPES");

        // A constructed IAttachmentHolder -- see this class's own javadoc for why an
        // AttachmentHolder subclass stands in for "a constructed entity" here.
        final AttachmentHolder holder = new AttachmentHolder() {
        };

        // Absent by default: never materialized through the default-value-supplying getData.
        assertFalse(holder.hasData(McMMOAttachments.BRED_BY));
        assertNull(holder.getExistingDataOrNull(McMMOAttachments.BRED_BY));

        final UUID breeder = UUID.randomUUID();
        holder.setData(McMMOAttachments.BRED_BY, breeder);
        assertTrue(holder.hasData(McMMOAttachments.BRED_BY));
        assertEquals(breeder, holder.getExistingDataOrNull(McMMOAttachments.BRED_BY));

        holder.removeData(McMMOAttachments.BRED_BY);
        assertFalse(holder.hasData(McMMOAttachments.BRED_BY), "removeData must drop the marker "
                + "back to absent");
        assertNull(holder.getExistingDataOrNull(McMMOAttachments.BRED_BY));
    }

    /**
     * {@code MOB_ORIGIN}'s own UUID-keyed accessors ({@link McMMOAttachments#clear(UUID)},
     * {@link McMMOAttachments#clearAll()}) are exercised here only to the extent they do not
     * require a live {@code Entity}/{@code Level} -- confirming this task left that side of the
     * class's API surface compiling and callable is enough; {@code MOB_ORIGIN}'s own behavioural
     * coverage is out of scope for this task (see {@link McMMOAttachments}'s class javadoc: this
     * task adds {@code BRED_BY} alongside it and does not touch or "upgrade" that stand-in).
     */
    @Test
    void mobOriginClearApiStillCompilesAndRunsUntouched() {
        final UUID id = UUID.randomUUID();
        McMMOAttachments.clear(id); // no-op on an untracked id; must not throw.
        McMMOAttachments.clearAll(); // must not throw.
    }
}
