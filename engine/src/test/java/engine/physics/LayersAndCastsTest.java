package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;

class LayersAndCastsTest {
  private static final double DT = 1.0 / 60.0;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
  private final Scene scene = new Scene();

  private Entity add(Entity e) {
    this.scene.add(e);
    this.scene.flush();
    return e;
  }

  private Entity box(float x, float y, float z, float half) {
    final var e = new Entity("box");
    e.transform().position().set(x, y, z);
    e.add(new BoxCollider(half, half, half));
    return add(e);
  }

  private void step(int n) {
    this.scene.flush();
    for (int i = 0; i < n; i++) {
      this.physics.update(this.scene, DT);
    }
  }

  @Test
  void bodiesOnLayersThatDoNotCollideFallThroughEachOther() {
    final var floor = new Entity("floor");
    floor.transform().position().set(0f, -0.5f, 0f);
    floor.add(new BoxCollider(10f, 0.5f, 10f)).layer = 1;
    add(floor);
    final var ghost = new Entity("ghost");
    ghost.transform().position().set(0f, 1f, 0f);
    ghost.add(new BoxCollider(0.5f, 0.5f, 0.5f)).layer = 2;
    ghost.add(new Rigidbody());
    add(ghost);
    final var crate = new Entity("crate");
    crate.transform().position().set(3f, 1f, 0f);
    crate.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    crate.add(new Rigidbody());
    add(crate);

    this.physics.setLayersCollide(1, 2, false);
    assertFalse(this.physics.layersCollide(2, 1));
    assertTrue(this.physics.layersCollide(0, 1));
    step(90);

    assertTrue(ghost.transform().position().y < -2f, "fell through the floor");
    assertEquals(0.5f, crate.transform().position().y, 0.05f, "layer 0 still rests on layer 1");
  }

  @Test
  void continuousCollisionIgnoresLayersThatDoNotCollide() {
    final var wall = box(0f, 0f, 5f, 0.5f);
    wall.get(BoxCollider.class).layer = 3;
    final var bullet = new Entity("bullet");
    bullet.add(new SphereCollider(0.1f)).layer = 4;
    final var body = bullet.add(new Rigidbody());
    body.useGravity = false;
    body.continuous = true;
    body.velocity.set(0f, 0f, 600f); // 10 m per step: would tunnel without the sweep
    add(bullet);
    this.physics.setLayersCollide(3, 4, false);
    step(2);
    assertTrue(bullet.transform().position().z > 10f, "passed the wall");
  }

  @Test
  void queryFiltersPickLayers() {
    box(0f, 0f, 5f, 0.5f).get(BoxCollider.class).layer = 5;
    box(0f, 0f, 8f, 0.5f).get(BoxCollider.class).layer = 6;
    final var origin = new Vector3f();
    final var dir = new Vector3f(0f, 0f, 1f);
    assertEquals(4.5f, this.physics.raycast(this.scene, origin, dir, 20f, PhysicsSystem.solid(~0)).get().distance(),
        1e-4f);
    final var hit = this.physics.raycast(this.scene, origin, dir, 20f, PhysicsSystem.solid(PhysicsSystem.layers(6)));
    assertEquals(7.5f, hit.get().distance(), 1e-4f);
    assertEquals(0b1100000, PhysicsSystem.layers(5, 6));
    assertThrows(IllegalArgumentException.class, () -> this.physics.setLayersCollide(0, 32, true));
  }

  @Test
  void sphereCastStopsAtAFaceAndAtACorner() {
    box(0f, 0f, 5f, 0.5f);
    final var face = this.physics.sphereCast(this.scene, new Vector3f(), 0.25f, new Vector3f(0f, 0f, 2f), 10f);
    assertEquals(4.25f, face.get().distance(), 1e-3f);
    assertEquals(-1f, face.get().normal().z, 1e-3f);
    assertEquals(4.5f, face.get().point().z, 1e-3f);

    // grazing the edge at x = 0.5: a grown box would stop it early, the exact sweep at the rounded corner
    final var edge = this.physics.sphereCast(this.scene, new Vector3f(0.7f, 0f, 0f), 0.25f, new Vector3f(0f, 0f, 1f),
        10f);
    final float expected = 4.5f - (float) Math.sqrt(0.25f * 0.25f - 0.2f * 0.2f);
    assertEquals(expected, edge.get().distance(), 1e-3f);
    assertEquals(0.5f, edge.get().point().x, 1e-3f);

    assertTrue(this.physics.sphereCast(this.scene, new Vector3f(0.8f, 0f, 0f), 0.25f, new Vector3f(0f, 0f, 1f), 10f)
        .isEmpty(), "misses beside the box");
    assertTrue(this.physics.sphereCast(this.scene, new Vector3f(), 0.25f, new Vector3f(0f, 0f, 1f), 3f).isEmpty(),
        "too short");
  }

