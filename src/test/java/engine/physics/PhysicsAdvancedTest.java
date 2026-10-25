package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;
import engine.render.Camera;

class PhysicsAdvancedTest {
  private static final double DT = 1.0 / 60.0;

  private final MessageService messages = new MessageService();
  private final PhysicsSystem physics = new PhysicsSystem(this.messages);
  private final Scene scene = new Scene();

  private Entity floor() {
    final var e = new Entity("floor");
    e.transform().position().set(0f, -0.5f, 0f);
    e.add(new BoxCollider(50f, 0.5f, 50f));
    this.scene.add(e);
    return e;
  }

  private Entity crate(float x, float y, float z) {
    final var e = new Entity("crate");
    e.transform().position().set(x, y, z);
    e.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    e.add(new Rigidbody());
    this.scene.add(e);
    return e;
  }

  private void simulate(double seconds) {
    this.scene.flush();
    for (int i = 0; i < Math.round(seconds / DT); i++) {
      this.physics.update(this.scene, DT);
    }
  }

  private static Rigidbody body(Entity e) {
    return e.get(Rigidbody.class);
  }

  // ---- sleeping ----------------------------------------------------------------------------------------------

  @Test
  void aRestingBodyFallsAsleepAndStopsMoving() {
    floor();
    final var c = crate(0f, 0.6f, 0f);
    simulate(0.2);
    assertFalse(body(c).isSleeping(), "still settling");
    simulate(2.0);
    assertTrue(body(c).isSleeping());
    assertEquals(1, this.physics.stats().sleepingBodies);

    final float y = c.transform().position().y;
    simulate(1.0);
    assertEquals(y, c.transform().position().y, 0f, "a sleeping body is not integrated at all");
  }

  @Test
  void aFloatingBodyNeverSleeps() {
    final var c = crate(0f, 10f, 0f);
    body(c).useGravity = false;
    simulate(3.0);
    assertFalse(body(c).isSleeping());
  }

  @Test
  void aSlowDriftingBodyKeepsDriftingInsteadOfFreezing() {
    final var c = crate(0f, 10f, 0f);
    body(c).useGravity = false;
    body(c).linearDamping = 0f;
    body(c).velocity.set(0.02f, 0f, 0f); // below the sleep speed, but nothing supports it
    simulate(3.0);
    assertEquals(0.06f, c.transform().position().x, 0.005f);
  }

  @Test
  void kinematicAndNoSleepBodiesStayAwake() {
    floor();
    final var kin = crate(-3f, 0.5f, 0f);
    body(kin).kinematic = true;
    final var insomniac = crate(3f, 0.6f, 0f);
    body(insomniac).allowSleep = false;
    simulate(3.0);
    assertFalse(body(kin).isSleeping());
    assertFalse(body(insomniac).isSleeping());
  }

  @Test
  void aHitWakesASleepingBody() {
    floor();
    final var sleeper = crate(0f, 0.5f, 0f);
    simulate(2.5);
    assertTrue(body(sleeper).isSleeping());

    final var bullet = crate(-4f, 0.5f, 0f);
    body(bullet).velocity.set(12f, 0f, 0f);
    body(bullet).useGravity = false;
    simulate(0.6);
    assertFalse(body(sleeper).isSleeping());
    assertTrue(sleeper.transform().position().x > 0.3f, "the sleeper was pushed");
  }

  @Test
  void settingVelocityOrTeleportingWakesABody() {
    floor();
    final var c = crate(0f, 0.5f, 0f);
    simulate(2.5);
    assertTrue(body(c).isSleeping());

    body(c).velocity.set(0f, 5f, 0f);
    simulate(0.1);
    assertFalse(body(c).isSleeping());
    assertTrue(c.transform().position().y > 0.6f);

    simulate(3.0);
    assertTrue(body(c).isSleeping());
    c.transform().position().set(5f, 3f, 0f); // moved by code
    simulate(0.1);
    assertFalse(body(c).isSleeping());
    assertTrue(c.transform().position().y < 3f, "it falls again from the new place");
  }

