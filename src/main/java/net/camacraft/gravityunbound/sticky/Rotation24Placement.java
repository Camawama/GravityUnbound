package net.camacraft.gravityunbound.sticky;

import org.jetbrains.annotations.Nullable;

import net.camacraft.camafy.compat.vs.Ships;
import net.camacraft.camafy.util.math.Rotation24;
import net.camacraft.gravityunbound.api.GravityBlockHelper;
import net.camacraft.gravityunbound.util.RotationUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The placement half of the Gravity Block Framework's 24 grid orientations:
 * the pickers that need this mod's gravity frame and Valkyrien Skies'
 * placement transform. The orientation maths itself (quaternions, direction
 * tables, rotated shapes, {@link Rotation24.Orientation}) lives in Camafy's
 * {@link Rotation24}; these work out the grid-space vectors and hand them to
 * {@link Rotation24#forPlacement} / {@link Rotation24#forClickedFace}.
 */
public final class Rotation24Placement {

    /**
     * Picks the orientation for a block being placed by {@code placer} at
     * {@code pos}: the bottom face is the placer's gravity down and the spin
     * turns the block's front toward the placer, both in the BLOCK GRID of
     * the placement position (shipyard space on Valkyrien Skies ships).
     *
     * MUST be called from within the block-placement flow
     * ({@code BlockItem.place} / {@code getStateForPlacement}). Valkyrien
     * Skies' block_placement mixins wrap that flow in
     * {@code PlayerUtil.transformPlayerTemporarily}: while it runs, a
     * ship-placement player's ROTATION FIELDS have been rewritten to
     * ship-grid look angles (derived from {@code getLookAngle()}, which
     * already includes this mod's gravity frame) and their POSITION to
     * shipyard coordinates. So on ships the raw yaw/pitch ARE the grid-space
     * look and must be used verbatim — re-applying the gravity frame
     * ({@code getLookAngle()} does) or the world-to-ship transform (a
     * position-based derivation does) double-rotates, which is exactly what
     * broke the two previous attempts. Off ships the VS wrap is inactive and
     * the true world look is the grid look.
     *
     * The full (pitched) look is used; {@link Rotation24#spinForFront}'s
     * candidates are perpendicular to the bottom face, so dotting inherently
     * projects out the bottom component — standing on a wall and looking "up
     * the wall" picks the vertical-in-grid front correctly.
     *
     * Null-safe: defaults to (DOWN, 0).
     */
    public static Rotation24.Orientation fromPlacement(@Nullable Entity placer, Level level, BlockPos pos) {
        if (placer == null) {
            return new Rotation24.Orientation(Direction.DOWN, 0);
        }
        // front faces TOWARD the placer, i.e. opposite the look (forPlacement negates it)
        return Rotation24.forPlacement(
            GravityBlockHelper.worldDirectionToGrid(level, pos, GravityBlockHelper.gravityDownVector(placer)),
            gridLook(placer, level, pos)
        );
    }

    /**
     * Picks the orientation for a block being placed AGAINST a clicked
     * surface: the bottom face is the opposite of {@code clickedFace} (the
     * block sits ON the surface that was clicked) and the spin turns the
     * local front toward the placer's look direction — the local-south
     * candidate with the maximum dot against the grid-space look. The spin
     * candidates are perpendicular to the bottom face, so the dot inherently
     * projects the look into the clicked face's plane.
     *
     * Unlike {@link #fromPlacement} the placer's gravity frame plays no part
     * in the bottom face — surface-attached content (redstone, carpets,
     * rails) lies flat on whatever surface is clicked, regardless of the
     * placer's gravity. Same VS caveat as {@code fromPlacement}: MUST be
     * called from within the block-placement flow; on ships the clicked face
     * of the placement context is already a ship-grid direction and the
     * look is recovered the same way (see {@link #fromPlacement}).
     *
     * Null-safe: without a placer the spin defaults to 0.
     */
    public static Rotation24.Orientation fromClickedFace(
        @Nullable Entity placer, Level level, BlockPos pos, Direction clickedFace
    ) {
        return Rotation24.forClickedFace(clickedFace, placer == null ? null : gridLook(placer, level, pos));
    }

    /**
     * The placer's look direction in the BLOCK GRID of {@code pos}. See
     * {@link #fromPlacement} for why the raw rotation fields are used
     * verbatim on Valkyrien Skies ships.
     */
    private static Vec3 gridLook(Entity placer, Level level, BlockPos pos) {
        return Ships.inShipyard(level, pos)
            // VS's temporary placement transform: raw angles are already the
            // ship-grid look — use them verbatim (see fromPlacement javadoc)
            ? RotationUtil.rotToVec(placer.getYRot(), placer.getXRot())
            // off-ship: grid == world; the true world look (gravity-frame aware)
            : placer.getLookAngle();
    }

    private Rotation24Placement() {}
}
