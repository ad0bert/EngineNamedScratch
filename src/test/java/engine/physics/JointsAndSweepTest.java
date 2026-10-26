package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;
import engine.render.MeshFactory;

class JointsAndSweepTest {
  private static final double DT = 1.0 / 60.0;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
  private final Scene scene = new Scene();

  private void simulate(double seconds) {
    this.scene.flush();
    for (int i = 0; i < Math.round(seconds / DT); i++) {
      this.physics.update(this.scene, DT);
    }
  }

  private Entity ball(float x, float y, float z, float r) {
    final var e = new Entity("ball");
    e.transform().position().set(x, y, z);
    e.add(new SphereCollider(r));
    e.add(new Rigidbody());
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

  // ---- joints ------------------------------------------------------------------------------------------------

  @Test
  void aPendulumSwingsAndKeepsItsLength() {
    final var bob = ball(2f, 5f, 0f, 0.2f);
    bob.add(new BallJoint()).setWorldAnchor(new Vector3f(0f, 5f, 0f));
    boolean crossed = false;
    this.scene.flush();
    for (int i = 0; i < 180; i++) {
      this.physics.update(this.scene, DT);
      final var p = bob.transform().position();
      assertEquals(2f, p.distance(0f, 5f, 0f), 0.03f, "step " + i);
      crossed |= p.x < -1f;
    }
    assertTrue(crossed, "swung through to the other side");
  }

  @Test
  void aChainHangsFromTheCeilingAndFallsAsleep() {
    final List<Entity> links = new ArrayList<>();
    Entity previous = null;
    for (int i = 0; i < 6; i++) {
      final var link = ball(0.5f * (i + 1), 10f, 0f, 0.2f); // starts horizontal, swings down
      link.get(Rigidbody.class).linearDamping = 1f; // like a chain in thick air: it should settle in the test
      final var joint = link.add(new BallJoint());
      joint.connected = previous;
      joint.setWorldAnchor(new Vector3f(0.5f * i, 10f, 0f));
      links.add(link);
      previous = link;
    }
    simulate(20.0);
    final var end = links.get(5).transform().position();
    assertEquals(0f, end.x, 0.1f, "hangs straight down");
    assertEquals(7f, end.y, 0.1f, "six links of half a metre");
    assertEquals(6, this.physics.stats().sleepingBodies, "and comes to rest");
  }

  @Test
  void aRopeOnlyPulls() {
    final var weight = ball(0f, 9f, 0f, 0.3f);
    weight.add(new DistanceJoint(0f, 3f)).setWorldAnchors(new Vector3f(0f, 9f, 0f), new Vector3f(0f, 10f, 0f));
    simulate(0.2);
    assertTrue(weight.transform().position().y < 8.95f, "slack: falls freely at first");
    simulate(3.0);
    assertEquals(7f, weight.transform().position().y, 0.05f, "caught at the rope's length");
  }

  @Test
  void aRodHoldsTwoBodiesAtAFixedDistance() {
    final var a = ball(0f, 2f, 0f, 0.3f);
    final var b = ball(2f, 2f, 0f, 0.3f);
    final var rod = a.add(new DistanceJoint(2f, 2f));
    rod.connected = b;
    rod.setWorldAnchors(new Vector3f(0f, 2f, 0f), new Vector3f(2f, 2f, 0f));
    a.get(Rigidbody.class).useGravity = false;
    b.get(Rigidbody.class).useGravity = false;
    b.get(Rigidbody.class).velocity.set(-3f, 1f, 0f); // pushed towards a
    simulate(2.0);
    assertEquals(2f, a.transform().position().distance(b.transform().position()), 0.03f);
  }

  @Test
  void aHingedDoorOnlyTurnsAboutItsHinge() {
    final var door = new Entity("door");
    door.transform().position().set(0.5f, 1f, 0f);
    door.add(new BoxCollider(0.5f, 1f, 0.05f));
    final var body = door.add(new Rigidbody());
    body.useGravity = false;
    final var hinge = door.add(new HingeJoint());
    hinge.setWorldAnchor(new Vector3f(0f, 1f, 0f));
    hinge.setWorldAxis(new Vector3f(0f, 1f, 0f));
    this.scene.add(door);
    body.velocity.set(0f, 0.5f, -2f); // a shove at the free edge (and a little upwards)
    body.angularVelocity.set(1f, 0f, 0f);
    simulate(0.5); // about 3 rad/s: a quarter turn or so
    final var up = door.transform().rotation().transform(new Vector3f(0f, 1f, 0f));
    assertTrue(up.y > 0.995f, "stays upright: " + up);
    final var hingeNow = door.transform().worldMatrix(new org.joml.Matrix4f())
        .transformPosition(new Vector3f(hinge.anchor));
    assertEquals(0f, hingeNow.distance(0f, 1f, 0f), 0.03f, "the hinge stays put");
    final var across = door.transform().rotation().transform(new Vector3f(1f, 0f, 0f));
    assertTrue(Math.abs(across.z) > 0.3f, "but it swung open");
  }

  @Test
  void aHingeMotorDrivesAWheel() {
    final var wheel = new Entity("wheel");
    wheel.add(new SphereCollider(0.5f));
    final var body = wheel.add(new Rigidbody());
    body.useGravity = false;
    body.angularDamping = 0f;
    final var hinge = wheel.add(new HingeJoint());
    hinge.setWorldAnchor(new Vector3f());
    hinge.setWorldAxis(new Vector3f(1f, 0f, 0f));
    hinge.motor = true;
    hinge.motorSpeed = -4f; // B is the world: B relative to A, so the wheel spins at +4 about x
    hinge.maxMotorTorque = 50f;
    this.scene.add(wheel);
    simulate(1.0);
    assertEquals(4f, body.angularVelocity.x, 0.1f);
    assertEquals(0f, body.angularVelocity.y, 0.05f);
  }

  @Test
  void anOverloadedJointBreaks() {
    final var heavy = ball(0f, 4f, 0f, 0.3f);
    heavy.get(Rigidbody.class).mass = 100f;
    final var joint = heavy.add(new BallJoint());
    joint.setWorldAnchor(new Vector3f(0f, 5f, 0f));
    joint.breakImpulse = 5f; // 100 kg pulls with about 16 N s per step
    simulate(0.5);
    assertFalse(joint.isEnabled());
    assertTrue(heavy.transform().position().y < 3.5f, "fell once it broke");
  }

  @Test
  void joinedBodiesDoNotCollideUnlessAsked() {
    final var a = crate(0f, 5f, 0f);
    final var b = crate(0.6f, 5f, 0f); // overlapping
    a.get(Rigidbody.class).useGravity = false;
    b.get(Rigidbody.class).useGravity = false;
    final var joint = a.add(new BallJoint());
    joint.connected = b;
    joint.setWorldAnchor(new Vector3f(0.3f, 5f, 0f));
    simulate(0.5);
    assertEquals(0.6f, b.transform().position().x - a.transform().position().x, 0.02f, "not pushed apart");
  }

  // ---- continuous collision ----------------------------------------------------------------------------------

  private Entity thinWall() {
    final var wall = new Entity("wall");
    wall.transform().position().set(0f, 0f, -10f);
    wall.add(new BoxCollider(5f, 5f, 0.05f));
    this.scene.add(wall);
    return wall;
  }

  @Test
  void aBulletDoesNotTunnelThroughAThinWall() {
    thinWall();
    final var bullet = ball(0f, 0f, 0f, 0.1f);
    bullet.get(Rigidbody.class).useGravity = false;
    bullet.get(Rigidbody.class).velocity.set(0f, 0f, -290f); // about 5 m per step, 10 cm wall
    simulate(0.5);
    assertTrue(bullet.transform().position().z > -10f, "stopped in front: " + bullet.transform().position());
  }

  @Test
  void withoutContinuousCollisionItWould() {
    this.physics.continuousCollision = false;
    thinWall();
    final var bullet = ball(0f, 0f, 0f, 0.1f);
    bullet.get(Rigidbody.class).useGravity = false;
    bullet.get(Rigidbody.class).velocity.set(0f, 0f, -290f); // steps land at -9.67 and -14.5: never touching
    simulate(0.5);
    assertTrue(bullet.transform().position().z < -10f, "the test setup really tunnels");
  }

  @Test
  void fastBodiesDoNotFallThroughAMeshFloor() {
    final var floor = new Entity("floor");
    floor.transform().setEulerDegrees(-90f, 0f, 0f);
    floor.add(new MeshCollider(MeshFactory.quad(20f, 20f)));
    this.scene.add(floor);
    final var b = crate(0f, 30f, 0f);
    b.get(Rigidbody.class).velocity.set(0f, -150f, 0f);
    b.get(Rigidbody.class).restitution = 0f; // it would rightly bounce 40 m high otherwise
    simulate(2.0);
    assertEquals(0.5f, b.transform().position().y, 0.1f);
  }

  // ---- query sync --------------------------------------------------------------------------------------------

  @Test
  void syncQueriesShowsCollidersMovedAfterTheStep() {
    final var box = new Entity("box");
    box.transform().position().set(0f, 0f, -5f);
    box.add(new BoxCollider(0.5f, 0.5f, 0.5f));
    this.scene.add(box);
    simulate(DT);
    box.transform().position().set(20f, 0f, -5f); // moved by game code after the step
    final var origin = new Vector3f(20f, 0f, 0f);
    final var dir = new Vector3f(0f, 0f, -1f);
    assertTrue(this.physics.raycast(this.scene, origin, dir, 50f).isEmpty(), "the grid still has it at x = 0");
    this.physics.syncQueries(this.scene);
    assertEquals(box, this.physics.raycast(this.scene, origin, dir, 50f).orElseThrow().entity());
  }
}
