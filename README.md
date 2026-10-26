# EngineNamedScratch

A small, general-purpose Java game engine on OpenGL 3.3 core (JOGL), OpenAL (JOAL) for sound, JOML for math,
SLF4J/Logback for logging. It makes no assumptions about the kind of game: entities, components, systems, a scene,
rendering, physics, sound, animation, input and UI are building blocks you combine.

## Run

Requires JDK 25 and Maven.

```bash
mvn test                      # unit tests (no GPU needed)
mvn -q package                # build
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" game.Main     # use ';' instead of ':' on Windows
```

Demo controls: mouse look, `WASD`/arrows move, `Space`/`Shift` up/down, `F` throws a crate, `E` pushes the body
under the crosshair, `F3` toggles the on-screen debug text, `F5` saves the scene to `demo-scene.json`, `Esc` frees
the mouse and shows a settings panel with volume sliders (click outside it to look around again, `Tab` moves between
its widgets). With a gamepad: left stick moves, right stick looks, triggers fly up and down, `RB`/`X` throws, `A`
pushes.

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
| `engine.audio=false` | no sound (also automatic without a sound device) |
| `engine.gamepads=false` | do not look for gamepads |
| `demo.camera=x,y,z,yaw,pitch` | demo only: start somewhere else |
| `demo.saveScene=file.json` | demo only: save the scene at start (what `F5` does) |

The render settings can also be changed at run time through `engine.backend().settings()`.

Headless Linux works with a virtual X server and Mesa's software OpenGL; CI runs the demo like this on every push and
keeps the last frame as an artifact:

```bash
xvfb-run -a -s "-screen 0 1280x800x24" java -Dengine.debugGL=true -Dengine.exitAfterFrames=120 \
  -Dengine.captureMouse=false -Dengine.screenshot=smoke.png -cp "target/classes:$(cat cp.txt)" game.Main
```

## Features

- **Rendering:** GL3 core forward renderer into an HDR target, then bloom, ACES tone mapping and FXAA; linear
  lighting (colour textures and colours are sRGB, data maps are linear). GPU-instanced draw calls batched by (mesh,
  material), frustum culling (sphere, then box for long or flat objects), occlusion culling of static geometry, up to 8
  lights (directional, point and spot), up to 4 cascaded shadow maps with soft edges and cascade blending for the first
  shadow-casting directional light, shadows for up to 4 point or spot lights (a point light uses a cube, a spot light
  one map; maps whose light and casters did not change are reused), weighted blended order-independent transparency
  (or back-to-front sorting), alpha cut-outs (with matching holes in the shadows), points.
- **Materials:** Blinn-Phong or physically based (GGX, metallic/roughness, glTF-style metal-rough maps); colour or
  texture, tangent-space normal maps (meshes carry tangents with handedness, so mirrored uvs work), specular maps,
  emissive colour and maps, height maps with parallax occlusion mapping, uv scale/offset and clamping.
- **Models:** OBJ with MTL: `Ka Kd Ks Ke Ns Ni d Tr illum`, the PBR extension (`Pr Pm map_Pr map_Pm`), `map_Kd map_Ks
  map_Ke map_d`, `map_Bump`/`bump`/`norm` (normal maps, or grey bump maps that are converted), `disp`/`map_disp`
  (height), texture options `-s -o -clamp -bm`; one part per `usemtl`. glTF 2.0 (`.gltf` with embedded or external
  data, `.glb`): node hierarchy, meshes, metallic-roughness materials with base colour, metal-rough, normal and
  emissive textures, alpha modes, skins and animations (`ResourceManager.gltf(path).instantiate(scene)`). Generated
  meshes: cube, quad, sphere, points, and shape specs such as `cube:0.5` that scene files can refer to.
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
  (`SceneSerializer`); materials are shared by index and resources referred to by key; any component with public
  fields can opt in with one line (`FieldSerializer`), others with a `ComponentSerializer`. A `Prefab` is such a file
  used as a template, instantiated as often as needed (the instances share materials, so they still batch).
- **UI:** immediate-mode drawing with any number of fonts (any installed or logical Java font, proportional or fixed,
  glyphs rasterized on demand at the size they are drawn, so any character the font has works and scaled text stays
  sharp), clipping, and immediate-mode widgets (`engine.ui.Gui`): panels, labels, buttons, checkboxes, sliders,
  single- and multi-line text fields (cursor, selection, Home/End, arrows), scroll regions, rows; Tab/Shift+Tab
  focus navigation with Space/Enter to press.
