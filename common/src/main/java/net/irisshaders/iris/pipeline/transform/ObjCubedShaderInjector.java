package net.irisshaders.iris.pipeline.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.apache.commons.io.IOUtils;

public final class ObjCubedShaderInjector {
   private static final Identifier TOOLS = Identifier.fromNamespaceAndPath("minecraft", "shaders/include/objmc_tools.glsl");
   private static final Identifier MAIN = Identifier.fromNamespaceAndPath("minecraft", "shaders/include/objmc_main.glsl");
   private static final Pattern VERSION = Pattern.compile("(?m)^(#version[^\\r\\n]*)(?:\\r?\\n)");
   private static final Pattern MAIN_FUNCTION = Pattern.compile("(?m)^([ \\t]*)void\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
   private static final String POSITION_VALUE = "iris_ObjCubedPositionValue()";
   private static final String UV_VALUE = "iris_ObjCubedUVValue()";
   private static final String COLOR_VALUE = "iris_ObjCubedColorValue()";
   private static final String ENTITY_SHADOW_PROGRAM = "shadow_entities_cutout";
   private static final String SUBGROUP_SHUFFLE_EXTENSION = "#extension GL_KHR_shader_subgroup_shuffle : require";
   private static final String SUBGROUP_QUAD_EXTENSION = "#extension GL_KHR_shader_subgroup_quad : require";
   private static final String SUBGROUP_SHUFFLE_BRIDGE = "#define subgroupQuadBroadcast(value, quadLane) subgroupShuffle((value), ((gl_SubgroupInvocationID & ~3u) + uint(quadLane)))";

   private ObjCubedShaderInjector() {
   }

   public static Map<PatchShaderType, String> inject(Map<PatchShaderType, String> transformed, VanillaParameters parameters, String programName) {
      if (transformed != null && isEligible(parameters, programName)) {
         Optional<String> tools = read(TOOLS);
         Optional<String> main = read(MAIN);
         return !tools.isEmpty() && !main.isEmpty()
            ? injectWithSources(transformed, programName, tools.get(), main.get(), isEntityShadowProgram(programName), false)
            : transformed;
      } else {
         return transformed;
      }
   }

   public static Map<PatchShaderType, String> injectTerrain(Map<PatchShaderType, String> transformed, String programName) {
      if (transformed != null && Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))) {
         Optional<String> tools = read(TOOLS);
         Optional<String> main = read(MAIN);
         return !tools.isEmpty() && !main.isEmpty() ? injectWithSources(transformed, programName, tools.get(), main.get(), false, true) : transformed;
      } else {
         return transformed;
      }
   }

   public static Map<PatchShaderType, String> injectCrumbling(Map<PatchShaderType, String> transformed, String programName) {
      if (transformed != null && Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))) {
         Optional<String> tools = read(TOOLS);
         Optional<String> main = read(MAIN);
         return !tools.isEmpty() && !main.isEmpty() ? injectCrumblingWithSources(transformed, programName, tools.get(), main.get()) : transformed;
      } else {
         return transformed;
      }
   }

   static Map<PatchShaderType, String> injectCrumblingWithSources(Map<PatchShaderType, String> transformed, String programName, String tools, String main) {
      String vertex = transformed.get(PatchShaderType.VERTEX);
      if (vertex == null) {
         return transformed;
      }

      if (!Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+[iu]?vec2\\s+iris_UV1\\s*;").matcher(vertex).find()) {
         Matcher version = VERSION.matcher(vertex);
         if (!version.find()) {
            return transformed;
         }

         vertex = version.replaceFirst(Matcher.quoteReplacement(version.group(1) + "\nin ivec2 iris_UV1;\n"));
      }

      String injected = injectVertex(vertex, tools, main, false, true);
      if (injected == null) {
         return transformed;
      }

      injected = injected.replace("uniform sampler2D iris_ObjCubedSampler;", "uniform sampler2D iris_ObjCubedAtlasSampler;")
         .replace("#define Sampler0 iris_ObjCubedSampler", "#define Sampler0 iris_ObjCubedAtlasSampler")
         .replace("#define UV0 iris_UV0", "#define UV0 (vec2(iris_UV1) / 32767.0)")
         .replace("return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedTexCoord : iris_UV0;", "return iris_UV0;");
      EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
      result.putAll(transformed);
      result.put(PatchShaderType.VERTEX, injected);
      return result;
   }

   public static Map<PatchShaderType, String> injectSodiumTerrain(Map<PatchShaderType, String> transformed, String programName) {
      if (transformed != null && Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))) {
         Optional<String> tools = read(TOOLS);
         Optional<String> main = read(MAIN);
         return !tools.isEmpty() && !main.isEmpty() ? injectSodiumTerrainWithSources(transformed, programName, tools.get(), main.get(), false) : transformed;
      } else {
         return transformed;
      }
   }

   public static Map<PatchShaderType, String> injectVulkanSodiumTerrain(Map<PatchShaderType, String> transformed, String programName) {
      if (transformed != null && Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))) {
         Optional<String> tools = read(TOOLS);
         Optional<String> main = read(MAIN);
         return !tools.isEmpty() && !main.isEmpty() ? injectSodiumTerrainWithSources(transformed, programName, tools.get(), main.get(), true) : transformed;
      } else {
         return transformed;
      }
   }

   public static String injectRawSodiumTerrain(String source, boolean stableBlockAnchor) {
      if (source != null && !source.contains("iris_ObjCubedDecode") && Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))) {
         Optional<String> tools = read(TOOLS);
         Optional<String> main = read(MAIN);
         return !tools.isEmpty() && !main.isEmpty() ? injectRawSodiumTerrainWithSources(source, tools.get(), main.get(), stableBlockAnchor) : source;
      } else {
         return source;
      }
   }

   public static String injectRawSodiumTerrainWithSources(String source, String tools, String main, boolean stableBlockAnchor) {
      if (source != null
         && !source.contains("iris_ObjCubedDecode")
         && tools != null
         && main != null
         && Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))) {
         String injected = injectSodiumTerrainVertex(source, tools, main, stableBlockAnchor);
         return injected == null
            ? source
            : injected.replace("uniform sampler2D iris_ObjCubedSampler;", "").replace("#define Sampler0 iris_ObjCubedSampler", "#define Sampler0 u_BlockTex");
      } else {
         return source;
      }
   }

   static Map<PatchShaderType, String> injectSodiumTerrainWithSources(Map<PatchShaderType, String> transformed, String programName, String tools, String main) {
      return injectSodiumTerrainWithSources(transformed, programName, tools, main, false);
   }

   static Map<PatchShaderType, String> injectSodiumTerrainWithSources(
      Map<PatchShaderType, String> transformed, String programName, String tools, String main, boolean stableBlockAnchor
   ) {
      String vertex = transformed.get(PatchShaderType.VERTEX);
      if (vertex == null) {
         return transformed;
      } else {
         String injected = injectSodiumTerrainVertex(vertex, tools, main, stableBlockAnchor);
         if (injected == null) {
            Iris.logger.warn("obj-cubed Sodium terrain compatibility was eligible for {} but Sodium's unpack/main bridge could not be located", programName);
            return transformed;
         } else {
            EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
            result.putAll(transformed);
            result.put(PatchShaderType.VERTEX, injected);
            return result;
         }
      }
   }

   static Map<PatchShaderType, String> injectWithSources(
      Map<PatchShaderType, String> transformed, String programName, String tools, String main, boolean shadowPass, boolean blockPass
   ) {
      String vertex = transformed.get(PatchShaderType.VERTEX);
      if (vertex == null) {
         return transformed;
      } else {
         String injected = injectVertex(vertex, tools, main, shadowPass, blockPass);
         if (injected == null) {
            Iris.logger
               .warn(
                  "obj-cubed {} compatibility was eligible for {} but its transformed attributes/main could not be located",
                  blockPass ? "terrain" : "entity",
                  programName
               );
            return transformed;
         } else {
            EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
            result.putAll(transformed);
            result.put(PatchShaderType.VERTEX, injected);
            return result;
         }
      }
   }

   public static Map<PatchShaderType, String> configureVulkanSubgroups(Map<PatchShaderType, String> transformed, boolean nativeQuad) {
      if (nativeQuad && transformed != null) {
         String vertex = transformed.get(PatchShaderType.VERTEX);
         if (vertex == null || !vertex.contains("iris_ObjCubedDecode")) {
            return transformed;
         } else if (vertex.contains("#extension GL_KHR_shader_subgroup_shuffle : require")
            && vertex.contains("#define subgroupQuadBroadcast(value, quadLane) subgroupShuffle((value), ((gl_SubgroupInvocationID & ~3u) + uint(quadLane)))")) {
            EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
            result.putAll(transformed);
            result.put(
               PatchShaderType.VERTEX,
               vertex.replace("#extension GL_KHR_shader_subgroup_shuffle : require", "#extension GL_KHR_shader_subgroup_quad : require")
                  .replace("#define subgroupQuadBroadcast(value, quadLane) subgroupShuffle((value), ((gl_SubgroupInvocationID & ~3u) + uint(quadLane)))", "")
            );
            return result;
         } else {
            throw new IllegalStateException("obj-cubed Vulkan subgroup bridge was not found before native-quad selection");
         }
      } else {
         return transformed;
      }
   }

   private static boolean isEligible(VanillaParameters parameters, String programName) {
      return Boolean.parseBoolean(System.getProperty("iris.objcubed.compat", "true"))
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
      return "shadow_entities_cutout".equals(programName);
   }

   private static Optional<String> read(Identifier id) {
      try {
         Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
         if (resource.isEmpty()) {
            return Optional.empty();
         }

         try (InputStream input = resource.get().open()) {
            return Optional.of(IOUtils.toString(input, StandardCharsets.UTF_8));
         }
      } catch (IOException | RuntimeException e) {
         Iris.logger.warn("Failed to read obj-cubed shader include {}", id, e);
         return Optional.empty();
      }
   }

   private static String injectVertex(String source, String tools, String decoderBody, boolean shadowPass, boolean blockPass) {
      String redirected = redirectInputReads(source, "vec3", "iris_Position", "iris_ObjCubedPositionValue()");
      if (redirected == null) {
         return null;
      }

      redirected = redirectInputReads(redirected, "vec2", "iris_UV0", "iris_ObjCubedUVValue()");
      if (redirected == null) {
         return null;
      }

      redirected = redirectInputReads(redirected, "vec4", "iris_Color", "iris_ObjCubedColorValue()");
      if (redirected == null) {
         return null;
      }

      Matcher originalMain = MAIN_FUNCTION.matcher(redirected);
      if (!originalMain.find()) {
         return null;
      }

      redirected = originalMain.replaceFirst(Matcher.quoteReplacement(originalMain.group(1) + "void iris_ObjCubedOriginalMain()"));
      String bridge = buildBridge(tools, decoderBody, shadowPass, blockPass);
      Matcher version = VERSION.matcher(redirected);
      if (!version.find()) {
         return null;
      }

      redirected = version.replaceFirst(
         Matcher.quoteReplacement(
            version.group(1)
               + "\n#extension GL_KHR_shader_subgroup_basic : require\n#extension GL_KHR_shader_subgroup_shuffle : require\nvec3 iris_ObjCubedPositionValue();\nvec2 iris_ObjCubedUVValue();\nvec4 iris_ObjCubedColorValue();\n"
         )
      );
      return redirected + "\n" + bridge;
   }

   private static String injectSodiumTerrainVertex(String source, String tools, String decoderBody, boolean stableBlockAnchor) {
      if (source.contains("_vert_init") && source.contains("_vert_position") && source.contains("_vert_tex_diffuse_coord") && source.contains("u_RegionOffset")
         )
       {
         Matcher originalMain = MAIN_FUNCTION.matcher(source);
         if (!originalMain.find()) {
            return null;
         }

         String redirected = originalMain.replaceFirst(Matcher.quoteReplacement(originalMain.group(1) + "void iris_ObjCubedOriginalMain()"));
         redirected = Pattern.compile("\\b_vert_init\\s*\\(\\s*\\)\\s*;").matcher(redirected).replaceFirst("");
         Matcher version = VERSION.matcher(redirected);
         if (!version.find()) {
            return null;
         }

         redirected = version.replaceFirst(
            Matcher.quoteReplacement(
               version.group(1) + "\n#extension GL_KHR_shader_subgroup_basic : require\n#extension GL_KHR_shader_subgroup_shuffle : require\n"
            )
         );
         return redirected + "\n" + buildSodiumTerrainBridge(source, tools, decoderBody, stableBlockAnchor);
      } else {
         return null;
      }
   }

   private static String redirectInputReads(String source, String type, String inputName, String valueExpression) {
      Pattern declaration = Pattern.compile("(?m)^([ \\t]*(?:layout\\s*\\([^\\r\\n]*\\)\\s*)?in\\s+" + type + "\\s+)" + Pattern.quote(inputName) + "(\\s*;)");
      Matcher matcher = declaration.matcher(source);
      if (!matcher.find()) {
         return null;
      }

      String redirected = Pattern.compile("\\b" + Pattern.quote(inputName) + "\\b").matcher(source).replaceAll(Matcher.quoteReplacement(valueExpression));
      Pattern redirectedDeclaration = Pattern.compile(
         "(?m)^([ \\t]*(?:layout\\s*\\([^\\r\\n]*\\)\\s*)?in\\s+" + type + "\\s+)" + Pattern.quote(valueExpression) + "(\\s*;)"
      );
      Matcher redirectedMatcher = redirectedDeclaration.matcher(redirected);
      return !redirectedMatcher.find()
         ? null
         : redirectedMatcher.replaceFirst(Matcher.quoteReplacement(redirectedMatcher.group(1) + inputName + redirectedMatcher.group(2)));
   }

   private static String buildBridge(String tools, String body, boolean shadowPass, boolean blockPass) {
      String shadowWorldOverrides = shadowPass ? "#define isgui(projection) false\n#define ishand(projection) false\n" : "";
      String shadowWorldCleanup = shadowPass ? "#undef ishand\n#undef isgui\n" : "";
      String bridge = "uniform sampler2D iris_ObjCubedSampler;\nvec3 iris_ObjCubedPosition;\nvec2 iris_ObjCubedTexCoord;\nvec2 iris_ObjCubedTexCoord2;\nvec4 iris_ObjCubedOverlayColor;\nvec4 iris_ObjCubedVertexColor;\nvec4 iris_ObjCubedLightColor;\nfloat iris_ObjCubedTransition;\nint iris_ObjCubedIsCustom;\nint iris_ObjCubedIsGUI;\nint iris_ObjCubedIsHand;\nint iris_ObjCubedNoShadow;\n\n#define ENTITY\n#define NO_CARDINAL_LIGHTING\n#define Sampler0 iris_ObjCubedSampler\n#define Position iris_Position\n#define Pos iris_ObjCubedPosition\n#define UV0 iris_UV0\n#define Color iris_Color\n#define Normal iris_Normal\n#define ProjMat iris_ProjMat\n#define ModelViewMat iris_transforms.ModelViewMat\n#define TextureMat iris_transforms.TextureMat\n#define GameTime iris_globalInfo.GameTime\n#define texCoord iris_ObjCubedTexCoord\n#define texCoord2 iris_ObjCubedTexCoord2\n#define overlayColor iris_ObjCubedOverlayColor\n#define vertexColor iris_ObjCubedVertexColor\n#define lightColor iris_ObjCubedLightColor\n#define transition iris_ObjCubedTransition\n#define isCustom iris_ObjCubedIsCustom\n#define isGUI iris_ObjCubedIsGUI\n#define isHand iris_ObjCubedIsHand\n#define noshadow iris_ObjCubedNoShadow\n// Intel's OpenGL compiler can expose KHR basic+shuffle without exposing\n// GL_KHR_shader_subgroup_quad. A quad is four consecutive subgroup\n// invocations, so derive its base from gl_SubgroupInvocationID itself.\n// Do not use gl_VertexID here: draw/base-vertex offsets are unrelated to\n// subgroup lane allocation and caused the earlier 26.2 Vulkan jitter.\n#define subgroupQuadBroadcast(value, quadLane) subgroupShuffle((value), ((gl_SubgroupInvocationID & ~3u) + uint(quadLane)))\n\n"
         + tools
         + "\n"
         + shadowWorldOverrides
         + "\nvoid iris_ObjCubedDecode() {\n    iris_ObjCubedPosition = iris_Position;\n    iris_ObjCubedTexCoord = iris_UV0;\n    iris_ObjCubedTexCoord2 = iris_UV0;\n    iris_ObjCubedOverlayColor = vec4(1.0);\n    iris_ObjCubedVertexColor = vec4(1.0);\n    iris_ObjCubedLightColor = vec4(1.0);\n    iris_ObjCubedIsGUI = 0;\n    iris_ObjCubedIsHand = 0;\n    iris_ObjCubedNoShadow = 0;\n"
         + body
         + "}\n\n"
         + shadowWorldCleanup
         + "#undef noshadow\n#undef isHand\n#undef isGUI\n#undef isCustom\n#undef transition\n#undef lightColor\n#undef vertexColor\n#undef overlayColor\n#undef texCoord2\n#undef texCoord\n#undef GameTime\n#undef TextureMat\n#undef ModelViewMat\n#undef ProjMat\n#undef Normal\n#undef Color\n#undef UV0\n#undef Pos\n#undef Position\n#undef Sampler0\n#undef subgroupQuadBroadcast\n#undef NO_CARDINAL_LIGHTING\n#undef ENTITY\n\nvec3 iris_ObjCubedPositionValue() {\n    return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedPosition : iris_Position;\n}\n\nvec2 iris_ObjCubedUVValue() {\n    return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedTexCoord : iris_UV0;\n}\n\nvec4 iris_ObjCubedColorValue() {\n    return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedOverlayColor : iris_Color;\n}\n\nvoid main() {\n    iris_ObjCubedDecode();\n    iris_ObjCubedOriginalMain();\n}\n";
      return !blockPass
         ? bridge
         : bridge.replace("#define ENTITY", "#define BLOCK")
            .replace("#undef ENTITY", "#undef BLOCK")
            .replace("return iris_ObjCubedIsCustom != 0 ? iris_ObjCubedOverlayColor : iris_Color;", "return iris_Color;");
   }

   private static String buildSodiumTerrainBridge(String source, String tools, String body, boolean stableBlockAnchor) {
      if (stableBlockAnchor) {
         body = body.replace("if (quadLeader) meta = getmeta(topleft, i);", "meta = getmeta(topleft, i);")
            .replace("t[i] = subgroupQuadBroadcast(meta, 0);", "t[i] = meta;")
            .replace("Pos = subgroupQuadBroadcast(Pos, 2) + posoffset;", "Pos = iris_ObjCubedBlockAnchor + posoffset;");
      }

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
      String oldMain = "void main() {\n    iris_ObjCubedDecode();\n    iris_ObjCubedOriginalMain();\n}\n";
      String newMain = "void main() {\n    _vert_init();\n    iris_ObjCubedRawPosition = _vert_position + u_RegionOffset + _get_draw_translation(_draw_id);\n    iris_ObjCubedBlockAnchor = iris_ObjCubedRawPosition;\n#ifdef IRIS_OBJCUBED_STABLE_BLOCK_ANCHOR\n    // XHFP stores (blockCenter - vertexPosition) * 64 in RGBA8_SNORM.\n    // Undo both SNORM's /127 and the encoder's *64 here.  This remains\n    // correct across chunk/base-vertex offsets because both terms share\n    // the same region and draw translation above.\n    iris_ObjCubedBlockAnchor += at_midBlock.xyz * (127.0 / 64.0);\n#endif\n    // Match Sodium's own gl_MultiTexCoord0 reconstruction. The packed\n    // coordinate is stored on a texel edge; the per-corner bias moves it\n    // into the intended atlas texel. Reading the un-biased coordinate can\n    // floor to the neighbouring pixel, so obj-cubed's marker/header lookup\n    // intermittently fails for chunk-baked block carriers.\n    iris_ObjCubedRawUV = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink)\n        + _vert_tex_diffuse_coord;\n    iris_ObjCubedRawColor = _vert_color;\n    iris_ObjCubedDecode();\n    if (iris_ObjCubedIsCustom != 0) {\n        _vert_position = iris_ObjCubedPosition - u_RegionOffset - _get_draw_translation(_draw_id);\n        _vert_tex_diffuse_coord = iris_ObjCubedTexCoord;\n        _vert_tex_diffuse_coord_bias = vec2(0.0);\n    }\n    iris_ObjCubedOriginalMain();\n}\n";
      bridge = bridge.replace(oldMain, newMain);
      String declarations = "vec3 iris_ObjCubedRawPosition;\nvec3 iris_ObjCubedBlockAnchor;\nvec2 iris_ObjCubedRawUV;\nvec4 iris_ObjCubedRawColor;\n";
      if (stableBlockAnchor) {
         declarations = "#define IRIS_OBJCUBED_STABLE_BLOCK_ANCHOR\n"
            + (Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+vec4\\s+at_midBlock\\s*;").matcher(source).find() ? "" : "in vec4 at_midBlock;\n")
            + declarations;
      }

      if (!Pattern.compile("\\bu_CurrentTime\\b").matcher(source).find() && !Pattern.compile("\\buniform\\s+int\\s+worldTime\\b").matcher(source).find()) {
         declarations = declarations + "uniform int worldTime;\n";
      }

      return declarations + bridge;
   }
}
