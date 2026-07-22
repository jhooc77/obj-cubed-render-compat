package io.github.jhooc77.objcubedcompat.mixin;

import io.github.jhooc77.objcubedcompat.shader.ObjCubedShaderInjector;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.Map;

@Mixin(value = TransformPatcher.class, remap = false)
public abstract class TransformPatcherMixin {
    @Inject(method = "transform", at = @At("RETURN"), cancellable = true)
    private static void objCubed$injectVanilla(
        String name,
        String vertex,
        String geometry,
        String tessControl,
        String tessEval,
        String fragment,
        Parameters parameters,
        CallbackInfoReturnable<Map<PatchShaderType, String>> cir
    ) {
        if (!(parameters instanceof VanillaParameters vanilla)) return;

        Map<PatchShaderType, String> transformed = cir.getReturnValue();
        if (isTerrainProgram(name)) {
            // The full Iris fork owns the extended destroy-stage vertex format.
            // A sidecar must not add UV1 to upstream Iris's incompatible terrain
            // format, or ordinary block-breaking can fail shader compilation.
            if (!"crumbling".equalsIgnoreCase(name)) {
                cir.setReturnValue(ObjCubedShaderInjector.injectTerrain(transformed, name));
            }
        } else {
            cir.setReturnValue(ObjCubedShaderInjector.inject(transformed, vanilla, name));
        }
    }

    @Inject(method = "patchSodium", at = @At("RETURN"), cancellable = true)
    private static void objCubed$injectSodiumTerrain(
        String name,
        String vertex,
        String geometry,
        String tessControl,
        String tessEval,
        String fragment,
        AlphaTest alpha,
        Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
        CallbackInfoReturnable<Map<PatchShaderType, String>> cir
    ) {
        cir.setReturnValue(ObjCubedShaderInjector.injectSodiumTerrain(cir.getReturnValue(), name));
    }

    private static boolean isTerrainProgram(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("terrain_")
            || n.equals("moving_block")
            || n.equals("crumbling")
            || n.startsWith("shadow_terrain")
            || n.equals("shadow_translucent");
    }
}