- **Physics:** box, sphere and capsule colliders, triangle-mesh colliders for static or kinematic level geometry
  (BVH, one- or two-sided), compound bodies (several colliders on one rigidbody, with the combined centre of mass and
  inertia), rigidbodies (gravity, restitution, friction, rotation, kinematic bodies), ball, hinge (with motor) and
  distance (rod, rope, range) joints that can break, continuous collision for fast bodies (`Rigidbody.continuous`),
  multi-point contact manifolds solved as a block with friction at their centre, warm-started impulses and
  speculative contacts (40-crate stacks stand), moving platforms (kinematic or static colliders moved by code carry
  what rests on them), triggers, enter/exit `CollisionEvent`s, grid broadphase, sleeping, parented bodies,
  grid-accelerated `raycast` and `overlapSphere` queries (`syncQueries` makes them see colliders moved since the last
  step), `Camera.viewRay` for picking.
- **Loop:** fixed-timestep updates with render interpolation between the last two steps.
- **Input:** keys, mouse, text and up to four gamepads (JInput; radial stick and trigger dead zones, connect and
  disconnect), with actions bound to keys, gamepad buttons and gamepad axes at once; a first-person controller that
  takes both.
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
3,200-triangle mesh collider step in 0.9 ms; its BVH is only rebuilt when the mesh moves.

The post-processing passes (bloom, tone mapping, FXAA, OIT composite) each touch every pixel once; they are cheap on
a GPU but dominate on a software renderer. Local shadow maps are re-rendered only when their light or a caster in
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
  bodies in extra position rounds rather than by a velocity bias, so chains come to rest and sleep. Continuous
  collision sweeps a sphere inside each fast body through the grid and stops it at the first hit.
- `Transform` matrix queries are allocation-free (per-thread scratch), and `Scene.components(Class)` is cached until
  entities or components change.

## Architecture

| Package | Purpose |
|---|---|
| `engine.core` | `Engine` (window, GL loop, lifecycle, screenshots), `Game` (your entry point), `FixedTimestep`, `EngineConfig` |
| `engine.ecs` | `Entity`, `Component`, `Transform` (hierarchy + interpolation + change tracking), `Scene` (deferred add/remove, cached queries), `ISystem` |
| `engine.render` | Backend-agnostic: `RenderBackend`, `RenderSettings`, `RenderQueue`, `GpuMesh`/`GpuTexture` handles, `Material`, `MeshRenderer`, `Skin`, `Camera`, `Light`, `Overlay`/`UiCanvas`/`UiFont`, `FontAtlas`, `ShadowMath` |
| `engine.render.gl` | The OpenGL implementation of `RenderBackend` (shaders, instancing, skinning, shadow maps, occlusion queries, post-processing, UI batching) |
| `engine.animation` | `Track`, `Easing`, `Channel`, `AnimationClip`, `Animator` (layers, blending), `AnimationSystem` |
| `engine.audio` | Backend-agnostic: `AudioBackend`, `AudioClip`/`AudioVoice` handles, `AudioSource`, `AudioListener`, `ImpactSound`, `AudioMixer`/`AudioBus`, `Rolloff`, `VoiceAllocator`, `AudioSystem`, `NullAudioBackend` |
| `engine.audio.al` | The OpenAL (JOAL) implementation of `AudioBackend` (buffers, sources, streaming) |
| `engine.serialization` | `Json`, `SceneSerializer`, `Prefab`, `ComponentRegistry`, `ComponentSerializer`/`FieldSerializer`, `SerializationContext` |
| `engine.ui` | `Gui`: immediate-mode widgets on any `UiCanvas`; `TextEdit` (text editing model) |
| `engine.resources` | `ResourceManager` (cached meshes/textures/models/sounds, generated resources, keys for scene files), `ObjLoader`, `MtlLoader`, `GltfLoader`/`GltfModel`, `ImageLoader` (ImageIO), `AudioLoader`/`VorbisDecoder`, `NormalMaps` (height field to normal map), `Model` |
| `engine.spatial` | `SpatialGrid` (used by physics, physics queries and static-geometry culling) |
| `engine.input` | `InputListener` (window thread), `InputState` (polling, key and gamepad bindings, mouse, typed text), `GamepadState`/`Gamepad` (layout, dead zones), `GamepadBackend`, `FirstPersonController` |
| `engine.input.jinput` | The JInput implementation of `GamepadBackend` |
| `engine.messaging` | Thread-safe message queues (`send` is immediate, `post` is delivered on the next update) |
| `engine.physics` | `Rigidbody`, `BoxCollider`, `SphereCollider`, `CapsuleCollider`, `MeshCollider`, `BallJoint`/`HingeJoint`/`DistanceJoint`, `Narrowphase`, `ShapeTests`, `PhysicsSystem` (+ raycasts) |
| `engine.debug` | `DebugOverlay` |
| `game` | Demo scene (`DemoGame`, `Main`) |

