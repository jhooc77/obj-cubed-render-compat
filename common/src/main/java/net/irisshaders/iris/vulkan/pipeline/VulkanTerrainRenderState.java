package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.VulkanFrameState;
import net.irisshaders.iris.vulkan.VulkanDiagnostics;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vulkan.texture.VulkanCustomTextureManager;
import net.irisshaders.iris.vulkan.uniforms.VulkanShaderpackUniforms;
import net.irisshaders.iris.vulkan.uniforms.VulkanCustomUniforms;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.joml.Vector2i;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/** Owns the Vulkan resources needed while Minecraft submits chunk terrain draws. */
public final class VulkanTerrainRenderState implements AutoCloseable {
	private final GpuDevice device;
	private final VulkanTerrainPipelineCompiler.CompiledTerrainPipelines pipelines;
	private final VulkanCustomTextureManager customTextures;
	private final VulkanCustomUniforms customUniforms;
	private final Set<String> samplers;
	private final Set<Integer> terrainRequiredTargets;
	private final EnumMap<VulkanTerrainPipelineCompiler.TerrainPass, GpuBuffer> looseUniformBuffers =
		new EnumMap<>(VulkanTerrainPipelineCompiler.TerrainPass.class);
	private final EnumMap<VulkanTerrainPipelineCompiler.TerrainPass, VulkanShaderpackUniforms> uniformUploaders =
		new EnumMap<>(VulkanTerrainPipelineCompiler.TerrainPass.class);
	private final EnumMap<VulkanTerrainPipelineCompiler.TerrainPass, GpuBuffer> sodiumLooseUniformBuffers =
		new EnumMap<>(VulkanTerrainPipelineCompiler.TerrainPass.class);
	private final EnumMap<VulkanTerrainPipelineCompiler.TerrainPass, VulkanShaderpackUniforms> sodiumUniformUploaders =
		new EnumMap<>(VulkanTerrainPipelineCompiler.TerrainPass.class);
	private final GpuTexture fallbackTexture;
	private final GpuTextureView fallbackTextureView;
	private final GpuTexture fallbackDepthTexture;
	private final GpuTextureView fallbackDepthTextureView;
	private final Map<Integer, GpuTexture> colorTextures = new HashMap<>();
	private final Map<Integer, GpuTextureView> colorTextureViews = new HashMap<>();
	private final Map<Integer, GpuTextureView> colorSampleTextureViews = new HashMap<>();
	private final Map<Integer, GpuTexture> alternateColorTextures = new HashMap<>();
	private final Map<Integer, GpuTextureView> alternateColorTextureViews = new HashMap<>();
	private final Map<Integer, GpuTextureView> alternateColorSampleTextureViews = new HashMap<>();
	private final Set<Integer> clearedTargets = new HashSet<>();
	private final Set<Integer> flippedTargets = new HashSet<>();
	private Set<Integer> mipmappedTargets = Set.of();
	private @Nullable VulkanShadowRenderState shadowRenderState;
	private @Nullable GpuTexture sampledDepthTexture;
	private @Nullable GpuTextureView sampledDepthTextureView;
	private final GpuTexture[] stagedDepthTextures = new GpuTexture[3];
	private final GpuTextureView[] stagedDepthTextureViews = new GpuTextureView[3];
	private int width = -1;
	private int height = -1;
	private boolean currentWaterShadowEnabled;
	private int diagnosticFramesRemaining = VulkanDiagnostics.enabled() ? 3 : 0;
	private int lastUniformUpdateFrame = Integer.MIN_VALUE;
	private int lastUniformWidth = -1;
	private int lastUniformHeight = -1;

