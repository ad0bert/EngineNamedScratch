package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;
import engine.render.MeshFactory;

/** Capsules and triangle meshes. */
class ShapesTest {
  private static final double DT = 1.0 / 60.0;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
  private final Scene scene = new Scene();

  private void simulate(double seconds) {
    this.scene.flush();
    for (int i = 0; i < Math.round(seconds / DT); i++) {
      this.physics.update(this.scene, DT);
    }
  }

  private static ShapeTests.Capsule capsule(float ax, float ay, float az, float bx, float by, float bz, float r) {
    final var c = new ShapeTests.Capsule();
    c.a.set(ax, ay, az);
    c.b.set(bx, by, bz);
    c.r = r;
    return c;
  }

  /** A square of two triangles in the XZ plane, facing up, at height y. */
  private Entity floorMesh(float half, float y) {
    final var e = new Entity("floor-mesh");
    e.transform().position().set(0f, y, 0f);
    e.transform().setEulerDegrees(-90f, 0f, 0f); // the quad faces +Z; turned to face up
    e.add(new MeshCollider(MeshFactory.quad(2f * half, 2f * half)));
    this.scene.add(e);
    return e;
  }

  private Entity body(Collider shape, float x, float y, float z) {
    final var e = new Entity("body");
    e.transform().position().set(x, y, z);
    e.add(shape);
    e.add(new Rigidbody());
    this.scene.add(e);
    return e;
  }

  // ---- capsule contacts --------------------------------------------------------------------------------------

  @Test
  void sphereAgainstTheSideAndTheEndOfACapsule() {
    final var cap = capsule(0f, -1f, 0f, 0f, 1f, 0f, 0.5f);
    final var c = new Narrowphase.Contact();
    assertTrue(ShapeTests.sphereCapsule(new Narrowphase.Sphere(new Vector3f(0.9f, 0.3f, 0f), 0.5f), cap, c, 0f));
    assertEquals(0.1f, c.depth(), 1e-5f);
    assertEquals(-1f, c.normal().x, 1e-5f, "from the sphere towards the capsule");
    assertTrue(ShapeTests.sphereCapsule(new Narrowphase.Sphere(new Vector3f(0f, 1.8f, 0f), 0.5f), cap, c, 0f));
    assertEquals(0.2f, c.depth(), 1e-5f);
    assertFalse(ShapeTests.sphereCapsule(new Narrowphase.Sphere(new Vector3f(0f, 2.1f, 0f), 0.5f), cap, c, 0f));
  }

  @Test
  void parallelCapsulesTouchAlongTheirOverlap() {
    final var a = capsule(-1f, 0f, 0f, 1f, 0f, 0f, 0.5f);
    final var b = capsule(0f, 0.9f, 0f, 3f, 0.9f, 0f, 0.5f);
    final var c = new Narrowphase.Contact();
    assertTrue(ShapeTests.capsuleCapsule(a, b, c, 0f));
    assertEquals(0.1f, c.depth(), 1e-5f);
    assertEquals(1f, c.normal().y, 1e-5f);
    assertEquals(2, c.count(), "both ends of the overlap support it");
    final var crossing = capsule(0f, 0.9f, -1f, 0f, 0.9f, 1f, 0.5f);
    assertTrue(ShapeTests.capsuleCapsule(a, crossing, c, 0f));
    assertEquals(1, c.count());
  }

  @Test
  void aCapsuleLyingOnABoxGetsSupportAtBothEnds() {
    final var floor = new Entity();
    final var box = floor.add(new BoxCollider(5f, 0.5f, 5f));
    floor.transform().position().set(0f, -0.5f, 0f);
    final var cap = capsule(-1f, 0.45f, 0f, 1f, 0.45f, 0f, 0.5f);
    final var c = new Narrowphase.Contact();
    assertTrue(ShapeTests.capsuleBox(cap, box.world(), c, 0f));
    assertEquals(-1f, c.normal().y, 1e-4f, "from the capsule down into the box");
    assertEquals(0.05f, c.depth(), 1e-3f);
    assertTrue(c.count() >= 2);
  }

  @Test
  void raysHitTheBodyAndTheCapsOfACapsule() {
    final var cap = capsule(0f, -1f, -10f, 0f, 1f, -10f, 0.5f);
    final var side = ShapeTests.rayCapsule(cap, new Vector3f(0f, 0.5f, 0f), new Vector3f(0f, 0f, -1f), 100f);
    assertNotNull(side);
    assertEquals(9.5f, side.distance(), 1e-4f);
    assertEquals(1f, side.normal().z, 1e-4f);
    final var top = ShapeTests.rayCapsule(cap, new Vector3f(0f, 5f, -10f), new Vector3f(0f, -1f, 0f), 100f);
    assertEquals(3.5f, top.distance(), 1e-4f);
    assertEquals(1f, top.normal().y, 1e-4f);
    assertEquals(null, ShapeTests.rayCapsule(cap, new Vector3f(2f, 0f, 0f), new Vector3f(0f, 0f, -1f), 100f));
  }

