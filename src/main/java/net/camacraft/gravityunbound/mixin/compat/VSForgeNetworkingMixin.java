package net.camacraft.gravityunbound.mixin.compat;

import io.netty.buffer.ByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.core.internal.hooks.VsiCoreHooksIn;
import org.valkyrienskies.core.internal.world.VsiPlayer;
import org.valkyrienskies.mod.forge.common.VSForgeNetworking;

/**
 * Valkyrien Skies handles the packets its clients send on the network thread. Its Forge channel
 * consumer ({@code VSForgeNetworking.registerPacketHandlers}) calls the core's
 * {@code onReceiveServer} straight from Forge's dispatch, which runs on the Netty IO thread, and
 * never hands the work to the server thread the way every vanilla packet (and every Forge channel
 * that uses {@code enqueueWork}) does. One of those handlers, {@code PacketPlayerShipMotion}'s,
 * calls {@code ServerPlayer.setPos} with the position the client reports on its ship, once a tick
 * for every player standing on a ship. A {@code setPos} moves the player between the level's entity
 * sections (a remove and an add on their lists) - on the Netty thread, while the server thread may
 * be walking those very lists for anyone's {@code Level.getEntities}. When it is, the walk throws
 * {@code ConcurrentModificationException} out of whatever ticked, and the server dies with
 * "Ticking entity" (the owner's crash of 2026-09-30: an Automobility car on the plating of a fast
 * ship; MKW's ground probe looks for entities around the car every tick, so it was the one to be
 * caught, but any entity query could have been).
 *
 * <p>Measured in the real pack (Pack Harness, the section watch in its {@code EntitySectionMixin}):
 * a player standing on a ship moved the entity sections from the "Netty Server IO" thread 2,536
 * times over seven short scenarios, every one from that handler; nothing else wrote them off-thread.
 *
 * <p>This runs VS's server-side packet handling on the server thread instead: the packet's buffer
 * is kept alive across the hand-off and released after. Order is kept (the server's task queue is
 * the one vanilla's own packets go through, so a VS packet sent right after a move packet is still
 * handled right after it). The client side is left as VS has it.
 *
 * <p>The client's side is the same story one level down: {@code onReceiveClient} ran on the Netty
 * client IO thread, and VS's client handler for an entity's ship motion moves the entity there
 * (the harness caught it moving a dropped item between the client level's entity sections off the
 * render thread), which can throw the same exception out of any entity query the render thread
 * runs. Those are handed to the client's own task queue, where every vanilla packet goes.
 *
 * <p>The target is the Kotlin lambda VS compiled its consumer to, so this is pinned to the VS build
 * the pack uses; {@code GravityMixinPlugin} applies it only for that build and says so in the log
 * when it doesn't.
 */
@Mixin(value = VSForgeNetworking.class, remap = false)
public abstract class VSForgeNetworkingMixin {

    @Redirect(
        method = "registerPacketHandlers$lambda$3",
        at = @At(
            value = "INVOKE",
            target = "Lorg/valkyrienskies/core/internal/hooks/VsiCoreHooksIn;onReceiveServer(Lio/netty/buffer/ByteBuf;Lorg/valkyrienskies/core/internal/world/VsiPlayer;)V"
        ),
        remap = false
    )
    private static void gravityunbound$receiveOnServerThread(VsiCoreHooksIn hooks, ByteBuf buf, VsiPlayer sender) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.isSameThread()) {
            hooks.onReceiveServer(buf, sender);
            return;
        }
        buf.retain();
        server.execute(() -> {
            try {
                hooks.onReceiveServer(buf, sender);
            } finally {
                buf.release();
            }
        });
    }

    @Redirect(
        method = "registerPacketHandlers$lambda$3",
        at = @At(
            value = "INVOKE",
            target = "Lorg/valkyrienskies/core/internal/hooks/VsiCoreHooksIn;onReceiveClient(Lio/netty/buffer/ByteBuf;)V"
        ),
        remap = false
    )
    private static void gravityunbound$receiveOnRenderThread(VsiCoreHooksIn hooks, ByteBuf buf) {
        // only ever reached on a client (the branch VS takes when the packet has no sender)
        net.minecraft.util.thread.BlockableEventLoop<?> client =
            net.minecraftforge.common.util.LogicalSidedProvider.WORKQUEUE.get(net.minecraftforge.fml.LogicalSide.CLIENT);
        if (client == null || client.isSameThread()) {
            hooks.onReceiveClient(buf);
            return;
        }
        buf.retain();
        client.execute(() -> {
            try {
                hooks.onReceiveClient(buf);
            } finally {
                buf.release();
            }
        });
    }
}
