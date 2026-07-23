package io.github.jhooc77.objcubedoptifine;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Offline patcher. It never downloads or redistributes OptiFine. */
public final class PatcherMain {
	private static final Set<String> TARGETS = Set.of(
		"srg/net/optifine/shaders/ShadersCompatibility.class",
		"notch/net/optifine/shaders/ShadersCompatibility.class"
	);
	private static final Set<String> BRIDGES = Set.of(
		"srg/io/github/jhooc77/objcubedoptifine/ObjCubedOptiFineBridge.class",
		"notch/io/github/jhooc77/objcubedoptifine/ObjCubedOptiFineBridge.class"
	);
	private static final String REMAP_DESCRIPTOR = "(Lnet/optifine/shaders/Program;Lnet/optifine/shaders/config/ShaderType;Lnet/optifine/util/LineBuffer;)Lnet/optifine/util/LineBuffer;";
	private static final String PRE1_SHA256 = "044808D5B5B3FDF5D42155B13A83A704682C3D1ADBC9FB5572586301D0E1ED09";
	private static final String PRE2_SHA256 = "F8EB9026E4DA2444E18D5601D3DEDE2BD19CF514D02095FFCDB0E101687C2172";

	private PatcherMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1 || args.length > 2) {
			System.err.println("Usage: java -jar obj-cubed-optifine-patcher-mc26.1.2-k1-<pre1|pre2>.jar <official-optifine.jar> [output.jar]");
			System.exit(2);
		}
		Path input = Path.of(args[0]).toAbsolutePath().normalize();
		if (!Files.isRegularFile(input)) throw new IOException("Input JAR does not exist: " + input);
		Path output = args.length == 2
			? Path.of(args[1]).toAbsolutePath().normalize()
			: input.resolveSibling(stripJar(input.getFileName().toString()) + "-objcubed.jar");
		if (input.equals(output)) throw new IOException("Output must differ from input");

		Target target = loadTarget();
		String hash = sha256(input);
		if (!target.sha256().equals(hash)) {
			throw new IOException("Unsupported OptiFine build (SHA-256 " + hash + "). This patcher only accepts 26.1.2 HD U K1 " + target.name() + ".");
		}

		Path parent = output.getParent();
		if (parent != null) Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
		try {
			patch(input, temporary);
			Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Throwable throwable) {
			Files.deleteIfExists(temporary);
			throw throwable;
		}
		System.out.println("Patched OptiFine written to: " + output);
		System.out.println("Input SHA-256: " + hash);
		System.out.println("Target: OptiFine 26.1.2 HD U K1 " + target.name());
	}

	private static Target loadTarget() throws IOException {
		Properties properties = new Properties();
		try (InputStream input = PatcherMain.class.getResourceAsStream("/obj-cubed-optifine-target.properties")) {
			if (input == null) throw new IOException("Patcher target metadata is missing");
			properties.load(input);
		}
		return switch (properties.getProperty("target", "")) {
			case "pre1" -> new Target("pre1", PRE1_SHA256);
			case "pre2" -> new Target("pre2", PRE2_SHA256);
			default -> throw new IOException("Unknown OptiFine patcher target");
		};
	}

	private static void patch(Path input, Path output) throws IOException {
		try (JarFile jar = new JarFile(input.toFile())) {
			for (String target : TARGETS) {
				if (jar.getJarEntry(target) == null) throw new IOException("OptiFine shader compatibility class was not found: " + target);
			}

			Manifest manifest = jar.getManifest();
			if (manifest == null) {
				manifest = new Manifest();
				manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
			}
			try (OutputStream file = Files.newOutputStream(output);
				 JarOutputStream out = new JarOutputStream(file, manifest)) {
				Set<String> written = new HashSet<>();
				written.add("META-INF/MANIFEST.MF");
				var entries = jar.entries();
				while (entries.hasMoreElements()) {
					JarEntry entry = entries.nextElement();
					String name = entry.getName();
					if (name.equalsIgnoreCase("META-INF/MANIFEST.MF") || isSignature(name) || !written.add(name)) continue;
					JarEntry copy = new JarEntry(name);
					copy.setTime(entry.getTime());
					out.putNextEntry(copy);
					if (!entry.isDirectory()) {
						try (InputStream in = jar.getInputStream(entry)) {
							byte[] bytes = in.readAllBytes();
							out.write(TARGETS.contains(name) ? patchShadersCompatibility(bytes) : bytes);
						}
					}
					out.closeEntry();
				}
				writeBridges(out, written);
			}
		}
	}

	private static byte[] patchShadersCompatibility(byte[] source) throws IOException {
		ClassReader reader = new ClassReader(source);
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		int[] patchedReturns = {0};
		ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!"remap".equals(name) || !REMAP_DESCRIPTOR.equals(descriptor)) return delegate;
				return new MethodVisitor(Opcodes.ASM9, delegate) {
					@Override
					public void visitInsn(int opcode) {
						if (opcode == Opcodes.ARETURN) {
							visitVarInsn(Opcodes.ALOAD, 0);
							visitVarInsn(Opcodes.ALOAD, 1);
							visitMethodInsn(Opcodes.INVOKESTATIC,
								"io/github/jhooc77/objcubedoptifine/ObjCubedOptiFineBridge",
								"inject", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
							visitTypeInsn(Opcodes.CHECKCAST, "net/optifine/util/LineBuffer");
							patchedReturns[0]++;
						}
						super.visitInsn(opcode);
					}
				};
			}
		};
		reader.accept(visitor, 0);
		if (patchedReturns[0] == 0) throw new IOException("Compatible OptiFine remap method was not found");
		return writer.toByteArray();
	}

	private static void writeBridges(JarOutputStream out, Set<String> written) throws IOException {
		try (InputStream in = PatcherMain.class.getResourceAsStream("/io/github/jhooc77/objcubedoptifine/ObjCubedOptiFineBridge.class")) {
			if (in == null) throw new IOException("Embedded bridge class is missing");
			byte[] bridge = in.readAllBytes();
			for (String name : BRIDGES) {
				if (!written.add(name)) throw new IOException("Bridge class already exists in input JAR: " + name);
				out.putNextEntry(new JarEntry(name));
				out.write(bridge);
				out.closeEntry();
			}
		}
	}

	private static boolean isSignature(String name) {
		String upper = name.toUpperCase(java.util.Locale.ROOT);
		return upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA"));
	}

	private static String stripJar(String name) {
		return name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
	}

	private static String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(path)) {
				byte[] buffer = new byte[64 * 1024];
				for (int read; (read = in.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
			}
			return HexFormat.of().withUpperCase().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}

	private record Target(String name, String sha256) {
	}
}
