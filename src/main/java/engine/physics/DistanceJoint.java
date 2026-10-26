package engine.physics;

import org.joml.Vector3f;

/**
 * Keeps the distance between the anchors within {@link #minDistance}..{@link #maxDistance}: equal values make a
 * rigid rod, {@code minDistance = 0} a rope that only pulls. The bodies turn freely.
 */
public class DistanceJoint extends Joint {
  public float minDistance;
  public float maxDistance = 1f;

  private final Vector3f axis = new Vector3f();
  private final Vector3f velocity = new Vector3f();
  private final Vector3f impulse = new Vector3f();
  private float mass;
  private float accumulated;
  /** -1 pulls only (stretched), +1 pushes only (compressed), 0 both (rod), 2 inactive. */
  private int mode;

  public DistanceJoint() {
  }

  public DistanceJoint(float minDistance, float maxDistance) {
    this.minDistance = minDistance;
    this.maxDistance = maxDistance;
  }

  @Override
  void prepare(float dt) {
    this.axis.set(this.pB).sub(this.pA);
    final float length = this.axis.length();
    if (length > 1e-6f) {
      this.axis.div(length);
    } else {
      this.axis.set(0f, 1f, 0f);
    }
    if (this.maxDistance - this.minDistance < 1e-5f) {
      this.mode = 0;
    } else if (length > this.maxDistance - 1e-4f) {
      this.mode = -1;
    } else if (length < this.minDistance + 1e-4f) {
      this.mode = 1;
    } else {
      this.mode = 2;
      this.accumulated = 0f;
      return;
    }
    this.mass = linearMass(this.axis);
    applyLinear(this.impulse.set(this.axis).mul(this.accumulated));
  }

  /** Moves the bodies so the distance is back within the range. */
  @Override
  void correctPositions(PhysicsSystem physics) {
    if (!isEnabled() || !anchors()) {
      return;
    }
    final var d = this.tmp.set(this.pB).sub(this.pA);
    final float length = d.length();
    if (length < 1e-6f) {
      return;
    }
    final float target = Math.max(this.minDistance, Math.min(this.maxDistance, length));
    final float error = length - target;
    if (Math.abs(error) < 1e-5f) {
      return;
    }
    d.mul(error / length); // A moves along it, B against it
    final float total = this.invA + this.invB;
    if (this.invA > 0f) {
      PhysicsSystem.shift(this.bodyA, this.tmp2.set(d).mul(CORRECTION * this.invA / total));
    }
    if (this.invB > 0f) {
      PhysicsSystem.shift(this.bodyB, this.tmp2.set(d).mul(-CORRECTION * this.invB / total));
    }
  }

  @Override
  void solve() {
    if (this.mode == 2) {
      return;
    }
    final float speed = relativeVelocity(this.velocity).dot(this.axis);
    float total = this.accumulated - this.mass * speed;
    if (this.mode < 0) {
      total = Math.min(total, 0f); // a stretched rope pulls (impulse on B towards A)
    } else if (this.mode == 1) {
      total = Math.max(total, 0f);
    }
    final float delta = total - this.accumulated;
    this.accumulated = total;
    applyLinear(this.impulse.set(this.axis).mul(delta));
    this.stepImpulse = Math.abs(this.accumulated);
  }
}
