package net.irisshaders.iris.pipeline.transform;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.mixin.IrisMixinPlugin;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformResult;
import net.irisshaders.iris.vulkan.shader.VulkanShaderResourceValidator;
import net.irisshaders.iris.vulkan.shader.VulkanShaderTransformer;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles the live obj-cubed subgroup decoder inside real Complementary programs. */
public final class VulkanObjCubedSmoke {
	private VulkanObjCubedSmoke() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 4) {
			throw new IllegalArgumentException("Expected shaderpack zip, objmc_tools.glsl, objmc_main.glsl and terrain.vsh paths");
		}
		Path shaderpack = Path.of(args[0]).toAbsolutePath();
		String tools = Files.readString(Path.of(args[1]).toAbsolutePath());
		String decoder = Files.readString(Path.of(args[2]).toAbsolutePath());
		String nativeTerrain = Files.readString(Path.of(args[3]).toAbsolutePath());
		System.setProperty("iris.objcubed.compat", "false");
		initializeIrisConfig();

		ImmutableList.Builder<StringPair> defineBuilder = ImmutableList.builder();
		defineBuilder.add(
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

		try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + shaderpack.toUri()), Map.of());
			 GlslCompiler compiler = new GlslCompiler()) {
			String resolvedNativeTerrain = resolveMojImports(nativeTerrain, tools, decoder, new HashSet<>());
			resolvedNativeTerrain = "#version 450\n" + Pattern.compile("(?m)^\\s*#version[^\\r\\n]*$")
				.matcher(resolvedNativeTerrain).replaceAll("");
			compileStage(compiler, "native_resourcepack_terrain", resolvedNativeTerrain, ShaderType.VERTEX);
			ShaderPack pack = new ShaderPack(fs.getPath("/shaders"), defines, true);
			ProgramSet set = pack.getProgramSet(DimensionId.OVERWORLD);
			ProgramFallbackResolver resolver = new ProgramFallbackResolver(set);

			compileProgram(compiler, set, resolver.resolve(ProgramId.Entities).orElseThrow(),
				"entities", DefaultVertexFormat.ENTITY, AlphaTests.ONE_TENTH_ALPHA,
				tools, decoder, false, false);
			compileProgram(compiler, set, resolver.resolve(ProgramId.TerrainSolid).orElseThrow(),
				"terrain_solid", DefaultVertexFormat.BLOCK, AlphaTests.OFF,
				tools, decoder, false, true);
			compileCrumblingProgram(compiler, set, resolver.resolve(ProgramId.TerrainSolid).orElseThrow(),
				"crumbling", tools, decoder);
			compileProgram(compiler, set, resolver.resolve(ProgramId.ShadowEntities).orElseThrow(),
				"shadow_entities_cutout", DefaultVertexFormat.ENTITY, AlphaTests.ONE_TENTH_ALPHA,
				tools, decoder, true, false);
			compileProgram(compiler, set, resolver.resolve(ProgramId.ShadowSolid).orElseThrow(),
				"shadow_solid", DefaultVertexFormat.BLOCK, AlphaTests.OFF,
				tools, decoder, false, true);
			compileSodiumProgram(compiler, set, resolver.resolve(ProgramId.TerrainSolid).orElseThrow(),
				"sodium_terrain_solid", AlphaTests.OFF, tools, decoder, false);
			compileSodiumProgram(compiler, set, resolver.resolve(ProgramId.ShadowSolid).orElseThrow(),
				"sodium_shadow_solid", AlphaTests.OFF, tools, decoder, true);
		}

		System.out.println("Vulkan obj-cubed subgroup smoke passed: native quad + shuffle fallback across entity + vanilla/Sodium terrain + crumbling + shadow entity + vanilla/Sodium shadow terrain");
	}

	private static String resolveMojImports(String source, String tools, String decoder, Set<String> stack) throws Exception {
		Pattern imports = Pattern.compile("(?m)^\\s*#moj_import\\s+<([^>]+)>\\s*$");
		Matcher matcher = imports.matcher(source);
		StringBuffer output = new StringBuffer();
		while (matcher.find()) {
			String id = matcher.group(1);
			if (!stack.add(id)) throw new IllegalStateException("Recursive Mojang shader import " + id);
			String imported;
			String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
			String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
			if ("minecraft".equals(namespace) && "objmc_tools.glsl".equals(path)) imported = tools;
			else if ("minecraft".equals(namespace) && "objmc_main.glsl".equals(path)) imported = decoder;
			else {
				String resourcePath = "/assets/" + namespace + "/shaders/include/" + path;
				try (var input = VulkanObjCubedSmoke.class.getResourceAsStream(resourcePath)) {
					if (input == null) throw new IllegalStateException("Missing Mojang shader import " + resourcePath);
					imported = new String(input.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
			imported = resolveMojImports(imported, tools, decoder, stack);
			stack.remove(id);
			matcher.appendReplacement(output, Matcher.quoteReplacement(imported));
		}
		matcher.appendTail(output);
		return output.toString();
	}

	private static void assertRawSodiumInjection(String tools, String decoder) throws Exception {
		String path = "/assets/sodium/shaders/blocks/block_layer_opaque.vsh";
		try (var input = VulkanObjCubedSmoke.class.getResourceAsStream(path)) {
			if (input == null) throw new IllegalStateException("Missing Sodium native terrain shader " + path);
			String stock = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			String injected = ObjCubedShaderInjector.injectRawSodiumTerrainWithSources(stock, tools, decoder, true);
			if (!injected.contains("iris_ObjCubedDecode")
				|| !injected.contains("#define IRIS_OBJCUBED_STABLE_BLOCK_ANCHOR")
				|| !injected.contains("in vec4 at_midBlock;")
				|| !injected.contains("uniform sampler2D u_BlockTex;")
				|| !injected.contains("#define Sampler0 u_BlockTex")
				|| injected.contains("uniform sampler2D iris_ObjCubedSampler;")) {
				throw new IllegalStateException("Native shader-off Sodium terrain injection is incomplete");
			}
		}
	}

	private static void compileCrumblingProgram(
		GlslCompiler compiler,
		ProgramSet set,
		ProgramSource source,
		String patchName,
		String tools,
		String decoder
	) throws Exception {
		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(AlphaTests.OFF);
		Map<PatchShaderType, String> patched = TransformPatcher.patchVanillaWithoutObjCubed(
			patchName, source.getVertexSource().orElseThrow(), null, null, null,
			source.getFragmentSource().orElseThrow(), alpha, false, false, true,
			new ShaderAttributeInputs(IrisVertexFormats.TERRAIN, false, false, false, false, false),
			set.getPackDirectives().getTextureMap());
		patched = ObjCubedShaderInjector.injectCrumblingWithSources(patched, patchName, tools, decoder);
		String vertex = patched.get(PatchShaderType.VERTEX);
		if (vertex == null || !vertex.contains("iris_ObjCubedAtlasSampler")
			|| !vertex.contains("#define UV0 (vec2(iris_UV1) / 32767.0)")
			|| !vertex.contains("vec2 iris_ObjCubedUVValue() {\n    return iris_UV0;")) {
			throw new IllegalStateException("Crumbling did not preserve atlas metadata separately from destroy-stage UV0");
		}
		compileVariant(compiler, source, patchName, patched, IrisVertexFormats.CRUMBLING, false, false);
		compileVariant(compiler, source, patchName, patched, IrisVertexFormats.CRUMBLING, false, true);
	}

	private static void compileSodiumProgram(
		GlslCompiler compiler,
		ProgramSet set,
		ProgramSource source,
		String patchName,
		AlphaTest fallbackAlpha,
		String tools,
		String decoder,
		boolean shadowPass
	) throws Exception {
		var format = FormatAnalyzer.createFormat(true, true, true, true).getVertexFormat();
		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(fallbackAlpha);
		Map<PatchShaderType, String> patched = TransformPatcher.patchSodium(
			patchName, source.getVertexSource().orElseThrow(), null, null, null,
			source.getFragmentSource().orElseThrow(), alpha, set.getPackDirectives().getTextureMap(), shadowPass);
		patched = ObjCubedShaderInjector.injectSodiumTerrainWithSources(
			patched, patchName, tools, decoder, true);
		if (!patched.get(PatchShaderType.VERTEX).contains("iris_ObjCubedDecode")) {
			throw new IllegalStateException("obj-cubed decoder was not injected into " + patchName);
		}
		if (!patched.get(PatchShaderType.VERTEX).contains(
			"iris_ObjCubedRawUV = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink)")) {
			throw new IllegalStateException("Sodium atlas-edge bias was not preserved in " + patchName);
		}
		if (!patched.get(PatchShaderType.VERTEX).contains(
			"iris_ObjCubedBlockAnchor += at_midBlock.xyz * (127.0 / 64.0)")) {
			throw new IllegalStateException("Sodium's per-vertex block anchor was not used in " + patchName);
		}
		if (!patched.get(PatchShaderType.VERTEX).contains("t[i] = meta;")) {
			throw new IllegalStateException("Vulkan terrain metadata still depends on cross-primitive subgroup lanes in " + patchName);
		}
		compileSodiumVariant(compiler, source, patchName, patched, format, false);
		compileSodiumVariant(compiler, source, patchName, patched, format, true);
	}

	private static void compileSodiumVariant(
		GlslCompiler compiler,
		ProgramSource source,
		String patchName,
		Map<PatchShaderType, String> patched,
		com.mojang.blaze3d.vertex.VertexFormat format,
		boolean nativeQuad
	) throws Exception {
		Map<PatchShaderType, String> selected = ObjCubedShaderInjector.configureVulkanSubgroups(patched, nativeQuad);
		EnumMap<PatchShaderType, String> preprocessed = new EnumMap<>(PatchShaderType.class);
		preprocessed.putAll(selected);
		preprocessed.put(PatchShaderType.VERTEX,
			JcppProcessor.glslPreprocessSource(selected.get(PatchShaderType.VERTEX), List.of()));
		int[] outputLocations = new int[source.getDirectives().getDrawBuffers().length];
		for (int index = 0; index < outputLocations.length; index++) outputLocations[index] = index;
		VulkanShaderTransformResult transformed = VulkanShaderTransformer.transformSodiumTerrain(preprocessed, outputLocations, format);
		VulkanShaderResourceValidator.validateSodiumTerrain(transformed, patchName);
		String vertex = transformed.sources().get(PatchShaderType.VERTEX);
		boolean expectedOperation = nativeQuad
			? vertex != null && vertex.contains("subgroupQuadBroadcast(") && !vertex.contains("subgroupShuffle(")
			: vertex != null && vertex.contains("subgroupShuffle(") && !vertex.contains("subgroupQuadBroadcast(");
		if (!expectedOperation || !vertex.contains("push_constant")) {
			throw new IllegalStateException("obj-cubed Sodium " + (nativeQuad ? "native quad" : "shuffle")
				+ " path or push constants were not selected correctly in " + patchName);
		}
		String variantName = patchName + (nativeQuad ? "_native_quad" : "_shuffle");
		compileStage(compiler, variantName, vertex, ShaderType.VERTEX);
		compileStage(compiler, variantName, transformed.sources().get(PatchShaderType.FRAGMENT), ShaderType.FRAGMENT);
	}

	private static void compileProgram(
		GlslCompiler compiler,
		ProgramSet set,
		ProgramSource source,
		String patchName,
		com.mojang.blaze3d.vertex.VertexFormat format,
		AlphaTest fallbackAlpha,
		String tools,
		String decoder,
		boolean shadowPass,
		boolean terrain
	) throws Exception {
		if (!terrain && format.equals(DefaultVertexFormat.ENTITY)) {
			format = IrisVertexFormats.ENTITY;
		}
		AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(fallbackAlpha);
		Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla(
			patchName, source.getVertexSource().orElseThrow(), null, null, null,
			source.getFragmentSource().orElseThrow(), alpha, false, false, true,
			new ShaderAttributeInputs(format, false, false, false, false, false),
			set.getPackDirectives().getTextureMap()
		);
		patched = ObjCubedShaderInjector.injectWithSources(
			patched, patchName, tools, decoder, shadowPass, terrain);
		String injectedVertex = patched.get(PatchShaderType.VERTEX);
		if (injectedVertex == null || !injectedVertex.contains("iris_ObjCubedDecode")) {
			throw new IllegalStateException("obj-cubed decoder was not injected into " + patchName);
		}

		compileVariant(compiler, source, patchName, patched, format, terrain, false);
		compileVariant(compiler, source, patchName, patched, format, terrain, true);
	}

	private static void compileVariant(
		GlslCompiler compiler,
		ProgramSource source,
		String patchName,
		Map<PatchShaderType, String> patched,
		com.mojang.blaze3d.vertex.VertexFormat format,
		boolean terrain,
		boolean nativeQuad
	) throws Exception {
		Map<PatchShaderType, String> selected = ObjCubedShaderInjector.configureVulkanSubgroups(patched, nativeQuad);
		EnumMap<PatchShaderType, String> preprocessed = new EnumMap<>(PatchShaderType.class);
		preprocessed.putAll(selected);
		preprocessed.put(PatchShaderType.VERTEX,
			JcppProcessor.glslPreprocessSource(selected.get(PatchShaderType.VERTEX), List.of()));

		int[] outputLocations = new int[source.getDirectives().getDrawBuffers().length];
		for (int index = 0; index < outputLocations.length; index++) outputLocations[index] = index;
		VulkanShaderTransformResult transformed = terrain
			? VulkanShaderTransformer.transformTerrain(preprocessed, outputLocations)
			: VulkanShaderTransformer.transform(preprocessed, outputLocations, format);
		if (terrain) VulkanShaderResourceValidator.validateTerrain(transformed, patchName);
		else VulkanShaderResourceValidator.validateDraw(transformed, patchName);
		String vertex = transformed.sources().get(PatchShaderType.VERTEX);
		boolean expectedOperation = nativeQuad
			? vertex != null && vertex.contains("subgroupQuadBroadcast(") && !vertex.contains("subgroupShuffle(")
			: vertex != null && vertex.contains("subgroupShuffle(") && !vertex.contains("subgroupQuadBroadcast(");
		if (!expectedOperation) {
			throw new IllegalStateException("obj-cubed " + (nativeQuad ? "native quad" : "shuffle")
				+ " subgroup path was not selected correctly in " + patchName);
		}
		String variantName = patchName + (nativeQuad ? "_native_quad" : "_shuffle");
		compileStage(compiler, variantName, vertex, ShaderType.VERTEX);
		compileStage(compiler, variantName,
			transformed.sources().get(PatchShaderType.FRAGMENT), ShaderType.FRAGMENT);
	}

	private static void compileStage(GlslCompiler compiler, String name, String source, ShaderType type) throws Exception {
		if (source == null) throw new IllegalStateException("Missing " + type + " source for " + name);
		try (IntermediaryShaderModule module = compiler.createIntermediary(
			name + ".objcubed." + type.name().toLowerCase(), source, type)) {
			if (module == IntermediaryShaderModule.INVALID) {
				throw new IllegalStateException("Invalid obj-cubed SPIR-V intermediary for " + name + " " + type);
			}
		}
	}

	private static void initializeIrisConfig() throws Exception {
		IrisMixinPlugin.usingVulkan = true;
		var field = Iris.class.getDeclaredField("irisConfig");
		field.setAccessible(true);
		Path root = Path.of(System.getProperty("java.io.tmpdir"), "iris-vulkan-objcubed-smoke");
		field.set(null, new IrisConfig(root.resolve("iris.properties"), root.resolve("iris-excluded.json")));
	}
}
