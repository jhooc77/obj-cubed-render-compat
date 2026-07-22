package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.vulkan.VulkanCapabilities;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformResult;
import net.irisshaders.iris.vulkan.shader.VulkanShaderResourceValidator;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** Compiles the complete vertex/fragment fullscreen graph for the Vulkan path. */
public final class VulkanCompositePipelineCompiler {
	private VulkanCompositePipelineCompiler() {
	}

	public static CompiledCompositePipelines compile(
		GpuDevice device,
		ShaderPack pack,
		NamespacedId dimension,
		Map<Integer, VulkanTerrainPipelineCompiler.RenderTargetInfo> renderTargetInfo
	) {
		ProgramSet programSet = pack.getProgramSet(dimension);
		Map<Integer, GpuFormat> targetFormats = new LinkedHashMap<>();
		renderTargetInfo.forEach((target, info) -> targetFormats.put(target, info.format()));
		List<CompiledCompositePipeline> passes = new ArrayList<>();
		appendArray(device, passes, programSet.getComposite(ProgramArrayId.Begin), PassStage.BEGIN,
			targetFormats, programSet.getPackDirectives().getTextureMap());
		appendArray(device, passes, programSet.getComposite(ProgramArrayId.Prepare), PassStage.PREPARE,
			targetFormats, programSet.getPackDirectives().getTextureMap());
		appendArray(device, passes, programSet.getComposite(ProgramArrayId.Deferred), PassStage.DEFERRED,
			targetFormats, programSet.getPackDirectives().getTextureMap());
		appendArray(device, passes, programSet.getComposite(ProgramArrayId.Composite), PassStage.COMPOSITE,
			targetFormats, programSet.getPackDirectives().getTextureMap());

		ProgramSource finalSource = programSet.get(ProgramId.Final)
			.orElseThrow(() -> new IllegalStateException("Shader pack does not provide a valid final program"));
		CompiledCompositePipeline finalPass = compileOne(device, finalSource, PassStage.FINAL, passes.size(),
			targetFormats, programSet.getPackDirectives().getTextureMap());
		EnumMap<PassStage, Map<Integer, Boolean>> preFlips = new EnumMap<>(PassStage.class);
		preFlips.put(PassStage.BEGIN, Map.copyOf(programSet.getPackDirectives().getExplicitFlips("begin_pre")));
		preFlips.put(PassStage.PREPARE, Map.copyOf(programSet.getPackDirectives().getExplicitFlips("prepare_pre")));
		preFlips.put(PassStage.DEFERRED, Map.copyOf(programSet.getPackDirectives().getExplicitFlips("deferred_pre")));
		preFlips.put(PassStage.COMPOSITE, Map.copyOf(programSet.getPackDirectives().getExplicitFlips("composite_pre")));
		return new CompiledCompositePipelines(List.copyOf(passes), finalPass, Map.copyOf(preFlips));
	}

	public static CompiledShadowCompositePipelines compileShadow(
		GpuDevice device,
		ShaderPack pack,
		NamespacedId dimension,
		Map<Integer, VulkanShadowPipelineCompiler.ShadowTargetInfo> renderTargetInfo
	) {
		if (renderTargetInfo.isEmpty()) {
			return new CompiledShadowCompositePipelines(List.of(), Map.of());
		}
		ProgramSet programSet = pack.getProgramSet(dimension);
		Map<Integer, GpuFormat> targetFormats = new LinkedHashMap<>();
		renderTargetInfo.forEach((target, info) -> targetFormats.put(target, info.format()));
		List<CompiledCompositePipeline> passes = new ArrayList<>();
		appendArray(device, passes, programSet.getComposite(ProgramArrayId.ShadowComposite),
			PassStage.SHADOWCOMP, targetFormats, programSet.getPackDirectives().getTextureMap());
		return new CompiledShadowCompositePipelines(
			List.copyOf(passes),
			Map.copyOf(programSet.getPackDirectives().getExplicitFlips("shadowcomp_pre"))
		);
	}

	private static void appendArray(
		GpuDevice device,
		List<CompiledCompositePipeline> output,
		ProgramSource[] sources,
		PassStage stage,
		Map<Integer, GpuFormat> renderTargetFormats,
		it.unimi.dsi.fastutil.objects.Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
	) {
		for (ProgramSource source : sources) {
			if (source == null || source.getVertexSource().isEmpty() || source.getFragmentSource().isEmpty()) {
				continue;
			}
			output.add(compileOne(device, source, stage, output.size(), renderTargetFormats, textureMap));
		}
	}

