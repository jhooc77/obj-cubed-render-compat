package net.irisshaders.iris.vulkan.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
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
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.ObjCubedShaderInjector;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.vulkan.VulkanCapabilities;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformResult;
import net.irisshaders.iris.vulkan.shader.VulkanShaderResourceValidator;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vulkan.texture.VulkanTextureFormat;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Compiles shader-pack shadow terrain programs against Minecraft's Vulkan backend. */
public final class VulkanShadowPipelineCompiler {
	private VulkanShadowPipelineCompiler() {
	}

	public static CompiledShadowPipelines compile(GpuDevice device, ShaderPack pack, NamespacedId dimension) {
		var programSet = pack.getProgramSet(dimension);
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(programSet);
		PackShadowDirectives directives = programSet.getPackDirectives().getShadowDirectives();
		if (!directives.isShadowEnabled().orElse(true)) {
			return new CompiledShadowPipelines(Map.of(), Map.of(), Map.of(), directives, programSet.getPackDirectives().getSunPathRotation(), false);
		}

		EnumMap<ShadowPass, CompiledShadowPipeline> pipelines = new EnumMap<>(ShadowPass.class);
		EnumMap<ShadowPass, CompiledShadowPipeline> sodiumPipelines = new EnumMap<>(ShadowPass.class);
		EnumMap<ShadowPass, ProgramSource> sources = new EnumMap<>(ShadowPass.class);
		Map<Integer, ShadowTargetInfo> targets = new LinkedHashMap<>();
		for (ShadowPass pass : ShadowPass.values()) {
			if (pass == ShadowPass.TRANSLUCENT && !directives.shouldRenderTranslucent()) {
				continue;
			}
			ProgramSource source = resolver.resolve(pass.programId)
				.orElseThrow(() -> new IllegalStateException("Shader pack does not provide " + pass.programId + " or a fallback"));
			sources.put(pass, source);
			registerTargets(source, directives, targets);
		}
		// Entity/equipment programs can legally select additional shadowcolor
		// attachments. Allocate the complete set before the shadow textures exist.
		for (RenderPipeline original : VulkanEntityPipelineCompiler.supportedPipelines()) {
			ShaderKey key = IrisPipelines.getShadowPipeline(original);
			if (key == null) continue;
			ProgramSource source = resolver.resolve(key.getProgram()).orElse(null);
			if (source != null) registerTargets(source, directives, targets);
		}
		for (ProgramSource source : programSet.getComposite(ProgramArrayId.ShadowComposite)) {
			if (source != null && source.isValid()) registerTargets(source, directives, targets);
		}

		int[] opaqueTargets = unionTargets(sources.get(ShadowPass.SOLID), sources.get(ShadowPass.CUTOUT));
		int[] translucentTargets = unionTargets(sources.get(ShadowPass.TRANSLUCENT));
		for (Map.Entry<ShadowPass, ProgramSource> entry : sources.entrySet()) {
			ShadowPass pass = entry.getKey();
			int[] groupTargets = pass == ShadowPass.TRANSLUCENT ? translucentTargets : opaqueTargets;
			CompiledShadowPipeline compiled = compileOne(device, pass, entry.getValue(), targets, groupTargets,
				programSet.getPackDirectives().getTextureMap(), false);
			pipelines.put(pass, compiled);
			sodiumPipelines.put(pass, compileOne(device, pass, entry.getValue(), targets, groupTargets,
				programSet.getPackDirectives().getTextureMap(), true));
		}
		return new CompiledShadowPipelines(Map.copyOf(pipelines), Map.copyOf(sodiumPipelines), Map.copyOf(targets), directives,
			programSet.getPackDirectives().getSunPathRotation(), true);
	}

	private static CompiledShadowPipeline compileOne(
		GpuDevice device,
		ShadowPass pass,
		ProgramSource source,
		Map<Integer, ShadowTargetInfo> targets,
		int[] groupTargets,
		it.unimi.dsi.fastutil.objects.Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
		boolean sodium
	) {
		if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
			throw new UnsupportedOperationException("Vulkan shadow program '" + source.getName() + "' uses an unsupported geometry/tessellation stage");
		}

