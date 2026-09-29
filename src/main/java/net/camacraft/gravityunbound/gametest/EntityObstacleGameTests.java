package net.camacraft.gravityunbound.gametest;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * HARD-COLLISION ENTITIES UNDER A ROTATED FRAME. The capsule collider that
 * replaces vanilla's collide under non-default gravity gathered block shapes
 * only, never {@code Level.getEntityCollisions}, so everything vanilla
 * stands on that is an ENTITY (boats, minecarts, shulkers, Vanilla
 * Backport's happy ghast) was air: a mob falling onto one under a rotated
 * frame went straight through it. Each test logs under the
 * {@code [GravityUnbound/test]} prefix so a failure explains itself.
 */
@GameTestHolder("gravityunbound")
@PrefixGameTestTemplate(false)
public class EntityObstacleGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("GravityUnbound/test");
    private static final String EMPTY = "empty20";
    /**
     * A batch of their own, run after the default one: the default batch
     * keeps its original tests in their original cells (a Valkyrien Skies
     * ship left in the dev test world freezes whatever lands on it, so a
     * shifted layout moves that freeze onto a different test).
     */
    static final String BATCH = "gravityunbound_compat";

    /** The shulker sits on this one block, clear of everything else: its box is x 10..11, y 5..6, z 9..10. */
    private static final BlockPos SHULKER_SEAT = new BlockPos(10, 4, 9);
    private static final Vec3 SHULKER_AT = new Vec3(10.5, 5.0, 9.5);

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }

    private static Shulker placeShulker(GameTestHelper helper) {
        helper.setBlock(SHULKER_SEAT, Blocks.STONE.defaultBlockState());
        return helper.spawnWithNoFreeWill(EntityType.SHULKER, SHULKER_AT);
    }

    /**
     * Holds {@code entity} at {@code park} (relative) with no velocity for
     * ticks 1..{@code ticks}, turning its base gravity to {@code gravity} on
     * the first: it must not fall until its frame has turned, or it would
     * already be lying on the floor it fell to under plain gravity.
     */
    private static void parkWhileTurning(GameTestHelper helper, Entity entity, Vec3 park, Direction gravity, int ticks) {
        Vec3 absolute = helper.absoluteVec(park);
        for (int t = 1; t <= ticks; t++) {
            final int tick = t;
            helper.runAfterDelay(t, () -> {
                if (tick == 1) {
                    GravityChangerAPI.setBaseGravityDirection(entity, gravity);
                }
                entity.setPos(absolute.x, absolute.y, absolute.z);
                entity.setDeltaMovement(Vec3.ZERO);
                // held in the air, gravity still counts a fall every tick:
                // a boat would land with metres of it and break
                entity.resetFallDistance();
            });
        }
    }

    private static void log(String label, int tick, Entity entity, Vec3 origin) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        LOGGER.info("[GravityUnbound/test] {} t={} rel={} dm={} onGround={} up={} visualDefault={} capsule={} vehicle={}",
            label, tick, fmt(entity.position().subtract(origin)), fmt(entity.getDeltaMovement()), entity.onGround(),
            fmt(GravityChangerAPI.getUpVector(entity)),
            comp == null ? "?" : comp.isVisuallyDefault(),
            comp == null ? "?" : comp.useCapsuleCollision(),
            entity.getVehicle() == null ? "none" : entity.getVehicle().getType().toShortString());
    }

    /**
     * A pig under gravity EAST falls sideways onto the WEST face of a
     * shulker, which is its floor in that frame. It must come to rest on
     * that face instead of passing through the shulker to the backstop wall
     * four blocks further on.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = BATCH)
    public static void mobLandsOnShulkerUnderRotatedGravity(GameTestHelper helper) {
        Shulker shulker = placeShulker(helper);
        // what a pig passing through the shulker lands on instead (x = 14)
        for (int y = 3; y <= 8; y++) {
            for (int z = 7; z <= 12; z++) {
                helper.setBlock(new BlockPos(14, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));
        // feet point level with the middle of the shulker's west face: under
        // gravity EAST the body runs west from here
        Vec3 park = new Vec3(7.0, 5.5, 9.5);
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, park);
        parkWhileTurning(helper, pig, park, Direction.EAST, 40);

        for (int t = 40; t <= 140; t += 10) {
            final int tick = t;
            helper.runAfterDelay(t, () -> log("shulker/east", tick, pig, origin));
        }

        helper.runAfterDelay(150, () -> {
            Vec3 rel = pig.position().subtract(origin);
            Vec3 up = GravityChangerAPI.getUpVector(pig);
            if (up.x > -0.9) {
                helper.fail("the pig's frame never turned to gravity EAST: up=" + fmt(up));
                return;
            }
            if (!shulker.isAlive()) {
                helper.fail("the shulker is gone (removed=" + shulker.getRemovalReason() + ")");
                return;
            }
            if (rel.x > 10.5) {
                helper.fail("the pig fell THROUGH the shulker: rel=" + fmt(rel) + " (the shulker's west face is x = 10)");
                return;
            }
            if (rel.x < 9.8) {
                helper.fail("the pig never reached the shulker: rel=" + fmt(rel));
                return;
            }
            if (!pig.onGround()) {
                helper.fail("the pig is not standing on the shulker: rel=" + fmt(rel) + " dm=" + fmt(pig.getDeltaMovement()));
                return;
            }
            helper.succeed();
        });
    }

    /** The control: the same pig and shulker at plain gravity, dropped onto the shulker's top. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = BATCH)
    public static void mobLandsOnShulkerUnderPlainGravity(GameTestHelper helper) {
        Shulker shulker = placeShulker(helper);
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(10.5, 8.0, 9.5));

        for (int t = 10; t <= 80; t += 10) {
            final int tick = t;
            helper.runAfterDelay(t, () -> log("shulker/down", tick, pig, origin));
        }

        helper.runAfterDelay(90, () -> {
            Vec3 rel = pig.position().subtract(origin);
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(pig);
            if (comp != null && !comp.isVisuallyDefault()) {
                helper.fail("the control pig is not at plain gravity: up=" + fmt(comp.getUpVector()));
                return;
            }
            if (!shulker.isAlive()) {
                helper.fail("the shulker is gone (removed=" + shulker.getRemovalReason() + ")");
                return;
            }
            if (rel.y < 5.95 || rel.y > 6.1 || !pig.onGround()) {
                helper.fail("the pig is not standing on the shulker's top (y = 6): rel=" + fmt(rel)
                    + " onGround=" + pig.onGround());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * RIDING, with entity obstacles in the collider. A boat carrying a pig,
     * under gravity EAST, falls onto a wall and rests there. The collider
     * now asks {@code getEntityCollisions}, which leaves out the mover's own
     * vehicle stack; a boat that counted its own passenger as an obstacle
     * would be shoved off it every tick. The boat must lie still on the wall
     * and the pig must still be riding it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 240, batch = BATCH)
    public static void riddenBoatRestsStillUnderRotatedGravity(GameTestHelper helper) {
        for (int y = 2; y <= 9; y++) {
            for (int z = 5; z <= 14; z++) {
                helper.setBlock(new BlockPos(12, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        Vec3 origin = Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO));
        Vec3 park = new Vec3(10.0, 5.5, 9.5);
        Boat boat = helper.spawn(EntityType.BOAT, park);
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, park);
        if (!pig.startRiding(boat, true)) {
            helper.fail("the pig could not board the boat");
            return;
        }
        parkWhileTurning(helper, boat, park, Direction.EAST, 40);

        Vec3[] first = new Vec3[1];
        double[] spread = new double[1];
        for (int t = 40; t <= 200; t += 10) {
            final int tick = t;
            helper.runAfterDelay(t, () -> {
                log("riddenboat/east", tick, boat, origin);
                log("riddenboat/rider", tick, pig, origin);
                if (tick >= 120) {
                    if (first[0] == null) {
                        first[0] = boat.position();
                    }
                    spread[0] = Math.max(spread[0], boat.position().distanceTo(first[0]));
                }
            });
        }

        helper.runAfterDelay(210, () -> {
            Vec3 rel = boat.position().subtract(origin);
            Vec3 up = GravityChangerAPI.getUpVector(boat);
            if (up.x > -0.9) {
                helper.fail("the boat's frame never turned to gravity EAST: up=" + fmt(up));
                return;
            }
            if (pig.getVehicle() != boat) {
                helper.fail("the pig stopped riding the boat");
                return;
            }
            if (rel.x < 11.0 || rel.x > 12.05) {
                helper.fail("the boat is not resting on the wall (x = 12): rel=" + fmt(rel));
                return;
            }
            if (spread[0] > 0.02) {
                helper.fail("the resting boat keeps moving: it wandered " + String.format(java.util.Locale.ROOT,
                    "%.3f", spread[0]) + " blocks over 80 ticks, rel=" + fmt(rel));
                return;
            }
            helper.succeed();
        });
    }
}