	private static CompiledCompositePipeline compileOne(
		GpuDevice device,
		ProgramSource source,
		PassStage stage,
		int ordinal,
		Map<Integer, GpuFormat> renderTargetFormats,
		it.unimi.dsi.fastutil.objects.Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
	) {
		if (source.getGeometrySource().isPresent()) {
			throw new UnsupportedOperationException("Geometry stages are not available for Vulkan fullscreen programs");
		}

		int[] logicalTargets = stage == PassStage.FINAL ? new int[]{-1} : source.getDirectives().getDrawBuffers();
		if (logicalTargets.length > VulkanCapabilities.maxColorAttachments()) {
			throw new UnsupportedOperationException(source.getName() + " needs " + logicalTargets.length + " simultaneous color targets");
		}
		List<GpuFormat> formats = Arrays.stream(logicalTargets).mapToObj(target -> {
			if (target < 0) {
				return GpuFormat.RGBA8_UNORM;
			}
			GpuFormat format = renderTargetFormats.get(target);
			if (format == null) {
				String targetName = stage == PassStage.SHADOWCOMP ? "shadowcolor" : "colortex";
				throw new IllegalArgumentException(source.getName() + " writes unsupported " + targetName + target);
			}
			return format;
		}).toList();
		Set<Integer> flipTargets = new LinkedHashSet<>();
		if (stage != PassStage.FINAL) {
			for (int target : logicalTargets) {
				if (source.getDirectives().getExplicitFlips().get(target) != Boolean.FALSE) {
					flipTargets.add(target);
				}
			}
			source.getDirectives().getExplicitFlips().forEach((target, shouldFlip) -> {
				if (shouldFlip) {
					if (!flipTargets.add(target)) {
						flipTargets.remove(target);
					}
				}
			});
		}
		TargetLayout layout = new TargetLayout(
			logicalTargets,
			formats,
			flipTargets.stream().mapToInt(Integer::intValue).toArray(),
			source.getDirectives().getViewportScale(),
			Set.copyOf(source.getDirectives().getMipmappedBuffers())
		);

		TextureStage textureStage = stage.textureStage();
		Map<PatchShaderType, String> irisTransformed = TransformPatcher.patchComposite(
			source.getName(),
			source.getVertexSource().orElseThrow(),
			null,
			source.getFragmentSource().orElseThrow(),
			textureStage,
			textureMap
		);
		int[] outputLocations = new int[logicalTargets.length];
		for (int i = 0; i < outputLocations.length; i++) {
			outputLocations[i] = i;
		}
		VulkanShaderTransformResult transformed = VulkanShaderTransformer.transform(irisTransformed, outputLocations);
		VulkanShaderResourceValidator.validateFullscreen(transformed, source.getName());
		String pathName = source.getName().replaceAll("[^a-zA-Z0-9_./-]", "_");
		Identifier shaderId = Identifier.fromNamespaceAndPath("iris",
			"vulkan/fullscreen/" + stage.name().toLowerCase() + "_" + ordinal + "_" + pathName);

		BindGroupLayout.Builder bindings = BindGroupLayout.builder();
		transformed.uniformBlocks().forEach(block -> bindings.withUniform(block, UniformType.UNIFORM_BUFFER));
		transformed.texelBuffers().forEach(buffer ->
			VulkanShaderResourceValidator.addTexelBufferBinding(bindings, transformed, buffer));
		transformed.samplers().forEach(bindings::withSampler);

		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(shaderId)
			.withVertexShader(shaderId)
			.withFragmentShader(shaderId)
			.withBindGroupLayout(bindings.build())
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
			.withCull(false);

		for (int attachment = 0; attachment < logicalTargets.length; attachment++) {
			Optional<BlendFunction> blend = Optional.empty();
			if (source.getDirectives().getBlendModeOverride().isPresent()) {
				BlendMode mode = source.getDirectives().getBlendModeOverride().get().getBlendMode();
				blend = mode == null ? Optional.empty() : Optional.of(VulkanBlendFunction.fromIris(mode));
			}
			if (logicalTargets[attachment] >= 0) {
				for (BufferBlendInformation override : source.getDirectives().getBufferBlendOverrides()) {
					if (override.index() == logicalTargets[attachment]) {
						blend = override.blendMode() == null
							? Optional.empty() : Optional.of(VulkanBlendFunction.fromIris(override.blendMode()));
					}
				}
			}
			builder.withColorTargetState(attachment, new ColorTargetState(blend, formats.get(attachment), ColorTargetState.WRITE_ALL));
		}

		String vertexSource = requiredSource(transformed, PatchShaderType.VERTEX);
		String fragmentSource = requiredSource(transformed, PatchShaderType.FRAGMENT);
		ShaderPrinter.printProgram("vulkan_" + stage.name().toLowerCase() + "_" + source.getName())
			.addSources(transformed.sources())
			.addJson(debugMetadata(source, stage, layout))
			.print();

		RenderPipeline replacement = builder.build();
		CompiledRenderPipeline compiled = device.precompilePipeline(replacement, (id, type) -> {
			if (!shaderId.equals(id)) {
				return null;
			}
			return type == ShaderType.VERTEX ? vertexSource : fragmentSource;
		});
		if (!compiled.isValid()) {
			throw new IllegalStateException("Minecraft's Vulkan backend rejected " + source.getName() + ": " + device.getLastDebugMessages());
		}
		Iris.logger.info("Compiled Vulkan {} pipeline '{}' (targets {})", stage, source.getName(), Arrays.toString(logicalTargets));
		return new CompiledCompositePipeline(source, stage, replacement, compiled, transformed, layout);
	}