  @Test
  void capsuleInertiaMatchesASphereWhenItHasNoLength() {
    final var e = new Entity();
    final var cap = e.add(new CapsuleCollider(0.5f, 0f));
    cap.refresh();
    final var m = new org.joml.Matrix3f().zero();
    cap.addCachedInertia(2f, m);
    assertEquals(0.4f * 2f * 0.25f, m.m00, 1e-5f);
    assertEquals(0.4f * 2f * 0.25f, m.m11, 1e-5f);
    final var tall = new Entity().add(new CapsuleCollider(0.5f, 1f));
    tall.refresh();
    m.zero();
    tall.addCachedInertia(2f, m);
    assertTrue(m.m00 > m.m11 * 2f, "a long capsule turns end over end much harder than about its own axis");
    assertEquals(m.m00, m.m22, 1e-5f);
  }

  // ---- capsule bodies ----------------------------------------------------------------------------------------

  @Test
  void anUprightCapsuleCharacterStandsOnTheFloor() {
    final var floor = new Entity("floor");
    floor.transform().position().set(0f, -0.5f, 0f);
    floor.add(new BoxCollider(20f, 0.5f, 20f));
    this.scene.add(floor);
    final var player = body(new CapsuleCollider(0.4f, 0.5f), 0f, 3f, 0f);
    player.get(Rigidbody.class).lockRotation = true;
    simulate(2.0);
    assertEquals(0.9f, player.transform().position().y, 0.03f);
  }

  @Test
  void aTippedCapsuleFallsOverAndLiesOnItsSide() {
    floorMesh(20f, 0f);
    final var c = body(new CapsuleCollider(0.3f, 0.8f), 0f, 1.2f, 0f);
    c.transform().setEulerDegrees(0f, 0f, 10f);
    simulate(4.0);
    assertEquals(0.3f, c.transform().position().y, 0.05f, "on its side on a mesh floor");
  }

  // ---- meshes ------------------------------------------------------------------------------------------------

  @Test
  void boxesSpheresAndCapsulesRestOnAMesh() {
    floorMesh(10f, 0f);
    final var crate = body(new BoxCollider(0.5f, 0.5f, 0.5f), -3f, 2f, 0f);
    final var ball = body(new SphereCollider(0.5f), 0f, 2f, 0f);
    final var pill = body(new CapsuleCollider(0.4f, 0.4f), 3f, 2f, 0f);
    pill.get(Rigidbody.class).lockRotation = true;
    simulate(3.0);
    assertEquals(0.5f, crate.transform().position().y, 0.03f);
    assertEquals(0.5f, ball.transform().position().y, 0.03f);
    assertEquals(0.8f, pill.transform().position().y, 0.03f);
    final var up = crate.transform().rotation().transform(new Vector3f(0f, 1f, 0f));
    assertTrue(up.y > 0.999f, "a crate on the seam between two triangles does not tip");
  }

  @Test
  void aBallRollsDownAMeshRamp() {
    final var ramp = new Entity("ramp");
    // face up, then tilt 20 degrees so the surface descends towards +x
    ramp.transform().rotation().rotateZ((float) Math.toRadians(-20)).rotateX((float) Math.toRadians(-90));
    ramp.add(new MeshCollider(MeshFactory.quad(30f, 10f)));
    this.scene.add(ramp);
    final var ball = body(new SphereCollider(0.5f), -5f, 3f, 0f);
    simulate(2.0);
    assertTrue(ball.transform().position().x > -3f, "rolled downhill");
    // still on the surface: height above the tilted plane is the radius
    final var n = new Vector3f(0f, 0f, 1f);
    ramp.transform().rotation().transform(n);
    assertEquals(0.5f, ball.transform().position().dot(n), 0.05f);
  }

  @Test
  void oneSidedTrianglesIgnoreWhatIsBehindThem() {
    final var mesh = floorMesh(10f, 0f);
    final var below = body(new SphereCollider(0.5f), 0f, -1f, 0f);
    simulate(0.5);
    assertTrue(below.transform().position().y < -1.5f, "falls on, nothing pushes it up through the floor");

    mesh.get(MeshCollider.class).twoSided = true;
    final var under = body(new SphereCollider(0.5f), 3f, -0.3f, 0f);
    under.get(Rigidbody.class).useGravity = false;
    simulate(0.5);
    assertTrue(under.transform().position().y < -0.45f, "two-sided: pushed out of the underside");
  }

