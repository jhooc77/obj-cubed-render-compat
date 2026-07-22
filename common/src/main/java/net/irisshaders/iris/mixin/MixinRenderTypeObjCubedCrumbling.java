package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.data.AtlasIds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.LinkedHashMap;
import java.util.Map;

/** Binds the block atlas beside the destroy-stage texture for native obj-cubed cracks. */
@Mixin(RenderType.class)
public abstract class MixinRenderTypeObjCubedCrumbling {
	@ModifyExpressionValue(
		method = "draw",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderSetup;getTextures()Ljava/util/Map;")
	)
	private Map<String, RenderSetup.TextureAndSampler> iris$bindObjCubedCrumblingAtlas(
		Map<String, RenderSetup.TextureAndSampler> original
	) {
		if (((RenderType) (Object) this).pipeline() != RenderPipelines.CRUMBLING
			|| !Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))
			|| original.containsKey("Sampler1")) {
			return original;
		}

		var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
		Map<String, RenderSetup.TextureAndSampler> textures = new LinkedHashMap<>(original);
		textures.put("Sampler1", new RenderSetup.TextureAndSampler(atlas.getTextureView(), atlas.getSampler()));
		return textures;
	}
}
