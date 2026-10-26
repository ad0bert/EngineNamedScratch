package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageQueue;
import engine.messaging.MessageService;

class PhysicsTest {
  private static final double DT = 1.0 / 60.0;

  private final MessageService messages = new MessageService();
  private final PhysicsSystem physics = new PhysicsSystem(this.messages);
  private final Scene scene = new Scene();

  private Entity floor() {
    final var e = new Entity("floor");
    e.transform().position().set(0f, -0.5f, 0f); // top surface at y = 0
    e.add(new BoxCollider(20f, 0.5f, 20f));
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
    for (int i = 0; i < (int) (seconds / DT); i++) {
      this.physics.update(this.scene, DT);
    }
  }

  private static Narrowphase.Box box(float x, float y, float rotZDegrees, float half) {
    final var e = new Entity();
    e.transform().position().set(x, y, 0f);
    e.transform().setEulerDegrees(0f, 0f, rotZDegrees);
    final var c = e.add(new BoxCollider(half, half, half));
    return c.world();
  }

  @Test
  void crateFallsAndComesToRestOnTheFloor() {
    floor();
    final var c = crate(0f, 3f, 0f);
    simulate(3.0);
    assertEquals(0.5f, c.transform().position().y, 0.03f);
    assertEquals(0f, c.get(Rigidbody.class).velocity.length(), 0.3f);
  }

  @Test
  void crateStacksOnAnotherCrate() {
    floor();
    final var bottom = crate(0f, 0.6f, 0f);
    final var top = crate(0f, 2.0f, 0f);
    simulate(4.0);
    assertEquals(0.5f, bottom.transform().position().y, 0.05f);
    assertEquals(1.5f, top.transform().position().y, 0.08f);
    assertEquals(0f, top.transform().position().x, 0.05f);
  }

  @Test
  void fastImpactBouncesSlowImpactDoesNot() {
    floor();
    final var c = crate(0f, 0.5f, 0f);
    c.get(Rigidbody.class).velocity.set(0f, -8f, 0f);
    c.get(Rigidbody.class).useGravity = false;
    c.get(Rigidbody.class).restitution = 0.5f;
    simulate(0.1);
    assertTrue(c.get(Rigidbody.class).velocity.y > 2f, "should rebound upwards");
  }

  @Test
  void frictionStopsASlidingCrate() {
    floor();
    final var c = crate(0f, 0.5f, 0f);
    c.get(Rigidbody.class).velocity.set(4f, 0f, 0f);
    simulate(3.0);
    assertTrue(Math.abs(c.get(Rigidbody.class).velocity.x) < 0.1f);
  }

  @Test
  void kinematicBodyPushesDynamicOneButIsNotPushed() {
    final var pusher = new Entity("pusher");
    pusher.transform().position().set(-2f, 0f, 0f);
    pusher.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    pusher.add(new Rigidbody()).kinematic = true;
    this.scene.add(pusher);
    final var target = crate(0f, 0f, 0f);
    target.get(Rigidbody.class).useGravity = false;
    this.scene.flush();

    for (int i = 0; i < 120; i++) {
      pusher.transform().position().x += 0.03f; // moved by "game code" at 1.8 units/s
      this.physics.update(this.scene, DT);
    }
    assertTrue(target.transform().position().x > 0.3f, "target must have been pushed");
    assertEquals(-2f + 120 * 0.03f, pusher.transform().position().x, 1e-3f);
  }

  @Test
  void triggersReportOverlapButNeverPush() {
    final var zone = new Entity("zone");
    zone.add(new BoxCollider(2f, 2f, 2f)).trigger = true;
    this.scene.add(zone);
    final var c = crate(0f, 0f, 0f);
    c.get(Rigidbody.class).useGravity = false;
    final List<CollisionEvent> events = new ArrayList<>();
    this.messages.register(m -> events.add((CollisionEvent) m.getPayload()), MessageQueue.COLLISION);

    simulate(0.5);
    assertEquals(0f, c.transform().position().x, 1e-5f);
    assertEquals(1, events.size());
    assertTrue(events.get(0).started());

    c.transform().position().set(10f, 0f, 0f);
    simulate(0.1);
    assertEquals(2, events.size());
    assertFalse(events.get(1).started());
  }

