package game;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import engine.core.Engine;
import engine.core.Game;
import engine.ecs.Entity;
import engine.ecs.Spinner;
import engine.input.FirstPersonController;
import engine.messaging.IMessageReceiver;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;
import engine.physics.AabbCollider;
import engine.physics.CollisionEvent;
import engine.render.Camera;
import engine.render.Light;
import engine.render.Material;
import engine.render.MeshData;
import engine.render.MeshFactory;
import engine.render.MeshRenderer;
import lombok.extern.slf4j.Slf4j;

/** Sample scene exercising the engine: lit/textured meshes, OBJ models, points, a sprite and collisions. */
@Slf4j
public class DemoGame implements Game {

  @Override
  public void init(Engine engine) {
    final var res = engine.resources();
    final var scene = engine.scene();

    final Entity sun = new Entity("sun");
    sun.transform().setEulerDegrees(-50f, -30f, 0f);
    sun.add(new Light());
    scene.add(sun);

    final Entity player = new Entity("player");
    player.transform().position().set(0f, 1.5f, 7f);
    player.add(new Camera());
    player.add(new FirstPersonController(engine.input()));
    player.add(new AabbCollider(new Vector3f(0.3f)));
    scene.add(player);

    final Entity ground = new Entity("ground");
    ground.transform().setEulerDegrees(-90f, 0f, 0f);
    ground.transform().position().set(0f, -1f, 0f);
    ground.add(new MeshRenderer(res.mesh(MeshFactory.quad(40f, 40f)), new Material().color(0.35f, 0.55f, 0.3f, 1f)));
    scene.add(ground);

    final Entity cube = new Entity("cube");
    cube.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), new Material(res.texture("textures/cubeFace.png"))));
    cube.add(new Spinner(20f, 35f, 10f));
    cube.add(new AabbCollider(new Vector3f(1f)));
    scene.add(cube);

    final var capsuleMesh = res.mesh("objects/capsule/capsule.obj");
    final Entity capsule = new Entity("capsule");
    capsule.transform().position().set(4f, 0f, 0f);
    capsule.add(new MeshRenderer(capsuleMesh, new Material(res.texture("objects/capsule/capsule0.jpg"))));
    capsule.add(new Spinner(0f, 40f, 0f));
    capsule.add(new AabbCollider(capsuleMesh.data().halfExtents()));
    scene.add(capsule);

    final var boxMesh = res.mesh("objects/box.obj");
    final Entity box = new Entity("box");
    box.transform().position().set(-4f, 0f, 0f);
    box.add(new MeshRenderer(boxMesh, new Material().color(0.8f, 0.3f, 0.2f, 1f)));
    box.add(new AabbCollider(boxMesh.data().halfExtents()));
    scene.add(box);

    // hierarchy: a small cube orbiting the big one through a pivot parent
    final Entity pivot = new Entity("pivot");
    pivot.add(new Spinner(0f, 60f, 0f));
    scene.add(pivot);
    final Entity moon = new Entity("moon");
    moon.transform().setParent(pivot.transform());
    moon.transform().position().set(2.5f, 0f, 0f);
    moon.transform().scale().set(0.3f);
    moon.add(new MeshRenderer(res.mesh(MeshFactory.cube(1f)), new Material().color(0.9f, 0.9f, 0.2f, 1f)));
    scene.add(moon);

    final List<Vector3f> ring = new ArrayList<>();
    for (int i = 0; i < 48; i++) {
      final double a = i * Math.PI * 2 / 48;
      ring.add(new Vector3f((float) Math.cos(a) * 3f, 2.5f, (float) Math.sin(a) * 3f));
    }
    final MeshData pointData = MeshFactory.points(ring);
    final Entity points = new Entity("points");
    points.add(new MeshRenderer(res.mesh(pointData), new Material().color(1f, 0.9f, 0.1f, 1f).unlit()));
    scene.add(points);

    final Entity sprite = new Entity("sprite");
    sprite.transform().position().set(0f, 2f, -3f);
    final Material spriteMat = new Material(res.texture("textures/pointerIcon.png")).unlit();
    sprite.add(new MeshRenderer(res.mesh(MeshFactory.quad(1f, 1f)), spriteMat));
    scene.add(sprite);

    engine.messages().register(new IMessageReceiver() {
      @Override
      public void receiveMessage(MessageObject m) {
        if (m.getPayload() instanceof CollisionEvent c) {
          log.info("collision {}: {} <-> {}", c.started() ? "start" : "end", c.a(), c.b());
        }
      }
    }, MessageQueue.COLLISION);
  }
}
