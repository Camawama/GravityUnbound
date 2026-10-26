package net.camacraft.gravityunbound.network;

import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.camacraft.camafy.compat.genesis.Celestials;
import net.camacraft.gravityunbound.celestial.CelestialGravity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C: the planet the receiving player last left for VS Genesis's space and when (Camafy's departure record, made at
 * Genesis's teleporter on the server), or none. The client moves its own player and holds that planet's pull off for
 * the departure grace ({@link CelestialGravity}); without this it could only guess from its own changes of world, and
 * an admin's teleport into space read as a departure.
 */
public class CelestialDeparturePacket {
    private final @Nullable ResourceLocation body;
    private final long gameTime;

    public CelestialDeparturePacket(@Nullable Celestials.Departure departure) {
        this.body = departure == null ? null : departure.body();
        this.gameTime = departure == null ? 0L : departure.gameTime();
    }

    public CelestialDeparturePacket(FriendlyByteBuf buf) {
        this.body = buf.readBoolean() ? buf.readResourceLocation() : null;
        this.gameTime = buf.readLong();
    }

    public static void encode(CelestialDeparturePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.body != null);
        if (msg.body != null) {
            buf.writeResourceLocation(msg.body);
        }
        buf.writeLong(msg.gameTime);
    }

    public static class Handler {
        public static void onMessage(CelestialDeparturePacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> CelestialGravity.setClientDeparture(
                msg.body == null ? null : new Celestials.Departure(msg.body, msg.gameTime)));
            ctx.get().setPacketHandled(true);
        }
    }
}
