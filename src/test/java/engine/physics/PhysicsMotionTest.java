package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;

/** Warm starting, compound bodies, moving platforms and grid-accelerated queries. */
class PhysicsMotionTest {
  private static final double DT = 1.0 / 60.0;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
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

  // ---- warm starting -----------------------------------------------------------------------------------------

  @Test
  void aTwelveCrateStackStandsWithoutSleeping() {
    this.physics.sleepingEnabled = false; // no sleeping to hide jitter: the solver alone must hold it
    floor();
    final List<Entity> stack = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      stack.add(crate(0f, 0.5f + i * 1.02f, 0f));
    }
    simulate(10.0);
    final var top = stack.get(11).transform().position();
    assertEquals(11.4f, top.y, 0.1f);
    assertEquals(0f, top.x, 0.3f);
    assertEquals(0f, top.z, 0.3f);
    assertTrue(this.physics.cachedContacts() >= 12, "every resting contact is kept for the next step");
  }

  @Test
  void aTwentyCrateStackStandsAndFallsAsleep() {
    floor();
    final List<Entity> stack = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      stack.add(crate(0f, 0.5f + i * 1.02f, 0f));
    }
    simulate(8.0);
    final var top = stack.get(19).transform().position();
    assertEquals(19.4f, top.y, 0.15f);
    assertEquals(0f, top.x, 0.5f);
    assertEquals(20, this.physics.stats().sleepingBodies);
  }

  @Test
  void withoutWarmStartingNothingIsCached() {
    this.physics.warmStarting = false;
    floor();
    crate(0f, 0.5f, 0f);
    simulate(0.5);
    assertEquals(0, this.physics.cachedContacts());
  }

  // ---- compound bodies ---------------------------------------------------------------------------------------

  private Entity dumbbell(float leftRadius, float rightRadius) {
    final var e = new Entity("dumbbell");
    final var left = e.add(new SphereCollider(leftRadius));
    left.offset.set(-1f, 0f, 0f);
    final var right = e.add(new SphereCollider(rightRadius));
    right.offset.set(1f, 0f, 0f);
    final var body = e.add(new Rigidbody());
    body.useGravity = false;
    body.angularDamping = 0f;
    this.scene.add(e);
    return e;
  }

  @Test
  void aCompoundBodyHasItsMassAtTheVolumeWeightedCentre() {
    final var e = dumbbell(0.5f, 0.5f);
    final var body = e.get(Rigidbody.class);
    this.physics.computeMassProperties(body);
    assertEquals(0f, body.centerOfMass.x, 1e-5f);
    // two half-mass spheres one unit from the axis: 2 * (2/5 m r^2 + m d^2)
    assertEquals(2f * (0.4f * 0.5f * 0.25f + 0.5f), body.inertiaWorld.m11, 1e-4f);
    assertEquals(2f * (0.4f * 0.5f * 0.25f), body.inertiaWorld.m00, 1e-4f, "no lever arm about the bar's axis");

    final var lopsided = dumbbell(0.25f, 0.5f);
    final var lb = lopsided.get(Rigidbody.class);
    this.physics.computeMassProperties(lb);
    final float small = 0.25f * 0.25f * 0.25f;
    final float big = 0.125f;
    assertEquals((big - small) / (big + small), lb.centerOfMass.x, 1e-4f, "pulled towards the big sphere");
  }

  @Test
  void aSpinningCompoundTurnsAboutItsCentreOfMass() {
    final var e = dumbbell(0.25f, 0.5f);
    final var body = e.get(Rigidbody.class);
    this.scene.flush();
    this.physics.computeMassProperties(body);
    final var com = new Vector3f(body.centerOfMass);
    body.angularVelocity.set(0f, 3f, 0f);
    simulate(1.0);
    this.physics.computeMassProperties(body);
    assertEquals(com.x, body.centerOfMass.x, 1e-3f);
    assertEquals(com.z, body.centerOfMass.z, 1e-3f);
    assertTrue(e.transform().position().length() > 0.1f, "the origin swings around the centre of mass");
  }

  @Test
  void aTableOnFourLegsStandsLevel() {
    floor();
    final var table = new Entity("table");
    table.transform().position().set(0f, 1.2f, 0f);
    final var top = table.add(new BoxCollider(1f, 0.05f, 0.6f));
    top.offset.set(0f, 0.5f, 0f);
    for (int i = 0; i < 4; i++) {
      final var leg = table.add(new BoxCollider(0.05f, 0.5f, 0.05f));
      leg.offset.set(i < 2 ? -0.9f : 0.9f, 0f, i % 2 == 0 ? -0.5f : 0.5f);
    }
    table.add(new Rigidbody());
    this.scene.add(table);
    simulate(3.0);
    assertEquals(0.5f, table.transform().position().y, 0.03f, "the legs reach the floor");
    final var up = table.transform().rotation().transform(new Vector3f(0f, 1f, 0f));
    assertTrue(up.y > 0.999f, "level");
  }

  // ---- moving platforms --------------------------------------------------------------------------------------

  @Test
  void aTurningKinematicPlatformCarriesWhatRestsOnIt() {
    final var platform = new Entity("turntable");
    platform.add(new BoxCollider(4f, 0.25f, 4f));
    platform.add(new Rigidbody()).kinematic = true;
    this.scene.add(platform);
    final var c = crate(2f, 0.75f, 0f);
    simulate(1.0); // settle (and fall asleep)
    final float angle0 = (float) Math.atan2(c.transform().position().z, c.transform().position().x);

    float yaw = 0f;
    for (int i = 0; i < 60; i++) {
      yaw += 1f / 60f; // one radian per second
      platform.transform().rotation().identity().rotateY(yaw);
      this.physics.update(this.scene, DT);
    }
    assertEquals(1f, platform.get(Rigidbody.class).angularVelocity.y, 0.01f, "spin is estimated from the motion");
    final var p = c.transform().position();
    final float angle1 = (float) Math.atan2(p.z, p.x);
    assertEquals(-1f, angle1 - angle0, 0.25f, "dragged round with the platform");
    assertEquals(2f, (float) Math.hypot(p.x, p.z), 0.2f, "and not flung off");
  }

  @Test
  void aStaticColliderMovedByCodeWakesAndCarriesASleeper() {
    final var lift = new Entity("lift");
    lift.add(new BoxCollider(2f, 0.25f, 2f));
    this.scene.add(lift);
    final var c = crate(0f, 0.75f, 0f);
    simulate(2.0);
    assertTrue(c.get(Rigidbody.class).isSleeping());

    for (int i = 0; i < 60; i++) {
      lift.transform().position().add(0.5f / 60f, 1f / 60f, 0f); // up and sideways
      this.physics.update(this.scene, DT);
    }
    assertFalse(c.get(Rigidbody.class).isSleeping());
    assertEquals(1.75f, c.transform().position().y, 0.05f, "lifted with the platform");
    assertEquals(0.5f, c.transform().position().x, 0.1f, "and carried sideways by friction");
  }

  @Test
  void spinIsTheAngularVelocityBetweenTwoRotations() {
    final var from = new Quaternionf().rotateX(0.3f);
    final var to = new Quaternionf().rotateY((float) Math.PI / 2).mul(from);
    final var w = PhysicsSystem.spin(from, to, 0.5f, new Vector3f());
    assertEquals(0f, w.x, 1e-4f);
    assertEquals((float) Math.PI, w.y, 1e-4f);
    assertEquals(0f, w.z, 1e-4f);
    assertEquals(0f, PhysicsSystem.spin(from, from, 0.5f, new Vector3f()).length(), 1e-6f);
  }

  // ---- queries through the grid ------------------------------------------------------------------------------

  @Test
  void gridQueriesFindTheSameAsTestingEveryCollider() {
    final var random = new Random(7);
    floor();
    for (int i = 0; i < 400; i++) {
      final var e = new Entity("thing");
      e.transform().position().set(random.nextFloat() * 80f - 40f, random.nextFloat() * 10f,
          random.nextFloat() * 80f - 40f);
      if (random.nextBoolean()) {
        e.add(new SphereCollider(0.2f + random.nextFloat()));
      } else {
        e.transform().setEulerDegrees(random.nextFloat() * 90f, random.nextFloat() * 90f, 0f);
        e.add(new BoxCollider(0.2f + random.nextFloat(), 0.2f + random.nextFloat(), 0.2f + random.nextFloat()));
      }
      if (i % 3 == 0) {
        e.add(new Rigidbody()).useGravity = false;
      }
      this.scene.add(e);
    }
    simulate(DT); // one step builds the grid the queries use

    final var reference = new PhysicsSystem(new MessageService()); // never stepped: always tests every collider
    for (int i = 0; i < 500; i++) {
      final var origin = new Vector3f(random.nextFloat() * 100f - 50f, random.nextFloat() * 12f - 1f,
          random.nextFloat() * 100f - 50f);
      final var dir = new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f);
      final float max = random.nextFloat() * 120f;
      final var fast = this.physics.raycast(this.scene, origin, dir, max);
      final var slow = reference.raycast(this.scene, origin, dir, max);
      assertEquals(slow.isPresent(), fast.isPresent(), "ray " + i);
      if (slow.isPresent()) {
        assertEquals(slow.get().distance(), fast.get().distance(), 1e-4f, "ray " + i);
      }

      final float radius = random.nextFloat() * 4f;
      final var a = new HashSet<>(this.physics.overlapSphere(this.scene, origin, radius, c -> true));
      final var b = new HashSet<>(reference.overlapSphere(this.scene, origin, radius, c -> true));
      assertEquals(b, a, "sphere " + i);
    }
  }

  @Test
  void queriesStillWorkAfterCollidersWereAdded() {
    floor();
    simulate(DT);
    final var late = new Entity("late");
    late.transform().position().set(0f, 1f, -5f);
    late.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    this.scene.add(late);
    this.scene.flush(); // added after the last step: not in the grid yet
    final var hit = this.physics.raycast(this.scene, new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, -1f), 50f);
    assertEquals(late, hit.orElseThrow().entity());
  }
}
