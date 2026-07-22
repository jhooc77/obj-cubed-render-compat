package io.github.jhooc77.objcubedoptifine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Optional local integration probe for unredistributable official OptiFine JARs.
 * Run through the Gradle officialOptiFineProbe task with -PoptifineJar=...
 */
public final class OfficialOptiFineRemapProbe {
	private static final String LEGACY_VERTEX = """
		#version 120
		varying vec2 texcoord;
		varying vec4 color;
		void main() {
		    gl_Position = ftransform();
		    texcoord = gl_MultiTexCoord0.xy;
		    color = gl_Color;
		}
		""";

	private OfficialOptiFineRemapProbe() {
	}

	public static void main(String[] args) throws Exception {
		Path input = Path.of(args[0]).toAbsolutePath().normalize();
		Path classes = Files.createTempDirectory("optifine-remap-classes-");
		try {
			extractSrgClasses(input, classes);
			try (URLClassLoader loader = new URLClassLoader(
				new URL[]{classes.toUri().toURL()}, OfficialOptiFineRemapProbe.class.getClassLoader())) {
				String source = invokeOfficialRemap(loader);
				String injected = invokeBridge(source);
				require(injected != null && injected.contains("objcubed_optifine_bridge_v1"),
					"obj-cubed bridge rejected OptiFine's remapped vertex source\n" + source);
				System.out.println("Official OptiFine remap -> obj-cubed injection passed: " + input.getFileName());
				System.out.println("Remapped lines: " + source.lines().count());
				System.out.println("Injected lines: " + injected.lines().count());
			}
		} finally {
			try (var paths = Files.walk(classes)) {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (Exception exception) {
						throw new RuntimeException(exception);
					}
				});
			}
		}
	}

	private static void extractSrgClasses(Path input, Path classes) throws Exception {
		try (JarFile jar = new JarFile(input.toFile())) {
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String name = entry.getName();
				if (!name.startsWith("srg/") || !name.endsWith(".class")) continue;
				Path output = classes.resolve(name.substring(4));
				Files.createDirectories(output.getParent());
				try (var stream = jar.getInputStream(entry)) {
					Files.copy(stream, output);
				}
			}
		}
	}

	private static String invokeOfficialRemap(ClassLoader loader) throws Exception {
		Class<?> programStageClass = Class.forName("net.optifine.shaders.ProgramStage", true, loader);
		Object gbuffers = enumValue(programStageClass, "GBUFFERS");
		Class<?> programClass = Class.forName("net.optifine.shaders.Program", true, loader);
		Constructor<?> programConstructor = programClass.getConstructor(
			int.class, String.class, programStageClass, boolean.class);
		Object program = programConstructor.newInstance(0, "gbuffers_entities", gbuffers, true);

		Class<?> shaderTypeClass = Class.forName("net.optifine.shaders.config.ShaderType", true, loader);
		Object vertex = enumValue(shaderTypeClass, "VERTEX");
		Class<?> lineBufferClass = Class.forName("net.optifine.util.LineBuffer", true, loader);
		Object lines = lineBufferClass.getConstructor(String[].class)
			.newInstance((Object) LEGACY_VERTEX.split("\\R", -1));

		Class<?> compatibilityClass = Class.forName(
			"net.optifine.shaders.ShadersCompatibility", true, loader);
		Method remap = compatibilityClass.getMethod(
			"remap", programClass, shaderTypeClass, lineBufferClass);
		Object remapped = remap.invoke(null, program, vertex, lines);
		return String.join("\n", (String[]) lineBufferClass.getMethod("getLines").invoke(remapped));
	}

	private static String invokeBridge(String source) throws Exception {
		Method inject = ObjCubedOptiFineBridge.class.getDeclaredMethod(
			"injectSource", String.class, String.class, String.class, boolean.class);
		inject.setAccessible(true);
		return (String) inject.invoke(null, source,
			"ivec4 getmeta(ivec2 p, int i) { return ivec4(0); }",
			"isCustom = 1; Pos = Position; texCoord = UV0; overlayColor = Color;", false);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Object enumValue(Class<?> type, String name) {
		return Enum.valueOf((Class<? extends Enum>) type, name);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
