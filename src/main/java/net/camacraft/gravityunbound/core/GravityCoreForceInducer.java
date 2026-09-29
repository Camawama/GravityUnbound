package net.camacraft.gravityunbound.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.core.api.world.PhysLevel;

import net.camacraft.camafy.compat.vs.ShipForces;
import net.camacraft.camafy.compat.vs.impl.VsTyped;
import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.config.GravityConfig;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Gravity-field forces (cores and plating) on Valkyrien Skies ships: one
 * instance per held ship, holding the fields that currently hold it.
 *
 * THE MODEL. A field sets a CLOSING ACCELERATION between the held ship and
 * the ship (or the world) carrying the field: the force is the field's
 * acceleration times the pair's REDUCED mass, applied equal and opposite to
 * both bodies. A planet-sized source barely moves and the held ship falls
 * at the full field strength; equal masses each take half; a one-block
 * core ship pulling a freighter simply falls onto the freighter at the
 * field strength. No body ever accelerates faster than the field, and the
 * pair's momentum is conserved — a field can never push its own ship
 * around by pulling something into its hull. World-mounted fields react
 * on nothing: the world is the immovable mass.
 *
 * DISSIPATION. A constant pull pressing blocks into a hull is a textbook
 * energy source for a rigid-body contact solver (penetration correction,
 * friction), and a conservative field never removes any of it — a cluster
 * of captured blocks around a core ship kept jittering until it spun the
 * core up and flung them off. A held pair therefore also gets pairwise
 * damping of its RELATIVE velocity and RELATIVE spin (config
 * {@code shipFieldDamping}, per second), equal and opposite on both, so
 * captures settle into resting contact with momentum still conserved.
 *
 * THREADING. The game thread only describes the fields that hold a ship
 * ({@link FieldSource}: which ship carries it, its geometry in that ship's
 * block grid, strength, falloff). The physics thread evaluates them every
 * physics tick from LIVE transforms and velocities — a force computed 50
 * ms earlier on the game thread and held constant was itself a second
 * energy source (a lagged central force is a negative damper). Sources are
 * handed over with a game-tick-stamped list that is only promoted once its
 * tick has passed, so the physics thread never sees a half-built list.
 *
 * GRAVITY. A field replaces the gravity acting on a ship it holds (the
 * way an entity in a field ignores world gravity): the game thread
 * determines that gravity ({@code util.ShipGravity}: the dimension's
 * vector as VS registered it, or a per-ship override such as VMod's) and
 * the physics thread cancels exactly that — unless the field is set to
 * BLEND with world gravity, for fields meant to add to it.
 *
 * PHYSICS CALLBACK. Camafy's shared ship force hook, not an attachment of
 * our own: every loaded server ship carries Camafy's single force
 * attachment, which runs the registered physics hooks on the physics thread
 * every physics step ({@link #registerPhysicsHook}). A second attachment
 * of this mod's would compete with the other mods' for the same ships. The
 * per-ship state lives in {@link #STATES}, keyed by ship id: created when a
 * field first offers itself for a ship ({@link #getOrCreate}), retired by
 * the physics thread once the hold has expired and nothing new is pending.
 * A ship that unloads mid-hold leaves its state behind; lists older than
 * {@link #STALE_GAME_TICKS} are never applied, so a reloaded ship starts
 * clean exactly as a fresh attachment did, and the map is cleared when the
 * server stops.
 */
public final class GravityCoreForceInducer {

    /** Source id of a field mounted in the world rather than on a ship. */
    public static final long WORLD = -1L;

    /**
     * One field holding this ship, as the game thread described it. All
     * geometry is in the SOURCE's block grid (world coordinates for a
     * world-mounted source) and is transformed with the source ship's live
     * transform on the physics thread. Immutable; never mutate the vectors.
     */
    public record FieldSource(
        long sourceShipId,
        boolean radial,
        // radial (core): grid center, WORLD-scaled reach, grid->world scale
        Vector3dc center, double range, boolean attracting, double gridScale,
        // planar (plating): grid effect box, grid effect direction, falloff plane
        AABBdc box, Vector3dc direction, Vector3dc falloffOrigin, Vector3dc falloffNormal, double falloffRange,
        // closing acceleration (m/s^2) and how it fades
        double accel, boolean gradualFalloff,
        // whether the field replaces the dimension's gravity for the held ship
        boolean replacesGravity
    ) {
        public static FieldSource radial(
            long sourceShipId, Vector3dc gridCenter, double worldRange, double gridScale,
            double accel, boolean attracting, boolean gradualFalloff, boolean replacesGravity
        ) {
            return new FieldSource(sourceShipId, true, new Vector3d(gridCenter), worldRange, attracting, gridScale,
                null, null, null, null, 0.0, accel, gradualFalloff, replacesGravity);
        }

        public static FieldSource planar(
            long sourceShipId, AABBdc gridBox, Vector3dc gridDirection,
            Vector3dc falloffOrigin, Vector3dc falloffNormal, double falloffRange,
            double accel, boolean gradualFalloff, boolean replacesGravity
        ) {
            return new FieldSource(sourceShipId, false, null, 0.0, true, 1.0,
                new AABBd(gridBox), new Vector3d(gridDirection), new Vector3d(falloffOrigin),
                new Vector3d(falloffNormal), falloffRange, accel, gradualFalloff, replacesGravity);
        }
    }

    /** Physics ticks per game tick is small; after this many physics ticks
     *  without a fresh list the game thread has stopped refreshing (source
     *  removed / ship left every field) and the hold expires. */
    private static final int EXPIRY_PHYS_TICKS = 10;

    /** A complete list older than this many game ticks when the physics
     *  thread gets to it belongs to a ship that unloaded mid-hold and has
     *  just come back: it is dropped, as a fresh attachment would have had
     *  nothing. In normal running a list is one or two ticks old. */
    private static final int STALE_GAME_TICKS = 20;

    /** Camafy physics-hook key (the old attachment's key). */
    private static final ResourceLocation HOOK_KEY = new ResourceLocation(GravityAPI.MODID, "field_force");

    /** Per-ship state, keyed by ship id (read by the physics thread). */
    private static final ConcurrentHashMap<Long, GravityCoreForceInducer> STATES = new ConcurrentHashMap<>();

    private final long shipId;
    // set (with `this` held) when the physics thread drops this state from
    // STATES; an offer that finds it set goes to the ship's new state
    private boolean retired = false;

    // in-progress list for the game tick stamped by buildingTick (guarded by `this`)
    private List<FieldSource> building = null;
    private int buildingTick = 0;
    // the gravity (m/s^2, world axes) acting on this ship as the game
    // thread determined it — dimension gravity, or another mod's per-ship
    // override — which a REPLACING field cancels
    private Vector3d buildingGravity = null;

    // last COMPLETE game-tick list, not yet latched by the physics thread (guarded by `this`)
    private List<FieldSource> completed = null;
    private Vector3d completedGravity = null;
    private int completedTick = 0;
    private boolean completedFresh = false;

    // physics-thread state: the physics runs several ticks per game tick, so
    // the last known list keeps applying until the game thread stops refreshing it
    private List<FieldSource> active = null;
    private Vector3d activeGravity = null;
    private int physTicksSinceUpdate = 0;

    private GravityCoreForceInducer(long shipId) {
        this.shipId = shipId;
    }

    /**
     * The state of a LOADED ship (only loaded ships are ever pulled: the
     * physics thread only steps loaded ships, and fields look them up among
     * the loaded ones). Null while Camafy's ship force hook is unavailable
     * this session (Valkyrien Skies refused it, or it broke): fields then
     * leave ships alone.
     */
    @Nullable
    public static GravityCoreForceInducer getOrCreate(LoadedServerShip ship) {
        if (!ShipForces.available()) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                LOGGER.warn("Camafy's ship force hook is unavailable; "
                    + "Gravity Unbound fields will not pull ships this session");
            }
            return null;
        }
        return STATES.computeIfAbsent(ship.getId(), GravityCoreForceInducer::new);
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static volatile boolean warnedUnavailable = false;

    /**
     * Registers the physics hook with Camafy, whose single attachment runs
     * it for every loaded server ship on every physics step (an idle ship
     * costs one map lookup), and forgets every ship's state when the server
     * stops (ship ids and tick counts start over with the next world).
     * Called once from common setup.
     */
    public static void registerPhysicsHook() {
        VsTyped.registerPhysicsHook(HOOK_KEY, GravityCoreForceInducer::onPhysicsTick);
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> STATES.clear());
    }

    // ------------------------------------------------------------------
    // game thread
    // ------------------------------------------------------------------

    /**
     * Declares that {@code source} holds this ship during the current game
     * tick. A plated deck is many plates whose fields all hold the same
     * ship: only the FIRST planar source per carrying ship (or the world)
     * counts per tick, or a deck would multiply its gravity by its plate
     * count. Every radial source (core) counts — each core is one source.
     */
    public void offer(FieldSource source, Vector3dc gravityActingOnShip) {
        GravityCoreForceInducer state = this;
        // the physics thread may have retired this state since it was
        // looked up: the offer then goes to the ship's new one
        while (!state.offerIfLive(source, gravityActingOnShip)) {
            state = STATES.computeIfAbsent(shipId, GravityCoreForceInducer::new);
        }
    }

    private synchronized boolean offerIfLive(FieldSource source, Vector3dc gravityActingOnShip) {
        if (retired) {
            return false;
        }
        promoteIfTickPassed();
        int tick = currentGameTick();
        if (building == null || buildingTick != tick) {
            building = new ArrayList<>(4);
            buildingTick = tick;
        }
        buildingGravity = new Vector3d(gravityActingOnShip);
        if (!source.radial()) {
            for (FieldSource existing : building) {
                if (!existing.radial() && existing.sourceShipId() == source.sourceShipId()) {
                    return true;
                }
            }
        }
        building.add(source);
        return true;
    }

    /**
     * Moves {@code building} to {@code completed} once its game tick is over,
     * i.e. once every source has had its chance to declare itself. Must be
     * called with {@code this} held.
     */
    private void promoteIfTickPassed() {
        if (building != null && buildingTick != currentGameTick()) {
            completed = building;
            completedGravity = buildingGravity;
            completedTick = buildingTick;
            completedFresh = true;
            building = null;
            buildingGravity = null;
        }
    }

    /**
     * The server's game tick counter, readable from both the game and physics
     * threads (a plain int read; at worst one tick stale on the physics thread,
     * which only delays a promotion by one physics tick).
     */
    private int currentGameTick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.getTickCount() : buildingTick + 1;
    }

    // ------------------------------------------------------------------
    // physics thread
    // ------------------------------------------------------------------

    /** Camafy physics hook: runs for every loaded server ship, every physics step. */
    private static void onPhysicsTick(PhysShip physShip, PhysLevel physLevel, double dt) {
        if (STATES.isEmpty()) {
            return;
        }
        GravityCoreForceInducer state = STATES.get(physShip.getId());
        if (state != null) {
            state.applyForces(physShip, physLevel);
        }
    }

    private void applyForces(PhysShip self, @Nullable PhysLevel physLevel) {
        List<FieldSource> sources;
        Vector3d gravityActing;
        synchronized (this) {
            promoteIfTickPassed();
            boolean latched = false;
            if (completedFresh) {
                completedFresh = false;
                int age = currentGameTick() - completedTick;
                // (a stale list counts as a missed refresh, see STALE_GAME_TICKS)
                if (age >= 0 && age <= STALE_GAME_TICKS) {
                    active = completed;
                    activeGravity = completedGravity;
                    physTicksSinceUpdate = 0;
                    latched = true;
                }
            }
            if (!latched && ++physTicksSinceUpdate > EXPIRY_PHYS_TICKS) {
                // the game thread stopped refreshing (source removed / out of range)
                active = null;
                activeGravity = null;
                if (building == null) {
                    // and nothing is offering: forget this ship until a field holds it again
                    retired = true;
                    STATES.remove(shipId, this);
                }
            }
            sources = active;
            gravityActing = activeGravity;
        }
        if (sources == null || sources.isEmpty()) {
            return;
        }

        double selfMass = self.getMass();
        if (!(selfMass > 0.0)) {
            return;
        }
        Vector3dc selfPos = self.getTransform().getPositionInWorld();
        double selfScale = shipScale(self);

        boolean held = false;
        boolean replacesGravity = false;
        boolean heldByWorld = false;
        List<PhysShip> partners = new ArrayList<>(2);

        for (FieldSource field : sources) {
            PhysShip source = null;
            if (field.sourceShipId() != WORLD) {
                if (physLevel == null) {
                    continue;
                }
                source = physLevel.getShipById(field.sourceShipId());
                if (source == null || source.getId() == self.getId()) {
                    continue;
                }
            }
            Vector3d force = evaluate(field, selfPos, selfMass, selfScale, source);
            if (force == null) {
                continue;
            }
            self.applyInvariantForce(force);
            if (source != null) {
                // Newton's third law: the carrying ship gets the opposite pull
                source.applyInvariantForce(force.negate(new Vector3d()));
                if (!partners.contains(source)) {
                    partners.add(source);
                }
            }
            else {
                heldByWorld = true;
            }
            held = true;
            replacesGravity |= field.replacesGravity();
        }
        if (!held) {
            return;
        }

        double damping = GravityConfig.shipFieldDamping.get();
        if (damping > 0.0) {
            for (PhysShip partner : partners) {
                damp(self, selfMass, partner, damping);
            }
            if (heldByWorld) {
                damp(self, selfMass, null, damping);
            }
        }

        if (replacesGravity && gravityActing != null && gravityActing.isFinite()
            && gravityActing.lengthSquared() > 1.0E-12) {
            // A FIELD REPLACES GRAVITY: cancel exactly the gravity the game
            // thread saw acting on this ship (the dimension's vector as VS
            // registered it, or another mod's per-ship override such as
            // VMod's — cancelling the dimension's gravity on a ship VMod
            // had already zeroed left a net 1 g UPWARD).
            self.applyInvariantForce(gravityActing.mul(-selfMass, new Vector3d()));
        }
    }

    /**
     * The pull one field exerts on this ship right now: field acceleration
     * times the pair's reduced mass, along the field's direction evaluated
     * from the source's LIVE transform. Null when the ship's center of mass
     * is outside the field.
     */
    @Nullable
    private static Vector3d evaluate(
        FieldSource field, Vector3dc selfPos, double selfMass, double selfScale, @Nullable PhysShip source
    ) {
        ShipTransform sourceTransform = source != null ? source.getTransform() : null;
        double reducedMass = selfMass;
        if (source != null) {
            double sourceMass = source.getMass();
            if (!(sourceMass > 0.0)) {
                return null;
            }
            reducedMass = selfMass * sourceMass / (selfMass + sourceMass);
        }

        // A field's acceleration is authored for full-size ships. A SCALED
        // ship (Genesis space runs ships at 1/16) feels it at its own scale
        // — a 1/16 ship falls one of its own blocks in the time a full-size
        // ship falls one block — otherwise a mini ship is flung across the
        // room by what a full-size ship feels as 1 g.
        double accel = field.accel() * selfScale;
        Vector3d direction;
        if (field.radial()) {
            Vector3d center = new Vector3d(field.center());
            if (sourceTransform != null) {
                sourceTransform.getShipToWorld().transformPosition(center);
            }
            Vector3d toCenter = center.sub(selfPos);
            double distance = toCenter.length();
            // no inner dead zone: a one-block ship resting ON a one-block
            // core ship has its center exactly one block away
            if (distance > field.range() || distance < 1.0E-3) {
                return null;
            }
            if (field.gradualFalloff()) {
                // inverse-square in the source's own grid units, capped at
                // full strength (same law the entity pull follows)
                double gridDistance = distance / field.gridScale();
                accel *= Math.min(1.0, 16.0 / (gridDistance * gridDistance));
            }
            direction = toCenter.div(distance);
            if (!field.attracting()) {
                direction.negate();
            }
        }
        else {
            Vector3d local = new Vector3d(selfPos);
            if (sourceTransform != null) {
                sourceTransform.getWorldToShip().transformPosition(local);
            }
            if (!field.box().containsPoint(local)) {
                return null;
            }
            if (field.gradualFalloff()) {
                double distanceToPlane = -new Vector3d(local).sub(field.falloffOrigin()).dot(field.falloffNormal());
                accel *= Math.max(0.0, Math.min(1.0, 1.0 - Math.max(0.0, distanceToPlane) / field.falloffRange()));
            }
            direction = new Vector3d(field.direction());
            if (sourceTransform != null) {
                sourceTransform.getShipToWorld().transformDirection(direction);
            }
            if (direction.lengthSquared() < 1.0E-12) {
                return null;
            }
            direction.normalize();
        }
        if (!(accel > 0.0)) {
            return null;
        }
        Vector3d force = direction.mul(accel * reducedMass);
        return force.isFinite() ? force : null;
    }

    /**
     * Pairwise damping of the relative velocity and relative spin between
     * this ship and {@code partner} (null: the world frame), scaled by the
     * pair's reduced mass / reduced inertia so the RELATIVE motion decays
     * at {@code rate} per second whatever the masses, and applied equal
     * and opposite so momentum and angular momentum are conserved.
     */
    private static void damp(PhysShip self, double selfMass, @Nullable PhysShip partner, double rate) {
        double reducedMass = selfMass;
        Vector3d relativeVelocity = new Vector3d(self.getVelocity());
        Vector3d relativeSpin = new Vector3d(self.getAngularVelocity());
        if (partner != null) {
            double partnerMass = partner.getMass();
            if (!(partnerMass > 0.0)) {
                return;
            }
            reducedMass = selfMass * partnerMass / (selfMass + partnerMass);
            relativeVelocity.sub(partner.getVelocity());
            relativeSpin.sub(partner.getAngularVelocity());
        }

        if (relativeVelocity.isFinite() && relativeVelocity.lengthSquared() > 1.0E-8) {
            Vector3d force = relativeVelocity.mul(-rate * reducedMass);
            self.applyInvariantForce(force);
            if (partner != null) {
                partner.applyInvariantForce(force.negate(new Vector3d()));
            }
        }

        double spinSq = relativeSpin.lengthSquared();
        if (relativeSpin.isFinite() && spinSq > 1.0E-8) {
            Vector3d axis = new Vector3d(relativeSpin).div(Math.sqrt(spinSq));
            double selfInertia = inertiaAbout(self, axis);
            double reducedInertia = selfInertia;
            if (partner != null) {
                double partnerInertia = inertiaAbout(partner, axis);
                if (selfInertia + partnerInertia > 0.0) {
                    reducedInertia = selfInertia * partnerInertia / (selfInertia + partnerInertia);
                }
            }
            if (reducedInertia > 0.0) {
                Vector3d torque = relativeSpin.mul(-rate * reducedInertia);
                self.applyInvariantTorque(torque);
                if (partner != null) {
                    partner.applyInvariantTorque(torque.negate(new Vector3d()));
                }
            }
        }
    }

    /** The ship's uniform world scale (1 for ordinary ships), from its live transform. */
    private static double shipScale(PhysShip ship) {
        try {
            Vector3dc scaling = ship.getTransform().getShipToWorldScaling();
            double scale = Math.cbrt(Math.abs(scaling.x() * scaling.y() * scaling.z()));
            if (!Double.isFinite(scale) || scale < 1.0E-3) {
                return 1.0;
            }
            return Math.min(scale, 1.0E3);
        }
        catch (RuntimeException e) {
            return 1.0;
        }
    }

    /**
     * Scalar moment of inertia about a unit WORLD axis: a^T I a, with the
     * axis first taken into the ship's own axes — VS's inertia tensor is in
     * SHIP axes (the same computation as Camafy's PhysBody.inertiaAbout).
     * Using the world axis directly damped a rotated, non-symmetric ship's
     * spin against the wrong inertia.
     */
    private static double inertiaAbout(PhysShip ship, Vector3dc worldAxis) {
        Vector3d axis = ship.getTransform().getShipToWorldRotation().transformInverse(new Vector3d(worldAxis));
        Vector3d transformed = ship.getMomentOfInertia().transform(axis, new Vector3d());
        return Math.max(0.0, transformed.dot(axis));
    }
}