	private static String requiredSource(VulkanShaderTransformResult transformed, PatchShaderType type) {
		String source = transformed.sources().get(type);
		if (source == null) {
			throw new IllegalStateException("Missing transformed " + type + " source");
		}
		return source;
	}

	private static String debugMetadata(ProgramSource source, PassStage stage, TargetLayout layout) {
		return """
			{"backend":"vulkan","stage":"%s","program":"%s","drawBuffers":%s,"formats":[%s]}
			""".formatted(
				stage, source.getName(), Arrays.toString(layout.logicalTargets()),
				layout.formats().stream().map(format -> "\"" + format.name() + "\"").collect(java.util.stream.Collectors.joining(","))
			);
	}

	public enum PassStage {
		SHADOWCOMP(TextureStage.SHADOWCOMP),
		BEGIN(TextureStage.BEGIN),
		PREPARE(TextureStage.PREPARE),
		DEFERRED(TextureStage.DEFERRED),
		COMPOSITE(TextureStage.COMPOSITE_AND_FINAL),
		FINAL(TextureStage.COMPOSITE_AND_FINAL);

		private final TextureStage textureStage;

		PassStage(TextureStage textureStage) {
			this.textureStage = textureStage;
		}

		public TextureStage textureStage() {
			return textureStage;
		}
	}

	public record CompiledCompositePipeline(
		ProgramSource source,
		PassStage stage,
		RenderPipeline pipeline,
		CompiledRenderPipeline compiled,
		VulkanShaderTransformResult shaders,
		TargetLayout layout
	) {
	}

	public record CompiledCompositePipelines(
		List<CompiledCompositePipeline> passes,
		CompiledCompositePipeline finalPass,
		Map<PassStage, Map<Integer, Boolean>> preFlips
	) {
		public CompiledCompositePipelines {
			passes = List.copyOf(passes);
			preFlips = Map.copyOf(preFlips);
		}

		public Set<Integer> mipmappedTargets() {
			Set<Integer> targets = new LinkedHashSet<>();
			passes.forEach(pass -> targets.addAll(pass.layout().mipmappedBuffers()));
			targets.addAll(finalPass.layout().mipmappedBuffers());
			return Set.copyOf(targets);
		}
	}

	public record CompiledShadowCompositePipelines(
		List<CompiledCompositePipeline> passes,
		Map<Integer, Boolean> preFlips
	) {
		public CompiledShadowCompositePipelines {
			passes = List.copyOf(passes);
			preFlips = Map.copyOf(preFlips);
		}

		public Set<Integer> mipmappedTargets() {
			Set<Integer> targets = new LinkedHashSet<>();
			passes.forEach(pass -> targets.addAll(pass.layout().mipmappedBuffers()));
			return Set.copyOf(targets);
		}
	}

	public record TargetLayout(
		int[] logicalTargets,
		List<GpuFormat> formats,
		int[] flipTargets,
		ViewportData viewport,
		Set<Integer> mipmappedBuffers
	) {
		public TargetLayout {
			logicalTargets = logicalTargets.clone();
			formats = List.copyOf(formats);
			flipTargets = flipTargets.clone();
			mipmappedBuffers = Set.copyOf(mipmappedBuffers);
		}
	}
}
