package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.vulkan.pipeline.VulkanTerrainPipelineCompiler;
import net.irisshaders.iris.vulkan.pipeline.VulkanTerrainRenderState;
import net.irisshaders.iris.vulkan.pipeline.VulkanEntityPipelineCompiler;
import net.irisshaders.iris.vulkan.pipeline.VulkanEntityRenderState;
import net.irisshaders.iris.vulkan.pipeline.VulkanCompositePipelineCompiler;
import net.irisshaders.iris.vulkan.pipeline.VulkanCompositeRenderState;
import net.irisshaders.iris.vulkan.pipeline.VulkanShadowPipelineCompiler;
import net.irisshaders.iris.vulkan.pipeline.VulkanShadowRenderState;
import net.irisshaders.iris.vulkan.pipeline.VulkanShadowCompositeRenderState;
import net.irisshaders.iris.vulkan.texture.VulkanCustomTextureManager;
import net.irisshaders.iris.vulkan.uniforms.VulkanCustomUniforms;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Entry point for the shader-pack runtime hosted by Minecraft's Vulkan device. */
public final class IrisVulkan {
	private static boolean rendererInitialized;
	private static boolean pipelineCompilationAttempted;
	private static volatile boolean shaderPackDepthMode;
	private static final AtomicLong requestedPipelineGeneration = new AtomicLong();
	private static long initializedPipelineGeneration = -1L;
	private static @Nullable NamespacedId activeDimension;
	private static @Nullable Object activeLevel;
	private static @Nullable GpuDevice device;
	private static @Nullable VulkanTerrainPipelineCompiler.CompiledTerrainPipelines terrainPipelines;
	private static @Nullable VulkanCustomTextureManager customTextureManager;
	private static @Nullable VulkanCustomUniforms customUniforms;
	private static @Nullable VulkanTerrainRenderState terrainRenderState;
	private static @Nullable VulkanEntityPipelineCompiler.CompiledEntityPipelines entityPipelines;
	private static @Nullable VulkanEntityPipelineCompiler.CompiledEntityPipelines handEntityPipelines;
	private static @Nullable VulkanEntityPipelineCompiler.CompiledEntityPipelines shadowEntityPipelines;
	private static @Nullable VulkanEntityRenderState entityRenderState;
	private static @Nullable VulkanEntityRenderState handEntityRenderState;
	private static @Nullable VulkanCompositePipelineCompiler.CompiledCompositePipelines compositePipelines;
	private static @Nullable VulkanCompositeRenderState compositeRenderState;
	private static @Nullable VulkanShadowPipelineCompiler.CompiledShadowPipelines shadowPipelines;
	private static @Nullable VulkanShadowRenderState shadowRenderState;
	private static @Nullable VulkanCompositePipelineCompiler.CompiledShadowCompositePipelines shadowCompositePipelines;
	private static @Nullable VulkanShadowCompositeRenderState shadowCompositeRenderState;
	private static @Nullable VulkanShadowRenderState activeShadowRenderState;
	private static boolean renderingHand;
	private static boolean renderingLevel;
	private static boolean offscreenEntityBypassLogged;
	private static final int MIN_PIPELINE_WARMUP_FRAMES = 2;
	private static final int MIN_SODIUM_TERRAIN_WAIT_FRAMES = 8;
	private static final int SODIUM_TERRAIN_STABLE_FRAMES = 3;
	private static final int MAX_SODIUM_TERRAIN_WAIT_FRAMES = 120;
	private static int pipelineWarmupFramesRemaining;
	private static int sodiumTerrainWaitFrames;
	private static int sodiumTerrainStableFrames;
	private static boolean awaitingSodiumTerrain;
	private static boolean sodiumTerrainQueryFailureLogged;
	private static boolean pipelineWarmupThisFrame;
	private static volatile WorldRenderingPhase renderPhase = WorldRenderingPhase.NONE;

	private IrisVulkan() {
	}

