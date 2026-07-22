package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vulkan.VulkanFrameState;
import net.irisshaders.iris.vulkan.VulkanDiagnostics;
import net.irisshaders.iris.vulkan.texture.VulkanCustomTextureManager;
import net.irisshaders.iris.vulkan.uniforms.VulkanShaderpackUniforms;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Executes shader-pack fullscreen programs at the same world-render lifecycle points as Iris. */
public final class VulkanCompositeRenderState implements AutoCloseable {
	private static final Pattern COLORTEX = Pattern.compile("colortex(\\d+)");

	private final GpuDevice device;
	private final VulkanCompositePipelineCompiler.CompiledCompositePipelines pipelines;
	private final VulkanTerrainRenderState worldTargets;
	private final Map<VulkanCompositePipelineCompiler.CompiledCompositePipeline, GpuBuffer> uniformBuffers = new HashMap<>();
	private final Map<VulkanCompositePipelineCompiler.CompiledCompositePipeline, VulkanShaderpackUniforms> uniformUploaders = new HashMap<>();
	private final GpuBuffer fullscreenVertices;
	private final GpuSampler sampler;
	private final VulkanMipmapGenerator mipmapGenerator;
	private final @Nullable VulkanCenterDepthSampler centerDepthSampler;
	private final List<VulkanCompositePipelineCompiler.CompiledCompositePipeline> allPipelines;
	private final Set<Integer> neededTargets;
	private final EnumSet<VulkanCompositePipelineCompiler.PassStage> executedStages =
		EnumSet.noneOf(VulkanCompositePipelineCompiler.PassStage.class);

	public VulkanCompositeRenderState(
		GpuDevice device,
		VulkanCompositePipelineCompiler.CompiledCompositePipelines pipelines,
		VulkanTerrainRenderState worldTargets,
		float centerDepthHalfLife
	) {
		this.device = device;
		this.pipelines = pipelines;
		this.worldTargets = worldTargets;
		this.allPipelines = buildAllPipelines(pipelines);
		this.neededTargets = collectNeededTargets(allPipelines);
		VulkanCompositePipelineCompiler.CompiledCompositePipeline diagnosticPipeline = !VulkanDiagnostics.enabled() ? null : allPipelines.stream()
			.filter(pipeline -> hasLooseUniform(pipeline, "frameTime")
				&& hasLooseUniform(pipeline, "gbufferProjection"))
			.findFirst()
			.orElseGet(() -> allPipelines.stream()
				.filter(pipeline -> pipeline.shaders().looseUniformBufferSize() > 0)
				.findFirst().orElse(null));
		for (VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline : allPipelines) {
			int size = pipeline.shaders().looseUniformBufferSize();
			if (size > 0) {
				uniformBuffers.put(pipeline, createZeroUniformBuffer(size, pipeline.source().getName()));
				String diagnosticName = pipeline == diagnosticPipeline ? pipeline.source().getName() : null;
				uniformUploaders.put(pipeline, new VulkanShaderpackUniforms(
					pipeline.shaders(), worldTargets.customUniforms(), 0.0F, false, diagnosticName));
			}
		}
		this.fullscreenVertices = createFullscreenVertices();
		this.sampler = device.createSampler(
			AddressMode.CLAMP_TO_EDGE,
			AddressMode.CLAMP_TO_EDGE,
			FilterMode.LINEAR,
			FilterMode.LINEAR,
			1,
			OptionalDouble.empty()
		);
		this.mipmapGenerator = new VulkanMipmapGenerator(device);
		this.centerDepthSampler = usesCenterDepth(pipelines)
			? new VulkanCenterDepthSampler(device, centerDepthHalfLife) : null;
	}

	public void beginFrame() {
		executedStages.clear();
	}

	public void resetForWorld() {
		executedStages.clear();
		if (centerDepthSampler != null) centerDepthSampler.resetForWorld();
	}

	/** Allocates the complete graph without executing it, allowing one submit of warmup. */
	public void prepareTargets(int width, int height) {
		worldTargets.ensureTargets(width, height, neededTargets);
	}

	public void sampleCenterDepth(GpuTextureView depthTexture) {
		if (centerDepthSampler != null) centerDepthSampler.sample(depthTexture);
	}

