# EngineNamedScratch

A small, general-purpose Java game engine on OpenGL 3.3 core (JOGL), OpenAL (JOAL) for sound, JOML for math,
SLF4J/Logback for logging. It makes no assumptions about the kind of game: entities, components, systems, a scene,
rendering, physics, sound, animation, input and UI are building blocks you combine.

## Project layout

| Module | What it is |
|---|---|
| `engine/` | The engine library, published as `io.github.ad0bert:engine-named-scratch` (jar, sources, javadoc). It holds only `engine.*` and its shaders. |
| `demo/` | The demo game (`game.*` and its assets). It shows how to use the engine and is the CI smoke test. It is not published. |

## Run

Requires JDK 25 and Maven.

```bash
mvn test                      # unit tests (no GPU needed)
mvn -q package                # build the engine jar and demo/target/dist/demo.jar (+ dist/lib)
java -jar demo/target/dist/demo.jar
mvn -q package -Pjpackage     # also: demo/target/jpackage, the demo with its own Java runtime
```

Demo controls: mouse look, `WASD`/arrows walk, `Space` jumps, `V` switches between walking and flying (`Space`/`Shift`
fly up/down), `F` throws a crate, `E` pushes the body
under the crosshair (with sparks), `F3` toggles the on-screen debug text, `F5` saves the scene to
`demo-scene.json`, `Esc` frees the mouse and shows a settings panel with volume sliders and a controls panel to change the keys (click outside it to look around again, `Tab` moves between
its widgets), `P` pauses, `F11` switches to fullscreen. With a gamepad: left stick moves, right stick looks,
`Y` jumps, triggers fly up and down, `RB`/`X` throws, `A` pushes, `Start` pauses. Volumes, fullscreen and vsync are saved in the
user's config directory (`~/.config/enginenamedscratch-demo` on Linux).

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
| `engine.exposure=1`, `engine.bloomStrength=0.08`, `engine.bloomThreshold=1` | HDR tone mapping and bloom |
| `engine.antiAliasing=false`, `engine.orderIndependentTransparency=false` | turn off FXAA / weighted blended OIT |
| `engine.ambientOcclusion=false`, `engine.aoRadius=0.8`, `engine.aoStrength=2` | screen-space ambient occlusion; `engine.debugAmbientOcclusion=true` shows it in grey |
| `engine.skyIntensity=1` | brightness of the sky and its lighting |
| `engine.audio=false` | no sound (also automatic without a sound device) |
| `engine.gamepads=false` | do not look for gamepads |
| `engine.fullscreen=true`, `engine.vsync=false` | override the config's `app.fullscreen` / `app.vsync` |
| `engine.debugOverlay=false` | start with the debug text (`F3`) hidden; games can also call `engine.setDebugOverlayVisible(false)` |
| `engine.hotReload=true` | watch the asset directories and apply changed shaders, textures and meshes while running (`Engine.setHotReload`) |
| `engine.userDir=dir` | keep settings and saves under `dir/<app id>` instead of the platform's user directories |
| `demo.camera=x,y,z,yaw,pitch` | demo only: start flying with the eyes somewhere else |
| `demo.sky=file.hdr` or `demo.sky=none` | demo only: an HDR environment image instead of the procedural sky, or no sky |
| `demo.saveScene=file.json` | demo only: save the scene at start (what `F5` does) |
| `demo.paused=true` | demo only: start in the pause menu |
| `demo.probe=false` | demo only: the room by the field without its reflection probe |
| `demo.assets=dir` | demo only: an asset directory whose files replace the demo's and the engine's shaders (`dir/shaders/tonemap.frag`) |

The render settings can also be changed at run time through `engine.backend().settings()`.

Headless Linux works with a virtual X server and Mesa's software OpenGL; CI runs the demo like this on every push and
keeps the last frame as an artifact:

```bash
xvfb-run -a -s "-screen 0 1280x800x24" java -Dengine.debugGL=true -Dengine.exitAfterFrames=120 \
  -Dengine.captureMouse=false -Dengine.screenshot=smoke.png -jar demo/target/dist/demo.jar
```

