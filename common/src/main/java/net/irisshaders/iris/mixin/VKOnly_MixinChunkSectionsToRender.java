package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.pipeline.VulkanTerrainRenderState;
import net.irisshaders.iris.vulkan.pipeline.VulkanShadowRenderState;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

@Mixin(ChunkSectionsToRender.class)
public class VKOnly_MixinChunkSectionsToRender {
	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void iris$beginTerrainPhase(ChunkSectionLayerGroup group, GpuSampler groupSampler, CallbackInfo ci) {
		IrisVulkan.setRenderPhase(WorldRenderingPhase.fromTerrainRenderType(group));
	}

	@Inject(method = "renderGroup", at = @At("RETURN"))
	private void iris$endTerrainPhase(ChunkSectionLayerGroup group, GpuSampler groupSampler, CallbackInfo ci) {
		IrisVulkan.clearRenderPhase(WorldRenderingPhase.fromTerrainRenderType(group));
	}

	@Redirect(
		method = "renderGroup",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;")
	)
	private RenderPass iris$createTerrainRenderPass(
		CommandEncoder encoder,
		Supplier<String> label,
		GpuTextureView colorTexture,
		Optional<Vector4fc> clearColor,
		@Nullable GpuTextureView depthTexture,
		OptionalDouble clearDepth,
		ChunkSectionLayerGroup group,
		GpuSampler groupSampler
	) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) {
			return shadow.createTerrainRenderPass(encoder, label, group);
		}
		VulkanTerrainRenderState state = IrisVulkan.getTerrainRenderState();
		return state == null
			? encoder.createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth)
			: state.createTerrainRenderPass(encoder, label, colorTexture, clearColor, depthTexture, clearDepth, group);
	}

	@Redirect(
		method = "renderGroup",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V")
	)
	private void iris$setTerrainPipeline(RenderPass renderPass, RenderPipeline pipeline) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) {
			shadow.setTerrainPipeline(renderPass, pipeline);
			return;
		}
		VulkanTerrainRenderState state = IrisVulkan.getTerrainRenderState();
		if (state == null) {
			renderPass.setPipeline(pipeline);
		} else {
			state.setTerrainPipeline(renderPass, pipeline);
		}
	}

	@Redirect(
		method = "renderGroup",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V")
	)
	private void iris$bindTerrainTexture(RenderPass renderPass, String name, GpuTextureView textureView, GpuSampler sampler) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) {
			shadow.bindTerrainTexture(renderPass, name, textureView, sampler);
			return;
		}
		VulkanTerrainRenderState state = IrisVulkan.getTerrainRenderState();
		if (state == null) {
			renderPass.bindTexture(name, textureView, sampler);
		} else {
			state.bindTerrainTexture(renderPass, name, textureView, sampler);
		}
	}
}
