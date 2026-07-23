package io.github.jhooc77.objcubedoptifine;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Method;

public final class ObjCubedOptiFineBridgeSelfTest {
	private static final String REMAP_DESCRIPTOR = "(Lnet/optifine/shaders/Program;Lnet/optifine/shaders/config/ShaderType;Lnet/optifine/util/LineBuffer;)Lnet/optifine/util/LineBuffer;";
	private static final String SOURCE = """
		#version 150
		in vec3 vaPosition;
		in vec4 vaColor;
		in vec2 vaUV0;
		in vec3 vaNormal;
		uniform mat4 projectionMatrix;
		uniform mat4 modelViewMatrix;
		void main() {
		    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
		}
		""";

	private ObjCubedOptiFineBridgeSelfTest() {
	}

	public static void main(String[] args) throws Exception {
		testStructuralPatcher();

		String decoder = "isCustom = 1; Pos = Position + vec3(1.0); texCoord = UV0; overlayColor = Color;";
		String entity = inject(SOURCE, "ivec4 getmeta(ivec2 p, int i) { return ivec4(0); }", decoder, false);
		require(entity != null, "entity injection returned null");
		require(entity.contains("objcubed_optifine_bridge_v1"), "marker missing");
		require(entity.contains("void objCubedOptiFineOriginalMain()"), "original main was not wrapped");
		require(entity.contains("objCubedPositionValue()"), "position was not redirected");
		require(entity.contains("#define ENTITY"), "entity decoder was not selected");
		require(!entity.contains("#define BLOCK"), "block decoder leaked into entity pass");

		String block = inject(SOURCE, "", "isCustom = 0;", true);
		require(block != null && block.contains("#define BLOCK"), "block decoder was not selected");
		require(!block.contains("#define ENTITY"), "entity decoder leaked into block pass");
		System.out.println("OptiFine structural patcher and shader bridge self-test passed");
	}

	private static void testStructuralPatcher() throws Exception {
		byte[] patched = PatcherMain.patchShadersCompatibility(remapFixture(true));
		int[] bridgeCalls = {0};
		new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
						if (opcode == Opcodes.INVOKESTATIC
							&& "io/github/jhooc77/objcubedoptifine/ObjCubedOptiFineBridge".equals(owner)
							&& "inject".equals(methodName)) bridgeCalls[0]++;
					}
				};
			}
		}, 0);
		require(bridgeCalls[0] == 1, "compatible OptiFine remap structure was not patched");

		try {
			PatcherMain.patchShadersCompatibility(remapFixture(false));
			throw new AssertionError("incompatible OptiFine remap structure was accepted");
		} catch (IOException expected) {
			// expected
		}
	}

	private static byte[] remapFixture(boolean compatible) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(
			Opcodes.V17,
			Opcodes.ACC_PUBLIC,
			"net/optifine/shaders/ShadersCompatibility",
			null,
			"java/lang/Object",
			null
		);
		String descriptor = compatible ? REMAP_DESCRIPTOR : "()Ljava/lang/Object;";
		MethodVisitor method = writer.visitMethod(
			Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			"remap",
			descriptor,
			null,
			null
		);
		method.visitCode();
		if (compatible) method.visitVarInsn(Opcodes.ALOAD, 2);
		else method.visitInsn(Opcodes.ACONST_NULL);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(1, compatible ? 3 : 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static String inject(String source, String tools, String decoder, boolean block) throws Exception {
		Method method = ObjCubedOptiFineBridge.class.getDeclaredMethod(
			"injectSource", String.class, String.class, String.class, boolean.class);
		method.setAccessible(true);
		return (String) method.invoke(null, source, tools, decoder, block);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