## Using the engine in a game

Keep the game in its own project and depend on the engine. A release is published when a tag like `v0.8.0` is
pushed (`.github/workflows/release.yml`): the jars go to GitHub Packages and are attached to a GitHub release, along
with the demo (a runnable jar, and packaged for each platform). The release's title and description come from
`builds/release-notes/<tag>.md`; write it before pushing the tag.

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/ad0bert/EngineNamedScratch</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>io.github.ad0bert</groupId>
    <artifactId>engine-named-scratch</artifactId>
    <version>0.8.0</version>
  </dependency>
  <!-- the engine logs through SLF4J only; pick a backend -->
  <dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.5.18</version>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

GitHub Packages needs credentials even for public packages: add a `github` server with your user name and a token
with `read:packages` to `~/.m2/settings.xml`. While changing the engine and a game together, run `mvn install` here and
depend on the `-SNAPSHOT` version instead. That needs no repository or credentials.

The game supplies its own `application.properties` (or whatever it passes to `EngineConfig.load`), its assets on the
classpath, and a `main` that calls `new Engine(config, game).run()`. The JOGL, JOAL and JInput natives come with the
Maven dependencies. `demo/pom.xml` shows the packaging:

- `target/dist/`: a runnable `demo.jar` with its dependencies in `lib/` (needs Java 25 installed);
- `-Pjpackage`: the same with its own trimmed Java runtime, as a folder (`jpackage.type=app-image`) or an installer
  (`deb`, `rpm`, `dmg`, `pkg`, `msi`, `exe`). jpackage builds for the system it runs on; the release workflow builds
  the demo on Linux, macOS and Windows. The macOS build is not signed, so Gatekeeper asks before the first start.

The packages `engine.render.gl`, `engine.audio.al` and `engine.input.jinput` are the backends' internals; everything
else is the API. The jar's module name is `io.github.ad0bert.engine`.

### Structuring a game

```java
public final class Main {
  public static void main(String[] args) throws InterruptedException {
    final var config = EngineConfig.load("application.properties");   // app.id, title, size, fullscreen, vsync
    final var engine = new Engine(config, e -> e.states().push(new TitleScreen()));
    engine.assets().addRoot(Path.of("mods"));                          // files here override assets in the jar
    engine.run();
  }
}

class Level1 extends LevelState {                                     // a level owns the scene while it runs
  @Override
  protected void build(Engine engine) {
    engine.scene().add(new Entity("floor").add(...));                 // resources loaded here are the level's
  }

  @Override
  public void update(Engine engine, double dt) {
    if (won) engine.states().replace(new Level2());                   // clears the scene, frees Level1's resources
    if (engine.input().wasActionPressed("pause")) engine.states().push(new PauseMenu());
  }
}

class PauseMenu implements GameState {
  @Override public boolean simulates() { return false; }              // physics, animation and components stop
  @Override public void draw(Engine engine, UiCanvas ui, int w, int h) { ... }
}
```

- **States** (`engine.states()`): `push`, `pop`, `replace` and `clear` take effect at the start of the next fixed
  step. The top state gets `update`; all states `draw`, bottom first.
- **Levels** (`LevelState`): `enter` opens a resource scope and calls `build`; `exit` clears the scene and closes the
  scope, which disposes the meshes, textures and sounds first loaded during the level. Without states, the same is
  `engine.scene().clear()` and `engine.resources().openScope()` / `scope.close()`.
- **Pause:** `engine.setPaused(true)`, or a top state whose `simulates()` is false. Physics, animation and component
  updates stop; sound keeps playing (`engine.audio().mixer().setMuted(true)` silences it). Systems that should keep
  going return true from `ISystem.runsWhilePaused`.
- **Window:** `engine.setFullscreen`, `setVsync`, `setWindowSize`, `monitorResolutions()` for a settings menu.
- **Assets and user files:** `engine.assets()` looks in added directories, then on the classpath;
  `engine.userDirs().config()` / `.data()` are the platform's per-user directories; `Settings.load(file)` reads and
  safely writes a properties file of player settings.
