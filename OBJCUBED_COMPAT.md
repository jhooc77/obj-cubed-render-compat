# Iris 1.10.9 obj-cubed compatibility fork

This branch is based on Iris commit `8dd80cfe9a6101a415eba133f3b526f54245969c`,
the 2026-04-03 Iris 1.10.9 release-era source for Minecraft 26.1.x and Sodium
0.8.9. It remains an LGPL-3.0-only derivative of Iris.

## Supported canary target

- Minecraft 26.1.2
- Fabric Loader
- Sodium 0.8.9
- obj-cubed resource pack containing both
  `minecraft:shaders/include/objmc_tools.glsl` and
  `minecraft:shaders/include/objmc_main.glsl`
- OpenGL with `GL_KHR_shader_subgroup_basic` and
  `GL_KHR_shader_subgroup_shuffle`

The artifact is compiled against Minecraft 26.1.1 because that is the upstream
Iris 1.10.9 build target. The same upstream release metadata covers 26.1.2 and
requires Sodium `0.8.x`.

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

Canary 7 closes the shadow pass' dedicated entity outline batch after feature
rendering, matching vanilla's buffer lifecycle. This prevents outlined item and
equipment vertices from accumulating between shadow frames until BufferBuilder's
24-bit vertex-count limit is reached.

Release 8 extends the decoder bridge to real block/chunk draws. Vanilla terrain,
moving-block, Sodium terrain, and their shadow variants now run obj-cubed's
`BLOCK` decoder before the shaderpack program. Sodium's packed atlas coordinate
is reconstructed with its original inward texel bias, preventing carrier marker
and header reads from landing in a neighbouring atlas pixel.

Release 9 also injects the BLOCK decoder into Sodium's native terrain shader
when shaderpacks are disabled, fixing the carrier-plane-only fallback result.
With a shaderpack active, a dedicated crumbling vertex format preserves the
original block-atlas UV and moves the destroy-stage crack overlay onto decoded
OBJ geometry without increasing the normal terrain vertex size.

Release 11 declares Sodium's native `u_BlockTex` sampler in the injected
shader-off terrain source, fixing the 26.1.2 shader compilation crash. The
native crumbling pipeline now carries the original atlas UV beside the destroy
texture and binds the block atlas as `Sampler1`, so crack overlays follow the
decoded OBJ geometry even when shaderpacks are disabled.

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
display entities to jitter. Equipment face/corner selection now uses the fixed
humanoid UV layout on both 26.1.x and 26.2, avoiding Sodium 0.8.9's non-local
`gl_VertexID` in shared vertex arenas.

Build .12 keeps the extended crumbling vertex format active during resource
reload as well as world rendering and restores the canonical Minecraft input
names in the native destroy-stage program. The companion resource shader uses
the carrier face normal to recover a rotation-aware block anchor, so the same
OBJ carrier remains valid for all four horizontal block-state rotations.

Build .13 preserves the `Normal` input already declared by Minecraft 26.1.x's
native crumbling shader instead of injecting a second declaration. The same
guard still supplies the missing input on 26.2.

## Known canary limitations

- This is a targeted compatibility bridge, not a general automatic merge of
  arbitrary Minecraft core shaders with arbitrary shaderpacks.
- The obj-cubed fragment cross-fade path is reduced to the current vertex-selected
  texture frame. Geometry animation remains active.
- Custom face normals and `noshadow` do not yet override a shaderpack's own
  lighting/shadow policy.
- Vulkan/SPIR-V and macOS remain canary targets rather than guaranteed targets.
- Each eligible non-obj-cubed entity vertex pays the decoder marker checks while
  this fork and the obj-cubed resource pack are active.

For diagnosis only, launch with `-Diris.objcubed.compat=false` to disable the
bridge without swapping the JAR.

## Build

Place the official Sodium 0.8.9 artifacts under `custom_sodium/` using the local
filenames declared in `build.gradle.kts`, then run:

```powershell
./gradlew.bat :fabric:build '-Pbuild.release=true'
```

The distributable JAR is written to `build/libs/`.
