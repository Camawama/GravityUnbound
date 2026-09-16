package net.camacraft.gravityunbound.gametest;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.sticky.Rotation24;
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
