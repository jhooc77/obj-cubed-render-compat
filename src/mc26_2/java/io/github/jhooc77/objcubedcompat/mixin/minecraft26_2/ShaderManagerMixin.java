package io.github.jhooc77.objcubedcompat.mixin.minecraft26_2;

import com.google.common.collect.ImmutableMap.Builder;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.shaders.ShaderType;
import io.github.jhooc77.objcubedcompat.shader.ObjCubedShaderInjector;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    private static final Identifier TOOLS =
        Identifier.fromNamespaceAndPath("minecraft", "shaders/include/objmc_tools.glsl");
    private static final Identifier MAIN =
        Identifier.fromNamespaceAndPath("minecraft", "shaders/include/objmc_main.glsl");

    @ModifyExpressionValue(
        method = "loadShader",
        at = @At(
            value = "INVOKE",
            target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/Reader;)Ljava/lang/String;"
        )
    )
    private static String objCubed$injectNativeTerrain(
        String source,
        Identifier fullPath,
        Resource shaderResource,
        ShaderType shaderType,
        Map<Identifier, Resource> resources,
        Builder<?, String> output
    ) {
        if (shaderType != ShaderType.VERTEX
            || !"sodium".equals(fullPath.getNamespace())
            || !"shaders/blocks/block_layer_opaque.vsh".equals(fullPath.getPath())) {
            return source;
        }

        String tools = objCubed$read(resources.get(TOOLS));
        String main = objCubed$read(resources.get(MAIN));
        return tools == null || main == null
            ? source
            : ObjCubedShaderInjector.injectRawSodiumTerrainWithSources(source, tools, main);
    }

    private static String objCubed$read(Resource resource) {
        if (resource == null) {
            return null;
        }

        try (InputStream input = resource.open()) {
            return IOUtils.toString(input, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }
}
