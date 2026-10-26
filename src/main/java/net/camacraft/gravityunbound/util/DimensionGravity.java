package net.camacraft.gravityunbound.util;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.camacraft.gravityunbound.config.GravityConfig;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Per-dimension ambient gravity.
 *
 * Sources, in override order: runtime API registrations (mods calling
 * {@link #set}) win over the {@code dimensionGravity} server config entries,
 * which win over the world's VS Genesis body ({@link #bodyEntry}: a world that
 * is a body, a Camafy runtime planet or moon, has that body's surface gravity,
 * down; config {@code celestialSurfaceGravity}).
 * Applied inside the gravity capability as a LOW-priority direction effect
 * (priority 100), so every plating/core/normalizer field overrides it, and
 * entities leaving all fields settle onto the dimension's gravity instead of
 * plain world-down.
 */
public final class DimensionGravity {

    public record Entry(Vec3 down, double strength) {}

    // runtime API registrations (win over config)
    private static final Map<ResourceLocation, Entry> RUNTIME = new ConcurrentHashMap<>();

    // parsed config cache, rebuilt whenever the backing list instance changes
    // (config load/reload swaps the list)
    private static volatile List<? extends String> parsedFrom = null;
    private static volatile Map<ResourceLocation, Entry> configEntries = Map.of();

    /** API: set ambient gravity for a dimension (pass null entry via {@link #clear}). */
    public static void set(ResourceLocation dimension, Direction down, double strength) {
        RUNTIME.put(dimension, new Entry(Vec3.atLowerCornerOf(down.getNormal()), strength));
    }

    public static void clear(ResourceLocation dimension) {
        RUNTIME.remove(dimension);
    }

    /** The ambient gravity direction for this level, or null for vanilla. */
    @Nullable
    public static Vec3 downFor(Level level) {
        Entry entry = entryFor(level);
        return entry != null ? entry.down() : null;
    }

    public static double strengthFor(Level level) {
        Entry entry = entryFor(level);
        return entry != null ? entry.strength() : 1.0;
    }

    @Nullable
    private static Entry entryFor(Level level) {
        Entry configured = configuredFor(level);
        return configured != null ? configured : bodyEntry(level);
    }

    /** A runtime registration or a config entry for this level, or null. */
    @Nullable
    private static Entry configuredFor(Level level) {
        ResourceLocation dim = level.dimension().location();
        Entry runtime = RUNTIME.get(dim);
        if (runtime != null) {
            return runtime;
        }
        // the config's entries, parsed when the config loads or reloads (PerfSwitches.refresh -> reload): this used to
        // read the config value here, once per entity per tick (a locked path walk in Forge's config), 2026-10-17
        Map<ResourceLocation, Entry> entries = configEntries;
        return entries.isEmpty() ? null : entries.get(dim);
    }

    private static final Vec3 DOWN = new Vec3(0.0, -1.0, 0.0);

    /** One level's body entry as of one game tick (per side: a client's level and the server's share a key). */
    private record BodyCache(long gameTime, @Nullable Entry entry) {}

    private static final Map<ResourceKey<Level>, BodyCache> SERVER_BODIES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, BodyCache> CLIENT_BODIES = new ConcurrentHashMap<>();

    /**
     * The world's own VS Genesis body as ambient gravity: straight down at the body's surface gravity
     * ({@code Celestials.surfaceGravity}: Genesis's value times Camafy's {@code gravityScale}), or null where that is
     * Earth's (exactly vanilla), where the world isn't a body, in Genesis's space, where Ad Astra gives the world a
     * gravity of its own (its planets), or with {@code celestialSurfaceGravity} off. Worked out once per game tick
     * per level and side: entities ask for every tick.
     */
    @Nullable
    public static Entry bodyEntry(Level level) {
        if (!net.camacraft.gravityunbound.celestial.CelestialGravity.surfaceEnabled()) {
            return null;
        }
        Map<ResourceKey<Level>, BodyCache> cache = level.isClientSide() ? CLIENT_BODIES : SERVER_BODIES;
        long now = level.getGameTime();
        BodyCache cached = cache.get(level.dimension());
        if (cached != null && cached.gameTime() == now) {
            return cached.entry();
        }
        Entry entry = null;
        if (!net.camacraft.camafy.compat.genesis.Celestials.isSpace(level)) {
            double g = net.camacraft.gravityunbound.celestial.CelestialGravity.surfaceGravity(level);
            if (Double.isFinite(g) && g >= 0.0 && Math.abs(g - 1.0) > 1.0E-6
                && net.camacraft.gravityunbound.compat.AdAstraCompat.planetGravity(level) == 1.0f) {
                entry = new Entry(DOWN, g);
            }
        }
        cache.put(level.dimension(), new BodyCache(now, entry));
        return entry;
    }

    /**
     * How much to add to the gravity VS applies to ships in this level, as a share of it, for the world's VS Genesis
     * body ({@code body - 1}: -0.84 on a moon), or NaN where nothing changes: no body entry, or a runtime registration
     * or config entry naming the world (this mod's dimension gravity has never touched ships).
     */
    public static double bodyShipFactor(Level level) {
        if (configuredFor(level) != null) {
            return Double.NaN;
        }
        Entry body = bodyEntry(level);
        return body == null ? Double.NaN : body.strength() - 1.0;
    }

    /** Forgets the cached body answers (the server stopped). */
    public static void clearBodyCache() {
        SERVER_BODIES.clear();
        CLIENT_BODIES.clear();
    }

    /** Re-parses the config's dimension list (called when the config loads or reloads, and once per level tick). */
    public static void reload() {
        List<? extends String> raw;
        try {
            raw = GravityConfig.dimensionGravity.get();
        }
        catch (IllegalStateException | NullPointerException e) {
            return; // config not loaded yet
        }
        if (raw != parsedFrom) {
            configEntries = parse(raw);
            parsedFrom = raw;
        }
    }

    private static Map<ResourceLocation, Entry> parse(List<? extends String> raw) {
        Map<ResourceLocation, Entry> out = new HashMap<>();
        for (String line : raw) {
            try {
                String[] kv = line.split("=", 2);
                ResourceLocation dim = new ResourceLocation(kv[0].trim());
                String[] parts = kv[1].split(",");
                Direction dir = Direction.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
                double strength = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : 1.0;
                out.put(dim, new Entry(Vec3.atLowerCornerOf(dir.getNormal()), strength));
            }
            catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(DimensionGravity.class)
                    .warn("[GravityUnbound] bad dimensionGravity entry '{}': {}", line, e.toString());
            }
        }
        return Map.copyOf(out);
    }

    private DimensionGravity() {}
}
