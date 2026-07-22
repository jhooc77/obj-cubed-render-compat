package io.github.jhooc77.objcubedcompat.shader;

import java.lang.reflect.Method;
import java.util.Arrays;

public final class ObjCubedShaderInjectorSelfTest {
    private static final String VANILLA_SOURCE = """
        #version 330 core
        in vec3 iris_Position;
        in vec2 iris_UV0;
        in vec4 iris_Color;
        in vec3 iris_Normal;
        void main() {
            gl_Position = vec4(iris_Position, 1.0);
        }
        """;

    private static final String SODIUM_SOURCE = """
        #version 330 core
        vec3 _vert_position;
        vec2 _vert_tex_diffuse_coord;
        vec2 _vert_tex_diffuse_coord_bias;
        vec4 _vert_color;
        vec3 u_RegionOffset;
        float u_TexCoordShrink;
        int _draw_id;
        vec3 _get_draw_translation(int id) { return vec3(0.0); }
        void _vert_init() {}
        void main() {
            _vert_init();
            gl_Position = vec4(_vert_position + u_RegionOffset, 1.0);
        }
        """;

    private ObjCubedShaderInjectorSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        verifyMixinTargets();

        Method injectVertex = ObjCubedShaderInjector.class.getDeclaredMethod(
            "injectVertex", String.class, String.class, String.class, boolean.class, boolean.class);
        injectVertex.setAccessible(true);

        String tools = "ivec4 getmeta(ivec2 p, int i) { return ivec4(0); }";
        String decoder = "isCustom = 1; Pos = Position + vec3(1.0); texCoord = UV0; overlayColor = Color;";
        String entity = (String) injectVertex.invoke(null, VANILLA_SOURCE, tools, decoder, false, false);
        require(entity != null, "entity injection returned null");
        require(entity.contains("void iris_ObjCubedOriginalMain()"), "entity main was not wrapped");
        require(entity.contains("iris_ObjCubedPositionValue()"), "entity position was not redirected");
        require(entity.contains("#define ENTITY"), "entity decoder mode is missing");
        require(!entity.contains("#define BLOCK"), "block decoder mode leaked into entity pass");

        String block = (String) injectVertex.invoke(null, VANILLA_SOURCE, tools, decoder, false, true);
        require(block != null && block.contains("#define BLOCK"), "block decoder mode is missing");
        require(!block.contains("#define ENTITY"), "entity decoder mode leaked into block pass");

        String sodium = ObjCubedShaderInjector.injectRawSodiumTerrainWithSources(SODIUM_SOURCE, tools, decoder);
        require(sodium.contains("iris_ObjCubedRawPosition"), "Sodium position bridge is missing");
        require(sodium.contains("_vert_tex_diffuse_coord_bias * u_TexCoordShrink"), "Sodium UV bias is missing");
        require(sodium.contains("uniform sampler2D u_BlockTex;"), "Sodium atlas binding is missing");

        System.out.println("Iris/Sodium shader injection self-test passed");
    }

    private static void verifyMixinTargets() throws Exception {
        ClassLoader loader = ObjCubedShaderInjectorSelfTest.class.getClassLoader();
        Class<?> transformPatcher = Class.forName(
            "net.irisshaders.iris.pipeline.transform.TransformPatcher", false, loader);
        require(hasMethod(transformPatcher, "transform", 7), "Iris transform target is missing");
        require(hasMethod(transformPatcher, "patchSodium", 8)
            || hasMethod(transformPatcher, "patchSodium", 9), "Supported Iris patchSodium target is missing");

        Class<?> irisSamplers = Class.forName("net.irisshaders.iris.samplers.IrisSamplers", false, loader);
        require(hasMethod(irisSamplers, "addLevelSamplers", 6), "Iris sampler target is missing");

        Class<?> shadowRenderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer", false, loader);
        require(hasMethod(shadowRenderer, "renderShadows", 3), "Iris shadow target is missing");
        require(Arrays.stream(shadowRenderer.getDeclaredFields())
            .anyMatch(field -> field.getName().equals("outlineBuffers")), "Iris outline buffer field is missing");

        Class<?> shaderLoader = Class.forName(
            "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", false, loader);
        require(hasMethod(shaderLoader, "getShaderSource", 1), "Sodium shader source target is missing");
    }

    private static boolean hasMethod(Class<?> owner, String name, int parameterCount) {
        return Arrays.stream(owner.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals(name) && method.getParameterCount() == parameterCount);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
