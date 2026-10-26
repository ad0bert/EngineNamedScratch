package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;

class JointLimitsTest {
  private static final double DT = 1.0 / 60.0;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
  private final Scene scene = new Scene();

  private void simulate(double seconds) {
    this.scene.flush();
    for (int i = 0; i < Math.round(seconds / DT); i++) {
      this.physics.update(this.scene, DT);
    }
  }

  private Entity box(float x, float y, float z, float hx, float hy, float hz) {
    final var e = new Entity("box");
    e.transform().position().set(x, y, z);
    e.add(new BoxCollider(hx, hy, hz));
    e.add(new Rigidbody());
    this.scene.add(e);
    return e;
  }

  // ---- hinge -------------------------------------------------------------------------------------------------

  /** A horizontal lever hinged at one end about z; gravity turns it down until the limit holds it. */
  private HingeJoint lever(float mass) {
    final var lever = box(0.5f, 5f, 0f, 0.5f, 0.05f, 0.05f);
    lever.get(Rigidbody.class).mass = mass;
    final var hinge = lever.add(new HingeJoint());
    hinge.setWorldAnchor(new Vector3f(0f, 5f, 0f));
    hinge.setWorldAxis(new Vector3f(0f, 0f, 1f));
    hinge.limit = true;
    hinge.lowerAngle = -0.5f;
    hinge.upperAngle = 0.5f;
    return hinge;
  }

  @Test
  void aHingeLimitHoldsALeverUnderLoad() {
    final var hinge = lever(20f);
    final var body = hinge.entity().get(Rigidbody.class);
    this.scene.flush();
    float largest = 0f;
    for (int i = 0; i < 300; i++) {
      this.physics.update(this.scene, DT);
      largest = Math.max(largest, Math.abs(hinge.angle()));
    }
    // the lever turns clockwise (about -z): the world (B) turns the other way relative to it
    assertEquals(0.5f, hinge.angle(), 0.02f, "rests on the limit");
    assertTrue(largest < 0.55f, "never far past it: " + largest);
    final var tip = hinge.entity().transform().rotation().transform(new Vector3f(1f, 0f, 0f));
    assertEquals(-Math.sin(0.5), tip.y, 0.03f, "tilted down by the limit angle");
    assertTrue(body.isSleeping(), "and falls asleep there");
  }

  @Test
  void aHingeMotorStopsAtTheLimit() {
    final var wheel = box(0f, 0f, 0f, 0.5f, 0.5f, 0.5f);
    final var body = wheel.get(Rigidbody.class);
    body.useGravity = false;
    body.angularDamping = 0f;
    final var hinge = wheel.add(new HingeJoint());
    hinge.setWorldAnchor(new Vector3f());
    hinge.setWorldAxis(new Vector3f(1f, 0f, 0f));
    hinge.motor = true;
    hinge.motorSpeed = -4f;
    hinge.maxMotorTorque = 50f;
    hinge.limit = true;
    hinge.lowerAngle = -1f;
    hinge.upperAngle = 1f;
    simulate(0.1);
    assertEquals(4f, body.angularVelocity.x, 0.2f, "the motor spins it up");
    simulate(1.5);
    assertEquals(-1f, hinge.angle(), 0.02f, "and the limit stops it");
    assertEquals(0f, body.angularVelocity.length(), 0.05f);
    hinge.motorSpeed = 4f; // and back to the other stop
    simulate(1.5);
    assertEquals(1f, hinge.angle(), 0.02f);
  }

  @Test
  void aBouncyLimitThrowsTheBodyBack() {
    final var wheel = box(0f, 0f, 0f, 0.5f, 0.5f, 0.5f);
    final var body = wheel.get(Rigidbody.class);
    body.useGravity = false;
    body.angularDamping = 0f;
    final var hinge = wheel.add(new HingeJoint());
    hinge.setWorldAnchor(new Vector3f());
    hinge.setWorldAxis(new Vector3f(0f, 1f, 0f));
    hinge.limit = true;
    hinge.limitRestitution = 0.5f;
    body.angularVelocity.set(0f, -3f, 0f); // the world turns +y relative to the body: towards the upper limit
    simulate(0.5);
    assertTrue(body.angularVelocity.y > 1f, "bounced: " + body.angularVelocity);
    assertTrue(hinge.angle() < 0.8f);
  }

  // ---- ball --------------------------------------------------------------------------------------------------