- **Versions:** `Engine.VERSION` is the engine's version. The demo gets its own `app.version` from Maven by filtering
  `src/main/resources-filtered/application.properties`.

## Features

- **Rendering:** GL3 core forward renderer into an HDR target, then screen-space ambient occlusion (it darkens only
  the ambient and sky light, which the opaque pass writes to a second target), bloom, ACES tone mapping and FXAA; linear
  lighting (colour textures and colours are sRGB, data maps are linear). GPU-instanced draw calls batched by (mesh,
  material), frustum culling (sphere, then box for long or flat objects), occlusion culling of static geometry, up to 8
  lights (directional, point and spot), up to 4 cascaded shadow maps with soft edges and cascade blending for the first
  shadow-casting directional light, shadows for up to 4 point or spot lights (a point light uses a cube, a spot light
  one map; maps whose light and casters did not change are reused), weighted blended order-independent transparency
  (or back-to-front sorting), alpha cut-outs (with matching holes in the shadows), points. A sky behind the scene
  (`RenderSettings.sky`: a procedural gradient with a sun disc, or an equirectangular HDR image) that also lights it:
  image-based lighting baked once on the GPU (diffuse irradiance, GGX-prefiltered reflections per roughness, split-sum
  BRDF table), with intensity and rotation settings. Local `ReflectionProbe`s capture the scene into cube maps
  (blurred per roughness like the sky); inside their boxes surfaces take box-projected reflections and ambient light
  from them instead of the sky, so rooms reflect their own walls and are not lit by the open sky.
- **Particles:** `ParticleEmitter` components spawn camera-facing quads by rate or in bursts, inside a sphere and a
  cone, with acceleration, drag, size, colour and spin over their life, texture atlases (animated or one random frame
  each), additive or alpha blending (sorted back to front) and soft edges where they meet geometry; simulated at the
  fixed step in flat arrays, spread along the emitter's path when it moves, in world or local space, and drawn with
  one instanced call per emitter. Smoke and dust can be lit (`lit`: the lights and the ambient or sky light at the
  emitter), and particles can collide with the physics world (`collider = physics.particleCollider(scene, mask)`):
  they bounce with `bounce` and `collisionFriction`, or die where they hit.
- **Materials:** Blinn-Phong or physically based (GGX, metallic/roughness, glTF-style metal-rough maps); colour or
  texture, tangent-space normal maps (meshes carry tangents with handedness, so mirrored uvs work), specular maps,
  emissive colour and maps, height maps with parallax occlusion mapping, uv scale/offset and clamping; with a sky,
  physically based materials reflect it (blurrier when rougher) and every material takes its ambient light from it.
- **Models:** OBJ with MTL: `Ka Kd Ks Ke Ns Ni d Tr illum`, the PBR extension (`Pr Pm map_Pr map_Pm`), `map_Kd map_Ks
  map_Ke map_d`, `map_Bump`/`bump`/`norm` (normal maps, or grey bump maps that are converted), `disp`/`map_disp`
  (height), texture options `-s -o -clamp -bm`; one part per `usemtl`. glTF 2.0 (`.gltf` with embedded or external
  data, `.glb`): node hierarchy, meshes, metallic-roughness materials with base colour, metal-rough, normal and
  emissive textures, alpha modes, skins and animations (`ResourceManager.gltf(path).instantiate(scene)`). Generated
  meshes: cube, quad, sphere, points, and shape specs such as `cube:0.5` that scene files can refer to. Levels of
  detail: `MeshRenderer.addLod(distance, mesh)` switches to coarser meshes with the camera distance (times
  `RenderSettings.lodBias`, `-Dengine.lodBias`), `cullDistance` stops drawing far away, and `MeshSimplifier` makes
  coarser meshes by vertex clustering (keeping hard edges apart).
  Radiance `.hdr` images (`HdrLoader`) for skies.