  @Test
  void raysAndOverlapsSeeMeshesAndCapsules() {
    floorMesh(10f, 0f);
    final var pill = new Entity("pill");
    pill.transform().position().set(0f, 1f, -5f);
    pill.add(new CapsuleCollider(0.5f, 0.5f));
    this.scene.add(pill);
    this.scene.flush();
    final var down = this.physics.raycast(this.scene, new Vector3f(2f, 5f, 0f), new Vector3f(0f, -1f, 0f), 20f);
    assertEquals(5f, down.orElseThrow().distance(), 1e-4f);
    assertEquals(1f, down.get().normal().y, 1e-5f);
    assertTrue(this.physics.raycast(this.scene, new Vector3f(2f, -5f, 0f), new Vector3f(0f, 1f, 0f), 20f).isEmpty(),
        "a one-sided floor is not hit from below");
    final var ahead = this.physics.raycast(this.scene, new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, -1f), 20f);
    assertEquals(pill, ahead.orElseThrow().entity());
    assertEquals(4.5f, ahead.get().distance(), 1e-4f);

    final var hits = this.physics.overlapSphere(this.scene, new Vector3f(0f, 0.3f, -5f), 0.4f, c -> true);
    assertEquals(2, hits.size(), "the floor and the capsule");
  }

  @Test
  void manyBodiesSettleOnABigTerrainMesh() {
    // a 40 x 40 grid of gentle hills: 3200 triangles
    final int n = 40;
    final float[] pos = new float[(n + 1) * (n + 1) * 3];
    for (int z = 0; z <= n; z++) {
      for (int x = 0; x <= n; x++) {
        final int i = (z * (n + 1) + x) * 3;
        pos[i] = x - n / 2f;
        pos[i + 1] = 0.6f * (float) (Math.sin(x * 0.4) * Math.cos(z * 0.3));
        pos[i + 2] = z - n / 2f;
      }
    }
    final int[] idx = new int[n * n * 6];
    int k = 0;
    for (int z = 0; z < n; z++) {
      for (int x = 0; x < n; x++) {
        final int a = z * (n + 1) + x;
        final int b = a + n + 1;
        // counter-clockwise seen from above (+y)
        idx[k++] = a;
        idx[k++] = b;
        idx[k++] = a + 1;
        idx[k++] = a + 1;
        idx[k++] = b;
        idx[k++] = b + 1;
      }
    }
    final var terrain = new Entity("terrain");
    terrain.add(new MeshCollider(pos, idx));
    this.scene.add(terrain);
    final List<Entity> bodies = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      final float x = (i % 10) * 3f - 14f;
      final float z = (i / 10) * 3f - 8f;
      final Collider shape = i % 3 == 0 ? new BoxCollider(0.4f, 0.4f, 0.4f)
          : i % 3 == 1 ? new SphereCollider(0.4f) : new CapsuleCollider(0.3f, 0.3f);
      bodies.add(body(shape, x, 3f, z));
    }
    final long start = System.nanoTime();
    simulate(4.0);
    final double msPerStep = (System.nanoTime() - start) / 1e6 / 240;
    for (var b : bodies) {
      assertTrue(b.transform().position().y > -0.7f, "nothing fell through: " + b.transform().position());
    }
    System.out.printf("terrain: %.2f ms per step%n", msPerStep);
    assertTrue(msPerStep < 20, "a step took " + msPerStep + " ms");
  }

  @Test
  void aCrateShovedIntoAMeshCornerStaysInIt() {
    floorMesh(10f, 0f);
    final var wall = new Entity("wall");
    wall.transform().position().set(2f, 0f, 0f);
    wall.transform().setEulerDegrees(0f, -90f, 0f); // the quad faces +Z; turned to face -x, into the room
    wall.add(new MeshCollider(MeshFactory.quad(20f, 20f)));
    this.scene.add(wall);
    final var crate = body(new BoxCollider(0.5f, 0.5f, 0.5f), 0f, 0.5f, 0f);
    crate.get(Rigidbody.class).velocity.set(6f, 0f, 0f);
    crate.get(Rigidbody.class).restitution = 0f; // no rebound: it must end up flush with the wall
    simulate(2.0);
    final var p = crate.transform().position();
    assertEquals(1.5f, p.x, 0.05f, "against the wall");
    assertEquals(0.5f, p.y, 0.05f, "on the floor");
  }
}
