# EngineNamedScratch

A small, general-purpose Java game engine on OpenGL 3.3 core (JOGL), JOML for math, SLF4J/Logback for logging. It
makes no assumptions about the kind of game: entities, components, systems, a scene, rendering, physics, input and UI
are building blocks you combine.

## Run

Requires JDK 25 and Maven.

```bash
mvn test                      # unit tests (no GPU needed)
mvn -q package                # build
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" game.Main     # use ';' instead of ':' on Windows
```

Demo controls: mouse look, `WASD`/arrows move, `Space`/`Shift` up/down, `F` throws a crate, `E` pushes the crate
under the crosshair, `F3` toggles the on-screen debug text, `Esc` frees the mouse and shows a settings panel (click
outside it to look around again).

System properties:

| Property | Effect |
|---|---|
| `engine.debugGL=true` | throw on GL errors |
| `engine.exitAfterFrames=N` | close after N frames (smoke tests) |
| `engine.screenshot=file.png` | save the last frame as PNG (with `exitAfterFrames`), or frame `engine.screenshotFrame=N` |
| `engine.captureMouse=false` | start with a free pointer |
| `engine.shadowMapSize=2048`, `engine.pointShadowMapSize=512`, `engine.maxPointShadows=4` | shadow quality |
| `engine.occlusionCulling=false` | draw hidden static geometry too |
| `engine.debugCascades=true` | tint the shadow cascades |
| `demo.camera=x,y,z,yaw,pitch` | demo only: start somewhere else |

The render settings can also be changed at run time through `engine.backend().settings()`.

Headless Linux works with a virtual X server and Mesa's software OpenGL; CI runs the demo like this on every push and
keeps the last frame as an artifact:

```bash
xvfb-run -a -s "-screen 0 1280x800x24" java -Dengine.debugGL=true -Dengine.exitAfterFrames=120 \
  -Dengine.captureMouse=false -Dengine.screenshot=smoke.png -cp "target/classes:$(cat cp.txt)" game.Main
```

## Features

- **Rendering:** GL3 core forward renderer, GPU-instanced draw calls batched by (mesh, material), frustum culling
  (sphere, then box for long or flat objects), occlusion culling of static geometry, up to 8 lights (directional +
  point), up to 4 cascaded shadow maps with soft edges and cascade blending for the first shadow-casting directional
  light, cube shadow maps for up to 4 shadow-casting point lights, alpha-blended transparent objects sorted back to
  front (still instanced where possible), alpha cut-outs (with matching holes in the shadows), points.
- **Materials:** colour or texture, tangent-space normal maps (meshes carry tangents with handedness, so mirrored uvs
  work), specular maps, emissive colour and maps, height maps with parallax occlusion mapping.
- **Models:** OBJ with MTL: `Kd Ks Ke Ns d illum`, `map_Kd map_Ks map_Ke map_d`, `map_Bump`/`bump`/`norm` (normal
  maps, or grey bump maps that are converted) and `disp`/`map_disp` (height); one part per `usemtl`. Generated meshes:
  cube, quad, sphere, points.
- **UI:** immediate-mode drawing with any number of fonts (any installed or logical Java font, proportional or fixed,
  glyphs rasterized on demand, so any character the font has works), and immediate-mode widgets (`engine.ui.Gui`):
  panels, labels, buttons, checkboxes, sliders, text fields, rows, mouse and keyboard handling.
- **Physics:** oriented box and sphere colliders, compound bodies (several colliders on one rigidbody, with the
  combined centre of mass and inertia), rigidbodies (gravity, restitution, friction, rotation, kinematic bodies),
  multi-point contact manifolds, warm-started impulses and speculative contacts (stable stacks), moving platforms
  (kinematic or static colliders moved by code carry what rests on them), triggers, enter/exit `CollisionEvent`s,
  grid broadphase, sleeping, parented bodies, grid-accelerated `raycast` and `overlapSphere` queries,
  `Camera.viewRay` for picking.
- **Loop:** fixed-timestep updates with render interpolation between the last two steps.
- **ECS, input (keys, mouse, text), first-person controller, message queues, resource caching, screenshots** (see
  below).

## Performance notes

Measured on a desktop PC with unit-test style benchmarks (single thread, no GPU):

| Case | Before | After |
|---|---|---|
| Physics step, 800 crates spread over a field | 70 ms (all pairs) | 2.4 ms (grid), 1.3 ms when all are asleep |
| Raycast among 800 crates | 58 µs (every collider) | 2.4 µs (walks the grid cells along the ray) |
| Culling 50,000 objects | 4.3 ms | 0.09 ms when they are marked static |
| Culling 10,000 objects | 0.46 ms | 0.07 ms when they are marked static |

Warm starting and speculative contacts cost about 7 % more per awake physics step; in exchange a 12-crate stack stands
without sleeping (it fell over before) and a 20-crate stack comes to rest.

How it works:

- `PhysicsSystem` pairs colliders through a uniform grid over their bounding spheres; colliders much larger than a cell
  (ground planes) are tested against everything. The cell size adapts to the size of the moving bodies. Bodies that
  rest on something for half a second fall asleep and are woken by moving bodies, by losing their support, by code that
  moves them or sets their velocity, or by `Rigidbody.wakeUp()`. Queries reuse the grid of the last step.
- Contacts are cached per collider pair; the next step starts from the impulses they ended with (matched by position),
  and pairs up to 2 cm apart are solved as speculative contacts, so resting contacts survive small bounces.
