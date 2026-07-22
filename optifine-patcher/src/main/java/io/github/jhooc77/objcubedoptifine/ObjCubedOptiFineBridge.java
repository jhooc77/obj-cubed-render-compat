package io.github.jhooc77.objcubedoptifine;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loaded inside a locally patched OptiFine installation. Uses reflection to avoid bundling OptiFine APIs. */
public final class ObjCubedOptiFineBridge {
	private static final Pattern VERSION = Pattern.compile("(?m)^(#version[^\\r\\n]*)(?:\\r?\\n)");
	private static final Pattern MAIN = Pattern.compile("(?m)^([ \\t]*)void\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
	private static final String MARKER = "objcubed_optifine_bridge_v1";

	private ObjCubedOptiFineBridge() {
	}

	/** Signature deliberately uses Object so this class can be compiled without OptiFine. */
	public static Object inject(Object lineBuffer, Object program, Object shaderType) {
		if (lineBuffer == null || program == null || shaderType == null) return lineBuffer;
		try {
			if (!"VERTEX".equalsIgnoreCase(shaderType.toString())) return lineBuffer;
			String programName = String.valueOf(program.getClass().getMethod("getName").invoke(program));
			Pass pass = classify(programName);
			if (pass == Pass.NONE) return lineBuffer;

			String[] lines = (String[]) lineBuffer.getClass().getMethod("getLines").invoke(lineBuffer);
			String source = String.join("\n", lines);
			if (source.contains(MARKER)) return lineBuffer;

			String tools = readResource("minecraft", "shaders/include/objmc_tools.glsl");
			String decoder = readResource("minecraft", "shaders/include/objmc_main.glsl");
			if (tools == null || decoder == null) return lineBuffer;
			String injected = injectSource(source, tools, decoder, pass == Pass.BLOCK);
			if (injected == null) return lineBuffer;
			return lineBuffer.getClass().getConstructor(String[].class)
				.newInstance((Object) injected.split("\\R", -1));
		} catch (Throwable throwable) {
			System.err.println("[obj-cubed/OptiFine] Shader injection skipped: " + throwable);
			return lineBuffer;
		}
	}

	private static Pass classify(String name) {
		if (name == null) return Pass.NONE;
		String n = name.toLowerCase(Locale.ROOT);
		if (n.contains("terrain") || n.contains("damagedblock")) return Pass.BLOCK;
		if (n.contains("entities") || n.contains("entity") || n.contains("item")
			|| n.contains("hand") || n.contains("armor_glint") || n.contains("spidereyes")
			|| n.contains("beaconbeam") || n.equals("gbuffers_block")) return Pass.ENTITY;
		return Pass.NONE;
	}

	private static String injectSource(String source, String tools, String decoder, boolean blockPass) {
		Matcher version = VERSION.matcher(source);
		Matcher main = MAIN.matcher(source);
		if (!version.find() || !main.find()) return null;

		String redirected = ensureInput(source, "vec3", "vaPosition");
		redirected = ensureInput(redirected, "vec4", "vaColor");
		redirected = ensureInput(redirected, "vec2", "vaUV0");
		redirected = ensureInput(redirected, "vec3", "vaNormal");
		redirected = ensureUniform(redirected, "sampler2D", "gtexture");
		redirected = ensureUniform(redirected, "mat4", "projectionMatrix");
		redirected = ensureUniform(redirected, "mat4", "modelViewMatrix");
		redirected = ensureUniform(redirected, "mat4", "textureMatrix");
		redirected = ensureUniform(redirected, "int", "worldTime");

		redirected = redirectInputReads(redirected, "vec3", "vaPosition", "objCubedPositionValue()");
		if (redirected == null) return null;
		redirected = redirectInputReads(redirected, "vec2", "vaUV0", "objCubedUvValue()");
		if (redirected == null) return null;
		redirected = redirectInputReads(redirected, "vec4", "vaColor", "objCubedColorValue()");
		if (redirected == null) return null;

		main = MAIN.matcher(redirected);
		if (!main.find()) return null;
		redirected = main.replaceFirst(Matcher.quoteReplacement(main.group(1) + "void objCubedOptiFineOriginalMain()"));
		version = VERSION.matcher(redirected);
		if (!version.find()) return null;
		redirected = version.replaceFirst(Matcher.quoteReplacement(version.group(1) + "\n"
			+ "#extension GL_KHR_shader_subgroup_basic : require\n"
			+ "#extension GL_KHR_shader_subgroup_shuffle : require\n"
			+ "// " + MARKER + "\n"
			+ "vec3 objCubedPositionValue();\n"
			+ "vec2 objCubedUvValue();\n"
			+ "vec4 objCubedColorValue();\n"));
		return redirected + "\n" + bridge(tools, decoder, blockPass);
	}

	private static String bridge(String tools, String decoder, boolean blockPass) {
		String bridge = """
			vec3 objCubedPosition;
			vec2 objCubedTexCoord;
			vec2 objCubedTexCoord2;
			vec4 objCubedOverlayColor;
			vec4 objCubedVertexColor;
			vec4 objCubedLightColor;
			float objCubedTransition;
			int objCubedIsCustom;
			int objCubedIsGUI;
			int objCubedIsHand;
			int objCubedNoShadow;

			#define ENTITY
			#define NO_CARDINAL_LIGHTING
			#define Sampler0 gtexture
			#define Position vaPosition
			#define Pos objCubedPosition
			#define UV0 vaUV0
			#define Color vaColor
			#define Normal vaNormal
			#define ProjMat projectionMatrix
			#define ModelViewMat modelViewMatrix
			#define TextureMat textureMatrix
			#define GameTime (float(worldTime % 24000) / 24000.0)
			#define texCoord objCubedTexCoord
			#define texCoord2 objCubedTexCoord2
			#define overlayColor objCubedOverlayColor
			#define vertexColor objCubedVertexColor
			#define lightColor objCubedLightColor
			#define transition objCubedTransition
			#define isCustom objCubedIsCustom
			#define isGUI objCubedIsGUI
			#define isHand objCubedIsHand
			#define noshadow objCubedNoShadow
			#define subgroupQuadBroadcast(value, quadLane) subgroupShuffle((value), ((gl_SubgroupInvocationID & ~3u) + uint(quadLane)))

			""" + tools + """

			void objCubedDecode() {
			    objCubedPosition = vaPosition;
			    objCubedTexCoord = vaUV0;
			    objCubedTexCoord2 = vaUV0;
			    objCubedOverlayColor = vec4(1.0);
			    objCubedVertexColor = vec4(1.0);
			    objCubedLightColor = vec4(1.0);
			    objCubedIsGUI = 0;
			    objCubedIsHand = 0;
			    objCubedNoShadow = 0;
			""" + decoder + """
			}

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

			vec3 objCubedPositionValue() {
			    return objCubedIsCustom != 0 ? objCubedPosition : vaPosition;
			}

			vec2 objCubedUvValue() {
			    return objCubedIsCustom != 0 ? objCubedTexCoord : vaUV0;
			}

			vec4 objCubedColorValue() {
			    return objCubedIsCustom != 0 ? objCubedOverlayColor : vaColor;
			}

			void main() {
			    objCubedDecode();
			    objCubedOptiFineOriginalMain();
			}
			""";
		if (!blockPass) return bridge;
		return bridge
			.replace("#define ENTITY", "#define BLOCK")
			.replace("#undef ENTITY", "#undef BLOCK")
			.replace("return objCubedIsCustom != 0 ? objCubedOverlayColor : vaColor;", "return vaColor;");
	}

	private static String ensureInput(String source, String type, String name) {
		if (Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+" + type + "\\s+" + name + "\\s*;").matcher(source).find()) return source;
		return insertAfterVersion(source, "in " + type + " " + name + ";\n");
	}

	private static String ensureUniform(String source, String type, String name) {
		if (Pattern.compile("(?m)^\\s*uniform\\s+" + type + "\\s+" + name + "\\s*;").matcher(source).find()) return source;
		return insertAfterVersion(source, "uniform " + type + " " + name + ";\n");
	}

	private static String insertAfterVersion(String source, String text) {
		Matcher matcher = VERSION.matcher(source);
		return matcher.find() ? matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + "\n" + text)) : source;
	}

