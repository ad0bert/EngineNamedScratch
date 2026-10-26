package engine.physics;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.ecs.Component;
import engine.ecs.Entity;

/**
 * Connects the entity it is added to (body A) with {@link #connected} (body B), or with a fixed point in the world
 * when that is null. Either side may be static (no rigidbody) or kinematic. Joints are solved together with the
 * contacts by {@link PhysicsSystem}: their velocity rows are warm-started, and drift is removed by moving the bodies
 * back in the position rounds (not with a velocity bias, which would leave resting bodies with a small velocity
 * and keep them awake). Joined bodies fall asleep and wake up together, and by default do not collide with each
 * other.
 *
 * <p>
 * Anchors are given in each entity's local space; {@link #setWorldAnchor} sets both from one world point using the
 * current transforms (the usual way to place a ball or hinge joint), {@link #setWorldAnchors} from two.
 */
public abstract class Joint extends Component {
  /** Body B, or null to attach A to the world. */
  public Entity connected;
  /** Anchor in A's local space. */
  public final Vector3f anchor = new Vector3f();
  /** Anchor in B's local space (in world space when {@link #connected} is null). */
  public final Vector3f connectedAnchor = new Vector3f();
  /** Whether A and B still collide with each other. */
  public boolean collideConnected;
  /**
   * The joint breaks (disables itself) when it needs a larger impulse than this in one step (N s); infinite by
   * default.
   */
  public float breakImpulse = Float.POSITIVE_INFINITY;

  /** Fraction of the position error removed per position round. */
  static final float CORRECTION = 0.8f;
  /** Angle limits are left this far violated by the position rounds (rad), so a resting limit does not jitter. */
  static final float ANGULAR_SLOP = 0.003f;
  private static final Vector3f NO_MOTION = new Vector3f();
  private static final Matrix3f NO_INERTIA = new Matrix3f().zero();

  // solver state of one step
  Rigidbody bodyA;
  Rigidbody bodyB;
  float invA;
  float invB;
  Matrix3f ia;
  Matrix3f ib;
  Vector3f va;
  Vector3f vb;
  Vector3f wa;
  Vector3f wb;
  final Vector3f rA = new Vector3f();
  final Vector3f rB = new Vector3f();
  final Vector3f pA = new Vector3f();
  final Vector3f pB = new Vector3f();
  private final Matrix4f matrix = new Matrix4f();
  final Vector3f tmp = new Vector3f();
  final Vector3f tmp2 = new Vector3f();
  final Quaternionf rotationA = new Quaternionf();
  final Quaternionf rotationB = new Quaternionf();
  float stepImpulse;

  /** Sets both anchors so they meet at {@code worldPoint} with the bodies where they are now (ball, hinge). */
  public Joint setWorldAnchor(Vector3f worldPoint) {
    return setWorldAnchors(worldPoint, worldPoint);
  }

  /**
   * Sets the anchors from two world points, one on each body, with the bodies where they are now (distance joints:
   * the rope runs between them).
   */
  public Joint setWorldAnchors(Vector3f onA, Vector3f onB) {
    entity().transform().worldMatrix(this.matrix).invert().transformPosition(onA, this.anchor);
    if (this.connected != null) {
      this.connected.transform().worldMatrix(this.matrix).invert().transformPosition(onB, this.connectedAnchor);
    } else {
      this.connectedAnchor.set(onB);
    }
    return this;
  }

  /** True if the joint takes part this step (enabled, and at least one side can move). */
  final boolean begin(PhysicsSystem physics, float dt) {
    this.bodyA = entity().findOrNull(Rigidbody.class);
    this.bodyB = this.connected != null ? this.connected.findOrNull(Rigidbody.class) : null;
    this.invA = awakeInverseMass(this.bodyA);
    this.invB = awakeInverseMass(this.bodyB);
    if (this.invA + this.invB <= 0f) {
      return false;
    }
    if (this.invA > 0f) {
      physics.prepareBody(this.bodyA);
    }
    if (this.invB > 0f) {
      physics.prepareBody(this.bodyB);
    }
    this.ia = this.invA > 0f ? this.bodyA.invInertiaWorld : NO_INERTIA;
    this.ib = this.invB > 0f ? this.bodyB.invInertiaWorld : NO_INERTIA;
    this.va = this.bodyA != null ? this.bodyA.velocity : NO_MOTION;
    this.vb = this.bodyB != null ? this.bodyB.velocity : NO_MOTION;
    this.wa = this.bodyA != null ? this.bodyA.angularVelocity : NO_MOTION;
    this.wb = this.bodyB != null ? this.bodyB.angularVelocity : NO_MOTION;
    entity().transform().worldMatrix(this.matrix).transformPosition(this.pA.set(this.anchor));
    if (this.connected != null) {
      this.connected.transform().worldMatrix(this.matrix).transformPosition(this.pB.set(this.connectedAnchor));
    } else {
      this.pB.set(this.connectedAnchor);
    }
    this.rA.set(this.pA).sub(reference(this.bodyA, entity()));
    this.rB.set(this.pB).sub(this.bodyB != null ? reference(this.bodyB, this.connected) : this.pB);
    this.stepImpulse = 0f;
    prepare(dt);
    return true;
  }