  @Test
  void capsuleCastHitsWithItsEndAndItsSide() {
    box(0f, -0.5f, 0f, 0.5f); // top at y = 0
    final var down = this.physics.capsuleCast(this.scene, new Vector3f(0f, 2f, 0f), new Vector3f(0f, 3f, 0f), 0.3f,
        new Vector3f(0f, -1f, 0f), 10f, c -> true);
    assertEquals(1.7f, down.get().distance(), 1e-3f);
    assertEquals(1f, down.get().normal().y, 1e-3f);

    final var side = this.physics.capsuleCast(this.scene, new Vector3f(-3f, -0.6f, 0f),
        new Vector3f(-3f, 0.6f, 0f), 0.3f, new Vector3f(1f, 0f, 0f), 10f, c -> true);
    assertEquals(2.2f, side.get().distance(), 1e-3f);
    assertEquals(-1f, side.get().normal().x, 1e-3f);
  }

  @Test
  void castsStartingInContactOnlyHitWhenMovingIn() {
    box(0f, -0.5f, 0f, 0.5f);
    final var a = new Vector3f(0f, 0.2f, 0f);
    final var into = this.physics.sphereCast(this.scene, a, 0.25f, new Vector3f(0f, -1f, 0f), 1f);
    assertEquals(0f, into.get().distance(), 1e-6f);
    assertTrue(this.physics.sphereCast(this.scene, a, 0.25f, new Vector3f(0f, 1f, 0f), 1f).isEmpty());
    assertTrue(this.physics.sphereCast(this.scene, a, 0.25f, new Vector3f(1f, 0f, 0f), 0.3f).isEmpty(),
        "sliding along the surface it rests in");
  }

  @Test
  void castsAgainstMeshesRespectOneSidedTriangles() {
    final var ground = new Entity("ground");
    // a 10 x 10 quad at y = 0, facing up
    ground.add(new MeshCollider(new float[] { -5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5 }, new int[] { 0, 2, 1, 0, 3,
        2 }));
    add(ground);
    final var down = this.physics.sphereCast(this.scene, new Vector3f(1f, 2f, 1f), 0.5f, new Vector3f(0f, -1f, 0f),
        5f);
    assertEquals(1.5f, down.get().distance(), 1e-3f);
    assertSame(ground.get(MeshCollider.class), down.get().collider());
    assertTrue(this.physics.sphereCast(this.scene, new Vector3f(1f, -2f, 1f), 0.5f, new Vector3f(0f, 1f, 0f), 5f)
        .isEmpty(), "comes from behind");
  }

  @Test
  void castsUseTheGridOnceItExists() {
    for (int i = 0; i < 50; i++) {
      final var crate = box(i * 3f, 0f, 0f, 0.5f);
      crate.add(new Rigidbody()).useGravity = false;
    }
    step(1);
    final var hit = this.physics.sphereCast(this.scene, new Vector3f(60f, 0f, -5f), 0.2f, new Vector3f(0f, 0f, 1f),
        10f);
    assertEquals(4.3f, hit.get().distance(), 1e-3f);
    assertEquals(60f, hit.get().entity().transform().position().x, 1e-4f);
  }

  @Test
  void overlapCapsuleFindsWhatTouchesIt() {
    final var near = box(0f, 0f, 0f, 0.5f);
    box(3f, 0f, 0f, 0.5f);
    final var found = this.physics.overlapCapsule(this.scene, new Vector3f(0.7f, -1f, 0f), new Vector3f(0.7f, 1f, 0f),
        0.25f, c -> true);
    assertEquals(1, found.size());
    assertSame(near, found.get(0).entity());
    assertTrue(this.physics.overlapCapsule(this.scene, new Vector3f(1.5f, -1f, 0f), new Vector3f(1.5f, 1f, 0f), 0.25f,
        c -> true).isEmpty());
  }
}
