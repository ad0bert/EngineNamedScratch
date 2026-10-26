package engine.physics;

import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Keeps the two anchors together and lets the bodies turn freely about them: a ball-and-socket (chains, ragdolls). */
public class BallJoint extends Joint {
  private final Matrix3f inverse = new Matrix3f();
  private final Vector3f accumulated = new Vector3f();
  private final Vector3f rhs = new Vector3f();
  private final Vector3f velocity = new Vector3f();

  @Override
  void prepare(float dt) {
    pointMatrix(this.inverse).invert();
    applyLinear(this.accumulated); // warm start
  }

  @Override
  void solve() {
    relativeVelocity(this.velocity);
    this.rhs.set(this.velocity).negate();
    this.inverse.transform(this.rhs); // the impulse that stops the anchors drifting apart
    this.accumulated.add(this.rhs);
    applyLinear(this.rhs);
    this.stepImpulse = this.accumulated.length();
  }
}