	public void renderStage(
		VulkanCompositePipelineCompiler.PassStage stage,
		GpuTextureView output,
		GpuTextureView depthTexture
	) {
		if (stage == VulkanCompositePipelineCompiler.PassStage.FINAL || !executedStages.add(stage)) return;
		worldTargets.applyExplicitFlips(pipelines.preFlips().getOrDefault(stage, Map.of()));
		int width = output.getWidth(0);
		int height = output.getHeight(0);
		worldTargets.ensureTargets(width, height, neededTargets);

		for (VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline : pipelines.passes()) {
			if (pipeline.stage() != stage) continue;
			Map<Integer, DiagnosticWriteView> diagnosticWriteViews = captureDiagnosticWriteViews(pipeline);
			logDiagnosticBindings(pipeline);
			CommandEncoder encoder = device.createCommandEncoder();
			generateMipmaps(encoder, pipeline);
			updateUniforms(encoder, pipeline, width, height);
			try (RenderPass pass = worldTargets.createCompositeRenderPass(
				encoder,
				() -> "Iris Vulkan " + pipeline.source().getName(),
				pipeline.layout()
			)) {
				bindPipeline(pass, pipeline, depthTexture);
				pass.draw(4, 1, 0, 0);
			}
			worldTargets.finishCompositePass(pipeline.layout());
			queuePassOutputProbes(pipeline, diagnosticWriteViews);
		}
	}

	public void renderToScreen(GpuTextureView output, GpuTextureView depthTexture) {
		if (VulkanDiagnostics.enabled() && VulkanFrameState.frameCounter() <= 3) {
			worldTargets.queueDiagnosticProbe("pre-composite-colortex0", worldTargets.getColorSampleTextureView(0));
			worldTargets.queueDiagnosticProbe("pre-composite-colortex1", worldTargets.getColorSampleTextureView(1));
			worldTargets.queueDiagnosticProbe("pre-composite-colortex3", worldTargets.getColorSampleTextureView(3));
			worldTargets.queueDiagnosticProbe("pre-composite-colortex6", worldTargets.getColorSampleTextureView(6));
		}
		renderStage(VulkanCompositePipelineCompiler.PassStage.COMPOSITE, output, depthTexture);
		if (!executedStages.add(VulkanCompositePipelineCompiler.PassStage.FINAL)) return;
		int width = output.getWidth(0);
		int height = output.getHeight(0);
		worldTargets.ensureTargets(width, height, neededTargets);
		VulkanCompositePipelineCompiler.CompiledCompositePipeline finalPass = pipelines.finalPass();
		logDiagnosticBindings(finalPass);
		CommandEncoder encoder = device.createCommandEncoder();
		generateMipmaps(encoder, finalPass);
		updateUniforms(encoder, finalPass, width, height);
		try (RenderPass pass = encoder.createRenderPass(
			() -> "Iris Vulkan final",
			output,
			Optional.empty(),
			null,
			OptionalDouble.empty()
		)) {
			bindPipeline(pass, finalPass, depthTexture);
			pass.draw(4, 1, 0, 0);
		}
		if (VulkanDiagnostics.enabled() && VulkanFrameState.frameCounter() <= 3) {
			worldTargets.queueDiagnosticProbe("final-output", output);
		}
		worldTargets.preserveHistoryForNextFrame();
	}

	private Map<Integer, DiagnosticWriteView> captureDiagnosticWriteViews(
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline
	) {
		if (!VulkanDiagnostics.enabled() || VulkanFrameState.frameCounter() > 3) return Map.of();
		Map<Integer, DiagnosticWriteView> views = new LinkedHashMap<>();
		for (int target : pipeline.layout().logicalTargets()) {
			if (target < 0) continue;
			GpuTextureView view = worldTargets.getColorWriteTextureViewForDiagnostics(target);
			if (view != null) {
				views.put(target, new DiagnosticWriteView(
					view, worldTargets.colorWriteSideForDiagnostics(target)
				));
			}
		}
		return views;
	}