  @Test
  void aSleepingBodyWakesWhenItsSupportIsRemoved() {
    final var f = floor();
    final var c = crate(0f, 0.5f, 0f);
    simulate(2.5);
    assertTrue(body(c).isSleeping());

    this.scene.remove(f);
    simulate(0.5);
    assertFalse(body(c).isSleeping());
    assertTrue(c.transform().position().y < 0f, "it fell");
  }

  @Test
  void aStackSleepsAsAWhole() {
    floor();
    final var bottom = crate(0f, 0.6f, 0f);
    final var top = crate(0f, 1.8f, 0f);
    simulate(5.0);
    assertTrue(body(bottom).isSleeping() && body(top).isSleeping());
    assertEquals(1.5f, top.transform().position().y, 0.1f);
  }

  // ---- broadphase --------------------------------------------------------------------------------------------

  private static Set<Long> keys(List<PhysicsSystem.Pair> pairs) {
    final Set<Long> out = new HashSet<>();
    for (var p : pairs) {
      final long a = Math.min(p.a().entity().id(), p.b().entity().id());
      final long b = Math.max(p.a().entity().id(), p.b().entity().id());
      out.add(a << 32 | b);
    }
    assertEquals(pairs.size(), out.size(), "no pair may be reported twice");
    return out;
  }

  @Test
  void gridBroadphaseFindsExactlyTheSamePairsAsBruteForce() {
    final var rnd = new Random(7);
    floor();
    for (int i = 0; i < 250; i++) {
      final var e = new Entity();
      e.transform().position().set(rnd.nextFloat() * 40f - 20f, rnd.nextFloat() * 6f, rnd.nextFloat() * 40f - 20f);
      if (rnd.nextBoolean()) {
        e.add(new BoxCollider(0.3f + rnd.nextFloat(), 0.3f + rnd.nextFloat(), 0.3f + rnd.nextFloat()));
      } else {
        e.add(new SphereCollider(0.3f + rnd.nextFloat()));
      }
      if (rnd.nextInt(3) > 0) {
        e.add(new Rigidbody());
      }
      this.scene.add(e);
    }
    final var giant = new Entity("giant"); // bigger than any cell
    giant.add(new BoxCollider(200f, 0.1f, 200f));
    this.scene.add(giant);
    this.scene.flush();
    final var colliders = this.scene.components(Collider.class);

    final var grid = keys(this.physics.findPairs(colliders));
    this.physics.bruteForceBroadphase = true;
    final var brute = keys(this.physics.findPairs(colliders));
    assertEquals(brute, grid);
    assertTrue(brute.size() > 100, "scene should be dense enough to be meaningful");
  }

  @Test
  void manySeparatedBodiesProduceFewPairs() {
    floor();
    for (int x = 0; x < 20; x++) {
      for (int z = 0; z < 20; z++) {
        crate(x * 3f, 0.5f, z * 3f); // 400 well separated crates
      }
    }
    simulate(0.1);
    // with a grid only neighbours (plus the ground) are paired; brute force would test ~80k pairs
    assertTrue(this.physics.stats().candidatePairs < 1500, "pairs: " + this.physics.stats().candidatePairs);
  }

  // ---- parenting ---------------------------------------------------------------------------------------------