  private Vector3f reference(Rigidbody b, Entity e) {
    if (b != null && !b.sleeping) {
      return b.kinematic ? e.transform().worldPosition(this.tmp2) : b.centerOfMass;
    }
    return e.transform().worldPosition(this.tmp2);
  }

  private static float awakeInverseMass(Rigidbody r) {
    return r == null || r.sleeping ? 0f : r.inverseMass();
  }

  /** Sets up the rows for this step and applies the impulses kept from the last one. */
  abstract void prepare(float dt);

  /** One round of the solver. */
  abstract void solve();

  /** True if a motor would turn the bodies now; wakes them when they sleep. */
  boolean drives() {
    return false;
  }

  /** One position round: moves the bodies (split by inverse mass) to undo drift. */
  void correctPositions(PhysicsSystem physics) {
    if (!isEnabled() || !anchors()) {
      return;
    }
    final var gap = this.tmp.set(this.pB).sub(this.pA);
    if (gap.lengthSquared() > 1e-8f) {
      final float total = this.invA + this.invB;
      if (this.invA > 0f) {
        PhysicsSystem.shift(this.bodyA, this.tmp2.set(gap).mul(CORRECTION * this.invA / total));
      }
      if (this.invB > 0f) {
        PhysicsSystem.shift(this.bodyB, this.tmp2.set(gap).mul(-CORRECTION * this.invB / total));
      }
    }
  }

  /**
   * Current world anchors in {@link #pA}/{@link #pB} and the awake inverse masses; false if neither side can move.
   */
  final boolean anchors() {
    this.bodyA = entity().findOrNull(Rigidbody.class);
    this.bodyB = this.connected != null ? this.connected.findOrNull(Rigidbody.class) : null;
    this.invA = awakeInverseMass(this.bodyA);
    this.invB = awakeInverseMass(this.bodyB);
    if (this.invA + this.invB <= 0f) {
      return false;
    }
    entity().transform().worldMatrix(this.matrix).transformPosition(this.pA.set(this.anchor));
    if (this.connected != null) {
      this.connected.transform().worldMatrix(this.matrix).transformPosition(this.pB.set(this.connectedAnchor));
    } else {
      this.pB.set(this.connectedAnchor);
    }
    return true;
  }

  /** After the last round: breaks the joint if it had to pull too hard. */
  final void finish() {
    if (this.stepImpulse > this.breakImpulse) {
      setEnabled(false);
    }
  }

  /** World rotations of A and B into {@link #rotationA}/{@link #rotationB} (identity for the world). */
  final void rotations() {
    entity().transform().worldRotation(this.rotationA);
    if (this.connected != null) {
      this.connected.transform().worldRotation(this.rotationB);
    } else {
      this.rotationB.identity();
    }
  }

  /**
   * Position round of an angle limit: turns B by {@code angle} about {@code axis} relative to A, split between the
   * bodies by inverse mass (the share of the error given by {@code fraction}).
   */
  final void turnApart(PhysicsSystem physics, Vector3f axis, float angle, float fraction) {
    final float total = this.invA + this.invB;
    if (this.invA > 0f) {
      physics.twist(this.bodyA, this.tmp2.set(axis).mul(-angle * fraction * this.invA / total));
    }
    if (this.invB > 0f) {
      physics.twist(this.bodyB, this.tmp2.set(axis).mul(angle * fraction * this.invB / total));
    }
  }

  // ---- helpers for the rows ----------------------------------------------------------------------------------

  /** Velocity of B's anchor relative to A's. */
  final Vector3f relativeVelocity(Vector3f dest) {
    dest.set(this.vb).sub(this.va);
    dest.add(this.wb.cross(this.rB, this.tmp));
    dest.sub(this.wa.cross(this.rA, this.tmp));
    return dest;
  }

  /** Applies {@code impulse} to B's anchor and its opposite to A's. */
  final void applyLinear(Vector3f impulse) {
    if (this.invA > 0f) {
      this.va.fma(-this.invA, impulse);
      this.wa.sub(this.ia.transform(this.rA.cross(impulse, this.tmp)));
    }
    if (this.invB > 0f) {
      this.vb.fma(this.invB, impulse);
      this.wb.add(this.ib.transform(this.rB.cross(impulse, this.tmp)));
    }
  }

