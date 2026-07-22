package net.irisshaders.iris.mixin;

import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.shaders.ShaderType;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.ObjCubedShaderInjector;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Adds obj-cubed geometry to Minecraft's native block destroy-stage shader. */
@Mixin(ShaderManager.class)
public abstract class MixinShaderManagerObjCubed {
	private static final String VANILLA_CRUMBLING = "shaders/core/rendertype_crumbling.vsh";
	private static final Identifier TOOLS = Identifier.fromNamespaceAndPath("minecraft", "shaders/include/objmc_tools.glsl");
	private static final Identifier MAIN = Identifier.fromNamespaceAndPath("minecraft", "shaders/include/objmc_main.glsl");

	@ModifyExpressionValue(
		method = "loadShader",
		at = @At(value = "INVOKE", target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/Reader;)Ljava/lang/String;")
	)
	private static String iris$injectObjCubedNativeCrumbling(
		String source,
		Identifier fullPath,
		Resource shaderResource,
		ShaderType shaderType,
		Map<Identifier, Resource> resources,
		ImmutableMap.Builder<?, String> output
	) {
		if (shaderType != ShaderType.VERTEX
			|| !"minecraft".equals(fullPath.getNamespace())
			|| !VANILLA_CRUMBLING.equals(fullPath.getPath())) {
			return source;
		}

		String tools = iris$read(resources.get(TOOLS));
		String main = iris$read(resources.get(MAIN));
		if (tools == null || main == null) return source;
		return ObjCubedShaderInjector.injectNativeCrumblingWithSources(source, tools, main);
	}

	private static String iris$read(Resource resource) {
		if (resource == null) return null;
		try (var input = resource.open()) {
			return IOUtils.toString(input, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Iris.logger.warn("Failed to read obj-cubed include during native shader reload", e);
			return null;
		}
	}
}
