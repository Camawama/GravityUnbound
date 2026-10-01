package net.camacraft.gravityunbound.network;

import net.camacraft.camafy.network.CamafyChannel;
import net.camacraft.gravityunbound.GravityAPI;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.simple.SimpleChannel;

public class GravityNetwork
{
	private static final String PROTOCOL_VERSION = "3";   // 2: the ship-frame vehicle report (2026-09-30); 3: ship-held riders' frames and headings (2026-10-01)
	// every message is registered with the one direction it may travel: one
	// arriving the other way is dropped before its handler runs
	public static final CamafyChannel NET = CamafyChannel.create(new ResourceLocation(GravityAPI.MODID, "gravityapi"), PROTOCOL_VERSION);
	/** The underlying Forge channel (kept for code that sends through it directly). */
	public static final SimpleChannel CHANNEL = NET.raw();
	
	public static void registerMessages()
	{
		// registration order is the wire id order (0..3): never reorder
		NET.clientbound(UpdateGravityCapabilityPacket.class, UpdateGravityCapabilityPacket::encode, UpdateGravityCapabilityPacket::new, UpdateGravityCapabilityPacket.Handler::onMessage);
		NET.serverbound(UpdateGravityBlockSettingsPacket.class, UpdateGravityBlockSettingsPacket::encode, UpdateGravityBlockSettingsPacket::new, UpdateGravityBlockSettingsPacket.Handler::onMessage);
		NET.serverbound(SurfaceClingTargetPacket.class, SurfaceClingTargetPacket::encode, SurfaceClingTargetPacket::new, SurfaceClingTargetPacket.Handler::onMessage);
		NET.serverbound(PlayerFrameSyncPacket.class, PlayerFrameSyncPacket::encode, PlayerFrameSyncPacket::new, PlayerFrameSyncPacket.Handler::onMessage);
		// 4: the vehicle a player drives, placed in its ship's frame (2026-09-30)
		NET.serverbound(ShipVehicleMotionPacket.class, ShipVehicleMotionPacket::encode, ShipVehicleMotionPacket::new, ShipVehicleMotionPacket.Handler::onMessage);
	}
	
    public static <MSG> void sendToServer(MSG message) 
    {
    	NET.toServer(message);
    }
    
    public static <MSG> void sendNonLocal(MSG msg, ServerPlayer player)
    {
    	NET.toPlayer(player, msg);
    }

    /** Every player tracking {@code entity}, and the entity itself if it is a player (server side only). */
    public static <MSG> void sendToTrackingAndSelf(Entity entity, MSG msg)
    {
    	NET.toTrackingAndSelf(entity, msg);
    }
}
