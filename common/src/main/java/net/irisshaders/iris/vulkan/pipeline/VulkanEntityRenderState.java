package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vulkan.texture.VulkanCustomTextureManager;
import net.irisshaders.iris.vulkan.uniforms.VulkanShaderpackUniforms;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.data.AtlasIds;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/** Binds transformed entity shaders and obj-cubed's carrier sampler on Vulkan. */
public final class VulkanEntityRenderState implements AutoCloseable {
	private final GpuDevice device;
	private final VulkanEntityPipelineCompiler.CompiledEntityPipelines pipelines;
	private final VulkanTerrainRenderState worldTargets;
	private final Map<RenderPipeline, GpuBuffer> looseUniformBuffers = new HashMap<>();
	private final Map<RenderPipeline, VulkanShaderpackUniforms> uniformUploaders = new HashMap<>();
	private final GpuSampler fallbackSampler;
	private final Set<GpuTextureView> atlasViews;
	private @Nullable GpuTextureView currentDepthTexture;

	public VulkanEntityRenderState(
		GpuDevice device,
		VulkanEntityPipelineCompiler.CompiledEntityPipelines pipelines,
		VulkanTerrainRenderState worldTargets
	) {
		this.device = device;
		this.pipelines = pipelines;
		this.worldTargets = worldTargets;
		Set<GpuTextureView> currentAtlases = Collections.newSetFromMap(new IdentityHashMap<>());
		Minecraft.getInstance().getAtlasManager().forEach((id, atlas) -> currentAtlases.add(atlas.getTextureView()));
		this.atlasViews = Collections.unmodifiableSet(currentAtlases);
		this.fallbackSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
			FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
		pipelines.pipelines().forEach((original, pipeline) -> {
			int size = pipeline.shaders().looseUniformBufferSize();
			if (size > 0) {
				looseUniformBuffers.put(original, createZeroUniformBuffer(size, original));
				uniformUploaders.put(original, new VulkanShaderpackUniforms(
					pipeline.shaders(), worldTargets.customUniforms(), pipeline.alphaTest().reference(), false));
			}
		});
	}

	public boolean replaces(RenderPipeline original) {
		return pipelines.forOriginal(original) != null;
	}

	public RenderPass createRenderPass(
		CommandEncoder encoder,
		Supplier<String> label,
		GpuTextureView colorTexture,
		@Nullable GpuTextureView depthTexture,
		OptionalDouble clearDepth,
		RenderPipeline original
	) {
		return createRenderPass(encoder, label, colorTexture, depthTexture, clearDepth, original, List.of());
	}

	public RenderPass createRenderPass(
		CommandEncoder encoder,
		Supplier<String> label,
		GpuTextureView colorTexture,
		@Nullable GpuTextureView depthTexture,
		OptionalDouble clearDepth,
		RenderPipeline original,
		List<PreparedRenderType.Texture> textures
	) {
		VulkanEntityPipelineCompiler.CompiledEntityPipeline replacement = pipelines.forOriginal(original);
		if (replacement == null) {
			throw new IllegalArgumentException("No Vulkan entity pipeline for " + original.getLocation());
		}
		currentDepthTexture = usesDepthSampler(replacement)
			? worldTargets.snapshotDepth(encoder, depthTexture) : null;
		GpuBuffer uniforms = looseUniformBuffers.get(original);
		VulkanShaderpackUniforms uploader = uniformUploaders.get(original);
		if (uniforms != null && uploader != null) {
			GpuTextureView albedo = null;
			for (PreparedRenderType.Texture texture : textures) {
				if ("Sampler0".equals(texture.name())) {
					albedo = texture.textureView();
					break;
				}
			}
			int textureWidth = albedo == null ? 0 : albedo.getWidth(0);
			int textureHeight = albedo == null ? 0 : albedo.getHeight(0);
			boolean atlas = albedo != null && atlasViews.contains(albedo);
			uploader.update(
				encoder,
				uniforms,
				colorTexture.getWidth(0),
				colorTexture.getHeight(0),
				textureWidth,
				textureHeight,
				atlas ? textureWidth : 0,
				atlas ? textureHeight : 0
			);
		}
		return worldTargets.createEntityRenderPass(encoder, label, colorTexture, depthTexture, clearDepth, replacement.layout());
	}

