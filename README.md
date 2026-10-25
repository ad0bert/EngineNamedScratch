# EngineNamedScratch

A small, general-purpose Java game engine on OpenGL 3.3 core (JOGL), JOML for math, SLF4J/Logback for logging. It
makes no assumptions about the kind of game: entities, components, systems, a scene, rendering, physics and input are
building blocks you combine.

## Run

Requires JDK 25 and Maven.

```bash
mvn test                      # unit tests (no GPU needed)
mvn -q package                # build
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" game.Main     # use ';' instead of ':' on Windows
```

Demo controls: mouse look, `WASD`/arrows move, `Space`/`Shift` up/down, `F` throws a crate, `E` pushes the crate
under the crosshair, `Esc` frees the mouse (click to recapture), `F3` toggles the on-screen debug text.

System properties: `-Dengine.debugGL=true` (throw on GL errors), `-Dengine.debugCascades=true` (tint the shadow
cascades), `-Dengine.exitAfterFrames=N`,
`-Dengine.captureMouse=false`.

## Features

- **Rendering:** GL3 core, GPU-instanced draw calls batched by (mesh, material), frustum culling, up to 8 lights
  (directional + point), up to 4 cascaded 2048² shadow maps with soft edges and cascade blending for the first
  shadow-casting directional light, tangent-space normal maps (`Material.normalMap`, or `map_Bump` in an MTL),
  alpha-blended transparent objects sorted back to front, points.
- **Models:** OBJ with MTL (diffuse colour/texture, specular, shininess, alpha); one part per `usemtl`.
- **UI:** immediate-mode text and rectangles (`Overlay`, `UiCanvas`) from a Java2D-generated font atlas; the debug HUD
  and the demo crosshair use it.
- **Physics:** oriented box and sphere colliders, rigidbodies (gravity, restitution, friction, rotation and inertia,
  kinematic bodies), multi-point contact manifolds, triggers, enter/exit `CollisionEvent`s, grid broadphase, sleeping, parented bodies, `raycast` and `overlapSphere`
  queries, `Camera.viewRay` for picking.
- **Loop:** fixed-timestep updates with render interpolation between the last two steps.
- **ECS, input bindings, first-person controller, message queues, resource caching** (see below).

## Performance notes

Measured on a desktop PC with unit-test style benchmarks (single thread, no GPU):

| Case | Before | After |
|---|---|---|
| Physics step, 800 crates spread over a field | 70 ms (all pairs) | 2.4 ms (grid), 1.3 ms when all are asleep |
| Culling 50,000 objects | 4.3 ms | 0.09 ms when they are marked static |
| Culling 10,000 objects | 0.46 ms | 0.07 ms when they are marked static |

How it works:

- `PhysicsSystem` pairs colliders through a uniform grid over their bounding spheres; colliders much larger than a cell
  (ground planes) are tested against everything. The cell size adapts to the size of the moving bodies. Bodies that
  rest on something for half a second fall asleep and are woken by moving bodies, by losing their support, by code that
  moves them or sets their velocity, or by `Rigidbody.wakeUp()`.
- `RenderQueue` (backend independent) computes each renderer's matrices once per frame, reuses them for objects that did
  not move since the last fixed step, and culls renderers marked `setStaticGeometry(true)` through a persistent grid,
  visiting only the cells inside the camera's (or light's) bounding box. Models carry a per-instance normal matrix, so
  the shader does no matrix inversion, and the renderer reuses its lists, buffers and matrices every frame.
- `Transform` matrix queries are allocation-free (per-thread scratch), and `Scene.components(Class)` is cached until
  entities or components change.

## Architecture

