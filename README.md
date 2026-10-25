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

Controls: mouse look, `WASD`/arrows move, `Space`/`Shift` up/down, `Esc` frees the mouse (click to recapture),
`F3` toggles the debug title bar (FPS, entity count, camera position).

System properties: `-Dengine.debugGL=true` (throw on GL errors), `-Dengine.exitAfterFrames=N`,
`-Dengine.captureMouse=false`.

## Architecture

| Package | Purpose |
|---|---|
| `engine.core` | `Engine` (window, GL loop, lifecycle), `Game` (your entry point), `FixedTimestep`, `EngineConfig` |
| `engine.ecs` | `Entity`, `Component`, `Transform` (hierarchy), `Scene` (deferred add/remove), `ISystem` |
| `engine.render` | `Renderer` (shader, one directional light), `Mesh`, `Material`, `MeshRenderer`, `Camera`, `Light` |
| `engine.resources` | `ResourceManager` (cached meshes/textures from the classpath), `ObjLoader` |
| `engine.input` | `InputListener` (window thread), `InputState` (polling, key bindings), `FirstPersonController` |
| `engine.messaging` | Thread-safe message queues (`send` is immediate, `post` is delivered on the next update) |
| `engine.physics` | `AabbCollider`, `CollisionSystem` (emits `CollisionEvent` on the `COLLISION` queue) |
| `engine.debug` | `DebugOverlay` (title-bar HUD) |
| `game` | Demo scene (`DemoGame`, `Main`) |

Everything — fixed-step update, message dispatch, rendering — runs on the GL thread, so scene state needs no locks.
Only window events cross threads, via `InputState` and `IMessageService.post`. GL calls are confined to
`engine.render` and `engine.resources`.

Writing a game: implement `Game.init`, build entities (`new Entity().add(new MeshRenderer(...))`), add them to
`engine.scene()`, and register extra `ISystem`s with `engine.addSystem`.

## Known limitations / next ideas

- OBJ materials (`.mtl`) are ignored; one texture per material.
- One directional light, no shadows; no frustum culling or draw batching.
- Colliders are axis-aligned boxes (rotation ignored); no physics response.
- The debug HUD is the window title; there is no in-world text or UI rendering yet.
- Updates are not interpolated between fixed steps.
- Textures are decoded via JOGL's TextureIO (may use AWT for some formats).
