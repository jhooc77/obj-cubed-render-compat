# Iris Vulkan architecture for Minecraft 26.2

## Decision

The Vulkan implementation is an Iris-owned shaderpack runtime built on top of
Minecraft 26.2's backend-neutral GPU API. It reuses Iris' shaderpack discovery,
include processing, option preprocessing, `ShaderPack`, `ProgramSet`,
`ProgramSource`, directives and material maps, but it does not call Iris'
OpenGL runtime.

This is the renderer-side subsystem option from the design document, hosted in
the Iris fork so the existing configuration and UI can still be reused.

## Why the existing Iris runtime is not the backend boundary

The 26.2 source tree contains 682 Java files under `common/src/main/java`.
116 of them directly import either LWJGL OpenGL or Mojang OpenGL classes, with
304 direct OpenGL import lines. There are additionally 195
`IrisRenderSystem` calls, 291 `GlStateManager` calls and roughly 1,100 direct
OpenGL constant references.

`IrisRenderingPipeline`, framebuffer management, shader programs, samplers,
uniforms, composite rendering and shadow rendering all exchange OpenGL object
ids or mutate the OpenGL state machine. Retrofitting those classes behind an
interface would amount to reproducing OpenGL semantics and would retain a
large, difficult-to-audit Vulkan path.

## Minecraft 26.2 facilities to reuse

Minecraft already owns and exposes the required Vulkan device lifecycle:

- `RenderSystem.getDevice()` returns the active `GpuDevice`.
- `GpuDevice.precompilePipeline(RenderPipeline, ShaderSource)` accepts dynamic
  shader source and compiles it through the active backend.
- `VulkanDevice` owns Minecraft's instance, physical/logical device, queues,
  VMA allocator, command encoder and pipeline cache.
- `GlslCompiler` uses shaderc to compile GLSL to SPIR-V, auto-assigns bindings
  and locations, reflects SPIR-V, and rebinds vertex inputs, varyings, uniform
  buffers and samplers to the pipeline layout.
- `RenderPipeline` describes vertex formats, topology, culling, depth state,
  color formats/write masks and blending.
- `RenderPassDescriptor` and `RenderPass` expose color/depth attachments,
  named uniform buffers, named sampled textures, vertex/index buffers and draw
  calls.
- `GpuTexture`, `GpuTextureView`, `GpuSampler`, `GpuBuffer` and
  `CommandEncoder` are implemented by both Mojang backends and require no
  second Vulkan instance or device.

No Zink device, OpenGL context, private swapchain or replacement allocator is
created.

## Runtime structure

```text
Iris configuration and shaderpack directory
  -> Iris ShaderPack / ProgramSet parser
  -> VulkanShaderTransformer
  -> Minecraft ShaderSource
  -> GpuDevice.precompilePipeline
  -> Mojang shaderc + SPIR-V reflection
  -> mapped RenderPipeline
  -> existing Minecraft RenderPass and draw buffers
```

The Vulkan-only mixins are deliberately separate from the OpenGL mixins. The
existing mixin plugin already applies only classes whose names contain
`VKOnly` when `preferredGraphicsBackend` is Vulkan. This preserves upstream
OpenGL behavior and makes accidental GL calls in Vulkan mode easy to audit.

## Pipeline replacement

Minecraft draw submission continues to produce its normal `RenderPipeline`,
index buffers and texture bindings. World immediate-mode buffers use Iris'
existing extended entity/terrain/glyph layouts so material ids, midpoint UVs
and tangents remain per vertex; paths outside level rendering keep their
vanilla layouts. The Vulkan runtime maps an
eligible vanilla/Sodium pipeline to an Iris-generated `RenderPipeline` with:

- the matching vanilla or extended vertex binding and original primitive
  topology;
- compatible depth, cull, polygon, blend and write-mask state;
- shaderpack-derived vertex and fragment sources;
- original bind-group layouts plus Iris frame/pass/draw uniform layouts;
- shaderpack color-target formats once the gbuffer target manager is active.

The replacement is precompiled with a custom `ShaderSource` before it can be
submitted. This is required because Mojang's backend pipeline cache otherwise
looks shader ids up only in the resource-pack `ShaderManager`.

