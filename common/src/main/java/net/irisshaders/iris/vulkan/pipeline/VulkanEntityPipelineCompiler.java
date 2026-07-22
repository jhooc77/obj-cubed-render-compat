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
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.ObjCubedShaderInjector;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.vulkan.VulkanCapabilities;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformResult;
import net.irisshaders.iris.vulkan.shader.VulkanShaderResourceValidator;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

/** Compiles the immediate entity/item/equipment pipelines that carry obj-cubed data. */
public final class VulkanEntityPipelineCompiler {
	private static final List<RenderPipeline> ENTITY_PIPELINES = List.of(
		RenderPipelines.SOLID_BLOCK,
		RenderPipelines.CUTOUT_BLOCK,
		RenderPipelines.TRANSLUCENT_BLOCK,
		RenderPipelines.ARMOR_CUTOUT_NO_CULL,
		RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL,
		RenderPipelines.ARMOR_TRANSLUCENT,
		RenderPipelines.ENTITY_SOLID,
		RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
		RenderPipelines.ENTITY_CUTOUT_CULL,
		RenderPipelines.ENTITY_CUTOUT,
		RenderPipelines.ENTITY_CUTOUT_Z_OFFSET,
		RenderPipelines.ENTITY_CUTOUT_DISSOLVE,
		RenderPipelines.ENTITY_TRANSLUCENT,
		RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE,
		RenderPipelines.ENTITY_TRANSLUCENT_CULL,
		RenderPipelines.ENTITY_SHADOW,
		RenderPipelines.END_CRYSTAL_BEAM,
		RenderPipelines.BANNER_PATTERN,
		RenderPipelines.BREEZE_WIND,
		RenderPipelines.ENERGY_SWIRL,
		RenderPipelines.EYES,
		RenderPipelines.ITEM_CUTOUT,
		RenderPipelines.ITEM_TRANSLUCENT,
		RenderPipelines.BEACON_BEAM_OPAQUE,
		RenderPipelines.BEACON_BEAM_TRANSLUCENT,
		RenderPipelines.LEASH,
		RenderPipelines.WATER_MASK,
		RenderPipelines.GLINT,
		RenderPipelines.CRUMBLING,
		RenderPipelines.TEXT,
		RenderPipelines.TEXT_POLYGON_OFFSET,
		RenderPipelines.TEXT_SEE_THROUGH,
		RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH,
		RenderPipelines.TEXT_BACKGROUND,
		RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH,
		RenderPipelines.TEXT_GRAYSCALE,
		RenderPipelines.LIGHTNING,
		RenderPipelines.DRAGON_RAYS,
		RenderPipelines.END_PORTAL,
		RenderPipelines.END_GATEWAY,
		RenderPipelines.FLAT_CLOUDS,
		RenderPipelines.CLOUDS,
		RenderPipelines.LINES,
		RenderPipelines.LINES_TRANSLUCENT,
		RenderPipelines.SECONDARY_BLOCK_OUTLINE,
		RenderPipelines.WORLD_BORDER,
		RenderPipelines.OPAQUE_PARTICLE,
		RenderPipelines.TRANSLUCENT_PARTICLE,
		RenderPipelines.WEATHER_DEPTH_WRITE,
		RenderPipelines.WEATHER_NO_DEPTH_WRITE,
		RenderPipelines.SKY,
		RenderPipelines.END_SKY,
		RenderPipelines.SUNRISE_SUNSET,
		RenderPipelines.STARS,
		RenderPipelines.CELESTIAL,
		RenderPipelines.BLOCK_SCREEN_EFFECT,
		RenderPipelines.FIRE_SCREEN_EFFECT
	);
	private static final Set<RenderPipeline> OBJ_REQUIRED_PIPELINES = Set.of(
		RenderPipelines.ARMOR_CUTOUT_NO_CULL,
		RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL,
		RenderPipelines.ARMOR_TRANSLUCENT,
		RenderPipelines.ENTITY_SOLID,
		RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
		RenderPipelines.ENTITY_CUTOUT_CULL,
		RenderPipelines.ENTITY_CUTOUT,
		RenderPipelines.ENTITY_CUTOUT_Z_OFFSET,
		RenderPipelines.ENTITY_CUTOUT_DISSOLVE,
		RenderPipelines.ENTITY_TRANSLUCENT,
		RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE,
		RenderPipelines.ENTITY_TRANSLUCENT_CULL,
		RenderPipelines.ITEM_CUTOUT,
		RenderPipelines.ITEM_TRANSLUCENT
	);

