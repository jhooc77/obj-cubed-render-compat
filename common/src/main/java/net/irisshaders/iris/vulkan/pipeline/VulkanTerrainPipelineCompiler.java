package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ObjCubedShaderInjector;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.vulkan.VulkanCapabilities;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformResult;
import net.irisshaders.iris.vulkan.shader.VulkanShaderResourceValidator;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vulkan.texture.VulkanTextureFormat;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Compiles the first real shader-pack terrain pipeline through Minecraft's Vulkan backend. */
public final class VulkanTerrainPipelineCompiler {
	private VulkanTerrainPipelineCompiler() {
	}

	public static CompiledTerrainPipelines compile(GpuDevice device, ShaderPack pack, NamespacedId dimension) {
		ProgramSet programSet = pack.getProgramSet(dimension);
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(programSet);
		EnumMap<TerrainPass, ProgramSource> sources = new EnumMap<>(TerrainPass.class);

		for (TerrainPass pass : TerrainPass.values()) {
			ProgramSource source = resolver.resolve(pass.programId)
				.orElseThrow(() -> new IllegalStateException("Shader pack does not provide " + pass.programId + " or a fallback"));
			sources.put(pass, source);
		}

		Map<Integer, RenderTargetInfo> renderTargets = createRenderTargetInfo(programSet);
		EnumMap<ChunkSectionLayerGroup, TargetLayout> layouts = new EnumMap<>(ChunkSectionLayerGroup.class);
		layouts.put(ChunkSectionLayerGroup.OPAQUE, createLayout(
			renderTargets,
			sources.get(TerrainPass.SOLID).getDirectives().getDrawBuffers(),
			sources.get(TerrainPass.CUTOUT).getDirectives().getDrawBuffers()
		));
		layouts.put(ChunkSectionLayerGroup.TRANSLUCENT, createLayout(
			renderTargets,
			sources.get(TerrainPass.TRANSLUCENT).getDirectives().getDrawBuffers()
		));

		EnumMap<TerrainPass, CompiledTerrainPipeline> pipelines = new EnumMap<>(TerrainPass.class);
		EnumMap<TerrainPass, CompiledTerrainPipeline> sodiumPipelines = new EnumMap<>(TerrainPass.class);
		for (TerrainPass pass : TerrainPass.values()) {
			TargetLayout layout = layouts.get(pass.group);
			pipelines.put(pass, compileOne(device, pass, sources.get(pass), layout,
				programSet.getPackDirectives().getTextureMap(), false));
			sodiumPipelines.put(pass, compileOne(device, pass, sources.get(pass), layout,
				programSet.getPackDirectives().getTextureMap(), true));
		}
		return new CompiledTerrainPipelines(Map.copyOf(pipelines), Map.copyOf(sodiumPipelines), Map.copyOf(layouts), renderTargets,
			programSet.getPackDirectives());
	}

	private static CompiledTerrainPipeline compileOne(
		GpuDevice device,
		TerrainPass pass,
		ProgramSource source,
		TargetLayout layout,
		it.unimi.dsi.fastutil.objects.Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
		boolean sodium
	) {
		Identifier shaderId = Identifier.fromNamespaceAndPath("iris", "vulkan/"
			+ (sodium ? "sodium_" : "") + "gbuffers_" + pass.name().toLowerCase());

		if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
			throw new UnsupportedOperationException("Geometry and tessellation stages are not available through Minecraft 26.2's public Vulkan pipeline API");
		}

		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(pass.fallbackAlpha);
		VertexFormat vertexFormat = sodium
			? WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat()
			: IrisVertexFormats.TERRAIN;
		Map<PatchShaderType, String> irisTransformed = sodium
			? TransformPatcher.patchSodium(
				source.getName(), source.getVertexSource().orElseThrow(), null, null, null,
				source.getFragmentSource().orElseThrow(), alpha, textureMap, false)
			: TransformPatcher.patchVanilla(
				source.getName(),
				source.getVertexSource().orElseThrow(),
				null,
				null,
				null,
				source.getFragmentSource().orElseThrow(),
				alpha,
				false,
				false,
				true,
				new ShaderAttributeInputs(DefaultVertexFormat.BLOCK, false, false, false, false, false),
				textureMap
			);
		irisTransformed = sodium
			? ObjCubedShaderInjector.injectVulkanSodiumTerrain(irisTransformed, source.getName())
			: ObjCubedShaderInjector.injectTerrain(irisTransformed, source.getName());
		if (irisTransformed.get(PatchShaderType.VERTEX).contains("iris_ObjCubedDecode")) {
			VulkanCapabilities.requireObjCubedSubgroups(source.getName());
			irisTransformed = ObjCubedShaderInjector.configureVulkanSubgroups(
				irisTransformed, VulkanCapabilities.useNativeObjCubedQuad());
			Map<PatchShaderType, String> preprocessed = new EnumMap<>(PatchShaderType.class);
			preprocessed.putAll(irisTransformed);
			preprocessed.put(
				PatchShaderType.VERTEX,
				JcppProcessor.glslPreprocessSource(irisTransformed.get(PatchShaderType.VERTEX), List.of())
			);
			irisTransformed = preprocessed;
		}