Sodium 0.9.x does not submit terrain through the vanilla chunk draw wrapper.
Its `DefaultChunkRenderer` render passes are intercepted directly, with
separate Iris pipelines transformed from Sodium's compact vertex decoder.
The transformer restores Sodium's region offset, section time and region id as
the same 20-byte Vulkan push-constant block consumed by its draw context;
turning those fields into ordinary uniforms would break every chunk draw.

For shadows, Iris enters Sodium's shadow render-list scope, runs terrain setup
with the shadow frustum, and attaches the resulting renderer and shadow camera
matrices to `ChunkSectionsToRender`. Opaque and translucent shadow terrain
therefore use the same chunk ownership protocol as upstream Iris without any
OpenGL calls.

## Resource binding

The first terrain milestone reuses the existing terrain vertex buffers,
`Sampler0`, `Sampler2`, `Globals`, `Projection`, `Fog` and `ChunkSection`
bindings. The compatibility transformer maps shaderpack terrain inputs and the
minimum standard uniforms to those resources.

Obj-cubed terrain is decoded after Sodium expands its compact vertex fields and
before the shaderpack reads them. Its source UV uses Sodium's complete
`coord + cornerBias * texelShrink` reconstruction; using the packed coordinate
alone can move integer marker/header fetches into the adjacent atlas texel.

Additional Iris values are packed into one reflected
`IrisShaderpackUniforms` std140 block per program rather than emulating
individual `glUniform*` calls. Iris' fixed/custom uniform graph fills the
backend-neutral members first. The Vulkan draw context then supplies values
that the OpenGL path normally uploads directly: inverse draw matrices, the
normal and lightmap matrices, alpha-test reference, entity ids, render stage
and internal fog aliases.

The gbuffer target manager later adds `colortex`, `depthtex`, `shadowtex` and
custom texture views to render passes and binds their sampled views by name.
Iris' existing `CommonUniforms` and custom-uniform dependency graph provide
the frame/world values. Vulkan only captures data that is otherwise supplied
by OpenGL render state (for example fog ranges), owns the shadow matrices and
render-stage values, applies per-pipeline alpha-test values, and runs the
one-pixel `iris_centerDepthSmooth` temporal pass before hand-depth staging.

Reflection results are validated before a pipeline is built. Only uniform
blocks and texel buffers supplied by the selected Minecraft draw path, the
Iris std140 block, and supported 2D sampler types are accepted. This prevents
an apparently successful compile from producing an unbound Vulkan descriptor
at draw time.

## Pass order

1. shadow
2. shadow composite
3. gbuffer terrain/entities/block entities/particles/weather/hand/sky
4. deferred fullscreen passes
5. composite fullscreen passes
6. final pass into Minecraft's main color target

Image layout transitions and synchronization remain the responsibility of
Minecraft's command encoder and render-pass implementation. Direct Vulkan
commands are allowed only where the public 26.2 GPU API has no equivalent and
must use the existing `VulkanDevice` and command encoder.

Forward-Z conversion is scoped to the level, hand and shadow frame graph. GUI
item-atlas and player-preview offscreen passes run after `renderLevel` and keep
Minecraft's native reverse-Z projection, compare operation and depth clear.
This prevents a creative-inventory atlas refresh from caching punched-out or
empty item thumbnails and preserves the inventory player preview.

The shadow render state reuses Iris' `ShadowMatrices` for both projection and
model-view construction. This keeps celestial rotation, interval snapping and
the backend's zero-to-one depth convention identical to the OpenGL Iris path
instead of duplicating those compatibility formulas in the Vulkan runtime.

## Compatibility policy

The first supported feature set is vertex+fragment shaderpacks without
geometry, tessellation, compute, SSBO or storage-image requirements. Missing
features are reported with the program and stage; they are never silently
ignored. The backend is extended capability by capability after the complete
vertex+fragment render graph is working.

Subgroup support remains available through Vulkan GLSL/SPIR-V. Capability
macros must come from Vulkan device properties, not OpenGL extension strings.
The runtime queries `VkPhysicalDeviceSubgroupProperties` from Minecraft's
already-created physical device; it does not create another Vulkan instance or
device. Obj-cubed's vertex decoder requires subgroup basic plus either quad or
shuffle, at least four lanes, and a subgroup size divisible by four. It keeps
native quad broadcast on quad-capable devices and lowers it to an
invocation-relative shuffle only when quad is absent and shuffle is available.