	public static synchronized void onRendererInit(GpuDevice device) {
		if (rendererInitialized) {
			return;
		}
		if (!(((GpuDeviceAccessor)(Object)device).getBackend() instanceof VulkanDevice)) {
			// preferredGraphicsBackend is read by the mixin plugin before Minecraft can
			// recover from an unclean startup. If Minecraft subsequently falls back to
			// OpenGL, Vulkan-only mixins may still be present, but they must stay inert.
			Iris.logger.warn(
				"Vulkan was requested but Minecraft initialized {}; leaving the experimental Vulkan runtime disabled",
				device.getDeviceInfo().driverInfo()
			);
			shaderPackDepthMode = false;
			return;
		}

		Iris.registerVertexSerializers();
		VulkanCapabilities.initialize(device);
		IrisVulkan.device = device;
		rendererInitialized = true;

		Iris.logger.info(
			"Initializing experimental Vulkan shader-pack runtime on {} ({})",
			device.getDeviceInfo().name(),
			device.getDeviceInfo().driverInfo()
		);

		// Shader-pack compilation is deferred until the first world frame. initRenderer
		// runs before Minecraft constructs its resource manager, so compiling here would
		// make the active server pack's obj-cubed includes invisible to the injector.
	}

	public static synchronized void ensurePipelineInitialized() {
		GpuDevice activeDevice = device;
		if (!rendererInitialized || activeDevice == null) {
			return;
		}
		NamespacedId dimension = Iris.getCurrentDimension();
		Object level = Minecraft.getInstance().level;
		long requestedGeneration = requestedPipelineGeneration.get();
		boolean reloadRequested = pipelineCompilationAttempted
			&& initializedPipelineGeneration != requestedGeneration;
		boolean levelChanged = pipelineCompilationAttempted && activeLevel != level;
		boolean dimensionChanged = pipelineCompilationAttempted
			&& !java.util.Objects.equals(activeDimension, dimension);
		// A different ClientLevel in the same dimension uses the exact same compiled
		// shader programs and vertex layout. Recompiling here was both unnecessary
		// and the cause of the sky-only second-world failure on Adreno. Retain the
		// graph and reset only its temporal/per-world state.
		if (levelChanged && !reloadRequested && !dimensionChanged) {
			try {
				resetForWorldTransition();
				activeLevel = level;
				Iris.logger.info("Reset Vulkan shader-pack state for a new world instance without recompiling pipelines");
				return;
			} catch (Throwable throwable) {
				// A failed temporal reset must not leave a half-reset graph alive. Fall
				// through to the normal full rebuild, which is slower but deterministic.
				Iris.logger.warn("Failed to reset Vulkan shader-pack state for the new world; rebuilding pipelines", throwable);
			}
		}
		if (reloadRequested || levelChanged || dimensionChanged) {
			ArrayList<String> reasons = new ArrayList<>(3);
			if (reloadRequested) {
				reasons.add("reload generation " + initializedPipelineGeneration + " => " + requestedGeneration);
			}
			if (levelChanged) reasons.add("world instance changed");
			if (dimensionChanged) reasons.add("dimension " + activeDimension + " => " + dimension);
			Iris.logger.info("Rebuilding Vulkan shader-pack pipeline ({})", String.join(", ", reasons));
			// Mojang keys its Vulkan shader cache by shader id/stage/defines, while
			// Iris intentionally reuses stable ids across resource-pack reloads and
			// dimension changes. Flush at this render-thread boundary so the new
			// source cannot reuse stale SPIR-V and so superseded native pipelines are
			// actually released. clearPipelineCache waits for the graphics queue.
			clearPipelineCacheSafely(activeDevice, null);
			closePipelineResources();
			pipelineCompilationAttempted = false;
			shaderPackDepthMode = false;
		}
		if (pipelineCompilationAttempted) return;
		pipelineCompilationAttempted = true;
		// Capture the generation before compilation. If a reload callback arrives
		// while compilation is in progress, the incremented requested generation is
		// deliberately left unmatched so the next render frame rebuilds again instead
		// of silently losing that request.
		initializedPipelineGeneration = requestedPipelineGeneration.get();
		activeDimension = dimension;
		activeLevel = level;
		// A newly-created shader pipeline must not inherit previous camera matrices,
		// smoothing accumulators or system-time counters from the pipeline it replaced.
		// The OpenGL PipelineManager resets the same shared timer/counter here.
		VulkanFrameState.resetForPipeline();

		try {
			if (Iris.getCurrentPack().isEmpty()) Iris.loadShaderpack();
			shaderPackDepthMode = Iris.getCurrentPack().isPresent();
			ShaderPrinter.resetPrintState();
			if (Iris.getCurrentPack().isEmpty()) {
				configureVanillaWorldRenderingSettings();
			}
			Iris.getCurrentPack().ifPresent(pack -> {
				ProgramSet programSet = pack.getProgramSet(dimension);
				validateVertexFragmentProfile(programSet);
				configureWorldRenderingSettings(pack, programSet);
				customUniforms = new VulkanCustomUniforms(pack, dimension);
				customTextureManager = new VulkanCustomTextureManager(activeDevice, pack,
					programSet.getPackDirectives());
				terrainPipelines = VulkanTerrainPipelineCompiler.compile(activeDevice, pack, dimension);
				terrainRenderState = new VulkanTerrainRenderState(activeDevice, terrainPipelines, customTextureManager, customUniforms);
				shadowPipelines = VulkanShadowPipelineCompiler.compile(activeDevice, pack, dimension);
				entityPipelines = VulkanEntityPipelineCompiler.compile(activeDevice, pack, dimension, terrainPipelines.renderTargets());
				handEntityPipelines = VulkanEntityPipelineCompiler.compileHand(activeDevice, pack, dimension, terrainPipelines.renderTargets());
				shadowEntityPipelines = VulkanEntityPipelineCompiler.compileShadow(activeDevice, pack, dimension,
					shadowPipelines.targets(), entityPipelines.pipelines().keySet());
				shadowRenderState = new VulkanShadowRenderState(activeDevice, shadowPipelines, shadowEntityPipelines, terrainRenderState);
				shadowCompositePipelines = VulkanCompositePipelineCompiler.compileShadow(
					activeDevice, pack, dimension, shadowPipelines.targets());
				shadowCompositeRenderState = new VulkanShadowCompositeRenderState(
					activeDevice, shadowCompositePipelines, shadowRenderState, terrainRenderState);
				terrainRenderState.setShadowRenderState(shadowRenderState);
				entityRenderState = new VulkanEntityRenderState(activeDevice, entityPipelines, terrainRenderState);
				handEntityRenderState = new VulkanEntityRenderState(activeDevice, handEntityPipelines, terrainRenderState);
				compositePipelines = VulkanCompositePipelineCompiler.compile(activeDevice, pack, dimension, terrainPipelines.renderTargets());
				terrainRenderState.setMipmappedTargets(compositePipelines.mipmappedTargets());
				compositeRenderState = new VulkanCompositeRenderState(
					activeDevice,
					compositePipelines,
					terrainRenderState,
					programSet.getPackDirectives().getCenterDepthHalfLife()
				);
				// Recreate Sodium only after every replacement pipeline and render target
				// is ready. Starting its workers before the several-second shader compile
				// let a reload report an empty build queue while its new render lists were
				// still transitioning, producing a persistent sky-only frame graph.
				boolean sodiumReloaded = reloadSodiumRendererIfRequired(WorldRenderingSettings.INSTANCE);
				// Vulkan image contents and staging uploads are only guaranteed to be
				// available after the command buffer which created them is submitted.
				// Keep the first renderLevel on vanilla for one frame so mobile drivers
				// never sample a freshly allocated shader-pack graph in the same submit.
				beginSodiumTerrainWarmup(sodiumReloaded);
			});
		} catch (Throwable throwable) {
			shaderPackDepthMode = false;
			// Compilation may have populated part of Mojang's native pipeline/shader
			// cache before a later program failed. Release that partial graph too.
			clearPipelineCacheSafely(activeDevice, throwable);
			closePipelineResources();
			try {
				configureVanillaWorldRenderingSettings();
			} catch (Throwable resetFailure) {
				throwable.addSuppressed(resetFailure);
			}
			Iris.logger.error("Failed to initialize the configured shader pack for the Vulkan runtime", throwable);
		}
	}

