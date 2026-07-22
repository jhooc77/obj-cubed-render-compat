package net.irisshaders.iris.mixin.vertices;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import net.irisshaders.iris.vertices.BufferBuilderPolygonView;
import net.irisshaders.iris.vertices.ExtendedDataHelper;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.MojangBufferAccessor;
import net.irisshaders.iris.vertices.NormI8;
import net.irisshaders.iris.vertices.NormalHelper;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BufferBuilder.class)
public abstract class MixinBufferBuilder implements VertexConsumer, BlockSensitiveBufferBuilder {
   @Unique
   private static final int IRIS$UNKNOWN_OFFSET = -1;
   @Unique
   private static final int IRIS$NORMAL_SEMANTIC_ID = 5;
   @Unique
   private static final int IRIS$NORMAL_MASK = 32;
   @Unique
   private final BufferBuilderPolygonView polygon = new BufferBuilderPolygonView();
   @Unique
   private final Vector3f normal = new Vector3f();
   @Unique
   private final long[] vertexOffsets = new long[4];
   @Shadow
   private int elementsToFill;
   @Unique
   private boolean skipEndVertexOnce;
   @Shadow
   @Final
   private PrimitiveTopology primitiveTopology;
   @Shadow
   @Final
   private VertexFormat format;
   @Shadow
   private long vertexPointer;
   @Shadow
   private int vertices;
   @Unique
   private boolean extending;
   @Unique
   private boolean injectNormalAndUV1;
   @Unique
   private int iris$vertexCount;
   @Unique
   private int currentBlock = -1;
   @Unique
   private byte currentRenderType = -1;
   @Unique
   private byte currentBlockEmission = -1;
   @Unique
   private int currentLocalPosX;
   @Unique
   private int currentLocalPosY;
   @Unique
   private int currentLocalPosZ;
   @Unique
   private int positionOffset = -1;
   @Unique
   private int uvOffset = -1;
   @Unique
   private int normalOffset = -1;
   @Unique
   private int midTexOffset = -1;
   @Unique
   private int tangentOffset = -1;
   @Unique
   private int midBlockOffset = -1;
   @Unique
   private int entityOffset = -1;
   @Unique
   private int entityIdOffset = -1;
   @Shadow
   @Final
   private ByteBufferBuilder buffer;

   @Shadow
   public abstract VertexConsumer setNormal(float var1, float var2, float var3);

   @ModifyVariable(
      method = "<init>",
      at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexFormat;contains(Ljava/lang/String;)Z"),
      argsOnly = true
   )
   private VertexFormat iris$extendFormat(VertexFormat format) {
      this.injectNormalAndUV1 = false;
      if (ImmediateState.skipExtension.get() || !ImmediateState.isRenderingLevel || !Iris.isPackInUseQuick()) {
         return format;
      }

      if (format.equals(IrisVertexFormats.CRUMBLING)) {
         this.extending = true;
         return IrisVertexFormats.CRUMBLING;
      }

      if (format.equals(DefaultVertexFormat.BLOCK) || format.equals(IrisVertexFormats.TERRAIN)) {
         this.extending = true;
         return IrisVertexFormats.TERRAIN;
      }

      if (format.equals(DefaultVertexFormat.ENTITY) || format.equals(IrisVertexFormats.ENTITY)) {
         this.extending = true;
         return IrisVertexFormats.ENTITY;
      }

      if (!format.equals(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR) && !format.equals(IrisVertexFormats.GLYPH)) {
         return format;
      }

      this.extending = true;
      this.injectNormalAndUV1 = true;
      return IrisVertexFormats.GLYPH;
   }

   @Inject(method = "<init>", at = @At("TAIL"))
   private void iris$cacheOffsets(ByteBufferBuilder buffer, PrimitiveTopology primitiveTopology, VertexFormat format, CallbackInfo ci) {
      if (this.extending) {
         this.positionOffset = IrisVertexFormats.getOffset(this.format, "Position");
         this.uvOffset = IrisVertexFormats.getOffset(this.format, "UV0");
         this.normalOffset = IrisVertexFormats.getOffset(this.format, "Normal");
         this.midTexOffset = IrisVertexFormats.getOffset(this.format, "mc_midTexCoord");
         this.tangentOffset = IrisVertexFormats.getOffset(this.format, "at_tangent");
         this.midBlockOffset = IrisVertexFormats.getOffset(this.format, "at_midBlock");
         this.entityOffset = IrisVertexFormats.getOffset(this.format, "mc_Entity");
         this.entityIdOffset = IrisVertexFormats.getOffset(this.format, "iris_Entity");
      }
   }