Everything — fixed-step update, message dispatch, rendering — runs on the GL thread, so scene state needs no locks.
Only window events cross threads, via `InputState` and `IMessageService.post`.

The game, ECS, physics and resource code only see `RenderBackend`, `AudioBackend` and opaque handles; they never
touch GL or AL types. `Engine` itself is the JOGL platform layer (window, event loop) and picks the backends: OpenAL
if a sound device opens (else the silent `NullAudioBackend`), JInput for gamepads if it loads. Replacing OpenGL means
writing another `RenderBackend` and a platform class instead of `Engine`'s window code that feeds `InputState` (its
writers are public, gamepads included).

Writing a game: implement `Game.init`, build entities (`new Entity().add(new MeshRenderer(...))`), add them to
`engine.scene()`, register extra `ISystem`s with `engine.addSystem` and HUD code with `engine.addOverlay`. Entities with
a collider and no `Rigidbody` are static; add a `Rigidbody` to make them move, several colliders to give it a compound
shape, a joint (`link.add(new BallJoint()).setWorldAnchor(point)`, with `connected` set to the other entity or left
null for the world) to tie it to something. Mark scenery that rarely or never moves with `MeshRenderer.setStaticGeometry(true)`; if it does move, the engine
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
  a mesh collider gets no mass from it. There are no cylinder or convex-hull colliders. Joints have no angle or twist
  limits (hinges turn freely, ball joints have no cone). Continuous collision sweeps the largest sphere inside a body,
  so a fast box can still clip a corner, and it stops the body at the first hit instead of solving the rest of the
  step.
- At most 8 lights per frame in a forward renderer; only the first directional light has cascaded shadows and at most
  4 point or spot lights cast shadows. No image-based lighting, so metals only reflect the lights and the ambient
  colour (they look dark against the sky).
- Occlusion culling only skips static geometry, in whole grid cells, and is one frame late: an object coming out from
  behind a wall can appear a frame after it should. A moved static renderer is re-registered at the next fixed step,
  so it can be drawn at its old place for a frame unless `invalidate()` is called. Order-independent transparency is
  an approximation: overlapping layers of very different opacity blend a little differently from exact sorting.
- The UI has no clipboard, no word wrap and no input method support.
- Sound: positioned sounds should be mono (OpenAL does not pan stereo clips). A streaming clip that loses its voice
  starts from the beginning when it gets one again (whole clips resume where their clock is). No effects (reverb,
  filters, occlusion). Impact sounds estimate the impulse from the body's change of velocity in the step the contact
  began. Without a sound device the ALSA library may print a few lines while OpenAL looks for one.
- Gamepads: JInput finds pads at start-up and on a rescan (window focus or `Engine.rescanGamepads()`), not the
  moment they are plugged in; buttons are mapped by name for Xbox-style pads and guessed for others; no rumble. On
  Linux the user needs read access to `/dev/input/event*`.
- Scene files keep resources by key: meshes and textures generated without a key (`res.mesh(data)`) are left out,
  and generated ones with a key must be generated again before loading. Animators and skins are not saved (a loaded
  glTF instance stands still), nor is whether a sound is playing (use `playOnStart`).
- glTF: no morph targets, sparse accessors, cameras, lights, texture transforms or occlusion maps; only the first uv
  set; the file's tangents are recomputed. Skinned meshes are culled with a generous sphere around their bind pose
  and are drawn one by one.
- MTL statements the renderer has no use for (`Ps`, `Pc`, `aniso`, reflection maps) and the texture options other than
  `-s -o -clamp -bm` are ignored; one uv transform is used for all of a material's maps.