- **Animation:** keyframe tracks (step, linear with 16 easing curves, slerped rotations, cubic splines) for transform
  properties of an entity or its named descendants and for any float values (material colours, volumes...), as
  clips played by an `Animator` with layers: play, cross-fade, blend at a weight, tween; loop, once, ping-pong.
  Skeletal animation from glTF with GPU skinning (four weights per vertex, joint matrices in a texture buffer, in the
  scene and shadow passes).
- **Sound:** OpenAL through JOAL, or silence when there is no device. `AudioSource` components (2D or positioned,
  volume, pitch, loop, three distance curves, min/max distance, priority, play/pause/stop, play on start) and an
  `AudioListener` (or the camera), Doppler from rigidbody velocities, one-shots (`engine.audio().play(clip, pos)`),
  mixer buses (master, music, effects, UI), a voice limit with priority- and loudness-based stealing (sounds without
  a voice keep time and come back), impact sounds scaled by the collision impulse (`ImpactSound`). WAV/AIFF/AU and
  Ogg Vorbis (JOrbis), long music streamed from the compressed file.
- **Scenes and prefabs:** entities, their hierarchy and components saved to and loaded from JSON
  (`SceneSerializer`); materials, animation clips and skins are shared by index and resources referred to by key;
  animators keep their clips and the layers playing (clock, speed, weight, fade) and sound sources whether they play
  and where, so a saved glTF instance moves on where it was; any component with public
  fields can opt in with one line (`FieldSerializer`), others with a `ComponentSerializer`. A `Prefab` is such a file
  used as a template, instantiated as often as needed (the instances share materials, so they still batch).
- **UI:** immediate-mode drawing with any number of fonts (any installed or logical Java font, proportional or fixed,
  glyphs rasterized on demand at the size they are drawn, so any character the font has works and scaled text stays
  sharp), clipping, and immediate-mode widgets (`engine.ui.Gui`): panels, labels, buttons, checkboxes, sliders,
  single- and multi-line text fields (cursor, selection, Home/End, arrows, Ctrl+A, copy/cut/paste with Ctrl+C/X/V
  through the system clipboard or an in-memory one when headless), word-wrapped paragraphs, scroll regions, rows;
  Tab/Shift+Tab focus navigation with Space/Enter to press.
