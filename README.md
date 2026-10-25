# EngineNamedScratch

A small Java game engine on OpenGL 3.3 core (JOGL), JOML for math, SLF4J/Logback for logging.

## Run

Requires JDK 25 and Maven.

```bash
mvn test                      # unit tests (no GPU needed)
mvn -q package                # build
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" game.Main     # use ';' instead of ':' on Windows
```

Controls: mouse look, `WASD`/arrows move, `Space`/`Shift` up/down, `F` throws a crate, `Esc` frees the mouse (click to
recapture), `F3` toggles the on-screen debug text.

System properties: `-Dengine.debugGL=true` (throw on GL errors), `-Dengine.exitAfterFrames=N`,
`-Dengine.captureMouse=false`.

## Features

- **Rendering:** GL3 core, GPU-instanced draw calls batched by (mesh, material), frustum culling, up to 8 lights
  (directional + point), a 2048² shadow map with PCF for the first shadow-casting directional light, alpha-blended
  transparent objects sorted back to front, points.
- **Models:** OBJ with MTL (diffuse colour/texture, specular, shininess, alpha); one part per `usemtl`.
- **UI:** immediate-mode text and rectangles (`Overlay`, `UiCanvas`) from a Java2D-generated font atlas; the debug HUD
  and the demo crosshair use it.
- **Physics:** oriented box and sphere colliders, rigidbodies (gravity, restitution, friction, kinematic bodies),
  triggers, enter/exit `CollisionEvent`s.
- **Loop:** fixed-timestep updates with render interpolation between the last two steps.
- **ECS, input bindings, first-person controller, message queues, resource caching** (see below).

## Architecture

| Package | Purpose |
|---|---|
| `engine.core` | `Engine` (window, GL loop, lifecycle), `Game` (your entry point), `FixedTimestep`, `EngineConfig` |
| `engine.ecs` | `Entity`, `Component`, `Transform` (hierarchy + interpolation), `Scene` (deferred add/remove), `ISystem` |
| `engine.render` | Backend-agnostic: `RenderBackend`, `GpuMesh`/`GpuTexture` handles, `Material`, `MeshRenderer`, `Camera`, `Light`, `Overlay`/`UiCanvas`, `FontAtlas`, `ShadowMath` |
| `engine.render.gl` | The OpenGL implementation of `RenderBackend` (shaders, instancing, shadows, UI batching) |
| `engine.resources` | `ResourceManager` (cached meshes/textures/models), `ObjLoader`, `MtlLoader`, `ImageLoader` (ImageIO), `Model` |
| `engine.input` | `InputListener` (window thread), `InputState` (polling, key bindings), `FirstPersonController` |
| `engine.messaging` | Thread-safe message queues (`send` is immediate, `post` is delivered on the next update) |
| `engine.physics` | `Rigidbody`, `BoxCollider`, `SphereCollider`, `Narrowphase`, `PhysicsSystem` |
| `engine.debug` | `DebugOverlay` |
| `game` | Demo scene (`DemoGame`, `Main`) |

Everything — fixed-step update, message dispatch, rendering — runs on the GL thread, so scene state needs no locks.
Only window events cross threads, via `InputState` and `IMessageService.post`.

The game, ECS, physics and resource code only see `RenderBackend` and opaque handles; they never touch GL types.
`Engine` itself is the JOGL platform layer (window, event loop). Replacing OpenGL means writing another
`RenderBackend` and a platform class instead of `Engine`'s window code.

Writing a game: implement `Game.init`, build entities (`new Entity().add(new MeshRenderer(...))`), add them to
`engine.scene()`, register extra `ISystem`s with `engine.addSystem` and HUD code with `engine.addOverlay`. Entities with
a collider and no `Rigidbody` are static; add a `Rigidbody` to make them move.

## Known limitations

- Developed and tested on Windows with an NVIDIA GPU. macOS and Linux are untested (the old macOS texture bug should
  be gone because textures are now decoded with ImageIO and uploaded by the engine, but that is not verified).
- Physics is linear only (bodies never rotate), has no sleeping, and uses brute-force pair finding. It should not be
  used with parented colliders.
- Only one shadow map (first directional light, no cascades); point lights do not cast shadows.
- Instancing recomputes the normal matrix per vertex in the shader; fine for demos, not for dense meshes.
- Per-frame lists are rebuilt each frame (some garbage); there is no spatial index for culling.
- UI is a single bitmap font (monospaced, ASCII only), no layout system or input handling for widgets.
- MTL support covers Kd/Ks/Ns/d/map_Kd; no normal/specular maps.
