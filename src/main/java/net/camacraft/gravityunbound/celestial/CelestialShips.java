package net.camacraft.gravityunbound.celestial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;

import net.camacraft.camafy.compat.genesis.Celestials;
import net.camacraft.camafy.compat.vs.impl.VsTyped;
import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.core.GravityCoreForceInducer;
import net.camacraft.gravityunbound.util.DimensionGravity;
import net.camacraft.gravityunbound.util.ShipGravity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * {@link CelestialGravity}'s pull on Valkyrien Skies ships, and a body world's gravity for the ships on it.
 *
 * <p><b>In Genesis's space</b> every loaded ship that isn't static is pulled toward each body in reach by that body's
 * surface gravity (VS's 10 m/s² per Earth gravity, its own default dimension vector) times Genesis's 1/16, falling off
 * as {@link CelestialGravity#falloff}: Earth's surface pulls a ship at 0.625 m/s², the sun's (Genesis's gravity 2.0)
 * at 1.25 m/s² within its 3 radii. The planet a ship just left stays off for the ship's {@link CelestialGravity.Grace}
 * (Camafy's {@link Celestials#departure} of the ship, recorded at Genesis's teleporter). Every body pulls every ship the
 * same way whatever it weighs: the force is the ship's own mass times the pull.</p>
 *
 * <p><b>On a body world</b> (a world that is a Genesis body with a gravity other than Earth's, Camafy's moons at 0.16)
 * a ship falls at that body's gravity: the gravity VS applies to it there ({@code ShipGravity.actingOn}: the dimension's
 * vector, VMod's per-ship one) is scaled by the body's, with an extra force of {@code (body - 1) × that × mass}. Not
 * where this mod's {@code dimensionGravity} names the world or Ad Astra has its own gravity for it (Camafy already
 * gives VS Ad Astra's), and not for a ship a gravity field holds (the field owns its gravity).</p>
 *
 * <p><b>Threading,</b> as with the field forces ({@code GravityCoreForceInducer}): the game thread describes, once a
 * server tick per ship, which bodies pull it (centre, radius, reach, surface pull after the grace); the physics thread
 * works the force out every physics step from the ship's live position, through Camafy's shared ship force hook. A
 * description older than {@link #STALE_GAME_TICKS} is never applied (a ship that unloaded and came back starts clean).
 * Bodies move slowly enough (Genesis's moon at 0.5 m/s) for their game-tick positions to stand for the three physics
 * steps of a tick.</p>
 */
public final class CelestialShips {
    /** VS's gravity, m/s² per Earth gravity (its default dimension vector is (0, -10, 0)). */
    public static final double SHIP_G = 10.0;
    private static final ResourceLocation HOOK_KEY = new ResourceLocation(GravityAPI.MODID, "celestial_gravity");
    private static final int STALE_GAME_TICKS = 20;
    /** Bodies this far beyond their reach are still handed over, so a fast ship meets the edge between game ticks. */
    private static final double REACH_MARGIN = 32.0;

    /** One body pulling one ship: centre, radius and reach in blocks, surface pull in m/s² after the grace. */
    private record Source(double x, double y, double z, double radius, double reach, double accel) {
    }

    /** What pulls one ship, as the game thread last described it; {@code extra} is a constant pull, m/s² world axes. */
    private record Pull(Source[] sources, double extraX, double extraY, double extraZ, int tick) {
    }

    private static final Source[] NO_SOURCES = new Source[0];
    private static final ConcurrentHashMap<Long, Pull> PULLS = new ConcurrentHashMap<>();
    /** The departure grace of each ship that left a planet (game thread only). */
    private static final Map<Long, CelestialGravity.Grace> GRACES = new HashMap<>();

    private CelestialShips() {
    }

    /** Common setup: the physics hook, the per-level description pass, and forgetting it all when the server stops. */
    public static void register() {
        VsTyped.registerPhysicsHook(HOOK_KEY, CelestialShips::onPhysicsTick);
        MinecraftForge.EVENT_BUS.addListener((TickEvent.LevelTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
                describe(level);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            PULLS.clear();
            GRACES.clear();
        });
    }

    /** For readouts: the grace of a ship, or null. */
    public static @Nullable CelestialGravity.Grace graceOf(long shipId) {
        return GRACES.get(shipId);
    }

    /** For readouts: the pull the physics thread applies to a ship at {@code pos} (m/s², world axes), or null. */
    public static @Nullable Vec3 pullOn(long shipId, Vector3dc pos) {
        Pull pull = PULLS.get(shipId);
        if (pull == null) {
            return null;
        }
        Vector3d a = accelerationAt(pull, pos);
        return new Vec3(a.x, a.y, a.z);
    }

    // ------------------------------------------------------------------
    // game thread
    // ------------------------------------------------------------------

    private static void describe(ServerLevel level) {
        MinecraftServer server = level.getServer();
        int tick = server.getTickCount();
        if (tick % 20 == 0) {
            PULLS.entrySet().removeIf(e -> tick - e.getValue().tick() > STALE_GAME_TICKS);
            GRACES.keySet().removeIf(id -> Celestials.departure(id) == null);
        }
        if (!CelestialGravity.shipsEnabled() || !Celestials.available()) {
            return;
        }
        if (Celestials.isSpace(level)) {
            if (CelestialGravity.enabled()) {
                describeSpace(level, tick);
            }
        }
        else if (CelestialGravity.surfaceEnabled()) {
            double factor = DimensionGravity.bodyShipFactor(level);
            if (!Double.isNaN(factor)) {
                describeBodyWorld(level, tick, factor);
            }
        }
    }

    private static void describeSpace(ServerLevel level, int tick) {
        List<Celestials.Body> bodies = Celestials.bodies(level);
        if (bodies.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        VsTyped.forEachLoaded(level, loaded -> {
            if (!(loaded instanceof LoadedServerShip ship) || ship.isStatic()) {
                return;
            }
            long id = ship.getId();
            CelestialGravity.Grace grace = graceFor(id, now);
            if (grace != null) {
                grace.tick(now);
            }
            Vector3dc p = ship.getTransform().getPositionInWorld();
            List<Source> sources = new ArrayList<>(2);
            for (Celestials.Body body : bodies) {
                double surface = CelestialGravity.surfacePull(level, body);
                double radius = body.radius();
                if (!(surface > 0.0) || !(radius > 0.0)) {
                    continue;
                }
                double reach = CelestialGravity.reach(body);
                Vec3 c = body.position();
                double dx = c.x - p.x();
                double dy = c.y - p.y();
                double dz = c.z - p.z();
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double limit = reach + REACH_MARGIN;
                if (!(d < limit)) {
                    continue;
                }
                double held = grace != null ? grace.factor(body.id(), d) : 1.0;
                if (!(held > 0.0)) {
                    continue;
                }
                sources.add(new Source(c.x, c.y, c.z, radius, reach, surface * SHIP_G * held));
            }
            if (sources.isEmpty()) {
                PULLS.remove(id);
                return;
            }
            PULLS.put(id, new Pull(sources.toArray(NO_SOURCES), 0.0, 0.0, 0.0, tick));
        });
    }

    private static void describeBodyWorld(ServerLevel level, int tick, double factor) {
        VsTyped.forEachLoaded(level, loaded -> {
            if (!(loaded instanceof LoadedServerShip ship) || ship.isStatic()) {
                return;
            }
            long id = ship.getId();
            if (GravityCoreForceInducer.heldByField(id)) {
                PULLS.remove(id);
                return;
            }
            Vector3d g = ShipGravity.actingOn(level, ship);
            if (!g.isFinite() || g.lengthSquared() < 1.0E-12) {
                PULLS.remove(id);
                return;
            }
            PULLS.put(id, new Pull(NO_SOURCES, g.x * factor, g.y * factor, g.z * factor, tick));
        });
    }

    private static @Nullable CelestialGravity.Grace graceFor(long shipId, long now) {
        Celestials.Departure departure = Celestials.departure(shipId);
        if (departure == null) {
            GRACES.remove(shipId);
            return null;
        }
        CelestialGravity.Grace grace = GRACES.get(shipId);
        if (grace == null || !grace.matches(departure)) {
            grace = new CelestialGravity.Grace(departure, now);
            GRACES.put(shipId, grace);
        }
        return grace;
    }

    // ------------------------------------------------------------------
    // physics thread
    // ------------------------------------------------------------------

    private static void onPhysicsTick(PhysShip ship, PhysLevel physLevel, double dt) {
        if (PULLS.isEmpty()) {
            return;
        }
        Pull pull = PULLS.get(ship.getId());
        if (pull == null) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() - pull.tick() > STALE_GAME_TICKS) {
            return;
        }
        double mass = ship.getMass();
        if (!(mass > 0.0)) {
            return;
        }
        Vector3d a = accelerationAt(pull, ship.getTransform().getPositionInWorld());
        if (a.lengthSquared() < 1.0E-12) {
            return;
        }
        Vector3d force = a.mul(mass);
        if (force.isFinite()) {
            ship.applyInvariantForce(force);
        }
    }

    private static Vector3d accelerationAt(Pull pull, Vector3dc p) {
        Vector3d a = new Vector3d(pull.extraX(), pull.extraY(), pull.extraZ());
        for (Source s : pull.sources()) {
            double dx = s.x() - p.x();
            double dy = s.y() - p.y();
            double dz = s.z() - p.z();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1.0E-6) {
                continue;
            }
            double k = s.accel() * CelestialGravity.falloff(d, s.radius(), s.reach()) / d;
            a.add(dx * k, dy * k, dz * k);
        }
        return a;
    }
}
