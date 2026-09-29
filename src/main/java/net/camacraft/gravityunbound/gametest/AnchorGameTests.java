package net.camacraft.gravityunbound.gametest;

import net.camacraft.camafy.compat.gravity.Gravity;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.init.GravityEnchantments;
import net.camacraft.gravityunbound.plating.GravityPlatingBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Camafy's {@code Gravity.isAnchored}, as Gravity Unbound's provider answers it: an entity
 * standing on gravity plating, or clinging with Surface Cling boots, is held fast to its surface,
 * so Full Stop never throws it off a ship that stops or swerves under it. Standing on plain
 * ground, floating in a plate's field without touching anything, or wearing plain boots is not
 * anchored. Mobs with AI but no goals, so they stand where they are put and still move under
 * their own physics (NoAI mobs never update {@code onGround}). Runs in the compat batch, after
 * the default one, so the default batch's layout is unchanged.
 */
@GameTestHolder("gravityunbound")
@PrefixGameTestTemplate(false)
public class AnchorGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("GravityUnbound/test");
    private static final String EMPTY = "empty20";

    /** A stone floor at y = 1 over x, z 2..16. */
    private static void floor(GameTestHelper helper) {
        for (int x = 2; x <= 16; x++) {
            for (int z = 2; z <= 16; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /** A mob that stands where it is put: AI on, no goals. */
    private static <T extends Mob> T stand(GameTestHelper helper, EntityType<T> type, Vec3 at) {
        T mob = helper.spawn(type, at);
        mob.goalSelector.removeAllGoals(goal -> true);
        mob.targetSelector.removeAllGoals(goal -> true);
        mob.setPersistenceRequired();
        return mob;
    }

    /**
     * Plating on the floor (pulling down) under one cow: after two seconds it is anchored. A cow
     * on bare stone far off is not, and neither is one on bare stone right beside the plated
     * patch, in the plates' sideways bleed (a secondary field, which doesn't hold anyone).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = EntityObstacleGameTests.BATCH)
    public static void standingOnPlatingIsAnchored(GameTestHelper helper) {
        floor(helper);
        BlockState plating = GravityBlocks.GRAVITY_PLATING.get().defaultBlockState()
            .setValue(GravityPlatingBlock.directionToProperty(Direction.DOWN), true);
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 2, z), plating);
            }
        }
        net.minecraft.world.entity.animal.Cow onPlating = stand(helper, EntityType.COW, new Vec3(6.5, 2.0, 6.5));
        net.minecraft.world.entity.animal.Cow onStone = stand(helper, EntityType.COW, new Vec3(13.5, 2.0, 13.5));
        net.minecraft.world.entity.animal.Cow beside = stand(helper, EntityType.COW, new Vec3(9.6, 2.0, 6.5));

        helper.runAfterDelay(40, () -> {
            GravityCapabilityImpl platingComp = GravityChangerAPI.getGravityComponentOrNull(onPlating);
            long now = helper.getLevel().getGameTime();
            boolean a = Gravity.isAnchored(onPlating);
            boolean b = Gravity.isAnchored(onStone);
            boolean c = Gravity.isAnchored(beside);
            LOGGER.info("[GravityUnbound/test] anchor plating: on plating anchored={} (onGround={}, field {} ticks ago), "
                    + "on stone anchored={} (onGround={}), beside the plating anchored={} (onGround={}, at {})",
                a, onPlating.onGround(), platingComp == null ? "?" : now - platingComp.lastPlatingFieldTick, b,
                onStone.onGround(), c, beside.onGround(), beside.position());
            if (!a) {
                helper.fail("a cow standing on gravity plating is not anchored (onGround=" + onPlating.onGround() + ")");
                return;
            }
            if (b) {
                helper.fail("a cow standing on plain stone is anchored");
                return;
            }
            if (c) {
                helper.fail("a cow standing on bare stone beside the plating is anchored");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A pig in Surface Cling boots standing on stone clings to it: anchored. A pig in plain iron
     * boots is not.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = EntityObstacleGameTests.BATCH)
    public static void surfaceClingBootsAnchor(GameTestHelper helper) {
        floor(helper);
        net.minecraft.world.entity.animal.Pig clinging = stand(helper, EntityType.PIG, new Vec3(5.5, 2.0, 5.5));
        ItemStack cling = new ItemStack(Items.IRON_BOOTS);
        cling.enchant(GravityEnchantments.SURFACE_CLING.get(), 1);
        clinging.setItemSlot(EquipmentSlot.FEET, cling);
        net.minecraft.world.entity.animal.Pig plain = stand(helper, EntityType.PIG, new Vec3(13.5, 2.0, 13.5));
        plain.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));

        helper.runAfterDelay(40, () -> {
            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(clinging);
            boolean a = Gravity.isAnchored(clinging);
            boolean b = Gravity.isAnchored(plain);
            LOGGER.info("[GravityUnbound/test] anchor cling: Surface Cling pig anchored={} (holding={}, onGround={}), "
                    + "plain boots pig anchored={}", a, comp != null && comp.clingHeldLastTick, clinging.onGround(), b);
            if (!a) {
                helper.fail("a pig in Surface Cling boots standing on stone is not anchored (holding="
                    + (comp != null && comp.clingHeldLastTick) + ")");
                return;
            }
            if (b) {
                helper.fail("a pig in plain boots is anchored");
                return;
            }
            helper.succeed();
        });
    }
}
