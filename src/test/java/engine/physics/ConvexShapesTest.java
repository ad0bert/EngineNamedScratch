package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;
import engine.render.MeshFactory;

/** Cylinders and convex hulls. */
class ConvexShapesTest {
  private static final double DT = 1.0 / 60.0;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
  private final Scene scene = new Scene();

  private void simulate(double seconds) {
    this.scene.flush();
    for (int i = 0; i < Math.round(seconds / DT); i++) {
      this.physics.update(this.scene, DT);
    }
  }

  private Entity body(Collider shape, float x, float y, float z) {
    final var e = new Entity("body");
    e.transform().position().set(x, y, z);
    e.add(shape);
    e.add(new Rigidbody());
    this.scene.add(e);
    return e;
  }

  private Entity ground() {
    final var floor = new Entity("floor");
    floor.transform().position().set(0f, -0.5f, 0f);
    floor.add(new BoxCollider(20f, 0.5f, 20f));
    this.scene.add(floor);
    return floor;
  }

  private Entity floorMesh() {
    final var e = new Entity("floor-mesh");
    e.transform().setEulerDegrees(-90f, 0f, 0f);
    e.add(new MeshCollider(MeshFactory.quad(20f, 20f)));
    this.scene.add(e);
    return e;
  }

  /** The eight corners of a box with the given half sizes, plus points inside that must not become vertices. */
  private static float[] boxPoints(float hx, float hy, float hz) {
    final List<Float> p = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      p.add((i & 1) == 0 ? -hx : hx);
      p.add((i & 2) == 0 ? -hy : hy);
      p.add((i & 4) == 0 ? -hz : hz);
    }
    for (int i = 0; i < 20; i++) {
      p.add(hx * 0.9f * (float) Math.sin(i));
      p.add(hy * 0.9f * (float) Math.cos(i * 1.3));
      p.add(hz * 0.9f * (float) Math.sin(i * 0.7));
    }
    final float[] a = new float[p.size()];
    for (int i = 0; i < a.length; i++) {
      a[i] = p.get(i);
    }
    return a;
  }

  private static float[] spherePoints(int n, float r, long seed) {
    final var random = new Random(seed);
    final float[] p = new float[n * 3];
    for (int i = 0; i < n; i++) {
      final var v = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(),
          (float) random.nextGaussian()).normalize(r);
      p[i * 3] = v.x;
      p[i * 3 + 1] = v.y;
      p[i * 3 + 2] = v.z;
    }
    return p;
  }

  // ---- hull building and mass ----------------------------------------------------------------------------------

  @Test
  void theHullOfABoxHasEightCornersAndSixQuads() {
    final var hull = new ConvexHullCollider(boxPoints(1f, 0.5f, 0.25f));
    assertEquals(8, hull.vertexCount(), "inner points are dropped");
    assertEquals(6, hull.faceCount(), "coplanar triangles are merged");
    assertEquals(8f * 0.5f * 0.25f, hull.localVolume(), 1e-5f);
    assertEquals(0f, hull.localCentroid(new Vector3f()).length(), 1e-5f);
  }

  @Test
  void aHullHasTheMassPropertiesOfTheSameBox() {
    final var a = new Entity();
    a.transform().setEulerDegrees(20f, 30f, 40f);
    a.transform().scale().set(2f, 1f, 1f); // non-uniform scale too
    final var hull = a.add(new ConvexHullCollider(boxPoints(0.5f, 0.5f, 0.25f)));
    final var b = new Entity();
    b.transform().setEulerDegrees(20f, 30f, 40f);
    final var box = b.add(new BoxCollider(1f, 0.5f, 0.25f));
    hull.refresh();
    box.refresh();
    assertEquals(box.cachedVolume(), hull.cachedVolume(), 1e-5f);
    final var ih = new Matrix3f().zero();
    final var ib = new Matrix3f().zero();
    hull.addCachedInertia(3f, ih);
    box.addCachedInertia(3f, ib);
    for (int c = 0; c < 3; c++) {
      for (int r = 0; r < 3; r++) {
        assertEquals(ib.get(c, r), ih.get(c, r), 1e-4f, "inertia " + c + "," + r);
      }
    }
    assertEquals(box.cachedInnerRadius(), hull.cachedInnerRadius(), 1e-5f);
  }

  @Test
  void aHullKeepsAtMostItsVertexCapAndApproachesTheShape() {
    final float[] points = spherePoints(2000, 1f, 7);
    final var coarse = new ConvexHullCollider(points, 24);
    final var fine = new ConvexHullCollider(points, 200);
    assertTrue(coarse.vertexCount() <= 24, "capped: " + coarse.vertexCount());
    assertTrue(fine.vertexCount() > 100);
    final float sphere = 4f / 3f * (float) Math.PI;
    assertTrue(fine.localVolume() < sphere && fine.localVolume() > 0.95f * sphere, "fine: " + fine.localVolume());
    assertTrue(coarse.localVolume() < fine.localVolume() && coarse.localVolume() > 0.7f * sphere,
        "coarse: " + coarse.localVolume());
    assertEquals(0f, fine.localCentroid(new Vector3f()).length(), 0.02f);
  }

  @Test
  void flatPointCloudsAreRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> new ConvexHullCollider(new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0 }));
  }

  @Test
  void cylinderVolumeAndInertia() {
    final var e = new Entity();
    final var cyl = e.add(new CylinderCollider(0.5f, 1f));
    cyl.refresh();
    assertEquals((float) Math.PI * 0.25f * 2f, cyl.cachedVolume(), 1e-5f);
    final var m = new Matrix3f().zero();
    cyl.addCachedInertia(2f, m);
    assertEquals(2f * 0.25f / 2f, m.m11, 1e-5f, "about its own axis: m r^2 / 2");
    assertEquals(2f * (3f * 0.25f + 4f) / 12f, m.m00, 1e-5f, "across: m (3 r^2 + h^2) / 12");
    assertEquals(m.m00, m.m22, 1e-6f);
  }

  // ---- narrowphase -------------------------------------------------------------------------------------------

  @Test
  void hullAgainstHullFindsTheFaceAndFourPoints() {
    final var lower = new Entity();
    final var a = lower.add(new ConvexHullCollider(boxPoints(1f, 1f, 1f)));
    final var upper = new Entity();
    upper.transform().position().set(0.2f, 1.4f, 0.1f);
    upper.transform().setEulerDegrees(0f, 30f, 0f);
    final var b = upper.add(new ConvexHullCollider(boxPoints(0.5f, 0.5f, 0.5f)));
    a.refresh();
    b.refresh();
    final var c = new Narrowphase.Contact();
    c.wantPoints = true;
    assertTrue(Narrowphase.test(a, b, c));
    assertEquals(1f, c.normal().y, 1e-4f, "from A up towards B");
    assertEquals(0.1f, c.depth(), 1e-4f);
    assertEquals(4, c.count());
    for (int i = 0; i < c.count(); i++) {
      assertEquals(0.95f, c.point(i).y, 1e-4f, "halfway between the faces");
      assertEquals(0.1f, c.depth(i), 1e-4f);
    }
    upper.transform().position().y = 1.55f;
    b.refresh();
    assertFalse(Narrowphase.test(a, b, c));
    assertTrue(Narrowphase.test(a, b, c, 0.1f), "within a speculative margin");
    assertEquals(-0.05f, c.depth(), 1e-4f);
  }

  @Test
  void spheresAndCapsulesAgainstCylinders() {
    final var e = new Entity();
    e.transform().position().set(0f, 0f, 0f);
    final var cyl = e.add(new CylinderCollider(1f, 1f));
    cyl.refresh();
    final var ball = new Entity();
    ball.transform().position().set(1.4f, 0.3f, 0f); // against the side
    final var s = ball.add(new SphereCollider(0.5f));
    s.refresh();
    final var c = new Narrowphase.Contact();
    assertTrue(Narrowphase.test(s, cyl, c));
    assertEquals(-1f, c.normal().x, 1e-4f, "from the sphere towards the cylinder");
    assertEquals(0.1f, c.depth(), 1e-4f);
    ball.transform().position().set(0.3f, 1.3f, 0.2f); // on the cap
    s.refresh();
    assertTrue(Narrowphase.test(cyl, s, c));
    assertEquals(1f, c.normal().y, 1e-4f, "from the cylinder up to the sphere");
    assertEquals(0.2f, c.depth(), 1e-4f);
    ball.transform().position().set(1.1f, 1.3f, 1.1f); // beyond the rim, diagonally
    s.refresh();
    assertFalse(Narrowphase.test(cyl, s, c), "a box of that size would touch, the cylinder does not");

    final var pill = new Entity();
    pill.transform().position().set(0f, 1.4f, 0f);
    pill.transform().setEulerDegrees(0f, 0f, 90f); // lying across the cap
    final var cap = pill.add(new CapsuleCollider(0.5f, 0.5f));
    cap.refresh();
    c.wantPoints = true;
    assertTrue(Narrowphase.test(cyl, cap, c));
    assertEquals(0.1f, c.depth(), 1e-3f);
    assertEquals(2, c.count(), "both ends of the capsule rest on the cap");
  }

  @Test
  void aDeepOverlapStillGivesTheShortestWayOut() {
    final var lower = new Entity();
    final var a = lower.add(new ConvexHullCollider(boxPoints(1f, 1f, 1f)));
    final var upper = new Entity();
    upper.transform().position().set(0f, 0.6f, 0f);
    final var b = upper.add(new CylinderCollider(0.3f, 0.3f));
    a.refresh();
    b.refresh();
    final var c = new Narrowphase.Contact();
    assertTrue(Narrowphase.test(a, b, c));
    assertEquals(1f, c.normal().y, 1e-3f);
    assertEquals(0.7f, c.depth(), 1e-3f, "out of the top: 1 - (0.6 - 0.3)");
  }

  // ---- bodies at rest ----------------------------------------------------------------------------------------

  @Test
  void aHullCrateFallsAndRestsFlatAndSleeps() {
    ground();
    final var crate = body(new ConvexHullCollider(boxPoints(0.5f, 0.5f, 0.5f)), 0f, 3f, 0f);
    crate.transform().setEulerDegrees(10f, 20f, 5f);
    simulate(4.0);
    assertEquals(0.5f, crate.transform().position().y, 0.02f);
    final var up = crate.transform().rotation().transform(new Vector3f(0f, 1f, 0f));
    assertTrue(Math.abs(up.x) < 0.01f && Math.abs(up.z) < 0.01f || Math.abs(up.y) < 0.01f, "lies on a face: " + up);
    assertTrue(crate.get(Rigidbody.class).isSleeping());
  }

  @Test
  void aStackOfHullsStands() {
    ground();
    final List<Entity> crates = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      crates.add(body(new ConvexHullCollider(boxPoints(0.5f, 0.5f, 0.5f)), 0f, 0.5f + i * 1.0f, 0f));
    }
    simulate(5.0);
    for (int i = 0; i < 5; i++) {
      final var p = crates.get(i).transform().position();
      assertEquals(0.5f + i, p.y, 0.05f, "crate " + i);
      assertEquals(0f, p.x, 0.05f);
      assertEquals(0f, p.z, 0.05f);
    }
    assertEquals(5, this.physics.stats().sleepingBodies);
  }

  @Test
  void aCylinderStandsOnItsCapAndSleeps() {
    ground();
    final var barrel = body(new CylinderCollider(0.4f, 0.6f), 0f, 1f, 0f);
    simulate(3.0);
    assertEquals(0.6f, barrel.transform().position().y, 0.02f);
    final var up = barrel.transform().rotation().transform(new Vector3f(0f, 1f, 0f));
    assertTrue(up.y > 0.999f, "upright: " + up);
    assertTrue(barrel.get(Rigidbody.class).isSleeping());
  }

  @Test
  void aTippedCylinderFallsOverAndLiesOnItsSide() {
    ground();
    final var log = body(new CylinderCollider(0.3f, 1f), 0f, 1.1f, 0f);
    log.transform().setEulerDegrees(0f, 0f, 25f);
    simulate(5.0);
    assertEquals(0.3f, log.transform().position().y, 0.03f, "on its side");
    final var axis = log.transform().rotation().transform(new Vector3f(0f, 1f, 0f));
    assertEquals(0f, axis.y, 0.02f);
  }

  @Test
  void aCylinderRollsDownASlopeLikeAWheel() {
    final var ramp = new Entity("ramp");
    ramp.transform().setEulerDegrees(0f, 0f, -15f); // descends towards +x
    ramp.add(new BoxCollider(20f, 0.5f, 5f));
    this.scene.add(ramp);
    final var wheel = body(new CylinderCollider(0.5f, 0.2f), -3f, 2f, 0f);
    wheel.transform().setEulerDegrees(90f, 0f, 0f); // axis along z: rolls along x
    wheel.get(Rigidbody.class).angularDamping = 0f;
    simulate(2.0);
    assertTrue(wheel.transform().position().x > 0f, "rolled down: " + wheel.transform().position());
    assertTrue(Math.abs(wheel.get(Rigidbody.class).angularVelocity.z) > 2f, "turning");
  }

  @Test
  void hullsAndCylindersRestOnAMeshFloor() {
    floorMesh();
    final var rock = body(new ConvexHullCollider(spherePoints(300, 0.5f, 3), 32), -2f, 2f, 0f);
    final var barrel = body(new CylinderCollider(0.4f, 0.5f), 2f, 2f, 0f);
    final var crate = body(new ConvexHullCollider(boxPoints(0.5f, 0.5f, 0.5f)), 0f, 2f, 0f);
    simulate(5.0);
    assertTrue(rock.transform().position().y > 0.35f && rock.transform().position().y < 0.55f,
        "rock: " + rock.transform().position());
    assertEquals(0.5f, barrel.transform().position().y, 0.02f);
    assertEquals(0.5f, crate.transform().position().y, 0.02f);
    final var up = crate.transform().rotation().transform(new Vector3f(0f, 1f, 0f));
    assertTrue(up.y > 0.999f, "a crate on the seam between two triangles does not tip");
    assertTrue(crate.get(Rigidbody.class).isSleeping() && barrel.get(Rigidbody.class).isSleeping());
  }

  @Test
  void cylindersAndHullsCollideWithBoxesSpheresAndCapsules() {
    ground();
    final var barrel = body(new CylinderCollider(0.5f, 0.5f), 0f, 0.5f, 0f);
    final var ball = body(new SphereCollider(0.3f), 0.1f, 2f, 0f);
    final var hull = body(new ConvexHullCollider(boxPoints(0.3f, 0.3f, 0.3f)), 3f, 0.3f, 0f);
    final var pill = body(new CapsuleCollider(0.2f, 0.3f), 3f, 2f, 0f);
    pill.transform().setEulerDegrees(0f, 0f, 90f);
    simulate(3.0);
    assertTrue(ball.transform().position().y > 1.2f, "the ball landed on the barrel: " + ball.transform().position());
    assertTrue(pill.transform().position().y > 0.7f, "the capsule on the hull: " + pill.transform().position());
    assertEquals(0.5f, barrel.transform().position().y, 0.02f);
    assertEquals(0.3f, hull.transform().position().y, 0.02f);
  }

  @Test
  void aCompoundOfACylinderAndAHullTurnsAboutTheirCommonCentre() {
    final var e = new Entity("hammer");
    final var handle = e.add(new CylinderCollider(0.05f, 0.5f));
    final var head = e.add(new ConvexHullCollider(boxPoints(0.2f, 0.1f, 0.1f)));
    head.offset.set(0f, 0.6f, 0f);
    final var body = e.add(new Rigidbody());
    body.mass = 2f;
    body.useGravity = false;
    this.scene.add(e);
    simulate(DT);
    this.physics.computeMassProperties(body);
    final float handleVolume = (float) Math.PI * 0.05f * 0.05f;
    final float headVolume = 0.4f * 0.2f * 0.2f;
    final float expected = 0.6f * headVolume / (handleVolume + headVolume);
    assertEquals(expected, body.centerOfMass.y, 1e-3f, "nearer to the heavy head");
    assertTrue(handle.cachedVolume() > 0f);
  }

  // ---- queries -----------------------------------------------------------------------------------------------

  @Test
  void raysHitCylindersAndHulls() {
    final var pillar = new Entity("pillar");
    pillar.transform().position().set(0f, 0f, -10f);
    pillar.add(new CylinderCollider(1f, 2f));
    this.scene.add(pillar);
    final var rock = new Entity("rock");
    rock.transform().position().set(10f, 0f, -10f);
    rock.transform().setEulerDegrees(0f, 45f, 0f);
    rock.add(new ConvexHullCollider(boxPoints(1f, 1f, 1f)));
    this.scene.add(rock);
    this.scene.flush();
    final var side = this.physics.raycast(this.scene, new Vector3f(0.5f, 1f, 0f), new Vector3f(0f, 0f, -1f), 50f);
    assertEquals(pillar, side.orElseThrow().entity());
    assertEquals(10f - (float) Math.sqrt(1 - 0.25), side.get().distance(), 1e-4f);
    final var top = this.physics.raycast(this.scene, new Vector3f(0.3f, 5f, -10f), new Vector3f(0f, -1f, 0f), 50f);
    assertEquals(3f, top.orElseThrow().distance(), 1e-4f);
    assertEquals(1f, top.get().normal().y, 1e-5f);
    assertTrue(this.physics.raycast(this.scene, new Vector3f(0.9f, 5f, -9.1f), new Vector3f(0f, -1f, 0f), 50f)
        .isEmpty(), "past the round edge of the cap");
    final var corner = this.physics.raycast(this.scene, new Vector3f(10f, 0f, 0f), new Vector3f(0f, 0f, -1f), 50f);
    assertEquals(rock, corner.orElseThrow().entity());
    assertEquals(10f - (float) Math.sqrt(2), corner.get().distance(), 1e-4f, "the turned cube's edge");
    final var hits = this.physics.overlapSphere(this.scene, new Vector3f(1.2f, 0f, -10f), 0.3f, c -> true);
    assertEquals(1, hits.size());
    assertTrue(this.physics.overlapSphere(this.scene, new Vector3f(1.2f, 2.2f, -8.8f), 0.3f, c -> true).isEmpty());
    final var sweep = Narrowphase.sweepSphere(pillar.get(CylinderCollider.class), new Vector3f(0f, 0f, 0f),
        new Vector3f(0f, 0f, -1f), 50f, 0.5f);
    assertNotNull(sweep);
    assertEquals(8.5f, sweep.distance(), 1e-4f);
  }

  @Test
  void aFastHullDoesNotTunnelThroughAThinWall() {
    final var wall = new Entity("wall");
    wall.transform().position().set(0f, 0f, -10f);
    wall.add(new BoxCollider(5f, 5f, 0.05f));
    this.scene.add(wall);
    final var bullet = body(new ConvexHullCollider(boxPoints(0.1f, 0.1f, 0.1f)), 0f, 0f, 0f);
    bullet.get(Rigidbody.class).useGravity = false;
    bullet.get(Rigidbody.class).velocity.set(0f, 0f, -290f);
    simulate(0.5);
    assertTrue(bullet.transform().position().z > -10f, "stopped in front: " + bullet.transform().position());
  }

  @Test
  void restingHullsAndCylindersDoNotAllocateInTheStep() {
    ground();
    body(new ConvexHullCollider(boxPoints(0.5f, 0.5f, 0.5f)), 0f, 0.5f, 0f);
    body(new ConvexHullCollider(boxPoints(0.5f, 0.5f, 0.5f)), 0f, 1.5f, 0f);
    body(new CylinderCollider(0.4f, 0.5f), 2f, 0.5f, 0f);
    final var lying = body(new CylinderCollider(0.4f, 0.5f), -2f, 0.4f, 0f);
    lying.transform().setEulerDegrees(90f, 0f, 0f);
    for (final var b : this.scene.components(Rigidbody.class)) {
      b.allowSleep = false;
    }
    simulate(0.5);
    final var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    final long id = Thread.currentThread().threadId();
    final long before = threads.getThreadAllocatedBytes(id);
    for (int i = 0; i < 60; i++) {
      this.physics.update(this.scene, DT);
    }
    final long allocated = threads.getThreadAllocatedBytes(id) - before;
    assertTrue(allocated < 1024, "allocated " + allocated + " bytes");
  }
}