  @Test
  void staticPairsProduceNoEvents() {
    final var a = new Entity();
    a.add(new BoxCollider(1f, 1f, 1f));
    final var b = new Entity();
    b.add(new BoxCollider(1f, 1f, 1f));
    this.scene.add(a);
    this.scene.add(b);
    final List<Object> events = new ArrayList<>();
    this.messages.register(m -> events.add(m), MessageQueue.COLLISION);
    simulate(0.2);
    assertTrue(events.isEmpty());
  }

  @Test
  void rotatedBoxesUseTheirTrueShapeNotTheirAabb() {
    final var a = box(0f, 0f, 0f, 0.5f);
    // a unit square turned 45 degrees is a diamond with half-diagonal 0.707: tip at x = 1.25 - 0.707 = 0.543
    assertNull(Narrowphase.boxBox(a, box(1.25f, 0f, 45f, 0.5f)));
    // moved in a little the tip touches
    final var c = Narrowphase.boxBox(a, box(1.15f, 0f, 45f, 0.5f));
    assertNotNull(c);
    assertEquals(1f, c.normal().x, 1e-3f);
    assertTrue(c.depth() > 0f && c.depth() < 0.1f);
  }

  @Test
  void boxBoxNormalPointsFromAToBAndDepthIsPenetration() {
    final var c = Narrowphase.boxBox(box(0f, 0f, 0f, 0.5f), box(0.8f, 0.05f, 0f, 0.5f));
    assertNotNull(c);
    assertEquals(1f, c.normal().x, 1e-4f);
    assertEquals(0.2f, c.depth(), 1e-4f);
    final var reversed = Narrowphase.boxBox(box(0.8f, 0.05f, 0f, 0.5f), box(0f, 0f, 0f, 0.5f));
    assertEquals(-1f, reversed.normal().x, 1e-4f);
  }

  @Test
  void sphereContacts() {
    final var s1 = new Narrowphase.Sphere(new Vector3f(0f, 0f, 0f), 1f);
    final var s2 = new Narrowphase.Sphere(new Vector3f(1.5f, 0f, 0f), 1f);
    final var cc = Narrowphase.sphereSphere(s1, s2);
    assertEquals(0.5f, cc.depth(), 1e-5f);
    assertEquals(1f, cc.normal().x, 1e-5f);
    assertNull(Narrowphase.sphereSphere(s1, new Narrowphase.Sphere(new Vector3f(3f, 0f, 0f), 1f)));

    final var box = box(0f, 0f, 0f, 0.5f);
    final var above = Narrowphase.sphereBox(new Narrowphase.Sphere(new Vector3f(0f, 0.9f, 0f), 0.5f), box);
    assertEquals(0.1f, above.depth(), 1e-4f);
    assertEquals(-1f, above.normal().y, 1e-4f); // sphere -> box points down
    assertNull(Narrowphase.sphereBox(new Narrowphase.Sphere(new Vector3f(0f, 1.2f, 0f), 0.5f), box));

    final var inside = Narrowphase.sphereBox(new Narrowphase.Sphere(new Vector3f(0.4f, 0f, 0f), 0.2f), box);
    assertNotNull(inside); // centre inside the box: leave through the nearest (+x) face
    assertEquals(-1f, inside.normal().x, 1e-4f);
    assertEquals(0.2f + 0.1f, inside.depth(), 1e-4f);
  }

  /** How far the entity's local up axis points up in the world (1 = upright, 0 = on its side). */
  private static float uprightness(Entity e) {
    return e.transform().rotation().transform(new Vector3f(0f, 1f, 0f)).y;
  }

  @Test
  void aTiltedCrateLandsOnItsCornerAndFallsFlat() {
    floor();
    final var c = crate(0f, 2f, 0f);
    c.transform().setEulerDegrees(35f, 0f, 25f);
    simulate(4.0);
    final float up = Math.abs(uprightness(c));
    assertTrue(up > 0.99f, "settles on a face, uprightness " + up);
    assertEquals(0.5f, c.transform().position().y, 0.04f);
    assertTrue(c.get(Rigidbody.class).angularVelocity.length() < 0.5f);
  }

