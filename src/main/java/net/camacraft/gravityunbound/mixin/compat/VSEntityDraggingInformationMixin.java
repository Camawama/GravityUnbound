package net.camacraft.gravityunbound.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;

/**
 * A SHIP-CARRIED ENTITY SEEN BY ANOTHER CLIENT MUST KEEP MOVING.
 *
 * <h2>The defect</h2>
 *
 * <p>Reported in multiplayer: a friend walked into a ship-mounted core's
 * field and, on everyone else's screen, stopped dead at the edge of it. Their
 * head and gravity frame kept turning; their position never changed again
 * until they left the field.
 *
 * <p>Verified against the Valkyrien Skies 2.4.11 bytecode. While the SERVER
 * considers an entity dragged by a ship, VS's ServerEntity mixin
 * ({@code feature.entity_movement_packets.MixinServerEntity}) swallows the
 * vanilla move/teleport/motion packets for it and sends a ship-relative
 * {@code PacketEntityShipMotion} instead. On a watching client, that packet's
 * handler only STORES the target for a living entity; the position is applied
 * later by {@code EntityLerper.lerpStep} (from {@code MixinLivingEntity.preAiStep}),
 * and lerpStep does nothing unless the WATCHING client's own copy
 * {@code isEntityBeingDraggedByAShip()}: {@code ticksSinceStoodOnShip < 25}.
 *
 * <p>The watcher's copy never walks, so the only things that restart its
 * window are VS's {@code postBaseTick}, which looks up a ship block under
 * {@code getOnPos()}, and a packet naming a DIFFERENT ship than before. Under
 * a rotated frame our {@code EntityMixin.inject_getOnPos} probes along the
 * frame's down and returns a WORLD position. VS then finds neither a shipyard
 * block nor a solid world block there, so the window just runs out. It runs
 * out mid-air too: a jump or a zero-g float inside the field never "stands"
 * at all. Meanwhile the server keeps the drag alive for as long as the player
 * is in the field: the capsule registers ship ground every tick
 * ({@code EntityMixin}), and an airborne attached player is re-registered
 * every tick ({@code GravityCapabilityImpl.updateShipAttachment}). So the
 * packets kept coming, all of them same-ship, and the watcher dropped every
 * one. Once frozen it could not recover: the frozen copy's position never
 * moves somewhere that {@code getOnPos} would find the ship. Head turns kept
 * arriving because {@code lerpHeadStep} has no such gate. Leaving the field
 * ended the server-side drag, vanilla packets resumed, and the friend
 * reappeared.
 *
 * <h2>The fix</h2>
 *
 * <p>Every {@code PacketEntityShipMotion} is the server saying "this entity
 * is being dragged by ship X right now": MixinServerEntity sends it only
 * under exactly that condition. Treat it as that, and restart the watcher's
 * drag window whenever one arrives. The hook is
 * {@code setLastShipStoodOnServerWriteOnly}, which in 2.4.11 the client
 * handler for that packet calls first thing for every packet (on remote
 * entities only), and which nothing else calls. With a steady packet stream
 * this changes nothing where VS already detects the standing itself, because
 * that detection resets the same counter every tick. After the last packet
 * the window still closes 25 ticks later, as before.
 *
 * <p>{@code require = 0}: the dependency range admits Valkyrien Skies
 * versions older than the 2.4.11 this was verified against. If one lacks the
 * method, the fix is skipped instead of failing at startup.
 */
@Mixin(value = EntityDraggingInformation.class, remap = false)
public abstract class VSEntityDraggingInformationMixin {

    @Inject(
        method = "setLastShipStoodOnServerWriteOnly",
        at = @At("TAIL"),
        remap = false,
        require = 0
    )
    private void gravityunbound$serverSaysDragged(Long shipId, CallbackInfo ci) {
        if (shipId != null) {
            ((EntityDraggingInformation) (Object) this).setTicksSinceStoodOnShip(0);
        }
    }
}
