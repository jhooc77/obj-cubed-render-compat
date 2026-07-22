package net.irisshaders.iris.compat.sodium.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.pipeline.VulkanShadowPipelineCompiler;
import net.irisshaders.iris.vulkan.pipeline.VulkanShadowRenderState;
import net.irisshaders.iris.vulkan.pipeline.VulkanTerrainPipelineCompiler;
import net.irisshaders.iris.vulkan.pipeline.VulkanTerrainRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Routes Sodium 0.9's native Vulkan terrain submission through the active shader-pack targets. */
@Mixin(DefaultChunkRenderer.class)
public class VKOnly_MixinDefaultChunkRenderer {
	@Unique
	private TerrainRenderPass iris$currentTerrainPass;

	@Inject(method = "render", at = @At("HEAD"), remap = false)
	private void iris$captureTerrainPass(
		ChunkRenderMatrices matrices,
		ChunkRenderListIterable renderLists,
		TerrainRenderPass pass,
		CameraTransform camera,
		FogParameters fog,
		boolean useLocalIndices,
		GpuSampler terrainSampler,
		GpuBufferSlice globals,
		GpuBuffer sectionTimeInfo,
		CallbackInfo ci
	) {
		iris$currentTerrainPass = pass;
	}

	@Inject(method = "render", at = @At("RETURN"), remap = false)
	private void iris$clearTerrainPass(
		ChunkRenderMatrices matrices,
		ChunkRenderListIterable renderLists,
		TerrainRenderPass pass,
		CameraTransform camera,
		FogParameters fog,
		boolean useLocalIndices,
		GpuSampler terrainSampler,
		GpuBufferSlice globals,
		GpuBuffer sectionTimeInfo,
		CallbackInfo ci
	) {
		iris$currentTerrainPass = null;
	}

	@Redirect(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"),
		remap = false
	)
	private RenderPass iris$createShaderpackTerrainPass(
		CommandEncoder encoder,
		Supplier<String> label,
		GpuTextureView color,
		Optional<Vector4fc> clearColor,
		GpuTextureView depth,
		OptionalDouble clearDepth
	) {
		ChunkSectionLayerGroup group = iris$group();
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) return shadow.createTerrainRenderPass(encoder, label, group);
		VulkanTerrainRenderState world = IrisVulkan.getTerrainRenderState();
		return world == null
			? encoder.createRenderPass(label, color, clearColor, depth, clearDepth)
			: world.createTerrainRenderPass(encoder, label, color, clearColor, depth, clearDepth, group);
	}

	@Redirect(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),
		remap = false
	)
	private void iris$setShaderpackTerrainPipeline(RenderPass renderPass, RenderPipeline original) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) {
			shadow.setSodiumTerrainPipeline(renderPass, iris$shadowPass());
			return;
		}
		VulkanTerrainRenderState world = IrisVulkan.getTerrainRenderState();
		if (world == null) renderPass.setPipeline(original);
		else world.setSodiumTerrainPipeline(renderPass, iris$terrainPass());
	}

	@Redirect(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V"),
		remap = false
	)
	private void iris$bindShaderpackTerrainTexture(RenderPass renderPass, String name, GpuTextureView texture, GpuSampler sampler) {
		VulkanShadowRenderState shadow = IrisVulkan.getActiveShadowRenderState();
		if (shadow != null) {
			shadow.bindSodiumTerrainTexture(renderPass, name, texture, sampler);
			return;
		}
		VulkanTerrainRenderState world = IrisVulkan.getTerrainRenderState();
		if (world == null) renderPass.bindTexture(name, texture, sampler);
		else world.bindSodiumTerrainTexture(renderPass, name, texture, sampler);
	}

	@Unique
	private ChunkSectionLayerGroup iris$group() {
		return iris$currentTerrainPass == DefaultTerrainRenderPasses.TRANSLUCENT
			? ChunkSectionLayerGroup.TRANSLUCENT : ChunkSectionLayerGroup.OPAQUE;
	}

	@Unique
	private VulkanTerrainPipelineCompiler.TerrainPass iris$terrainPass() {
		if (iris$currentTerrainPass == DefaultTerrainRenderPasses.SOLID) return VulkanTerrainPipelineCompiler.TerrainPass.SOLID;
		if (iris$currentTerrainPass == DefaultTerrainRenderPasses.CUTOUT) return VulkanTerrainPipelineCompiler.TerrainPass.CUTOUT;
		if (iris$currentTerrainPass == DefaultTerrainRenderPasses.TRANSLUCENT) return VulkanTerrainPipelineCompiler.TerrainPass.TRANSLUCENT;
		throw new IllegalStateException("Unknown Sodium terrain pass: " + iris$currentTerrainPass);
	}

	@Unique
	private VulkanShadowPipelineCompiler.ShadowPass iris$shadowPass() {
		return switch (iris$terrainPass()) {
			case SOLID -> VulkanShadowPipelineCompiler.ShadowPass.SOLID;
			case CUTOUT -> VulkanShadowPipelineCompiler.ShadowPass.CUTOUT;
			case TRANSLUCENT -> VulkanShadowPipelineCompiler.ShadowPass.TRANSLUCENT;
		};
	}
}