  /** Applies the angular impulse {@code axis * amount} to B and its opposite to A. */
  final void applyAngular(Vector3f axis, float amount) {
    if (this.invA > 0f) {
      this.wa.sub(this.ia.transform(this.tmp.set(axis).mul(amount)));
    }
    if (this.invB > 0f) {
      this.wb.add(this.ib.transform(this.tmp.set(axis).mul(amount)));
    }
  }

  /** Effective mass of a linear row along {@code dir} at the anchors. */
  final float linearMass(Vector3f dir) {
    final float k = this.invA + this.invB + angular(this.ia, this.rA, dir) + angular(this.ib, this.rB, dir);
    return k > 1e-12f ? 1f / k : 0f;
  }

  /** Effective mass of an angular row about {@code axis}. */
  final float angularMass(Vector3f axis) {
    final float k = this.ia.transform(this.tmp.set(axis)).dot(axis) + this.ib.transform(this.tmp.set(axis)).dot(axis);
    return k > 1e-12f ? 1f / k : 0f;
  }

  private float angular(Matrix3f invInertia, Vector3f r, Vector3f dir) {
    r.cross(dir, this.tmp);
    invInertia.transform(this.tmp);
    return this.tmp.cross(r).dot(dir);
  }

  /**
   * The point-to-point block: K = (invA + invB) E - [rA] IA [rA] - [rB] IB [rB], written into {@code dest}. Column i
   * of [r] I [r]^T is r x (I (e_i x r)).
   */
  final Matrix3f pointMatrix(Matrix3f dest) {
    final float m = this.invA + this.invB;
    dest.set(m, 0f, 0f, 0f, m, 0f, 0f, 0f, m);
    addSkewTerm(dest, this.ia, this.rA);
    addSkewTerm(dest, this.ib, this.rB);
    return dest;
  }

  private void addSkewTerm(Matrix3f dest, Matrix3f inertia, Vector3f r) {
    for (int i = 0; i < 3; i++) {
      this.tmp.set(i == 0 ? 1f : 0f, i == 1 ? 1f : 0f, i == 2 ? 1f : 0f).cross(r);
      inertia.transform(this.tmp);
      r.cross(this.tmp, this.tmp2);
      dest.setColumn(i, dest.getColumn(i, this.tmp).add(this.tmp2));
    }
  }

  /**
   * A one-sided angular limit row: keeps the spin of B relative to A along {@link #axis} at or above a bound (the
   * impulse only pushes away from the limit). Speculative like the contacts: while the limit is still {@code c}
   * radians away the bodies may approach by that much in this step, so a body reaches the limit without bouncing off
   * early; once there it bounces with the given restitution or, when soft, keeps a fraction of its approach speed.
   */
  static final class AngularLimit {
    /** Approach speeds below this (rad/s) do not bounce. */
    private static final float REST_SPEED = 0.5f;
    /** The row is set up when the limit is this close (rad) after this step's motion. */
    private static final float MARGIN = 0.05f;

    final Vector3f axis = new Vector3f();
    private float mass;
    private float bound;
    float impulse;
    boolean active;

    /**
     * Sets up the row for a limit {@code c} radians away (negative: violated) along {@code dir} (the direction in
     * which the relative spin moves away from the limit) and applies the impulse kept from the last step.
     */
    void prepare(Joint j, Vector3f dir, float c, float dt, float restitution, float softness) {
      this.axis.set(dir);
      final float speed = j.tmp.set(j.wb).sub(j.wa).dot(this.axis);
      if (c + Math.min(speed, 0f) * dt > MARGIN) {
        this.active = false;
        this.impulse = 0f;
        return;
      }
      if (!this.active) {
        this.impulse = 0f;
      }
      this.active = true;
      this.mass = j.angularMass(this.axis);
      if (restitution > 0f && speed < -REST_SPEED && c + speed * dt < 0f) {
        this.bound = -restitution * speed;
        this.impulse = 0f; // a bounce starts fresh
      } else {
        this.bound = c > 0f ? -c / dt : 0f;
        if (softness > 0f && speed < 0f) {
          this.bound = Math.min(this.bound, softness * speed);
        }
      }
      j.applyAngular(this.axis, this.impulse);
    }

    void solve(Joint j) {
      if (!this.active) {
        return;
      }
      final float speed = j.tmp2.set(j.wb).sub(j.wa).dot(this.axis);
      final float total = Math.max(0f, this.impulse + this.mass * (this.bound - speed));
      j.applyAngular(this.axis, total - this.impulse);
      this.impulse = total;
    }
  }
}
