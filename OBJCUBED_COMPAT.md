# Iris 1.11.2 / Minecraft 26.2 obj-cubed compatibility fork

This branch is based on the official Iris `26.2` branch at commit
`20e226b14fd2c3ba192e16ae2c8af4a27987767c` (Iris 1.11.2, Minecraft 26.2,
Sodium 0.9.1). It remains an LGPL-3.0-only derivative of Iris.

## Supported beta target

- Minecraft 26.2
- Fabric Loader
- Sodium 0.9.1
- obj-cubed resource pack containing both
  `minecraft:shaders/include/objmc_tools.glsl` and
  `minecraft:shaders/include/objmc_main.glsl`
- Minecraft 26.2 OpenGL or native Vulkan; obj-cubed still requires subgroup
  basic plus quad or shuffle support

The artifact is compiled directly against the official Minecraft 26.2 and
Sodium 0.9.1 dependencies used by the upstream Iris 26.2 branch.

## What the patch does

When a shaderpack is active, Iris normally runs the pack's `gbuffers_entities`
program instead of Minecraft's resource-pack entity core shader. obj-cubed's
metadata carrier texture therefore reaches the shaderpack without being decoded.

For entity vertex formats only, this fork:

1. reads the active resource pack's obj-cubed includes at shader compile time;
2. decodes position, base UV and color into private compatibility values;
3. executes the obj-cubed decoder before the shaderpack vertex `main`;
4. passes decoded geometry and UVs into the original shaderpack program; and
5. binds the normal entity albedo texture to a private decoder sampler alias.

Normal shaderpack lighting and G-buffer output still run after the decode. Text,
particles, terrain, clouds and glint programs are not injected. If the obj-cubed
includes are absent, the fork behaves like upstream Iris.

The bridge preserves Iris' canonical `iris_Position`, `iris_UV0` and
`iris_Color` input names so Iris can bind the vertex format before linking.
Shaderpack reads go through selector functions: ordinary entities read the
canonical attributes directly, while marker-backed obj-cubed draws receive the
decoded values. This replaces the canary-2 mutable-global path that could make
all entity inputs disappear or become flat colors on some shaderpacks/drivers.

Canary 6 also injects the decoder into Iris' entity/equipment shadow program,
so obj-cubed geometry reaches the shaderpack shadow map instead of its carrier
quad. Other shadow variants remain untouched. The bridge forces world-view
classification during this pass because an orthographic shadow projection can
otherwise be mistaken for an inventory GUI projection by the resource-pack
decoder.

Canary 4 no longer asks the OpenGL compiler for
`GL_KHR_shader_subgroup_quad`. The resource-pack decoder's
`subgroupQuadBroadcast` calls are translated inside the Iris-injected source to
`subgroupShuffle` using the current subgroup invocation's four-lane-aligned
base. This supports Intel OpenGL drivers that expose basic+shuffle but not quad,
without reintroducing the `gl_VertexID`-based mapping that caused 26.2 Vulkan
display jitter. The live resource-pack shaders remain unchanged.

Canary 5 passes the shader program name directly into the compatibility bridge.
Iris transform-cache hits can skip the code that populates the mutable
`VanillaParameters.name` field; canary 4 dereferenced that nullable field while
excluding shadow programs and failed before shader compilation.

The companion obj-cubed resource-pack canary restores
`subgroupQuadBroadcast` after an explicit-shuffle experiment caused Intel Vulkan
display entities to jitter. Equipment face/corner selection uses the fixed
humanoid UV layout instead of relying on non-local `gl_VertexID` values in
Sodium shared vertex arenas.

Beta 7 extends compatibility to real block/chunk draws both with and without an
active shaderpack. Vulkan terrain uses the per-vertex `at_midBlock` anchor and
per-vertex header reads, so unrelated primitives may share a subgroup without
stretching obj-cubed triangles across the scene. The companion resource shader
recovers the BLOCK corner from the carrier's baked atlas-UV marker instead of
assuming `gl_VertexID` order, keeping both desktop and mobile Vulkan on the same
deterministic path. The native and shaderpack crumbling passes preserve the
block-atlas UV separately from the destroy texture and apply cracks to decoded
OBJ geometry instead of the carrier plane.

Beta 8 makes the fallback block anchor follow the carrier face normal, preserving
all four horizontal CraftEngine rotations. It also keeps the crumbling vertex
format active while pipelines compile during resource reload and restores the
canonical `Position`/`Color`/`UV0`/`UV1`/`Normal` binding names in Minecraft's
native destroy-stage program.

Beta 9 makes the native crumbling normal declaration version-aware: an existing
26.1-style canonical `Normal` input is retained, while the missing 26.2 input is
added exactly once.

## Known beta limitations

- This is a targeted compatibility bridge, not a general automatic merge of
  arbitrary Minecraft core shaders with arbitrary shaderpacks.
- The obj-cubed fragment cross-fade path is reduced to the current vertex-selected
  texture frame. Geometry animation remains active.
- Custom face normals and `noshadow` do not yet override a shaderpack's own
  lighting/shadow policy.
- macOS remains an unverified target.
- Each eligible non-obj-cubed entity vertex pays the decoder marker checks while
  this fork and the obj-cubed resource pack are active.

For diagnosis only, launch with `-Diris.objcubed.compat=false` to disable the
bridge without swapping the JAR.

## Build

Run:

```powershell
./gradlew.bat :fabric:build '-Pbuild.release=true'
```

The distributable JAR is written to `build/libs/`.
