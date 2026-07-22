package io.github.jhooc77.objcubedoptifine;

import java.lang.reflect.Method;

public final class ObjCubedOptiFineBridgeSelfTest {
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
		System.out.println("OptiFine shader bridge self-test passed");
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
