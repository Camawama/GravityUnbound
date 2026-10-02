package net.camacraft.gravityunbound.gametest;

import net.camacraft.camafy.physics.PhysicsAspect;
import net.camacraft.camafy.physics.PhysicsOwnership;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.plating.GravityPlatingBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * An entity another mod holds (Camafy's physics claims: Physgun Unbound's beam claims every aspect while it holds, a
 * frozen entity's MOTION) is left alone by the field it is in: no pull, no anchor, no carry, no velocity edit. Its
 * gravity frame still follows the field, because the frame is a coordinate system the holder moves it through
 * ({@code Gravity.worldToMovement}), not a force. Runs in the compat batch.
 */
@GameTestHolder("gravityunbound")
@PrefixGameTestTemplate(false)
public class ClaimGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("GravityUnbound/test");
    private static final String EMPTY = "empty20";
    private static final ResourceLocation HOLDER = new ResourceLocation("gravityunbound_test", "holder");

    /** A mob that stays where it is put: AI on, no goals, and no gravity of its own (a field's pull is all it gets). */
    private static <T extends Mob> T floating(GameTestHelper helper, EntityType<T> type, Vec3 at) {
        T mob = helper.spawn(type, at);
        mob.goalSelector.removeAllGoals(goal -> true);
        mob.targetSelector.removeAllGoals(goal -> true);
        mob.setPersistenceRequired();
        mob.setNoGravity(true);
        return mob;
    }

    /**
     * A wall of plating pulling west (a sideways field) with two weightless pigs floating in it. One is claimed by
     * another owner every tick, the other is free. After three seconds the free pig has been pulled to the plate; the
     * claimed one has not moved, yet its frame stands sideways (the field's frame).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = EntityObstacleGameTests.BATCH)
    public static void aClaimedEntityKeepsTheFrameButGetsNoFieldMotion(GameTestHelper helper) {
        for (int x = 2; x <= 16; x++) {
            for (int z = 2; z <= 16; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
        BlockState plating = GravityBlocks.GRAVITY_PLATING.get().defaultBlockState()
            .setValue(GravityPlatingBlock.directionToProperty(Direction.WEST), true);
        for (int y = 2; y <= 7; y++) {
            for (int z = 3; z <= 12; z++) {
                helper.setBlock(new BlockPos(4, y, z), plating);
            }
        }
        net.minecraft.world.entity.animal.Pig held = floating(helper, EntityType.PIG, new Vec3(4.6, 4.0, 5.5));
        net.minecraft.world.entity.animal.Pig free = floating(helper, EntityType.PIG, new Vec3(4.6, 4.0, 9.5));
        Vec3 heldStart = held.position();
        Vec3 freeStart = free.position();
        helper.onEachTick(() -> {
            PhysicsOwnership.claim(held, HOLDER, PhysicsAspect.ALL, 5);
            // the holder owns its velocity, as Physgun Unbound's beam does
            held.setDeltaMovement(Vec3.ZERO);
        });

        helper.runAfterDelay(60, () -> {
            GravityCapabilityImpl heldComp = GravityChangerAPI.getGravityComponentOrNull(held);
            GravityCapabilityImpl freeComp = GravityChangerAPI.getGravityComponentOrNull(free);
            double heldMoved = held.position().distanceTo(heldStart);
            double freeMoved = free.position().distanceTo(freeStart);
            Vec3 heldUp = heldComp == null ? Vec3.ZERO : heldComp.getUpVector();
            Vec3 freeUp = freeComp == null ? Vec3.ZERO : freeComp.getUpVector();
            LOGGER.info("[GravityUnbound/test] claimed in a sideways field: held moved {} (up {}), free moved {} (up {})",
                String.format("%.3f", heldMoved), heldUp, String.format("%.3f", freeMoved), freeUp);
            // (the free pig is pulled through the thin plate and out of the field's far side long before tick 60, so
            // only its having moved is asked of it)
            if (freeMoved < 0.2) {
                helper.fail("the free pig wasn't pulled by the sideways field (moved " + freeMoved + ", up " + freeUp
                    + "): the test's field isn't working");
            }
            if (heldUp.x < 0.9) {
                helper.fail("the claimed pig's frame doesn't follow the field (up " + heldUp + ")");
            }
            if (heldMoved > 0.05) {
                helper.fail("the field moved the claimed pig by " + heldMoved + " blocks");
            }
            helper.succeed();
        });
    }
    /**
     * The owner's report of 2026-10-02: a car on plating on a moving ship was let go by its ship on every hop. MKW
     * claims six aspects of a free-roam kart as its driver and excuses a launch arc from Full Stop's impacts with the
     * seventh under a second owner: together every aspect, but nobody holds the kart. A boat in a sideways field
     * claimed that way stays the field's (not held); claimed by one owner outright (the physgun's beam) it is held.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = EntityObstacleGameTests.BATCH)
    public static void claimsThatOnlyAddUpToEveryAspectAreNotAHold(GameTestHelper helper) {
        for (int x = 2; x <= 16; x++) {
            for (int z = 2; z <= 16; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
        BlockState plating = GravityBlocks.GRAVITY_PLATING.get().defaultBlockState()
            .setValue(GravityPlatingBlock.directionToProperty(Direction.WEST), true);
        for (int y = 2; y <= 7; y++) {
            for (int z = 3; z <= 12; z++) {
                helper.setBlock(new BlockPos(4, y, z), plating);
            }
        }
        ResourceLocation driver = new ResourceLocation("gravityunbound_test", "driver");
        ResourceLocation excuse = new ResourceLocation("gravityunbound_test", "launch_excuse");
        net.minecraft.world.entity.vehicle.Boat boat = new net.minecraft.world.entity.vehicle.Boat(
            helper.getLevel(), 0, 0, 0);
        Vec3 at = helper.absoluteVec(new Vec3(4.9, 4.0, 7.5));
        boat.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        boat.setNoGravity(true);
        helper.getLevel().addFreshEntity(boat);
        int[] phase = {0};
        boolean[] heldWhileSummed = {false};
        boolean[] heldWhileOutright = {false};
        helper.onEachTick(() -> {
            if (phase[0] == 0) {
                PhysicsOwnership.claim(boat, driver, PhysicsAspect.ALL & ~PhysicsAspect.IMPACT.bit, 5);
                PhysicsOwnership.claim(boat, excuse, PhysicsAspect.IMPACT.bit, 5);
            } else {
                PhysicsOwnership.claim(boat, HOLDER, PhysicsAspect.ALL, 5);
            }
            boolean held = foreignHold(boat);
            if (phase[0] == 0 && held) heldWhileSummed[0] = true;
            if (phase[0] == 1 && held) heldWhileOutright[0] = true;
        });
        helper.runAfterDelay(40, () -> {
            PhysicsOwnership.release(boat, driver);
            PhysicsOwnership.release(boat, excuse);
            phase[0] = 1;
        });
        helper.runAfterDelay(80, () -> {
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(boat);
            LOGGER.info("[GravityUnbound/test] summed claims held={}, one owner's held={}, up {}",
                heldWhileSummed[0], heldWhileOutright[0], comp == null ? null : comp.getUpVector());
            if (comp == null) {
                helper.fail("the boat has no gravity component");
            }
            if (heldWhileSummed[0]) {
                helper.fail("two owners' claims adding up to every aspect were taken for a hold");
            }
            if (!heldWhileOutright[0]) {
                helper.fail("one owner claiming every aspect wasn't taken for a hold (the test's boat isn't ticked?)");
            }
            helper.succeed();
        });
    }

    private static boolean foreignHold(net.minecraft.world.entity.Entity entity) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null) return false;
        try {
            java.lang.reflect.Field f = GravityCapabilityImpl.class.getDeclaredField("foreignHold");
            f.setAccessible(true);
            return f.getBoolean(comp);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
