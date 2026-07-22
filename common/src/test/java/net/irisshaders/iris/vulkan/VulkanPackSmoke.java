package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.mixin.IrisMixinPlugin;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformResult;
import net.irisshaders.iris.vulkan.shader.VulkanShaderResourceValidator;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vulkan.pipeline.VulkanTerrainRenderState;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;

/** Command-line static smoke test for real shaderpack fullscreen programs. */
public final class VulkanPackSmoke {
	private static final Set<String> UNIFORM_BLOCKS = new LinkedHashSet<>();
	private static final Set<String> TEXEL_BUFFERS = new LinkedHashSet<>();
	private static final Map<String, String> TEXEL_BUFFER_TYPES = new LinkedHashMap<>();
	private static final Map<String, String> SAMPLER_TYPES = new LinkedHashMap<>();
	private static final Map<String, String> LOOSE_UNIFORMS = new LinkedHashMap<>();

	private VulkanPackSmoke() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("Expected a shaderpack zip path");
		assertProjectionConversion();
		assertLegacyRenderTargetAliases();
		Path zip = Path.of(args[0]).toAbsolutePath();
		System.setProperty("iris.objcubed.compat", "false");
		URI uri = URI.create("jar:" + zip.toUri());
		ImmutableList.Builder<StringPair> defineBuilder = ImmutableList.builder();
		defineBuilder.add(
			// OptiFine/Iris encode 26.2 as major/minor/patch = 26/02/00.
			// Using 2602 incorrectly made packs such as Solas select their
			// pre-1.19.2 fallback and hid unsupported Vulkan feature paths.
			new StringPair("MC_VERSION", "260200"),
			new StringPair("MC_GL_VERSION", "450"),
			new StringPair("MC_GLSL_VERSION", "450"),
			new StringPair("MC_OS_WINDOWS", "1"),
			new StringPair("MC_GL_VENDOR_OTHER", "1"),
			new StringPair("MC_GL_RENDERER_OTHER", "1"),
			new StringPair("IRIS_VERSION", "11102"),
			new StringPair("IS_IRIS", ""),
			new StringPair("IRIS_VULKAN", ""),
			new StringPair("MC_RENDER_QUALITY", "1.0"),
			new StringPair("MC_SHADOW_QUALITY", "1.0"),
			new StringPair("MC_HAND_DEPTH", "0.125"),
			new StringPair("MAX_COLOR_BUFFERS", "16")
		);
		for (WorldRenderingPhase phase : WorldRenderingPhase.values()) {
			defineBuilder.add(new StringPair("MC_RENDER_STAGE_" + phase.name(), String.valueOf(phase.ordinal())));
		}
		ImmutableList<StringPair> defines = defineBuilder.build();
		try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of());
			 GlslCompiler compiler = new GlslCompiler()) {
			initializeIrisConfig();
			ShaderPack pack = new ShaderPack(fs.getPath("/shaders"), defines, true);
			ProgramSet set = pack.getProgramSet(DimensionId.OVERWORLD);
			IrisVulkan.validateVertexFragmentProfile(set);
			int compiled = 0;
			for (ProgramArrayId id : ProgramArrayId.values()) {
				TextureStage textureStage = textureStage(id);
				for (ProgramSource source : set.getComposite(id)) {
					if (source == null || !source.isValid()
						|| source.getVertexSource().isEmpty() || source.getFragmentSource().isEmpty()) continue;
					Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(
						source.getName(), source.getVertexSource().orElseThrow(), null,
						source.getFragmentSource().orElseThrow(), textureStage,
						set.getPackDirectives().getTextureMap());
					int[] outputs = new int[source.getDirectives().getDrawBuffers().length];
					for (int index = 0; index < outputs.length; index++) outputs[index] = index;
					VulkanShaderTransformResult transformed = VulkanShaderTransformer.transform(patched, outputs);
					VulkanShaderResourceValidator.validateFullscreen(transformed, source.getName());
					ShaderPrinter.printProgram("smoke_fullscreen_" + source.getName())
						.addSources(transformed.sources()).print();
					printLayoutFingerprint(source.getName(), transformed);
					recordResources(transformed);
					compile(compiler, source.getName(), transformed.sources());
					compiled++;
				}
			}
			ProgramSource finalSource = set.get(ProgramId.Final).orElseThrow();
			compileFullscreen(set, compiler, finalSource, TextureStage.COMPOSITE_AND_FINAL, 1);
			compiled++;
			ProgramFallbackResolver resolver = new ProgramFallbackResolver(set);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.TerrainSolid).orElseThrow(),
				AlphaTests.OFF, true, DefaultVertexFormat.BLOCK, false, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.TerrainCutout).orElseThrow(),
				AlphaTests.ONE_TENTH_ALPHA, true, DefaultVertexFormat.BLOCK, false, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.Water).orElseThrow(),
				AlphaTests.OFF, true, DefaultVertexFormat.BLOCK, false, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.Entities).orElseThrow(),
				AlphaTests.ONE_TENTH_ALPHA, false, DefaultVertexFormat.ENTITY, true, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.Block).orElseThrow(),
				AlphaTests.ONE_TENTH_ALPHA, false, RenderPipelines.SOLID_BLOCK.getVertexFormatBinding(0), true, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.Hand).orElseThrow(),
				AlphaTests.ONE_TENTH_ALPHA, false, RenderPipelines.ENTITY_CUTOUT.getVertexFormatBinding(0), true, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.Particles).orElseThrow(),
				AlphaTests.ONE_TENTH_ALPHA, false, RenderPipelines.OPAQUE_PARTICLE.getVertexFormatBinding(0), false, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.Weather).orElseThrow(),
				AlphaTests.ONE_TENTH_ALPHA, false, RenderPipelines.WEATHER_DEPTH_WRITE.getVertexFormatBinding(0), false, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.SkyBasic).orElseThrow(),
				AlphaTests.OFF, false, RenderPipelines.SKY.getVertexFormatBinding(0), false, false);
			compiled += compileVanilla(set, compiler, resolver.resolve(ProgramId.Clouds).orElseThrow(),
				AlphaTests.ONE_TENTH_ALPHA, false, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, false, true);
			compiled += compileSodium(set, compiler, resolver.resolve(ProgramId.TerrainSolid).orElseThrow(), AlphaTests.OFF, false);
			compiled += compileSodium(set, compiler, resolver.resolve(ProgramId.TerrainCutout).orElseThrow(), AlphaTests.ONE_TENTH_ALPHA, false);
			compiled += compileSodium(set, compiler, resolver.resolve(ProgramId.Water).orElseThrow(), AlphaTests.OFF, false);
			compiled += compileSodium(set, compiler, resolver.resolve(ProgramId.ShadowSolid).orElseThrow(), AlphaTests.OFF, true);
			compiled += compileSodium(set, compiler, resolver.resolve(ProgramId.ShadowCutout).orElseThrow(), AlphaTests.ONE_TENTH_ALPHA, true);
			compiled += compileSodium(set, compiler, resolver.resolve(ProgramId.ShadowWater).orElseThrow(), AlphaTests.OFF, true);
			System.out.println("Vulkan shaderpack uniform blocks: " + UNIFORM_BLOCKS);
			System.out.println("Vulkan shaderpack texel buffers: " + TEXEL_BUFFERS);
			System.out.println("Vulkan shaderpack texel buffer types: " + TEXEL_BUFFER_TYPES);
			System.out.println("Vulkan shaderpack sampler types: " + SAMPLER_TYPES);
			System.out.println("Vulkan shaderpack loose uniforms: " + LOOSE_UNIFORMS);
			System.out.println("Vulkan shaderpack smoke passed: " + compiled + " programs from " + zip.getFileName());
		}
	}

	private static void assertLegacyRenderTargetAliases() {
		if (!Integer.valueOf(7).equals(VulkanTerrainRenderState.logicalColorTarget("gaux4"))
			|| !Integer.valueOf(0).equals(VulkanTerrainRenderState.logicalColorTarget("gcolor"))
			|| !Integer.valueOf(12).equals(VulkanTerrainRenderState.logicalColorTarget("colortex12"))
			|| VulkanTerrainRenderState.logicalColorTarget("not_a_target") != null) {
			throw new IllegalStateException("Vulkan legacy render-target alias mapping is invalid");
		}
	}

	private static void assertProjectionConversion() {
		float fov = (float)Math.toRadians(70.0);
		float aspect = 16.0F / 9.0F;
		Matrix4f reverseZ = new Matrix4f().setPerspective(fov, aspect, 1000.0F, 0.05F, true);
		Matrix4f expected = new Matrix4f().setPerspective(fov, aspect, 0.05F, 1000.0F, false);
		VulkanFrameState.ensureOpenGlForwardProjection(reverseZ);
		if (!reverseZ.equals(expected, 0.00001F)) {
			throw new AssertionError("Reverse-Z projection conversion does not match OpenGL forward-Z");
		}
		Matrix4f unchanged = new Matrix4f(reverseZ);
		VulkanFrameState.ensureOpenGlForwardProjection(unchanged);
		if (!unchanged.equals(reverseZ, 0.0F)) {
			throw new AssertionError("Forward-Z projection was converted twice");
		}
		Matrix4f sodiumInput = new Matrix4f().setPerspective(fov, aspect, 1000.0F, 0.05F, true);
		Matrix4f sodiumProjection = VulkanFrameState.normalizeSodiumProjection(sodiumInput);
		if (!sodiumProjection.equals(expected, 0.00001F)) {
			throw new AssertionError("Sodium projection bridge did not normalize reverse-Z");
		}
		if (sodiumProjection == sodiumInput) {
			throw new AssertionError("Sodium projection bridge mutated the shared captured matrix");
		}
	}

	private static void compileFullscreen(ProgramSet set, GlslCompiler compiler, ProgramSource source,
			TextureStage textureStage, int outputCount) throws Exception {
		Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(
			source.getName(), source.getVertexSource().orElseThrow(), null,
			source.getFragmentSource().orElseThrow(), textureStage,
			set.getPackDirectives().getTextureMap());
		int[] outputs = new int[outputCount];
		for (int index = 0; index < outputs.length; index++) outputs[index] = index;
		VulkanShaderTransformResult transformed = VulkanShaderTransformer.transform(patched, outputs);
		VulkanShaderResourceValidator.validateFullscreen(transformed, source.getName());
		ShaderPrinter.printProgram("smoke_fullscreen_" + source.getName())
			.addSources(transformed.sources()).print();
		printLayoutFingerprint(source.getName(), transformed);
		recordResources(transformed);
		compile(compiler, source.getName(), transformed.sources());
	}

	private static int compileVanilla(ProgramSet set, GlslCompiler compiler, ProgramSource source,
			AlphaTest fallbackAlpha, boolean terrain, com.mojang.blaze3d.vertex.VertexFormat format,
			boolean extended, boolean clouds) throws Exception {
		com.mojang.blaze3d.vertex.VertexFormat shaderFormat = terrain
			? IrisVertexFormats.TERRAIN
			: extended ? extendImmediateFormat(format) : format;
		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(fallbackAlpha);
		Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla(
			source.getName(), source.getVertexSource().orElseThrow(), null, null, null,
			source.getFragmentSource().orElseThrow(), alpha, false, clouds, true,
			new ShaderAttributeInputs(shaderFormat, false, false, false, false, false),
			set.getPackDirectives().getTextureMap());
		int[] outputs = new int[source.getDirectives().getDrawBuffers().length];
		for (int index = 0; index < outputs.length; index++) outputs[index] = index;
		VulkanShaderTransformResult transformed = terrain
			? VulkanShaderTransformer.transformTerrain(patched, outputs, shaderFormat)
			: VulkanShaderTransformer.transform(patched, outputs, shaderFormat);
		if (shaderFormat.equals(IrisVertexFormats.ENTITY)) {
			assertExtendedEntityInputs(transformed, source.getName());
		}
		if (terrain) VulkanShaderResourceValidator.validateTerrain(transformed, source.getName());
		else VulkanShaderResourceValidator.validateDraw(transformed, source.getName());
		recordResources(transformed);
		compile(compiler, source.getName() + (terrain ? "_terrain" : "_entity"), transformed.sources(), shaderFormat);
		return 1;
	}

	private static int compileSodium(ProgramSet set, GlslCompiler compiler, ProgramSource source,
			AlphaTest fallbackAlpha, boolean shadow) throws Exception {
		var format = FormatAnalyzer.createFormat(true, true, true, true).getVertexFormat();
		Set<String> formatElements = new LinkedHashSet<>();
		format.getElements().forEach(element -> formatElements.add(element.name()));
		for (String input : new String[] {"mc_Entity", "iris_Normal", "mc_midTexCoord", "at_midBlock"}) {
			if (!formatElements.contains(input)) throw new IllegalStateException("Sodium extended format lost " + input);
		}
		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(fallbackAlpha);
		Map<PatchShaderType, String> patched = TransformPatcher.patchSodium(
			source.getName(), source.getVertexSource().orElseThrow(), null, null, null,
			source.getFragmentSource().orElseThrow(), alpha, set.getPackDirectives().getTextureMap(), shadow);
		int[] outputs = new int[source.getDirectives().getDrawBuffers().length];
		for (int index = 0; index < outputs.length; index++) outputs[index] = index;
		VulkanShaderTransformResult transformed = VulkanShaderTransformer.transformSodiumTerrain(patched, outputs, format);
		VulkanShaderResourceValidator.validateSodiumTerrain(transformed, source.getName());
		assertSodiumAbi(transformed, source.getName());
		ShaderPrinter.printProgram("smoke_sodium_" + source.getName() + (shadow ? "_shadow" : ""))
			.addSources(transformed.sources()).print();
		recordResources(transformed);
		compile(compiler, source.getName() + (shadow ? "_sodium_shadow" : "_sodium_terrain"), transformed.sources(), format);
		return 1;
	}

	private static void assertSodiumAbi(VulkanShaderTransformResult transformed, String programName) {
		String vertex = required(transformed.sources(), PatchShaderType.VERTEX);
		if (!vertex.contains("push_constant") || !vertex.contains("u_RegionOffset")
			|| !vertex.contains("u_CurrentTime") || !vertex.contains("u_RegionID")) {
			throw new IllegalStateException(programName + " lost Sodium's Vulkan push-constant ABI");
		}
		for (String name : new String[] {"u_RegionOffset", "u_CurrentTime", "u_RegionID"}) {
			if (transformed.looseUniforms().stream().anyMatch(uniform -> uniform.name().equals(name))) {
				throw new IllegalStateException(programName + " incorrectly packed Sodium push constant " + name);
			}
		}
		if (!transformed.uniformBlocks().contains("u_Globals") || !transformed.texelBuffers().contains("u_SectionTimeInfo")) {
			throw new IllegalStateException(programName + " lost Sodium's external uniform bindings");
		}
		if (!"isamplerBuffer".equals(transformed.texelBufferTypes().get("u_SectionTimeInfo"))
			|| VulkanShaderResourceValidator.texelBufferFormat(transformed, "u_SectionTimeInfo") != GpuFormat.R32_SINT) {
			throw new IllegalStateException(programName + " lost Sodium's R32_SINT section-time buffer contract");
		}
		if (!vertex.matches("(?s).*\\bin\\s+[^;]*\\bmc_Entity\\b[^;]*;.*")) {
			throw new IllegalStateException(programName + " lost Sodium's material-id input");
		}
	}

	private static void assertExtendedEntityInputs(VulkanShaderTransformResult transformed, String programName) {
		boolean looseEntityUniform = transformed.looseUniforms().stream()
			.anyMatch(uniform -> uniform.name().equals("iris_Entity"));
		if (looseEntityUniform) {
			throw new IllegalStateException(programName + " converted extended iris_Entity input to a draw uniform");
		}

		String vertexSource = required(transformed.sources(), PatchShaderType.VERTEX);
		if (!vertexSource.matches("(?s).*\\bin\\s+[^;]*\\biris_Entity\\b[^;]*;.*")) {
			throw new IllegalStateException(programName + " dropped the extended iris_Entity vertex input");
		}
	}

	private static com.mojang.blaze3d.vertex.VertexFormat extendImmediateFormat(
		com.mojang.blaze3d.vertex.VertexFormat format
	) {
		if (format.equals(DefaultVertexFormat.BLOCK)) return IrisVertexFormats.TERRAIN;
		if (format.equals(DefaultVertexFormat.ENTITY)) return IrisVertexFormats.ENTITY;
		if (format.equals(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)) return IrisVertexFormats.GLYPH;
		return format;
	}

	private static void initializeIrisConfig() throws Exception {
		IrisMixinPlugin.usingVulkan = true;
		var field = Iris.class.getDeclaredField("irisConfig");
		field.setAccessible(true);
		Path root = Path.of(System.getProperty("java.io.tmpdir"), "iris-vulkan-smoke");
		IrisConfig config = new IrisConfig(root.resolve("iris.properties"), root.resolve("iris-excluded.json"));
		config.setDebugEnabled(true);
		field.set(null, config);
	}

	private static TextureStage textureStage(ProgramArrayId id) {
		return switch (id) {
			case ShadowComposite -> TextureStage.SHADOWCOMP;
			case Begin -> TextureStage.BEGIN;
			case Prepare -> TextureStage.PREPARE;
			case Deferred -> TextureStage.DEFERRED;
			case Composite -> TextureStage.COMPOSITE_AND_FINAL;
			case Setup -> TextureStage.SETUP;
		};
	}

	private static void compile(GlslCompiler compiler, String name, Map<PatchShaderType, String> sources) throws Exception {
		compile(compiler, name, sources, null);
	}

	private static void compile(GlslCompiler compiler, String name, Map<PatchShaderType, String> sources,
			com.mojang.blaze3d.vertex.VertexFormat vertexFormat) throws Exception {
		System.out.println("Compiling Vulkan fullscreen program: " + name);
		try (IntermediaryShaderModule vertex = compileStage(compiler, name, sources, PatchShaderType.VERTEX, ShaderType.VERTEX);
			 IntermediaryShaderModule fragment = compileStage(compiler, name, sources, PatchShaderType.FRAGMENT, ShaderType.FRAGMENT)) {
			if (vertex == IntermediaryShaderModule.INVALID || fragment == IntermediaryShaderModule.INVALID) {
				throw new IllegalStateException("Invalid SPIR-V intermediary for " + name);
			}
			if (vertexFormat != null) assertVertexBindings(vertex, vertexFormat, name);
		}
	}

	private static void assertVertexBindings(IntermediaryShaderModule vertex,
			com.mojang.blaze3d.vertex.VertexFormat format, String program) {
		Set<String> provided = new LinkedHashSet<>();
		format.getElements().forEach(element -> provided.add(element.name()));
		Set<String> required = new LinkedHashSet<>();
		for (Object input : vertex.inputs()) {
			String value = input.toString();
			int start = value.indexOf("name=");
			int end = value.indexOf(',', start);
			if (start >= 0) required.add(value.substring(start + 5, end < 0 ? value.length() - 1 : end));
		}
		required.removeAll(provided);
		if (!required.isEmpty()) {
			throw new IllegalStateException(program + " requires missing Vulkan vertex inputs " + required
				+ "; provided=" + provided);
		}
	}

	private static IntermediaryShaderModule compileStage(GlslCompiler compiler, String name,
			Map<PatchShaderType, String> sources, PatchShaderType patchType, ShaderType shaderType) throws Exception {
		String source = required(sources, patchType);
		try {
			return compiler.createIntermediary(name + "." + patchType.name().toLowerCase(), source, shaderType);
		} catch (Exception exception) {
			String[] lines = source.split("\\R");
			for (int index = 0; index < Math.min(lines.length, 180); index++) {
				System.err.printf("%4d | %s%n", index + 1, lines[index]);
			}
			throw exception;
		}
	}

	private static String required(Map<PatchShaderType, String> sources, PatchShaderType type) {
		String source = sources.get(type);
		if (source == null) throw new IllegalStateException("Missing " + type + " source");
		return source;
	}

	private static void recordResources(VulkanShaderTransformResult transformed) {
		UNIFORM_BLOCKS.addAll(transformed.uniformBlocks());
		TEXEL_BUFFERS.addAll(transformed.texelBuffers());
		BindGroupLayout.Builder texelBindings = BindGroupLayout.builder();
		transformed.texelBuffers().forEach(buffer -> {
			VulkanShaderResourceValidator.addTexelBufferBinding(texelBindings, transformed, buffer);
			TEXEL_BUFFER_TYPES.put(buffer,
				transformed.texelBufferTypes().get(buffer) + "->" + VulkanShaderResourceValidator.texelBufferFormat(transformed, buffer));
		});
		texelBindings.build();
		SAMPLER_TYPES.putAll(transformed.samplerTypes());
		transformed.looseUniforms().forEach(uniform ->
			LOOSE_UNIFORMS.putIfAbsent(uniform.name(), uniform.declaration()));
	}

	private static void printLayoutFingerprint(String program, VulkanShaderTransformResult transformed) throws Exception {
		StringBuilder material = new StringBuilder();
		transformed.sources().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> material.append(entry.getKey()).append('\n').append(entry.getValue()).append('\n'));
		material.append("blocks=").append(transformed.uniformBlocks())
			.append("\nsamplers=").append(transformed.samplers())
			.append("\ntexel=").append(transformed.texelBuffers())
			.append("\nuniforms=").append(transformed.looseUniforms());
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
		System.out.println("Vulkan fullscreen fingerprint " + program + ": " + hash
			+ " samplers=" + transformed.samplers()
			+ " uniforms=" + transformed.looseUniforms().stream()
				.map(uniform -> uniform.name() + "@" + uniform.offset() + ":" + uniform.size()).toList());
	}
}
