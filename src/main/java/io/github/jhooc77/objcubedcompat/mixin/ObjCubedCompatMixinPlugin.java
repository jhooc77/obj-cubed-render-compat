package io.github.jhooc77.objcubedcompat.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Selects only the renderer hooks that exist in the running Minecraft/Sodium
 * generation. Common Iris hooks remain active on every supported version.
 */
public final class ObjCubedCompatMixinPlugin implements IMixinConfigPlugin {
    private boolean minecraft26_2;

    @Override
    public void onLoad(String mixinPackage) {
        String version = FabricLoader.getInstance()
            .getModContainer("minecraft")
            .orElseThrow(() -> new IllegalStateException("Minecraft mod metadata is unavailable"))
            .getMetadata()
            .getVersion()
            .getFriendlyString();
        minecraft26_2 = isMinecraft26_2Version(version);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".minecraft26_2.ShaderManagerMixin")) {
            return minecraft26_2;
        }
        if (mixinClassName.endsWith(".ShadowRendererMixin")
            || mixinClassName.endsWith(".sodium.ShaderLoaderMixin")) {
            return !minecraft26_2;
        }
        return true;
    }

    public static boolean isMinecraft26_2Version(String version) {
        return "26.2".equals(version) || version.startsWith("26.2.");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