  @Test
  void parentedBodiesFallInWorldSpace() {
    floor();
    final var parent = new Entity("parent");
    parent.transform().position().set(2f, 3f, 0f);
    parent.transform().setEulerDegrees(0f, 0f, 90f); // local x now points along world y
    parent.transform().scale().set(2f);
    this.scene.add(parent);
    final var child = new Entity("child");
    child.transform().setParent(parent.transform());
    child.add(new BoxCollider(0.25f, 0.25f, 0.25f)); // world half extent 0.5 because of the parent's scale
    child.add(new Rigidbody());
    this.scene.add(child);

    simulate(0.5);
    final var p = child.transform().worldPosition(new Vector3f());
    assertEquals(2f, p.x, 0.01f);
    assertTrue(p.y < 3f - 0.5f * 9.81f * 0.25f * 0.8f, "fell about g*t^2/2 in world space, y=" + p.y);

    simulate(4.0);
    child.transform().worldPosition(p);
    assertEquals(0.5f, p.y, 0.05f, "comes to rest on the floor");
    assertEquals(2f, p.x, 0.05f);
    assertEquals(3f, parent.transform().position().y, 0f, "the parent itself is not moved");
  }

  // ---- queries -----------------------------------------------------------------------------------------------

  private Entity box(float x, float y, float z, float hx, float hy, float hz) {
    final var e = new Entity("box");
    e.transform().position().set(x, y, z);
    e.add(new BoxCollider(hx, hy, hz));
    this.scene.add(e);
    this.scene.flush();
    return e;
  }

  private Optional<RayHit> ray(float ox, float oy, float oz, float dx, float dy, float dz, float max) {
    return this.physics.raycast(this.scene, new Vector3f(ox, oy, oz), new Vector3f(dx, dy, dz), max);
  }

  @Test
  void rayHitsBoxFaceWithDistanceNormalAndPoint() {
    final var e = box(0f, 0f, -10f, 1f, 1f, 1f);
    final var hit = ray(0f, 0f, 0f, 0f, 0f, -3f, 100f).orElseThrow(); // direction need not be normalized
    assertSame(e, hit.entity());
    assertEquals(9f, hit.distance(), 1e-4f);
    assertEquals(1f, hit.normal().z, 1e-5f);
    assertEquals(-9f, hit.point().z, 1e-4f);
  }

  @Test
  void rayHitsRotatedBoxExactly() {
    box(0f, 0f, -10f, 1f, 1f, 1f).transform().setEulerDegrees(0f, 45f, 0f);
    final var hit = ray(0f, 0f, 0f, 0f, 0f, -1f, 100f).orElseThrow();
    assertEquals(10f - (float) Math.sqrt(2), hit.distance(), 1e-3f); // the tip of the diamond
    // a ray that would hit the unrotated box's corner region misses the turned box
    assertTrue(ray(0.95f, 0f, 0f, 0f, 0f, -1f, 100f).isEmpty() || ray(0.95f, 0f, 0f, 0f, 0f, -1f, 100f)
        .orElseThrow().distance() > 10f - (float) Math.sqrt(2) + 0.5f);
  }

  @Test
  void rayHitsSpheresAndMisses() {
    final var e = new Entity("ball");
    e.transform().position().set(0f, 0f, -10f);
    e.add(new SphereCollider(2f));
    this.scene.add(e);
    this.scene.flush();
    final var hit = ray(0f, 0f, 0f, 0f, 0f, -1f, 100f).orElseThrow();
    assertEquals(8f, hit.distance(), 1e-4f);
    assertEquals(1f, hit.normal().z, 1e-4f);
    assertTrue(ray(0f, 5f, 0f, 0f, 0f, -1f, 100f).isEmpty(), "passes above the sphere");
    assertTrue(ray(0f, 0f, 0f, 0f, 0f, 1f, 100f).isEmpty(), "pointing away");
    assertTrue(ray(0f, 0f, 0f, 0f, 0f, 0f, 100f).isEmpty(), "zero direction");
  }