| Package | Purpose |
|---|---|
| `engine.core` | `Engine` (window, GL loop, lifecycle), `Game` (your entry point), `FixedTimestep`, `EngineConfig` |
| `engine.ecs` | `Entity`, `Component`, `Transform` (hierarchy + interpolation), `Scene` (deferred add/remove, cached queries), `ISystem` |
| `engine.render` | Backend-agnostic: `RenderBackend`, `RenderQueue`, `GpuMesh`/`GpuTexture` handles, `Material`, `MeshRenderer`, `Camera`, `Light`, `Overlay`/`UiCanvas`, `FontAtlas`, `ShadowMath` |
| `engine.render.gl` | The OpenGL implementation of `RenderBackend` (shaders, instancing, shadows, UI batching) |
| `engine.resources` | `ResourceManager` (cached meshes/textures/models, generated textures), `ObjLoader`, `MtlLoader`, `ImageLoader` (ImageIO), `NormalMaps` (height field to normal map), `Model` |
| `engine.spatial` | `SpatialGrid` (used by physics and static-geometry culling) |
| `engine.input` | `InputListener` (window thread), `InputState` (polling, key bindings), `FirstPersonController` |
| `engine.messaging` | Thread-safe message queues (`send` is immediate, `post` is delivered on the next update) |
| `engine.physics` | `Rigidbody`, `BoxCollider`, `SphereCollider`, `Narrowphase`, `PhysicsSystem` (+ raycasts) |
| `engine.debug` | `DebugOverlay` |
| `game` | Demo scene (`DemoGame`, `Main`) |

Everything — fixed-step update, message dispatch, rendering — runs on the GL thread, so scene state needs no locks.
Only window events cross threads, via `InputState` and `IMessageService.post`.

The game, ECS, physics and resource code only see `RenderBackend` and opaque handles; they never touch GL types.
`Engine` itself is the JOGL platform layer (window, event loop). Replacing OpenGL means writing another
`RenderBackend` and a platform class instead of `Engine`'s window code.

Writing a game: implement `Game.init`, build entities (`new Entity().add(new MeshRenderer(...))`), add them to
`engine.scene()`, register extra `ISystem`s with `engine.addSystem` and HUD code with `engine.addOverlay`. Entities with
a collider and no `Rigidbody` are static; add a `Rigidbody` to make them move. Mark scenery that never moves with
`MeshRenderer.setStaticGeometry(true)` (call `invalidate()` if you ever do move it; a debug check logs a warning if you
forget).

## Known limitations

- Developed and tested on Windows with an NVIDIA GPU. macOS and Linux are untested (the old macOS texture bug should
  be gone because textures are now decoded with ImageIO and uploaded by the engine, but that is not verified).
- Bodies rotate about the centre of their first non-trigger collider and use the inertia of a box or sphere (so
  capsule-like or compound shapes are approximated by that collider). Contact impulses are not warm-started between
  steps, so very tall stacks may jitter or sag (a 6-crate stack is tested and holds); raise `PhysicsSystem.velocityIterations`. Kinematic
  bodies do not carry spin, and a spinning one does not drag what rests on it. Sleeping bodies are not carried by
  moving *static* colliders; make movers kinematic rigidbodies. Set `Rigidbody.lockRotation` for upright characters.
- Raycasts and overlap queries loop over all colliders (with a bounding-sphere early-out); they do not use the grid.
- Shadows come from the first directional light only (up to 4 cascades, fixed 2048² each); point lights do not cast
  shadows, and casters further than 4 cascade radii towards the light are dropped from a cascade.
- Normal maps use screen-space derivatives for the tangent frame (no tangent attribute), so meshes need texture
  coordinates; a mirrored UV layout may light incorrectly. There are no specular, roughness or height maps.
- Static geometry is a promise: moving it without `invalidate()` leaves stale culling and drawing data.
- Culling uses bounding spheres, there is no occlusion culling, and the transparent pass is not batched.
- UI is a single bitmap font (monospaced, ASCII only), no layout system or input handling for widgets.
- MTL support covers Kd/Ks/Ns/d/map_Kd and normal maps (map_Bump, bump, norm with `-bm`).
