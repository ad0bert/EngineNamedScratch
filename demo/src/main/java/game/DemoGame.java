package game;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.jogamp.newt.event.KeyEvent;

import engine.animation.AnimationClip;
import engine.animation.AnimationLayer;
import engine.animation.Animator;
import engine.animation.Channel;
import engine.animation.Easing;
import engine.animation.Track;
import engine.audio.AudioBus;
import engine.audio.AudioClip;
import engine.audio.AudioListener;
import engine.audio.AudioSource;
import engine.audio.ImpactSound;
import engine.core.Engine;
import engine.core.Game;
import engine.core.Settings;
import engine.ecs.Entity;
import engine.ecs.Spinner;
import engine.input.FirstPersonController;
import engine.input.Gamepad;
import engine.messaging.IMessageReceiver;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;
import engine.physics.BallJoint;
import engine.physics.BoxCollider;
import engine.physics.CapsuleCollider;
import engine.physics.CollisionEvent;
import engine.physics.HingeJoint;
import engine.physics.MeshCollider;
import engine.physics.Rigidbody;
import engine.physics.SphereCollider;
import engine.render.Camera;
import engine.render.GpuMesh;
import engine.render.Light;
import engine.render.Material;
import engine.render.MeshFactory;
import engine.render.MeshRenderer;
import engine.render.Sky;
import engine.render.UiCanvas;
import engine.resources.HdrLoader;
import engine.serialization.Prefab;
import engine.serialization.SceneSerializer;
import engine.ui.Gui;
import lombok.extern.slf4j.Slf4j;

/**
 * Sample scene exercising the engine: cascaded and point-light shadows, normal, height and specular maps, a cut-out
 * grate, instanced crates under physics, a turntable carrying crates, a compound body, an MTL-textured model,
 * transparency, points, a sprite, a jointed chain, a motorised hinge, a mesh-collider dome, synthesized sounds
 * (impacts, throws, a humming light, wind) and a HUD. Press F to throw a crate.
 */
@Slf4j
public class DemoGame implements Game {
  private GpuMesh crateMesh;
  private Material crateMaterial;
  private AudioClip thud;
  private AudioClip whoosh;
  private AudioClip click;
  private final SceneSerializer serializer = new SceneSerializer();
  private Animator dancer;
  private float danceTime;
  private boolean bending;
  private Entity player;
  private Entity turntable;
  private float turntableSpeed = 0.6f;
  private String aim = "";
  private Gui gui;
  private FirstPersonController controller;
  private String playerName = "Player";
  private String notes = "";
  /** Recent collision events of the player, newest last (shown in a scrolling list). */
  private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
  /** Window and volume choices, kept in the user's config directory between runs. */
  private Settings settings;

