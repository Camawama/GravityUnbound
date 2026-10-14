package net.camacraft.gravityunbound;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;

import net.minecraftforge.fml.loading.LoadingModList;

public class GravityMixinPlugin implements IMixinConfigPlugin
{
    @Override
    public void onLoad(String mixinPackage)
    {
        MixinExtrasBootstrap.init();
        // MixinSquaredBootstrap.init(); // Removed because MixinSquared is now loaded via jarJar
    }

	@Override
	public String getRefMapperConfig()
	{
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) 
	{
		if(mixinClassName.equals("net.camacraft.gravityunbound.mixin.compat.ACEntityMixin") && LoadingModList.get().getModFileById("alexscaves") == null)
		{
			return false;
		}
		if(mixinClassName.equals("net.camacraft.gravityunbound.mixin.compat.AdAstraFluidMixin") && LoadingModList.get().getModFileById("ad_astra") == null)
		{
			return false;
		}
		if((mixinClassName.startsWith("net.camacraft.gravityunbound.mixin.compat.VModPhysgun")
			|| mixinClassName.startsWith("net.camacraft.gravityunbound.mixin.compat.VModSchematic")) && LoadingModList.get().getModFileById("the_vmod") == null)
		{
			return false;
		}
		if(mixinClassName.startsWith("net.camacraft.gravityunbound.mixin.compat.VanillaBackport") && LoadingModList.get().getModFileById("vanillabackport") == null)
		{
			return false;
		}
		if(mixinClassName.equals("net.camacraft.gravityunbound.mixin.compat.VSForgeNetworkingMixin"))
		{
			// Targets the lambda VS 2.4.11 compiled its Forge packet consumer to (see the mixin): only for that build.
			net.minecraftforge.fml.loading.moddiscovery.ModFileInfo vs = LoadingModList.get().getModFileById("valkyrienskies");
			String version = vs == null || vs.getMods().isEmpty() ? "" : vs.getMods().get(0).getVersion().toString();
			if(!version.startsWith("2.4.11"))
			{
				org.slf4j.LoggerFactory.getLogger("GravityUnbound").warn(
					"[GravityUnbound] Valkyrien Skies {} isn't the 2.4.11 build: VS's server packet handling stays on the network thread"
						+ " (its player-on-ship position updates can crash the server with a ConcurrentModificationException)", version);
				return false;
			}
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets)
	{
		
	}

	@Override
	public List<String> getMixins()
	{
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) 
	{
		
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
	{
		
	}
}