	private static String redirectInputReads(String source, String type, String inputName, String valueExpression) {
		Pattern declaration = Pattern.compile("(?m)^([ \\t]*(?:layout\\s*\\([^\\r\\n]*\\)\\s*)?in\\s+" + type + "\\s+)"
			+ Pattern.quote(inputName) + "(\\s*;)");
		if (!declaration.matcher(source).find()) return null;
		String redirected = Pattern.compile("\\b" + Pattern.quote(inputName) + "\\b")
			.matcher(source).replaceAll(Matcher.quoteReplacement(valueExpression));
		Pattern redirectedDeclaration = Pattern.compile("(?m)^([ \\t]*(?:layout\\s*\\([^\\r\\n]*\\)\\s*)?in\\s+" + type + "\\s+)"
			+ Pattern.quote(valueExpression) + "(\\s*;)");
		Matcher matcher = redirectedDeclaration.matcher(redirected);
		if (!matcher.find()) return null;
		return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + inputName + matcher.group(2)));
	}

	private static String readResource(String namespace, String path) throws Exception {
		Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
		Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
		Object manager = minecraftClass.getMethod("getResourceManager").invoke(minecraft);
		Class<?> identifierClass = Class.forName("net.minecraft.resources.Identifier");
		Object identifier = identifierClass.getMethod("fromNamespaceAndPath", String.class, String.class)
			.invoke(null, namespace, path);
		Method getResource = manager.getClass().getMethod("getResource", identifierClass);
		Object optionalValue = getResource.invoke(manager, identifier);
		if (!(optionalValue instanceof Optional<?> optional) || optional.isEmpty()) return null;
		Object resource = optional.get();
		try (InputStream input = (InputStream) resource.getClass().getMethod("open").invoke(resource)) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private enum Pass {
		NONE, ENTITY, BLOCK
	}
}
