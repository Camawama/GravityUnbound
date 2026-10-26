package net.camacraft.gravityunbound.util;

import net.camacraft.gravityunbound.config.GravityConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Config values the per-entity and per-fluid-tick code reads, cached (2026-10-17, the owner's spark profile of
 * 10-15: a Forge {@code ConfigValue.get()} walks the config's path under a lock, and
 * {@code gravityStrengthMultiplier}, {@code gravityAffectsFluids} and {@code dimensionGravity} were read once per
 * entity per tick and several times per fluid spread; 0.2 s of a 120 s server thread each). Refreshed when the
 * config loads or reloads and once at the start of every level tick, so a changed file is seen within a tick.
 *
 * <p>{@link #idleFastPaths()} is the switch for the fast paths that skip work for an entity whose gravity is plain
 * vanilla and whose frame is settled ({@code Performance.idleEntityFastPaths} in gravity-apivs.toml;
 * {@code -Dgravityunbound.idleFastPaths=false} forces them off live for an A/B, read once per level tick).
 */
public final class PerfSwitches {
    private static volatile boolean idleFastPaths = true;
    private static volatile double gravityStrengthMultiplier = 1.0;
    private static volatile boolean gravityAffectsFluids = true;
    private static volatile boolean shipFieldsAffectWorldFluids = true;

    private PerfSwitches() {
    }

    public static void register(IEventBus modBus, IEventBus forgeBus) {
        modBus.addListener((ModConfigEvent.Loading e) -> refresh());
        modBus.addListener((ModConfigEvent.Reloading e) -> refresh());
        forgeBus.addListener((TickEvent.LevelTickEvent e) -> {
            if (e.phase == TickEvent.Phase.START) {
                refresh();
            }
        });
    }

    /** Re-reads every cached value (cheap: four config reads and a system property). */
    public static void refresh() {
        boolean fast = true;
        double strength = 1.0;
        boolean fluids = true;
        boolean shipFluids = true;
        try {
            fast = GravityConfig.idleEntityFastPaths == null || GravityConfig.idleEntityFastPaths.get();
            strength = GravityConfig.gravityStrengthMultiplier.get();
            fluids = GravityConfig.gravityAffectsFluids.get();
            shipFluids = GravityConfig.shipFieldsAffectWorldFluids.get();
        }
        catch (IllegalStateException | NullPointerException ignored) {
            // config not loaded yet: the defaults above
        }
        if ("false".equalsIgnoreCase(System.getProperty("gravityunbound.idleFastPaths"))) {
            fast = false;
        }
        idleFastPaths = fast;
        gravityStrengthMultiplier = strength;
        gravityAffectsFluids = fluids;
        shipFieldsAffectWorldFluids = shipFluids;
        DimensionGravity.reload();
        net.camacraft.gravityunbound.celestial.CelestialGravity.refresh();
    }

    /** Whether entities with plain gravity and a settled frame may skip the per-tick work that can't change anything. */
    public static boolean idleFastPaths() {
        return idleFastPaths;
    }

    public static double gravityStrengthMultiplier() {
        return gravityStrengthMultiplier;
    }

    public static boolean gravityAffectsFluids() {
        return gravityAffectsFluids;
    }

    public static boolean shipFieldsAffectWorldFluids() {
        return shipFieldsAffectWorldFluids;
    }
}
