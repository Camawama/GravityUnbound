package net.camacraft.gravityunbound.util;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.Ship;

import net.camacraft.camafy.compat.vs.impl.VsTyped;
import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Wakes the WORLD liquid a field carried by a Valkyrien Skies ship may have
 * moved: one per ship-mounted field block (core or plate), asked every
 * {@code SHIP_FLUID_WAKE_PERIOD} ticks. Still water has no scheduled ticks of
 * its own, so a lake under a passing ship's field only notices the field when
 * it is woken (the cross-grid lookup, {@code GravityFieldLookup.fluidDownAt},
 * then bends what was woken).
 *
 * <p>This used to wake EVERY liquid block in the box the field covered and the
 * box it covered at its last wake-up, joined into one box, every period while
 * the ship moved. Two of the owner's reports (2026-10-12) came from that:
 * <ul>
 * <li><b>"after I tried pasting in that schematic, the world froze a few moments
 * later"</b> (a VMod paste of a gravity-core ship and its slime ships): the
 * server's watchdog caught two ticks of 40 s and 80 s inside
 * {@code GravityCoreBlockEntity.wakeWorldFluidsUnderShip}. A pasted ship moves
 * from where it was made to where it is put, so its last box and its new one
 * were far apart, and the box joining them was walked chunk column by chunk
 * column. The two boxes are now woken apart, and a box no field could cover is
 * never walked at all.</li>
 * <li><b>The lag profile</b> with a moving core ship over the sea (radius 32,
 * 12 TPS, 1.1-1.25 s ticks): 100k+ fluid ticks per wake-up, nearly all of them
 * on sea water that could not move, each asking the field lookup about every
 * neighbour. Now only liquid that can move is woken (a block that is not a full
 * source, or that has a neighbour that is not a full source of the same liquid:
 * the surface and the shore; a source surrounded by the same liquid stays as it
 * is under any gravity, and a neighbour that does change schedules it in the
 * normal way), only where THIS field's down changed since its last wake-up
 * (a ship that only moves re-points a few cells, not the whole sea), and a
 * budget at a time: the scan and the wake-ups are carried across ticks
 * ({@code shipFluidWakeBudget}).</li>
 * </ul>
 * The settle wake-ups after a field stops changing ({@link ShipFluidWake}) and
 * a change of the field's settings wake every movable cell, as before.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = GravityAPI.MODID)
public final class ShipFluidWaker {

    /** No field's box is wider than this (the largest range doubled, and some): a wider box is an unset transform. */
    private static final double MAX_BOX = 1100.0;
    /** Section scans a level may make in one tick: a section of mixed blocks reads every cell once. */
    private static final int SECTIONS_PER_TICK = 24;

    private final ShipFluidWake due = new ShipFluidWake();
    private @Nullable AABB lastBox;
    private @Nullable Matrix4d lastWorldToShip;
    private @Nullable Matrix4d lastShipToWorld;
    private int lastSettings;

    /**
     * Called by a ship-mounted field block on its wake-up ticks, server side.
     *
     * @param gridBox  the field's box in the ship's own grid
     * @param settings everything about the field besides its pose (range, targets, direction...)
     * @param source   the field block, asked for its down at a grid cell
     */
    public void wake(Level world, Ship ship, AABB gridBox, int settings, GravityFieldLookup.Source source) {
        if (!(world instanceof ServerLevel level)) {
            return;
        }
        AABB worldBox = VsTyped.shipBoxToWorld(ship, gridBox);
        if (!due.due(ship, worldBox, settings)) {
            return;
        }
        Matrix4d worldToShip = new Matrix4d(ship.getTransform().getWorldToShip());
        Matrix4d shipToWorld = new Matrix4d(ship.getTransform().getShipToWorld());
        AABB oldBox = lastBox;
        Matrix4d oldWorldToShip = lastWorldToShip;
        Matrix4d oldShipToWorld = lastShipToWorld;
        boolean sameSettings = lastSettings == settings;
        lastBox = worldBox;
        lastWorldToShip = worldToShip;
        lastShipToWorld = shipToWorld;
        lastSettings = settings;

        // Only where the field's down changed, when the pose is what changed. A settle
        // wake-up (same pose as last time) or new settings wake every movable cell.
        boolean moved = oldWorldToShip != null && !oldWorldToShip.equals(worldToShip, 1.0E-9);
        Changed changed = moved && sameSettings
            ? cell -> downAt(source, oldWorldToShip, oldShipToWorld, cell) != downAt(source, worldToShip, shipToWorld, cell)
            : null;

        submit(level, worldBox, changed);
        if (oldBox != null && !oldBox.equals(worldBox)) {
            if (oldBox.intersects(worldBox)) {
                // close: the strip it left is in the union, and the union is small
                submit(level, oldBox.minmax(worldBox), changed);
            }
            else {
                // apart (a teleport, a paste): each box on its own, never the span between them
                submit(level, oldBox, changed);
            }
        }
    }

    /** Whether a world cell's down differs between two poses of the field. */
    private interface Changed {
        boolean at(BlockPos worldCell);
    }