  @Test
  void anOffCentreHitSpinsABody() {
    final var c = crate(0f, 0f, 0f);
    c.get(Rigidbody.class).useGravity = false;
    final var hitter = new Entity("hitter");
    hitter.transform().position().set(-1.45f, 0.45f, 0f); // meets the crate's upper left edge
    hitter.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    final var hitterBody = hitter.add(new Rigidbody());
    hitterBody.useGravity = false;
    hitterBody.velocity.set(6f, 0f, 0f);
    this.scene.add(hitter);
    simulate(0.5);
    assertTrue(c.get(Rigidbody.class).angularVelocity.z < -0.5f, "turned clockwise about z: "
        + c.get(Rigidbody.class).angularVelocity);
  }

  @Test
  void aLockedBodyNeverRotates() {
    floor();
    final var c = crate(0f, 2f, 0f);
    c.transform().setEulerDegrees(35f, 0f, 25f);
    c.get(Rigidbody.class).lockRotation = true;
    final var before = new org.joml.Quaternionf(c.transform().rotation());
    simulate(2.0);
    assertTrue(before.equals(c.transform().rotation()));
  }

  @Test
  void aSphereRollsWithoutSlipping() {
    floor();
    final var ball = new Entity("ball");
    ball.transform().position().set(0f, 0.5f, 0f);
    ball.add(new SphereCollider(0.5f));
    final var body = ball.add(new Rigidbody());
    body.linearDamping = 0f;
    body.angularDamping = 0f;
    body.velocity.set(3f, 0f, 0f);
    this.scene.add(ball);
    simulate(1.0);
    final float v = body.velocity.x;
    assertTrue(v > 1.5f, "keeps rolling, speed " + v);
    assertEquals(-v / 0.5f, body.angularVelocity.z, 0.4f); // rolling: omega = v / r, turning clockwise seen from +z
    assertEquals(0f, body.angularVelocity.x, 0.2f);
  }

  @Test
  void aTallStackStaysStanding() {
    floor();
    final List<Entity> stack = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      stack.add(crate(0f, 0.5f + i * 1.02f, 0f));
    }
    simulate(6.0);
    final var top = stack.get(5);
    assertEquals(5.5f, top.transform().position().y, 0.15f);
    assertEquals(0f, top.transform().position().x, 0.1f);
    assertEquals(0f, top.transform().position().z, 0.1f);
    assertTrue(uprightness(top) > 0.99f);
  }

  @Test
  void aBoxRestingFlatHasFourContactPoints() {
    final var c = Narrowphase.boxBox(box(0f, 0f, 0f, 0.5f), box(0.8f, 0.05f, 0f, 0.5f));
    assertEquals(4, c.count());
    for (int i = 0; i < c.count(); i++) {
      assertEquals(0.2f, c.depth(i), 1e-4f);
      assertEquals(0.4f, c.point(i).x, 1e-3f); // halfway between the faces at x = 0.5 and x = 0.3
    }
  }

  @Test
  void aTurnedBoxTouchesAlongItsEdgeOnly() {
    final var c = Narrowphase.boxBox(box(0f, 0f, 0f, 0.5f), box(1.15f, 0f, 45f, 0.5f));
    assertEquals(2, c.count()); // the diamond's tip is an edge along z, poking into A's face
    for (int i = 0; i < 2; i++) {
      assertEquals(0.4715f, c.point(i).x, 0.01f); // halfway between the tip (x = 0.443) and A's face (x = 0.5)
      assertEquals(0f, c.point(i).y, 1e-3f);
      assertEquals(0.5f, Math.abs(c.point(i).z), 1e-3f);
    }
  }

  @Test
  void sphereRollsToRestOnFloor() {
    floor();
    final var ball = new Entity("ball");
    ball.transform().position().set(0f, 2f, 0f);
    ball.add(new SphereCollider(0.5f));
    ball.add(new Rigidbody());
    this.scene.add(ball);
    simulate(3.0);
    assertEquals(0.5f, ball.transform().position().y, 0.03f);
  }
}