	private VulkanEntityPipelineCompiler() {
	}

	public static List<RenderPipeline> supportedPipelines() {
		return ENTITY_PIPELINES;
	}

	public static CompiledEntityPipelines compile(
		GpuDevice device,
		ShaderPack pack,
		NamespacedId dimension,
		Map<Integer, VulkanTerrainPipelineCompiler.RenderTargetInfo> renderTargetInfo
	) {
		var programSet = pack.getProgramSet(dimension);
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(programSet);
		Map<RenderPipeline, CompiledEntityPipeline> result = new LinkedHashMap<>();
		for (RenderPipeline original : ENTITY_PIPELINES) {
			ShaderKey key = mainShaderKey(original);
			if (key == null) {
				continue;
			}
			ProgramSource source = resolver.resolve(key.getProgram())
				.orElseThrow(() -> new IllegalStateException("Shader pack does not provide " + key.getProgram() + " or a fallback"));
			try {
				result.put(original, compileOne(device, original, key, source, target -> {
					VulkanTerrainPipelineCompiler.RenderTargetInfo info = renderTargetInfo.get(target);
					if (info == null) throw new IllegalArgumentException("Entity program writes unsupported colortex" + target);
					return info.format();
				}, false, true, programSet.getPackDirectives().getTextureMap()));
			} catch (RuntimeException exception) {
				if (OBJ_REQUIRED_PIPELINES.contains(original)) throw exception;
				Iris.logger.warn("Vulkan shader-pack fallback: could not compile optional pipeline '{}'", original.getLocation(), exception);
			}
		}
		return new CompiledEntityPipelines(Map.copyOf(result));
	}

	private static ShaderKey mainShaderKey(RenderPipeline pipeline) {
		if (pipeline == RenderPipelines.BLOCK_SCREEN_EFFECT || pipeline == RenderPipelines.FIRE_SCREEN_EFFECT) {
			return ShaderKey.TEXTURED_COLOR;
		}
		return IrisPipelines.getPipeline(null, pipeline);
	}

	public static CompiledEntityPipelines compileHand(
		GpuDevice device,
		ShaderPack pack,
		NamespacedId dimension,
		Map<Integer, VulkanTerrainPipelineCompiler.RenderTargetInfo> renderTargetInfo
	) {
		var programSet = pack.getProgramSet(dimension);
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(programSet);
		Map<RenderPipeline, CompiledEntityPipeline> result = new LinkedHashMap<>();
		for (RenderPipeline original : ENTITY_PIPELINES) {
			ShaderKey key = handShaderKey(original);
			if (key == null) continue;
			ProgramSource source = resolver.resolve(key.getProgram()).orElse(null);
			if (source == null) continue;
			try {
				result.put(original, compileOne(device, original, key, source, target -> {
					VulkanTerrainPipelineCompiler.RenderTargetInfo info = renderTargetInfo.get(target);
					if (info == null) throw new IllegalArgumentException("Hand program writes unsupported colortex" + target);
					return info.format();
				}, false, true, programSet.getPackDirectives().getTextureMap()));
			} catch (RuntimeException exception) {
				Iris.logger.warn("Vulkan shader-pack fallback: could not compile hand pipeline '{}'", original.getLocation(), exception);
			}
		}
		return new CompiledEntityPipelines(Map.copyOf(result));
	}

