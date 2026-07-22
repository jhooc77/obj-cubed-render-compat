package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.VulkanFrameState;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vulkan.texture.VulkanCustomTextureManager;
import net.irisshaders.iris.vulkan.texture.VulkanShadowSamplerFactory;
import net.irisshaders.iris.vulkan.uniforms.VulkanShaderpackUniforms;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.data.AtlasIds;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/** Owns and renders the first native Vulkan shadow map (terrain, cutout and translucent terrain). */
public final class VulkanShadowRenderState implements AutoCloseable {
	private final GpuDevice device;
	private final VulkanShadowPipelineCompiler.CompiledShadowPipelines pipelines;
	private final VulkanEntityPipelineCompiler.CompiledEntityPipelines entityPipelines;
	private final VulkanTerrainRenderState worldTargets;
	private final Map<Integer, GpuTexture> colorTextures = new HashMap<>();
	private final Map<Integer, GpuTextureView> colorRenderViews = new HashMap<>();
	private final Map<Integer, GpuTextureView> colorSampleViews = new HashMap<>();
	private final Map<Integer, GpuTexture> alternateColorTextures = new HashMap<>();
	private final Map<Integer, GpuTextureView> alternateColorRenderViews = new HashMap<>();
	private final Map<Integer, GpuTextureView> alternateColorSampleViews = new HashMap<>();
	private final Set<Integer> flippedColors = new HashSet<>();
	private final EnumMap<VulkanShadowPipelineCompiler.ShadowPass, GpuBuffer> uniformBuffers =
		new EnumMap<>(VulkanShadowPipelineCompiler.ShadowPass.class);
	private final EnumMap<VulkanShadowPipelineCompiler.ShadowPass, VulkanShaderpackUniforms> uniformUploaders =
		new EnumMap<>(VulkanShadowPipelineCompiler.ShadowPass.class);
	private final EnumMap<VulkanShadowPipelineCompiler.ShadowPass, GpuBuffer> sodiumUniformBuffers =
		new EnumMap<>(VulkanShadowPipelineCompiler.ShadowPass.class);
	private final EnumMap<VulkanShadowPipelineCompiler.ShadowPass, VulkanShaderpackUniforms> sodiumUniformUploaders =
		new EnumMap<>(VulkanShadowPipelineCompiler.ShadowPass.class);
	private final Map<RenderPipeline, GpuBuffer> entityUniformBuffers = new HashMap<>();
	private final Map<RenderPipeline, VulkanShaderpackUniforms> entityUniformUploaders = new HashMap<>();
	private final Set<Integer> clearedColors = new HashSet<>();
	private final int resolution;
	private final @Nullable GpuTexture depthTexture;
	private final @Nullable GpuTextureView depthRenderView;
	private final @Nullable GpuTextureView depthSampleView;
	private final @Nullable GpuTexture opaqueDepthTexture;
	private final @Nullable GpuTextureView opaqueDepthSampleView;
	private final @Nullable GpuBuffer projectionBuffer;
	private final Map<String, GpuSampler> regularSamplers = new HashMap<>();
	private final Map<String, GpuSampler> comparisonSamplers = new HashMap<>();
	private final @Nullable VulkanMipmapGenerator mipmapGenerator;
	private @Nullable GpuBufferSlice shadowDynamicTransforms;
	private @Nullable ChunkSectionsToRender pendingTranslucentChunks;
	private @Nullable SodiumWorldRenderer pendingSodiumRenderer;
	private final Matrix4f currentProjection = new Matrix4f();
	private boolean clearDepth;

