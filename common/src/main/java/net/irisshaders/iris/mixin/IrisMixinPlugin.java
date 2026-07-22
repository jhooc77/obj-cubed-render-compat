package net.irisshaders.iris.mixin;

import com.google.common.base.Splitter;
import com.google.common.io.Files;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public class IrisMixinPlugin implements IMixinConfigPlugin {
   private static final Splitter OPTION_SPLITTER = Splitter.on(':').limit(2);
   private static final Set<String> VULKAN_SHARED_MIXINS = Set.of(
      "net.irisshaders.iris.mixin.GameRendererAccessor",
      "net.irisshaders.iris.mixin.GpuDeviceAccessor",
      "net.irisshaders.iris.mixin.MixinBiomeAmbientSoundsHandler",
      "net.irisshaders.iris.mixin.MixinByteBufferBuilder",
      "net.irisshaders.iris.mixin.MixinPreparedRenderType",
      "net.irisshaders.iris.mixin.MixinGameRenderer_NightVisionCompat",
      "net.irisshaders.iris.mixin.MixinItem",
      "net.irisshaders.iris.mixin.MixinItemInHandRenderer",
      "net.irisshaders.iris.mixin.MixinLightTexture",
      "net.irisshaders.iris.mixin.MixinFogRenderer",
      "net.irisshaders.iris.mixin.MixinLocalPlayer",
      "net.irisshaders.iris.mixin.MixinModelViewBobbing",
      "net.irisshaders.iris.mixin.MixinRenderPipeline",
      "net.irisshaders.iris.mixin.vertices.MixinBufferBuilder",
      "net.irisshaders.iris.mixin.vertices.immediate.MixinBufferSource",
      "net.irisshaders.iris.mixin.vertices.immediate.MixinLevelRenderer",
      "net.irisshaders.iris.mixin.vertices.immediate.MixinRenderType"
   );
   private static final Set<String> VULKAN_SHARED_SODIUM_MIXINS = Set.of(
      "net.irisshaders.iris.compat.sodium.mixin.MixinAbstractBlockRenderContext",
      "net.irisshaders.iris.compat.sodium.mixin.MixinBlockRenderer",
      "net.irisshaders.iris.compat.sodium.mixin.MixinChunkVertexConsumer",
      "net.irisshaders.iris.compat.sodium.mixin.MixinChunkMeshBuildTask",
      "net.irisshaders.iris.compat.sodium.mixin.MixinChunkVertex",
      "net.irisshaders.iris.compat.sodium.mixin.MixinDefaultFluidRenderer",
      "net.irisshaders.iris.compat.sodium.mixin.MixinChunkMeshFormats",
      "net.irisshaders.iris.compat.sodium.mixin.MixinRenderSectionManager",
      "net.irisshaders.iris.compat.sodium.mixin.MixinRenderRegion",
      "net.irisshaders.iris.compat.sodium.mixin.MixinRenderRegionManager",
      "net.irisshaders.iris.compat.sodium.mixin.MixinRenderSectionManagerShadow",
      "net.irisshaders.iris.compat.sodium.mixin.MixinUniformData",
      "net.irisshaders.iris.compat.sodium.mixin.MixinSodiumWorldRenderer",
      "net.irisshaders.iris.compat.sodium.mixin.MixinDefaultChunkRenderer",
      "net.irisshaders.iris.compat.sodium.mixin.MixinShaderChunkRenderer"
   );
   private static final String VULKAN_SHARED_ENTITY_CONTEXT = "net.irisshaders.iris.mixin.entity_render_context.";
   public static boolean usingVulkan;

   public void onLoad(String mixinPackage) {
   }

   public String getRefMapperConfig() {
      return "iris.refmap.json";
   }

   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      if (mixinClassName.contains("VKOnly")) {
         return usingVulkan;
      } else {
         return !usingVulkan
               || !VULKAN_SHARED_MIXINS.contains(mixinClassName)
                  && !VULKAN_SHARED_SODIUM_MIXINS.contains(mixinClassName)
                  && !mixinClassName.startsWith("net.irisshaders.iris.mixin.entity_render_context.")
            ? !usingVulkan
            : true;
      }
   }

   public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
   }

   public List<String> getMixins() {
      return List.of();
   }

   public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }

   public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }

   static {
      BufferedReader reader = null;
      boolean check = true;

      try {
         reader = Files.newReader(IrisPlatformHelpers.getInstance().getGameDir().resolve("options.txt").toFile(), StandardCharsets.UTF_8);
      } catch (FileNotFoundException e) {
         usingVulkan = false;
         check = false;
      }

      if (check) {
         Map<String, String> options = new HashMap<>();

         try {
            reader.lines().forEach(line -> {
               try {
                  Iterator<String> iterator = OPTION_SPLITTER.split(line).iterator();
                  options.put(iterator.next(), iterator.next());
               } catch (Exception var3) {
               }
            });
         } catch (Throwable var6) {
            if (reader != null) {
               try {
                  reader.close();
               } catch (Throwable var5) {
                  var6.addSuppressed(var5);
               }
            }

            throw var6;
         }

         if (options.get("preferredGraphicsBackend") != null) {
            usingVulkan = options.get("preferredGraphicsBackend").toLowerCase(Locale.ROOT).contains("vulkan");
         } else {
            usingVulkan = false;
         }
      }
   }
}