		int[] drawBuffers = source.getDirectives().getDrawBuffers();
		if (drawBuffers.length > VulkanCapabilities.maxColorAttachments()) {
			throw new UnsupportedOperationException(source.getName() + " needs " + drawBuffers.length + " shadow color targets");
		}
		List<GpuFormat> formats = Arrays.stream(groupTargets).mapToObj(target -> targets.get(target).format()).toList();

		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(pass.alphaTest);
		VertexFormat vertexFormat = sodium
			? WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat()
			: IrisVertexFormats.TERRAIN;
		Map<PatchShaderType, String> transformed = sodium
			? TransformPatcher.patchSodium(
				source.getName(), source.getVertexSource().orElseThrow(), null, null, null,
				source.getFragmentSource().orElseThrow(), alpha, textureMap, true)
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
		transformed = sodium
			? ObjCubedShaderInjector.injectVulkanSodiumTerrain(transformed, source.getName())
			: ObjCubedShaderInjector.injectTerrain(transformed, source.getName());
		if (transformed.get(PatchShaderType.VERTEX).contains("iris_ObjCubedDecode")) {
			VulkanCapabilities.requireObjCubedSubgroups(source.getName());
			transformed = ObjCubedShaderInjector.configureVulkanSubgroups(
				transformed, VulkanCapabilities.useNativeObjCubedQuad());
			Map<PatchShaderType, String> preprocessed = new EnumMap<>(PatchShaderType.class);
			preprocessed.putAll(transformed);
			preprocessed.put(PatchShaderType.VERTEX,
				JcppProcessor.glslPreprocessSource(transformed.get(PatchShaderType.VERTEX), List.of()));
			transformed = preprocessed;
		}

		int[] locations = remapOutputs(drawBuffers, groupTargets);
		VulkanShaderTransformResult shaders = sodium
			? VulkanShaderTransformer.transformSodiumTerrain(transformed, locations, vertexFormat)
			: VulkanShaderTransformer.transformTerrain(transformed, locations, vertexFormat);
		if (sodium) VulkanShaderResourceValidator.validateSodiumTerrain(shaders, source.getName());
		else VulkanShaderResourceValidator.validateTerrain(shaders, source.getName());
		Identifier shaderId = Identifier.fromNamespaceAndPath("iris", "vulkan/"
			+ (sodium ? "sodium_" : "") + "shadow_" + pass.name().toLowerCase());
		BindGroupLayout.Builder bindings = BindGroupLayout.builder();
		shaders.uniformBlocks().forEach(block -> bindings.withUniform(block, UniformType.UNIFORM_BUFFER));
		shaders.texelBuffers().forEach(buffer ->
			VulkanShaderResourceValidator.addTexelBufferBinding(bindings, shaders, buffer));
		shaders.samplers().forEach(bindings::withSampler);

		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(shaderId)
			.withVertexShader(shaderId)
			.withFragmentShader(shaderId)
			.withBindGroupLayout(bindings.build())
			.withVertexBinding(0, vertexFormat)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withCull(false);
		for (int index = 0; index < formats.size(); index++) {
			builder.withColorTargetState(index, new ColorTargetState(Optional.empty(), formats.get(index), ColorTargetState.WRITE_ALL));
		}

		String vertex = required(shaders, PatchShaderType.VERTEX);
		String fragment = required(shaders, PatchShaderType.FRAGMENT);
		ShaderPrinter.printProgram("vulkan_shadow_" + pass.name().toLowerCase() + "_" + source.getName())
			.addSources(shaders.sources())
			.addJson("{\"backend\":\"vulkan\",\"stage\":\"shadow\",\"program\":\"" + source.getName()
				+ "\",\"drawBuffers\":\"" + Arrays.toString(drawBuffers) + "\"}")
			.print();