  @Test
  void rayRespectsMaxDistanceNearestHitAndTriggers() {
    final var far = box(0f, 0f, -20f, 1f, 1f, 1f);
    final var near = box(0f, 0f, -10f, 1f, 1f, 1f);
    assertSame(near, ray(0f, 0f, 0f, 0f, 0f, -1f, 100f).orElseThrow().entity());
    assertTrue(ray(0f, 0f, 0f, 0f, 0f, -1f, 5f).isEmpty(), "too far");

    near.get(BoxCollider.class).trigger = true;
    assertSame(far, ray(0f, 0f, 0f, 0f, 0f, -1f, 100f).orElseThrow().entity(), "triggers are ignored");
    final var hitTrigger = this.physics.raycast(this.scene, new Vector3f(), new Vector3f(0f, 0f, -1f), 100f, c -> true);
    assertSame(near, hitTrigger.orElseThrow().entity());

    final var skipNear = this.physics.raycast(this.scene, new Vector3f(), new Vector3f(0f, 0f, -1f), 100f,
        c -> c.entity() != near);
    assertSame(far, skipNear.orElseThrow().entity());
  }

  @Test
  void rayStartingInsideReportsDistanceZero() {
    box(0f, 0f, 0f, 2f, 2f, 2f);
    final var hit = ray(0f, 0f, 0f, 1f, 0f, 0f, 100f).orElseThrow();
    assertEquals(0f, hit.distance(), 0f);
    assertEquals(-1f, hit.normal().x, 1e-5f);
  }

  @Test
  void overlapSphereFindsTouchingColliders() {
    final var a = box(0f, 0f, 0f, 1f, 1f, 1f);
    final var b = box(10f, 0f, 0f, 1f, 1f, 1f);
    final var ball = new Entity("ball");
    ball.transform().position().set(0f, 3f, 0f);
    ball.add(new SphereCollider(1f));
    this.scene.add(ball);
    this.scene.flush();

    final var near = this.physics.overlapSphere(this.scene, new Vector3f(0f, 1.5f, 0f), 1f, c -> true);
    final Set<Entity> entities = new HashSet<>();
    near.forEach(c -> entities.add(c.entity()));
    assertEquals(Set.of(a, ball), entities);
    assertTrue(this.physics.overlapSphere(this.scene, new Vector3f(50f, 0f, 0f), 1f, c -> true).isEmpty());
    assertEquals(1, this.physics.overlapSphere(this.scene, new Vector3f(10f, 0f, 0f), 0.5f, c -> true).size());
    assertNotNull(b);
  }

  @Test
  void cameraRaysFollowTheViewDirection() {
    final var e = new Entity("cam");
    e.transform().position().set(1f, 2f, 3f);
    final var cam = e.add(new Camera());
    cam.fovDegrees = 90f;
    final var origin = new Vector3f();
    final var dir = new Vector3f();

    cam.viewRay(0f, 0f, 1f, origin, dir);
    assertEquals(1f, origin.x, 1e-5f);
    assertEquals(-1f, dir.z, 1e-5f);

    cam.viewRay(1f, 0f, 1f, origin, dir); // right edge of a 90 degree view
    assertEquals(Math.sqrt(0.5), dir.x, 1e-4f);
    assertEquals(-Math.sqrt(0.5), dir.z, 1e-4f);

    cam.viewRay(0f, 1f, 2f, origin, dir); // top edge, wide aspect does not affect y
    assertEquals(Math.sqrt(0.5), dir.y, 1e-4f);

    e.transform().setEulerDegrees(0f, 90f, 0f); // turned to look along -x
    cam.viewRay(0f, 0f, 1f, origin, dir);
    assertEquals(-1f, dir.x, 1e-4f);
  }

  @Test
  void raycastFromTheEngineStyleCrosshairHitsACrate() {
    floor();
    final var c = crate(0f, 0.5f, -6f);
    simulate(0.1);
    final var hit = this.physics.raycast(this.scene, new Vector3f(0f, 0.5f, 0f), new Vector3f(0f, 0f, -1f), 50f,
        col -> !col.trigger);
    assertEquals(c, hit.orElseThrow().entity());
    assertEquals(5.5f, hit.get().distance(), 0.05f);
    final List<Entity> seen = new ArrayList<>();
    seen.add(hit.get().entity());
    assertEquals(1, seen.size());
  }
}
