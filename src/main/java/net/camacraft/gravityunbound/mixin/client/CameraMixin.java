package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.camafy.util.math.QuaternionUtil;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

@Mixin(value = Camera.class, priority = 1001)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    private Entity entity;

    @Shadow
    @Final
    private Quaternionf rotation;

    @Shadow
    private float eyeHeightOld;

    @Shadow
    private float eyeHeight;

    /**
     * Store the tickDelta passed to Camera#setup so all camera-related mixins use the same partial tick.
     */
    @Unique
    private float gravityunbound$tickDelta;

    @Inject(
            method = "setup",
            at = @At("HEAD")
    )
    private void gravityunbound$storeTickDelta(
            BlockGetter area,
            Entity focusedEntity,
            boolean thirdPerson,
            boolean inverseView,
            float tickDelta,
            CallbackInfo ci
    ) {
        this.gravityunbound$tickDelta = tickDelta;
    }

    @WrapOperation(
            method = "setup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;setPosition(DDD)V",
                    ordinal = 0
            )
    )
    private void wrapOperation_update_setPos_0(
            Camera camera, double x, double y, double z,
            Operation<Void> original, BlockGetter area, Entity focusedEntity,
            boolean thirdPerson, boolean inverseView, float tickDelta
    ) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(focusedEntity);

        if (comp == null || comp.isRenderDefault()) {
            original.call(this, x, y, z);
            return;
        }

        Quaternionf gravityRotation = comp.getRenderRotation(tickDelta);

        double entityX = Mth.lerp((double) tickDelta, focusedEntity.xo, focusedEntity.getX());
        double entityY = Mth.lerp((double) tickDelta, focusedEntity.yo, focusedEntity.getY());
        double entityZ = Mth.lerp((double) tickDelta, focusedEntity.zo, focusedEntity.getZ());

        // diagnostics: how far the camera-time position sits from the tick
        // position — nonzero means VS's render-ride is moving this entity
        // between ticks (see the vs-drag heartbeat)
        comp.dbgCamVsTickDist = (float) new Vec3(entityX, entityY, entityZ).distanceTo(comp.dbgTickPos);
        // AN UPRIGHT FRAME NEEDS NO MARCH. The eye is walked back out of blocks
        // below because a TURNING frame sweeps the eye height sideways, into
        // the floor or a wall. A frame that isn't turning (a rider held by a
        // plate on a level deck: isRenderDefault is false only for the plate's
        // hold) puts the eye exactly where vanilla does, and vanilla's eye is
        // never checked against blocks in first person. Checking it anyway
        // cost the owner the camera stutter of 2026-09-30: inside a room on a
        // ship doing 300 m/s the eye read as "inside a block" on frames the
        // ship's poses disagreed, and the camera was marched down to the
        // waist and back up, frame by frame.
        if (Math.abs(gravityRotation.w()) >= UPRIGHT_W) {
            original.call(this, x, y, z);
            return;
        }

        double currentCameraY = Mth.lerp(tickDelta, this.eyeHeightOld, this.eyeHeight);

        Vec3 eyeOffset = QuaternionUtil.rotate(
                new Vec3(0, currentCameraY, 0),
                new Quaternionf(gravityRotation).conjugate()
        );

        // KEEP THE EYE OUT OF THE BLOCKS.
        //
        // Vanilla only pushes a camera out of geometry in THIRD person -- the
        // move/getMaxZoom pair sits inside `if (detached)`, and getMaxZoom's rays
        // all start at the eye, so it can only shorten a distance, never displace
        // one. First person has no such mechanism because vanilla's first-person
        // eye is inside the player by construction.
        //
        // Here it is not. The offset above is the eye height ROTATED by the
        // frame, so mid-flip it sweeps a full eye height of HORIZONTAL travel --
        // into the floor the player was standing on, or any nearby wall.
        //
        // MARCHED BACK, not clipped. A first attempt ray-traced from a point on
        // the body axis out to the eye and shortened the result. It did nothing,
        // because when the body itself is against the floor that start point is
        // inside a block, and Level.clip from inside a shape reports a hit at
        // zero distance -- so the "free" distance was zero and the eye was left
        // exactly where it started. Testing the eye position itself and walking
        // it back towards the body until it is clear does not care whether
        // anything else is embedded, which is the property that was missing.
        // THE TERMINAL IS THE ENTITY ORIGIN, AND IT IS PROBED LIKE EVERY OTHER
        // SAMPLE.
        //
        // The march used to end at origin + bodyHalf ALONG THE ROTATED UP, and
        // it never tested that point. Mid-flip the rotated up points downwards,
        // so for a 1.8-tall rider the terminal is about 0.9 blocks BELOW the
        // feet -- through the floor the vehicle is parked on. The loop then ran
        // out of steps, landed exactly on that buried point and committed to
        // it, which is the bug it was written to prevent. Reported from play as
        // the camera ending up inside the ground for a few frames with nothing
        // visible at all.
        //
        // The origin is the bottom-centre of the body in EVERY frame -- it is
        // where the capsule collider anchors, and it does not swing when the
        // frame turns, which is the whole property the old terminal lacked.
        // While seated it sits above the surface, so in the reported case it is
        // clear and the camera ends up AT the rider: pushed out and closer to
        // the player, which is what was asked for.
        Vec3 anchor = new Vec3(entityX, entityY, entityZ);
        Vec3 eye = new Vec3(entityX + eyeOffset.x(), entityY + eyeOffset.y(),
                entityZ + eyeOffset.z());

        // step runs to CAMERA_CLEAR_STEPS inclusive, so the anchor itself is
        // the last sample and is tested rather than assumed.
        Vec3 cleared = null;
        for (int step = 0; step <= CAMERA_CLEAR_STEPS; step++) {
            double t = (double) step / CAMERA_CLEAR_STEPS;
            Vec3 at = eye.add(anchor.subtract(eye).scale(t));
            if (!gravityunbound$insideBlock(focusedEntity, at)) {
                cleared = at;
                break;
            }
        }

        if (cleared == null) {
            // Everything from the eye to the body is solid. Fall back to the
            // UNROTATED eye, which is where vanilla would have put it -- and
            // probe that too rather than repeating the old mistake.
            Vec3 upright = new Vec3(entityX, entityY + currentCameraY, entityZ);
            cleared = gravityunbound$insideBlock(focusedEntity, upright) ? anchor : upright;
        }

        original.call(this, cleared.x, cleared.y, cleared.z);
    }

    @Inject(
            method = "Lnet/minecraft/client/Camera;setRotation(FF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;",
                    shift = At.Shift.AFTER,
                    remap = false
            )
    )
    private void inject_setRotation(CallbackInfo ci) {
        if (this.entity == null) {
            return;
        }

        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(this.entity);
        if (comp == null) {
            return;
        }

        // the render frame carries everything — including, while riding a
        // Valkyrien Skies ship, the sub-tick lead of the ship's DRAWN pose
        // over its tick pose (swing and twist alike; see the ship-relative
        // reconstruction in GravityCapabilityImpl.getRenderRotation). No
        // separate camera-side ship correction exists any more.
        if (!comp.isRenderDefault()) {
            Quaternionf gravityRotation = comp.getRenderRotation(this.gravityunbound$tickDelta);

            Quaternionf rotation = new Quaternionf(gravityRotation);
            rotation.conjugate();
            rotation.mul(this.rotation);
            this.rotation.set(rotation.x(), rotation.y(), rotation.z(), rotation.w());
        }
    }

    /**
     * Whether a point is inside a block a camera should not see through.
     *
     * <p>Tested in the world AND in the shipyard space of every nearby
     * Valkyrien Skies ship. A ship's blocks do not live where the ship is
     * drawn, so a raw world-space lookup over a deck answers air and a camera
     * buried in a hull is declared clear -- which is how a camera ends up
     * inside a ship while this function reports everything is fine.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean gravityunbound$insideBlock(Entity context, Vec3 point) {
        if (gravityunbound$solidAt(context, point)) {
            return true;
        }
        return gravityunbound$insideShipBlock(context, point);
    }

    /** The world-space half of {@link #gravityunbound$insideBlock}. */
    @org.spongepowered.asm.mixin.Unique
    private static boolean gravityunbound$solidAt(Entity context, Vec3 point) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(point);
        net.minecraft.world.level.block.state.BlockState state =
                context.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        net.minecraft.world.phys.shapes.VoxelShape shape =
                state.getCollisionShape(context.level(), pos);
        if (shape.isEmpty()) {
            return false;
        }
        return shape.move(pos.getX(), pos.getY(), pos.getZ())
                .bounds()
                .inflate(1.0E-6)
                .contains(point);
    }

    /**
     * The ship half: the point carried into the shipyard space of every ship
     * whose box contains it.
     *
     * <p>Built on {@code VsTyped.intersecting} plus {@code getWorldToShip},
     * the same idiom {@code EntityMixin}'s capsule scan uses, so there is one
     * way of asking this question in the mod rather than two.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean gravityunbound$insideShipBlock(Entity context, Vec3 point) {
        net.minecraft.world.phys.AABB probe =
                new net.minecraft.world.phys.AABB(point, point).inflate(1.0E-4);
        for (org.valkyrienskies.core.api.ships.Ship ship :
                net.camacraft.camafy.compat.vs.impl.VsTyped.intersecting(context.level(), probe)) {
            // The camera lives in the world as DRAWN this frame: the ship's blocks are where its
            // render pose puts them, not where its last tick pose did (15 blocks apart at 300 m/s).
            org.valkyrienskies.core.api.ships.properties.ShipTransform drawn =
                    net.camacraft.camafy.compat.vs.impl.VsTyped.transform(ship, net.camacraft.camafy.compat.vs.ShipRef.Pose.RENDER);
            org.joml.Matrix4dc worldToShip = drawn != null ? drawn.getWorldToShip() : ship.getWorldToShip();
            org.joml.Vector3d inShip = worldToShip
                    .transformPosition(new org.joml.Vector3d(point.x, point.y, point.z));
            if (gravityunbound$solidAt(context, new Vec3(inShip.x, inShip.y, inShip.z))) {
                return true;
            }
        }
        return false;
    }

    /**
     * THE THIRD-PERSON VIEW RAYS, CLIPPED AGAINST SHIPS WHERE THEY ARE DRAWN. Vanilla shortens the
     * third-person zoom with eight rays from the eye back past the camera, clipped through
     * {@code level.clip}. Valkyrien Skies replaces that clip with one that also tries every ship
     * whose box the rays cross - and finds those ships by their TICK pose while it clips them at
     * their drawn pose. On a ship doing 300 m/s (15 blocks a tick) the drawn pose is up to a tick
     * of motion away from the tick pose, so a camera standing inside a room on that ship falls
     * outside the ship's tick box on some frames and inside it on others: those frames clip the
     * rays against the room's wall and the others don't, and the zoom snapped between 2.5 and 4
     * blocks frame by frame (the owner's stutter, watched from behind). Here the rays are clipped
     * against the world alone (VS's own vanilla clip) and then against every ship near the rays,
     * found with room to spare and clipped in its shipyard at its DRAWN pose, with the hit
     * carried back out through the same pose; the nearest hit wins.
     */
    @WrapOperation(
            method = "getMaxZoom",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/BlockGetter;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"
            )
    )
    private net.minecraft.world.phys.BlockHitResult gravityunbound$clipShipsAsDrawn(
            BlockGetter area, net.minecraft.world.level.ClipContext context,
            Operation<net.minecraft.world.phys.BlockHitResult> original) {
        if (!(area instanceof net.minecraft.world.level.Level level)
                || !net.camacraft.camafy.compat.vs.Ships.available()) {
            return original.call(area, context);
        }
        Vec3 from = context.getFrom();
        Vec3 to = context.getTo();
        net.minecraft.world.phys.BlockHitResult best;
        double bestDistance;
        try {
            best = org.valkyrienskies.mod.common.world.RaycastUtilsKt.vanillaClip(level, context);
        } catch (Throwable t) {
            return original.call(area, context);
        }
        bestDistance = best.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                ? Double.MAX_VALUE : best.getLocation().distanceTo(from);
        // Ships are found by their tick pose: look well past the rays so a fast ship's drawn
        // pose is still inside the search (a tick of motion at 500 m/s is 25 blocks).
        net.minecraft.world.phys.AABB near = new net.minecraft.world.phys.AABB(from, to).inflate(32.0);
        for (org.valkyrienskies.core.api.ships.LoadedShip ship :
                net.camacraft.camafy.compat.vs.impl.VsTyped.intersecting(level, near)) {
            org.valkyrienskies.core.api.ships.properties.ShipTransform drawn =
                    net.camacraft.camafy.compat.vs.impl.VsTyped.transform(ship, net.camacraft.camafy.compat.vs.ShipRef.Pose.RENDER);
            if (drawn == null) continue;
            org.joml.Vector3d f = drawn.getWorldToShip().transformPosition(new org.joml.Vector3d(from.x, from.y, from.z));
            org.joml.Vector3d t = drawn.getWorldToShip().transformPosition(new org.joml.Vector3d(to.x, to.y, to.z));
            net.minecraft.world.phys.BlockHitResult hit;
            try {
                hit = org.valkyrienskies.mod.common.world.RaycastUtilsKt.vanillaClip(level,
                        new net.minecraft.world.level.ClipContext(new Vec3(f.x, f.y, f.z), new Vec3(t.x, t.y, t.z),
                                net.minecraft.world.level.ClipContext.Block.VISUAL,
                                net.minecraft.world.level.ClipContext.Fluid.NONE, this.entity));
            } catch (Throwable e) {
                continue;
            }
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) continue;
            org.joml.Vector3d back = drawn.getShipToWorld().transformPosition(
                    new org.joml.Vector3d(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z));
            Vec3 where = new Vec3(back.x, back.y, back.z);
            double distance = where.distanceTo(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new net.minecraft.world.phys.BlockHitResult(where, hit.getDirection(), hit.getBlockPos(), hit.isInside());
            }
        }
        return best;
    }

    /** How many times the camera is walked back towards the body. */
    @org.spongepowered.asm.mixin.Unique
    private static final int CAMERA_CLEAR_STEPS = 6;

    /** A render rotation with |w| at least this is upright (under a tenth of a degree off). */
    @org.spongepowered.asm.mixin.Unique
    private static final float UPRIGHT_W = 0.9999995F;
}