	public VulkanShadowRenderState(
		GpuDevice device,
		VulkanShadowPipelineCompiler.CompiledShadowPipelines pipelines,
		VulkanEntityPipelineCompiler.CompiledEntityPipelines entityPipelines,
		VulkanTerrainRenderState worldTargets
	) {
		this.device = device;
		this.pipelines = pipelines;
		this.entityPipelines = entityPipelines;
		this.worldTargets = worldTargets;
		this.resolution = pipelines.directives().getResolution();
		if (!pipelines.enabled()) {
			depthTexture = null;
			depthRenderView = null;
			depthSampleView = null;
			opaqueDepthTexture = null;
			opaqueDepthSampleView = null;
			projectionBuffer = null;
			mipmapGenerator = null;
			return;
		}

		int depthMips = mipLevels(resolution,
			pipelines.directives().getDepthSamplingSettings().get(0).getMipmap());
		depthTexture = device.createTexture("Iris Vulkan shadowtex0",
			GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
			GpuFormat.D32_FLOAT, resolution, resolution, 1, depthMips);
		depthRenderView = device.createTextureView(depthTexture, 0, 1);
		depthSampleView = device.createTextureView(depthTexture);

		int opaqueMips = mipLevels(resolution,
			pipelines.directives().getDepthSamplingSettings().get(1).getMipmap());
		opaqueDepthTexture = device.createTexture("Iris Vulkan shadowtex1",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
			GpuFormat.D32_FLOAT, resolution, resolution, 1, opaqueMips);
		opaqueDepthSampleView = device.createTextureView(opaqueDepthTexture);

		for (Map.Entry<Integer, VulkanShadowPipelineCompiler.ShadowTargetInfo> entry : pipelines.targets().entrySet()) {
			int target = entry.getKey();
			var info = entry.getValue();
			GpuTexture texture = device.createTexture("Iris Vulkan shadowcolor" + target,
				GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				info.format(), resolution, resolution, 1, mipLevels(resolution, info.mipmapped()));
			colorTextures.put(target, texture);
			colorRenderViews.put(target, device.createTextureView(texture, 0, 1));
			colorSampleViews.put(target, device.createTextureView(texture));
			GpuTexture alternate = device.createTexture("Iris Vulkan shadowcolor" + target + " alternate",
				GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				info.format(), resolution, resolution, 1, mipLevels(resolution, info.mipmapped()));
			alternateColorTextures.put(target, alternate);
			alternateColorRenderViews.put(target, device.createTextureView(alternate, 0, 1));
			alternateColorSampleViews.put(target, device.createTextureView(alternate));
		}

		pipelines.pipelines().forEach((pass, pipeline) -> {
			int size = pipeline.shaders().looseUniformBufferSize();
			if (size > 0) {
				uniformBuffers.put(pass, createZeroUniformBuffer(size, pass));
				uniformUploaders.put(pass, new VulkanShaderpackUniforms(
					pipeline.shaders(), worldTargets.customUniforms(), pipeline.alphaTest().reference(), true));
			}
		});
		pipelines.sodiumPipelines().forEach((pass, pipeline) -> {
			int size = pipeline.shaders().looseUniformBufferSize();
			if (size > 0) {
				sodiumUniformBuffers.put(pass, createZeroUniformBuffer(size, "sodium_" + pass.name().toLowerCase()));
				sodiumUniformUploaders.put(pass, new VulkanShaderpackUniforms(
					pipeline.shaders(), worldTargets.customUniforms(), pipeline.alphaTest().reference(), true));
			}
		});
		entityPipelines.pipelines().forEach((original, pipeline) -> {
			int size = pipeline.shaders().looseUniformBufferSize();
			if (size > 0) {
				entityUniformBuffers.put(original, createZeroUniformBuffer(size, "entity_" + original.getLocation().getPath()));
				entityUniformUploaders.put(original, new VulkanShaderpackUniforms(
					pipeline.shaders(), worldTargets.customUniforms(), pipeline.alphaTest().reference(), true));
			}
		});
		projectionBuffer = createZeroUniformBuffer(64, "projection");
		for (int index = 0; index < 2; index++) {
			var settings = pipelines.directives().getDepthSamplingSettings().get(index);
			createShadowSamplers("shadowtex" + index, settings.getNearest());
		}
		pipelines.targets().forEach((target, info) ->
			regularSamplers.put("shadowcolor" + target, createShadowSampler(info.nearest(), false)));
		mipmapGenerator = new VulkanMipmapGenerator(device);
	}

	public boolean enabled() {
		return pipelines.enabled();
	}

	public int resolution() {
		return resolution;
	}

