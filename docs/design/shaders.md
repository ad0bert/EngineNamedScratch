# Custom shaders

Status: done. How games and kits bring their own shaders without forking the engine's.

## Before

A game could only replace a built-in shader file by putting one with the same path in an asset directory. That
replaced it for every object, meant copying a 458-line fragment shader with 63 uniforms the renderer sets by name,
and broke whenever the engine changed it. Materials had no shader of their own, no values for one, and the
post-processing chain was fixed.

## Design

**A shader library instead of one big shader.** `basic.frag` and `basic.vert` were split into include files under
`shaders/lib/`:

- `lighting.glsl`: the lighting uniforms and functions (lights, cascaded and local shadows, sky, probes, output
  targets) and `struct Surface`. `lighting(s)` returns the light a surface sends to the camera, `shade(s)` also writes
  the fragment (unlit, glass, opaque or weighted blended transparency).
- `surface.glsl`: the material uniforms and `standardSurface()`, the Material as a `Surface` (colour, UV transform,
  parallax, normal, specular, metal-roughness and emissive maps, alpha cut-off).
- `vertex.glsl`: the attributes (per-instance matrices, skinning) and `emit(position, normal)`.
- `post.glsl`: what a post pass gets.

`basic.frag` is now `shade(standardSurface())` and `basic.vert` is `emit(aPos, aNormal)`; the rendered image is the
same as before. The `Surface` struct is the stable contract: a custom shader changes a field or two and calls
`shade`, and keeps working when the engine's lighting improves.

**`#include`** (`ShaderIncludes`, GL-free and unit-tested): looked up next to the including file, then in the
engine's `shaders/` directory (so `"lib/surface.glsl"` works from any shader, also one in a jar), then as a path of
its own. Each file is included once per shader. `#line` directives keep compiler messages on the right file and
line, and a failing compile lists which number is which file. Hot reloading recompiles every program that includes a
changed file.

**Per-material programs.** `Material.fragmentShader`/`vertexShader` (resource paths; either may be null for the
engine's) and `Material.params` (`ShaderParams`: floats, ints, vectors, matrices, textures). The renderer builds a
program per distinct pair, sets the pass's lighting uniforms on it the first time it is used in a pass, then the
material's uniforms (the standard ones, so `standardSurface()` works, then `RenderSettings.shaderGlobals`, then the
material's params; textures go to units 16 and up). Batching is unchanged: runs of the same mesh and material are one
instanced draw. `uTime` (seconds since the renderer started) is set on every main-pass program and pass.

**Post passes.** `RenderSettings.postPasses` holds `PostPass`es (fragment shader, `Stage`, params, enabled). `HDR`
passes run on the linear scene after transparency and particles, before bloom and tone mapping; `DISPLAY` passes on
the tone-mapped image before FXAA. Each stage ping-pongs between its existing target and one more texture, made on
first use. FXAA now computes luma from colour instead of reading it from alpha, so passes need not keep it.

**Failures** are logged once: a material falls back to the engine's program, a pass is skipped. A hot reload clears
the failures so a fixed file is tried again.

**Saving.** The shader paths are plain `Material` fields and are saved as such; `params` are saved next to them
(numbers, arrays for vectors, `{"int": n}`, `{"mat4": [...]}`, `{"texture": key}`).

## Limits

- Shadow and depth passes use the engine's shaders: a vertex shader that moves vertices does not move their shadows,
  and a fragment shader that discards does not cut holes in shadows (use `alphaCutoff` for that).
- The main fragment shader already samples 15 textures; GL 3.3 only promises 16 per stage, so a custom shader with
  more than one texture of its own needs hardware with more (desktop GPUs have 32).
- Every distinct shader pair is a program switch per frame and pass; fine for a handful of effects.
