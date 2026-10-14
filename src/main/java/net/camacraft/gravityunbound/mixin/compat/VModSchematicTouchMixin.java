package net.camacraft.gravityunbound.mixin.compat;

import java.util.ArrayList;
import java.util.List;

import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.Ship;

import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.core.GravityCoreForceInducer;
import net.minecraft.world.level.Level;

/**
 * VMod's toolgun copies a schematic of the ship aimed at and every ship
 * "touching" it, transitively: each ship's world box asked for the ships it
 * intersects ({@code traverseGetAllTouchingShips}). A gravity field pulls the
 * ships it holds into contact with its own ship and with each other, so aiming
 * at one slime-block ship held round a core copied the core and every ship
 * round it: "it ended up making the entire schematic of the gravity core and
 * all the slime blocks even though I only tried to copy the single ship"
 * (2026-10-12). A contact where either ship is held by a field
 * ({@link GravityCoreForceInducer#heldByField}) is the field's doing, so it
 * does not join them here; VMod's own connections (its constraints, walked
 * separately) still do. Config {@code vmodSchematicsSkipFieldContacts}.
 * Only applied when VMod is present (GravityMixinPlugin).
 */
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "net.spaceeye.vmod.utils.vs.TraverseGetConnectedShipsKt", remap = false)
public abstract class VModSchematicTouchMixin {

    /** The ship whose box is being asked about, from the read just before the query (server thread). */
    @Unique
    private static long gravityunbound$askingFor = Long.MIN_VALUE;

    @Redirect(method = "traverseGetAllTouchingShips", remap = false,
        at = @At(value = "INVOKE", remap = false,
            target = "Lorg/valkyrienskies/core/api/ships/ServerShip;getWorldAABB()Lorg/joml/primitives/AABBdc;"))
    private static AABBdc gravityunbound$rememberShip(ServerShip ship) {
        gravityunbound$askingFor = ship.getId();
        return ship.getWorldAABB();
    }

    @Redirect(method = "traverseGetAllTouchingShips", remap = false,
        at = @At(value = "INVOKE", remap = false,
            target = "Lorg/valkyrienskies/mod/common/VSGameUtilsKt;getShipsIntersecting(Lnet/minecraft/world/level/Level;Lorg/joml/primitives/AABBdc;)Ljava/lang/Iterable;"))
    private static Iterable<Ship> gravityunbound$withoutFieldContacts(Level level, AABBdc box) {
        Iterable<Ship> touching = org.valkyrienskies.mod.common.VSGameUtilsKt.getShipsIntersecting(level, box);
        long asking = gravityunbound$askingFor;
        if (asking == Long.MIN_VALUE || !GravityConfig.vmodSchematicsSkipFieldContacts.get()) {
            return touching;
        }
        boolean askingHeld = GravityCoreForceInducer.heldByField(asking);
        List<Ship> kept = new ArrayList<>();
        for (Ship ship : touching) {
            if (ship.getId() == asking || !(askingHeld || GravityCoreForceInducer.heldByField(ship.getId()))) {
                kept.add(ship);
            }
        }
        return kept;
    }
}