	private static ShaderKey handShaderKey(RenderPipeline pipeline) {
		if (pipeline == RenderPipelines.ENTITY_SOLID || pipeline == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD) {
			return ShaderKey.HAND_CUTOUT;
		}
		if (pipeline == RenderPipelines.ENTITY_CUTOUT || pipeline == RenderPipelines.ENTITY_CUTOUT_CULL
			|| pipeline == RenderPipelines.ENTITY_CUTOUT_Z_OFFSET || pipeline == RenderPipelines.ENTITY_CUTOUT_DISSOLVE
			|| pipeline == RenderPipelines.ITEM_CUTOUT || pipeline == RenderPipelines.ARMOR_CUTOUT_NO_CULL
			|| pipeline == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
			return ShaderKey.HAND_CUTOUT_DIFFUSE;
		}
		if (pipeline == RenderPipelines.ENTITY_TRANSLUCENT || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_CULL
			|| pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE || pipeline == RenderPipelines.ITEM_TRANSLUCENT
			|| pipeline == RenderPipelines.ARMOR_TRANSLUCENT) {
			return ShaderKey.HAND_WATER_DIFFUSE;
		}
		if (pipeline == RenderPipelines.TEXT || pipeline == RenderPipelines.TEXT_POLYGON_OFFSET
			|| pipeline == RenderPipelines.TEXT_GRAYSCALE) return ShaderKey.HAND_TEXT;
		if (pipeline == RenderPipelines.TEXT_SEE_THROUGH || pipeline == RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH) {
			return ShaderKey.HAND_TEXT_TRANSLUCENT;
		}
		if (pipeline == RenderPipelines.GLINT) return ShaderKey.GLINT;
		return null;
	}

	public static CompiledEntityPipelines compileShadow(
		GpuDevice device,
		ShaderPack pack,
		NamespacedId dimension,
		Map<Integer, VulkanShadowPipelineCompiler.ShadowTargetInfo> shadowTargets,
		Set<RenderPipeline> originals
	) {
		var programSet = pack.getProgramSet(dimension);
		ProgramFallbackResolver resolver = new ProgramFallbackResolver(programSet);
		Map<RenderPipeline, CompiledEntityPipeline> result = new LinkedHashMap<>();
		for (RenderPipeline original : originals) {
			ShaderKey key = IrisPipelines.getShadowPipeline(original);
			if (key == null) continue;
			ProgramSource source = resolver.resolve(key.getProgram())
				.orElseThrow(() -> new IllegalStateException("Shader pack does not provide " + key.getProgram() + " or a fallback"));
			try {
				result.put(original, compileOne(device, original, key, source, target -> {
					VulkanShadowPipelineCompiler.ShadowTargetInfo info = shadowTargets.get(target);
					if (info == null) throw new IllegalArgumentException("Shadow entity program writes unsupported shadowcolor" + target);
					return info.format();
				}, true, true, programSet.getPackDirectives().getTextureMap()));
			} catch (RuntimeException exception) {
				if (OBJ_REQUIRED_PIPELINES.contains(original)) throw exception;
				Iris.logger.warn("Vulkan shader-pack fallback: could not compile optional shadow pipeline '{}'", original.getLocation(), exception);
			}
		}
		return new CompiledEntityPipelines(Map.copyOf(result));
	}

	private static CompiledEntityPipeline compileOne(
		GpuDevice device,
		RenderPipeline original,
		ShaderKey key,
		ProgramSource source,
		IntFunction<GpuFormat> targetFormat,
		boolean shadow,
		boolean extendedVertexInputs,
		it.unimi.dsi.fastutil.objects.Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
	) {
		if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
			throw new UnsupportedOperationException("Geometry and tessellation entity stages are not available through Minecraft 26.2's public Vulkan pipeline API");
		}

		int[] drawBuffers = source.getDirectives().getDrawBuffers();
		if (drawBuffers.length > VulkanCapabilities.maxColorAttachments()) {
			throw new UnsupportedOperationException("Entity program needs " + drawBuffers.length + " simultaneous color targets");
		}
		List<GpuFormat> formats = Arrays.stream(drawBuffers)
			.mapToObj(targetFormat)
			.toList();
		TargetLayout layout = new TargetLayout(drawBuffers, formats);