	private static void resetForWorldTransition() {
		VulkanFrameState.resetForPipeline();
		if (terrainRenderState != null) terrainRenderState.resetForWorld();
		if (compositeRenderState != null) compositeRenderState.resetForWorld();
		if (shaderPackDepthMode) {
			// A new ClientLevel owns a fresh Sodium renderer even when the compiled
			// shader graph can be reused.
			beginSodiumTerrainWarmup(true);
		} else {
			pipelineWarmupFramesRemaining = 0;
			sodiumTerrainWaitFrames = 0;
			sodiumTerrainStableFrames = 0;
			awaitingSodiumTerrain = false;
		}
		pipelineWarmupThisFrame = false;
		activeShadowRenderState = null;
		renderingHand = false;
		renderingLevel = false;
		renderPhase = WorldRenderingPhase.NONE;
	}

	/** Selects a stable vanilla or shader-pack depth convention for the whole renderLevel call. */
	public static void enterRenderLevel() {
		// Pipeline compilation happens at GameRenderer.renderLevel HEAD. Mark the
		// scope before compiling so VulkanConst builds Iris' replacement pipelines
		// with forward-Z, while later GUI item-atlas/PIP pipelines retain vanilla
		// reverse-Z.
		renderingLevel = true;
	}

	public static void prepareRenderLevel() {
		renderingLevel = true;
		if (!shaderPackDepthMode) {
			pipelineWarmupThisFrame = false;
			return;
		}
		if (pipelineWarmupFramesRemaining > 0) {
			pipelineWarmupThisFrame = true;
			return;
		}
		if (awaitingSodiumTerrain) {
			boolean ready = sodiumTerrainWaitFrames >= MIN_SODIUM_TERRAIN_WAIT_FRAMES
				&& isSodiumTerrainReady();
			sodiumTerrainStableFrames = ready ? sodiumTerrainStableFrames + 1 : 0;
		}
		if (awaitingSodiumTerrain && sodiumTerrainStableFrames >= SODIUM_TERRAIN_STABLE_FRAMES) {
			awaitingSodiumTerrain = false;
			Iris.logger.info(
				"Iris Vulkan Sodium terrain rebuild stabilized after {} warmup frame(s)",
				sodiumTerrainWaitFrames
			);
		} else if (awaitingSodiumTerrain && sodiumTerrainWaitFrames >= MAX_SODIUM_TERRAIN_WAIT_FRAMES) {
			awaitingSodiumTerrain = false;
			Iris.logger.warn(
				"Iris Vulkan timed out waiting for Sodium terrain after {} frames; activating the shader-pack graph",
				sodiumTerrainWaitFrames
			);
		}
		pipelineWarmupThisFrame = awaitingSodiumTerrain;
	}

