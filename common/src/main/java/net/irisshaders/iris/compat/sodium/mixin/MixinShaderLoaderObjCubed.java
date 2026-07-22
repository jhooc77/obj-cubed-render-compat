package net.irisshaders.iris.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.irisshaders.iris.pipeline.transform.ObjCubedShaderInjector;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adds obj-cubed decoding to Sodium's native terrain program when shader packs are disabled. */
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class MixinShaderLoaderObjCubed {
	@ModifyReturnValue(method = "getShaderSource", at = @At("RETURN"))
	private static String iris$injectObjCubedNativeTerrain(String source, Identifier id) {
		if (!"sodium".equals(id.getNamespace()) || !"blocks/block_layer_opaque.vsh".equals(id.getPath())) {
			return source;
		}
		return ObjCubedShaderInjector.injectRawSodiumTerrain(source);
	}
}
