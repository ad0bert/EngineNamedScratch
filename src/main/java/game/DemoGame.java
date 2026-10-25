package game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Vector3f;

import com.jogamp.newt.event.KeyEvent;

import engine.core.Engine;
import engine.core.Game;
import engine.ecs.Entity;
import engine.ecs.Spinner;
import engine.input.FirstPersonController;
import engine.messaging.IMessageReceiver;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;
import engine.physics.BoxCollider;
import engine.physics.CollisionEvent;
import engine.physics.Rigidbody;
import engine.physics.SphereCollider;
import engine.render.Camera;
import engine.render.GpuMesh;
import engine.render.Light;
import engine.render.Material;
import engine.render.MeshFactory;
import engine.render.MeshRenderer;
import engine.render.UiCanvas;
import lombok.extern.slf4j.Slf4j;

/**
 * Sample scene exercising the engine: shadows, two light types, instanced crates under physics, an MTL-textured model,
 * transparency, points, a sprite and a HUD. Press F to throw a crate.
 */
@Slf4j
public class DemoGame implements Game {
  private GpuMesh crateMesh;
  private Material crateMaterial;
  private Entity player;
  private String aim = "";

  @Override
  public void init(Engine engine) {
    final var res = engine.resources();
    final var scene = engine.scene();
    engine.input().bind("spawn", KeyEvent.VK_F);
    engine.input().bind("poke", KeyEvent.VK_E);

    final Entity sun = new Entity("sun");
    sun.transform().setEulerDegrees(-50f, -30f, 0f);
    sun.add(new Light());
    scene.add(sun);

    this.player = new Entity("player");
    this.player.transform().position().set(0f, 1.5f, 8f);
    this.player.add(new Camera());
    this.player.add(new FirstPersonController(engine.input()));
    this.player.add(new SphereCollider(0.4f));
    final Rigidbody playerBody = this.player.add(new Rigidbody());
    playerBody.kinematic = true;
    scene.add(this.player);

    final Entity ground = new Entity("ground");
    ground.transform().setEulerDegrees(-90f, 0f, 0f);
    ground.transform().position().set(0f, -1f, 0f);
    final Material grass = new Material().color(0.35f, 0.55f, 0.3f, 1f);
    grass.specular = 0f;
    ground.add(new MeshRenderer(res.mesh(MeshFactory.quad(60f, 60f)), grass)).setStaticGeometry(true);
    scene.add(ground);

    final Entity groundBody = new Entity("ground-collider");
    groundBody.transform().position().set(0f, -1.5f, 0f);
    groundBody.add(new BoxCollider(30f, 0.5f, 30f));
    scene.add(groundBody);

    final Entity cube = new Entity("cube");
    cube.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), new Material(res.texture("textures/cubeFace.png"))));
    cube.add(new Spinner(20f, 35f, 10f));
    cube.add(new BoxCollider(new Vector3f(1f)));
    scene.add(cube);

    final var capsuleModel = res.model("objects/capsule/capsule.obj");
    final Entity capsule = new Entity("capsule");
    capsule.transform().position().set(4f, 0f, 0f);
    capsuleModel.attachTo(capsule);
    capsule.add(new Spinner(0f, 40f, 0f));
    final BoxCollider capsuleCollider = capsule.add(new BoxCollider(capsuleModel.halfExtents()));
    capsuleCollider.offset.set(capsuleModel.center());
    scene.add(capsule);

    final var boxModel = res.model("objects/box.obj");
    boxModel.parts().get(0).material().color(0.8f, 0.3f, 0.2f, 1f);
    final Entity box = new Entity("box");
    box.transform().position().set(-4f, 0f, 0f);
    boxModel.attachTo(box);
    box.components().forEach(c -> {
      if (c instanceof MeshRenderer r) {
        r.setStaticGeometry(true);
      }
    });
    box.add(new BoxCollider(boxModel.halfExtents()));
    scene.add(box);

    // hierarchy: a small glowing cube orbits through a pivot parent and carries a warm point light
    final Entity pivot = new Entity("pivot");
    pivot.transform().position().set(0f, 0.5f, 0f);
    pivot.add(new Spinner(0f, 60f, 0f));
    scene.add(pivot);
    final Entity moon = new Entity("moon");
    moon.transform().setParent(pivot.transform());
    moon.transform().position().set(3.5f, 0f, 0f);
    moon.transform().scale().set(0.3f);
    final Material moonMaterial = new Material().color(1f, 0.85f, 0.4f, 1f).unlit();
    final MeshRenderer moonRenderer = moon.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), moonMaterial));
    moonRenderer.castShadows = false;
    moon.add(Light.point(1f, 0.6f, 0.2f, 1.1f, 8f));
    scene.add(moon);

    final List<Vector3f> ring = new ArrayList<>();
    for (int i = 0; i < 48; i++) {
      final double a = i * Math.PI * 2 / 48;
      ring.add(new Vector3f((float) Math.cos(a) * 3f, 2.5f, (float) Math.sin(a) * 3f));
    }
    final Entity points = new Entity("points");
    points.add(new MeshRenderer(res.mesh(MeshFactory.points(ring)), new Material().color(1f, 0.9f, 0.1f, 1f).unlit()))
        .setStaticGeometry(true);
    scene.add(points);

    final Entity sprite = new Entity("sprite");
    sprite.transform().position().set(0f, 2f, -3f);
    final MeshRenderer spriteRenderer = sprite.add(new MeshRenderer(res.mesh(MeshFactory.quad(1f, 1f)),
        new Material(res.texture("textures/pointerIcon.png")).unlit().transparent()));
    spriteRenderer.castShadows = false;
    scene.add(sprite);

    final Entity glass = new Entity("glass");
    glass.transform().position().set(2.5f, 0f, 3f);
    glass.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), new Material().color(0.2f, 0.5f, 1f, 0.4f)))
        .castShadows = false;
    scene.add(glass);

    // many identical crates: one mesh + one material, so they render as a single instanced draw call
    this.crateMesh = res.mesh(MeshFactory.cube(0.5f));
    this.crateMaterial = new Material(res.texture("textures/cubeFace.png"));
    for (int x = -3; x < 3; x++) {
      for (int z = 0; z < 6; z++) {
        for (int y = 0; y < 4; y++) {
          spawnCrate(engine, new Vector3f((x + 0.5f) * 1.15f + 0.03f * z, 1.5f + y * 1.3f, -17f + z * 1.15f),
              new Vector3f());
        }
      }
    }

    // static scenery: cached matrices and grid-based culling; only the pillars near the view cost anything
    final GpuMesh pillarMesh = res.mesh(MeshFactory.cube(0.5f));
    final Material stone = new Material().color(0.6f, 0.58f, 0.55f, 1f);
    for (int x = -7; x <= 7; x++) {
      for (int z = -7; z <= 7; z++) {
        if (Math.abs(x) < 4 && z > -6 && z < 4) {
          continue; // keep the play area free
        }
        final Entity pillar = new Entity("pillar");
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
        }
      }
    }, MessageQueue.COLLISION);

    engine.addOverlay(this::drawHud);
  }

  private void spawnCrate(Engine engine, Vector3f position, Vector3f velocity) {
    final Entity crate = new Entity("crate");
    crate.transform().position().set(position);
    crate.transform().scale().set(0.8f);
    crate.add(new MeshRenderer(this.crateMesh, this.crateMaterial));
    crate.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    final Rigidbody body = crate.add(new Rigidbody());
    body.velocity.set(velocity);
    engine.scene().add(crate);
  }

  @Override
  public void update(Engine engine, double dt) {
    final var t = this.player.transform();
    final Vector3f forward = t.forward(new Vector3f());
    if (engine.input().wasActionPressed("spawn")) {
      spawnCrate(engine, t.position().fma(1.5f, forward, new Vector3f()), new Vector3f(forward).mul(10f));
    }

    // what is the crosshair pointing at? E pushes it (wakes sleeping bodies)
    final Vector3f origin = t.position();
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

  private void drawHud(UiCanvas ui, int width, int height) {
    final float cx = width / 2f;
    final float cy = height / 2f;
    ui.drawRect(cx - 8, cy - 1, 16, 2, 1f, 1f, 1f, 0.8f);
    ui.drawRect(cx - 1, cy - 8, 2, 16, 1f, 1f, 1f, 0.8f);
    if (!this.aim.isEmpty()) {
      ui.drawText(this.aim, cx + 15f, cy + 9f, 1f, 0f, 0f, 0f, 0.8f);
      ui.drawText(this.aim, cx + 14f, cy + 8f, 1f, 1f, 1f, 1f, 1f);
    }
    final String help = "WASD move  Space/Shift up/down  F throw crate  E push  F3 debug  Esc free mouse";
    ui.drawText(help, 11f, height - ui.lineHeight(1f) - 9f, 1f, 0f, 0f, 0f, 0.8f);
    ui.drawText(help, 10f, height - ui.lineHeight(1f) - 10f, 1f, 1f, 1f, 1f, 1f);
  }
}
