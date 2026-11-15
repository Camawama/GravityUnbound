package net.camacraft.gravityunbound.util;

import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the server still checks of a move it otherwise takes on the client's word (a player near a gravity source, on
 * plating, in Surface Cling boots: the server cannot replay a turned capsule's collisions, so the reported position is
 * accepted). Without any check that word let a modified client walk through walls wherever a field reached, and
 * anywhere at all in Surface Cling boots.
 *
 * <p>Two tests that need no frame, on the reported feet point alone, against the world's own blocks:</p>
 * <ul>
 * <li><b>Not deep in a block.</b> An honest feet point lies on a face or in the open. One with solid collision a
 * quarter block away on all six sides is inside a block: refused, unless the player was already inside (a block
 * placed on them, sand; they may move out).</li>
 * <li><b>Not through a block.</b> The straight line from the last position to the new one must be free of block
 * collision once it is lifted by a step's height in at least one of the six directions. Whatever the player's down is,
 * the line lifted along their own up clears steps, slabs and a cube's edge; a wall, a floor, a door or a pane is
 * crossed by every one of the six.</li>
 * </ul>
 *
 * <p><b>Beside a Valkyrien Skies ship</b> (2.0.0.9) the world's blocks are judged as anywhere else: they do not move,
 * and an honest client collided with them itself. The ship's own blocks are judged in the ship's space, with a
 * tolerance for what the ship moved since the client last saw it (the ship's speed at the point, or what its pose
 * really moved in the last tick, over the player's latency and three ticks more): the point must be deep in the ship
 * at this tick's pose and at the last one's with the probes pushed out by the tolerance, or the line must cross ship
 * blocks that are thicker than the tolerance along it under both poses and all six lifts. A ship that moves more than
 * {@link #MAX_SHIP_TOLERANCE} in that time is not judged, and neither is the line through the world's blocks beside it
 * (the server's start of the line was carried by the ship; the deep test still is).</p>
 *
 * <p><b>Not too far in one packet</b> (2.0.0.9, {@code -Dgravityunbound.clientMoveSpeed=false}): {@link #PER_PACKET}
 * blocks a packet (twice what the game itself lets pass), more for the velocity the server knows of the body and for
 * the ship that carries it. Passengers, gliders and bodies another mod has claimed are not judged.</p>
 *
 * <p>Left alone: unloaded chunks, and hovering (a server of this pack runs with {@code allow-flight}, so the game
 * judges no player's height; jetpacks, weightless worlds and grapples make any rule here a guess).
 * {@code -Dgravityunbound.clientMoveSanity=false} switches the block tests off,
 * {@code -Dgravityunbound.clientMoveShips=false} the ship's.</p>
 */
public final class ClientMoveSanity {
    private static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("gravityunbound.clientMoveSanity"));
    private static final boolean SHIPS = !"false".equalsIgnoreCase(System.getProperty("gravityunbound.clientMoveShips"));
    private static final boolean SPEED = !"false".equalsIgnoreCase(System.getProperty("gravityunbound.clientMoveSpeed"));
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double PROBE = 0.25;
    /** A line longer than this is the speed rule's to refuse, not walked block by block. */
    private static final double MAX_LINE = 48.0;
    /** Blocks one packet may move a body nothing carries (the game's own bound is 10). */
    private static final double PER_PACKET = 20.0;
    /** A ship's slack with nothing moving: a pane passes, a door or a block does not. */
    private static final double SHIP_SLACK = 0.15;
    /** A ship that moved further than this since the client can have seen it is not judged. */
    private static final double MAX_SHIP_TOLERANCE = 2.0;
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final AtomicLong LAST_LOG = new AtomicLong();

    private ClientMoveSanity() {
    }

    /** Moves refused since the server started (for tests). */
    public static long refused() {
        return REFUSED.get();
    }

    /** Whether a client-authoritative move from {@code from} to {@code to} goes into or through block collision. */
    public static boolean refuses(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
        return refuses(level, player, from, to, 1);
    }

    /**
     * Whether a client-authoritative move from {@code from} to {@code to}, the sum of {@code packets} move packets,
     * goes into or through blocks (the world's or a ship's) or further than anything carries the player.
     */
    public static boolean refuses(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to, int packets) {
        if (from == null || to == null || player.noPhysics || player.isSpectator()) return false;
        if (!Double.isFinite(to.x) || !Double.isFinite(to.y) || !Double.isFinite(to.z)) return ON || SPEED;
        String why = null;
        try {
            List<ShipRef> ships = Ships.available() ? Ships.intersecting(level, new AABB(from, to).inflate(4.0)) : List.of();
            int wait = Mth.clamp((player.latency + 49) / 50, 0, 20) + 3;
            double[] tolerance = new double[ships.size()];
            double carried = 0.0;
            boolean fastShip = false;
            for (int i = 0; i < tolerance.length; i++) {
                tolerance[i] = SHIP_SLACK + perTick(ships.get(i), to) * wait;
                carried = Math.max(carried, tolerance[i]);
                if (!(tolerance[i] <= MAX_SHIP_TOLERANCE)) fastShip = true;
            }
            if (SPEED && tooFar(player, from, to, packets, carried)) {
                why = "further than one packet goes";
            } else if (ON) {
                if (inWorldBlocks(level, player, from, to, fastShip, carried)) {
                    why = "through blocks";
                } else if (SHIPS) {
                    for (int i = 0; i < tolerance.length && why == null; i++) {
                        if (tolerance[i] <= MAX_SHIP_TOLERANCE && inShipBlocks(level, player, ships.get(i), from, to, tolerance[i])) {
                            why = "through a ship's blocks";
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        if (why == null) return false;
        REFUSED.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = LAST_LOG.get();
        if (now - last > 5000 && LAST_LOG.compareAndSet(last, now)) {
            org.slf4j.LoggerFactory.getLogger("GravityUnbound").warn(
                "[GravityUnbound] refused a move of {} {}: {} -> {} ({} refused so far; -Dgravityunbound.clientMoveSanity=false, clientMoveShips, clientMoveSpeed)",
                player.getGameProfile().getName(), why, from, to, REFUSED.get());
        }
        return true;
    }

    // ---- how far ---------------------------------------------------------------------------------------------

    private static boolean tooFar(ServerPlayer player, Vec3 from, Vec3 to, int packets, double carried) {
        if (player.isPassenger() || player.isFallFlying() || player.isChangingDimension() || player.isSleeping()) return false;
        if (PhysicsOwnership.claims(player) != 0) return false;
        long moved = PhysicsOwnership.lastDiscontinuity(player);
        if (moved != Long.MIN_VALUE && player.level().getGameTime() - moved < 40) return false;
        double known = player.getDeltaMovement().length();
        double allowed = Math.max(1, Math.min(packets, 5)) * (PER_PACKET + 4.0 * known) + 2.0 * carried;
        return from.distanceTo(to) > allowed;
    }

    /** Blocks a tick the ship's material moves at {@code at}: by its velocity, or by what its pose really did. */
    private static double perTick(ShipRef ship, Vec3 at) {
        double moved = 0.0;
        Vec3 velocity = ship.velocityAt(at);
        if (velocity == null) return Double.POSITIVE_INFINITY;
        moved = velocity.length() / 20.0;
        Vec3 before = ship.currentToPrevTick(at);
        if (before == null) return Double.POSITIVE_INFINITY;
        moved = Math.max(moved, before.distanceTo(at));
        return Double.isFinite(moved) ? moved : Double.POSITIVE_INFINITY;
    }

    // ---- the world's blocks ----------------------------------------------------------------------------------

    private static boolean inWorldBlocks(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to, boolean fastShip, double carried) {
        if (!loaded(level, to) || !loaded(level, from)) return false;
        boolean wasDeep = deep(level, from, PROBE);
        if (deep(level, to, PROBE)) return !wasDeep;
        if (wasDeep || fastShip) return false;
        double length = from.distanceTo(to);
        if (length < 1.0e-3 || length > MAX_LINE) return false;
        double lift = Math.max(0.6, player.maxUpStep() + 0.1) + (carried > SHIP_SLACK ? carried : 0.0);
        return crossesAllLifted(level, from, to, lift, 0.0);
    }

    // ---- a ship's blocks -------------------------------------------------------------------------------------

    private static boolean inShipBlocks(ServerLevel level, ServerPlayer player, ShipRef ship, Vec3 from, Vec3 to, double tolerance) {
        double scale = ship.scale();
        if (!(scale > 1.0e-4) || !Double.isFinite(scale)) return false;
        Vec3 toNow = ship.toShip(to, ShipRef.Pose.TICK), toThen = ship.toShip(to, ShipRef.Pose.PREV_TICK);
        Vec3 fromNow = ship.toShip(from, ShipRef.Pose.TICK), fromThen = ship.toShip(from, ShipRef.Pose.PREV_TICK);
        if (toNow == null || toThen == null || fromNow == null || fromThen == null) return false;
        if (!loaded(level, toNow) || !loaded(level, toThen) || !loaded(level, fromNow) || !loaded(level, fromThen)) return false;
        double slack = tolerance / scale;
        double probe = PROBE / scale;
        // already inside (a block placed on them, a ship assembled round them): they may move
        if (deep(level, fromNow, probe) || deep(level, fromThen, probe)) return false;
        if (deep(level, toNow, probe + slack) && deep(level, toThen, probe + slack)) return true;
        double length = fromNow.distanceTo(toNow);
        if (length <= slack || length > MAX_LINE) return false;
        double lift = Math.max(0.6, player.maxUpStep() + 0.1) / scale + slack;
        return crossesAllLifted(level, fromNow, toNow, lift, slack) && crossesAllLifted(level, fromThen, toThen, lift, slack);
    }

    // ---- blocks, read where they are loaded and nowhere else ------------------------------------------------

    private static boolean loaded(ServerLevel level, Vec3 p) {
        return level.getChunkSource().getChunkNow(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4) != null;
    }

    /** The collision shape at {@code pos}, or null where the chunk is not there or nothing collides. */
    private static VoxelShape shape(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return null;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        BlockState state = chunk.getBlockState(pos);
        if (state.isAir()) return null;
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() ? null : shape;
    }

    /** Whether block collision holds the point (the block's own shape, and a tall one's from the block below). */
    private static boolean solid(ServerLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        for (int down = 0; down <= 1; down++) {
            BlockPos at = down == 0 ? pos : pos.below();
            VoxelShape shape = shape(level, at);
            if (shape == null) continue;
            double lx = x - at.getX(), ly = y - at.getY(), lz = z - at.getZ();
            for (AABB box : shape.toAabbs()) {
                if (lx >= box.minX - 0.01 && lx <= box.maxX + 0.01 && ly >= box.minY - 0.01 && ly <= box.maxY + 0.01
                        && lz >= box.minZ - 0.01 && lz <= box.maxZ + 0.01) return true;
            }
        }
        return false;
    }

    /** Solid collision {@code probe} away on all six sides. */
    private static boolean deep(ServerLevel level, Vec3 p, double probe) {
        for (Direction d : DIRECTIONS) {
            if (!solid(level, p.x + d.getStepX() * probe, p.y + d.getStepY() * probe, p.z + d.getStepZ() * probe)) return false;
        }
        return true;
    }

    /**
     * Whether the line, lifted by {@code lift} in each of the six directions, crosses block collision every time, and
     * (with {@code thicker} above 0) more than that much of it each time.
     */
    private static boolean crossesAllLifted(ServerLevel level, Vec3 from, Vec3 to, double lift, double thicker) {
        for (Direction d : DIRECTIONS) {
            Vec3 o = new Vec3(d.getStepX() * lift, d.getStepY() * lift, d.getStepZ() * lift);
            Vec3 a = from.add(o), b = to.add(o);
            Vec3 in = firstHit(level, a, b);
            if (in == null) return false;
            if (thicker > 0.0) {
                Vec3 out = firstHit(level, b, a);
                if (out == null || in.distanceTo(out) <= thicker) return false;
            }
        }
        return true;
    }

    /** Where the line first meets block collision, or null. */
    private static Vec3 firstHit(ServerLevel level, Vec3 from, Vec3 to) {
        return BlockGetter.traverseBlocks(from, to, level, (l, pos) -> {
            VoxelShape shape = shape(l, pos);
            if (shape == null) return null;
            BlockHitResult hit = shape.clip(from, to, pos);
            return hit == null ? null : hit.getLocation();
        }, l -> null);
    }
}