    /** This one field's down at a world cell with the ship at the given pose, snapped to a world cardinal; null outside. */
    @Nullable
    private static Direction downAt(GravityFieldLookup.Source source, Matrix4dc worldToShip, Matrix4dc shipToWorld,
                                    BlockPos cell) {
        Vector3d at = worldToShip.transformPosition(new Vector3d(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5));
        BlockPos grid = BlockPos.containing(at.x, at.y, at.z);
        BlockPos center = source.sourcePos();
        int distance = Math.max(Math.abs(grid.getX() - center.getX()),
            Math.max(Math.abs(grid.getY() - center.getY()), Math.abs(grid.getZ() - center.getZ())));
        if (distance > source.sourceMaxRange()) {
            return null;
        }
        Direction down = source.downAt(grid, GravityFieldLookup.Kind.FLUID);
        if (down == null) {
            return null;
        }
        Vector3d dir = shipToWorld.transformDirection(new Vector3d(down.getStepX(), down.getStepY(), down.getStepZ()));
        return dir.lengthSquared() < 1.0E-12 ? null : Direction.getNearest(dir.x, dir.y, dir.z);
    }

    // ---- the per-level queue, worked a budget at a time ------------------------------------------------------

    private static final Map<Level, ArrayDeque<Job>> JOBS = new WeakHashMap<>();

    private static void submit(ServerLevel level, AABB box, @Nullable Changed changed) {
        if (box.getXsize() > MAX_BOX || box.getYsize() > MAX_BOX || box.getZsize() > MAX_BOX) {
            return;
        }
        int minY = Math.max(level.getMinBuildHeight(), (int) Math.floor(box.minY));
        int maxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.floor(box.maxY));
        if (maxY < minY) {
            return;
        }
        ArrayDeque<Job> jobs = JOBS.computeIfAbsent(level, l -> new ArrayDeque<>());
        // a backlog this deep is behind for good: the newest requests stand for the old ones
        while (jobs.size() >= 64) {
            jobs.pollFirst();
        }
        jobs.addLast(new Job((int) Math.floor(box.minX), minY, (int) Math.floor(box.minZ),
            (int) Math.floor(box.maxX), maxY, (int) Math.floor(box.maxZ), changed));
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        ArrayDeque<Job> jobs = JOBS.get(level);
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        int[] budget = {Math.max(1, GravityConfig.shipFluidWakeBudget.get()), SECTIONS_PER_TICK};
        while (!jobs.isEmpty() && budget[0] > 0 && budget[1] > 0) {
            Job job = jobs.peekFirst();
            boolean done;
            try {
                done = job.work(level, budget);
            }
            catch (RuntimeException e) {
                done = true;   // the field block went away under it: drop the job
            }
            if (done) {
                jobs.pollFirst();
            }
        }
    }

    /** One box to wake, walked a chunk section at a time; the cursor carries over between ticks. */
    private static final class Job {
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final @Nullable Changed changed;
        int cx, cz, sy;

        Job(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, @Nullable Changed changed) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.changed = changed;
            this.cx = minX >> 4;
            this.cz = minZ >> 4;
            this.sy = minY >> 4;
        }

        /** Works sections until the budget is spent; true once the whole box is done. */
        boolean work(ServerLevel level, int[] budget) {
            while (budget[0] > 0 && budget[1] > 0) {
                if (cx > maxX >> 4) {
                    return true;
                }
                section(level, budget);
                if (++sy > maxY >> 4) {
                    sy = minY >> 4;
                    if (++cz > maxZ >> 4) {
                        cz = minZ >> 4;
                        cx++;
                    }
                }
            }
            return cx > maxX >> 4;
        }

        private void section(ServerLevel level, int[] budget) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                return;   // never loads: a chunk nobody has loaded has no liquid anybody sees
            }
            int index = chunk.getSectionIndexFromSectionY(sy);
            if (index < 0 || index >= chunk.getSections().length) {
                return;
            }
            LevelChunkSection section = chunk.getSection(index);
            if (section.hasOnlyAir() || !section.maybeHas(state -> !state.getFluidState().isEmpty())) {
                return;
            }
            budget[1]--;
            int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) + 15);
            int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
            int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) + 15);
            // a section of one full source all through (open sea) can only move at its faces
            net.minecraft.world.level.block.state.BlockState first = section.getBlockState(0, 0, 0);
            boolean uniform = first.getFluidState().isSource()
                && !section.maybeHas(state -> state != first);
            BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        if (uniform && (x & 15) != 0 && (x & 15) != 15 && (y & 15) != 0 && (y & 15) != 15
                            && (z & 15) != 0 && (z & 15) != 15) {
                            continue;
                        }
                        FluidState fluid = section.getFluidState(x & 15, y & 15, z & 15);
                        if (fluid.isEmpty() || !movable(level, section, fluid, x, y, z)) {
                            continue;
                        }
                        cell.set(x, y, z);
                        if (changed != null && !changed.at(cell)) {
                            continue;
                        }
                        level.scheduleTick(cell.immutable(), fluid.getType(), fluid.getType().getTickDelay(level));
                        budget[0]--;
                    }
                }
            }
        }

        /** Not a full source, or beside something that isn't a full source of the same liquid. */
        private static boolean movable(ServerLevel level, LevelChunkSection section, FluidState fluid, int x, int y, int z) {
            if (!fluid.isSource()) {
                return true;
            }
            for (Direction d : Direction.values()) {
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                FluidState next;
                if (nx >> 4 == x >> 4 && ny >> 4 == y >> 4 && nz >> 4 == z >> 4) {
                    next = section.getFluidState(nx & 15, ny & 15, nz & 15);
                }
                else {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(nx >> 4, nz >> 4);
                    if (chunk == null || ny < level.getMinBuildHeight() || ny >= level.getMaxBuildHeight()) {
                        continue;   // nothing loaded there to flow into
                    }
                    next = chunk.getFluidState(nx, ny, nz);
                }
                if (!next.isSource() || !next.getType().isSame(fluid.getType())) {
                    return true;
                }
            }
            return false;
        }
    }
}
