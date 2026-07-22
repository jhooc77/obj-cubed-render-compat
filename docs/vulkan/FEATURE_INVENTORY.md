# Minecraft 26.2 Vulkan feature inventory

## Confirmed usable through the Mojang GPU API

| Requirement | 26.2 facility | Initial status |
|---|---|---|
| Dynamic vertex/fragment GLSL | `GpuDevice.precompilePipeline(..., ShaderSource)` | available |
| GLSL to SPIR-V | Vulkan `GlslCompiler` using shaderc | available |
| SPIR-V reflection | `IntermediaryShaderModule` | available |
| Explicit/automatic locations | shaderc auto-map plus SPIR-V rebind | available |
| Uniform buffers | named `BindGroupLayout` uniforms | available |
| 2D/cube samplers | named bind-group samplers | available |
| Vertex/index/indirect draw | `RenderPass` | available |
| Multiple color attachments | `RenderPassDescriptor` | available, device-limited |
| Per-target format/write mask | `ColorTargetState` | available |
| Depth compare/write/bias | `DepthStencilState` | available |
| Blending | `ColorTargetState`/`BlendFunction` | available with limitation below |
| Texture creation/views/mips | `GpuDevice` texture APIs | implemented for colortex and shadow targets |
| Buffer/texture copies and clears | `CommandEncoder` | available |
| Runtime resize/recreation | texture/view APIs | available |
| GPU labels/checkpoints | render-pass debug groups and Vulkan checkpoints | available |

## Mojang API limitations that affect shaderpacks

- Shader stages exposed by `ShaderType`/`RenderPipeline` are vertex and
  fragment only.
- Geometry, tessellation and compute stages have no public pipeline builder.
- SSBO and storage-image descriptors are not exposed by `BindGroupLayout`.
- Vulkan reflection currently accepts uniform buffers, sampled 2D/cube images
  and texel buffers.
- A `RenderPipeline` supports at most eight color-target slots.
- Per-target blend functions are represented by individual
  `ColorTargetState` entries and are translated from Iris' buffer overrides.
- At most 16 vertex attributes can be bound.
- Loose OptiFine uniforms must be converted to std140 uniform blocks.
- Legacy GLSL attributes/varyings, `gl_FragData`, fixed-function built-ins and
  OpenGL coordinate assumptions require transformation before shaderc.

## Existing Iris OpenGL surface

The initial static inventory found:

- 116 Java files with direct OpenGL imports;
- 304 direct OpenGL import lines;
- 195 `IrisRenderSystem` calls;
- 291 `GlStateManager` calls;
- approximately 1,100 direct OpenGL constant references.

The major OpenGL-owned subsystems are:

- framebuffer/render-target creation and attachment routing;
- shader compilation/linking and program-uniform updates;
- sampler and texture-unit state;
- blend/depth/color-mask overrides;
- composite/final fullscreen rendering;
- shadow framebuffer and shadow draw lifecycle;
- custom image, SSBO and compute dispatch;
- GL capability and extension macro generation;
- PBR texture tracking and GL texture object access.

## Current Vulkan implementation status

- Terrain, immediate entity/item/equipment, hand, sky, cloud, weather and
  particle vertex/fragment programs are compiled through Minecraft's Vulkan
  device.
- Sodium 0.9.x terrain draws are intercepted at its `DefaultChunkRenderer`
  rather than the vanilla chunk wrapper. Solid, cutout, translucent and shadow
  draws retain Sodium's compact vertex buffers and fixed 20-byte region push
  constant ABI while using Iris-generated shaderpack pipelines.
- The Vulkan pipeline is initialized after Sodium's first renderer exists, so
  changing between compact and Iris-extended chunk formats reloads Sodium's
  `RenderSectionManager`/`ChunkBuilder`, not only the already-built chunks.
  Failed or disabled shaderpacks symmetrically restore the compact format.
- Backend-neutral `Iris.isPackInUseQuick()` reflects successful Vulkan runtime
  ownership. Immediate entity/equipment buffers therefore enable extended
  per-vertex data only after Vulkan pipeline construction succeeds, and cleanly
  fall back to vanilla formats after a rejected pack.
- Shader-pack colortex/depth targets, ping-pong flips, custom textures,
  shadow targets, deferred/composite/final passes and requested mip chains are
  owned by the Vulkan runtime.
- Iris' backend-neutral common/custom uniforms remain authoritative; Vulkan
  supplies only backend-owned matrices, render-stage values and captured fog
  state instead of overwriting them with approximations.
- Iris-injected draw uniforms now have explicit Vulkan providers: the current
  model-view/projection inverses, std140 `mat3` normal matrix, lightmap texture
  matrix, per-pipeline alpha-test reference, entity ids and the internal fog
  aliases. Shadow pipelines select the shadow matrices rather than reusing the
  main-camera values.
- World entity, item, equipment and glyph buffers reuse Iris' extended
  immediate vertex formats under Vulkan. Entity/item/block-entity ids,
  midpoint UVs and tangents therefore remain per vertex when Minecraft batches
  multiple submissions into one draw; pipelines that intentionally keep a
  vanilla format use the draw-uniform/fallback path instead.
- `iris_centerDepthSmooth` is backed by a Vulkan 1x1 temporal depth pass, and
  shadow depth/color samplers retain their individual filtering directives and
  legacy `shadow`/`watershadow` aliases.
- The Vulkan shadow camera uses Iris' authoritative `ShadowMatrices` model-view
  and projection builders, including its quarter-cycle celestial adjustment,
  grid snapping and zero-to-one depth convention. It does not maintain a
  second approximation of the shadow transform.