	private void queuePassOutputProbes(
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline,
		Map<Integer, DiagnosticWriteView> writtenViews
	) {
		if (writtenViews.isEmpty()) return;
		String passName = pipeline.source().getName().replaceAll("[^a-zA-Z0-9_.-]", "_");
		String prefix = "after-" + pipeline.stage().name().toLowerCase(java.util.Locale.ROOT) + "-" + passName;
		writtenViews.forEach((target, written) -> {
			GpuTextureView writtenView = written.view();
			GpuTextureView sampledView = worldTargets.getColorSampleTextureView(target);
			worldTargets.queueDiagnosticProbe(
				prefix + "-written-colortex" + target + "-" + written.side(),
				writtenView
			);
			if (sampledView != null && sampledView.texture() != writtenView.texture()) {
				worldTargets.queueDiagnosticProbe(
					prefix + "-sample-colortex" + target + "-" + worldTargets.colorSampleSideForDiagnostics(target),
					sampledView
				);
			}
		});
	}

	private record DiagnosticWriteView(GpuTextureView view, String side) {
	}

	private void logDiagnosticBindings(
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline
	) {
		if (!VulkanDiagnostics.enabled() || VulkanFrameState.frameCounter() != 1) return;
		List<String> reads = new ArrayList<>();
		for (String samplerName : pipeline.shaders().samplers()) {
			Integer target = logicalTarget(samplerName);
			if (target != null) {
				reads.add(samplerName + "=colortex" + target + ":" + worldTargets.colorSampleSideForDiagnostics(target));
			}
		}
		List<String> writes = Arrays.stream(pipeline.layout().logicalTargets())
			.filter(target -> target >= 0)
			.mapToObj(target -> "colortex" + target + ":" + worldTargets.colorWriteSideForDiagnostics(target))
			.toList();
		Iris.logger.info(
			"Iris Vulkan pass bindings: frame={} stage={} pass={} reads={} writes={} flipAfter={}",
			VulkanFrameState.frameCounter(), pipeline.stage(), pipeline.source().getName(), reads, writes,
			Arrays.toString(pipeline.layout().flipTargets())
		);
	}

	private void bindPipeline(
		RenderPass pass,
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline,
		GpuTextureView depthTexture
	) {
		boolean waterShadowEnabled = pipeline.shaders().samplers().contains("watershadow");
		pass.setPipeline(pipeline.pipeline());
		RenderSystem.bindDefaultUniforms(pass);
		GpuBuffer uniforms = uniformBuffers.get(pipeline);
		if (uniforms != null && pipeline.shaders().uniformBlocks().contains(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK)) {
			pass.setUniform(VulkanShaderTransformer.LOOSE_UNIFORM_BLOCK, uniforms);
		}
		for (String shaderSampler : pipeline.shaders().samplers()) {
			TextureStage textureStage = pipeline.stage().textureStage();
			VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(textureStage, shaderSampler);
			GpuSampler selectedSampler = custom != null ? custom.sampler() : sampler;
			if (custom == null && shaderSampler.equals("iris_centerDepthSmooth") && centerDepthSampler != null) {
				selectedSampler = centerDepthSampler.sampler();
			}
			if (custom == null && worldTargets.shadowTextureView(shaderSampler, waterShadowEnabled) != null) {
				String type = pipeline.shaders().samplerTypes().get(shaderSampler);
				GpuSampler shadowSampler = worldTargets.shadowSampler(shaderSampler,
					(type != null && type.toLowerCase(java.util.Locale.ROOT).contains("shadow")) || shaderSampler.endsWith("HW"),
					waterShadowEnabled);
				if (shadowSampler != null) selectedSampler = shadowSampler;
			}
			pass.bindTexture(shaderSampler,
				selectTexture(shaderSampler, depthTexture, textureStage, waterShadowEnabled), selectedSampler);
		}
		pass.setVertexBuffer(0, fullscreenVertices.slice());
	}

	private GpuTextureView selectTexture(String samplerName, GpuTextureView depthTexture, TextureStage textureStage,
									 boolean waterShadowEnabled) {
		VulkanCustomTextureManager.Binding custom = worldTargets.customTexture(textureStage, samplerName);
		if (custom != null) return custom.view();
		if (samplerName.equals("iris_centerDepthSmooth") && centerDepthSampler != null) {
			return centerDepthSampler.textureView();
		}
		Integer target = logicalTarget(samplerName);
		if (target != null) {
			GpuTextureView view = worldTargets.getColorSampleTextureView(target);
			if (view != null) {
				return view;
			}
		}
		if (samplerName.equals("depthtex0") || samplerName.equals("depthtex1") || samplerName.equals("depthtex2") || samplerName.equals("gdepthtex")) {
			return worldTargets.depthTextureView(samplerName, depthTexture);
		}
		GpuTextureView shadow = worldTargets.shadowTextureView(samplerName, waterShadowEnabled);
		if (shadow != null) return shadow;
		return worldTargets.fallbackTextureView();
	}

