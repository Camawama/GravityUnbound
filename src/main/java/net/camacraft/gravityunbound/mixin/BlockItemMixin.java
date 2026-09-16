package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.util.PlacementFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Opens the ship-placement window (see {@link PlacementFrame#inShipPlacement()}). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @WrapOperation(
        method = "getPlacementState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;getStateForPlacement(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState gravityunbound$shipPlacementWindow(Block block, BlockPlaceContext context, Operation<BlockState> original) {
        boolean onShip = org.valkyrienskies.mod.common.VSGameUtilsKt.getShipManagingPos(
            context.getLevel(), context.getClickedPos()) != null;
        if (!onShip) {
            return original.call(block, context);
        }
        PlacementFrame.enterShipPlacement();
        try {
            return original.call(block, context);
        }
        finally {
            PlacementFrame.exitShipPlacement();
        }
    }
}