	public VulkanTerrainRenderState(
		GpuDevice device,
		VulkanTerrainPipelineCompiler.CompiledTerrainPipelines pipelines,
		VulkanCustomTextureManager customTextures,
		VulkanCustomUniforms customUniforms
	) {
		this.device = device;
		this.pipelines = pipelines;
		this.customTextures = customTextures;
		this.customUniforms = customUniforms;
		this.samplers = collectSamplers(pipelines);
		Set<Integer> baseTargets = new HashSet<>();
		pipelines.layouts().values().forEach(layout -> {
			for (int target : layout.logicalTargets()) baseTargets.add(target);
		});
		this.terrainRequiredTargets = Set.copyOf(baseTargets);

		pipelines.pipelines().forEach((pass, pipeline) -> {
			int size = pipeline.shaders().looseUniformBufferSize();
			if (size > 0) {
				looseUniformBuffers.put(pass, createZeroUniformBuffer(size, pass));
				uniformUploaders.put(pass, new VulkanShaderpackUniforms(
					pipeline.shaders(), customUniforms, pipeline.alphaTest().reference(), false));
			}
		});
		pipelines.sodiumPipelines().forEach((pass, pipeline) -> {
			int size = pipeline.shaders().looseUniformBufferSize();
			if (size > 0) {
				sodiumLooseUniformBuffers.put(pass, createZeroUniformBuffer(size, "sodium_" + pass.name().toLowerCase()));
				sodiumUniformUploaders.put(pass, new VulkanShaderpackUniforms(
					pipeline.shaders(), customUniforms, pipeline.alphaTest().reference(), false));
			}
		});

		this.fallbackTexture = device.createTexture("Iris Vulkan fallback sampler", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
		this.fallbackTextureView = device.createTextureView(fallbackTexture);
		ByteBuffer whitePixel = MemoryUtil.memAlloc(4);
		try {
			whitePixel.putInt(-1).flip();
			device.createCommandEncoder().writeToTexture(fallbackTexture, whitePixel, 0, 0, 0, 0, 1, 1);
		} finally {
			MemoryUtil.memFree(whitePixel);
		}
		this.fallbackDepthTexture = device.createTexture(
			"Iris Vulkan fallback depth sampler",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
			GpuFormat.D32_FLOAT,
			1,
			1,
			1,
			1
		);
		this.fallbackDepthTextureView = device.createTextureView(fallbackDepthTexture);
		// The Vulkan shader-pack path globally converts vanilla reverse-Z clear values.
		device.createCommandEncoder().clearDepthTexture(fallbackDepthTexture, 0.0);
	}

	public VulkanCustomUniforms customUniforms() {
		return customUniforms;
	}

	/** Configures render targets which need a complete chain before the first allocation. */
	public void setMipmappedTargets(Set<Integer> targets) {
		Set<Integer> requested = Set.copyOf(targets);
		if (requested.equals(mipmappedTargets)) return;
		mipmappedTargets = requested;
		if (!colorTextures.isEmpty()) closeColorTargets();
	}

	public RenderPass createTerrainRenderPass(
		CommandEncoder encoder,
		Supplier<String> label,
		GpuTextureView colorTexture,
		Optional<Vector4fc> clearColor,
		@Nullable GpuTextureView depthTexture,
		OptionalDouble clearDepth,
		ChunkSectionLayerGroup group
	) {
		ensureColorTargets(colorTexture.getWidth(0), colorTexture.getHeight(0), Set.of());
		updateLooseUniforms(encoder, colorTexture.getWidth(0), colorTexture.getHeight(0));
		VulkanTerrainPipelineCompiler.TargetLayout layout = pipelines.layouts().get(group);
		if (layout == null) {
			throw new IllegalStateException("No Vulkan render-target layout for terrain group " + group);
		}
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(label);
		for (int logicalTarget : layout.logicalTargets()) {
			VulkanTerrainPipelineCompiler.RenderTargetInfo info = pipelines.renderTargets().get(logicalTarget);
			Optional<Vector4fc> targetClear = Optional.empty();
			if (info.clear() && clearedTargets.add(logicalTarget)) {
				Vector4f value = info.clearColor().map(Vector4f::new).orElseGet(() -> defaultClearColor(logicalTarget));
				targetClear = Optional.of(value);
			}
			descriptor.withColorAttachment(getColorTextureView(logicalTarget), targetClear);
		}
		if (depthTexture != null) {
			descriptor.withDepthAttachment(depthTexture, clearDepth);
		}
		int renderWidth = attachmentWidth(layout.logicalTargets(), depthTexture, colorTexture.getWidth(0));
		int renderHeight = attachmentHeight(layout.logicalTargets(), depthTexture, colorTexture.getHeight(0));
		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, renderWidth, renderHeight));
		return encoder.createRenderPass(descriptor);
	}

	public RenderPass createEntityRenderPass(
		CommandEncoder encoder,
		Supplier<String> label,
		GpuTextureView colorTexture,
		@Nullable GpuTextureView depthTexture,
		OptionalDouble clearDepth,
		VulkanEntityPipelineCompiler.TargetLayout layout
	) {
		ensureColorTargets(colorTexture.getWidth(0), colorTexture.getHeight(0), layout.logicalTargets());
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(label);
		for (int logicalTarget : layout.logicalTargets()) {
			VulkanTerrainPipelineCompiler.RenderTargetInfo info = pipelines.renderTargets().get(logicalTarget);
			Optional<Vector4fc> targetClear = Optional.empty();
			if (info.clear() && clearedTargets.add(logicalTarget)) {
				Vector4f value = info.clearColor().map(Vector4f::new).orElseGet(() -> defaultClearColor(logicalTarget));
				targetClear = Optional.of(value);
			}
			descriptor.withColorAttachment(getColorTextureView(logicalTarget), targetClear);
		}
		if (depthTexture != null) {
			descriptor.withDepthAttachment(depthTexture, clearDepth);
		}
		int renderWidth = attachmentWidth(layout.logicalTargets(), depthTexture, colorTexture.getWidth(0));
		int renderHeight = attachmentHeight(layout.logicalTargets(), depthTexture, colorTexture.getHeight(0));
		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, renderWidth, renderHeight));
		return encoder.createRenderPass(descriptor);
	}

	public RenderPass createCompositeRenderPass(
		CommandEncoder encoder,
		Supplier<String> label,
		VulkanCompositePipelineCompiler.TargetLayout layout
	) {
		ensureColorTargets(width, height, layout.logicalTargets());
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(label);
		for (int logicalTarget : layout.logicalTargets()) {
			descriptor.withColorAttachment(getWriteColorTextureView(logicalTarget), Optional.empty());
		}
		var viewport = layout.viewport();
		int attachmentWidth = attachmentWidth(layout.logicalTargets(), null, width);
		int attachmentHeight = attachmentHeight(layout.logicalTargets(), null, height);
		int viewportX = Math.clamp((int)(width * viewport.viewportX()), 0, Math.max(0, attachmentWidth - 1));
		int viewportY = Math.clamp((int)(height * viewport.viewportY()), 0, Math.max(0, attachmentHeight - 1));
		int viewportWidth = Math.max(1, Math.min(attachmentWidth - viewportX, (int)(width * viewport.scale())));
		int viewportHeight = Math.max(1, Math.min(attachmentHeight - viewportY, (int)(height * viewport.scale())));
		descriptor.withRenderArea(new RenderPass.RenderArea(viewportX, viewportY, viewportWidth, viewportHeight));
		return encoder.createRenderPass(descriptor);
	}

	public void finishCompositePass(VulkanCompositePipelineCompiler.TargetLayout layout) {
		for (int logicalTarget : layout.flipTargets()) {
			flipTarget(logicalTarget);
		}
	}

	public void applyExplicitFlips(Map<Integer, Boolean> flips) {
		flips.forEach((target, shouldFlip) -> {
			if (Boolean.TRUE.equals(shouldFlip)) flipTarget(target);
		});
	}

	private void flipTarget(int logicalTarget) {
		if (!flippedTargets.add(logicalTarget)) flippedTargets.remove(logicalTarget);
	}

	private int attachmentWidth(int[] logicalTargets, @Nullable GpuTextureView depth, int fallback) {
		int result = depth == null ? fallback : Math.min(fallback, depth.getWidth(0));
		for (int target : logicalTargets) {
			GpuTextureView view = getColorTextureView(target);
			if (view != null) result = Math.min(result, view.getWidth(0));
		}
		return Math.max(1, result);
	}

	private int attachmentHeight(int[] logicalTargets, @Nullable GpuTextureView depth, int fallback) {
		int result = depth == null ? fallback : Math.min(fallback, depth.getHeight(0));
		for (int target : logicalTargets) {
			GpuTextureView view = getColorTextureView(target);
			if (view != null) result = Math.min(result, view.getHeight(0));
		}
		return Math.max(1, result);
	}

	private void updateLooseUniforms(CommandEncoder encoder, int width, int height) {
		int frame = VulkanFrameState.frameCounter();
		if (lastUniformUpdateFrame == frame && lastUniformWidth == width && lastUniformHeight == height) return;
		lastUniformUpdateFrame = frame;
		lastUniformWidth = width;
		lastUniformHeight = height;
		GpuTextureView atlas = Minecraft.getInstance().getAtlasManager()
			.getAtlasOrThrow(AtlasIds.BLOCKS).getTextureView();
		int atlasWidth = atlas == null ? 0 : atlas.getWidth(0);
		int atlasHeight = atlas == null ? 0 : atlas.getHeight(0);
		uniformUploaders.forEach((pass, uploader) -> uploader.update(
			encoder,
			looseUniformBuffers.get(pass),
			width,
			height,
			atlasWidth,
			atlasHeight,
			atlasWidth,
			atlasHeight,
			terrainPhase(pass).ordinal()
		));
		sodiumUniformUploaders.forEach((pass, uploader) -> uploader.update(
			encoder,
			sodiumLooseUniformBuffers.get(pass),
			width,
			height,
			atlasWidth,
			atlasHeight,
			atlasWidth,
			atlasHeight,
			terrainPhase(pass).ordinal()
		));
	}

	private static WorldRenderingPhase terrainPhase(VulkanTerrainPipelineCompiler.TerrainPass pass) {
		return switch (pass) {
			case SOLID -> WorldRenderingPhase.TERRAIN_SOLID;
			case CUTOUT -> WorldRenderingPhase.TERRAIN_CUTOUT;
			case TRANSLUCENT -> WorldRenderingPhase.TERRAIN_TRANSLUCENT;
		};
	}

	public void setTerrainPipeline(RenderPass renderPass, RenderPipeline original) {
		VulkanTerrainPipelineCompiler.CompiledTerrainPipeline replacement = pipelines.forOriginal(original);
		if (replacement == null) {
			currentWaterShadowEnabled = false;
			renderPass.setPipeline(original);
			return;
		}

		currentWaterShadowEnabled = replacement.shaders().samplers().contains("watershadow");
		renderPass.setPipeline(replacement.pipeline());
		GpuBuffer looseUniformBuffer = looseUniformBuffers.get(replacement.pass());
		if (looseUniformBuffer != null && replacement.shaders().uniformBlocks().contains(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK)) {
			renderPass.setUniform(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK, looseUniformBuffer);
		}
		// ChunkSectionsToRender binds the terrain atlas/lightmap once before it
		// selects the solid and cutout pipelines. Rebind shadow aliases here so
		// each program gets its own `shadow` versus `watershadow` resolution and
		// its own comparison-sampler type instead of inheriting the previous
		// terrain layer (or the previous frame's translucent water program).
		for (String shaderSampler : replacement.shaders().samplers()) {
			GpuTextureView shadowView = shadowTextureView(shaderSampler, currentWaterShadowEnabled);
			if (shadowView == null) continue;
			String type = replacement.shaders().samplerTypes().get(shaderSampler);
			boolean comparison = (type != null && isComparisonSampler(type)) || shaderSampler.endsWith("HW");
			GpuSampler shadowSampler = shadowSampler(shaderSampler, comparison, currentWaterShadowEnabled);
			if (shadowSampler != null) {
				renderPass.bindTexture(shaderSampler, shadowView, shadowSampler);
			}
		}
	}

	/** Selects the shader-pack pipeline which consumes Sodium's extended terrain buffer. */
	public void setSodiumTerrainPipeline(RenderPass renderPass, VulkanTerrainPipelineCompiler.TerrainPass pass) {
		VulkanTerrainPipelineCompiler.CompiledTerrainPipeline replacement = pipelines.forSodium(pass);
		if (replacement == null) {
			throw new IllegalStateException("No Vulkan Sodium terrain pipeline for " + pass);
		}
		currentWaterShadowEnabled = replacement.shaders().samplers().contains("watershadow");
		renderPass.setPipeline(replacement.pipeline());
		GpuBuffer looseUniformBuffer = sodiumLooseUniformBuffers.get(pass);
		if (looseUniformBuffer != null && replacement.shaders().uniformBlocks().contains(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK)) {
			renderPass.setUniform(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK, looseUniformBuffer);
		}
		bindShadowAliases(renderPass, replacement);
	}

	private void bindShadowAliases(RenderPass renderPass, VulkanTerrainPipelineCompiler.CompiledTerrainPipeline replacement) {
		for (String shaderSampler : replacement.shaders().samplers()) {
			GpuTextureView shadowView = shadowTextureView(shaderSampler, currentWaterShadowEnabled);
			if (shadowView == null) continue;
			String type = replacement.shaders().samplerTypes().get(shaderSampler);
			boolean comparison = (type != null && isComparisonSampler(type)) || shaderSampler.endsWith("HW");
			GpuSampler shadowSampler = shadowSampler(shaderSampler, comparison, currentWaterShadowEnabled);
			if (shadowSampler != null) renderPass.bindTexture(shaderSampler, shadowView, shadowSampler);
		}
	}

	public void bindTerrainTexture(RenderPass renderPass, String originalName, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
		renderPass.bindTexture(originalName, textureView, sampler);
		if ("Sampler0".equals(originalName)) {
			for (String shaderSampler : samplers) {
				if (isLightmap(shaderSampler) || shaderSampler.equals(originalName)) {
					continue;
				}
				VulkanCustomTextureManager.Binding custom = customTexture(TextureStage.GBUFFERS_AND_SHADOW, shaderSampler);
				GpuTextureView selected = custom != null ? custom.view()
					: isBlockAtlas(shaderSampler) && textureView != null ? textureView : selectPackTexture(shaderSampler);
				GpuSampler selectedSampler = custom != null ? custom.sampler() : selectPackSampler(shaderSampler, sampler);
				renderPass.bindTexture(shaderSampler, selected, selectedSampler);
			}
		} else if ("Sampler2".equals(originalName)) {
			for (String shaderSampler : samplers) {
				if (isLightmap(shaderSampler) && !shaderSampler.equals(originalName)) {
					VulkanCustomTextureManager.Binding custom = customTexture(TextureStage.GBUFFERS_AND_SHADOW, shaderSampler);
					renderPass.bindTexture(shaderSampler, custom == null ? textureView : custom.view(),
						custom == null ? sampler : custom.sampler());
				}
			}
		}
	}

	/** Rebinds Sodium's atlas/lightmap names to the sampler names used by shader packs. */
	public void bindSodiumTerrainTexture(RenderPass renderPass, String originalName, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
		if ("u_BlockTex".equals(originalName)) {
			for (String shaderSampler : samplers) {
				if (isLightmap(shaderSampler)) continue;
				VulkanCustomTextureManager.Binding custom = customTexture(TextureStage.GBUFFERS_AND_SHADOW, shaderSampler);
				GpuTextureView selected = custom != null ? custom.view()
					: isBlockAtlas(shaderSampler) && textureView != null ? textureView : selectPackTexture(shaderSampler);
				GpuSampler selectedSampler = custom != null ? custom.sampler() : selectPackSampler(shaderSampler, sampler);
				renderPass.bindTexture(shaderSampler, selected, selectedSampler);
			}
		} else if ("u_LightTex".equals(originalName)) {
			for (String shaderSampler : samplers) {
				if (!isLightmap(shaderSampler)) continue;
				VulkanCustomTextureManager.Binding custom = customTexture(TextureStage.GBUFFERS_AND_SHADOW, shaderSampler);
				renderPass.bindTexture(shaderSampler, custom == null ? textureView : custom.view(),
					custom == null ? sampler : custom.sampler());
			}
		}
	}

	private GpuTextureView selectPackTexture(String samplerName) {
		VulkanCustomTextureManager.Binding custom = customTexture(TextureStage.GBUFFERS_AND_SHADOW, samplerName);
		if (custom != null) return custom.view();
		GpuTextureView shadow = shadowTextureView(samplerName, currentWaterShadowEnabled);
		if (shadow != null) return shadow;
		Integer logicalTarget = logicalColorTarget(samplerName);
		if (logicalTarget != null) {
			GpuTextureView color = getColorSampleTextureView(logicalTarget);
			if (color != null) return color;
		}
		return samplerName.startsWith("depthtex") || samplerName.equals("gdepthtex")
			? depthTextureView(samplerName, null) : fallbackTextureView;
	}

	/** Resolves both modern colortex names and OptiFine's gcolor/gaux aliases. */
	public static @Nullable Integer logicalColorTarget(String samplerName) {
		if (samplerName.startsWith("colortex")) {
			try {
				return Integer.parseInt(samplerName.substring("colortex".length()));
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		int legacy = PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf(samplerName);
		return legacy >= 0 ? legacy : null;
	}

	private @Nullable GpuSampler selectPackSampler(String samplerName, @Nullable GpuSampler fallback) {
		VulkanCustomTextureManager.Binding custom = customTexture(TextureStage.GBUFFERS_AND_SHADOW, samplerName);
		if (custom != null) return custom.sampler();
		if (shadowTextureView(samplerName, currentWaterShadowEnabled) == null) return fallback;
		boolean comparison = java.util.stream.Stream.concat(
			pipelines.pipelines().values().stream(), pipelines.sodiumPipelines().values().stream())
			.map(pipeline -> pipeline.shaders().samplerTypes().get(samplerName))
			.filter(java.util.Objects::nonNull)
			.anyMatch(VulkanTerrainRenderState::isComparisonSampler);
		GpuSampler shadow = shadowSampler(samplerName, comparison || samplerName.endsWith("HW"), currentWaterShadowEnabled);
		return shadow == null ? fallback : shadow;
	}

	private static boolean isComparisonSampler(String type) {
		return type.toLowerCase(java.util.Locale.ROOT).contains("shadow");
	}

	private GpuBuffer createZeroUniformBuffer(int size, VulkanTerrainPipelineCompiler.TerrainPass pass) {
		return createZeroUniformBuffer(size, pass.name().toLowerCase());
	}

	private GpuBuffer createZeroUniformBuffer(int size, String name) {
		ByteBuffer zeroes = MemoryUtil.memCalloc(size);
		try {
			return device.createBuffer(() -> "Iris Vulkan " + name + " shader-pack uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, zeroes);
		} finally {
			MemoryUtil.memFree(zeroes);
		}
	}

	private void ensureColorTargets(int requestedWidth, int requestedHeight, Set<Integer> additionalTargets) {
		if (width == requestedWidth && height == requestedHeight
			&& colorTextures.keySet().containsAll(terrainRequiredTargets)
			&& colorTextures.keySet().containsAll(additionalTargets)) return;
		Set<Integer> requiredTargets = new HashSet<>(terrainRequiredTargets);
		// PackRenderTargetDirectives exposes settings for every supported slot (often
		// 0..31), not just slots used by this pack. Allocating the whole settings map
		// created hundreds of megabytes of unused double-buffered images on mobile.
		// Composite/entity paths add their actual sampler and draw targets lazily.
		requiredTargets.addAll(additionalTargets);
		allocateMissingColorTargets(requestedWidth, requestedHeight, requiredTargets);
	}

	private void ensureColorTargets(int requestedWidth, int requestedHeight, int[] additionalTargets) {
		boolean complete = width == requestedWidth && height == requestedHeight
			&& colorTextures.keySet().containsAll(terrainRequiredTargets);
		if (complete) {
			for (int target : additionalTargets) {
				if (!colorTextures.containsKey(target)) {
					complete = false;
					break;
				}
			}
		}
		if (complete) return;
		Set<Integer> requiredTargets = new HashSet<>(terrainRequiredTargets);
		for (int target : additionalTargets) requiredTargets.add(target);
		allocateMissingColorTargets(requestedWidth, requestedHeight, requiredTargets);
	}

	private void allocateMissingColorTargets(int requestedWidth, int requestedHeight, Set<Integer> requiredTargets) {
		if (width != requestedWidth || height != requestedHeight) {
			closeColorTargets();
			width = requestedWidth;
			height = requestedHeight;
		}
		CommandEncoder initializationEncoder = null;
		for (int logicalTarget : requiredTargets) {
			if (colorTextures.containsKey(logicalTarget)) {
				continue;
			}
			VulkanTerrainPipelineCompiler.RenderTargetInfo info = pipelines.renderTargets().get(logicalTarget);
			Vector2i dimensions = pipelines.packDirectives().getTextureScaleOverride(logicalTarget, width, height);
			int targetWidth = Math.max(1, dimensions.x());
			int targetHeight = Math.max(1, dimensions.y());
			int mipLevels = mipmappedTargets.contains(logicalTarget) ? mipLevels(targetWidth, targetHeight) : 1;
			GpuTexture texture = device.createTexture(
				"Iris Vulkan colortex" + logicalTarget,
				GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				info.format(),
				targetWidth,
				targetHeight,
				1,
				mipLevels
			);
			colorTextures.put(logicalTarget, texture);
			colorTextureViews.put(logicalTarget, device.createTextureView(texture, 0, 1));
			colorSampleTextureViews.put(logicalTarget, device.createTextureView(texture));
			GpuTexture alternate = device.createTexture(
				"Iris Vulkan colortex" + logicalTarget + " alternate",
				GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				info.format(),
				targetWidth,
				targetHeight,
				1,
				mipLevels
			);
			alternateColorTextures.put(logicalTarget, alternate);
			alternateColorTextureViews.put(logicalTarget, device.createTextureView(alternate, 0, 1));
			alternateColorSampleTextureViews.put(logicalTarget, device.createTextureView(alternate));

			// OpenGL Iris performs a full clear of both sides when render targets are
			// first allocated. Vulkan image contents are undefined after allocation,
			// so history buffers such as MakeUp's gaux3 must receive the same
			// deterministic initialization before their first sample.
			if (initializationEncoder == null) initializationEncoder = device.createCommandEncoder();
			Vector4f initialColor = bootstrapClearColor(logicalTarget, info);
			initializationEncoder.clearColorTexture(texture, initialColor);
			initializationEncoder.clearColorTexture(alternate, initialColor);
			Iris.logger.info(
				"Iris Vulkan target init: colortex{} {}x{} format={} clear={} color=({}, {}, {}, {})",
				logicalTarget, targetWidth, targetHeight, info.format(), info.clear(),
				initialColor.x, initialColor.y, initialColor.z, initialColor.w
			);
		}
		clearedTargets.clear();
		flippedTargets.clear();
	}

	public void ensureTargets(int requestedWidth, int requestedHeight, Set<Integer> targets) {
		ensureColorTargets(requestedWidth, requestedHeight, targets);
	}

	/** Drops per-world history while retaining the compiled pipeline and its stable target graph. */
	public void resetForWorld() {
		closeSampledDepth();
		closeStagedDepth(1);
		closeStagedDepth(2);
		clearedTargets.clear();
		flippedTargets.clear();
		currentWaterShadowEnabled = false;
		diagnosticFramesRemaining = VulkanDiagnostics.enabled() ? 3 : 0;
		lastUniformUpdateFrame = Integer.MIN_VALUE;
		lastUniformWidth = -1;
		lastUniformHeight = -1;
	}

	/** Starts a new shader-pack frame and applies Iris' render-target clear directives before begin passes. */
	public void beginWorldFrame(int requestedWidth, int requestedHeight) {
		ensureColorTargets(requestedWidth, requestedHeight, Set.of());
		clearedTargets.clear();
		flippedTargets.clear();
		CommandEncoder encoder = device.createCommandEncoder();
		boolean temporalBootstrap = VulkanFrameState.frameCounter() <= 2;
		colorTextures.forEach((target, main) -> {
			VulkanTerrainPipelineCompiler.RenderTargetInfo info = pipelines.renderTargets().get(target);
			if (info == null || (!temporalBootstrap && !info.clear())) return;
			Vector4f clear = temporalBootstrap
				? bootstrapClearColor(target, info)
				: info.clearColor().map(Vector4f::new).orElseGet(() -> defaultClearColor(target));
			GpuTexture alternate = alternateColorTextures.get(target);
			if (main != null) encoder.clearColorTexture(main, clear);
			if (alternate != null) encoder.clearColorTexture(alternate, clear);
			if (info.clear() && (main != null || alternate != null)) clearedTargets.add(target);
		});
		if (temporalBootstrap && VulkanDiagnostics.enabled()) {
			Iris.logger.info("Iris Vulkan temporal bootstrap reset: frame={}", VulkanFrameState.frameCounter());
		}
	}

	/**
	 * Carries non-cleared ping-pong results into the fixed main-side layout used
	 * at the start of the next frame. OpenGL Iris performs the same end-of-frame
	 * alt-to-main copy for temporal buffers (TAA, exposure, accumulated clouds,
	 * and similar history). Without it each Vulkan frame samples an untouched
	 * allocation instead of the previous frame's output.
	 */
	public void preserveHistoryForNextFrame() {
		CommandEncoder encoder = null;
		Set<Integer> copiedTargets = VulkanDiagnostics.enabled() ? new java.util.TreeSet<>() : null;
		for (int logicalTarget : flippedTargets) {
			VulkanTerrainPipelineCompiler.RenderTargetInfo info = pipelines.renderTargets().get(logicalTarget);
			if (info == null || info.clear()) continue;
			GpuTexture source = alternateColorTextures.get(logicalTarget);
			GpuTexture destination = colorTextures.get(logicalTarget);
			if (source == null || destination == null) continue;
			if (encoder == null) encoder = device.createCommandEncoder();
			int copyWidth = Math.min(source.getWidth(0), destination.getWidth(0));
			int copyHeight = Math.min(source.getHeight(0), destination.getHeight(0));
			encoder.copyTextureToTexture(
				source, destination,
				0, 0, 0,
				0, 0,
				copyWidth, copyHeight
			);
			if (copiedTargets != null) copiedTargets.add(logicalTarget);
		}
		if (diagnosticFramesRemaining > 0 && copiedTargets != null) {
			Iris.logger.info(
				"Iris Vulkan frame targets: frame={} flipped={} historyCopied={} cleared={}",
				VulkanFrameState.frameCounter(), new java.util.TreeSet<>(flippedTargets),
				copiedTargets, new java.util.TreeSet<>(clearedTargets)
			);
			diagnosticFramesRemaining--;
		}
	}

	/**
	 * Copies a tiny center tile after a render stage and logs its raw GPU contents.
	 * This makes otherwise indistinguishable black/sky-only mobile failures visible
	 * in a text log without asking the tester to describe each corrupted frame.
	 */
	public void queueDiagnosticProbe(String label, @Nullable GpuTextureView view) {
		if (!VulkanDiagnostics.enabled()) return;
		if (view == null) {
			Iris.logger.info("Iris Vulkan GPU probe [{}]: frame={} unavailable", label, VulkanFrameState.frameCounter());
			return;
		}
		GpuTexture texture = view.texture();
		if ((texture.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
			Iris.logger.info("Iris Vulkan GPU probe [{}]: frame={} copy-src unsupported", label, VulkanFrameState.frameCounter());
			return;
		}

		int probeWidth = Math.min(16, view.getWidth(0));
		int probeHeight = Math.min(16, view.getHeight(0));
		int x = Math.max(0, (view.getWidth(0) - probeWidth) / 2);
		int y = Math.max(0, (view.getHeight(0) - probeHeight) / 2);
		int bytesPerPixel = texture.getFormat().blockSize();
		String format = texture.getFormat().name();
		long size = (long)probeWidth * probeHeight * bytesPerPixel;
		int capturedFrame = VulkanFrameState.frameCounter();
		GpuBuffer readback = device.createBuffer(
			() -> "Iris Vulkan diagnostic " + label,
			GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
			size
		);
		device.createCommandEncoder().copyTextureToBuffer(
			texture,
			readback,
			0L,
			() -> logDiagnosticProbe(label, capturedFrame, format, readback),
			0,
			x,
			y,
			probeWidth,
			probeHeight
		);
	}

	private static void logDiagnosticProbe(String label, int frame, String format, GpuBuffer readback) {
		try (var mapped = readback.map(true, false)) {
			ByteBuffer bytes = mapped.data();
			long hash = 0xcbf29ce484222325L;
			int zeroBytes = 0;
			for (int i = 0; i < bytes.remaining(); i++) {
				int value = bytes.get(i) & 0xff;
				if (value == 0) zeroBytes++;
				hash ^= value;
				hash *= 0x100000001b3L;
			}
			StringBuilder words = new StringBuilder();
			int wordCount = Math.min(8, bytes.remaining() / Integer.BYTES);
			for (int i = 0; i < wordCount; i++) {
				if (i > 0) words.append(',');
				words.append(String.format(java.util.Locale.ROOT, "%08x", bytes.getInt(i * Integer.BYTES)));
			}
			Iris.logger.info(
				"Iris Vulkan GPU probe [{}]: frame={} format={} bytes={} zeroBytes={} hash={} words=[{}]",
				label, frame, format, bytes.remaining(), zeroBytes,
				String.format(java.util.Locale.ROOT, "%016x", hash), words
			);
		} catch (Throwable throwable) {
			Iris.logger.warn("Iris Vulkan GPU probe [{}] failed for frame {}", label, frame, throwable);
		} finally {
			readback.close();
		}
	}

	private static Vector4f defaultClearColor(int logicalTarget) {
		if (logicalTarget == 0) {
			var fog = CapturedRenderingState.INSTANCE.getFogColor();
			return new Vector4f((float)fog.x, (float)fog.y, (float)fog.z, 1.0f);
		}
		if (logicalTarget == 1) {
			return new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
		}
		return new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
	}

	private static Vector4f bootstrapClearColor(
		int logicalTarget,
		VulkanTerrainPipelineCompiler.RenderTargetInfo info
	) {
		if (info.clearColor().isPresent()) return new Vector4f(info.clearColor().get());
		if (!info.clear() && logicalTarget == 0) {
			// A non-clearing colortex0 is history, not the current scene clear. Using
			// CapturedRenderingState's live fog color here made a hot re-enable start
			// with blue history while cold startup happened to start with black. Packs
			// then fed that difference into water/cloud accumulation across worlds.
			return new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
		}
		return defaultClearColor(logicalTarget);
	}

	public @Nullable GpuTextureView getColorTextureView(int logicalTarget) {
		return flippedTargets.contains(logicalTarget)
			? alternateColorTextureViews.get(logicalTarget)
			: colorTextureViews.get(logicalTarget);
	}

	public @Nullable GpuTextureView getColorSampleTextureView(int logicalTarget) {
		return flippedTargets.contains(logicalTarget)
			? alternateColorSampleTextureViews.get(logicalTarget)
			: colorSampleTextureViews.get(logicalTarget);
	}

	/** The attachment selected for the next fullscreen write, exposed only for startup diagnostics. */
	@Nullable GpuTextureView getColorWriteTextureViewForDiagnostics(int logicalTarget) {
		return getWriteColorTextureView(logicalTarget);
	}

	String colorSampleSideForDiagnostics(int logicalTarget) {
		return flippedTargets.contains(logicalTarget) ? "alternate" : "main";
	}

	String colorWriteSideForDiagnostics(int logicalTarget) {
		return flippedTargets.contains(logicalTarget) ? "main" : "alternate";
	}

	private @Nullable GpuTextureView getWriteColorTextureView(int logicalTarget) {
		return flippedTargets.contains(logicalTarget)
			? colorTextureViews.get(logicalTarget)
			: alternateColorTextureViews.get(logicalTarget);
	}

	public GpuTextureView fallbackTextureView() {
		return fallbackTextureView;
	}

	public GpuTextureView fallbackDepthTextureView() {
		return fallbackDepthTextureView;
	}

	public VulkanCustomTextureManager.Binding customTexture(TextureStage stage, String name) {
		return customTextures.find(stage, name);
	}

	public void setShadowRenderState(VulkanShadowRenderState shadowRenderState) {
		this.shadowRenderState = shadowRenderState;
	}

	public @Nullable GpuTextureView shadowTextureView(String name) {
		return shadowRenderState == null ? null : shadowRenderState.texture(name);
	}

	public @Nullable GpuTextureView shadowTextureView(String name, boolean waterShadowEnabled) {
		return shadowRenderState == null ? null : shadowRenderState.texture(name, waterShadowEnabled);
	}

	public @Nullable GpuSampler shadowSampler(String name, boolean comparison) {
		return shadowRenderState == null ? null : shadowRenderState.sampler(name, comparison);
	}

	public @Nullable GpuSampler shadowSampler(String name, boolean comparison, boolean waterShadowEnabled) {
		return shadowRenderState == null ? null : shadowRenderState.sampler(name, comparison, waterShadowEnabled);
	}

	/**
	 * Copies an attached depth image before an entity pass samples it. Vulkan forbids sampling the
	 * same image while it is attached for depth writes, even though several OpenGL drivers tolerate
	 * that feedback loop. Targets without COPY_SRC (notably small UI/PIP targets) deliberately fall
	 * back to white instead of creating an invalid render pass.
	 */
	public GpuTextureView snapshotDepth(CommandEncoder encoder, @Nullable GpuTextureView source) {
		if (source == null || (source.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
			return fallbackDepthTextureView;
		}

		GpuTexture sourceTexture = source.texture();
		if (sampledDepthTexture == null
			|| sampledDepthTexture.isClosed()
			|| sampledDepthTexture.getWidth(0) != source.getWidth(0)
			|| sampledDepthTexture.getHeight(0) != source.getHeight(0)
			|| sampledDepthTexture.getFormat() != sourceTexture.getFormat()) {
			closeSampledDepth();
			sampledDepthTexture = device.createTexture(
				"Iris Vulkan sampled depth copy",
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				sourceTexture.getFormat(),
				source.getWidth(0),
				source.getHeight(0),
				1,
				1
			);
			sampledDepthTextureView = device.createTextureView(sampledDepthTexture);
		}

		encoder.copyTextureToTexture(
			sourceTexture,
			sampledDepthTexture,
			0,
			0,
			0,
			0,
			0,
			source.getWidth(0),
			source.getHeight(0)
		);
		return sampledDepthTextureView;
	}

	/** Captures OptiFine depthtex1 (pre-translucent) or depthtex2 (pre-hand). */
	public void captureDepthStage(int stage, @Nullable GpuTextureView source) {
		if (stage < 1 || stage > 2 || source == null || (source.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0) return;
		GpuTexture sourceTexture = source.texture();
		GpuTexture texture = stagedDepthTextures[stage];
		if (texture == null || texture.isClosed()
			|| texture.getWidth(0) != source.getWidth(0)
			|| texture.getHeight(0) != source.getHeight(0)
			|| texture.getFormat() != sourceTexture.getFormat()) {
			closeStagedDepth(stage);
			texture = device.createTexture(
				"Iris Vulkan depthtex" + stage,
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				sourceTexture.getFormat(), source.getWidth(0), source.getHeight(0), 1, 1
			);
			stagedDepthTextures[stage] = texture;
			stagedDepthTextureViews[stage] = device.createTextureView(texture);
		}
		device.createCommandEncoder().copyTextureToTexture(
			sourceTexture, texture, 0, 0, 0, 0, 0, source.getWidth(0), source.getHeight(0)
		);
	}

	public GpuTextureView depthTextureView(String name, @Nullable GpuTextureView currentDepth) {
		int stage = switch (name) {
			case "depthtex1" -> 1;
			case "depthtex2" -> 2;
			default -> 0;
		};
		if (stage == 0) return currentDepth == null ? fallbackDepthTextureView : currentDepth;
		GpuTextureView view = stagedDepthTextureViews[stage];
		return view == null ? (currentDepth == null ? fallbackDepthTextureView : currentDepth) : view;
	}

	private static Set<String> collectSamplers(VulkanTerrainPipelineCompiler.CompiledTerrainPipelines pipelines) {
		Set<String> result = new LinkedHashSet<>();
		pipelines.pipelines().values().forEach(pipeline -> result.addAll(pipeline.shaders().samplers()));
		pipelines.sodiumPipelines().values().forEach(pipeline -> result.addAll(pipeline.shaders().samplers()));
		return Set.copyOf(result);
	}

	private static boolean isBlockAtlas(String sampler) {
		return sampler.equals("gtexture") || sampler.equals("texture") || sampler.equals("tex")
			|| sampler.equals("iris_ObjCubedSampler") || sampler.equals("Sampler0") || sampler.equals("u_BlockTex");
	}

	private static boolean isLightmap(String sampler) {
		return sampler.equals("lightmap") || sampler.equals("Sampler2") || sampler.equals("u_LightTex");
	}

	private void closeColorTargets() {
		colorTextureViews.values().forEach(GpuTextureView::close);
		colorSampleTextureViews.values().forEach(GpuTextureView::close);
		colorTextures.values().forEach(GpuTexture::close);
		alternateColorTextureViews.values().forEach(GpuTextureView::close);
		alternateColorSampleTextureViews.values().forEach(GpuTextureView::close);
		alternateColorTextures.values().forEach(GpuTexture::close);
		colorTextureViews.clear();
		colorSampleTextureViews.clear();
		colorTextures.clear();
		alternateColorTextureViews.clear();
		alternateColorSampleTextureViews.clear();
		alternateColorTextures.clear();
		clearedTargets.clear();
		flippedTargets.clear();
	}

	private static int mipLevels(int width, int height) {
		return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
	}

	private void closeSampledDepth() {
		if (sampledDepthTextureView != null) {
			sampledDepthTextureView.close();
			sampledDepthTextureView = null;
		}
		if (sampledDepthTexture != null) {
			sampledDepthTexture.close();
			sampledDepthTexture = null;
		}
	}

	private void closeStagedDepth(int stage) {
		if (stagedDepthTextureViews[stage] != null) {
			stagedDepthTextureViews[stage].close();
			stagedDepthTextureViews[stage] = null;
		}
		if (stagedDepthTextures[stage] != null) {
			stagedDepthTextures[stage].close();
			stagedDepthTextures[stage] = null;
		}
	}

	@Override
	public void close() {
		closeColorTargets();
		closeSampledDepth();
		closeStagedDepth(1);
		closeStagedDepth(2);
		fallbackTextureView.close();
		fallbackTexture.close();
		fallbackDepthTextureView.close();
		fallbackDepthTexture.close();
		looseUniformBuffers.values().forEach(GpuBuffer::close);
		looseUniformBuffers.clear();
		uniformUploaders.values().forEach(VulkanShaderpackUniforms::close);
		uniformUploaders.clear();
		sodiumLooseUniformBuffers.values().forEach(GpuBuffer::close);
		sodiumLooseUniformBuffers.clear();
		sodiumUniformUploaders.values().forEach(VulkanShaderpackUniforms::close);
		sodiumUniformUploaders.clear();
	}
}