		int[] outputLocations = remapOutputs(source.getDirectives().getDrawBuffers(), layout.logicalTargets());
		VulkanShaderTransformResult transformed = sodium
			? VulkanShaderTransformer.transformSodiumTerrain(irisTransformed, outputLocations, vertexFormat)
			: VulkanShaderTransformer.transformTerrain(irisTransformed, outputLocations, vertexFormat);
		if (sodium) VulkanShaderResourceValidator.validateSodiumTerrain(transformed, source.getName());
		else VulkanShaderResourceValidator.validateTerrain(transformed, source.getName());
		String vertexSource = requiredSource(transformed, PatchShaderType.VERTEX);
		String fragmentSource = requiredSource(transformed, PatchShaderType.FRAGMENT);
		ShaderPrinter.printProgram("vulkan_" + pass.name().toLowerCase() + "_" + source.getName())
			.addSources(transformed.sources())
			.addJson(debugMetadata(source, layout, outputLocations))
			.print();

		BindGroupLayout.Builder bindings = BindGroupLayout.builder();
		for (String uniformBlock : transformed.uniformBlocks()) {
			bindings.withUniform(uniformBlock, UniformType.UNIFORM_BUFFER);
		}
		for (String texelBuffer : transformed.texelBuffers()) {
			VulkanShaderResourceValidator.addTexelBufferBinding(bindings, transformed, texelBuffer);
		}
		for (String sampler : transformed.samplers()) {
			bindings.withSampler(sampler);
		}

		RenderPipeline.Builder pipelineBuilder = RenderPipeline.builder()
			.withLocation(shaderId)
			.withVertexShader(shaderId)
			.withFragmentShader(shaderId)
			.withBindGroupLayout(bindings.build())
			.withVertexBinding(0, vertexFormat)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withDepthStencilState(DepthStencilState.DEFAULT);

		for (int index = 0; index < layout.logicalTargets().length; index++) {
			Optional<BlendFunction> blend = pass.translucent ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty();
			if (source.getDirectives().getBlendModeOverride().isPresent()) {
				BlendMode mode = source.getDirectives().getBlendModeOverride().get().getBlendMode();
				blend = mode == null ? Optional.empty() : Optional.of(VulkanBlendFunction.fromIris(mode));
			}
			int logicalTarget = layout.logicalTargets()[index];
			for (BufferBlendInformation override : source.getDirectives().getBufferBlendOverrides()) {
				if (override.index() == logicalTarget) {
					blend = override.blendMode() == null
						? Optional.empty() : Optional.of(VulkanBlendFunction.fromIris(override.blendMode()));
				}
			}
			pipelineBuilder.withColorTargetState(index, new ColorTargetState(blend, layout.formats().get(index), ColorTargetState.WRITE_ALL));
		}

