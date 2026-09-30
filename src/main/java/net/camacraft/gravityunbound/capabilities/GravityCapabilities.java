package net.camacraft.gravityunbound.capabilities;

import javax.annotation.Nonnull;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;

public class GravityCapabilities
{
	public static final Capability<IGravityCapability> GRAVITY = CapabilityManager.get(new CapabilityToken<>() {});

	public static void attachEntityCapability(AttachCapabilitiesEvent<Entity> e)
	{
		GravityCapabilityImpl impl = new GravityCapabilityImpl();
		impl.setEntity(e.getObject());
		Provider provider = new Provider(impl);
		// invalidate the handed-out LazyOptional when the entity's capabilities are invalidated
		e.addListener(provider::invalidate);
		e.addCapability(IGravityCapability.ID, provider);
	}

	/**
	 * THE CAPABILITY SURVIVES A REVIVE.
	 *
	 * Forge invalidates an entity's capabilities every time it is removed ({@code Entity.remove}
	 * calls {@code invalidateCaps} whatever the reason), and that includes a player changing
	 * dimension: {@code ServerPlayer.teleportTo} and {@code changeDimension} take the player out
	 * of the old level ({@code removePlayerImmediately}) and {@code revive()} it straight after,
	 * which makes its capabilities valid again. An invalidated LazyOptional never comes back,
	 * though, and this provider used to hold a single one: after any dimension change (a
	 * {@code /tp} into another dimension, travelling to VS Genesis's space) the SERVER's copy of a
	 * player had no gravity capability at all until the player relogged. Everything the server
	 * knows about that player's gravity went with it: the frame its client reports, a plate's
	 * hold (so Full Stop threw players off plated ships that sped up, and let a player's weight
	 * on its own ship's plating push that ship), Surface Cling's mirror. Measured in the real
	 * pack (Pack Harness, space plating scenarios): the server-side plate listed the player
	 * among its entities but never held it, because the player had no capability there.
	 *
	 * A fresh LazyOptional is handed out once the old one has been invalidated. The entity's
	 * own provider returns nothing while its capabilities are invalid, so this is only reached
	 * after the revive. The capability object itself (the player's state) is the same one
	 * throughout.
	 */
	private static final class Provider implements ICapabilitySerializable<CompoundTag>
	{
		private final GravityCapabilityImpl impl;
		private LazyOptional<IGravityCapability> inst;

		Provider(GravityCapabilityImpl impl)
		{
			this.impl = impl;
			this.inst = LazyOptional.of(() -> impl);
		}

		void invalidate()
		{
			inst.invalidate();
		}

		@Nonnull
		@Override
		public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing)
		{
			if (capability != GRAVITY)
			{
				return LazyOptional.empty();
			}
			if (!inst.isPresent())
			{
				inst = LazyOptional.of(() -> impl);
			}
			return inst.cast();
		}

		// NBT (de)serialization goes through the impl directly, not the LazyOptional:
		// Forge still saves entity NBT after caps are invalidated (e.g. dead players
		// during autosave), and the invalidated optional would throw.
		@Override
		public CompoundTag serializeNBT()
		{
			return impl.serializeNBT();
		}

		@Override
		public void deserializeNBT(CompoundTag nbt)
		{
			impl.deserializeNBT(nbt);
		}
	}
}
