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
}