	public void renderTerrain(LevelRenderer levelRenderer) {
		if (!enabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Camera camera = minecraft.gameRenderer.mainCamera();
		float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float sun = normalizeDegrees(camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks) + 90.0F);
		boolean day = sun < 180.0F;
		float lightAngle = camera.attributeProbe().getValue(day ? EnvironmentAttributes.SUN_ANGLE : EnvironmentAttributes.MOON_ANGLE, partialTicks);
		float shadowAngle = normalizeDegrees(lightAngle + 90.0F) / 360.0F;
		Matrix4f modelView = createShadowModelView(shadowAngle, pipelines.directives().getIntervalSize(),
			pipelines.sunPathRotation(), camera.position().x(), camera.position().y(), camera.position().z());
		Matrix4f projection = pipelines.directives().getFov() == null
			? ShadowMatrices.createOrthoMatrix(
				pipelines.directives().getDistance(),
				pipelines.directives().getNearPlane(),
				pipelines.directives().getFarPlane())
			: ShadowMatrices.createPerspectiveMatrix(pipelines.directives().getFov());

		VulkanFrameState.setShadow(modelView, projection, shadowAngle);
		currentProjection.set(projection);
		shadowDynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(modelView);
		CommandEncoder projectionEncoder = device.createCommandEncoder();
		writeMatrix(projectionEncoder, projectionBuffer, projection);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projectionBuffer.slice(), pipelines.directives().getFov() == null
			? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE);
		clearFrameTargets();
		IrisVulkan.beginShadowPass(this);
		boolean previousShadowActive = ShadowRenderer.ACTIVE;
		int previousResolution = ShadowRenderer.RESOLUTION;
		int previousRenderDistance = ShadowRenderer.renderDistance;
		try {
			ShadowRenderer.ACTIVE = true;
			ShadowRenderer.RESOLUTION = resolution;
			ShadowRenderer.renderDistance = Math.max(1,
				(int)Math.ceil(pipelines.directives().getDistance() / 16.0));
			ChunkSectionsToRender chunks = prepareSodiumShadowChunks(levelRenderer, camera, modelView, projection);
			if (pipelines.directives().shouldRenderTerrain()) {
				chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, blockAtlasSampler());
			}
			pendingTranslucentChunks = chunks;
		} finally {
			endPendingSodiumScope();
			ShadowRenderer.ACTIVE = previousShadowActive;
			ShadowRenderer.RESOLUTION = previousResolution;
			ShadowRenderer.renderDistance = previousRenderDistance;
			IrisVulkan.endShadowPass(this);
			RenderSystem.restoreProjectionMatrix();
		}
	}

	private ChunkSectionsToRender prepareSodiumShadowChunks(
		LevelRenderer levelRenderer,
		Camera camera,
		Matrix4f modelView,
		Matrix4f projection
	) {
		if (!(levelRenderer instanceof LevelRendererExtension extension)) {
			return levelRenderer.prepareChunkRenders(modelView);
		}
		SodiumWorldRenderer sodium = extension.sodium$getWorldRenderer();
		if (sodium == null) return levelRenderer.prepareChunkRenders(modelView);

		ChunkRenderMatrices playerMatrices = extension.sodium$getMatrices();
		ChunkRenderMatrices shadowMatrices = new ChunkRenderMatrices(projection, modelView);
		extension.sodium$setMatrices(shadowMatrices);
		beginSodiumScope(sodium);
		try {
			sodium.scheduleTerrainUpdate();
			double distance = Math.max(16.0, pipelines.directives().getDistance());
			BoxCullingFrustum frustum = new BoxCullingFrustum(new BoxCuller(distance));
			double x = camera.position().x();
			double y = camera.position().y();
			double z = camera.position().z();
			frustum.prepare(x, y, z);
			sodium.setupTerrain(
				camera,
				frustum.sodium$createViewport(),
				FogParameters.NONE,
				camera.entity() != null && camera.entity().isSpectator(),
				false,
				((FrustumAccessor)frustum).sodium$getMatrix()
			);
			ChunkSectionsToRender sections = new ChunkSectionsToRender(null, null, 0, null);
			((SodiumChunkSection)(Object)sections).sodium$setRendering(
				sodium, shadowMatrices, x, y, z);
			return sections;
		} finally {
			extension.sodium$setMatrices(playerMatrices);
		}
	}

	private void beginSodiumScope(SodiumWorldRenderer sodium) {
		if (pendingSodiumRenderer == sodium) return;
		endPendingSodiumScope();
		if (sodium instanceof ShadowRenderListAccess access) access.iris$beginShadowRenderListScope();
		pendingSodiumRenderer = sodium;
	}

	private void endPendingSodiumScope() {
		SodiumWorldRenderer sodium = pendingSodiumRenderer;
		pendingSodiumRenderer = null;
		if (sodium instanceof ShadowRenderListAccess access) access.iris$endShadowRenderListScope();
	}

	/** Replays the already-prepared world feature buffers into the shadow framebuffer. */
	public void renderFeatures(FeatureRenderDispatcher.PreparedFrame frame) {
		if (!enabled() || shadowDynamicTransforms == null) return;
		CommandEncoder encoder = device.createCommandEncoder();
		writeMatrix(encoder, projectionBuffer, currentProjection);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projectionBuffer.slice(), pipelines.directives().getFov() == null
			? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE);
		IrisVulkan.beginShadowPass(this);
		boolean previousShadowActive = ShadowRenderer.ACTIVE;
		int previousResolution = ShadowRenderer.RESOLUTION;
		int previousRenderDistance = ShadowRenderer.renderDistance;
		try {
			ShadowRenderer.ACTIVE = true;
			ShadowRenderer.RESOLUTION = resolution;
			ShadowRenderer.renderDistance = Math.max(1,
				(int)Math.ceil(pipelines.directives().getDistance() / 16.0));
			if (!entityPipelines.pipelines().isEmpty()) frame.executeSolid();
			copyOpaqueDepth();
			if (pipelines.directives().shouldRenderTranslucent()) {
				if (!entityPipelines.pipelines().isEmpty()) {
					frame.executeTranslucent();
					frame.executeTranslucentAfterTerrain();
				}
				if (pendingTranslucentChunks != null
					&& pipelines.pipelines().containsKey(VulkanShadowPipelineCompiler.ShadowPass.TRANSLUCENT)) {
					if (Minecraft.getInstance().levelRenderer instanceof LevelRendererExtension extension) {
						SodiumWorldRenderer sodium = extension.sodium$getWorldRenderer();
						if (sodium != null) beginSodiumScope(sodium);
					}
					pendingTranslucentChunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, blockAtlasSampler());
				}
			}
		} finally {
			endPendingSodiumScope();
			pendingTranslucentChunks = null;
			ShadowRenderer.ACTIVE = previousShadowActive;
			ShadowRenderer.RESOLUTION = previousResolution;
			ShadowRenderer.renderDistance = previousRenderDistance;
			IrisVulkan.endShadowPass(this);
			RenderSystem.restoreProjectionMatrix();
		}
	}

	public void finishShadowFrame() {
		generateMipmaps();
	}

	public boolean replacesEntity(RenderPipeline original) {
		return entityPipelines.forOriginal(original) != null;
	}

	public RenderPass createEntityRenderPass(CommandEncoder encoder, Supplier<String> label, RenderPipeline original) {
		var replacement = entityPipelines.forOriginal(original);
		if (replacement == null) throw new IllegalArgumentException("No Vulkan shadow entity pipeline for " + original.getLocation());
		GpuBuffer uniforms = entityUniformBuffers.get(original);
		VulkanShaderpackUniforms uploader = entityUniformUploaders.get(original);
		if (uniforms != null && uploader != null) uploader.update(encoder, uniforms, resolution, resolution);
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(label);
		for (int target : replacement.layout().logicalTargets()) {
			descriptor.withColorAttachment(colorRenderViews.get(target), Optional.empty());
		}
		descriptor.withDepthAttachment(depthRenderView, OptionalDouble.empty());
		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, resolution, resolution));
		return encoder.createRenderPass(descriptor);
	}

	public void setEntityPipeline(RenderPass pass, RenderPipeline original) {
		var replacement = entityPipelines.forOriginal(original);
		if (replacement == null) return;
		pass.setPipeline(replacement.replacement());
		GpuBuffer uniforms = entityUniformBuffers.get(original);
		if (uniforms != null && replacement.shaders().uniformBlocks().contains(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK)) {
			pass.setUniform(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK, uniforms);
		}
		for (String name : replacement.shaders().samplers()) {
			VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, name);
			String type = replacement.shaders().samplerTypes().get(name);
			GpuSampler fallback = sampler(name, type != null && type.toLowerCase(java.util.Locale.ROOT).contains("shadow"));
			pass.bindTexture(name, custom == null ? worldTargets.fallbackTextureView() : custom.view(),
				custom == null ? fallback : custom.sampler());
		}
	}

	public void bindEntityTexture(RenderPass pass, RenderPipeline original, String originalName, GpuTextureView texture, GpuSampler sampler) {
		var replacement = entityPipelines.forOriginal(original);
		if (replacement == null) return;
		for (String name : replacement.shaders().samplers()) {
			VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, name);
			if (custom != null) {
				pass.bindTexture(name, custom.view(), custom.sampler());
				continue;
			}
			if (originalName.equals("Sampler0") && !isLightmap(name) && !name.equals("iris_overlay") && !name.equals("Sampler1")) {
				pass.bindTexture(name, isAtlas(name) ? texture : worldTargets.fallbackTextureView(), sampler);
			} else if (originalName.equals("Sampler1") && (name.equals("iris_overlay") || name.equals("Sampler1"))) {
				pass.bindTexture(name, texture, sampler);
			} else if (originalName.equals("Sampler2") && isLightmap(name)) {
				pass.bindTexture(name, texture, sampler);
			}
		}
	}

	public GpuBufferSlice shadowDynamicTransforms(GpuBufferSlice original) {
		return shadowDynamicTransforms == null ? original : shadowDynamicTransforms;
	}

	public RenderPass createTerrainRenderPass(CommandEncoder encoder, Supplier<String> label, ChunkSectionLayerGroup group) {
		uniformUploaders.forEach((pass, uploader) -> uploader.update(
			encoder, uniformBuffers.get(pass), resolution, resolution,
			0, 0, 0, 0, shadowTerrainPhase(pass).ordinal()));
		sodiumUniformUploaders.forEach((pass, uploader) -> uploader.update(
			encoder, sodiumUniformBuffers.get(pass), resolution, resolution,
			0, 0, 0, 0, shadowTerrainPhase(pass).ordinal()));

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(label);
		for (int target : targetsForGroup(group)) {
			var info = pipelines.targets().get(target);
			Optional<org.joml.Vector4fc> clear = info.clear() && clearedColors.add(target)
				? Optional.of(new Vector4f(info.clearColor())) : Optional.empty();
			descriptor.withColorAttachment(colorRenderViews.get(target), clear);
		}
		descriptor.withDepthAttachment(depthRenderView, clearDepth ? OptionalDouble.of(0.0) : OptionalDouble.empty());
		clearDepth = false;
		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, resolution, resolution));
		return encoder.createRenderPass(descriptor);
	}

	private static WorldRenderingPhase shadowTerrainPhase(VulkanShadowPipelineCompiler.ShadowPass pass) {
		return switch (pass) {
			case SOLID -> WorldRenderingPhase.TERRAIN_SOLID;
			case CUTOUT -> WorldRenderingPhase.TERRAIN_CUTOUT;
			case TRANSLUCENT -> WorldRenderingPhase.TERRAIN_TRANSLUCENT;
		};
	}

	private void clearFrameTargets() {
		clearedColors.clear();
		flippedColors.clear();
		CommandEncoder encoder = device.createCommandEncoder();
		encoder.clearDepthTexture(depthTexture, 0.0);
		for (Map.Entry<Integer, VulkanShadowPipelineCompiler.ShadowTargetInfo> entry : pipelines.targets().entrySet()) {
			if (entry.getValue().clear()) {
				encoder.clearColorTexture(colorTextures.get(entry.getKey()), entry.getValue().clearColor());
				encoder.clearColorTexture(alternateColorTextures.get(entry.getKey()), entry.getValue().clearColor());
				clearedColors.add(entry.getKey());
			}
		}
		clearDepth = false;
	}

	private Set<Integer> targetsForGroup(ChunkSectionLayerGroup group) {
		Set<Integer> targets = new java.util.LinkedHashSet<>();
		if (group == ChunkSectionLayerGroup.OPAQUE) {
			addTargets(targets, VulkanShadowPipelineCompiler.ShadowPass.SOLID);
			addTargets(targets, VulkanShadowPipelineCompiler.ShadowPass.CUTOUT);
		} else {
			addTargets(targets, VulkanShadowPipelineCompiler.ShadowPass.TRANSLUCENT);
		}
		return targets;
	}

	private void addTargets(Set<Integer> targets, VulkanShadowPipelineCompiler.ShadowPass pass) {
		var pipeline = pipelines.pipelines().get(pass);
		if (pipeline != null) {
			for (int target : pipeline.drawBuffers()) targets.add(target);
		}
	}

	public void setTerrainPipeline(RenderPass pass, RenderPipeline original) {
		var replacement = pipelines.forOriginal(original);
		if (replacement == null) {
			pass.setPipeline(original);
			return;
		}
		pass.setPipeline(replacement.pipeline());
		GpuBuffer uniforms = uniformBuffers.get(replacement.pass());
		if (uniforms != null && replacement.shaders().uniformBlocks().contains(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK)) {
			pass.setUniform(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK, uniforms);
		}
	}

	public void setSodiumTerrainPipeline(RenderPass pass, VulkanShadowPipelineCompiler.ShadowPass shadowPass) {
		var replacement = pipelines.forSodium(shadowPass);
		if (replacement == null) {
			throw new IllegalStateException("No Vulkan Sodium shadow terrain pipeline for " + shadowPass);
		}
		pass.setPipeline(replacement.pipeline());
		GpuBuffer uniforms = sodiumUniformBuffers.get(shadowPass);
		if (uniforms != null && replacement.shaders().uniformBlocks().contains(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK)) {
			pass.setUniform(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK, uniforms);
		}
	}

	public void bindTerrainTexture(RenderPass pass, String originalName, GpuTextureView texture, GpuSampler sampler) {
		Set<String> names = new java.util.LinkedHashSet<>();
		pipelines.pipelines().values().forEach(pipeline -> names.addAll(pipeline.shaders().samplers()));
		if (originalName.equals("Sampler0")) {
			for (String name : names) {
				if (isLightmap(name)) continue;
				VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, name);
				pass.bindTexture(name, custom != null ? custom.view() : isAtlas(name) ? texture : worldTargets.fallbackTextureView(),
					custom != null ? custom.sampler() : sampler);
			}
		} else if (originalName.equals("Sampler2")) {
			for (String name : names) {
				if (isLightmap(name)) {
					VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, name);
					pass.bindTexture(name, custom == null ? texture : custom.view(), custom == null ? sampler : custom.sampler());
				}
			}
		}
	}

	public void bindSodiumTerrainTexture(RenderPass pass, String originalName, GpuTextureView texture, GpuSampler sampler) {
		Set<String> names = new java.util.LinkedHashSet<>();
		pipelines.sodiumPipelines().values().forEach(pipeline -> names.addAll(pipeline.shaders().samplers()));
		if (originalName.equals("u_BlockTex")) {
			for (String name : names) {
				if (isLightmap(name)) continue;
				VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, name);
				pass.bindTexture(name, custom != null ? custom.view() : isAtlas(name) ? texture : worldTargets.fallbackTextureView(),
					custom != null ? custom.sampler() : sampler);
			}
		} else if (originalName.equals("u_LightTex")) {
			for (String name : names) {
				if (!isLightmap(name)) continue;
				VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(TextureStage.GBUFFERS_AND_SHADOW, name);
				pass.bindTexture(name, custom == null ? texture : custom.view(), custom == null ? sampler : custom.sampler());
			}
		}
	}

	public @Nullable GpuTextureView texture(String name) {
		return texture(name, false);
	}

	public @Nullable GpuTextureView texture(String name, boolean waterShadowEnabled) {
		String resolvedName = resolveSamplerName(name, waterShadowEnabled);
		if (resolvedName.equals("shadowtex0")) return depthSampleView;
		if (resolvedName.equals("shadowtex1")) return opaqueDepthSampleView;
		if (resolvedName.startsWith("shadowcolor")) {
			try {
				return colorSampleView(Integer.parseInt(resolvedName.substring("shadowcolor".length())));
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	public GpuSampler sampler(String name, boolean comparison) {
		return sampler(name, comparison, false);
	}

	public GpuSampler sampler(String name, boolean comparison, boolean waterShadowEnabled) {
		String resolvedName = resolveSamplerName(name, waterShadowEnabled);
		GpuSampler selected = comparison ? comparisonSamplers.get(resolvedName) : regularSamplers.get(resolvedName);
		if (selected != null) return selected;
		selected = regularSamplers.get(resolvedName);
		if (selected != null) return selected;
		return regularSamplers.get("shadowtex0");
	}

	private static String resolveSamplerName(String name, boolean waterShadowEnabled) {
		return switch (name) {
			case "watershadow" -> "shadowtex0";
			case "shadow" -> waterShadowEnabled ? "shadowtex1" : "shadowtex0";
			case "shadowcolor" -> "shadowcolor0";
			case "shadowtex0HW" -> "shadowtex0";
			case "shadowtex1HW" -> "shadowtex1";
			default -> name;
		};
	}

	private void createShadowSamplers(String name, boolean nearest) {
		regularSamplers.put(name, createShadowSampler(nearest, false));
		comparisonSamplers.put(name, createShadowSampler(nearest, true));
	}

	private GpuSampler createShadowSampler(boolean nearest, boolean comparison) {
		FilterMode filter = nearest ? FilterMode.NEAREST : FilterMode.LINEAR;
		return VulkanShadowSamplerFactory.create(device, AddressMode.CLAMP_TO_EDGE, filter, filter,
			comparison, OptionalDouble.empty());
	}

	private static GpuSampler blockAtlasSampler() {
		return Minecraft.getInstance().getAtlasManager()
			.getAtlasOrThrow(AtlasIds.BLOCKS).getSampler();
	}

	private void copyOpaqueDepth() {
		CommandEncoder encoder = device.createCommandEncoder();
		encoder.copyTextureToTexture(depthTexture, opaqueDepthTexture, 0, 0, 0, 0, 0, resolution, resolution);
	}

	private void generateMipmaps() {
		if (mipmapGenerator == null) return;
		CommandEncoder encoder = device.createCommandEncoder();
		if (pipelines.directives().getDepthSamplingSettings().get(0).getMipmap()) {
			mipmapGenerator.generateDepth(encoder, depthTexture);
		}
		if (pipelines.directives().getDepthSamplingSettings().get(1).getMipmap()) {
			mipmapGenerator.generateDepth(encoder, opaqueDepthTexture);
		}
		pipelines.targets().forEach((target, info) -> {
			if (info.mipmapped()) {
				GpuTextureView view = colorSampleView(target);
				if (view != null) mipmapGenerator.generateColor(encoder, view.texture());
			}
		});
	}

	public @Nullable GpuTextureView colorSampleView(int target) {
		return flippedColors.contains(target)
			? alternateColorSampleViews.get(target) : colorSampleViews.get(target);
	}

	private @Nullable GpuTextureView writeColorRenderView(int target) {
		return flippedColors.contains(target)
			? colorRenderViews.get(target) : alternateColorRenderViews.get(target);
	}

	public void applyCompositePreFlips(Map<Integer, Boolean> flips) {
		flips.forEach((target, shouldFlip) -> {
			if (Boolean.TRUE.equals(shouldFlip)) flipColor(target);
		});
	}

	public void finishCompositePass(VulkanCompositePipelineCompiler.TargetLayout layout) {
		for (int target : layout.flipTargets()) flipColor(target);
	}

	private void flipColor(int target) {
		if (!flippedColors.add(target)) flippedColors.remove(target);
	}

	public RenderPass createCompositeRenderPass(
		CommandEncoder encoder,
		Supplier<String> label,
		VulkanCompositePipelineCompiler.TargetLayout layout
	) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(label);
		for (int target : layout.logicalTargets()) {
			GpuTextureView view = writeColorRenderView(target);
			if (view == null) throw new IllegalStateException("Missing Vulkan shadowcolor" + target);
			descriptor.withColorAttachment(view, Optional.empty());
		}
		var viewport = layout.viewport();
		int x = Math.clamp((int)(resolution * viewport.viewportX()), 0, Math.max(0, resolution - 1));
		int y = Math.clamp((int)(resolution * viewport.viewportY()), 0, Math.max(0, resolution - 1));
		int viewportWidth = Math.max(1, Math.min(resolution - x, (int)(resolution * viewport.scale())));
		int viewportHeight = Math.max(1, Math.min(resolution - y, (int)(resolution * viewport.scale())));
		descriptor.withRenderArea(new RenderPass.RenderArea(x, y, viewportWidth, viewportHeight));
		return encoder.createRenderPass(descriptor);
	}

	private GpuBuffer createZeroUniformBuffer(int size, VulkanShadowPipelineCompiler.ShadowPass pass) {
		return createZeroUniformBuffer(size, pass == null ? "projection" : pass.name().toLowerCase());
	}

	private GpuBuffer createZeroUniformBuffer(int size, String name) {
		ByteBuffer zeroes = MemoryUtil.memCalloc(size);
		try {
			return device.createBuffer(() -> "Iris Vulkan " + name + " shadow uniforms",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, zeroes);
		} finally {
			MemoryUtil.memFree(zeroes);
		}
	}

	private static void writeMatrix(CommandEncoder encoder, GpuBuffer buffer, Matrix4f matrix) {
		ByteBuffer data = MemoryUtil.memAlloc(64);
		try {
			matrix.get(0, data);
			data.position(0).limit(64);
			encoder.writeToBuffer(buffer.slice(), data);
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private static Matrix4f createShadowModelView(float angle, float interval, float sunPathRotation, double x, double y, double z) {
		PoseStack modelView = new PoseStack();
		ShadowMatrices.createModelViewMatrix(
			modelView, angle, interval, sunPathRotation, x, y, z,
			ShadowMatrices.NEAR, ShadowMatrices.FAR);
		return new Matrix4f(modelView.last().pose());
	}

	private static float normalizeDegrees(float value) {
		value %= 360.0F;
		return value < 0.0F ? value + 360.0F : value;
	}

	private static int mipLevels(int size, boolean mipmapped) {
		return mipmapped ? 32 - Integer.numberOfLeadingZeros(size) : 1;
	}

	private static boolean isAtlas(String name) {
		return name.equals("Sampler0") || name.equals("gtexture") || name.equals("texture") || name.equals("tex")
			|| name.equals("iris_ObjCubedSampler") || name.equals("u_BlockTex");
	}

	private static boolean isLightmap(String name) {
		return name.equals("Sampler2") || name.equals("lightmap") || name.equals("u_LightTex");
	}

	@Override
	public void close() {
		endPendingSodiumScope();
		pendingTranslucentChunks = null;
		uniformBuffers.values().forEach(GpuBuffer::close);
		sodiumUniformBuffers.values().forEach(GpuBuffer::close);
		entityUniformBuffers.values().forEach(GpuBuffer::close);
		uniformUploaders.values().forEach(VulkanShaderpackUniforms::close);
		sodiumUniformUploaders.values().forEach(VulkanShaderpackUniforms::close);
		entityUniformUploaders.values().forEach(VulkanShaderpackUniforms::close);
		colorRenderViews.values().forEach(GpuTextureView::close);
		colorSampleViews.values().forEach(GpuTextureView::close);
		colorTextures.values().forEach(GpuTexture::close);
		alternateColorRenderViews.values().forEach(GpuTextureView::close);
		alternateColorSampleViews.values().forEach(GpuTextureView::close);
		alternateColorTextures.values().forEach(GpuTexture::close);
		if (depthRenderView != null) depthRenderView.close();
		if (depthSampleView != null) depthSampleView.close();
		if (depthTexture != null) depthTexture.close();
		if (opaqueDepthSampleView != null) opaqueDepthSampleView.close();
		if (opaqueDepthTexture != null) opaqueDepthTexture.close();
		if (projectionBuffer != null) projectionBuffer.close();
		regularSamplers.values().forEach(GpuSampler::close);
		comparisonSamplers.values().forEach(GpuSampler::close);
		regularSamplers.clear();
		comparisonSamplers.clear();
		if (mipmapGenerator != null) mipmapGenerator.close();
	}
}