	private static void beginSodiumTerrainWarmup(boolean awaitSodiumTerrain) {
		pipelineWarmupFramesRemaining = MIN_PIPELINE_WARMUP_FRAMES;
		sodiumTerrainWaitFrames = 0;
		sodiumTerrainStableFrames = 0;
		awaitingSodiumTerrain = awaitSodiumTerrain;
		sodiumTerrainQueryFailureLogged = false;
	}

	private static boolean isSodiumTerrainReady() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) return false;
		if (!(minecraft.levelRenderer instanceof LevelRendererExtension extension)) return true;
		SodiumWorldRenderer sodium = extension.sodium$getWorldRenderer();
		if (sodium == null) return false;
		try {
			// isTerrainRenderComplete alone may be true in the empty interval before
			// the freshly reloaded renderer schedules its first build jobs.
			return sodium.getVisibleChunkCount() > 0 && sodium.isTerrainRenderComplete();
		} catch (Throwable throwable) {
			if (!sodiumTerrainQueryFailureLogged) {
				Iris.logger.warn("Unable to query Sodium terrain rebuild state; continuing warmup", throwable);
				sodiumTerrainQueryFailureLogged = true;
			}
			return false;
		}
	}

	private static void configureWorldRenderingSettings(ShaderPack pack, ProgramSet programSet) {
		var settings = WorldRenderingSettings.INSTANCE;
		var idMap = pack.getIdMap();
		settings.setEntityIds(idMap.getEntityIdMap());
		settings.setItemIds(idMap.getItemIdMap());
		settings.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
			idMap.getBlockProperties(), idMap.getTagEntries()));
		settings.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(idMap.getBlockRenderTypeMap()));
		settings.setAmbientOcclusionLevel(programSet.getPackDirectives().getAmbientOcclusionLevel());
		settings.setUseSeparateAo(programSet.getPackDirectives().shouldUseSeparateAo());
		settings.setVoxelizeLightBlocks(programSet.getPackDirectives().shouldVoxelizeLightBlocks());
		settings.setSeparateEntityDraws(programSet.getPackDirectives().shouldUseSeparateEntityDraws());
		settings.setDisableDirectionalShading(!programSet.getPackDirectives().isOldLighting());
		settings.setBreaksAnisotropy(programSet.getPackDirectives().breaksAnisotropy());
		settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
	}

	private static void configureVanillaWorldRenderingSettings() {
		var settings = WorldRenderingSettings.INSTANCE;
		settings.setDisableDirectionalShading(false);
		settings.setUseSeparateAo(false);
		settings.setSeparateEntityDraws(false);
		settings.setAmbientOcclusionLevel(1.0F);
		settings.setVertexFormat(ChunkMeshFormats.COMPACT);
		settings.setVoxelizeLightBlocks(false);
		settings.setBreaksAnisotropy(false);
		settings.setBlockTypeIds(java.util.Map.of());
		reloadSodiumRendererIfRequired(settings);
	}

	private static boolean reloadSodiumRendererIfRequired(WorldRenderingSettings settings) {
		if (settings.isReloadRequired()) {
			Minecraft minecraft = Minecraft.getInstance();
			boolean reloaded = false;
			if (minecraft.levelRenderer instanceof LevelRendererExtension extension) {
				SodiumWorldRenderer sodium = extension.sodium$getWorldRenderer();
				if (sodium != null) {
					sodium.reload();
					sodium.scheduleTerrainUpdate();
					reloaded = true;
				}
			}
			if (minecraft.levelExtractor != null) minecraft.levelExtractor.allChanged();
			settings.clearReloadRequired();
			if (reloaded) {
				Iris.logger.info("Iris Vulkan restarted Sodium terrain after render-settings transition");
			}
			return reloaded;
		}
		return false;
	}

	static void validateVertexFragmentProfile(ProgramSet programSet) {
		java.util.LinkedHashSet<String> computePrograms = new java.util.LinkedHashSet<>();
		collectActiveCompute(computePrograms, programSet.getSetup());
		collectActiveCompute(computePrograms, programSet.getShadowCompute());
		collectActiveCompute(computePrograms, programSet.getFinalCompute());
		for (ProgramArrayId id : ProgramArrayId.values()) {
			for (ComputeSource[] group : programSet.getCompute(id)) {
				collectActiveCompute(computePrograms, group);
			}
		}
		if (!computePrograms.isEmpty()) {
			throw new UnsupportedOperationException(
				"Minecraft 26.2's public GPU pipeline does not expose compute dispatch; active shader-pack compute programs: "
					+ String.join(", ", computePrograms)
			);
		}
	}

	private static void collectActiveCompute(java.util.Set<String> names, ComputeSource[] sources) {
		for (ComputeSource source : sources) {
			if (source == null) continue;
			String glsl = source.getSource().orElse("");
			if (glsl.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")) names.add(source.getName());
		}
	}

	/** Marks all Vulkan shader-pack resources for safe render-thread reconstruction. */
	public static void requestPipelineRebuild() {
		requestedPipelineGeneration.incrementAndGet();
		shaderPackDepthMode = false;
		pipelineWarmupThisFrame = false;
	}

	private static void closePipelineResources() {
		VulkanShadowCompositeRenderState oldShadowComposite = shadowCompositeRenderState;
		shadowCompositeRenderState = null;
		closeResource("shadow composite state", oldShadowComposite);
		VulkanCompositeRenderState oldComposite = compositeRenderState;
		compositeRenderState = null;
		closeResource("composite state", oldComposite);
		VulkanEntityRenderState oldEntity = entityRenderState;
		entityRenderState = null;
		closeResource("entity state", oldEntity);
		VulkanEntityRenderState oldHandEntity = handEntityRenderState;
		handEntityRenderState = null;
		closeResource("hand entity state", oldHandEntity);
		VulkanShadowRenderState oldShadow = shadowRenderState;
		shadowRenderState = null;
		closeResource("shadow state", oldShadow);
		VulkanTerrainRenderState oldTerrain = terrainRenderState;
		terrainRenderState = null;
		closeResource("terrain state", oldTerrain);
		VulkanCustomTextureManager oldCustomTextures = customTextureManager;
		customTextureManager = null;
		closeResource("custom textures", oldCustomTextures);
		customUniforms = null;
		compositePipelines = null;
		entityPipelines = null;
		handEntityPipelines = null;
		shadowEntityPipelines = null;
		shadowPipelines = null;
		shadowCompositePipelines = null;
		terrainPipelines = null;
		activeShadowRenderState = null;
		renderingHand = false;
		offscreenEntityBypassLogged = false;
		pipelineWarmupFramesRemaining = 0;
		sodiumTerrainWaitFrames = 0;
		sodiumTerrainStableFrames = 0;
		awaitingSodiumTerrain = false;
		sodiumTerrainQueryFailureLogged = false;
		pipelineWarmupThisFrame = false;
		renderPhase = WorldRenderingPhase.NONE;
	}

	private static void clearPipelineCacheSafely(GpuDevice activeDevice, @Nullable Throwable parent) {
		try {
			activeDevice.clearPipelineCache();
		} catch (Throwable cacheFailure) {
			if (parent != null) {
				parent.addSuppressed(cacheFailure);
			} else {
				Iris.logger.warn("Failed to clear the Vulkan pipeline cache during reload; continuing with resource teardown", cacheFailure);
			}
		}
	}

	private static void closeResource(String name, @Nullable AutoCloseable resource) {
		if (resource == null) return;
		try {
			resource.close();
		} catch (Throwable closeFailure) {
			// Continue closing the rest of the graph. Leaving later textures and
			// samplers live is more dangerous than losing one already-retired handle.
			Iris.logger.warn("Failed to close Vulkan shader-pack {}; continuing teardown", name, closeFailure);
		}
	}

	public static void renderShadows(LevelRenderer levelRenderer) {
		if (!isShaderPackDepthMode()) return;
		VulkanShadowRenderState state = shadowRenderState;
		if (state != null) {
			state.renderTerrain(levelRenderer);
		}
	}

	public static void beginWorldFrame(GpuTextureView output, org.joml.Matrix4fc renderedModelView) {
		renderPhase = WorldRenderingPhase.NONE;
		if (pipelineWarmupThisFrame) {
			VulkanCompositeRenderState composite = compositeRenderState;
			if (composite != null) {
				composite.prepareTargets(output.getWidth(0), output.getHeight(0));
			}
			VulkanTerrainRenderState state = terrainRenderState;
			if (state != null) {
				state.beginWorldFrame(output.getWidth(0), output.getHeight(0));
			}
			if (pipelineWarmupFramesRemaining > 0) {
				pipelineWarmupFramesRemaining--;
				if (pipelineWarmupFramesRemaining == MIN_PIPELINE_WARMUP_FRAMES - 1) {
					Iris.logger.info("Iris Vulkan pipeline warmup: initialized shader-pack resources before first use");
				}
			} else if (awaitingSodiumTerrain) {
				sodiumTerrainWaitFrames++;
			}
			return;
		}
		VulkanFrameState.beginFrame(renderedModelView);
		VulkanCustomUniforms custom = customUniforms;
		if (custom != null) custom.beginFrame();
		VulkanTerrainRenderState state = terrainRenderState;
		if (state != null) state.beginWorldFrame(output.getWidth(0), output.getHeight(0));
		VulkanCompositeRenderState composite = compositeRenderState;
		if (composite != null) {
			composite.beginFrame();
			var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			composite.renderStage(VulkanCompositePipelineCompiler.PassStage.BEGIN,
				main.getColorTextureView(), main.getDepthTextureView());
		}
	}

	public static void renderPrepare() {
		if (!isShaderPackDepthMode()) return;
		renderCompositeStage(VulkanCompositePipelineCompiler.PassStage.PREPARE);
	}

	public static void renderDeferred() {
		if (!isShaderPackDepthMode()) return;
		renderCompositeStage(VulkanCompositePipelineCompiler.PassStage.DEFERRED);
	}

	public static void beginTranslucents() {
		if (!isShaderPackDepthMode()) return;
		VulkanTerrainRenderState state = terrainRenderState;
		if (state == null) return;
		var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		state.captureDepthStage(1, main.getDepthTextureView());
		renderDeferred();
	}

	public static void beginHand() {
		if (!isShaderPackDepthMode()) return;
		VulkanTerrainRenderState state = terrainRenderState;
		if (state == null) return;
		var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		VulkanCompositeRenderState composite = compositeRenderState;
		if (composite != null) composite.sampleCenterDepth(main.getDepthTextureView());
		state.captureDepthStage(2, main.getDepthTextureView());
	}

	private static void renderCompositeStage(VulkanCompositePipelineCompiler.PassStage stage) {
		VulkanCompositeRenderState composite = compositeRenderState;
		if (composite == null) return;
		var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		composite.renderStage(stage, main.getColorTextureView(), main.getDepthTextureView());
	}

	public static void renderShadowFeatures(FeatureRenderDispatcher.PreparedFrame frame) {
		if (!isShaderPackDepthMode()) return;
		VulkanShadowRenderState state = shadowRenderState;
		if (state != null) {
			state.renderFeatures(frame);
			VulkanShadowCompositeRenderState composite = shadowCompositeRenderState;
			if (composite != null) composite.renderAll();
			state.finishShadowFrame();
		}
	}

	public static void beginShadowPass(VulkanShadowRenderState state) {
		activeShadowRenderState = state;
	}

	public static void endShadowPass(VulkanShadowRenderState state) {
		if (activeShadowRenderState == state) {
			activeShadowRenderState = null;
		}
		// A cancelled/unsupported prepared feature can bypass its normal RETURN
		// phase hook. Never let a shadow replay's entity/terrain stage leak into
		// prepare or the first main-world draw.
		renderPhase = WorldRenderingPhase.NONE;
	}

	public static @Nullable VulkanShadowRenderState getActiveShadowRenderState() {
		return isShaderPackDepthMode() ? activeShadowRenderState : null;
	}

	public static boolean isRendererInitialized() {
		return rendererInitialized;
	}

	/** True while the Vulkan runtime owns rendering for a successfully selected shader pack. */
	public static boolean isShaderPackDepthMode() {
		return shaderPackDepthMode && !pipelineWarmupThisFrame;
	}

	/** True only for draws and pipeline creation owned by the level frame graph. */
	public static boolean usesShaderPackDepthConvention() {
		return isShaderPackDepthMode() && (renderingLevel || renderingHand || activeShadowRenderState != null);
	}

	public static @Nullable VulkanTerrainPipelineCompiler.CompiledTerrainPipelines getTerrainPipelines() {
		return terrainPipelines;
	}

	public static @Nullable VulkanTerrainRenderState getTerrainRenderState() {
		return isShaderPackDepthMode() ? terrainRenderState : null;
	}

	public static @Nullable VulkanEntityRenderState getEntityRenderState() {
		if (!isShaderPackDepthMode()) return null;
		// PreparedRenderType is also used by inventory previews, GUI item thumbnails
		// and other small off-screen targets after LevelRenderer has returned. Feeding
		// those draws through the world gbuffer resizes every colortex to the preview
		// size and destroys temporal history. Only replace entity pipelines while the
		// level (including Iris' early hand pass) is actually being rendered.
		if (!renderingHand && !renderingLevel) {
			if (entityRenderState != null && !offscreenEntityBypassLogged) {
				Iris.logger.info("Iris Vulkan bypassing shader-pack entity replacement outside level rendering");
				offscreenEntityBypassLogged = true;
			}
			return null;
		}
		return renderingHand && handEntityRenderState != null ? handEntityRenderState : entityRenderState;
	}

	public static void setRenderPhase(WorldRenderingPhase phase) {
		renderPhase = phase == null ? WorldRenderingPhase.NONE : phase;
	}

	public static WorldRenderingPhase getRenderPhase() {
		return renderPhase;
	}

	public static void clearRenderPhase(WorldRenderingPhase expected) {
		if (renderPhase == expected) renderPhase = WorldRenderingPhase.NONE;
	}

	public static void beginHandRendering() {
		beginHandRendering(WorldRenderingPhase.HAND_SOLID);
	}

	public static void beginHandRendering(WorldRenderingPhase phase) {
		renderingHand = true;
		renderPhase = phase;
	}

	public static void endHandRendering() {
		renderingHand = false;
		renderPhase = WorldRenderingPhase.NONE;
	}

	public static void renderFinal(GpuTextureView output, GpuTextureView depthTexture) {
		try {
			if (!isShaderPackDepthMode()) return;
			VulkanCompositeRenderState state = compositeRenderState;
			if (state != null) {
				state.renderToScreen(output, depthTexture);
			}
		} finally {
			// GUI item thumbnails and inventory player previews are submitted after
			// GameRenderer.renderLevel returns. They must keep Minecraft's original
			// GUI pipelines instead of writing into the world gbuffer after final.
			renderingLevel = false;
			renderingHand = false;
			renderPhase = WorldRenderingPhase.NONE;
		}
	}
}