  @Test
  void aConeKeepsAPendulumInside() {
    final var bob = box(0f, 4f, 0f, 0.1f, 0.5f, 0.1f);
    final var joint = bob.add(new BallJoint());
    joint.setWorldAnchor(new Vector3f(0f, 5f, 0f));
    joint.setWorldAxis(new Vector3f(0f, -1f, 0f));
    joint.coneAngle = (float) Math.toRadians(30);
    bob.get(Rigidbody.class).velocity.set(6f, 0f, 2f); // would swing far above horizontal
    this.scene.flush();
    float largest = 0f;
    for (int i = 0; i < 240; i++) {
      this.physics.update(this.scene, DT);
      largest = Math.max(largest, joint.swingAngle());
      assertEquals(1f, bob.transform().position().distance(0f, 5f, 0f), 0.03f, "step " + i);
    }
    assertTrue(largest < Math.toRadians(30) + 0.05, "stays in the cone: " + Math.toDegrees(largest));
    assertTrue(largest > Math.toRadians(30) - 0.05, "but reached its edge");
  }

  @Test
  void aTwistLimitStopsASpinAboutTheAxis() {
    final var bob = box(0f, 4f, 0f, 0.1f, 0.5f, 0.1f);
    final var body = bob.get(Rigidbody.class);
    body.useGravity = false;
    body.angularDamping = 0f;
    final var joint = bob.add(new BallJoint());
    joint.setWorldAnchor(new Vector3f(0f, 5f, 0f));
    joint.setWorldAxis(new Vector3f(0f, -1f, 0f));
    joint.limitTwist = true;
    joint.lowerTwist = -0.4f;
    joint.upperTwist = 0.4f;
    body.angularVelocity.set(0f, 5f, 0f);
    this.scene.flush();
    for (int i = 0; i < 120; i++) {
      this.physics.update(this.scene, DT);
      final float twist = joint.twistAngle();
      assertTrue(twist > -0.45f && twist < 0.45f, "step " + i + ": " + twist);
    }
    assertEquals(0f, body.angularVelocity.y, 0.1f, "stopped at the limit");
  }

  @Test
  void limitedJointsDoNotAllocateInTheStep() {
    final var hinge = lever(5f);
    hinge.motor = true;
    hinge.motorSpeed = 2f;
    final var bob = box(0f, 2f, 0f, 0.1f, 0.5f, 0.1f);
    final var joint = bob.add(new BallJoint());
    joint.setWorldAnchor(new Vector3f(0f, 3f, 0f));
    joint.setWorldAxis(new Vector3f(0f, -1f, 0f));
    joint.coneAngle = 0.3f;
    joint.limitTwist = true;
    bob.get(Rigidbody.class).velocity.set(3f, 0f, 0f);
    bob.get(Rigidbody.class).angularVelocity.set(0f, 4f, 0f);
    bob.get(Rigidbody.class).allowSleep = false;
    simulate(0.5); // warm up the pools
    final var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    final long id = Thread.currentThread().threadId();
    final long before = threads.getThreadAllocatedBytes(id);
    for (int i = 0; i < 60; i++) {
      this.physics.update(this.scene, DT);
    }
    final long allocated = threads.getThreadAllocatedBytes(id) - before;
    assertTrue(allocated < 1024, "allocated " + allocated + " bytes");
  }

  @Test
  void aRagdollLikeChainWithLimitsComesToRestAndSleeps() {
    final List<Entity> limbs = new ArrayList<>();
    final List<BallJoint> joints = new ArrayList<>();
    Entity previous = null;
    for (int i = 0; i < 5; i++) {
      final var limb = box(0.5f * i + 0.25f, 10f, 0f, 0.25f, 0.08f, 0.08f); // starts horizontal, falls down
      limb.get(Rigidbody.class).linearDamping = 1f;
      final var joint = limb.add(new BallJoint());
      joint.connected = previous;
      joint.setWorldAnchor(new Vector3f(0.5f * i, 10f, 0f));
      joint.setWorldAxis(new Vector3f(1f, 0f, 0f));
      joint.coneAngle = previous == null ? (float) Math.PI : 0.4f;
      joint.limitTwist = true;
      joint.lowerTwist = -0.2f;
      joint.upperTwist = 0.2f;
      limbs.add(limb);
      joints.add(joint);
      previous = limb;
    }
    simulate(20.0);
    for (int i = 1; i < joints.size(); i++) {
      assertTrue(joints.get(i).swingAngle() < 0.45f, "joint " + i + ": " + joints.get(i).swingAngle());
      assertTrue(Math.abs(joints.get(i).twistAngle()) < 0.25f, "joint " + i + ": " + joints.get(i).twistAngle());
    }
    final var end = limbs.get(4).transform().position();
    assertTrue(end.y < 8f, "hangs down: " + end);
    assertEquals(5, this.physics.stats().sleepingBodies, "and comes to rest");
  }
}
