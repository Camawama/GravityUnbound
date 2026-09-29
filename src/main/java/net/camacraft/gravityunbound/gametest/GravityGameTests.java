package net.camacraft.gravityunbound.gametest;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.plating.GravityPlatingBlock;
import net.camacraft.camafy.util.math.Rotation24;
import net.camacraft.gravityunbound.sticky.StickyRailBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Headless server-side checks ({@code gradlew runGameTestServer}) for the
 * physics that cannot be judged by reading: a boat on the side face of a
 * water cube around a gravity core, and a minecart on a wall-mounted sticky
 * rail. Each test logs its subject's state every 20 ticks under the
 * {@code [GravityUnbound/test]} prefix so a failure explains itself.
 */
@GameTestHolder("gravityunbound")
@PrefixGameTestTemplate(false)
public class GravityGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("GravityUnbound/test");
    private static final String EMPTY = "empty20";

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }

    /**
     * 7x7x7 water cube, core at its center, a boat dropped onto the +X face.
     * It must stay on that face, upright in the face's frame, for 6 seconds.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void boatFloatsOnSideFace(GameTestHelper helper) {
        BlockPos core = new BlockPos(9, 5, 9);
        helper.setBlock(core, GravityBlocks.GRAVITY_CORE.get().defaultBlockState());
        for (int x = 6; x <= 12; x++) {
            for (int y = 2; y <= 8; y++) {
                for (int z = 6; z <= 12; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!p.equals(core)) {
                        helper.setBlock(p, Blocks.WATER.defaultBlockState());
                    }
                }
            }
        }
        // +X face: the water's outer boundary is x = 13.0 (relative)
        Vec3 spawn = new Vec3(13.0, 5.5, 9.5);
        Boat boat = helper.spawn(EntityType.BOAT, spawn);
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));

        for (int t = 20; t <= 240; t += 20) {
            final int tick = t;
            helper.runAfterDelay(t, () -> {
                GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(boat);
                Vec3 rel = boat.position().subtract(origin);
                int water = 0;
                for (int x = 6; x <= 12; x++) {
                    for (int y = 2; y <= 8; y++) {
                        for (int z = 6; z <= 12; z++) {
                            if (helper.getBlockState(new BlockPos(x, y, z)).getFluidState().isSource()) {
                                water++;
                            }
                        }
                    }
                }
                LOGGER.info("[GravityUnbound/test] boat t={} rel={} dm={} up={} visualDefault={} settled={} water={}",
                    tick, fmt(rel), fmt(boat.getDeltaMovement()),
                    comp == null ? "null" : fmt(comp.getUpVector()),
                    comp == null ? "?" : comp.isVisuallyDefault(),
                    comp == null ? "?" : comp.getSettledCardinal(),
                    water);
            });
        }

        helper.runAfterDelay(250, () -> {
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(boat);
            Vec3 rel = boat.position().subtract(origin);
            if (comp == null) {
                helper.fail("boat has no gravity component");
                return;
            }
            Vec3 up = comp.getUpVector();
            if (up.x < 0.9) {
                helper.fail("boat frame did not turn to the +X face: up=" + fmt(up));
                return;
            }
            if (rel.x < 12.2 || rel.x > 13.6) {
                helper.fail("boat is not floating on the +X face: rel=" + fmt(rel));
                return;
            }
            if (Math.abs(rel.y - spawn.y) > 1.5 || Math.abs(rel.z - spawn.z) > 1.5) {
                helper.fail("boat drifted along the face: rel=" + fmt(rel));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A wall of stone, a sticky rail track on its west face running along Z,
     * a minecart placed on it. The cart's visual frame must pin to the rail
     * (up = west) and the cart must stay on the track.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300)
    public static void minecartPinsToWallRail(GameTestHelper helper) {
        // support wall at x = 6, the rails hang on its west face (x = 5)
        for (int y = 2; y <= 6; y++) {
            for (int z = 6; z <= 12; z++) {
                helper.setBlock(new BlockPos(6, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        // spin whose local north/south axis runs along world Z on an EAST-bottom rail
        int spin = 0;
        for (int s = 0; s < 4; s++) {
            Direction north = Rotation24.localToWorld(Direction.NORTH, Direction.EAST, s);
            if (north.getAxis() == Direction.Axis.Z) {
                spin = s;
                break;
            }
        }
        BlockState rail = GravityBlocks.STICKY_RAIL.get().defaultBlockState()
            .setValue(StickyRailBlock.BOTTOM, Direction.EAST)
            .setValue(StickyRailBlock.SPIN, spin)
            .setValue(StickyRailBlock.SHAPE, RailShape.NORTH_SOUTH);
        for (int z = 7; z <= 11; z++) {
            helper.setBlock(new BlockPos(5, 4, z), rail);
        }
        LOGGER.info("[GravityUnbound/test] rail spin={} state={}", spin, helper.getBlockState(new BlockPos(5, 4, 9)));

        // vanilla MinecartItem drops a cart at the rail block's center, 1/16 up
        Vec3 spawn = new Vec3(5.5, 4.0625, 9.5);
        Minecart cart = helper.spawn(EntityType.MINECART, spawn);
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));

        for (int t = 5; t <= 200; t += (t < 40 ? 5 : 20)) {
            final int tick = t;
            helper.runAfterDelay(t, () -> {
                GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(cart);
                Vec3 rel = cart.position().subtract(origin);
                LOGGER.info("[GravityUnbound/test] cart t={} rel={} dm={} up={} visualDefault={} settled={} dir={} onGround={}",
                    tick, fmt(rel), fmt(cart.getDeltaMovement()),
                    comp == null ? "null" : fmt(comp.getUpVector()),
                    comp == null ? "?" : comp.isVisuallyDefault(),
                    comp == null ? "?" : comp.getSettledCardinal(),
                    comp == null ? "?" : comp.getCurrGravityDirection(),
                    cart.onGround());
            });
        }

        helper.runAfterDelay(210, () -> {
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(cart);
            Vec3 rel = cart.position().subtract(origin);
            if (comp == null) {
                helper.fail("cart has no gravity component");
                return;
            }
            Vec3 up = comp.getUpVector();
            if (up.x > -0.9) {
                helper.fail("cart frame is not pinned to the wall rail: up=" + fmt(up) + " rel=" + fmt(rel));
                return;
            }
            if (Math.abs(rel.x - 5.5) > 0.6 || Math.abs(rel.y - 4.0) > 1.0 || rel.z < 6.5 || rel.z > 12.5) {
                helper.fail("cart left the wall track: rel=" + fmt(rel));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A 3x3x3 stone cube around a core, a chicken dropped beside its +X face.
     * The chicken must land on that face and STAND there: frame up = +X,
     * grounded (vanilla onGround, which drives the wing flap), no flapping.
     * Reproduces the "chickens flap in place as if falling" report on the
     * user's cube ship, minus the ship.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void chickenStandsOnSideFace(GameTestHelper helper) {
        buildCoreCube(helper);
        Vec3 spawn = new Vec3(11.6, 5.2, 9.5);
        net.minecraft.world.entity.animal.Chicken chicken = helper.spawn(EntityType.CHICKEN, spawn);
        chicken.setNoAi(false);
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));

        for (int t = 1; t <= 240; t += (t < 14 ? 1 : (t < 40 ? 5 : 20))) {
            final int tick = t;
            helper.runAfterDelay(t, () -> logChicken("side", tick, chicken, origin));
        }

        helper.runAfterDelay(250, () -> {
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(chicken);
            Vec3 rel = chicken.position().subtract(origin);
            if (comp == null) {
                helper.fail("chicken has no gravity component");
                return;
            }
            // the chicken wanders (it planet-walks around the cube's faces on its
            // own), so: standing on SOME face of the cube, frame on that face
            Vec3 up = comp.getUpVector();
            double dominant = Math.max(Math.abs(up.x), Math.max(Math.abs(up.y), Math.abs(up.z)));
            if (dominant < 0.9) {
                helper.fail("chicken frame is not on a cube face: up=" + fmt(up) + " rel=" + fmt(rel));
                return;
            }
            if (rel.subtract(9.5, 5.5, 9.5).length() > 4.0) {
                helper.fail("chicken left the cube: rel=" + fmt(rel));
                return;
            }
            if (!chicken.onGround()) {
                helper.fail("chicken is not onGround on the face (flapping): rel=" + fmt(rel) + " flapSpeed=" + chicken.flapSpeed);
                return;
            }
            if (chicken.flapSpeed > 0.3F) {
                helper.fail("chicken keeps flapping on the face: flapSpeed=" + chicken.flapSpeed);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Same cube, chicken dropped onto the TOP face off-center (where the
     * radial pull tilts ~20 degrees while airborne). It must settle to plain
     * down gravity and stand still, not alternate between the tilted airborne
     * frame and the cardinal grounded one.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void chickenStandsOnTopFace(GameTestHelper helper) {
        buildCoreCube(helper);
        Vec3 spawn = new Vec3(10.3, 7.6, 10.4);
        net.minecraft.world.entity.animal.Chicken chicken = helper.spawn(EntityType.CHICKEN, spawn);
        chicken.setNoAi(false);
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));

        for (int t = 5; t <= 240; t += (t < 40 ? 5 : 20)) {
            final int tick = t;
            helper.runAfterDelay(t, () -> logChicken("top", tick, chicken, origin));
        }

        helper.runAfterDelay(250, () -> {
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(chicken);
            Vec3 rel = chicken.position().subtract(origin);
            if (comp == null) {
                helper.fail("chicken has no gravity component");
                return;
            }
            Vec3 up = comp.getUpVector();
            if (up.y < 0.95) {
                helper.fail("chicken frame did not settle upright on the top face: up=" + fmt(up) + " rel=" + fmt(rel));
                return;
            }
            if (rel.y < 6.9 || rel.y > 7.3) {
                helper.fail("chicken is not standing on the top face: rel=" + fmt(rel));
                return;
            }
            if (!chicken.onGround() || chicken.flapSpeed > 0.3F) {
                helper.fail("chicken flaps on the top face: onGround=" + chicken.onGround() + " flapSpeed=" + chicken.flapSpeed);
                return;
            }
            helper.succeed();
        });
    }

    /** Stone floor, a 3x3x3 stone cube at (8..10, 4..6, 8..10) with a gravity core at its center. */
    /**
     * PLATING ON A WALL, a boat resting on the plated face, and then the
     * field is taken away - the release the "boat driven out of the field
     * ends up inside the wall" report is about. Its position is its feet
     * point, which lies ON the face, so when the frame unwinds back to plain
     * gravity the upright box straddles the wall's plane; vanilla never
     * pushes an embedded box out. The boat must come out of the transition
     * clear of the blocks.
     *
     * Valkyrien Skies cancels every {@code move()} in the game-test world
     * (its box overlaps a ship it does not consider fully loaded - see
     * {@code debugShipFreeze}), so the boat cannot DRIVE down the face here.
     * It is parked on the face by hand while the field turns its frame; the
     * release itself, and the clearance, run entirely in the capability
     * tick and are unaffected.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void boatLeavingWallFieldIsNotLeftInTheWall(GameTestHelper helper) {
        // two-thick stone wall at x = 10,11; plating on its west face at
        // x = 9 pulling EAST
        for (int y = 6; y <= 15; y++) {
            for (int z = 5; z <= 14; z++) {
                helper.setBlock(new BlockPos(10, y, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(11, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        BlockState plating = GravityBlocks.GRAVITY_PLATING.get().defaultBlockState()
            .setValue(GravityPlatingBlock.directionToProperty(Direction.EAST), true);
        for (int y = 8; y <= 15; y++) {
            for (int z = 5; z <= 14; z++) {
                helper.setBlock(new BlockPos(9, y, z), plating);
            }
        }
        // a floor, so the released boat lands instead of falling out of the
        // structure. An embedded one lands too - the x assertion below is
        // what tells them apart.
        for (int x = 2; x <= 12; x++) {
            for (int z = 4; z <= 15; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
            }
        }

        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));
        Vec3 rest = new Vec3(9.9, 12.0, 9.5);
        Boat boat = helper.spawn(EntityType.BOAT, rest);

        // park it on the face until the field owns its frame
        for (int t = 1; t <= 60; t++) {
            helper.runAfterDelay(t, () -> boat.setPos(origin.add(rest)));
        }

        helper.runAfterDelay(61, () -> {
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(boat);
            if (comp == null || comp.getUpVector().x > -0.9) {
                helper.fail("the plating never turned the boat onto the face: up="
                    + (comp == null ? "null" : fmt(comp.getUpVector())));
                return;
            }
            // take the field away: this is "drove out the bottom of it"
            for (int y = 8; y <= 15; y++) {
                for (int z = 5; z <= 14; z++) {
                    helper.setBlock(new BlockPos(9, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        });

        for (int t = 20; t <= 180; t += 10) {
            final int tick = t;
            helper.runAfterDelay(t, () -> {
                GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(boat);
                Vec3 rel = boat.position().subtract(origin);
                LOGGER.info("[GravityUnbound/test] wallboat t={} rel={} up={} visualDefault={} dir={} uprightClear={}",
                    tick, fmt(rel),
                    comp == null ? "null" : fmt(comp.getUpVector()),
                    comp == null ? "?" : comp.isVisuallyDefault(),
                    comp == null ? "?" : comp.getCurrGravityDirection(),
                    uprightIsClear(boat));
            });
        }

        helper.runAfterDelay(190, () -> {
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(boat);
            Vec3 rel = boat.position().subtract(origin);
            if (comp == null || !comp.isVisuallyDefault()) {
                helper.fail("boat never returned to plain gravity: up="
                    + (comp == null ? "null" : fmt(comp.getUpVector())) + " rel=" + fmt(rel));
                return;
            }
            if (!uprightIsClear(boat)) {
                helper.fail("boat finished the transition INSIDE the wall: rel=" + fmt(rel)
                    + " box=" + boat.getBoundingBox());
                return;
            }
            // the teeth: unstepped, the boat comes out of the transition at
            // x = 10.0, a third of its width inside the stone column
            if (rel.x > 9.4) {
                helper.fail("boat is in the wall column: rel=" + fmt(rel));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A VEHICLE'S HEADING ACROSS A CARDINAL. Non-player entities move in the
     * CARDINAL frame ({@code GravityChangerAPI.getMovementRotation}), not the
     * continuous visual one players use, so when their cardinal flips the
     * same stored yaw suddenly names a different world direction. Driving a
     * boat over a gravity-core cube's edge must not turn it round: the world
     * heading it had on the old face is the heading it keeps on the new one.
     *
     * Needs no movement (Valkyrien Skies cancels every move() in the
     * game-test world): the cardinal is driven straight through the base
     * gravity API and the world heading compared either side of it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void vehicleKeepsHeadingAcrossACardinal(GameTestHelper helper) {
        Boat boat = helper.spawn(EntityType.BOAT, new Vec3(9.5, 4.0, 9.5));
        boat.setYRot(180.0F);
        boat.setDeltaMovement(0.0, 0.0, -0.3);

        Vec3[] before = new Vec3[2];
        helper.runAfterDelay(20, () -> {
            before[0] = worldHeading(boat);
            before[1] = GravityChangerAPI.getWorldVelocity(boat);
            LOGGER.info("[GravityUnbound/test] heading before: dir={} yaw={} world={} vel={}",
                GravityChangerAPI.getGravityDirection(boat), boat.getYRot(),
                fmt(before[0]), fmt(before[1]));
            // Top face -> north face of a CORE cube. The core is at the
            // centre, so on the north face down points back at it: SOUTH.
            GravityChangerAPI.setBaseGravityDirection(boat, Direction.SOUTH);
        });

        helper.runAfterDelay(60, () -> {
            Vec3 after = worldHeading(boat);
            Vec3 afterVel = GravityChangerAPI.getWorldVelocity(boat);
            LOGGER.info("[GravityUnbound/test] heading after: dir={} yaw={} world={} vel={}",
                GravityChangerAPI.getGravityDirection(boat), boat.getYRot(),
                fmt(after), fmt(afterVel));
            if (GravityChangerAPI.getGravityDirection(boat) != Direction.SOUTH) {
                helper.fail("the boat never adopted the new cardinal");
                return;
            }
            // PARALLEL TRANSPORT is the invariant, not an unchanged world
            // heading: rounding the edge rotates the frame by whatever
            // carries the old down onto the new one, and driving north over
            // the top edge means driving DOWN the north face afterwards. The
            // heading must be the old one under that same rotation - keep
            // holding forward and you keep going the same way round the cube.
            Vec3 expected = transport(Direction.DOWN, Direction.SOUTH, before[0]);
            if (after.dot(expected) < 0.99) {
                helper.fail("boat heading did not carry across the cardinal: was " + fmt(before[0])
                    + ", expected " + fmt(expected) + ", got " + fmt(after));
                return;
            }
            // No assertion on the velocity: compensateFrameChange carries it
            // through the same two frames in the same call, so the heading
            // check already covers them - and by the time we look, gravity
            // has been accelerating the boat along the NEW down for two
            // seconds, so a comparison here would measure that, not the
            // transport.
            helper.succeed();
        });
    }

    /**
     * A RIDER KEEPS ITS SEAT'S HEADING. Looking straight ahead over a boat's
     * bow, then driving over a cube edge onto the next face, must leave the
     * view still straight ahead over the bow. The two are kept in DIFFERENT
     * frames - the rider aims in the continuous visual frame, the vehicle
     * moves in the cardinal one - so the only meaningful comparison is the
     * angle between their WORLD headings, about the frame's up.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void riderKeepsHeadingWithItsVehicle(GameTestHelper helper) {
        for (int x = 4; x <= 15; x++) {
            for (int z = 4; z <= 15; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
            }
        }
        Boat boat = helper.spawn(EntityType.BOAT, new Vec3(9.5, 4.0, 9.5));
        boat.setYRot(180.0F);
        net.minecraft.world.entity.player.Player rider = helper.makeMockPlayer();
        rider.moveTo(boat.getX(), boat.getY(), boat.getZ(), 180.0F, 0.0F);
        rider.startRiding(boat, true);

        double[] before = new double[1];
        helper.runAfterDelay(20, () -> {
            before[0] = relativeHeadingDeg(rider, boat);
            LOGGER.info("[GravityUnbound/test] rider before: rel={} riding={} boatYaw={} riderYaw={} dir={}",
                String.format(java.util.Locale.ROOT, "%.2f", before[0]), rider.isPassenger(),
                boat.getYRot(), rider.getYRot(), GravityChangerAPI.getGravityDirection(boat));
        });

        // GRADUALLY, the way rounding a real cube edge does it: the field
        // sweeps from DOWN to SOUTH over two seconds, so the cardinal snaps
        // partway through while the frames are still turning. Flipping it in
        // one step hides the bug - everything then re-parametrizes in the
        // same tick, before the rider is ever positioned.
        for (int t = 21; t <= 30; t++) {
            final double a = Math.PI * 0.5 * ((t - 20) / 10.0);
            helper.runAfterDelay(t, () -> GravityChangerAPI.setBaseGravityDirection(
                boat, new Vec3(0.0, -Math.cos(a), Math.sin(a))));
        }

        for (int t = 30; t <= 100; t += 10) {
            final int tick = t;
            helper.runAfterDelay(t, () -> LOGGER.info(
                "[GravityUnbound/test] rider t={} rel={} boatYaw={} riderYaw={} boatDir={} riderDir={}",
                tick, String.format(java.util.Locale.ROOT, "%.2f", relativeHeadingDeg(rider, boat)),
                boat.getYRot(), rider.getYRot(),
                GravityChangerAPI.getGravityDirection(boat), GravityChangerAPI.getGravityDirection(rider)));
        }

        helper.runAfterDelay(120, () -> {
            double after = relativeHeadingDeg(rider, boat);
            LOGGER.info("[GravityUnbound/test] rider after: rel={}",
                String.format(java.util.Locale.ROOT, "%.2f", after));
            if (!rider.isPassenger()) {
                helper.fail("the rider stopped riding");
                return;
            }
            double drift = Math.abs(net.minecraft.util.Mth.wrapDegrees((float) (after - before[0])));
            if (drift > 5.0) {
                helper.fail("rider's heading drifted off the vehicle across the cardinal: was "
                    + String.format(java.util.Locale.ROOT, "%.2f", before[0]) + " deg, now "
                    + String.format(java.util.Locale.ROOT, "%.2f", after) + " deg");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A RIDER SITS ON ITS MOUNT'S FRAME. Vanilla seats a passenger by adding
     * the riding offset on the WORLD Y axis, so under rotated gravity the
     * rider floats off to the side of its mount instead of onto its back -
     * the player model sitting well clear of a horse standing in a field.
     * Boats and minecarts already override this with frame-aware versions;
     * everything else (horses, pigs, striders, modded mounts) used the
     * default. The seat offset must lie along the MOUNT's own up.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void riderSitsOnItsMountsFrame(GameTestHelper helper) {
        for (int x = 4; x <= 15; x++) {
            for (int z = 4; z <= 15; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
            }
        }
        net.minecraft.world.entity.animal.horse.Horse horse =
            helper.spawn(EntityType.HORSE, new Vec3(9.5, 3.0, 9.5));
        horse.setTamed(true);
        net.minecraft.world.entity.player.Player rider = helper.makeMockPlayer();
        rider.startRiding(horse, true);
        GravityChangerAPI.setBaseGravityDirection(horse, Direction.EAST);

        helper.runAfterDelay(60, () -> {
            Vec3 up = GravityChangerAPI.getUpVector(horse);
            Vec3 delta = rider.position().subtract(horse.position());
            double along = delta.dot(up);
            double sideways = delta.subtract(up.scale(along)).length();
            LOGGER.info("[GravityUnbound/test] mount: dir={} base={} up={} delta={} along={} sideways={}",
                GravityChangerAPI.getGravityDirection(horse),
                GravityChangerAPI.getBaseGravityDirection(horse),
                fmt(up), fmt(delta),
                String.format(java.util.Locale.ROOT, "%.3f", along),
                String.format(java.util.Locale.ROOT, "%.3f", sideways));
            if (!rider.isPassenger()) {
                helper.fail("the rider stopped riding");
                return;
            }
            if (Math.abs(up.y) > 0.1) {
                helper.fail("the horse's frame never turned off world up: up=" + fmt(up));
                return;
            }
            // vanilla puts the seat on world +Y, which is entirely SIDEWAYS
            // once the mount's up is west
            if (sideways > 0.1) {
                helper.fail("rider is seated off the side of its mount: delta=" + fmt(delta)
                    + " up=" + fmt(up) + " sideways=" + String.format(java.util.Locale.ROOT,
                    "%.3f", sideways));
                return;
            }
            if (along < 0.1) {
                helper.fail("rider is not seated along its mount's up: delta=" + fmt(delta)
                    + " up=" + fmt(up));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Signed angle, about the vehicle's up, from the vehicle's world heading
     * to the rider's world look. Both are read in the frame each actually
     * uses, so this is comparable across a frame change even though their
     * raw yaw numbers are not.
     */
    private static double relativeHeadingDeg(net.minecraft.world.entity.Entity rider,
                                             net.minecraft.world.entity.Entity vehicle) {
        Vec3 look = net.camacraft.gravityunbound.util.RotationUtil.vecPlayerToWorld(
            net.camacraft.gravityunbound.util.RotationUtil.rotToVec(rider.getYRot(), 0),
            GravityChangerAPI.getAimRotation(rider));
        Vec3 heading = net.camacraft.gravityunbound.util.RotationUtil.vecPlayerToWorld(
            net.camacraft.gravityunbound.util.RotationUtil.rotToVec(vehicle.getYRot(), 0),
            GravityChangerAPI.getMovementRotation(vehicle));
        Vec3 up = GravityChangerAPI.getUpVector(vehicle);
        return Math.toDegrees(Math.atan2(heading.cross(look).dot(up), heading.dot(look)));
    }

    /** {@code v} carried across an edge: rotated as the down was. */
    private static Vec3 transport(Direction from, Direction to, Vec3 v) {
        org.joml.Vector3f out = v.toVector3f();
        out.rotate(net.camacraft.gravityunbound.util.RotationUtil.getRotationBetween(from, to));
        return new Vec3(out);
    }

    /** The entity's flat world-space heading, in the frame it MOVES in. */
    private static Vec3 worldHeading(net.minecraft.world.entity.Entity entity) {
        return net.camacraft.gravityunbound.util.RotationUtil.vecPlayerToWorld(
            net.camacraft.gravityunbound.util.RotationUtil.rotToVec(entity.getYRot(), 0),
            GravityChangerAPI.getMovementRotation(entity));
    }

    /**
     * Is the entity's UPRIGHT (plain-gravity) body clear of blocks? Built
     * from the dimensions rather than read off the entity, because the
     * stored box is only refreshed when something moves the entity - a
     * frozen or resting one can still be carrying the rotated envelope,
     * which is narrower across the face and hides the very overlap this
     * asks about.
     */
    private static boolean uprightIsClear(net.minecraft.world.entity.Entity entity) {
        net.minecraft.world.phys.AABB upright =
            entity.getDimensions(entity.getPose()).makeBoundingBox(entity.position());
        return entity.level().noCollision(entity, upright.deflate(0.02));
    }

    private static void buildCoreCube(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
        BlockPos core = new BlockPos(9, 5, 9);
        for (int x = 8; x <= 10; x++) {
            for (int y = 4; y <= 6; y++) {
                for (int z = 8; z <= 10; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    helper.setBlock(p, p.equals(core)
                        ? GravityBlocks.GRAVITY_CORE.get().defaultBlockState()
                        : Blocks.STONE.defaultBlockState());
                }
            }
        }
    }

    private static void logChicken(String label, int tick, net.minecraft.world.entity.animal.Chicken chicken, Vec3 origin) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(chicken);
        Vec3 rel = chicken.position().subtract(origin);
        // what the capsule would do with the chicken's current local velocity
        String probe = "n/a";
        if (comp != null && comp.useCapsuleCollision()) {
            Vec3 worldMove = GravityChangerAPI.getWorldVelocity(chicken);
            net.camacraft.gravityunbound.util.CapsuleCollider.Result r =
                net.camacraft.gravityunbound.util.CapsuleCollider.collide(chicken, comp.getUpVector(), comp.getUpVector(), worldMove, false);
            probe = "worldMove=" + fmt(worldMove) + " collided=" + fmt(r.collidedMovement) + " grounded=" + r.grounded;
        }
        LOGGER.info("[GravityUnbound/test] chicken[{}] t={} tickCount={} old={} box={} noPhysics={} capsule={} {} ",
            label, tick, chicken.tickCount, fmt(new Vec3(chicken.xo, chicken.yo, chicken.zo).subtract(origin)),
            chicken.getBoundingBox().move(origin.scale(-1)), chicken.noPhysics,
            comp == null ? "?" : comp.useCapsuleCollision(), probe);
        LOGGER.info("[GravityUnbound/test] chicken[{}] t={} rel={} dm={} onGround={} frameGrounded={} up={} visualDefault={} settled={} dir={} flapSpeed={} nav={}",
            label, tick, fmt(rel), fmt(chicken.getDeltaMovement()), chicken.onGround(),
            comp == null ? "?" : comp.isGroundedInFrame(),
            comp == null ? "null" : fmt(comp.getUpVector()),
            comp == null ? "?" : comp.isVisuallyDefault(),
            comp == null ? "?" : comp.getSettledCardinal(),
            comp == null ? "?" : comp.getCurrGravityDirection(),
            String.format(java.util.Locale.ROOT, "%.2f", chicken.flapSpeed),
            chicken.getNavigation().getClass().getSimpleName());
    }

    // A Valkyrien Skies ship cannot be assembled in this headless test world:
    // the shipyard chunks never start ticking without a player near them, VS's
    // assembly waits for them ("Timed out waiting for chunks to start ticking
    // after assembly"), and that wait froze entity ticking in the neighbouring
    // test cell as well. Ship-mounted fields are tested in game.
}
