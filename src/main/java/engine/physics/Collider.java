package engine.physics;

import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * Base for collision shapes. An entity with a collider but no {@link Rigidbody} is static. A {@code trigger} reports
 * overlaps through {@link CollisionEvent} but never pushes anything. Shapes use the entity's world transform, so
 * physics assumes bodies are not parented to other transforms.
 *
 * <p>
 * Concrete shapes keep their world-space shape cached: {@link #refresh()} recomputes it in place (no allocation) from
 * the entity's transform, and the physics step uses the cached values.
 */
public abstract class Collider extends Component {
  public boolean trigger;
  /** Shape centre relative to the entity origin (in local, unscaled units). */
  public final Vector3f offset = new Vector3f();

  public abstract Vector3f worldCenter(Vector3f dest);

  /** Radius of a sphere around {@link #worldCenter} that contains the whole shape. */
  public abstract float boundingRadius();

  /** Recomputes the cached world-space shape from the entity's current transform. */
  void refresh() {
  }

  /** Centre of the cached shape (valid after {@link #refresh()}). */
  void cachedCenter(Vector3f dest) {
    worldCenter(dest);
  }

  /** Bounding radius of the cached shape (valid after {@link #refresh()}). */
  float cachedRadius() {
    return boundingRadius();
  }
}