		VertexFormat originalVertexFormat = original.getVertexFormatBinding(0);
		VertexFormat vertexFormat = extendedVertexInputs
			? (original == RenderPipelines.CRUMBLING
				? IrisVertexFormats.CRUMBLING
				: extendImmediateVertexFormat(originalVertexFormat))
			: originalVertexFormat;
		VertexFormat shaderVertexFormat = vertexFormat == null ? key.getVertexFormat() : vertexFormat;
		if (shaderVertexFormat == null) {
			throw new IllegalArgumentException("Entity pipeline has no shader attribute format: " + original.getLocation());
		}
		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(key.getAlphaTest());
		boolean lines = original.getPrimitiveTopology() == PrimitiveTopology.LINES
			|| original.getPrimitiveTopology() == PrimitiveTopology.DEBUG_LINES
			|| original.getPrimitiveTopology() == PrimitiveTopology.DEBUG_LINE_STRIP;
		ShaderAttributeInputs attributeInputs = new ShaderAttributeInputs(
			shaderVertexFormat, key.shouldIgnoreLightmap(), lines, key.isGlint(), key.isText(), false);
		boolean objCubedCrumbling = original == RenderPipelines.CRUMBLING;
		Map<PatchShaderType, String> irisTransformed = (objCubedCrumbling
			? TransformPatcher.patchVanillaWithoutObjCubed(
				key.getName(),
				source.getVertexSource().orElseThrow(),
				null,
				null,
				null,
				source.getFragmentSource().orElseThrow(),
				alpha,
				lines,
				false,
				true,
				attributeInputs,
				textureMap)
			: TransformPatcher.patchVanilla(
			key.getName(),
			source.getVertexSource().orElseThrow(),
			null,
			null,
			null,
			source.getFragmentSource().orElseThrow(),
			alpha,
			lines,
			false,
			true,
			attributeInputs,
			textureMap
		));
		if (objCubedCrumbling) {
			irisTransformed = ObjCubedShaderInjector.injectCrumbling(irisTransformed, key.getName());
		}
		// ObjCubedShaderInjector intentionally emits a small macro bridge after Iris'
		// normal transform pass. Expand that bridge before the Vulkan AST parser;
		// shaderc must receive the same subgroup decoder that OpenGL would preprocess.
		if (irisTransformed.get(PatchShaderType.VERTEX).contains("iris_ObjCubedDecode")) {
			VulkanCapabilities.requireObjCubedSubgroups(key.getName());
			irisTransformed = ObjCubedShaderInjector.configureVulkanSubgroups(
				irisTransformed, VulkanCapabilities.useNativeObjCubedQuad());
			Map<PatchShaderType, String> preprocessed = new java.util.EnumMap<>(PatchShaderType.class);
			preprocessed.putAll(irisTransformed);
			preprocessed.put(
				PatchShaderType.VERTEX,
				JcppProcessor.glslPreprocessSource(irisTransformed.get(PatchShaderType.VERTEX), List.of())
			);
			irisTransformed = preprocessed;
		}

		int[] identityLocations = new int[drawBuffers.length];
		for (int i = 0; i < identityLocations.length; i++) {
			identityLocations[i] = i;
		}
		VulkanShaderTransformResult transformed = vertexFormat == null
			? VulkanShaderTransformer.transform(irisTransformed, identityLocations)
			: VulkanShaderTransformer.transform(irisTransformed, identityLocations, vertexFormat);
		VulkanShaderResourceValidator.validateDraw(transformed, key.getName());
		String pipelineKind = shadow ? "shadow_entity" : extendedVertexInputs ? "entity" : "hand_entity";
		Identifier shaderId = Identifier.fromNamespaceAndPath(
			"iris",
			"vulkan/" + pipelineKind + "/" + original.getLocation().getNamespace() + "/" + original.getLocation().getPath()
		);

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
			.withPrimitiveTopology(original.getPrimitiveTopology())
			.withPolygonMode(original.getPolygonMode())
			.withCull(shadow ? false : original.isCull())
			.withDepthStencilState(shadow ? Optional.of(com.mojang.blaze3d.pipeline.DepthStencilState.DEFAULT)
				: Optional.ofNullable(original.getDepthStencilState()));
		if (vertexFormat != null) {
			builder.withVertexBinding(0, vertexFormat);
		}