		RenderPipeline pipeline = pipelineBuilder.build();
		CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, (id, type) -> {
			if (!shaderId.equals(id)) {
				return null;
			}
			return type == ShaderType.VERTEX ? vertexSource : fragmentSource;
		});

		if (!compiled.isValid()) {
			throw new IllegalStateException("Minecraft's Vulkan backend rejected the transformed terrain pipeline: " + device.getLastDebugMessages());
		}

		Iris.logger.info(
			"Compiled Vulkan {}shader-pack terrain pipeline '{}' ({} uniform blocks, {} samplers, {} loose uniforms, {} color targets)",
			sodium ? "Sodium " : "",
			source.getName(),
			transformed.uniformBlocks().size(),
			transformed.samplers().size(),
			transformed.looseUniforms().size(),
			layout.logicalTargets().length
		);

		return new CompiledTerrainPipeline(pass, pipeline, compiled, transformed, source, alpha);
	}

	private static String debugMetadata(ProgramSource source, TargetLayout layout, int[] outputLocations) {
		String formats = layout.formats().stream()
			.map(format -> "\"" + format.name() + "\"")
			.collect(java.util.stream.Collectors.joining(","));
		return """
			{
			  "backend": "vulkan",
			  "program": "%s",
			  "programDrawBuffers": %s,
			  "groupDrawBuffers": %s,
			  "outputLocations": %s,
			  "formats": [%s]
			}
			""".formatted(
				source.getName().replace("\\", "\\\\").replace("\"", "\\\""),
				Arrays.toString(source.getDirectives().getDrawBuffers()),
				Arrays.toString(layout.logicalTargets()),
				Arrays.toString(outputLocations),
				formats
			);
	}

	private static Map<Integer, RenderTargetInfo> createRenderTargetInfo(ProgramSet programSet) {
		Map<Integer, RenderTargetInfo> result = new java.util.LinkedHashMap<>();
		programSet.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings().forEach((index, settings) -> {
			Optional<Vector4f> clearColor = settings.getClearColor().map(Vector4f::new);
			result.put(index, new RenderTargetInfo(
				VulkanTextureFormat.fromIris(settings.getInternalFormat()),
				settings.shouldClear(),
				clearColor
			));
		});
		return Map.copyOf(result);
	}

	private static TargetLayout createLayout(Map<Integer, RenderTargetInfo> renderTargets, int[]... programTargets) {
		Set<Integer> union = new LinkedHashSet<>();
		for (int[] targets : programTargets) {
			Arrays.stream(targets).forEach(union::add);
		}
		if (union.size() > VulkanCapabilities.maxColorAttachments()) {
			throw new UnsupportedOperationException(
				"Terrain render group needs " + union.size() + " simultaneous color targets, but this Vulkan device supports " + VulkanCapabilities.maxColorAttachments()
			);
		}

		int[] logicalTargets = union.stream().mapToInt(Integer::intValue).toArray();
		List<GpuFormat> formats = new ArrayList<>(logicalTargets.length);
		for (int logicalTarget : logicalTargets) {
			RenderTargetInfo info = renderTargets.get(logicalTarget);
			if (info == null) {
				throw new IllegalArgumentException("Shader program writes unsupported colortex" + logicalTarget);
			}
			formats.add(info.format());
		}
		return new TargetLayout(logicalTargets, List.copyOf(formats));
	}

	private static int[] remapOutputs(int[] programTargets, int[] groupTargets) {
		int[] result = new int[programTargets.length];
		for (int output = 0; output < programTargets.length; output++) {
			int mapped = -1;
			for (int attachment = 0; attachment < groupTargets.length; attachment++) {
				if (programTargets[output] == groupTargets[attachment]) {
					mapped = attachment;
					break;
				}
			}
			if (mapped < 0) {
				throw new IllegalStateException("Missing group attachment for colortex" + programTargets[output]);
			}
			result[output] = mapped;
		}
		return result;
	}

	private static String requiredSource(VulkanShaderTransformResult transformed, PatchShaderType type) {
		String source = transformed.sources().get(type);
		if (source == null) {
			throw new IllegalStateException("Missing transformed " + type + " source");
		}
		return source;
	}

	public record CompiledTerrainPipeline(
		TerrainPass pass,
		RenderPipeline pipeline,
		CompiledRenderPipeline compiled,
		VulkanShaderTransformResult shaders,
		ProgramSource source,
		AlphaTest alphaTest
	) {
	}

	public record CompiledTerrainPipelines(
		Map<TerrainPass, CompiledTerrainPipeline> pipelines,
		Map<TerrainPass, CompiledTerrainPipeline> sodiumPipelines,
		Map<ChunkSectionLayerGroup, TargetLayout> layouts,
		Map<Integer, RenderTargetInfo> renderTargets,
		PackDirectives packDirectives
	) {
		public CompiledTerrainPipeline forOriginal(RenderPipeline original) {
			if (original == RenderPipelines.SOLID_TERRAIN || original == RenderPipelines.WIREFRAME) {
				return pipelines.get(TerrainPass.SOLID);
			}
			if (original == RenderPipelines.CUTOUT_TERRAIN) {
				return pipelines.get(TerrainPass.CUTOUT);
			}
			if (original == RenderPipelines.TRANSLUCENT_TERRAIN) {
				return pipelines.get(TerrainPass.TRANSLUCENT);
			}
			return null;
		}

		public CompiledTerrainPipeline forSodium(TerrainPass pass) {
			return sodiumPipelines.get(pass);
		}
	}

	public record TargetLayout(int[] logicalTargets, List<GpuFormat> formats) {
		public TargetLayout {
			logicalTargets = logicalTargets.clone();
			formats = List.copyOf(formats);
		}
	}

	public record RenderTargetInfo(GpuFormat format, boolean clear, Optional<Vector4f> clearColor) {
	}

	public enum TerrainPass {
		SOLID(ProgramId.TerrainSolid, AlphaTests.OFF, false, ChunkSectionLayerGroup.OPAQUE),
		CUTOUT(ProgramId.TerrainCutout, AlphaTests.HALF_ALPHA, false, ChunkSectionLayerGroup.OPAQUE),
		TRANSLUCENT(ProgramId.Water, AlphaTests.OFF, true, ChunkSectionLayerGroup.TRANSLUCENT);

		private final ProgramId programId;
		private final AlphaTest fallbackAlpha;
		private final boolean translucent;
		private final ChunkSectionLayerGroup group;

		TerrainPass(ProgramId programId, AlphaTest fallbackAlpha, boolean translucent, ChunkSectionLayerGroup group) {
			this.programId = programId;
			this.fallbackAlpha = fallbackAlpha;
			this.translucent = translucent;
			this.group = group;
		}
	}
}
