package engine.physics;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A ball joint that only allows turning about one axis (doors, wheels, levers). {@link #axis} is in A's local space,
 * {@link #connectedAxis} in B's (world space without B); {@link #setWorldAxis} sets both. An optional motor drives
 * the relative spin about the axis towards {@link #motorSpeed} with at most {@link #maxMotorTorque}.
 *
 * <p>
 * With {@link #limit} set, the {@link #angle()} of B relative to A stays within {@link #lowerAngle}..
 * {@link #upperAngle} (radians in -pi..pi, zero where the bodies were when {@link #setWorldAxis} was called, or at
 * the first step). A motor driving into a limit stops there. The limit can bounce ({@link #limitRestitution}) or
 * give a little ({@link #limitSoftness}: 0 is hard, towards 1 the bodies sink into the limit and are pushed back
 * slowly).
 */
public class HingeJoint extends Joint {
  public final Vector3f axis = new Vector3f(0f, 1f, 0f);
  public final Vector3f connectedAxis = new Vector3f(0f, 1f, 0f);
  public boolean motor;
  /** Target spin of B relative to A about the axis, in rad/s (right-hand rule). */
  public float motorSpeed;
  /** N m; the motor never pushes harder than this. */
  public float maxMotorTorque = 10f;
  public boolean limit;
  public float lowerAngle = (float) (-Math.PI / 4);
  public float upperAngle = (float) (Math.PI / 4);
  /** Bounciness 0..1 of the limit stops. */
  public float limitRestitution;
  /** 0..1 (exclusive): how much of its speed a body keeps when it runs into a limit. */
  public float limitSoftness;

  /** Zero-angle reference directions perpendicular to the axes, in A's and B's local space. */
  final Vector3f reference = new Vector3f();
  final Vector3f connectedReference = new Vector3f();
  private boolean referenced;
  private final AngularLimit lowerRow = new AngularLimit();
  private final AngularLimit upperRow = new AngularLimit();
  private final Vector3f refA = new Vector3f();
  private final Vector3f refB = new Vector3f();

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

  /**
   * Sets both axes from one world direction with the bodies where they are now; the current pose becomes angle
   * zero.
   */
  public HingeJoint setWorldAxis(Vector3f worldAxis) {
    rotations();
    this.rotationA.conjugate(this.tmpRotation).transform(this.axis.set(worldAxis).normalize());
    this.rotationB.conjugate(this.tmpRotation).transform(this.connectedAxis.set(worldAxis).normalize());
    reference(this.tmp.set(worldAxis).normalize());
    return this;
  }

  /** Makes the current pose angle zero: stores one direction perpendicular to the world axis in both local spaces. */
  private void reference(Vector3f worldAxis) {
    perpendicular(worldAxis, this.refA);
    this.rotationA.conjugate(this.tmpRotation).transform(this.reference.set(this.refA));
    this.rotationB.conjugate(this.tmpRotation).transform(this.connectedReference.set(this.refA));
    this.referenced = true;
  }

  static Vector3f perpendicular(Vector3f v, Vector3f dest) {
    if (Math.abs(v.x) > 0.57735f) {
      dest.set(v.y, -v.x, 0f);
    } else {
      dest.set(0f, v.z, -v.y);
    }
    return dest.normalize();
  }

  /** Angle of B relative to A about the axis in radians (-pi..pi); zero before the first step if never set. */
  public float angle() {
    if (!this.referenced) {
      return 0f;
    }
    rotations();
    return measure();
  }

  /** World axes into axisA/axisB and the angle, from {@link #rotationA}/{@link #rotationB}. */
  private float measure() {
    this.rotationA.transform(this.axisA.set(this.axis)).normalize();
    this.rotationB.transform(this.axisB.set(this.connectedAxis)).normalize();
    if (!this.referenced) {
      reference(this.axisB);
    }
    this.rotationA.transform(this.refA.set(this.reference));
    this.rotationB.transform(this.refB.set(this.connectedReference));
    return (float) Math.atan2(this.refA.cross(this.refB, this.tmp).dot(this.axisB), this.refA.dot(this.refB));
  }

  @Override
  boolean drives() {
    if (!this.motor || this.motorSpeed == 0f || this.maxMotorTorque <= 0f) {
      return false;
    }
    if (!this.limit) {
      return true;
    }
    final float angle = angle();
    return this.motorSpeed > 0f ? angle < this.upperAngle - 0.01f : angle > this.lowerAngle + 0.01f;
  }

  @Override
  void prepare(float dt) {
    pointMatrix(this.pointInverse).invert();
    applyLinear(this.pointImpulse);

    rotations();
    final float angle = measure();
    // two directions perpendicular to B's axis: A's axis must have no component along them
    final var b1 = perpendicular(this.axisB, this.tmp2);
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
    if (this.limit) {
      this.lowerRow.prepare(this, this.axisB, angle - this.lowerAngle, dt, this.limitRestitution, this.limitSoftness);
      this.upperRow.prepare(this, this.tmp2.set(this.axisB).negate(), this.upperAngle - angle, dt,
          this.limitRestitution, this.limitSoftness);
    } else {
      this.lowerRow.active = false;
      this.upperRow.active = false;
    }
  }

  private final Quaternionf tmpRotation = new Quaternionf();

  /** Closes the anchor gap, then turns the bodies so their axes line up again. */
  @Override
  void correctPositions(PhysicsSystem physics) {
    super.correctPositions(physics);
    if (!isEnabled() || !anchors()) {
      return;
    }
    rotations();
    measure();
    final var error = this.axisA.cross(this.axisB, this.velocity); // turning A by this aligns it with B
    if (error.lengthSquared() >= 1e-10f) {
      final float total = this.invA + this.invB;
      if (this.invA > 0f) {
        physics.twist(this.bodyA, this.tmp2.set(error).mul(CORRECTION * this.invA / total));
      }
      if (this.invB > 0f) {
        physics.twist(this.bodyB, this.tmp2.set(error).mul(-CORRECTION * this.invB / total));
      }
    }
    if (this.limit) {
      rotations();
      final float angle = measure();
      final float fraction = CORRECTION * (1f - this.limitSoftness);
      if (angle < this.lowerAngle - ANGULAR_SLOP) {
        turnApart(physics, this.axisB, this.lowerAngle - ANGULAR_SLOP - angle, fraction);
      } else if (angle > this.upperAngle + ANGULAR_SLOP) {
        turnApart(physics, this.axisB, this.upperAngle + ANGULAR_SLOP - angle, fraction);
      }
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
    this.lowerRow.solve(this);
    this.upperRow.solve(this);
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
