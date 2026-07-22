package net.irisshaders.iris.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.VulkanFrameState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Sodium's u_Globals projection in the shader-pack forward-Z convention. */
@Mixin(ChunkRenderMatrices.class)
public class VKOnly_MixinChunkRenderMatrices {
	@ModifyReturnValue(method = "projection", at = @At("RETURN"), remap = false)
	private Matrix4fc iris$normalizeShaderpackProjection(Matrix4fc projection) {
		return IrisVulkan.isShaderPackDepthMode()
			? VulkanFrameState.normalizeSodiumProjection(projection)
			: projection;
	}
}