	private static Set<Integer> collectNeededTargets(
		List<VulkanCompositePipelineCompiler.CompiledCompositePipeline> allPipelines
	) {
		Set<Integer> targets = new HashSet<>();
		for (VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline : allPipelines) {
			for (int target : pipeline.layout().logicalTargets()) {
				if (target >= 0) {
					targets.add(target);
				}
			}
			for (String samplerName : pipeline.shaders().samplers()) {
				Integer target = logicalTarget(samplerName);
				if (target != null) {
					targets.add(target);
				}
			}
		}
		return Set.copyOf(targets);
	}

	private static Integer logicalTarget(String samplerName) {
		Matcher matcher = COLORTEX.matcher(samplerName);
		if (matcher.matches()) {
			return Integer.parseInt(matcher.group(1));
		}
		int legacy = PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf(samplerName);
		return legacy >= 0 ? legacy : null;
	}

	private void updateUniforms(
		CommandEncoder encoder,
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline,
		int width,
		int height
	) {
		GpuBuffer buffer = uniformBuffers.get(pipeline);
		VulkanShaderpackUniforms uploader = uniformUploaders.get(pipeline);
		if (buffer != null && uploader != null) {
			uploader.update(encoder, buffer, width, height);
		}
	}

	private void generateMipmaps(
		CommandEncoder encoder,
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline
	) {
		for (int target : pipeline.layout().mipmappedBuffers()) {
			GpuTextureView view = worldTargets.getColorSampleTextureView(target);
			if (view == null) {
				throw new IllegalStateException(pipeline.source().getName() + " requests mipmaps for missing colortex" + target);
			}
			mipmapGenerator.generateColor(encoder, view.texture());
		}
	}

	private static List<VulkanCompositePipelineCompiler.CompiledCompositePipeline> buildAllPipelines(
		VulkanCompositePipelineCompiler.CompiledCompositePipelines pipelines
	) {
		java.util.ArrayList<VulkanCompositePipelineCompiler.CompiledCompositePipeline> result = new java.util.ArrayList<>(pipelines.passes());
		result.add(pipelines.finalPass());
		return List.copyOf(result);
	}

	private static boolean hasLooseUniform(
		VulkanCompositePipelineCompiler.CompiledCompositePipeline pipeline,
		String name
	) {
		return pipeline.shaders().looseUniforms().stream().anyMatch(member -> member.name().equals(name));
	}

	private static boolean usesCenterDepth(VulkanCompositePipelineCompiler.CompiledCompositePipelines pipelines) {
		if (pipelines.finalPass().shaders().samplers().contains("iris_centerDepthSmooth")) return true;
		return pipelines.passes().stream()
			.anyMatch(pass -> pass.shaders().samplers().contains("iris_centerDepthSmooth"));
	}

	private GpuBuffer createFullscreenVertices() {
		ByteBuffer data = MemoryUtil.memAlloc(4 * 5 * Float.BYTES);
		try {
			putVertex(data, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
			putVertex(data, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
			putVertex(data, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f);
			putVertex(data, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f);
			data.flip();
			return device.createBuffer(() -> "Iris Vulkan fullscreen quad", GpuBuffer.USAGE_VERTEX, data);
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private static void putVertex(ByteBuffer data, float x, float y, float z, float u, float v) {
		data.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
	}

	private GpuBuffer createZeroUniformBuffer(int size, String name) {
		ByteBuffer zeroes = MemoryUtil.memCalloc(size);
		try {
			return device.createBuffer(
				() -> "Iris Vulkan fullscreen uniforms for " + name,
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				zeroes
			);
		} finally {
			MemoryUtil.memFree(zeroes);
		}
	}

	@Override
	public void close() {
		uniformBuffers.values().forEach(GpuBuffer::close);
		uniformBuffers.clear();
		uniformUploaders.values().forEach(VulkanShaderpackUniforms::close);
		uniformUploaders.clear();
		fullscreenVertices.close();
		sampler.close();
		mipmapGenerator.close();
		if (centerDepthSampler != null) centerDepthSampler.close();
	}
}
