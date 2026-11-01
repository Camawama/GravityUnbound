package net.camacraft.gravityunbound.celestial;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.camacraft.camafy.compat.genesis.CelestialSettings;
import net.camacraft.camafy.compat.genesis.Celestials;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.util.GCUtil;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * VS Genesis's bodies pull what is in its space (the owner, 2026-10-23: "everything should feel it by default"; Genesis
 * itself flags every entity there no-gravity and zeroes Valkyrien Skies' ship gravity, so before this nothing in the
 * Great Unknown fell toward anything). Camafy reads the bodies ({@link Celestials}: where each is, how big, its gravity,
 * its kind) and keeps the per-body settings that {@code /planet gravity} changes ({@code gravityScale}, the reach).
 *
 * <p><b>The law.</b> A body pulls toward its centre with its surface gravity (Genesis's value, in Earth gravities, times
 * Camafy's {@code gravityScale}) falling off as (radius / distance)², from its surface out to its reach (planets and
 * moons 16 radii, stars 3, black holes 8, unless Camafy's settings say otherwise), and faded smoothly to nothing over the
 * last quarter of the reach so nothing feels the edge. Inside the cube (closer than a radius) it pulls at its surface
 * gravity; Genesis lands a ship on a planet as soon as it touches the cube. Several bodies add up as vectors.</p>
 *
 * <p><b>The 1/16 scale.</b> Genesis runs its space at 1/16 ({@link Celestials#spaceScale}): Earth's cube is 96 blocks
 * there for a planet that is 1,536 across, and players, mobs and ships are drawn and moved at 1/16 of their size (Pehkui's
 * base scale, VS's ship scale). Lengths are a sixteenth and Genesis keeps time as it is, so an acceleration that looks
 * like one g to a sixteenth-size traveller is a sixteenth of one: the pull is the body's gravity times 1/16 (Earth's
 * surface pulls a player at 0.005 blocks per tick², a ship at 0.625 m/s²). A fall toward a body then takes the time it
 * takes at full size from the same height in its own radii, and a ship that could lift itself off the planet has sixteen
 * times the thrust it needs to leave it. It also matches how Genesis scales velocity into space (a sixteenth).</p>
 *
 * <p><b>The frame turns</b> (the owner, 2026-10-24: "with the gravity from planets, it doesn't seem to rotate the
 * player like gravity fields do from gravity unbound"). Where the pull is strong enough to matter, at least
 * {@link #TURN_ON} of Earth's gravity at the traveller's own scale ({@code pull / spaceScale}), the summed pull is a
 * field to the gravity capability like a core's: an effect at {@link #PRIORITY} pointing down the summed pull, turned
 * to at a core's rate ({@link #TURN}: 300 ms, world momentum kept), with its strength; the capability then turns the
 * frame (players' view and body, mobs, items), pulls the living with its deficit pull and things with its zero-g field
 * force, and Camafy's gravity bridge reports the field as it reports any. A tenth of a g is past the halfway mark to the
 * moon's 0.16: a jump comes back within a few seconds and "down" means something; below it the traveller is floating,
 * and turning a camera toward a body hundreds of blocks off would only disorient (Earth's reach is 16 radii; a tenth of
 * a g is within 3.2 of them, so a ship Genesis drops at 2.4 radii is inside). It stops below {@link #TURN_OFF}, so
 * nothing flickers about the edge. Every field, plating, normalizer and Surface Cling outranks {@link #PRIORITY} (all
 * 500 and above) and holds the entity where it reaches; the dimension's own gravity (100) doesn't. Below the threshold
 * only the force is applied and the frame stays (Camafy's gravity bridge still reports the pull, so Full Stop's felt g
 * reads a free fall as weightless). Riders and what a ship carries (Valkyrien Skies drags it) aren't turned: a seat or
 * a deck carries them, and a deck's riders keep only the force.</p>
 *
 * <p><b>Who.</b> Every entity without a gravity of its own in Genesis's space (flagged no-gravity by Genesis, or in a
 * world Ad Astra makes weightless, as the "Genesis x Ad Astra" add-on does: {@link #weightlessHere}), on the side that moves it (the server for mobs
 * and things, the client for its own player, and the client's prediction of non-living things, as the zero-g field
 * pull does): not passengers (their vehicle or ship carries them), creative fliers, spectators, swimmers, entities a
 * gravity field holds (the field replaces gravity where it reaches, as it does on a planet) or entities another mod
 * scripts (physics claims). Ships through {@link CelestialShips}.</p>
 *
 * <p><b>Leaving a planet.</b> The owner: "if a ship is flown into space and transitions from overworld to great
 * unknown, the gravity doesn't immediately pull them back down to earth before they can fly out". Genesis drops a ship
 * a planet's size plus 20 blocks from its centre (2.4 radii for Earth), with a sixteenth of its speed: 60 m/s up becomes
 * 3.75 m/s, under escape speed there (5 m/s for Earth), so the planet would start turning it round at once. The planet
 * it left ({@link Celestials#departure}, recorded by Camafy at Genesis's teleporter; a player's client hears of its own
 * from the server) doesn't pull it for {@code celestialDepartureGraceSeconds} (20 s: at 1 m/s² of spare thrust a ship
 * goes 200 blocks in that time) or until it is {@code celestialDepartureClearFactor} times as far from the centre as it
 * arrived (2: 4.8 radii for Earth, where the pull is a twenty-third of the surface's), whichever comes first, and the
 * pull then comes back over {@code celestialDepartureRampSeconds} (5 s). Other bodies pull from the first tick. A ship
 * that coasts without thrust still falls back after that, as it should; one that keeps flying gets away.</p>
 */
public final class CelestialGravity {
    private static final double BASE = GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
    /** Below this (a multiple of vanilla gravity) a sum is no pull at all. */
    private static final double NONE = 1.0E-7;
    /** Share of a body's reach over which its pull fades to nothing at the edge. */
    private static final double FADE_SHARE = 0.25;
    /** The pull (Earth gravities at the traveller's own scale) from which a body turns the frame, and below which it stops. */
    public static final double TURN_ON = 0.10;
    public static final double TURN_OFF = 0.07;
    /**
     * The priority of the bodies' field among a gravity capability's effects: above the dimension's own gravity (100),
     * below Surface Cling (500), plating (about 1000 less the distance), cores (990 less it) and normalizers (1010).
     */
    public static final double PRIORITY = 200.0;
    /** A core's turn ({@code GravityCoreBlockEntity}): world momentum kept on entry, the view turned, 300 ms. */
    private static final net.camacraft.gravityunbound.api.RotationParameters TURN =
        new net.camacraft.gravityunbound.api.RotationParameters(false, true, 300);
    /** Effects this close below the strongest one blend with it ({@code GravityCapabilityImpl.resolveGravityTarget}). */
    private static final double BLEND_RANGE = 5.0;

    // config, cached (util.PerfSwitches.refresh: once per level tick and on config loads)
    private static volatile boolean enabled = true;
    private static volatile boolean ships = true;
    private static volatile boolean surface = true;
    private static volatile int graceTicks = 400;
    private static volatile double clearFactor = 2.0;
    private static volatile int rampTicks = 100;

    /** The planet the client's own player last left for space, as its server said (CelestialDeparturePacket). */
    private static volatile @Nullable Celestials.Departure clientDeparture;
    /**
     * Whether the server has said, since the client's player last came into space, whether it left a planet: until it
     * has, the client neither pulls nor turns its own player (one round trip; otherwise a ship's rider stepping off
     * into space right after the trip turned toward the planet for a tick or two before the grace arrived).
     */
    private static volatile boolean clientTold;

    private CelestialGravity() {
    }

    /** Re-reads the config (util.PerfSwitches). */
    public static void refresh() {
        boolean on = true;
        boolean ship = true;
        boolean ground = true;
        double grace = 20.0;
        double clear = 2.0;
        double ramp = 5.0;
        try {
            on = GravityConfig.celestialGravity.get();
            ship = GravityConfig.celestialGravityAffectsShips.get();
            ground = GravityConfig.celestialSurfaceGravity.get();
            grace = GravityConfig.celestialDepartureGraceSeconds.get();
            clear = GravityConfig.celestialDepartureClearFactor.get();
            ramp = GravityConfig.celestialDepartureRampSeconds.get();
        }
        catch (IllegalStateException | NullPointerException ignored) {
            // config not loaded yet: the defaults above
        }
        if ("false".equalsIgnoreCase(System.getProperty("gravityunbound.celestialGravity"))) {
            on = false;
        }
        if ("false".equalsIgnoreCase(System.getProperty("gravityunbound.celestialDepartureGrace"))) {
            grace = 0.0;
        }
        enabled = on;
        ships = ship;
        surface = ground;
        graceTicks = (int) Math.round(grace * 20.0);
        clearFactor = clear;
        rampTicks = (int) Math.round(ramp * 20.0);
    }

    /** Whether bodies pull in Genesis's space at all. */
    public static boolean enabled() {
        return enabled && Celestials.available();
    }

    /** Whether they pull ships too (and body worlds change how fast ships fall). */
    public static boolean shipsEnabled() {
        return ships;
    }

    /** Whether a world that is a body has that body's gravity on its surface. */
    public static boolean surfaceEnabled() {
        return surface && Celestials.available();
    }

    /** Client thread: the server's word on the local player's last departure (null: none, or it isn't in space). */
    public static void setClientDeparture(@Nullable Celestials.Departure departure) {
        clientDeparture = departure;
        clientTold = true;
    }

    /** Client thread: the player isn't in space (or not in a world): forget the server's word until it speaks again. */
    public static void forgetClientDeparture() {
        clientDeparture = null;
        clientTold = false;
    }

    // ------------------------------------------------------------------
    // the law
    // ------------------------------------------------------------------

    /**
     * How much of a body's surface pull reaches {@code distance} from its centre: (radius / distance)², 1 inside a radius,
     * faded smoothly to 0 over the last quarter of {@code reach}, 0 beyond it.
     */
    public static double falloff(double distance, double radius, double reach) {
        if (!(distance < reach)) {
            return 0.0;
        }
        double inverse = radius / Math.max(distance, radius);
        double share = inverse * inverse;
        double fadeStart = reach * (1.0 - FADE_SHARE);
        if (distance > fadeStart) {
            double t = (distance - fadeStart) / (reach - fadeStart);
            share *= 1.0 - t * t * (3.0 - 2.0 * t);
        }
        return share;
    }

    /**
     * A body's settings as they are now. Read here rather than from {@code Body.settings()}: Camafy's body snapshot is
     * refreshed when Genesis's clock moves, and that clock stands still with {@code doDaylightCycle} off, so a
     * {@code /planet gravity} change only reached the snapshot once time moved again (harness {@code gu_celestial}).
     */
    public static CelestialSettings.Entry settings(Celestials.Body body) {
        return CelestialSettings.of(body.id());
    }

    /** A body's pull at its surface in space, as a multiple of vanilla gravity (0.08 blocks/tick² for the living). */
    public static double surfacePull(Level level, Celestials.Body body) {
        if (body.kind() == Celestials.Kind.OTHER) {
            return 0.0;
        }
        double g = body.gravity() * settings(body).gravityScale();
        return g > 0.0 ? g * Celestials.spaceScale(level) : 0.0;
    }

    /** A body's reach in blocks from its centre. */
    public static double reach(Celestials.Body body) {
        return body.radius() * settings(body).gravityRangeFor(body.kind());
    }

    /**
     * A world's own body's surface gravity (Earth gravities: Genesis's value times its {@code gravityScale} now), or NaN
     * where the world isn't a body. {@code Celestials.surfaceGravity} with the settings read live (see {@link #settings}).
     */
    public static double surfaceGravity(Level level) {
        ResourceLocation id = Celestials.bodyOf(level);
        if (id == null) {
            return Double.NaN;
        }
        Celestials.Body body = Celestials.body(level, id);
        return body == null ? Double.NaN : body.gravity() * settings(body).gravityScale();
    }

    /**
     * Every body's pull at a point of {@code level} added up, a world vector in multiples of vanilla gravity; zero
     * outside Genesis's space or with the feature off. {@code grace} (the traveller's departure, or null) holds off the
     * planet it just left; it is advanced by the caller once per tick.
     */
    public static Vec3 pullAt(Level level, double x, double y, double z, @Nullable Grace grace) {
        if (!enabled() || !Celestials.isSpace(level)) {
            return Vec3.ZERO;
        }
        List<Celestials.Body> bodies = Celestials.bodies(level);
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (Celestials.Body body : bodies) {
            double pull = surfacePull(level, body);
            double radius = body.radius();
            if (!(pull > 0.0) || !(radius > 0.0)) {
                continue;
            }
            double reach = reach(body);
            Vec3 c = body.position();
            double dx = c.x - x;
            double dy = c.y - y;
            double dz = c.z - z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (!(d2 < reach * reach) || d2 < 1.0E-12) {
                continue;
            }
            double d = Math.sqrt(d2);
            double share = falloff(d, radius, reach);
            if (grace != null) {
                share *= grace.factor(body.id(), d);
            }
            if (!(share > 0.0)) {
                continue;
            }
            double k = pull * share / d;
            sx += dx * k;
            sy += dy * k;
            sz += dz * k;
        }
        if (sx * sx + sy * sy + sz * sz < NONE * NONE) {
            return Vec3.ZERO;
        }
        return new Vec3(sx, sy, sz);
    }

    // ------------------------------------------------------------------
    // the departure grace
    // ------------------------------------------------------------------

    /**
     * One traveller's grace after one departure: the planet it left doesn't pull it until the grace time has passed or
     * it is clear, and then comes back over the ramp. Not thread-safe: one traveller's tick (or the game thread for
     * ships) owns it.
     */
    public static final class Grace {
        private final ResourceLocation body;
        private final long departedAt;
        private double arrivalDistance = Double.NaN;
        private int ticks;
        private long lastTick = Long.MIN_VALUE;
        private int endedAt = -1;

        /**
         * {@code now}: the game time it is first looked at. A departure older than the grace and its ramp (a record
         * Camafy keeps until the traveller lands, while it came back to space some other way meanwhile) holds nothing.
         */
        public Grace(Celestials.Departure departure, long now) {
            this.body = departure.body();
            this.departedAt = departure.gameTime();
            if (now - departedAt > (long) graceTicks + rampTicks) {
                endedAt = 0;
                ticks = rampTicks;
            }
        }

        public boolean matches(Celestials.Departure departure) {
            return departure.body().equals(body) && departure.gameTime() == departedAt;
        }

        /** Counts one more tick in space (once per game tick however often it is called within one). */
        public void tick(long gameTime) {
            if (gameTime != lastTick) {
                lastTick = gameTime;
                ticks++;
            }
        }

        public ResourceLocation body() {
            return body;
        }

        /** How much of {@code id}'s pull reaches the traveller at {@code distance} from its centre: 0 to 1. */
        public double factor(ResourceLocation id, double distance) {
            if (!id.equals(body)) {
                return 1.0;
            }
            int hold = graceTicks;
            if (hold <= 0) {
                return 1.0;
            }
            if (Double.isNaN(arrivalDistance)) {
                arrivalDistance = distance;
            }
            if (endedAt < 0 && (ticks > hold || distance >= arrivalDistance * clearFactor)) {
                endedAt = ticks;
            }
            if (endedAt < 0) {
                return 0.0;
            }
            int ramp = rampTicks;
            return ramp <= 0 ? 1.0 : Math.min(1.0, (ticks - endedAt) / (double) ramp);
        }

        /** For readouts: "held 120 ticks", "ramping 40%", "over". */
        public String describe() {
            if (graceTicks <= 0) {
                return "off";
            }
            if (endedAt < 0) {
                return "held " + ticks + "/" + graceTicks + " ticks, arrived "
                    + (Double.isNaN(arrivalDistance) ? "?" : String.format(java.util.Locale.ROOT, "%.0f", arrivalDistance))
                    + " blocks from " + body;
            }
            double f = rampTicks <= 0 ? 1.0 : Math.min(1.0, (ticks - endedAt) / (double) rampTicks);
            return f >= 1.0 ? "over (" + body + ")" : String.format(java.util.Locale.ROOT, "ramping %.0f%% (%s)", f * 100.0, body);
        }
    }

    // ------------------------------------------------------------------
    // entities
    // ------------------------------------------------------------------

    /** An entity's pull this tick (the capability holds one). */
    public static final class EntityState {
        /** World vector, multiples of the entity's own vanilla gravity; zero when nothing pulls it. */
        Vec3 pull = Vec3.ZERO;
        long tick = Long.MIN_VALUE;
        @Nullable Grace grace;
        // the bodies' pull is the capability's field this tick (strong enough, not carried)
        boolean turning;
        // the departure last told to this player's client (server side)
        @Nullable Celestials.Departure sent;
        boolean told;

        public Vec3 pull() {
            return pull;
        }

        public @Nullable Grace grace() {
            return grace;
        }

        /** Whether the bodies' pull turns the frame now (a field to the capability), not only pushes. */
        public boolean turning() {
            return turning;
        }
    }

    /** Capability tick: works the pull out (both sides) and applies it where this side moves the entity. */
    public static void tickEntity(Entity entity, GravityCapabilityImpl comp, boolean foreignMotion) {
        EntityState state = comp.celestial;
        state.pull = Vec3.ZERO;
        Level level = entity.level();
        if (!Celestials.isSpace(level)) {
            state.grace = null;
            state.told = false;
            return;
        }
        if (!enabled()) {
            return;
        }
        // the grace counts from the departure whatever the entity does meanwhile (a rider who stands up after a
        // while has the time its ship had)
        Celestials.Departure departure = departureOf(entity);
        long now = level.getGameTime();
        if (departure == null) {
            state.grace = null;
        }
        else if (state.grace == null || !state.grace.matches(departure)) {
            state.grace = new Grace(departure, now);
        }
        // the client moves its own player: it hears of the departure from here
        if (entity instanceof net.minecraft.server.level.ServerPlayer player
            && (!state.told || !java.util.Objects.equals(departure, state.sent))) {
            state.told = true;
            state.sent = departure;
            net.camacraft.gravityunbound.network.GravityNetwork.sendNonLocal(
                new net.camacraft.gravityunbound.network.CelestialDeparturePacket(departure), player);
        }
        if (state.grace != null) {
            state.grace.tick(now);
        }
        if (excluded(entity) || (level.isClientSide() && !clientTold && GCUtil.isClientPlayer(entity))) {
            state.turning = false;
            return;
        }
        // a field of the world's or a ship's (plating, a core, a normalizer), or Surface Cling, holds it: theirs it is
        if (comp.isInField() && comp.getDominantFieldPriority() > PRIORITY + BLEND_RANGE) {
            state.turning = false;
            return;
        }
        Vec3 pull = pullAt(level, entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(), state.grace);
        state.pull = pull;
        state.tick = now;
        double felt = pull.length() / Celestials.spaceScale(level);
        state.turning = pull != Vec3.ZERO && !carriedByShip(entity) && felt >= (state.turning ? TURN_OFF : TURN_ON);
        if (state.turning) {
            // the capability turns the frame, as under a core, and pulls the living with its deficit pull along it
            if (fieldHere(entity)) {
                comp.applyGravityDirectionEffect(pull.normalize(), TURN, PRIORITY, false, pull.length(), false,
                    null, null, null, 1.0, null, true);
            }
            // things are pulled here, down the pull itself, once a tick (the capability's zero-g field force would
            // take the strength it had before this field reached it: a full g for the first tick); where Ad Astra makes
            // the world weightless the field restores their own gravity (AdAstraCompat answers Earth gravity for a
            // fielded entity, and their gravity constant is scaled by the field's strength), so that is their pull
            if (entity instanceof LivingEntity || foreignMotion || !movesHere(entity)
                || net.camacraft.gravityunbound.compat.AdAstraCompat.restoresGravityFor(entity)
                || !comp.tryClaimArtificialPull(now)) {
                return;
            }
        }
        // force only: never on top of a field (the bodies' own one fading out after a turn included)
        else if (pull == Vec3.ZERO || comp.isInField() || foreignMotion || !movesHere(entity)) {
            return;
        }
        Vec3 accel = pull.scale(entity instanceof LivingEntity ? BASE : GravityCapabilityImpl.vanillaGravity(entity));
        // projectiles move in world axes; everything else through the gravity frame (the identity in plain space)
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            entity.setDeltaMovement(entity.getDeltaMovement().add(accel));
        }
        else {
            GravityChangerAPI.setWorldVelocity(entity, GravityChangerAPI.getWorldVelocity(entity).add(accel));
        }
    }

    /**
     * The pull Camafy's gravity bridge reports for this entity (a world vector in multiples of its vanilla gravity),
     * or null when no body pulls it now: worked out by its capability this tick or the last.
     */
    public static @Nullable Vec3 pullOf(Entity entity) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null) {
            return null;
        }
        EntityState state = comp.celestial;
        Vec3 pull = state.pull;
        // while it turns the frame the pull is a field, and the field is what the bridge reports
        if (pull == Vec3.ZERO || state.turning || comp.isInField() || entity.level().getGameTime() - state.tick > 1
            || !Celestials.isSpace(entity.level())) {
            return null;
        }
        return pull;
    }

    /** Never pulled: passengers, creative fliers, spectators, swimmers, things that keep a gravity of their own here. */
    private static boolean excluded(Entity entity) {
        if (entity.isPassenger() || !weightlessHere(entity) || entity.isInWater() || entity.isInLava()) {
            return true;
        }
        return entity instanceof Player player && (player.isSpectator() || player.getAbilities().flying);
    }

    /**
     * Whether the entity has no gravity of its own in Genesis's space, so the bodies' pull is all it feels: Genesis flags
     * everything there no-gravity, unless the "Genesis x Ad Astra" add-on (in the owner's main pack) cancels that flag
     * and has Ad Astra make the world weightless instead. Checking the flag alone, nothing in that pack was ever pulled
     * or turned (only ships, which don't go through it): the owner's "celestial bodies are pulling with gravity, but are
     * not rotating entities towards them", 2026-10-30. {@code -Dgravityunbound.celestialAdAstraWeightless=false} goes
     * back to the flag alone for an A/B.
     */
    private static boolean weightlessHere(Entity entity) {
        if (entity.isNoGravity()) {
            return true;
        }
        return AD_ASTRA_WEIGHTLESS && net.camacraft.gravityunbound.compat.AdAstraCompat.zeroGravityWorld(entity.level());
    }

    private static final boolean AD_ASTRA_WEIGHTLESS =
        !"false".equalsIgnoreCase(System.getProperty("gravityunbound.celestialAdAstraWeightless"));

    /** Valkyrien Skies carries it with a ship it stands on (or stood on a moment ago): it keeps its frame. */
    private static boolean carriedByShip(Entity entity) {
        org.valkyrienskies.mod.common.util.EntityDraggingInformation drag =
            net.camacraft.camafy.compat.vs.impl.VsTyped.dragInfo(entity);
        return drag != null && drag.isEntityBeingDraggedByAShip();
    }

    /**
     * Where a field works its effect out, as a core's does: the server for everything, a client for its own player and
     * what it predicts (non-living things); a client's copy of another living entity takes its frame from the server.
     */
    private static boolean fieldHere(Entity entity) {
        if (!entity.level().isClientSide()) {
            return true;
        }
        return !(entity instanceof LivingEntity) || entity.isControlledByLocalInstance() || GCUtil.isClientPlayer(entity);
    }

    /** The side that moves it: the server for mobs and things, the client for its own player and its predictions. */
    private static boolean movesHere(Entity entity) {
        if (entity.level().isClientSide()) {
            return !(entity instanceof LivingEntity) || entity.isControlledByLocalInstance();
        }
        return !(entity instanceof Player);
    }

    private static @Nullable Celestials.Departure departureOf(Entity entity) {
        if (entity.level().isClientSide()) {
            return GCUtil.isClientPlayer(entity) ? clientDeparture : null;
        }
        return Celestials.departure(entity);
    }
}
