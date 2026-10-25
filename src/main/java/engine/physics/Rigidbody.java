package engine.physics;

import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * Makes an entity move under {@link PhysicsSystem}: gravity, damping and collision response (linear only, bodies do not
 * rotate). A {@code kinematic} body is moved by game code (e.g. the player); it pushes dynamic bodies but is never
 * pushed itself. Entities with a collider and no rigidbody are static.
 */
public class Rigidbody extends Component {
  public final Vector3f velocity = new Vector3f();
  public float mass = 1f;
  public boolean kinematic;
  public boolean useGravity = true;
  /** Bounciness 0..1; the lower value of the two touching bodies is used. */
  public float restitution = 0.25f;
  /** Coulomb friction coefficient; the geometric mean of the two touching bodies is used. */
  public float friction = 0.5f;
  public float linearDamping = 0.02f;

  // kinematic velocity estimation
  final Vector3f lastPosition = new Vector3f();
  boolean hasLastPosition;

  public float inverseMass() {
    return this.kinematic || this.mass <= 0f ? 0f : 1f / this.mass;
  }
}
