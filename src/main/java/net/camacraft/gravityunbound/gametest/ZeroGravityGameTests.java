package net.camacraft.gravityunbound.gametest;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.init.GravityMobEffects;
import net.camacraft.gravityunbound.plating.GravityPlatingBlock;
import net.camacraft.gravityunbound.plating.GravityPlatingBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Gravity Unbound's answers to Camafy's real-gravity reading (0.1.7), and its fields in zero
 * gravity. VS Genesis makes its space weightless by flagging every entity no-gravity; the flag
 * is the stand-in here (Genesis isn't on the dev runtime). Runs in the compat batch, with the
 * other tests that need a clean cell.
 */
@GameTestHolder("gravityunbound")
@PrefixGameTestTemplate(false)
public class ZeroGravityGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("GravityUnbound/test");
    private static final String EMPTY = "empty20";

    private static void floor(GameTestHelper helper) {
        for (int x = 2; x <= 16; x++) {
            for (int z = 2; z <= 16; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static <T extends Mob> T stand(GameTestHelper helper, EntityType<T> type, Vec3 at) {
        T mob = helper.spawn(type, at);
        mob.goalSelector.removeAllGoals(goal -> true);
        mob.targetSelector.removeAllGoals(goal -> true);
        mob.setPersistenceRequired();
        return mob;
    }

    /** Floor plating pulling down over x, z 4..8 at y = 2, reaching 5 blocks up, at {@code accel} blocks/tick^2. */
    private static void plating(GameTestHelper helper, double accel) {
        BlockState plating = GravityBlocks.GRAVITY_PLATING.get().defaultBlockState()
            .setValue(GravityPlatingBlock.directionToProperty(Direction.DOWN), true);
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 8; z++) {
                BlockPos pos = new BlockPos(x, 2, z);
                helper.setBlock(pos, plating);
                if (helper.getBlockEntity(pos) instanceof GravityPlatingBlockEntity be) {
                    be.applySettingsFromGui(Direction.DOWN, 5, true, false, accel, true, false, false);
                }
            }
        }
    }

    /**
     * A strength potion (Decrease I: 0.7) reaches Camafy once: the provider's 0.7 is carried by
     * Gravity Unbound's attribute modifier too, and Camafy leaves that modifier out, so the real
     * strength reads 0.7, not 0.49. A mob flagged no-gravity outside any field reads 0.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = EntityObstacleGameTests.BATCH)
    public static void camafyCountsTheStrengthOnce(GameTestHelper helper) {
        floor(helper);
        Pig pig = stand(helper, EntityType.PIG, new Vec3(12.5, 2.0, 12.5));
        pig.addEffect(new MobEffectInstance(GravityMobEffects.DECREASE.get(), 600, 0));
        Pig flagged = stand(helper, EntityType.PIG, new Vec3(14.5, 2.0, 5.5));
        flagged.setNoGravity(true);

        helper.runAfterDelay(20, () -> {
            double provider = Gravity.providerStrength(pig);
            double real = Gravity.strength(pig);
            double attribute = pig.getAttributeValue(ForgeMod.ENTITY_GRAVITY.get());
            LOGGER.info("[GravityUnbound/test] zero-g strength: provider={} real={} attribute={} flagged={}",
                provider, real, attribute, Gravity.strength(flagged));
            if (Math.abs(provider - 0.7) > 1.0E-3) {
                helper.fail("the potion's strength is " + provider + ", not 0.7");
            } else if (Math.abs(attribute - 0.056) > 1.0E-3) {
                helper.fail("the attribute carries " + attribute + ", not 0.056 (0.08 x 0.7)");
            } else if (Math.abs(real - 0.7) > 1.0E-3) {
                helper.fail("Camafy's real strength is " + real + " (0.49 would be the strength counted twice)");
            } else if (Gravity.strength(flagged) != 0.0 || !Gravity.isWeightless(flagged)) {
                helper.fail("a no-gravity pig outside any field reads " + Gravity.strength(flagged));
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * In zero gravity a field still pulls: a pig flagged no-gravity above floor plating is pulled
     * down onto it, and Camafy reads the field's strength for it (the provider owns its gravity),
     * while a flagged pig away from the plating hangs where it was, weightless.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = EntityObstacleGameTests.BATCH)
    public static void aFieldPullsANoGravityMob(GameTestHelper helper) {
        floor(helper);
        plating(helper, 0.08);
        Pig inField = stand(helper, EntityType.PIG, new Vec3(6.5, 5.0, 6.5));
        inField.setNoGravity(true);
        Pig adrift = stand(helper, EntityType.PIG, new Vec3(14.5, 5.0, 14.5));
        adrift.setNoGravity(true);
        double startY = adrift.getY();
        double[] inFieldStrength = {Double.NaN};

        helper.runAfterDelay(30, () -> inFieldStrength[0] = Gravity.strength(inField));
        helper.runAfterDelay(80, () -> {
            double plateTop = helper.absoluteVec(new Vec3(0, 2.0, 0)).y;
            LOGGER.info("[GravityUnbound/test] zero-g field: in-field y={} (plate top {}), strength at 30={}, adrift y={} (start {}) strength={}",
                inField.getY(), plateTop, inFieldStrength[0], adrift.getY(), startY, Gravity.strength(adrift));
            if (!(inField.getY() < plateTop + 0.5)) {
                helper.fail("the no-gravity pig was not pulled down onto the plating (at " + (inField.getY() - plateTop) + " above it)");
            } else if (!(inFieldStrength[0] > 0.9)) {
                helper.fail("Camafy read " + inFieldStrength[0] + " for a no-gravity pig in a full-strength field");
            } else if (Math.abs(adrift.getY() - startY) > 0.2) {
                helper.fail("the no-gravity pig outside the field moved " + (adrift.getY() - startY));
            } else if (!Gravity.isWeightless(adrift)) {
                helper.fail("the no-gravity pig outside the field is not weightless");
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * A field set to zero strength is a weightless zone: a pig standing in it reads weightless
     * (Camafy and the attribute agree), while its frame is the field's.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = EntityObstacleGameTests.BATCH)
    public static void aZeroStrengthFieldIsWeightless(GameTestHelper helper) {
        floor(helper);
        plating(helper, 0.0);
        Pig pig = stand(helper, EntityType.PIG, new Vec3(6.5, 3.5, 6.5));

        helper.runAfterDelay(40, () -> {
            double attribute = pig.getAttributeValue(ForgeMod.ENTITY_GRAVITY.get());
            LOGGER.info("[GravityUnbound/test] zero-strength field: provider={} real={} attribute={}",
                Gravity.providerStrength(pig), Gravity.strength(pig), attribute);
            if (!(Gravity.providerStrength(pig) < 1.0E-3)) {
                helper.fail("the zero-strength field gives strength " + Gravity.providerStrength(pig));
            } else if (!Gravity.isWeightless(pig)) {
                helper.fail("a pig in a zero-strength field is not weightless (" + Gravity.strength(pig) + ")");
            } else if (Math.abs(attribute) > 1.0E-3) {
                helper.fail("the attribute still pulls " + attribute);
            } else {
                helper.succeed();
            }
        });
    }
}