   @Inject(method = "addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("RETURN"))
   private void iris$fillPerVertexData(float x, float y, float z, CallbackInfoReturnable<VertexConsumer> cir) {
      if (this.extending && this.vertexPointer != -1L) {
         if (this.midBlockOffset != -1) {
            long offset = this.vertexPointer + this.midBlockOffset;
            MemoryUtil.memPutInt(offset, ExtendedDataHelper.computeMidBlock(x, y, z, this.currentLocalPosX, this.currentLocalPosY, this.currentLocalPosZ));
            MemoryUtil.memPutByte(offset + 3L, this.currentBlockEmission);
         }

         if (this.entityOffset != -1) {
            long offset = this.vertexPointer + this.entityOffset;
            MemoryUtil.memPutShort(offset, (short)this.currentBlock);
            MemoryUtil.memPutShort(offset + 2L, this.currentRenderType);
         }

         if (this.entityIdOffset != -1) {
            long offset = this.vertexPointer + this.entityIdOffset;
            MemoryUtil.memPutShort(offset, (short)CapturedRenderingState.INSTANCE.getCurrentRenderedEntity());
            MemoryUtil.memPutShort(offset + 2L, (short)CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity());
            MemoryUtil.memPutShort(offset + 4L, (short)CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
            MemoryUtil.memPutShort(offset + 6L, (short)0);
         }
      }
   }

   @Inject(method = "push", at = @At("TAIL"), remap = false, require = 0)
   @Dynamic("Used to skip endLastVertex if the last push was made by Sodium")
   private void iris$skipSodiumChange(CallbackInfo ci) {
      this.skipEndVertexOnce = true;
   }

   @Inject(method = "endLastVertex", at = @At("HEAD"))
   private void iris$beforeNext(CallbackInfo ci) {
      if (this.vertices != 0 && this.extending) {
         if (this.injectNormalAndUV1 && (this.elementsToFill & 32) != 0) {
            this.setNormal(0.0F, 1.0F, 0.0F);
         }

         if (this.skipEndVertexOnce) {
            this.skipEndVertexOnce = false;
         } else {
            int vertexAmount = this.iris$vertexAmountForExtendedData();
            if (vertexAmount != 0) {
               this.vertexOffsets[this.iris$vertexCount] = this.vertexPointer - ((MojangBufferAccessor)this.buffer).getPointer();
               this.iris$vertexCount++;
               if (this.iris$vertexCount == vertexAmount) {
                  this.fillExtendedData(vertexAmount);
               }
            }
         }
      }
   }

   @Override
   public void beginBlock(int block, byte renderType, byte blockEmission, int localPosX, int localPosY, int localPosZ) {
      this.currentBlock = block;
      this.currentRenderType = renderType;
      this.currentBlockEmission = blockEmission;
      this.currentLocalPosX = localPosX;
      this.currentLocalPosY = localPosY;
      this.currentLocalPosZ = localPosZ;
   }

   @Override
   public void endBlock() {
      this.currentBlock = -1;
      this.currentRenderType = -1;
      this.currentBlockEmission = -1;
      this.currentLocalPosX = 0;
      this.currentLocalPosY = 0;
      this.currentLocalPosZ = 0;
   }

   @Unique
   private int iris$vertexAmountForExtendedData() {
      if (this.primitiveTopology == PrimitiveTopology.QUADS) {
         return 4;
      } else {
         return this.primitiveTopology == PrimitiveTopology.TRIANGLES ? 3 : 0;
      }
   }

   @Unique
   private void fillExtendedData(int vertexAmount) {
      this.iris$vertexCount = 0;
      if (this.positionOffset != -1 && this.uvOffset != -1 && this.midTexOffset != -1 && this.normalOffset != -1 && this.tangentOffset != -1) {
         long basePointer = ((MojangBufferAccessor)this.buffer).getPointer();
         this.polygon.setup(basePointer, this.vertexOffsets, this.positionOffset, this.uvOffset);
         float midU = 0.0F;
         float midV = 0.0F;

         for (int vertex = 0; vertex < vertexAmount; vertex++) {
            midU += this.polygon.u(vertex);
            midV += this.polygon.v(vertex);
         }

         midU /= vertexAmount;
         midV /= vertexAmount;
         if (vertexAmount == 3) {
            for (int vertex = 0; vertex < vertexAmount; vertex++) {
               long newPointer = basePointer + this.vertexOffsets[vertex];
               int vertexNormal = MemoryUtil.memGetInt(newPointer + this.normalOffset);
               int tangent = NormalHelper.computeTangentSmooth(
                  NormI8.unpackX(vertexNormal), NormI8.unpackY(vertexNormal), NormI8.unpackZ(vertexNormal), this.polygon
               );
               MemoryUtil.memPutFloat(newPointer + this.midTexOffset, midU);
               MemoryUtil.memPutFloat(newPointer + this.midTexOffset + 4L, midV);
               MemoryUtil.memPutInt(newPointer + this.tangentOffset, tangent);
            }
         } else {
            boolean recalculateNormal = ImmediateState.isRenderingLevel;
            NormalHelper.computeFaceNormal(this.normal, this.polygon);
            int packedNormal = 0;
            if (recalculateNormal) {
               packedNormal = NormI8.pack(this.normal.x, this.normal.y, this.normal.z, 0.0F);
            }

            int tangent = NormalHelper.computeTangent(this.normal.x, this.normal.y, this.normal.z, this.polygon);

            for (int vertex = 0; vertex < vertexAmount; vertex++) {
               long newPointer = basePointer + this.vertexOffsets[vertex];
               MemoryUtil.memPutFloat(newPointer + this.midTexOffset, midU);
               MemoryUtil.memPutFloat(newPointer + this.midTexOffset + 4L, midV);
               if (recalculateNormal) {
                  MemoryUtil.memPutInt(newPointer + this.normalOffset, packedNormal);
               }

               MemoryUtil.memPutInt(newPointer + this.tangentOffset, tangent);
            }
         }

         Arrays.fill(this.vertexOffsets, 0L);
      } else {
         Arrays.fill(this.vertexOffsets, 0L);
      }
   }
}