		for (int attachment = 0; attachment < drawBuffers.length; attachment++) {
			Optional<BlendFunction> blend = shadow || original.getColorTargetState() == null
				? Optional.empty()
				: original.getColorTargetState().blendFunction();
			if (source.getDirectives().getBlendModeOverride().isPresent()) {
				BlendMode mode = source.getDirectives().getBlendModeOverride().get().getBlendMode();
				blend = mode == null ? Optional.empty() : Optional.of(VulkanBlendFunction.fromIris(mode));
			}
			for (BufferBlendInformation override : source.getDirectives().getBufferBlendOverrides()) {
				if (override.index() == drawBuffers[attachment]) {
					blend = override.blendMode() == null
						? Optional.empty() : Optional.of(VulkanBlendFunction.fromIris(override.blendMode()));
				}
			}
			builder.withColorTargetState(attachment, new ColorTargetState(blend, formats.get(attachment), ColorTargetState.WRITE_ALL));
		}

		String vertexSource = requiredSource(transformed, PatchShaderType.VERTEX);
		String fragmentSource = requiredSource(transformed, PatchShaderType.FRAGMENT);
		ShaderPrinter.printProgram("vulkan_" + pipelineKind + "_" + original.getLocation().getPath().replace('/', '_'))
			.addSources(transformed.sources())
			.addJson(debugMetadata(original, key, source, layout))
			.print();

		RenderPipeline replacement = builder.build();
		CompiledRenderPipeline compiled = device.precompilePipeline(replacement, (id, type) -> {
			if (!shaderId.equals(id)) {
				return null;
			}
			return type == ShaderType.VERTEX ? vertexSource : fragmentSource;
		});
		if (!compiled.isValid()) {
			throw new IllegalStateException("Minecraft's Vulkan backend rejected entity pipeline " + original.getLocation() + ": " + device.getLastDebugMessages());
		}

		Iris.logger.info("Compiled Vulkan {} pipeline '{}' as '{}' (targets {})", pipelineKind.replace('_', ' '), original.getLocation(), key, Arrays.toString(drawBuffers));
		return new CompiledEntityPipeline(original, replacement, compiled, key, source, transformed, layout, alpha);
	}

	private static VertexFormat extendImmediateVertexFormat(VertexFormat format) {
		if (format == null) return null;
		if (format.equals(IrisVertexFormats.CRUMBLING)) {
			return IrisVertexFormats.CRUMBLING;
		}
		if (format.equals(DefaultVertexFormat.BLOCK) || format.equals(IrisVertexFormats.TERRAIN)) {
			return IrisVertexFormats.TERRAIN;
		}
		if (format.equals(DefaultVertexFormat.ENTITY) || format.equals(IrisVertexFormats.ENTITY)) {
			return IrisVertexFormats.ENTITY;
		}
		if (format.equals(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR) || format.equals(IrisVertexFormats.GLYPH)) {
			return IrisVertexFormats.GLYPH;
		}
		return format;
	}

	private static String requiredSource(VulkanShaderTransformResult transformed, PatchShaderType type) {
		String source = transformed.sources().get(type);
		if (source == null) {
			throw new IllegalStateException("Missing transformed " + type + " source");
		}
		return source;
	}

	private static String debugMetadata(RenderPipeline original, ShaderKey key, ProgramSource source, TargetLayout layout) {
		return """
			{
			  "backend": "vulkan",
			  "originalPipeline": "%s",
			  "shaderKey": "%s",
			  "program": "%s",
			  "drawBuffers": %s,
			  "formats": [%s]
			}
			""".formatted(
				original.getLocation(), key, source.getName(), Arrays.toString(layout.logicalTargets()),
				layout.formats().stream().map(format -> "\"" + format.name() + "\"").collect(java.util.stream.Collectors.joining(","))
			);
	}

	public record CompiledEntityPipeline(
		RenderPipeline original,
		RenderPipeline replacement,
		CompiledRenderPipeline compiled,
		ShaderKey key,
		ProgramSource source,
		VulkanShaderTransformResult shaders,
		TargetLayout layout,
		AlphaTest alphaTest
	) {
	}

	public record CompiledEntityPipelines(Map<RenderPipeline, CompiledEntityPipeline> pipelines) {
		public CompiledEntityPipeline forOriginal(RenderPipeline original) {
			return pipelines.get(original);
		}
	}

	public record TargetLayout(int[] logicalTargets, List<GpuFormat> formats) {
		public TargetLayout {
			logicalTargets = logicalTargets.clone();
			formats = List.copyOf(formats);
		}
	}
}
