package engine.physics;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * Makes an entity move under {@link PhysicsSystem}: gravity, damping and collision response, including rotation. The
 * mass is spread over all non-trigger colliders of the entity in proportion to their volume, so a body made of
 * several boxes and spheres turns about their common centre of mass with the matching inertia; set
 * {@link #lockRotation} to keep it upright. A {@code kinematic} body is moved (and turned) by game code, e.g. the
 * player or a platform; its velocity and spin are estimated from that motion, so it pushes dynamic bodies, carries
 * what rests on it and drags it along when it turns, but it is never pushed itself. Entities with a collider and no
 * rigidbody are static.
 *
 * <p>
 * Bodies that stay (almost) still for {@link PhysicsSystem#sleepTime} seconds fall asleep: they are no longer
 * integrated and cost almost nothing. A sleeping body wakes up when an awake body hits it, when it loses its support,
 * when its entity is moved or turned by code, when its velocity is set, or on {@link #wakeUp()}.
 *
 * <p>
 * Velocity is in world space, so bodies may be children of other entities.
 */
public class Rigidbody extends Component {
  public final Vector3f velocity = new Vector3f();
  /** World-space angular velocity in rad/s (axis * speed). */
  public final Vector3f angularVelocity = new Vector3f();
  public float mass = 1f;
  public boolean kinematic;
  public boolean useGravity = true;
  /** Bounciness 0..1; the lower value of the two touching bodies is used. */
  public float restitution = 0.25f;
  /** Coulomb friction coefficient; the geometric mean of the two touching bodies is used. */
  public float friction = 0.5f;
  public float linearDamping = 0.02f;
  public float angularDamping = 0.05f;
  /** Set to true for bodies that must stay upright, such as a character: they translate but never rotate. */
  public boolean lockRotation;
  /** Set to false for bodies that must never be put to sleep. */
  public boolean allowSleep = true;
  /**
   * Always sweep this body along its motion (see {@link PhysicsSystem#continuousCollision}); otherwise only steps
   * longer than its inscribed radius are swept.
   */
  public boolean continuous;

  // kinematic velocity estimation (world space)
  final Vector3f lastPosition = new Vector3f();
  final Quaternionf lastRotation = new Quaternionf();
  boolean hasLastPosition;

  // sleeping
  boolean sleeping;
  float sleepTimer;
  final Vector3f sleepPosition = new Vector3f();
  final Quaternionf sleepRotation = new Quaternionf();
  boolean touchedThisStep;

  // solver data, refreshed once per step (see PhysicsSystem#prepare)
  final Matrix3f invInertiaWorld = new Matrix3f();
  final Matrix3f inertiaWorld = new Matrix3f();
  final Vector3f centerOfMass = new Vector3f();
  int preparedStep = -1;

  public float inverseMass() {
    return this.kinematic || this.mass <= 0f ? 0f : 1f / this.mass;
  }

  public boolean isSleeping() {
    return this.sleeping;
  }

  public void wakeUp() {
    this.sleeping = false;
    this.sleepTimer = 0f;
  }
}
