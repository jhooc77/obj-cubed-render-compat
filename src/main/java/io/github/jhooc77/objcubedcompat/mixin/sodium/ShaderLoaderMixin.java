package io.github.jhooc77.objcubedcompat.mixin.sodium;

import io.github.jhooc77.objcubedcompat.shader.ObjCubedShaderInjector;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void objCubed$injectNativeTerrain(
        Identifier id,
        CallbackInfoReturnable<String> cir
    ) {
        if ("sodium".equals(id.getNamespace()) && "blocks/block_layer_opaque.vsh".equals(id.getPath())) {
            cir.setReturnValue(ObjCubedShaderInjector.injectRawSodiumTerrain(cir.getReturnValue()));
        }
    }
}