		RenderPipeline pipeline = builder.build();
		CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, (id, type) -> {
			if (!shaderId.equals(id)) return null;
			return type == ShaderType.VERTEX ? vertex : fragment;
		});
		if (!compiled.isValid()) {
			throw new IllegalStateException("Minecraft's Vulkan backend rejected shadow pipeline " + source.getName() + ": " + device.getLastDebugMessages());
		}
		Iris.logger.info("Compiled Vulkan {}shadow pipeline '{}' (targets {})", sodium ? "Sodium " : "",
			source.getName(), Arrays.toString(drawBuffers));
		return new CompiledShadowPipeline(pass, source, pipeline, compiled, shaders, alpha, drawBuffers.clone(), groupTargets.clone(), formats);
	}

	private static void registerTargets(
		ProgramSource source,
		PackShadowDirectives directives,
		Map<Integer, ShadowTargetInfo> targets
	) {
		for (int target : source.getDirectives().getDrawBuffers()) {
			PackShadowDirectives.SamplingSettings settings = directives.getColorSamplingSettings()
				.computeIfAbsent(target, ignored -> new PackShadowDirectives.SamplingSettings());
			targets.putIfAbsent(target, new ShadowTargetInfo(
				VulkanTextureFormat.fromIris(settings.getFormat()),
				settings.getClear(),
				new org.joml.Vector4f(settings.getClearColor()),
				settings.getMipmap(),
				settings.getNearest()
			));
		}
	}

	private static int[] unionTargets(ProgramSource... sources) {
		Set<Integer> union = new LinkedHashSet<>();
		for (ProgramSource source : sources) {
			if (source != null) Arrays.stream(source.getDirectives().getDrawBuffers()).forEach(union::add);
		}
		if (union.size() > VulkanCapabilities.maxColorAttachments()) {
			throw new UnsupportedOperationException("Shadow render group needs " + union.size()
				+ " simultaneous color targets, but this Vulkan device supports " + VulkanCapabilities.maxColorAttachments());
		}
		return union.stream().mapToInt(Integer::intValue).toArray();
	}

	private static int[] remapOutputs(int[] programTargets, int[] groupTargets) {
		int[] locations = new int[programTargets.length];
		for (int output = 0; output < programTargets.length; output++) {
			locations[output] = -1;
			for (int attachment = 0; attachment < groupTargets.length; attachment++) {
				if (programTargets[output] == groupTargets[attachment]) {
					locations[output] = attachment;
					break;
				}
			}
			if (locations[output] < 0) throw new IllegalStateException("Shadow output target was not included in its render group");
		}
		return locations;
	}

	private static String required(VulkanShaderTransformResult shaders, PatchShaderType type) {
		String source = shaders.sources().get(type);
		if (source == null) throw new IllegalStateException("Missing transformed shadow " + type + " source");
		return source;
	}

	public record CompiledShadowPipelines(
		Map<ShadowPass, CompiledShadowPipeline> pipelines,
		Map<ShadowPass, CompiledShadowPipeline> sodiumPipelines,
		Map<Integer, ShadowTargetInfo> targets,
		PackShadowDirectives directives,
		float sunPathRotation,
		boolean enabled
	) {
		public CompiledShadowPipeline forOriginal(RenderPipeline original) {
			if (original == RenderPipelines.SOLID_TERRAIN || original == RenderPipelines.WIREFRAME) return pipelines.get(ShadowPass.SOLID);
			if (original == RenderPipelines.CUTOUT_TERRAIN) return pipelines.get(ShadowPass.CUTOUT);
			if (original == RenderPipelines.TRANSLUCENT_TERRAIN) return pipelines.get(ShadowPass.TRANSLUCENT);
			return null;
		}

		public CompiledShadowPipeline forSodium(ShadowPass pass) {
			return sodiumPipelines.get(pass);
		}
	}

	public record CompiledShadowPipeline(
		ShadowPass pass,
		ProgramSource source,
		RenderPipeline pipeline,
		CompiledRenderPipeline compiled,
		VulkanShaderTransformResult shaders,
		AlphaTest alphaTest,
		int[] drawBuffers,
		int[] groupTargets,
		List<GpuFormat> formats
	) {
		public CompiledShadowPipeline {
			drawBuffers = drawBuffers.clone();
			groupTargets = groupTargets.clone();
			formats = List.copyOf(formats);
		}
	}

	public record ShadowTargetInfo(
		GpuFormat format,
		boolean clear,
		org.joml.Vector4f clearColor,
		boolean mipmapped,
		boolean nearest
	) {
		public ShadowTargetInfo {
			clearColor = new org.joml.Vector4f(clearColor);
		}
	}

	public enum ShadowPass {
		SOLID(ProgramId.ShadowSolid, AlphaTests.OFF),
		CUTOUT(ProgramId.ShadowCutout, AlphaTests.ONE_TENTH_ALPHA),
		TRANSLUCENT(ProgramId.ShadowWater, AlphaTests.OFF);

		private final ProgramId programId;
		private final AlphaTest alphaTest;

		ShadowPass(ProgramId programId, AlphaTest alphaTest) {
			this.programId = programId;
			this.alphaTest = alphaTest;
		}
	}
}