  @Override
  public void init(Engine engine) {
    final var res = engine.resources();
    final var scene = engine.scene();
    engine.input().bind("spawn", KeyEvent.VK_F);
    engine.input().bind("poke", KeyEvent.VK_E);
    engine.input().bind("save_scene", KeyEvent.VK_F5);
    engine.input().bind("pause", KeyEvent.VK_P);
    engine.input().bind("fullscreen", KeyEvent.VK_F11);
    engine.input().bindGamepad("pause", Gamepad.BUTTON_START);
    engine.input().bindGamepad("spawn", Gamepad.BUTTON_RIGHT_BUMPER, Gamepad.BUTTON_X);
    engine.input().bindGamepad("poke", Gamepad.BUTTON_A);
    this.thud = res.sound("gen:thud", ProceduralSounds.thud());
    this.whoosh = res.sound("gen:whoosh", ProceduralSounds.whoosh());
    this.click = res.sound("gen:click", ProceduralSounds.click());

    final var sun = new Entity("sun");
    sun.transform().setEulerDegrees(-50f, -30f, 0f);
    final var sunLight = sun.add(new Light());
    sunLight.shadowDistance = 70f; // three cascades reach 70 m from the camera
    scene.add(sun);
    setUpSky(engine);

    this.player = new Entity("player");
    this.player.transform().position().set(0f, 1.5f, 8f);
    this.player.add(new Camera());
    this.player.add(new AudioListener());
    final var controller = this.player.add(new FirstPersonController(engine.input()));
    this.controller = controller;
    // -Ddemo.camera=x,y,z,yaw,pitch starts somewhere else (handy for screenshots)
    final var start = System.getProperty("demo.camera");
    if (start != null) {
      final var v = start.split(",");
      this.player.transform().position().set(Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]));
      controller.setLook(v.length > 3 ? Float.parseFloat(v[3]) : 0f, v.length > 4 ? Float.parseFloat(v[4]) : 0f);
    }
    this.player.add(new SphereCollider(0.4f));
    final var playerBody = this.player.add(new Rigidbody());
    playerBody.kinematic = true;
    scene.add(this.player);

    final var ground = new Entity("ground");
    ground.transform().setEulerDegrees(-90f, 0f, 0f);
    ground.transform().position().set(0f, -1f, 0f);
    final var grass = new Material().color(0.35f, 0.55f, 0.3f, 1f);
    grass.specular = 0f;
    grass.normalMap = res.dataTexture("gen:ground-bumps", ProceduralTextures.bumps(256, 5f, 4L));
    grass.normalScale = 0.8f;
    ground.add(new MeshRenderer(res.mesh(MeshFactory.quad(60f, 60f, 24f, 24f)), grass)).setStaticGeometry(true);
    scene.add(ground);

    final var groundBody = new Entity("ground-collider");
    groundBody.transform().position().set(0f, -1.5f, 0f);
    groundBody.add(new BoxCollider(30f, 0.5f, 30f));
    scene.add(groundBody);

    final var cube = new Entity("cube");
    cube.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), new Material(res.texture("textures/cubeFace.png"))));
    // keyframed instead of a Spinner: it bobs (eased, back and forth) while it turns (slerped rotation keys)
    final var bob = Track.linear(new float[] { 0f, 1.6f }, new float[] { 0f, 0f, 0f, 0f, 0.6f, 0f }, 3)
        .withEasing(Easing.SINE_IN_OUT);
    final var turn = new AnimationClip("turn").add(Channel.transform("", Channel.Property.ROTATION,
        Track.linear(new float[] { 0f, 2f, 4f, 6f }, turnKeys(), 4)));
    final var cubeAnimator = cube.add(new Animator());
    cubeAnimator.play(turn);
    cubeAnimator.blend(new AnimationClip("bob").add(Channel.transform("", Channel.Property.POSITION, bob)), 1f,
        AnimationLayer.Loop.PING_PONG);
    cube.add(new BoxCollider(new Vector3f(1f)));
    scene.add(cube);

    final var capsuleModel = res.model("objects/capsule/capsule.obj");
    final var capsule = new Entity("capsule");
    capsule.transform().position().set(4f, 0f, 0f);
    capsuleModel.attachTo(capsule);
    capsule.add(new Spinner(0f, 40f, 0f));
    final var capsuleCollider = capsule.add(new BoxCollider(capsuleModel.halfExtents()));
    capsuleCollider.offset.set(capsuleModel.center());
    scene.add(capsule);

    final var boxModel = res.model("objects/box.obj");
    boxModel.parts().get(0).material().color(0.8f, 0.3f, 0.2f, 1f);
    final var box = new Entity("box");
    box.transform().position().set(-4f, 0f, 0f);
    boxModel.attachTo(box);
    box.components().forEach(c -> {
      if (c instanceof MeshRenderer r) {
        r.setStaticGeometry(true);
      }
    });
    box.add(new BoxCollider(boxModel.halfExtents()));
    scene.add(box);

    // a brick wall (generated in code) with normal, height (parallax) and specular maps; the orbiting light below
    // sweeps across its relief
    final var bricks = ProceduralTextures.bricks(256);
    final var brickMaterial = new Material(res.texture("gen:bricks-albedo", bricks.albedo()));
    brickMaterial.normalMap = res.dataTexture("gen:bricks-normal", bricks.normal());
    brickMaterial.heightMap = res.dataTexture("gen:bricks-height", bricks.height());
    brickMaterial.heightScale = 0.03f;
    brickMaterial.specularMap = res.dataTexture("gen:bricks-gloss", bricks.specular());
    brickMaterial.specular = 0.5f;
    final var wall = new Entity("wall");
    wall.transform().position().set(-6f, 0.5f, -2f);
    wall.transform().setEulerDegrees(0f, 90f, 0f); // the quad faces +Z; turn it to face +X, towards the scene
    wall.add(new MeshRenderer(res.mesh(MeshFactory.quad(6f, 3f, 3f, 1.5f)), brickMaterial)).setStaticGeometry(true);
    wall.add(new BoxCollider(3f, 1.5f, 0.15f));
    scene.add(wall);

    // hierarchy: a small glowing cube orbits through a pivot parent and carries a warm point light
    final var pivot = new Entity("pivot");
    pivot.transform().position().set(0f, 0.5f, 0f);
    pivot.add(new Spinner(0f, 60f, 0f));
    scene.add(pivot);
    final var moon = new Entity("moon");
    moon.transform().setParent(pivot.transform());
    moon.transform().position().set(3.5f, 0f, 0f);
    moon.transform().scale().set(0.3f);
    final var moonMaterial = new Material().color(1f, 0.85f, 0.4f, 1f);
    moonMaterial.emissive.set(1f, 0.8f, 0.4f); // glows on its own
    final var moonRenderer = moon.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), moonMaterial));
    moonRenderer.castShadows = false; // the light sits inside it
    // its glow pulses: a tween of a material colour through a custom channel
    moon.add(new Animator()).tween(v -> moonMaterial.emissive.set(v[0], v[1], v[2]), new float[] { 1f, 0.8f, 0.4f },
        new float[] { 3f, 2.2f, 0.9f }, 0.8f, Easing.QUAD_IN_OUT, AnimationLayer.Loop.PING_PONG);
    final var moonLight = moon.add(Light.point(1f, 0.6f, 0.2f, 1.6f, 9f));
    moonLight.castShadows = true; // the spinning cube throws a moving shadow
    // and it hums: a looping spatial sound that circles the listener (panning, Doppler)
    final var hum = moon.add(new AudioSource(res.sound("gen:hum", ProceduralSounds.hum())));
    hum.loop = true;
    hum.playOnStart = true;
    hum.volume = 0.35f;
    hum.minDistance = 1.5f;
    hum.maxDistance = 30f;
    scene.add(moon);

    // a quiet wind bed on the music bus, not positioned
    final var ambience = new Entity("ambience");
    final var wind = ambience.add(new AudioSource(res.sound("gen:wind", ProceduralSounds.wind())));
    wind.spatial = false;
    wind.loop = true;
    wind.bus = AudioBus.MUSIC;
    wind.volume = 0.4f;
    wind.priority = 10; // keeps its voice however many crates clatter
    wind.playOnStart = true;
    scene.add(ambience);

    // a cut-out grate: its holes are discarded in the scene and in the shadow maps
    final var grateMaterial = new Material(res.texture("gen:grate", ProceduralTextures.grate(128, 6)));
    grateMaterial.alphaCutoff = 0.5f;
    final var grate = new Entity("grate");
    grate.transform().position().set(6f, 0.5f, 3f);
    grate.transform().setEulerDegrees(0f, -30f, 0f);
    grate.add(new MeshRenderer(res.mesh(MeshFactory.quad(3f, 3f)), grateMaterial)).setStaticGeometry(true);
    scene.add(grate);

    final List<Vector3f> ring = new ArrayList<>();
    for (int i = 0; i < 48; i++) {
      final double a = i * Math.PI * 2 / 48;
      ring.add(new Vector3f((float) Math.cos(a) * 3f, 2.5f, (float) Math.sin(a) * 3f));
    }
    final var points = new Entity("points");
    points.add(new MeshRenderer(res.mesh(MeshFactory.points(ring)), new Material().color(1f, 0.9f, 0.1f, 1f).unlit()))
        .setStaticGeometry(true);
    scene.add(points);

    final var sprite = new Entity("sprite");
    sprite.transform().position().set(0f, 2f, -3f);
    final var spriteRenderer = sprite.add(new MeshRenderer(res.mesh(MeshFactory.quad(1f, 1f)),
        new Material(res.texture("textures/pointerIcon.png")).unlit().transparent()));
    spriteRenderer.castShadows = false;
    scene.add(sprite);

    final var glass = new Entity("glass");
    glass.transform().position().set(2.5f, 0f, 3f);
    glass.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), new Material().color(0.2f, 0.5f, 1f, 0.4f)))
        .castShadows = false;
    scene.add(glass);

    // many identical crates: one mesh + one material, so they render as a single instanced draw call
    this.crateMesh = res.shape("cube:0.5"); // a keyed shape, so saved scenes can refer to it
    this.crateMaterial = new Material(res.texture("textures/cubeFace.png"));
    for (int x = -3; x < 3; x++) {
      for (int z = 0; z < 6; z++) {
        for (int y = 0; y < 4; y++) {
          spawnCrate(engine, new Vector3f((x + 0.5f) * 1.15f + 0.03f * z, 1.5f + y * 1.3f, -17f + z * 1.15f),
              new Vector3f());
        }
      }
    }

    // physically based materials: rougher from left to right, the back row metallic (gold)
    final var sphereMesh = res.mesh(MeshFactory.sphere(0.35f, 32, 16));
    for (int i = 0; i < 5; i++) {
      for (int row = 0; row < 2; row++) {
        final var m = new Material();
        if (row == 0) {
          m.color(0.75f, 0.1f, 0.1f, 1f).pbr(0f, 0.1f + 0.2f * i);
        } else {
          m.color(1f, 0.78f, 0.34f, 1f).pbr(1f, 0.1f + 0.2f * i);
        }
        final var ball = new Entity("pbr-sphere");
        ball.transform().position().set(-2f + i, -0.6f, 4.5f - row);
        ball.add(new MeshRenderer(sphereMesh, m)).setStaticGeometry(true);
        scene.add(ball);
      }
    }

    // a spot light on a post, aimed at the wall; it casts its own shadows
    final var lamp = new Entity("spot");
    lamp.transform().position().set(-2.5f, 3.5f, 2f);
    lamp.transform().rotation().lookAlong(new Vector3f(-1f, -1.2f, -1.1f), new Vector3f(0f, 1f, 0f)).conjugate();
    lamp.add(Light.spot(1f, 0.85f, 0.6f, 4f, 14f, 28f));
    scene.add(lamp);

    // two transparent panes crossing each other: order-independent transparency blends them correctly
    final var paneMesh = res.mesh(MeshFactory.quad(2f, 1.6f));
    for (int i = 0; i < 2; i++) {
      final var pane = new Entity("pane");
      pane.transform().position().set(5f, 0f, 6f);
      pane.transform().setEulerDegrees(0f, i == 0 ? 35f : -35f, 0f);
      final var m = new Material().color(i == 0 ? 1f : 0.2f, i == 0 ? 0.3f : 0.9f, 0.3f, 0.45f);
      pane.add(new MeshRenderer(paneMesh, m)).castShadows = false;
      scene.add(pane);
    }

    // a kinematic turntable turned by code (see update): the crates on it ride along
    this.turntable = new Entity("turntable");
    this.turntable.transform().position().set(9f, -0.75f, -6f);
    this.turntable.transform().scale().set(2.5f, 0.25f, 2.5f);
    this.turntable.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), new Material().color(0.3f, 0.3f, 0.35f, 1f)));
    this.turntable.add(new BoxCollider(1f, 1f, 1f));
    this.turntable.add(new Rigidbody()).kinematic = true;
    scene.add(this.turntable);
    for (int i = 0; i < 3; i++) {
      spawnCrate(engine, new Vector3f(9f + 1.6f * (i - 1), 0f, -5f + (i == 1 ? -1.2f : 0f)), new Vector3f());
    }

    // a compound body: two spheres and a bar on one rigidbody, turning about their common centre of mass
    final var ballMesh = res.mesh(MeshFactory.sphere(1f, 24, 12));
    final var steel = new Material().color(0.75f, 0.75f, 0.8f, 1f);
    steel.specular = 0.8f;
    steel.shininess = 64f;
    final var dumbbell = new Entity("dumbbell");
    dumbbell.transform().position().set(-9f, 3f, 4f);
    dumbbell.transform().setEulerDegrees(0f, 30f, 20f);
    final var big = new Entity("dumbbell-big");
    big.transform().setParent(dumbbell.transform());
    big.transform().position().set(-1f, 0f, 0f);
    big.transform().scale().set(0.6f);
    big.add(new MeshRenderer(ballMesh, steel));
    final var small = new Entity("dumbbell-small");
    small.transform().setParent(dumbbell.transform());
    small.transform().position().set(1f, 0f, 0f);
    small.transform().scale().set(0.35f);
    small.add(new MeshRenderer(ballMesh, steel));
    final var bar = new Entity("dumbbell-bar");
    bar.transform().setParent(dumbbell.transform());
    bar.transform().scale().set(1f, 0.08f, 0.08f);
    bar.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), steel));
    dumbbell.add(new SphereCollider(0.6f)).offset.set(-1f, 0f, 0f);
    dumbbell.add(new SphereCollider(0.35f)).offset.set(1f, 0f, 0f);
    dumbbell.add(new BoxCollider(1f, 0.08f, 0.08f));
    dumbbell.add(new Rigidbody()).mass = 3f;
    scene.add(dumbbell);
    scene.add(big);
    scene.add(small);
    scene.add(bar);

    // a chain of capsules hanging from a fixed point by ball joints; push it with E
    final var linkMesh = res.mesh(MeshFactory.cube(1f));
    final var linkMaterial = new Material().color(0.85f, 0.85f, 0.9f, 1f).pbr(1f, 0.3f);
    Entity previous = null;
    for (int i = 0; i < 6; i++) {
      final var link = new Entity("chain-link");
      link.transform().position().set(-9f + 0.25f + i * 0.5f, 4.6f, -1f); // laid out level: it swings down
      link.transform().setEulerDegrees(0f, 0f, 90f);
      link.transform().scale().set(0.12f, 0.5f, 0.12f);
      link.add(new MeshRenderer(linkMesh, linkMaterial));
      link.add(new CapsuleCollider(0.5f, 0.5f));
      link.add(new Rigidbody()).linearDamping = 0.3f;
      scene.add(link);
      final var joint = link.add(new BallJoint());
      joint.connected = previous;
      joint.setWorldAnchor(new Vector3f(-9f + i * 0.5f, 4.6f, -1f));
      previous = link;
    }

    // a paddle turned by a hinge motor: it sweeps whatever lies in its way
    final var paddle = new Entity("paddle");
    paddle.transform().position().set(9f, -0.3f, 2f);
    paddle.transform().scale().set(2.4f, 0.5f, 0.15f);
    paddle.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), new Material().color(0.9f, 0.6f, 0.1f, 1f)));
    paddle.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    paddle.add(new Rigidbody()).mass = 4f;
    scene.add(paddle);
    final var hinge = paddle.add(new HingeJoint());
    hinge.setWorldAnchor(new Vector3f(9f, -0.3f, 2f));
    hinge.setWorldAxis(new Vector3f(0f, 1f, 0f));
    hinge.motor = true;
    hinge.motorSpeed = 1.2f;
    hinge.maxMotorTorque = 60f;

    // a dome with a triangle-mesh collider: thrown crates roll and slide off its faceted surface
    final var domeMesh = MeshFactory.sphere(3f, 20, 10);
    final var dome = new Entity("dome");
    dome.transform().position().set(10f, -2.4f, 9f);
    dome.add(new MeshRenderer(res.mesh(domeMesh), new Material().color(0.45f, 0.5f, 0.6f, 1f).pbr(0f, 0.6f)))
        .setStaticGeometry(true);
    dome.add(new MeshCollider(domeMesh));
    scene.add(dome);

    // two skinned, animated tubes from a glTF file: one bends, the other cross-fades between twisting and bending
    // (see update) with a cubic-spline pulse blended in at half weight
    final var tube = res.gltf("models/tube.glb");
    final var bender = tube.instantiate(scene);
    bender.transform().position().set(-2.6f, -1f, 1.2f);
    bender.get(Animator.class).play("bend", AnimationLayer.Loop.LOOP);
    final var dancer = tube.instantiate(scene);
    dancer.transform().position().set(2.6f, -1f, 1.2f);
    this.dancer = dancer.get(Animator.class);
    this.dancer.play("twist", AnimationLayer.Loop.LOOP);
    this.dancer.blend(this.dancer.clip("pulse"), 0.5f, AnimationLayer.Loop.PING_PONG);

    // lamp posts from a prefab file (prefabs/lamp-post.json): three instances sharing their materials
    try {
      final var lampPost = Prefab.load("prefabs/lamp-post.json", this.serializer);
      final var ctx = engine.serialization();
      lampPost.instantiate(scene, ctx, new Vector3f(-3.5f, -1f, -4f), null);
      lampPost.instantiate(scene, ctx, new Vector3f(3.5f, -1f, -4f), null);
      lampPost.instantiate(scene, ctx, new Vector3f(0f, -1f, -9f), null);
    } catch (IOException | IllegalArgumentException e) {
      log.warn("Could not create the lamp posts: {}", e.getMessage());
    }

    // static scenery: cached matrices and grid-based culling; only the pillars near the view cost anything
    final var pillarMesh = res.mesh(MeshFactory.cube(0.5f));
    final var stone = new Material().color(0.6f, 0.58f, 0.55f, 1f);
    for (int x = -7; x <= 7; x++) {
      for (int z = -7; z <= 7; z++) {
        if (Math.abs(x) < 4 && z > -6 && z < 4) {
          continue; // keep the play area free
        }
        final var pillar = new Entity("pillar");
        pillar.transform().position().set(x * 4f, 0f + ((x * 7 + z * 13) & 3) * 0.5f, z * 4f);
        pillar.transform().scale().set(1f, 4f + ((x * 3 + z * 5) & 3), 1f);
        pillar.add(new MeshRenderer(pillarMesh, stone)).setStaticGeometry(true);
        pillar.add(new BoxCollider(0.5f, 0.5f, 0.5f));
        scene.add(pillar);
      }
    }

    engine.messages().register(new IMessageReceiver() {
      @Override
      public void receiveMessage(MessageObject m) {
        if (m.getPayload() instanceof CollisionEvent c && (c.a() == DemoGame.this.player
            || c.b() == DemoGame.this.player)) {
          log.debug("player collision {}: {} <-> {}", c.started() ? "start" : "end", c.a(), c.b());
          final var other = c.a() == DemoGame.this.player ? c.b() : c.a();
          DemoGame.this.events.addLast((c.started() ? "touched " : "left ") + other.name());
          if (DemoGame.this.events.size() > 50) {
            DemoGame.this.events.removeFirst();
          }
        }
      }
    }, MessageQueue.COLLISION);

    engine.addOverlay((ui, width, height) -> drawHud(engine, ui, width, height));
    if (System.getProperty("demo.saveScene") != null) { // -Ddemo.saveScene=file.json: what F5 does, at start
      saveScene(engine, Path.of(System.getProperty("demo.saveScene")));
    }

    loadSettings(engine);
    if (Boolean.getBoolean("demo.paused")) { // -Ddemo.paused=true: start in the pause menu (screenshots)
      engine.states().push(new PauseState());
    }

    // a settings panel while the mouse is free (Esc); clicks on it do not recapture the mouse
    this.gui = new Gui(engine.input());
    engine.setClickCapturesMouse(() -> !this.gui.wantsMouse());
    engine.addOverlay((ui, width, height) -> drawPanel(engine, ui, width));
  }

  private void loadSettings(Engine engine) {
    try {
      this.settings = Settings.load(engine.userDirs().config().resolve("settings.properties"));
    } catch (IOException e) {
      log.warn("No settings directory: {}", e.getMessage());
      this.settings = Settings.load(Path.of("demo-settings.properties"));
    }
    engine.setVsync(this.settings.getBoolean("video.vsync", engine.isVsync()));
    if (this.settings.getBoolean("video.fullscreen", engine.isFullscreen()) != engine.isFullscreen()) {
      engine.setFullscreen(!engine.isFullscreen());
    }
    final var mixer = engine.audio().mixer();
    for (AudioBus bus : AudioBus.values()) {
      mixer.setVolume(bus, this.settings.getFloat("audio." + bus.name().toLowerCase(Locale.ROOT), mixer.volume(bus)));
    }
  }

  @Override
  public void shutdown(Engine engine) {
    this.settings.set("video.vsync", engine.isVsync()).set("video.fullscreen", engine.isFullscreen());
    final var mixer = engine.audio().mixer();
    for (AudioBus bus : AudioBus.values()) {
      this.settings.set("audio." + bus.name().toLowerCase(Locale.ROOT), mixer.volume(bus));
    }
    try {
      this.settings.save();
    } catch (IOException e) {
      log.warn("Could not save the settings to {}: {}", this.settings.file(), e.getMessage());
    }
  }

  /**
   * A sky that lights the scene (the metal spheres reflect it): procedural by default, {@code -Ddemo.sky=file.hdr}
   * for an HDR environment image, {@code -Ddemo.sky=none} for the plain background colour and flat ambient light.
   */
  private static void setUpSky(Engine engine) {
    final var choice = System.getProperty("demo.sky", "procedural");
    final var settings = engine.backend().settings();
    if (choice.equals("none")) {
      return;
    }
    settings.sky = Sky.procedural();
    if (!choice.equals("procedural")) {
      try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(choice))) {
        settings.sky = Sky.image(HdrLoader.load(in));
      } catch (java.io.IOException e) {
        log.warn("cannot read sky {}: {}; using the procedural sky", choice, e.toString());
      }
    }
  }

  private void drawPanel(Engine engine, UiCanvas ui, int width) {
    final boolean free = !engine.isMouseCaptured();
    this.gui.begin(ui, width, 0, free);
    if (free) {
      final var settings = engine.backend().settings();
      final var g = this.gui;
      g.beginPanel("Demo settings", width - 290f, 10f, 280f);
      g.label("Grüße, " + this.playerName + "! – Ünïcödé ½ € ✓");
      g.row(2);
      if (g.button("Throw crate")) {
        final var t = this.player.transform();
        final var forward = t.forward(new Vector3f());
        spawnCrate(engine, t.position().fma(1.5f, forward, new Vector3f()), forward.mul(10f));
        engine.audio().play2D(this.click, 0.6f, AudioBus.UI);
      }
      if (g.button("Wake all")) {
        engine.scene().components(Rigidbody.class).forEach(Rigidbody::wakeUp);
        engine.audio().play2D(this.click, 0.6f, AudioBus.UI);
      }
      settings.occlusionCulling = g.checkbox("Occlusion culling", settings.occlusionCulling);
      settings.maxPointShadows = g.checkbox("Point light shadows", settings.maxPointShadows > 0) ? 4 : 0;
      settings.debugCascades = g.checkbox("Tint shadow cascades", settings.debugCascades);
      g.row(2);
      settings.antiAliasing = g.checkbox("FXAA", settings.antiAliasing);
      settings.orderIndependentTransparency = g.checkbox("OIT", settings.orderIndependentTransparency);
      g.row(2);
      if (g.checkbox("Fullscreen", engine.isFullscreen()) != engine.isFullscreen()) {
        engine.setFullscreen(!engine.isFullscreen());
      }
      engine.setVsync(g.checkbox("VSync", engine.isVsync()));
      settings.exposure = g.slider("Exposure", settings.exposure, 0.2f, 3f);
      settings.bloomStrength = g.slider("Bloom", settings.bloomStrength, 0f, 0.5f);
      settings.skyIntensity = g.slider("Sky light", settings.skyIntensity, 0f, 3f);
      g.space(4f);
      final var mixer = engine.audio().mixer();
      g.row(2);
      mixer.setVolume(AudioBus.MASTER, g.slider("Volume", mixer.volume(AudioBus.MASTER), 0f, 1f));
      mixer.setVolume(AudioBus.MUSIC, g.slider("Wind", mixer.volume(AudioBus.MUSIC), 0f, 1f));
      g.row(2);
      mixer.setVolume(AudioBus.EFFECTS, g.slider("Effects", mixer.volume(AudioBus.EFFECTS), 0f, 1f));
      mixer.setVolume(AudioBus.UI, g.slider("UI", mixer.volume(AudioBus.UI), 0f, 1f));
      g.row(2);
      engine.physics().gravity.y = -g.slider("Gravity", -engine.physics().gravity.y, 0f, 20f);
      this.turntableSpeed = g.slider("Turntable", this.turntableSpeed, -3f, 3f);
      this.playerName = g.textField("Name", this.playerName);
      this.notes = g.textArea("Notes (Enter for a new line)##notes", this.notes, 2);
      g.label("Player collisions:");
      g.beginScroll("log", 70f);
      if (this.events.isEmpty()) {
        g.label("(walk into something)");
      }
      for (var line : this.events) {
        g.label(line);
      }
      g.endScroll();
      g.endPanel();
    }
    this.gui.end();
  }

  private void spawnCrate(Engine engine, Vector3f position, Vector3f velocity) {
    final var crate = new Entity("crate");
    crate.transform().position().set(position);
    crate.transform().scale().set(0.8f);
    crate.add(new MeshRenderer(this.crateMesh, this.crateMaterial));
    crate.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    final var body = crate.add(new Rigidbody());
    crate.add(new ImpactSound(this.thud)).volume = 0.8f; // louder for harder hits
    body.velocity.set(velocity);
    body.continuous = velocity.lengthSquared() > 0f; // thrown crates do not tunnel through thin walls
    engine.scene().add(crate);
  }

  /** Quaternions (x, y, z, w) turning a full circle about a tilted axis in three steps. */
  private static float[] turnKeys() {
    final var axis = new Vector3f(0.3f, 1f, 0.2f).normalize();
    final float[] keys = new float[16];
    final var q = new Quaternionf();
    for (int i = 0; i < 4; i++) {
      q.fromAxisAngleRad(axis, (float) (i * 2 * Math.PI / 3));
      keys[i * 4] = q.x;
      keys[i * 4 + 1] = q.y;
      keys[i * 4 + 2] = q.z;
      keys[i * 4 + 3] = q.w;
    }
    return keys;
  }

  private void saveScene(Engine engine, Path file) {
    try {
      this.serializer.save(engine.scene(), engine.serialization(), file);
      log.info("Saved the scene to {}", file.toAbsolutePath());
    } catch (IOException e) {
      log.warn("Could not save the scene: {}", e.getMessage());
    }
  }

  @Override
  public void update(Engine engine, double dt) {
    if (engine.input().wasActionPressed("fullscreen")) {
      engine.setFullscreen(!engine.isFullscreen());
    }
    if (engine.isPaused()) {
      return; // the pause state handles its own input; nothing in the world moves
    }
    if (engine.input().wasActionPressed("pause")) {
      engine.states().push(new PauseState());
      return;
    }
    this.turntable.transform().rotation().rotateY((float) dt * this.turntableSpeed);
    this.danceTime += (float) dt;
    if (this.danceTime > 3f) { // every three seconds the right tube changes its dance, blending over half a second
      this.danceTime = 0f;
      this.bending = !this.bending;
      final var pulse = this.dancer.layers().get(this.dancer.layers().size() - 1);
      this.dancer.crossFade(this.dancer.clip(this.bending ? "bend" : "twist"), 0.5f, AnimationLayer.Loop.LOOP);
      this.dancer.remove(pulse); // keep the pulse on top, at its weight
      this.dancer.blend(this.dancer.clip("pulse"), 0.5f, AnimationLayer.Loop.PING_PONG);
    }
    this.controller.setEnabled(!this.gui.wantsKeyboard()); // typing a name must not walk around
    final var t = this.player.transform();
    final var forward = t.forward(new Vector3f());
    if (engine.input().wasActionPressed("save_scene")) { // F5: everything with a serializer, as JSON
      saveScene(engine, Path.of("demo-scene.json"));
    }
    if (engine.input().wasActionPressed("spawn")) {
      spawnCrate(engine, t.position().fma(1.5f, forward, new Vector3f()), new Vector3f(forward).mul(18f));
      engine.audio().play(this.whoosh, t.position().fma(0.8f, forward, new Vector3f()), 0.7f, 1f, AudioBus.EFFECTS, 1);
    }

    // what is the crosshair pointing at? E pushes it (wakes sleeping bodies)
    final var origin = t.position();
    final var hit = engine.physics().raycast(engine.scene(), origin, forward, 60f,
        c -> c.entity() != this.player && !c.trigger);
    if (hit.isPresent()) {
      this.aim = String.format(Locale.ROOT, "%s  %.1f m", hit.get().entity().name(), hit.get().distance());
      if (engine.input().wasActionPressed("poke")) {
        hit.get().entity().find(Rigidbody.class).ifPresent(body -> {
          body.wakeUp();
          body.velocity.fma(8f, forward);
        });
      }
    } else {
      this.aim = "";
    }
  }

  private void drawHud(Engine engine, UiCanvas ui, int width, int height) {
    final float cx = width / 2f;
    final float cy = height / 2f;
    ui.drawRect(cx - 8, cy - 1, 16, 2, 1f, 1f, 1f, 0.8f);
    ui.drawRect(cx - 1, cy - 8, 2, 16, 1f, 1f, 1f, 0.8f);
    if (!this.aim.isEmpty()) { // a little larger than the rest: scaled text is rasterized at its size, so it stays sharp
      ui.drawText(this.aim, cx + 15f, cy + 9f, 1.25f, 0f, 0f, 0f, 0.8f);
      ui.drawText(this.aim, cx + 14f, cy + 8f, 1.25f, 1f, 1f, 1f, 1f);
    }
    final var help = "WASD move  Space/Shift up/down  F throw crate  E push  F3 debug  F5 save scene  P pause  F11 fullscreen  Esc settings"
        + (engine.input().firstGamepad() != null ? "  Pad: sticks, triggers, RB throw, A push" : "");
    ui.drawText(help, 11f, height - ui.lineHeight(1f) - 9f, 1f, 0f, 0f, 0f, 0.8f);
    ui.drawText(help, 10f, height - ui.lineHeight(1f) - 10f, 1f, 1f, 1f, 1f, 1f);
  }
}