- `RenderQueue` (backend independent) computes each renderer's matrices once per frame, reuses them for objects that did
  not move since the last fixed step, and culls renderers marked `setStaticGeometry(true)` through a persistent grid,
  visiting only the cells inside the camera's (or light's) bounding box. Moved static renderers are found through
  `Transform.trackChanges()`, so only frames after a change look at them again. The GL backend draws the box of every
  visible static cell with an occlusion query after the opaque pass and skips cells that were hidden in the last
  frame; results are read a frame later, so the CPU never waits for the GPU.
- Models carry a per-instance normal matrix, so the shader does no matrix inversion, and the renderer reuses its lists,
  buffers and matrices every frame. Shadow casters with cut-outs use their own program, so every other caster keeps
  early depth testing.
- `Transform` matrix queries are allocation-free (per-thread scratch), and `Scene.components(Class)` is cached until
  entities or components change.

## Architecture

| Package | Purpose |
|---|---|
| `engine.core` | `Engine` (window, GL loop, lifecycle, screenshots), `Game` (your entry point), `FixedTimestep`, `EngineConfig` |
| `engine.ecs` | `Entity`, `Component`, `Transform` (hierarchy + interpolation + change tracking), `Scene` (deferred add/remove, cached queries), `ISystem` |
| `engine.render` | Backend-agnostic: `RenderBackend`, `RenderSettings`, `RenderQueue`, `GpuMesh`/`GpuTexture` handles, `Material`, `MeshRenderer`, `Camera`, `Light`, `Overlay`/`UiCanvas`/`UiFont`, `FontAtlas`, `ShadowMath` |
| `engine.render.gl` | The OpenGL implementation of `RenderBackend` (shaders, instancing, shadow maps, occlusion queries, UI batching) |
| `engine.ui` | `Gui`: immediate-mode widgets on any `UiCanvas` |
| `engine.resources` | `ResourceManager` (cached meshes/textures/models, generated textures), `ObjLoader`, `MtlLoader`, `ImageLoader` (ImageIO), `NormalMaps` (height field to normal map), `Model` |
| `engine.spatial` | `SpatialGrid` (used by physics, physics queries and static-geometry culling) |
| `engine.input` | `InputListener` (window thread), `InputState` (polling, key bindings, mouse, typed text), `FirstPersonController` |
| `engine.messaging` | Thread-safe message queues (`send` is immediate, `post` is delivered on the next update) |
| `engine.physics` | `Rigidbody`, `BoxCollider`, `SphereCollider`, `Narrowphase`, `PhysicsSystem` (+ raycasts) |
| `engine.debug` | `DebugOverlay` |
| `game` | Demo scene (`DemoGame`, `Main`) |

Everything — fixed-step update, message dispatch, rendering — runs on the GL thread, so scene state needs no locks.
Only window events cross threads, via `InputState` and `IMessageService.post`.

The game, ECS, physics and resource code only see `RenderBackend` and opaque handles; they never touch GL types.
`Engine` itself is the JOGL platform layer (window, event loop). Replacing OpenGL means writing another
`RenderBackend` and a platform class instead of `Engine`'s window code that feeds `InputState` (its writers are
public).

Writing a game: implement `Game.init`, build entities (`new Entity().add(new MeshRenderer(...))`), add them to
`engine.scene()`, register extra `ISystem`s with `engine.addSystem` and HUD code with `engine.addOverlay`. Entities with
a collider and no `Rigidbody` are static; add a `Rigidbody` to make them move, several colliders to give it a compound
shape. Mark scenery that rarely or never moves with `MeshRenderer.setStaticGeometry(true)`; if it does move, the engine
notices at the next fixed step (call `invalidate()` to apply it in the same frame). For menus and tools, create a
`Gui` and call its widgets from an overlay; `engine.setClickCapturesMouse(() -> !gui.wantsMouse())` keeps clicks on
widgets from grabbing the mouse.

## Known limitations

- Developed on Windows with an NVIDIA GPU; Linux is tested (CI runs the demo on Mesa's software OpenGL with GL error
  checking). macOS is untested.
- Physics knows boxes and spheres only (no capsules, cylinders or triangle meshes) and has no joints. There is no
  continuous collision detection: speculative contacts only cover shapes that start a step less than 2 cm apart, so
  small, fast bodies can still tunnel through thin colliders. Stacks of more than about 20 crates slowly
  creep sideways. Queries find their candidates through the grid of the last physics step, so a collider moved by code
  since then is only found where it was (exact shapes are always tested where they are now).
- Lighting is Blinn-Phong in a forward renderer: at most 8 lights per frame, no spot lights, no PBR, HDR or
  post-processing. Point light shadows cost six passes over the casters in range each.
- Occlusion culling only skips static geometry, in whole grid cells, and is one frame late: an object coming out from
  behind a wall can appear a frame after it should. Transparent objects are sorted per object, not per triangle.
- A moved static renderer is re-registered at the next fixed step, so it can be drawn at its old place for a frame
  unless `invalidate()` is called.
- The UI has no clipping or scrolling, no keyboard navigation between widgets, and its text fields are single-line
  without cursor movement or selection; glyphs are rasterized at one pixel size, so scaled text gets blurry.
- MTL texture options other than `-bm` are ignored, as are `Ka`, `Tr`, `Ni` and the PBR extensions.
