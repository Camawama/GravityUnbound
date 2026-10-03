package net.camacraft.gravityunbound.gametest;

import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.plating.GravityPlatingBlock;
import net.camacraft.gravityunbound.plating.GravityPlatingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * A plate's data reaching a client. Vanilla's chunk packet carries a block entity's data only when its update tag is
 * not empty (ClientboundLevelChunkPacketData.BlockEntityInfo sends null otherwise, and the client never loads its copy):
 * a plate set without its item had nothing to save until its first server tick, and a client that got its chunk in
 * that window kept an inert copy for good, so the ship's whole field was missing on that client (harness
 * car_roll_space, about one run in six; car_board_roll).
 */
@GameTestHolder("gravityunbound")
@PrefixGameTestTemplate(false)
public class PlateSyncGameTests {
    private static final String EMPTY = "empty20";

    /** A plate set without its item, before its first tick: what a chunk packet would carry loads the client's copy. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void aPlateSetWithoutDataReachesAClientBeforeItsFirstTick(GameTestHelper helper) {
        BlockPos rel = new BlockPos(5, 2, 5);
        BlockState plating = GravityBlocks.GRAVITY_PLATING.get().defaultBlockState()
            .setValue(GravityPlatingBlock.directionToProperty(Direction.DOWN), true);
        helper.setBlock(rel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(rel, plating);
        BlockPos abs = helper.absolutePos(rel);
        if (!(helper.getLevel().getBlockEntity(abs) instanceof GravityPlatingBlockEntity server)) {
            helper.fail("no plate block entity");
            return;
        }
        // as the chunk packet does: an empty update tag is sent as none, and the client's copy is never loaded
        CompoundTag tag = server.getUpdateTag();
        GravityPlatingBlockEntity client = new GravityPlatingBlockEntity(abs, plating);
        if (!tag.isEmpty()) {
            client.handleUpdateTag(tag);
        }
        helper.assertTrue(client.hasData(), "the client's copy got no data from the chunk packet (update tag " + tag + ")");
        helper.assertTrue(client.getSideData(Direction.DOWN) != null, "the client's copy has no floor side");
        helper.assertTrue(server.getSideData(Direction.DOWN) != null, "the server's copy has no floor side");
        helper.succeed();
    }

    /** A plate placed with data keeps it: the defaults never overwrite a side that is already set. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void aPlatesOwnDataIsSentUnchanged(GameTestHelper helper) {
        BlockPos rel = new BlockPos(5, 2, 5);
        BlockState plating = GravityBlocks.GRAVITY_PLATING.get().defaultBlockState()
            .setValue(GravityPlatingBlock.directionToProperty(Direction.DOWN), true);
        helper.setBlock(rel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(rel, plating);
        BlockPos abs = helper.absolutePos(rel);
        if (!(helper.getLevel().getBlockEntity(abs) instanceof GravityPlatingBlockEntity server)) {
            helper.fail("no plate block entity");
            return;
        }
        GravityPlatingBlockEntity.SideData own = GravityPlatingBlockEntity.SideData.createDefault();
        own.isAttracting = false;
        server.onPlacing(Direction.DOWN, own);
        GravityPlatingBlockEntity client = new GravityPlatingBlockEntity(abs, plating);
        client.handleUpdateTag(server.getUpdateTag());
        GravityPlatingBlockEntity.SideData sent = client.getSideData(Direction.DOWN);
        helper.assertTrue(sent != null && !sent.isAttracting, "the plate's own (repelling) side wasn't what the client got");
        helper.succeed();
    }
}
