package io.github.jhooc77.objcubedcompat.shader;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Injects the active resource pack's obj-cubed decoder into Iris/Sodium shaders. */
public final class ObjCubedShaderInjector {
	private static final Identifier TOOLS = Identifier.fromNamespaceAndPath("minecraft", "shaders/include/objmc_tools.glsl");
	private static final Identifier MAIN = Identifier.fromNamespaceAndPath("minecraft", "shaders/include/objmc_main.glsl");
	private static final Pattern VERSION = Pattern.compile("(?m)^(#version[^\\r\\n]*)(?:\\r?\\n)");
	private static final Pattern MAIN_FUNCTION = Pattern.compile("(?m)^([ \\t]*)void\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
	private static final String POSITION_VALUE = "iris_ObjCubedPositionValue()";
	private static final String UV_VALUE = "iris_ObjCubedUVValue()";
	private static final String COLOR_VALUE = "iris_ObjCubedColorValue()";
	private static final String ENTITY_SHADOW_PROGRAM = "shadow_entities_cutout";

	private ObjCubedShaderInjector() {
	}

	public static Map<PatchShaderType, String> inject(Map<PatchShaderType, String> transformed, VanillaParameters parameters, String programName) {
		if (transformed == null || !isEligible(parameters, programName)) return transformed;
		Optional<String> tools = read(TOOLS);
		Optional<String> main = read(MAIN);
		if (tools.isEmpty() || main.isEmpty()) return transformed;
		return injectWithSources(transformed, programName, tools.get(), main.get(), isEntityShadowProgram(programName), false);
	}

	public static Map<PatchShaderType, String> injectTerrain(Map<PatchShaderType, String> transformed, String programName) {
		if (transformed == null || !enabled()) return transformed;
		Optional<String> tools = read(TOOLS);
		Optional<String> main = read(MAIN);
		if (tools.isEmpty() || main.isEmpty()) return transformed;
		return injectWithSources(transformed, programName, tools.get(), main.get(), false, true);
	}

	public static Map<PatchShaderType, String> injectCrumbling(Map<PatchShaderType, String> transformed, String programName) {
		if (transformed == null || !enabled()) return transformed;
		Optional<String> tools = read(TOOLS);
		Optional<String> main = read(MAIN);
		if (tools.isEmpty() || main.isEmpty()) return transformed;
		String vertex = transformed.get(PatchShaderType.VERTEX);
		if (vertex == null) return transformed;
		if (!Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+[iu]?vec2\\s+iris_UV1\\s*;").matcher(vertex).find()) {
			Matcher version = VERSION.matcher(vertex);
			if (!version.find()) return transformed;
			vertex = version.replaceFirst(Matcher.quoteReplacement(version.group(1) + "\nin ivec2 iris_UV1;\n"));
		}
		String injected = injectVertex(vertex, tools.get(), main.get(), false, true);
		if (injected == null) return transformed;
		injected = injected
			.replace("uniform sampler2D iris_ObjCubedSampler;", "uniform sampler2D iris_ObjCubedAtlasSampler;")
			.replace("#define Sampler0 iris_ObjCubedSampler", "#define Sampler0 iris_ObjCubedAtlasSampler")
			.replace("#define UV0 iris_UV0", "#define UV0 (vec2(iris_UV1) / 32767.0)")
			.replace("return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedTexCoord : iris_UV0;", "return iris_UV0;");
		EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
		result.putAll(transformed);
		result.put(PatchShaderType.VERTEX, injected);
		return result;
	}

	public static String injectNativeCrumblingWithSources(String source, String tools, String main) {
		if (source == null || source.contains("iris_ObjCubedDecode") || tools == null || main == null || !enabled()) return source;
		Matcher version = VERSION.matcher(source);
		if (!version.find()) return source;
		String canonical = version.replaceFirst(Matcher.quoteReplacement(version.group(1)
			+ "\n#moj_import <minecraft:globals.glsl>\n"
			+ "in ivec2 iris_UV1;\n"));
		canonical = renameNativeInput(canonical, "Position", "iris_Position");
		canonical = renameNativeInput(canonical, "UV0", "iris_UV0");
		canonical = renameNativeInput(canonical, "Color", "iris_Color");
		String injected = injectVertex(canonical, tools, main, false, true);
		if (injected == null) return source;
		String nativeSource = injected
			.replace("uniform sampler2D iris_ObjCubedSampler;", "float iris_ObjCubedNativeGameTime() { return GameTime; }\nuniform sampler2D Sampler1;")
			.replace("#define Sampler0 iris_ObjCubedSampler", "#define Sampler0 Sampler1")
			.replace("#define UV0 iris_UV0", "#define UV0 (vec2(iris_UV1) / 32767.0)")
			.replace("#define GameTime iris_globalInfo.GameTime", "#define GameTime iris_ObjCubedNativeGameTime()")
			.replace("return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedTexCoord : iris_UV0;", "return iris_UV0;");
		return restoreNativeCrumblingInputs(nativeSource);
	}

	public static Map<PatchShaderType, String> injectSodiumTerrain(Map<PatchShaderType, String> transformed, String programName) {
		if (transformed == null || !enabled()) return transformed;
		Optional<String> tools = read(TOOLS);
		Optional<String> main = read(MAIN);
		if (tools.isEmpty() || main.isEmpty()) return transformed;
		return injectSodiumTerrainWithSources(transformed, programName, tools.get(), main.get());
	}

	public static String injectRawSodiumTerrain(String source) {
		if (source == null || source.contains("iris_ObjCubedDecode") || !enabled()) return source;
		Optional<String> tools = read(TOOLS);
		Optional<String> main = read(MAIN);
		if (tools.isEmpty() || main.isEmpty()) return source;
		return injectRawSodiumTerrainWithSources(source, tools.get(), main.get());
	}

	public static String injectRawSodiumTerrainWithSources(String source, String tools, String main) {
		if (source == null || source.contains("iris_ObjCubedDecode") || tools == null || main == null || !enabled()) return source;
		String injected = injectSodiumTerrainVertex(source, tools, main);
		if (injected == null) return source;
		return injected
			.replace("uniform sampler2D iris_ObjCubedSampler;", "uniform sampler2D u_BlockTex;")
			.replace("#define Sampler0 iris_ObjCubedSampler", "#define Sampler0 u_BlockTex");
	}

	private static Map<PatchShaderType, String> injectSodiumTerrainWithSources(Map<PatchShaderType, String> transformed, String programName, String tools, String main) {
		String vertex = transformed.get(PatchShaderType.VERTEX);
		if (vertex == null) return transformed;
		String injected = injectSodiumTerrainVertex(vertex, tools, main);
		if (injected == null) {
			Iris.logger.warn("obj-cubed Sodium terrain compatibility was eligible for {} but Sodium's unpack/main bridge could not be located", programName);
			return transformed;
		}
		EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
		result.putAll(transformed);
		result.put(PatchShaderType.VERTEX, injected);
		return result;
	}

	private static Map<PatchShaderType, String> injectWithSources(Map<PatchShaderType, String> transformed, String programName, String tools, String main, boolean shadowPass, boolean blockPass) {
		String vertex = transformed.get(PatchShaderType.VERTEX);
		if (vertex == null) return transformed;
		String injected = injectVertex(vertex, tools, main, shadowPass, blockPass);
		if (injected == null) {
			Iris.logger.warn("obj-cubed {} compatibility was eligible for {} but its transformed attributes/main could not be located", blockPass ? "terrain" : "entity", programName);
			return transformed;
		}
		EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
		result.putAll(transformed);
		result.put(PatchShaderType.VERTEX, injected);
		return result;
	}

	private static boolean enabled() {
		return Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"));
	}

	private static boolean isEligible(VanillaParameters parameters, String programName) {
		return enabled()
			&& (programName == null || !programName.startsWith("shadow_") || isEntityShadowProgram(programName))
			&& !parameters.isLines()
			&& !parameters.isClouds()
			&& !parameters.inputs.isText()
			&& !parameters.inputs.isGlint()
			&& parameters.inputs.hasColor()
			&& parameters.inputs.hasTex()
			&& parameters.inputs.hasOverlay()
			&& parameters.inputs.hasNormal();
	}

	private static boolean isEntityShadowProgram(String programName) {
		return ENTITY_SHADOW_PROGRAM.equals(programName);
	}

	private static Optional<String> read(Identifier id) {
		try {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
			if (resource.isEmpty()) return Optional.empty();
			try (var input = resource.get().open()) {
				return Optional.of(IOUtils.toString(input, StandardCharsets.UTF_8));
			}
		} catch (IOException | RuntimeException e) {
			Iris.logger.warn("Failed to read obj-cubed shader include {}", id, e);
			return Optional.empty();
		}
	}

	private static String injectVertex(String source, String tools, String decoderBody, boolean shadowPass, boolean blockPass) {
		String redirected = redirectInputReads(source, "vec3", "iris_Position", POSITION_VALUE);
		if (redirected == null) return null;
		redirected = redirectInputReads(redirected, "vec2", "iris_UV0", UV_VALUE);
		if (redirected == null) return null;
		redirected = redirectInputReads(redirected, "vec4", "iris_Color", COLOR_VALUE);
		if (redirected == null) return null;
		Matcher originalMain = MAIN_FUNCTION.matcher(redirected);
		if (!originalMain.find()) return null;
		redirected = originalMain.replaceFirst(Matcher.quoteReplacement(originalMain.group(1) + "void iris_ObjCubedOriginalMain()"));
		String bridge = buildBridge(tools, decoderBody, shadowPass, blockPass);
		Matcher version = VERSION.matcher(redirected);
		if (!version.find()) return null;
		redirected = version.replaceFirst(Matcher.quoteReplacement(version.group(1) + "\n"
			+ "#extension GL_KHR_shader_subgroup_basic : require\n"
			+ "#extension GL_KHR_shader_subgroup_shuffle : require\n"
			+ "vec3 iris_ObjCubedPositionValue();\n"
			+ "vec2 iris_ObjCubedUVValue();\n"
			+ "vec4 iris_ObjCubedColorValue();\n"));
		return redirected + "\n" + bridge;
	}

	private static String injectSodiumTerrainVertex(String source, String tools, String decoderBody) {
		if (!source.contains("_vert_init") || !source.contains("_vert_position") || !source.contains("_vert_tex_diffuse_coord") || !source.contains("u_RegionOffset")) return null;
		Matcher originalMain = MAIN_FUNCTION.matcher(source);
		if (!originalMain.find()) return null;
		String redirected = originalMain.replaceFirst(Matcher.quoteReplacement(originalMain.group(1) + "void iris_ObjCubedOriginalMain()"));
		redirected = Pattern.compile("\\b_vert_init\\s*\\(\\s*\\)\\s*;").matcher(redirected).replaceFirst("");
		Matcher version = VERSION.matcher(redirected);
		if (!version.find()) return null;
		redirected = version.replaceFirst(Matcher.quoteReplacement(version.group(1) + "\n"
			+ "#extension GL_KHR_shader_subgroup_basic : require\n"
			+ "#extension GL_KHR_shader_subgroup_shuffle : require\n"));
		return redirected + "\n" + buildSodiumTerrainBridge(source, tools, decoderBody);
	}

	private static String redirectInputReads(String source, String type, String inputName, String valueExpression) {
		Pattern declaration = Pattern.compile("(?m)^([ \\t]*(?:layout\\s*\\([^\\r\\n]*\\)\\s*)?in\\s+" + type + "\\s+)" + Pattern.quote(inputName) + "(\\s*;)");
		Matcher matcher = declaration.matcher(source);
		if (!matcher.find()) return null;
		String redirected = Pattern.compile("\\b" + Pattern.quote(inputName) + "\\b").matcher(source).replaceAll(Matcher.quoteReplacement(valueExpression));
		Pattern redirectedDeclaration = Pattern.compile("(?m)^([ \\t]*(?:layout\\s*\\([^\\r\\n]*\\)\\s*)?in\\s+" + type + "\\s+)" + Pattern.quote(valueExpression) + "(\\s*;)");
		Matcher redirectedMatcher = redirectedDeclaration.matcher(redirected);
		if (!redirectedMatcher.find()) return null;
		return redirectedMatcher.replaceFirst(Matcher.quoteReplacement(redirectedMatcher.group(1) + inputName + redirectedMatcher.group(2)));
	}

	private static String renameNativeInput(String source, String from, String to) {
		return Pattern.compile("\\b" + Pattern.quote(from) + "\\b").matcher(source).replaceAll(Matcher.quoteReplacement(to));
	}

	private static String restoreNativeCrumblingInputs(String source) {
		String restored = renameNativeInput(source, "iris_Position", "Position");
		restored = renameNativeInput(restored, "iris_UV0", "UV0");
		restored = renameNativeInput(restored, "iris_UV1", "UV1");
		restored = renameNativeInput(restored, "iris_Color", "Color");
		restored = renameNativeInput(restored, "iris_Normal", "Normal");
		return Pattern.compile("(?m)^[ \\t]*#define[ \\t]+(Position|Color|Normal)[ \\t]+\\1[ \\t]*(?:\\r?\\n)?").matcher(restored).replaceAll("");
	}

	private static String buildBridge(String tools, String body, boolean shadowPass, boolean blockPass) {
		String shadowWorldOverrides = shadowPass ? """
			#define isgui(projection) false
			#define ishand(projection) false
			""" : "";
		String shadowWorldCleanup = shadowPass ? """
			#undef ishand
			#undef isgui
			""" : "";
		String bridge = """
			uniform sampler2D iris_ObjCubedSampler;
			vec3 iris_ObjCubedPosition;
			vec2 iris_ObjCubedTexCoord;
			vec2 iris_ObjCubedTexCoord2;
			vec4 iris_ObjCubedOverlayColor;
			vec4 iris_ObjCubedVertexColor;
			vec4 iris_ObjCubedLightColor;
			float iris_ObjCubedTransition;
			int iris_ObjCubedIsCustom;
			int iris_ObjCubedIsGUI;
			int iris_ObjCubedIsHand;
			int iris_ObjCubedNoShadow;

			#define ENTITY
			#define NO_CARDINAL_LIGHTING
			#define Sampler0 iris_ObjCubedSampler
			#define Position iris_Position
			#define Pos iris_ObjCubedPosition
			#define UV0 iris_UV0
			#define Color iris_Color
			#define Normal iris_Normal
			#define ProjMat iris_ProjMat
			#define ModelViewMat iris_transforms.ModelViewMat
			#define TextureMat iris_transforms.TextureMat
			#define GameTime iris_globalInfo.GameTime
			#define texCoord iris_ObjCubedTexCoord
			#define texCoord2 iris_ObjCubedTexCoord2
			#define overlayColor iris_ObjCubedOverlayColor
			#define vertexColor iris_ObjCubedVertexColor
			#define lightColor iris_ObjCubedLightColor
			#define transition iris_ObjCubedTransition
			#define isCustom iris_ObjCubedIsCustom
			#define isGUI iris_ObjCubedIsGUI
			#define isHand iris_ObjCubedIsHand
			#define noshadow iris_ObjCubedNoShadow
			#define subgroupQuadBroadcast(value, quadLane) subgroupShuffle((value), ((gl_SubgroupInvocationID & ~3u) + uint(quadLane)))

			""" + tools + "\n" + shadowWorldOverrides + """

			void iris_ObjCubedDecode() {
			    iris_ObjCubedPosition = iris_Position;
			    iris_ObjCubedTexCoord = iris_UV0;
			    iris_ObjCubedTexCoord2 = iris_UV0;
			    iris_ObjCubedOverlayColor = vec4(1.0);
			    iris_ObjCubedVertexColor = vec4(1.0);
			    iris_ObjCubedLightColor = vec4(1.0);
			    iris_ObjCubedIsGUI = 0;
			    iris_ObjCubedIsHand = 0;
			    iris_ObjCubedNoShadow = 0;
			""" + body + """
			}

			""" + shadowWorldCleanup + """
			#undef noshadow
			#undef isHand
			#undef isGUI
			#undef isCustom
			#undef transition
			#undef lightColor
			#undef vertexColor
			#undef overlayColor
			#undef texCoord2
			#undef texCoord
			#undef GameTime
			#undef TextureMat
			#undef ModelViewMat
			#undef ProjMat
			#undef Normal
			#undef Color
			#undef UV0
			#undef Pos
			#undef Position
			#undef Sampler0
			#undef subgroupQuadBroadcast
			#undef NO_CARDINAL_LIGHTING
			#undef ENTITY

			vec3 iris_ObjCubedPositionValue() {
			    return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedPosition : iris_Position;
			}

			vec2 iris_ObjCubedUVValue() {
			    return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedTexCoord : iris_UV0;
			}

			vec4 iris_ObjCubedColorValue() {
			    return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedOverlayColor : iris_Color;
			}

			void main() {
			    iris_ObjCubedDecode();
			    iris_ObjCubedOriginalMain();
			}
			""";
		if (!blockPass) return bridge;
		return bridge
			.replace("#define ENTITY", "#define BLOCK")
			.replace("#undef ENTITY", "#undef BLOCK")
			.replace("return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedOverlayColor : iris_Color;", "return iris_Color;");
	}

	private static String buildSodiumTerrainBridge(String source, String tools, String body) {
		String gameTimeExpression = Pattern.compile("\\bu_CurrentTime\\b").matcher(source).find()
			? "(float(u_CurrentTime % 24000) / 24000.0)"
			: "(float(worldTime % 24000) / 24000.0)";
		String bridge = buildBridge(tools, body, false, true)
			.replace("#define Position iris_Position", "#define Position iris_ObjCubedRawPosition")
			.replace("#define UV0 iris_UV0", "#define UV0 iris_ObjCubedRawUV")
			.replace("#define Color iris_Color", "#define Color iris_ObjCubedRawColor")
			.replace("#define Normal iris_Normal", "#define Normal vec3(0.0, 1.0, 0.0)")
			.replace("#define ProjMat iris_ProjMat", "#define ProjMat u_ProjectionMatrix")
			.replace("#define ModelViewMat iris_transforms.ModelViewMat", "#define ModelViewMat u_ModelViewMatrix")
			.replace("#define TextureMat iris_transforms.TextureMat", "#define TextureMat mat4(1.0)")
			.replace("#define GameTime iris_globalInfo.GameTime", "#define GameTime " + gameTimeExpression)
			.replace("iris_ObjCubedPosition = iris_Position;", "iris_ObjCubedPosition = iris_ObjCubedRawPosition;")
			.replace("iris_ObjCubedTexCoord = iris_UV0;", "iris_ObjCubedTexCoord = iris_ObjCubedRawUV;")
			.replace("iris_ObjCubedTexCoord2 = iris_UV0;", "iris_ObjCubedTexCoord2 = iris_ObjCubedRawUV;")
			.replace("iris_ObjCubedPosition : iris_Position", "iris_ObjCubedPosition : iris_ObjCubedRawPosition")
			.replace("iris_ObjCubedTexCoord : iris_UV0", "iris_ObjCubedTexCoord : iris_ObjCubedRawUV")
			.replace("iris_ObjCubedOverlayColor : iris_Color", "iris_ObjCubedOverlayColor : iris_ObjCubedRawColor")
			.replace("return iris_Color;", "return iris_ObjCubedRawColor;");
		String oldMain = """
			void main() {
			    iris_ObjCubedDecode();
			    iris_ObjCubedOriginalMain();
			}
			""";
		String newMain = """
			void main() {
			    _vert_init();
			    iris_ObjCubedRawPosition = _vert_position + u_RegionOffset + _get_draw_translation(_draw_id);
			    iris_ObjCubedRawUV = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
			    iris_ObjCubedRawColor = _vert_color;
			    iris_ObjCubedDecode();
			    if (iris_ObjCubedIsCustom != 0) {
			        _vert_position = iris_ObjCubedPosition - u_RegionOffset - _get_draw_translation(_draw_id);
			        _vert_tex_diffuse_coord = iris_ObjCubedTexCoord;
			        _vert_tex_diffuse_coord_bias = vec2(0.0);
			    }
			    iris_ObjCubedOriginalMain();
			}
			""";
		bridge = bridge.replace(oldMain, newMain);
		String declarations = """
			vec3 iris_ObjCubedRawPosition;
			vec2 iris_ObjCubedRawUV;
			vec4 iris_ObjCubedRawColor;
			""";
		if (!Pattern.compile("\\bu_CurrentTime\\b").matcher(source).find() && !Pattern.compile("\\buniform\\s+int\\s+worldTime\\b").matcher(source).find()) {
			declarations += "uniform int worldTime;\n";
		}
		return declarations + bridge;
	}
}