	public void setPipeline(RenderPass renderPass, RenderPipeline original) {
		VulkanEntityPipelineCompiler.CompiledEntityPipeline replacement = pipelines.forOriginal(original);
		if (replacement == null) {
			renderPass.setPipeline(original);
			return;
		}
		renderPass.setPipeline(replacement.replacement());
		boolean waterShadowEnabled = replacement.shaders().samplers().contains("watershadow");
		GpuBuffer buffer = looseUniformBuffers.get(original);
		if (buffer != null && replacement.shaders().uniformBlocks().contains(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK)) {
			renderPass.setUniform(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK, buffer);
		}
		for (String shaderSampler : replacement.shaders().samplers()) {
			VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, shaderSampler);
			GpuTextureView selected = selectTexture(shaderSampler, "", null, waterShadowEnabled);
			GpuSampler selectedSampler = custom != null ? custom.sampler() : fallbackSampler;
			if (custom == null && worldTargets.shadowTextureView(shaderSampler, waterShadowEnabled) != null) {
				String type = replacement.shaders().samplerTypes().get(shaderSampler);
				GpuSampler shadowSampler = worldTargets.shadowSampler(shaderSampler,
					(type != null && type.toLowerCase(java.util.Locale.ROOT).contains("shadow")) || shaderSampler.endsWith("HW"),
					waterShadowEnabled);
				if (shadowSampler != null) selectedSampler = shadowSampler;
			}
			renderPass.bindTexture(shaderSampler, selected, selectedSampler);
		}
	}

	public void bindTexture(
		RenderPass renderPass,
		RenderPipeline original,
		String originalName,
		@Nullable GpuTextureView textureView,
		@Nullable GpuSampler sampler
	) {
		VulkanEntityPipelineCompiler.CompiledEntityPipeline replacement = pipelines.forOriginal(original);
		if (replacement == null) {
			renderPass.bindTexture(originalName, textureView, sampler);
			return;
		}
		boolean waterShadowEnabled = replacement.shaders().samplers().contains("watershadow");

		for (String shaderSampler : replacement.shaders().samplers()) {
			if (!shouldBindOn(shaderSampler, originalName)) continue;
			VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, shaderSampler);
			GpuTextureView selected = selectTexture(shaderSampler, originalName, textureView, waterShadowEnabled);
			GpuSampler selectedSampler = custom != null ? custom.sampler() : sampler;
			if (custom == null && worldTargets.shadowTextureView(shaderSampler, waterShadowEnabled) != null) {
				String type = replacement.shaders().samplerTypes().get(shaderSampler);
				GpuSampler shadowSampler = worldTargets.shadowSampler(shaderSampler,
					(type != null && type.toLowerCase(java.util.Locale.ROOT).contains("shadow")) || shaderSampler.endsWith("HW"),
					waterShadowEnabled);
				if (shadowSampler != null) selectedSampler = shadowSampler;
			}
			renderPass.bindTexture(shaderSampler, selected, selectedSampler);
		}
	}

	private static boolean shouldBindOn(String shaderSampler, String originalName) {
		if ("Sampler0".equals(originalName)) {
			return !shaderSampler.equals("iris_overlay") && !shaderSampler.equals("Sampler1") && !isLightmap(shaderSampler);
		}
		if ("Sampler1".equals(originalName)) return shaderSampler.equals("iris_overlay") || shaderSampler.equals("Sampler1");
		if ("Sampler2".equals(originalName)) return isLightmap(shaderSampler);
		return shaderSampler.equals(originalName);
	}

	private GpuTextureView selectTexture(String shaderSampler, String originalName,
									 @Nullable GpuTextureView originalTexture, boolean waterShadowEnabled) {
		if (shaderSampler.equals("iris_ObjCubedAtlasSampler")) {
			return Minecraft.getInstance().getAtlasManager()
				.getAtlasOrThrow(AtlasIds.BLOCKS).getTextureView();
		}
		VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, shaderSampler);
		if (custom != null) return custom.view();
		if ("Sampler0".equals(originalName) && isAlbedo(shaderSampler) && originalTexture != null) {
			return originalTexture;
		}
		if ("Sampler1".equals(originalName) && ("iris_overlay".equals(shaderSampler) || "Sampler1".equals(shaderSampler)) && originalTexture != null) {
			return originalTexture;
		}
		if ("Sampler2".equals(originalName) && isLightmap(shaderSampler) && originalTexture != null) {
			return originalTexture;
		}

		Integer logicalTarget = VulkanTerrainRenderState.logicalColorTarget(shaderSampler);
		if (logicalTarget != null) {
			GpuTextureView target = worldTargets.getColorSampleTextureView(logicalTarget);
			if (target != null) {
				return target;
			}
		}
		if ((shaderSampler.equals("depthtex0") || shaderSampler.equals("depthtex1") || shaderSampler.equals("depthtex2")
			|| shaderSampler.equals("gdepthtex")) && currentDepthTexture != null) {
			return worldTargets.depthTextureView(shaderSampler, currentDepthTexture);
		}
		GpuTextureView shadow = worldTargets.shadowTextureView(shaderSampler, waterShadowEnabled);
		if (shadow != null) return shadow;
		return worldTargets.fallbackTextureView();
	}

	private static boolean isAlbedo(String sampler) {
		return sampler.equals("gtexture") || sampler.equals("texture") || sampler.equals("tex")
			|| sampler.equals("u_MainSampler") || sampler.equals("iris_ObjCubedSampler") || sampler.equals("Sampler0");
	}

	private static boolean isLightmap(String sampler) {
		return sampler.equals("lightmap") || sampler.equals("Sampler2");
	}

	private static boolean usesDepthSampler(VulkanEntityPipelineCompiler.CompiledEntityPipeline pipeline) {
		List<String> samplers = pipeline.shaders().samplers();
		return samplers.contains("depthtex0") || samplers.contains("depthtex1")
			|| samplers.contains("depthtex2") || samplers.contains("gdepthtex");
	}

	private GpuBuffer createZeroUniformBuffer(int size, RenderPipeline original) {
		ByteBuffer zeroes = MemoryUtil.memCalloc(size);
		try {
			return device.createBuffer(
				() -> "Iris Vulkan uniforms for " + original.getLocation(),
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				zeroes
			);
		} finally {
			MemoryUtil.memFree(zeroes);
		}
	}

	@Override
	public void close() {
		looseUniformBuffers.values().forEach(GpuBuffer::close);
		looseUniformBuffers.clear();
		uniformUploaders.values().forEach(VulkanShaderpackUniforms::close);
		uniformUploaders.clear();
		fallbackSampler.close();
		currentDepthTexture = null;
	}
}
