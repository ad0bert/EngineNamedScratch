package engine.physics;

import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * A ball joint that only allows turning about one axis (doors, wheels, levers). {@link #axis} is in A's local space,
 * {@link #connectedAxis} in B's (world space without B); {@link #setWorldAxis} sets both. An optional motor drives
 * the relative spin about the axis towards {@link #motorSpeed} with at most {@link #maxMotorTorque}.
 */
public class HingeJoint extends Joint {
  public final Vector3f axis = new Vector3f(0f, 1f, 0f);
  public final Vector3f connectedAxis = new Vector3f(0f, 1f, 0f);
  public boolean motor;
  /** Target spin of B relative to A about the axis, in rad/s (right-hand rule). */
  public float motorSpeed;
  /** N m; the motor never pushes harder than this. */
  public float maxMotorTorque = 10f;

  private final Matrix3f pointInverse = new Matrix3f();
  private final Vector3f pointImpulse = new Vector3f();
  private final Vector3f rhs = new Vector3f();
  private final Vector3f velocity = new Vector3f();
  private final Vector3f axisA = new Vector3f();
  private final Vector3f axisB = new Vector3f();
  private final Vector3f[] row = { new Vector3f(), new Vector3f() };
  private final float[] rowMass = new float[2];
  private final float[] rowImpulse = new float[2];
  private final Vector3f spin = new Vector3f();
  private float motorMass;
  private float motorImpulse;
  private float maxMotorImpulse;

  /** Sets both axes from one world direction with the bodies where they are now. */
  public HingeJoint setWorldAxis(Vector3f worldAxis) {
    final var q = entity().transform().worldRotation(new org.joml.Quaternionf());
    q.conjugate().transform(this.axis.set(worldAxis).normalize());
    if (this.connected != null) {
      final var qb = this.connected.transform().worldRotation(new org.joml.Quaternionf());
      qb.conjugate().transform(this.connectedAxis.set(worldAxis).normalize());
    } else {
      this.connectedAxis.set(worldAxis).normalize();
    }
    return this;
  }

  @Override
  void prepare(float dt) {
    pointMatrix(this.pointInverse).invert();
    applyLinear(this.pointImpulse);

    entity().transform().worldRotation(this.tmpRotation).transform(this.axisA.set(this.axis)).normalize();
    if (this.connected != null) {
      this.connected.transform().worldRotation(this.tmpRotation).transform(this.axisB.set(this.connectedAxis))
          .normalize();
    } else {
      this.axisB.set(this.connectedAxis).normalize();
    }
    // two directions perpendicular to B's axis: A's axis must have no component along them
    final var b1 = this.tmp2;
    if (Math.abs(this.axisB.x) > 0.57735f) {
      b1.set(this.axisB.y, -this.axisB.x, 0f);
    } else {
      b1.set(0f, this.axisB.z, -this.axisB.y);
    }
    b1.normalize();
    final var b2 = this.axisB.cross(b1, this.spin);
    for (int i = 0; i < 2; i++) {
      final var perp = i == 0 ? b1 : b2;
      this.axisA.cross(perp, this.row[i]); // d/dt (a . b) = (wA - wB) . (a x b)
      this.rowMass[i] = angularMass(this.row[i]);
      applyAngular(this.row[i], -this.rowImpulse[i]);
    }
    this.motorMass = angularMass(this.axisB);
    this.maxMotorImpulse = this.maxMotorTorque * dt;
    if (this.motor) {
      applyAngular(this.axisB, this.motorImpulse);
    } else {
      this.motorImpulse = 0f;
    }
  }

  private final org.joml.Quaternionf tmpRotation = new org.joml.Quaternionf();

  /** Closes the anchor gap, then turns the bodies so their axes line up again. */
  @Override
  void correctPositions(PhysicsSystem physics) {
    super.correctPositions(physics);
    if (!isEnabled() || !anchors()) {
      return;
    }
    entity().transform().worldRotation(this.tmpRotation).transform(this.axisA.set(this.axis)).normalize();
    if (this.connected != null) {
      this.connected.transform().worldRotation(this.tmpRotation).transform(this.axisB.set(this.connectedAxis))
          .normalize();
    } else {
      this.axisB.set(this.connectedAxis).normalize();
    }
    final var error = this.axisA.cross(this.axisB, this.velocity); // turning A by this aligns it with B
    if (error.lengthSquared() < 1e-10f) {
      return;
    }
    final float total = this.invA + this.invB;
    if (this.invA > 0f) {
      physics.twist(this.bodyA, this.tmp2.set(error).mul(CORRECTION * this.invA / total));
    }
    if (this.invB > 0f) {
      physics.twist(this.bodyB, this.tmp2.set(error).mul(-CORRECTION * this.invB / total));
    }
  }

  @Override
  void solve() {
    if (this.motor) {
      final float relative = this.spin.set(this.wb).sub(this.wa).dot(this.axisB);
      final float total = Math.max(-this.maxMotorImpulse, Math.min(this.maxMotorImpulse,
          this.motorImpulse + this.motorMass * (this.motorSpeed - relative)));
      applyAngular(this.axisB, total - this.motorImpulse);
      this.motorImpulse = total;
    }
    for (int i = 0; i < 2; i++) {
      // Cdot = (wA - wB) . row; the impulse acts on A along +row, on B along -row
      final float cdot = this.spin.set(this.wa).sub(this.wb).dot(this.row[i]);
      final float delta = -this.rowMass[i] * cdot;
      this.rowImpulse[i] += delta;
      applyAngular(this.row[i], -delta);
    }
    relativeVelocity(this.velocity);
    this.rhs.set(this.velocity).negate();
    this.pointInverse.transform(this.rhs);
    this.pointImpulse.add(this.rhs);
    applyLinear(this.rhs);
    this.stepImpulse = this.pointImpulse.length();
  }
}
