package engine.physics;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Keeps the two anchors together and lets the bodies turn freely about them: a ball-and-socket (chains, ragdolls).
 *
 * <p>
 * Optional limits for ragdolls: {@link #axis} (in A's local space) and {@link #connectedAxis} (in B's, world space
 * without B) are the directions of the two parts, usually along the bones; {@link #setWorldAxis} sets both from the
 * current pose. A {@link #coneAngle} keeps B's axis within that angle of A's (the swing), and {@link #limitTwist}
 * keeps the turn of B about its own axis relative to A within {@link #lowerTwist}..{@link #upperTwist} (zero in the
 * pose of {@link #setWorldAxis}, or of the first step).
 */
public class BallJoint extends Joint {
  public final Vector3f axis = new Vector3f(0f, 1f, 0f);
  public final Vector3f connectedAxis = new Vector3f(0f, 1f, 0f);
  /** Largest angle between the two axes in radians; pi or more means no cone. */
  public float coneAngle = (float) Math.PI;
  public boolean limitTwist;
  public float lowerTwist = (float) (-Math.PI / 4);
  public float upperTwist = (float) (Math.PI / 4);
  /** Bounciness 0..1 of the cone and twist stops. */
  public float limitRestitution;
  /** 0..1 (exclusive): how much of its speed a body keeps when it runs into a limit. */
  public float limitSoftness;

  private final Matrix3f inverse = new Matrix3f();
  private final Vector3f accumulated = new Vector3f();
  private final Vector3f rhs = new Vector3f();
  private final Vector3f velocity = new Vector3f();

  /** Zero-twist reference directions perpendicular to the axes, in A's and B's local space. */
  final Vector3f reference = new Vector3f();
  final Vector3f connectedReference = new Vector3f();
  private boolean referenced;
  private final AngularLimit coneRow = new AngularLimit();
  private final AngularLimit lowerTwistRow = new AngularLimit();
  private final AngularLimit upperTwistRow = new AngularLimit();
  private final Vector3f axisA = new Vector3f();
  private final Vector3f axisB = new Vector3f();
  private final Vector3f refA = new Vector3f();
  private final Vector3f refB = new Vector3f();
  private final Vector3f swingAxis = new Vector3f();
  private final Vector3f twistAxis = new Vector3f();
  private final Quaternionf swing = new Quaternionf();
  private final Quaternionf tmpRotation = new Quaternionf();
  private float swingAngle;
  private float twistAngle;

  /** Sets both axes from one world direction with the bodies where they are now; the current pose has no twist. */
  public BallJoint setWorldAxis(Vector3f worldAxis) {
    rotations();
    this.rotationA.conjugate(this.tmpRotation).transform(this.axis.set(worldAxis).normalize());
    this.rotationB.conjugate(this.tmpRotation).transform(this.connectedAxis.set(worldAxis).normalize());
    reference(this.tmp.set(worldAxis).normalize());
    return this;
  }

  private void reference(Vector3f worldAxis) {
    HingeJoint.perpendicular(worldAxis, this.refA);
    this.rotationA.conjugate(this.tmpRotation).transform(this.reference.set(this.refA));
    this.rotationB.conjugate(this.tmpRotation).transform(this.connectedReference.set(this.refA));
    this.referenced = true;
  }

  private boolean limited() {
    return this.coneAngle < Math.PI || this.limitTwist;
  }

  /**
   * Swing (angle between the axes) and twist of the pose in {@link #rotationA}/{@link #rotationB}. The twist is
   * measured after turning B's reference by the shortest rotation that brings B's axis onto A's, so swinging alone
   * does not twist.
   */
  private void measure() {
    this.rotationA.transform(this.axisA.set(this.axis)).normalize();
    this.rotationB.transform(this.axisB.set(this.connectedAxis)).normalize();
    if (!this.referenced) {
      reference(this.axisA);
    }
    final float cos = Math.max(-1f, Math.min(1f, this.axisA.dot(this.axisB)));
    this.swingAngle = (float) Math.acos(cos);
    this.axisA.cross(this.axisB, this.swingAxis); // the swing grows when B turns about this relative to A
    if (this.swingAxis.lengthSquared() > 1e-12f) {
      this.swingAxis.normalize();
    } else {
      HingeJoint.perpendicular(this.axisA, this.swingAxis);
    }
    this.rotationA.transform(this.refA.set(this.reference));
    this.rotationB.transform(this.refB.set(this.connectedReference));
    this.swing.rotationTo(this.axisB, this.axisA).transform(this.refB);
    this.twistAngle = (float) Math.atan2(this.refA.cross(this.refB, this.tmp).dot(this.axisA),
        this.refA.dot(this.refB));
    this.twistAxis.set(this.axisA).add(this.axisB);
    if (this.twistAxis.lengthSquared() > 1e-8f) {
      this.twistAxis.normalize();
    } else {
      this.twistAxis.set(this.axisA);
    }
  }

  @Override
  void prepare(float dt) {
    pointMatrix(this.inverse).invert();
    applyLinear(this.accumulated); // warm start
    if (!limited()) {
      this.coneRow.active = false;
      this.lowerTwistRow.active = false;
      this.upperTwistRow.active = false;
      return;
    }
    rotations();
    measure();
    if (this.coneAngle < Math.PI) {
      this.coneRow.prepare(this, this.tmp2.set(this.swingAxis).negate(), this.coneAngle - this.swingAngle, dt,
          this.limitRestitution, this.limitSoftness);
    } else {
      this.coneRow.active = false;
    }
    if (this.limitTwist) {
      this.lowerTwistRow.prepare(this, this.twistAxis, this.twistAngle - this.lowerTwist, dt,
          this.limitRestitution, this.limitSoftness);
      this.upperTwistRow.prepare(this, this.tmp2.set(this.twistAxis).negate(), this.upperTwist - this.twistAngle,
          dt, this.limitRestitution, this.limitSoftness);
    } else {
      this.lowerTwistRow.active = false;
      this.upperTwistRow.active = false;
    }
  }

  /** Closes the anchor gap, then turns the bodies back into the cone and the twist range. */
  @Override
  void correctPositions(PhysicsSystem physics) {
    super.correctPositions(physics);
    if (!limited() || !isEnabled() || !anchors()) {
      return;
    }
    final float fraction = CORRECTION * (1f - this.limitSoftness);
    rotations();
    measure();
    if (this.swingAngle > this.coneAngle + ANGULAR_SLOP) {
      turnApart(physics, this.swingAxis, this.coneAngle + ANGULAR_SLOP - this.swingAngle, fraction);
      rotations();
      measure();
    }
    if (this.limitTwist) {
      if (this.twistAngle < this.lowerTwist - ANGULAR_SLOP) {
        turnApart(physics, this.twistAxis, this.lowerTwist - ANGULAR_SLOP - this.twistAngle, fraction);
      } else if (this.twistAngle > this.upperTwist + ANGULAR_SLOP) {
        turnApart(physics, this.twistAxis, this.upperTwist + ANGULAR_SLOP - this.twistAngle, fraction);
      }
    }
  }

  @Override
  void solve() {
    this.coneRow.solve(this);
    this.lowerTwistRow.solve(this);
    this.upperTwistRow.solve(this);
    relativeVelocity(this.velocity);
    this.rhs.set(this.velocity).negate();
    this.inverse.transform(this.rhs); // the impulse that stops the anchors drifting apart
    this.accumulated.add(this.rhs);
    applyLinear(this.rhs);
    this.stepImpulse = this.accumulated.length();
  }

  /** Current angle between the two axes (rad). */
  public float swingAngle() {
    rotations();
    measure();
    return this.swingAngle;
  }

  /** Current twist of B about its axis relative to A (rad, -pi..pi). */
  public float twistAngle() {
    rotations();
    measure();
    return this.twistAngle;
  }
}
