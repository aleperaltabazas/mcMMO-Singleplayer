package com.gmail.nossr50.neoforge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Task 3 STAND-IN for {@code com.gmail.nossr50.fabric.McMMOAttachments}. The Fabric original
 * persisted {@code MOB_ORIGIN} (and {@code BRED_BY}, not needed by {@code platform/}) into entity
 * NBT via {@code net.fabricmc.fabric.api.attachment.v1.AttachmentType}, a Fabric-API capability
 * with no NeoForge equivalent wired yet. {@code fabric/McMMOAttachments} itself cannot compile
 * under the NeoForge toolchain (no Fabric API on the classpath), so it stays excluded from
 * {@code sourceSets} pending its own deletion task.
 *
 * <p><b>What this class is NOT:</b> a persistence mechanism. It is an in-memory, UUID-keyed side
 * table -- exactly {@link com.gmail.nossr50.platform.MetadataStore}'s shape, right down to the
 * {@link #clear} naming and the "call it when the entity is removed" contract -- that keeps
 * {@link com.gmail.nossr50.platform.MobOrigins} compiling and functionally correct
 * <em>within a single server run</em>. A mob's origin marker does NOT currently survive a
 * chunk unload/reload or a world save/restart the way the Fabric original's NBT-backed attachment
 * did. Wiring real persistence -- NeoForge's {@code IAttachmentHolder}/{@code AttachmentType}, or
 * writing into entity NBT directly -- is out of scope for Task 3 (mapping-only translation of
 * {@code platform/}) and is flagged in the Task 3 report for a later task to pick up.
 *
 * <p><b>Eviction (fixed 2026-08-26, code review):</b> {@link #clear} mirrors
 * {@link com.gmail.nossr50.platform.MetadataStore#clear}'s shape exactly, for the same reason --
 * without it, {@link #MOB_ORIGIN} grows for the process lifetime as mobs spawn and die, since
 * nothing ever removed an entry. {@code MetadataStore}'s own {@code clear} is not
 * self-triggering either; it depends on a caller invoking it on entity removal (today, only
 * {@code fabric.mixin.FireworkRocketEntityMixin} does, for its own key). No NeoForge equivalent
 * of that entity-removal hook is wired yet on this branch (mixins/listeners are a later task), so
 * {@link #clear} is likewise not yet called from anywhere -- it exists so the eviction path is
 * ready the moment that hook lands, rather than requiring a second pass through this class then.
 * Until that hook exists, this table has the same unbounded-growth-over-a-session shape
 * {@code MetadataStore} would have if nothing ever called its own {@code clear} either; tracked
 * here rather than silently left unmentioned.
 *
 * <p><b>{@code BRED_BY} (Husbandry listener plan, Task A): real, registered persistence — NOT a
 * stand-in.</b> Unlike {@link #MOB_ORIGIN} above, {@link #BRED_BY} is a genuine NeoForge
 * {@link AttachmentType}, registered through {@link NeoForgeRegistries.Keys#ATTACHMENT_TYPES} via
 * {@link #ATTACHMENT_TYPES}, a {@link DeferredRegister} wired onto the mod event bus from
 * {@code McMMOMod}'s constructor. It marks a newborn animal with the {@link UUID} of the player
 * whose breeding produced it, persisted into the entity's own NBT via
 * {@code .serialize(UUIDUtil.CODEC)}, so the marker survives a chunk unload/reload or a world
 * save/restart the way {@link #MOB_ORIGIN} currently does not (see that field's own javadoc for
 * the gap this does not fix).
 *
 * <p><b>Always read/write {@link #BRED_BY} through {@code hasData}/{@code getExistingDataOrNull}/
 * {@code setData}/{@code removeData} — never plain {@code getData(type)}.</b> {@code getData}
 * materializes and stores the type's default value (here, {@code null}) on first read and syncs
 * it to clients, silently turning "no marker" into "a stored null-ish default" and defeating the
 * point of an absence check. {@code BRED_BY} carries no {@code copyOnDeath()}: a breeding-age
 * crossing consumes and removes the marker (Task B) before an animal could plausibly die carrying
 * it in a way that matters, the same reasoning the Fabric original used implicitly by never
 * calling anything death-related for this marker.
 */
public final class McMMOAttachments {

    private static final Map<UUID, String> MOB_ORIGIN = new ConcurrentHashMap<>();

    /**
     * This codebase's first {@link DeferredRegister} of any kind. Registered onto the mod event
     * bus from {@code McMMOMod}'s constructor via {@link #register}; every {@link AttachmentType}
     * this class defines is registered through it.
     */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, McMMOMod.MOD_ID);

    /**
     * Real, registered, disk-persisted marker of who bred an animal. See this class's own javadoc
     * for why this is not built the same way {@link #MOB_ORIGIN} is.
     *
     * <p>{@code AttachmentType.builder(...)} wants a default-value {@link Supplier}/{@code
     * Function<IAttachmentHolder, T>}, not a bare nullable — {@code () -> (UUID) null} supplies
     * that shape while keeping the type genuinely absence-checked (the default is never a valid
     * marker; it exists only so the builder compiles, and is never read through {@code getData}).
     */
    public static final Supplier<AttachmentType<UUID>> BRED_BY = ATTACHMENT_TYPES.register("bred_by",
            () -> AttachmentType.builder(() -> (UUID) null).serialize(UUIDUtil.CODEC).build());

    private McMMOAttachments() {
    }

    /**
     * Register {@link #ATTACHMENT_TYPES} onto the mod event bus. Called once from
     * {@code McMMOMod}'s constructor, alongside the mod's other one-time mod-bus setup — standard
     * NeoForge {@link DeferredRegister} registration shape.
     */
    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }

    /** Stand-in for {@code entity.getAttached(McMMOAttachments.MOB_ORIGIN)}. */
    public static @Nullable String getMobOrigin(@NotNull Entity entity) {
        return MOB_ORIGIN.get(entity.getUUID());
    }

    /** Stand-in for {@code entity.setAttached(McMMOAttachments.MOB_ORIGIN, value)}. */
    public static void setMobOrigin(@NotNull Entity entity, @NotNull String value) {
        MOB_ORIGIN.put(entity.getUUID(), value);
    }

    /**
     * Drop the stored origin marker for this entity. Call when the entity is removed/dies --
     * mirrors {@link com.gmail.nossr50.platform.MetadataStore#clear(Entity)}. See the class
     * javadoc for why nothing calls this yet on this branch.
     */
    public static void clear(@NotNull Entity entity) {
        clear(entity.getUUID());
    }

    /**
     * Drop the stored origin marker for this entity id, addressed by {@link UUID}. Mirrors
     * {@link com.gmail.nossr50.platform.MetadataStore#remove(UUID, String)}'s UUID-keyed shape --
     * for callers that hold an id rather than a live entity.
     */
    public static void clear(@NotNull UUID entityId) {
        MOB_ORIGIN.remove(entityId);
    }

    /** Clear the entire table (e.g. on server stop). Mirrors {@link com.gmail.nossr50.platform.MetadataStore#clearAll}. */
    public static void clearAll() {
        MOB_ORIGIN.clear();
    }
}