- **Physics:** box, sphere, capsule and cylinder colliders, convex hulls of point clouds (quickhull with a vertex
  cap, the solid's own mass and inertia; contacts with any other shape through GJK/EPA and clipped face manifolds),
  triangle-mesh colliders for static or kinematic level geometry (BVH, one- or two-sided), compound bodies (several
  colliders on one rigidbody, with the combined centre of mass and inertia), rigidbodies (gravity, restitution,
  friction, rotation, kinematic bodies), ball (with swing cone and twist limits, for ragdolls), hinge (with motor and
  angle limits that can bounce or give) and distance (rod, rope, range) joints that can break, continuous collision for fast bodies (`Rigidbody.continuous`),
  multi-point contact manifolds solved as a block with friction at their centre, warm-started impulses and
  speculative contacts (40-crate stacks stand), moving platforms (kinematic or static colliders moved by code carry
  what rests on them), triggers, enter/exit `CollisionEvent`s, grid broadphase, sleeping, parented bodies,
  grid-accelerated `raycast`, `sphereCast`, `capsuleCast`, `overlapSphere` and `overlapCapsule` queries (the casts
  are exact for every shape: conservative advancement on the GJK distance; `syncQueries` makes them see colliders
  moved since the last step), collision layers (`Collider.layer`, `setLayersCollide`, query filters by layer mask
  with `PhysicsSystem.solid(mask)`), `Camera.viewRay` for picking.
- **Characters:** `CharacterController` walks a capsule by sweeping it (collide and slide, creases, step up ledges,
  slope limit, ground snapping down slopes and steps, jumps with coyote time, ceilings), rides moving and turning
  ground, pushes dynamic bodies by its mass, is pushed out of things that move into it, and is a collider other bodies
  rest on. `FirstPersonController` walks with it when the entity has one (pitch on a `head` child) and flies
  otherwise.
- **Terrain:** a `Heightfield` (from a function, an image or the built-in fractal noise `Heightfield.fbm`) becomes
  chunked `Terrain` entities: smooth-normal meshes in metres-based uvs for tiling textures, coarser levels of detail
  every second and fourth sample with skirts against cracks, and full-detail `MeshCollider`s, so physics, character
  controllers and navigation walk on it.
- **Navigation:** `NavGrid` bakes where an agent of a given radius, height, step and slope can walk from the static
  colliders (up to four floors per cell, so bridges and storeys work), finds paths with A* (no corner cutting) and
  straightens them; regions can be baked again and cells blocked at run time. A `NavAgent` walks its entity along
  the path, through its `CharacterController` if it has one, and plans again when the goal moves or it gets stuck.
- **Loop:** fixed-timestep updates with render interpolation between the last two steps.
- **Input:** keys, mouse, text and up to four gamepads (JInput; radial stick and trigger dead zones, connect and
  disconnect, rumble where JInput finds motors), with actions bound to keys, gamepad buttons and gamepad axes at
  once; bindings can be listed, changed, written as text (`W, UP, pad:Y`) and saved in `Settings`
  (`saveBindings`/`loadBindings`), and a `Rebinder` binds the next key or button pressed (for a controls menu); a
  first-person controller that takes both.
- **Hot reloading and profiling:** with `Engine.setHotReload(true)` the asset directories are watched; changed
  shaders are compiled again (one that does not compile keeps its old version), textures and OBJ meshes loaded again
  in place, and `Engine.onAssetChanged` listeners hear of every changed path (to reload a level or a prefab). Shader
  files in an asset directory replace the built-in ones of the same path. `Engine.profiler()` times every system, the
  game's update and rendering per frame over the last 240 frames; games add sections with `begin`/`end`. F3 shows a
  frame-time graph and the slowest sections.
- **ECS, message queues, resource caching, screenshots** (see below).

## Performance notes

Measured on a desktop PC with unit-test style benchmarks (single thread, no GPU):

| Case | Before | After |
|---|---|---|
| Physics step, 800 crates spread over a field | 70 ms (all pairs) | 2.4 ms (grid), 1.3 ms when all are asleep |
| Raycast among 800 crates | 58 µs (every collider) | 2.4 µs (walks the grid cells along the ray) |
| Culling 50,000 objects | 4.3 ms | 0.09 ms when they are marked static |
| Culling 10,000 objects | 0.46 ms | 0.07 ms when they are marked static |

Animation, audio and gamepad polling do not allocate per step; skinning costs one texture-buffer upload and one
draw call per skinned renderer (they are not instanced), all other meshes stay instanced.

Warm starting and speculative contacts cost about 7 % more per awake physics step; in exchange a 12-crate stack stands
without sleeping (it fell over before) and a 20-crate stack comes to rest. Solving each contact manifold as one block
(instead of point by point) and its friction at the centre removed the sideways creep of tall stacks and made the
awake step about a third faster (800 awake crates: 5.2 ms → 3.5 ms in the CI container). Sixty bodies on a
3,200-triangle mesh collider step in 0.9 ms; its BVH is only rebuilt when the mesh moves. Convex shapes cost more
than boxes: 400 awake bodies resting on a box step in 2.7 ms as boxes, 5.8 ms as cube-shaped convex hulls and 7.7 ms
as cylinders (CI container).

The post-processing passes (bloom, tone mapping, FXAA, OIT composite) each touch every pixel once; they are cheap on
a GPU but dominate on a software renderer. The sky's lighting is baked once (about 0.25 s on Mesa's llvmpipe, again
only when the sky's image or colours change); after that a sky costs one pass over the background pixels and three
texture reads per lit pixel, about 15 % of a frame on the software renderer. Local shadow maps are re-rendered only when their light or a caster in
range changed, so a scene with still lights and sleeping bodies renders its point and spot shadows once.

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
- Contacts with a triangle mesh are found per triangle (through the mesh's BVH) and cached per triangle, so warm
  starting works on meshes too. Joints are solved together with the contacts; their drift is corrected by moving the
  bodies in extra position rounds rather than by a velocity bias, so chains come to rest and sleep. Angle limits are
  one-sided speculative rows of the same solver (a body may swing up to a limit within the step, but not past it),
  corrected in the same position rounds, and warm-started like the rest. Continuous
  collision sweeps a sphere inside each fast body through the grid and stops it at the first hit.
- `Transform` matrix queries are allocation-free (per-thread scratch), and `Scene.components(Class)` is cached until
  entities or components change.

## Architecture

| Package | Purpose |
|---|---|
| `engine.core` | `Engine` (window, GL loop, lifecycle, pause, window options, screenshots, hot reloading), `Game` (your entry point), `GameState`/`GameStates`/`LevelState` (screens and levels), `FixedTimestep`, `EngineConfig`, `UserDirs`, `Settings` |
| `engine.ecs` | `Entity`, `Component`, `Transform` (hierarchy + interpolation + change tracking), `Scene` (deferred add/remove, cached queries), `ISystem` |
| `engine.render` | Backend-agnostic: `RenderBackend`, `RenderSettings`, `RenderQueue`, `GpuMesh`/`GpuTexture` handles, `Material`, `MeshRenderer` (+ levels of detail, `MeshSimplifier`), `ReflectionProbe`, `ParticleEmitter`/`ParticleCollider`, `Skin`, `Camera`, `Light`, `Sky`/`HdrImage`, `Overlay`/`UiCanvas`/`UiFont`, `FontAtlas`, `ShadowMath` |
| `engine.render.gl` | The OpenGL implementation of `RenderBackend` (shaders, instancing, skinning, shadow maps, occlusion queries, particles, sky and image-based lighting, post-processing, UI batching) |
| `engine.animation` | `Track`, `Easing`, `Channel`, `AnimationClip`, `Animator` (layers, blending), `AnimationSystem` |
| `engine.audio` | Backend-agnostic: `AudioBackend`, `AudioClip`/`AudioVoice` handles, `AudioSource`, `AudioListener`, `ImpactSound`, `AudioMixer`/`AudioBus`, `Rolloff`, `VoiceAllocator`, `AudioSystem`, `NullAudioBackend` |
| `engine.audio.al` | The OpenAL (JOAL) implementation of `AudioBackend` (buffers, sources, streaming) |
| `engine.serialization` | `Json`, `SceneSerializer`, `Prefab`, `ComponentRegistry`, `ComponentSerializer`/`FieldSerializer`, `SerializationContext` |
| `engine.ui` | `Gui`: immediate-mode widgets on any `UiCanvas`; `TextEdit` (text editing model), `Clipboard` (system or in-memory), `TextWrap` |
| `engine.resources` | `Assets` (asset directories, then the classpath), `AssetWatcher` (changed files), `ResourceManager` (cached meshes/textures/models/sounds, generated resources, keys for scene files, scopes that release a level's resources), `ObjLoader`, `MtlLoader`, `GltfLoader`/`GltfModel`, `ImageLoader` (ImageIO), `HdrLoader` (Radiance `.hdr`), `AudioLoader`/`VorbisDecoder`, `NormalMaps` (height field to normal map), `Model` |
| `engine.spatial` | `SpatialGrid` (used by physics, physics queries and static-geometry culling) |
| `engine.input` | `InputListener` (window thread), `InputState` (polling, key and gamepad bindings, mouse, typed text), `GamepadState`/`Gamepad` (layout, dead zones), `GamepadBackend`, `FirstPersonController` |
| `engine.input.jinput` | The JInput implementation of `GamepadBackend` |
| `engine.messaging` | Thread-safe message queues (`send` is immediate, `post` is delivered on the next update) |
| `engine.physics` | `Rigidbody`, `BoxCollider`, `SphereCollider`, `CapsuleCollider`, `CylinderCollider`, `ConvexHullCollider`, `MeshCollider`, `BallJoint`/`HingeJoint`/`DistanceJoint`, `Narrowphase`, `ShapeTests`, `ConvexTests` (GJK/EPA), `CharacterController`, `ShapeCast`, `PhysicsSystem` (+ ray, shape casts, overlaps, layers) |
| `engine.terrain` | `Heightfield` (heights, interpolation, normals, noise, images), `Terrain` (chunk meshes, levels, colliders) |
| `engine.navigation` | `NavGrid` (baked walkable grid, A*, path straightening, blocking), `NavAgent` (path following) |
| `engine.debug` | `DebugOverlay` (F3 text and frame graph), `Profiler` |
| `game` (demo module) | Demo scene (`DemoGame`, `Main`) |

Everything — fixed-step update, message dispatch, rendering — runs on the GL thread, so scene state needs no locks.
Only window events cross threads, via `InputState` and `IMessageService.post`.

The game, ECS, physics and resource code only see `RenderBackend`, `AudioBackend` and opaque handles; they never
touch GL or AL types. `Engine` itself is the JOGL platform layer (window, event loop) and picks the backends: OpenAL
if a sound device opens (else the silent `NullAudioBackend`), JInput for gamepads if it loads. Replacing OpenGL means
writing another `RenderBackend` and a platform class instead of `Engine`'s window code that feeds `InputState` (its
writers are public, gamepads included).

Writing a game: implement `Game.init`, build entities (`new Entity().add(new MeshRenderer(...))`), add them to
`engine.scene()` (effects: `entity.add(new ParticleEmitter())`, then set its fields), register extra `ISystem`s with
`engine.addSystem` and HUD code with `engine.addOverlay`. Entities with
a collider and no `Rigidbody` are static; add a `Rigidbody` to make them move, several colliders to give it a compound
shape, a joint (`link.add(new BallJoint()).setWorldAnchor(point)`, with `connected` set to the other entity or left
null for the world) to tie it to something; `setWorldAxis` plus `coneAngle`/`limitTwist` (ball) or `limit` (hinge)
bound how far it turns. For a sky, set `engine.backend().settings().sky = Sky.procedural()` (or
`Sky.image(HdrLoader.load(stream))`). Mark scenery that rarely or never moves with `MeshRenderer.setStaticGeometry(true)`; if it does move, the engine
notices at the next fixed step (call `invalidate()` to apply it in the same frame). For menus and tools, create a
`Gui` and call its widgets from an overlay; `engine.setClickCapturesMouse(() -> !gui.wantsMouse())` keeps clicks on
widgets from grabbing the mouse. Sound: `entity.add(new AudioSource(res.sound("audio/hum.ogg"))).playOnStart = true`,
or `engine.audio().play(clip, position)` for one-shots. Animation: `entity.add(new Animator()).play(clip)`, or
`res.gltf("models/hero.glb").instantiate(scene)` and play its clips by name. Saving:
`new SceneSerializer().save(scene, engine.serialization(), path)`; prefabs: `Prefab.load("prefabs/x.json", serializer)
.instantiate(scene, engine.serialization(), position, null)`.

## Known limitations

- Developed on Windows with an NVIDIA GPU; Linux is tested (CI runs the demo on Mesa's software OpenGL with GL error
  checking). macOS is untested.
- Mesh colliders are for static or kinematic geometry: two meshes never collide with each other, and a rigidbody with
  a mesh collider gets no mass from it (use a convex hull for dynamic bodies of that shape; concave bodies need a
  compound of hulls, there is no automatic decomposition). Cylinder edges are rounded by up to a centimetre in
  contacts, and a hull stops at its vertex cap (an inner approximation). Hinge limits are angles in -180..180 degrees
  (no multi-turn limits) and a ball joint's cone is circular. Continuous collision sweeps the largest sphere inside a
  body, so a fast box can still clip a corner, and it stops the body at the first hit instead of solving the rest of
  the step. A `CharacterController` stays upright (it turns only about Y), takes the ground's motion at the point it
  stands on but not its turn, and is seen by bodies as a static collider moved by code, so it pushes them only
  through `mass`, while bodies that run into it meet an immovable wall.
- Terrain chunks use one material (no splatting of several textures by slope or height) and are generated meshes
  without resource keys, so scene files leave them out: build the terrain again when loading. Its colliders are
  triangle meshes (no dedicated heightfield shape), which costs more memory than the heights themselves.
- Navigation grids are 2.5D: walkable heights are sampled at cell centres (a cell is walkable if its centre is and
  the agent fits there), so narrow gaps smaller than a cell may close, and jumps or drops are not links in the graph.
  Baking is synchronous and costs about a millisecond per 10 cells (1.6 s for the demo's 120 x 120 cells in the CI
  container); agents do not avoid each other.
- At most 8 lights per frame in a forward renderer; only the first directional light has cascaded shadows and at most
  4 point or spot lights cast shadows. Reflection probes need a sky (they replace its light), are captured once
  (or every `refreshFrames`), at most one per frame, see only opaque surfaces, and at most four apply per frame
  (the nearest to the camera); their diffuse light is the roughest reflection level, not a separate irradiance map.
  Ambient occlusion is screen-space only: it sees what is on screen and fades out from 30 to 60 m.
- Occlusion culling only skips static geometry, in whole grid cells, and is one frame late: an object coming out from
  behind a wall can appear a frame after it should. A moved static renderer is re-registered at the next fixed step,
  so it can be drawn at its old place for a frame unless `invalidate()` is called. Order-independent transparency is
  an approximation: overlapping layers of very different opacity blend a little differently from exact sorting.
- Lit particles take one light value per emitter (at its centre, without shadows) and cast no shadows; colliding
  particles cost a ray per particle and step. Particles are drawn after all other transparent
  surfaces, so a particle behind a glass pane is not dimmed by it. Alpha particles are sorted per emitter and the
  emitters by their centres, so two smoke clouds that overlap can sort wrongly.
- The UI has no input method support, and text areas do not wrap (only `Gui.paragraph` does).
- Sound: positioned sounds should be mono (OpenAL does not pan stereo clips). A streaming clip that loses its voice
  starts from the beginning when it gets one again (whole clips resume where their clock is). No effects (reverb,
  filters, occlusion). Impact sounds estimate the impulse from the body's change of velocity in the step the contact
  began. Without a sound device the ALSA library may print a few lines while OpenAL looks for one.
- Gamepads: JInput finds pads at start-up and on a rescan (window focus or `Engine.rescanGamepads()`), not the
  moment they are plugged in; buttons are mapped by name for Xbox-style pads and guessed for others. Rumble goes
  through JInput's rumblers, which many pads and platforms do not offer (`GamepadState.canRumble()`); gamepad axis
  bindings are not part of the saved text. On
  Linux the user needs read access to `/dev/input/event*`.
- Scene files keep resources by key: meshes and textures generated without a key (`res.mesh(data)`) are left out,
  and generated ones with a key must be generated again before loading. Animation channels with a custom target
  (code) are not saved, nor are one-shot sounds (`engine.audio().play`).
- glTF: no morph targets, sparse accessors, cameras, lights, texture transforms or occlusion maps; only the first uv
  set; the file's tangents are recomputed. Skinned meshes are culled with a generous sphere around their bind pose
  and are drawn one by one.
- Hot reloading covers shaders, textures and meshes loaded by path; textures derived from several files (bump maps
  converted to normal maps, combined metal/roughness, alpha masks), OBJ models with their materials, glTF models and
  sounds are not reloaded (listeners can rebuild them). On macOS the JDK polls for file changes, so they take a few
  seconds to show.
- MTL statements the renderer has no use for (`Ps`, `Pc`, `aniso`, reflection maps) and the texture options other than
  `-s -o -clamp -bm` are ignored; one uv transform is used for all of a material's maps.