- Vertex/fragment `shadowcomp` programs run against ping-ponged shadowcolor
  targets. Stage pre-flips, per-program flips, viewport scaling and colortex
  texture-size overrides are preserved by the Vulkan target managers.
- Loose uniforms use one std140 block instance whose references are qualified
  in each shader stage. This avoids cross-stage global-name collisions and
  permits global initializers to read uniforms legally in Vulkan GLSL.
- Entity depth is copied only for programs that actually sample a depth target,
  and loose-uniform uploads reuse persistent staging memory.
- Resource-pack reloads and dimension changes clear Mojang's native Vulkan
  pipeline/shader caches at a graphics-queue idle boundary before recompiling.
  Stable Iris shader ids therefore cannot reuse stale SPIR-V, and superseded
  pipelines are released instead of accumulating across reloads.
- OptiFine PBR normal/specular maps are not advertised in Vulkan mode until
  backend-neutral atlas views are available; packs therefore use their normal
  non-PBR path instead of sampling an invalid white placeholder.
- Compute, SSBO, storage-image, geometry and tessellation requirements remain
  outside the supported vertex/fragment profile and must be reported as such.
- Reflected uniform blocks, texel buffers and sampler dimensionalities are
  checked against the resources each Minecraft draw path can actually bind;
  unsupported resources fail pipeline creation with the program and resource
  names instead of reaching a missing-descriptor draw.
- Vertex subgroup size, stages and operation bits are queried from Minecraft's
  existing Vulkan physical device. The obj-cubed decoder requires basic plus
  either native quad or shuffle support, a subgroup size of at least four, and
  a subgroup size divisible by four. Native quad broadcast is preferred on
  quad-only mobile drivers; shuffle is retained as the fallback. Shaderpack
  capability macros advertise only the operations reported by that device.
- Sodium shadow rendering uses Iris' shadow render-list scope and attaches the
  shadow camera matrices to `ChunkSectionsToRender`; it therefore does not
  reuse or accidentally empty the main-camera chunk list.
- Shadow loose-uniform buffers are updated with their own solid, cutout or
  translucent `renderStage` value. Sodium's combined opaque group cannot
  accidentally stamp the solid stage into its cutout shadow program.

## Implementation gates

### Gate 1: compile

- load the configured Iris shaderpack in Vulkan mode;
- transform one terrain vertex/fragment pair;
- precompile it through the active `GpuDevice`;
- dump original, transformed and SPIR-V diagnostic artifacts;
- reject unsupported stages with a specific error.

### Gate 2: terrain draw

- replace solid/cutout/translucent terrain pipelines;
- retain vanilla/Sodium vertex and index buffers;
- bind atlas, lightmap, camera/projection/fog/chunk resources;
- render terrain into the main color/depth targets with no validation errors.

### Gate 3: gbuffer

- create and resize shaderpack color/depth targets;
- append the correct attachments to world passes;
- apply per-program draw-buffer routing and clear directives;
- expose targets as sampled textures.

### Gate 4: full vertex+fragment pack

- entity, block entity, particle, weather, hand, sky and cloud programs;
- shadow terrain/entity passes;
- deferred, composite and final fullscreen passes;
- reload, resize and dimension transitions.

The implementation covers the Gate 4 render graph. Runtime sign-off still
requires the device matrix below; this server workspace deliberately performs
compile/transform validation only.

The static pack smoke tests currently parse Complementary Reimagined r5.8.1
HIGH and MakeUp Ultra Fast medium. They compile 25 and 22 representative
terrain/entity/block-entity/hand/particle/weather/sky/cloud/fullscreen programs
respectively to SPIR-V intermediaries through Minecraft 26.2's shaderc path.
Each total includes Sodium solid, cutout, water and shadow terrain variants;
the tests require Sodium's region data to remain a push-constant block and its
global/section-time resources to remain bindable by the chunk draw path.
The tests also inventory every loose uniform used by those programs so newly
introduced Iris built-ins cannot remain hidden behind a successful shader
compile. The entity smoke additionally requires `iris_Entity` to survive as a
real extended vertex input rather than being demoted to a draw-wide uniform.
Vertex inputs/outputs and fragment inputs deliberately use shaderc's
auto-location mapping so matrix and array varyings consume the correct number
of locations; explicit fragment-output locations remain available for
DRAWBUFFERS remapping.

A second smoke test injects the live server resource pack's
`objmc_tools.glsl` and `objmc_main.glsl` into Complementary entity, vanilla
terrain, Sodium terrain, shadow-entity, vanilla shadow-terrain and Sodium
shadow-terrain programs. It compiles every pair twice: once with native
subgroup quad broadcast and once with the subgroup-shuffle fallback, through
the same Minecraft 26.2 shaderc path. The Sodium bridge decodes its compressed
position/UV/color first, applies ObjCubed, and writes the result back before the
shaderpack vertex main executes. The smoke also asserts that Sodium's inward
atlas-edge bias is retained for obj-cubed metadata fetches.

### Gate 5: extended Vulkan features

- storage images and SSBO descriptors;
- compute dispatch;
- optional geometry/tessellation stages;
- subgroup capability macros and required-subgroup-size handling.

## Verification matrix

Each completed gate requires automated compile/transform tests plus a Vulkan
runtime capture. Product completion additionally requires Windows NVIDIA,
Android Vulkan and Apple-Silicon Vulkan runs, resource/shader reload, resize,
dimension change, shadow/entity/translucency coverage, and comparison against
the same pack under OpenGL Iris.
