package engine.physics;

import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * Base for collision shapes. An entity with a collider but no {@link Rigidbody} is static. A {@code trigger} reports
 * overlaps through {@link CollisionEvent} but never pushes anything. Shapes use the entity's world transform, so
 * physics assumes bodies are not parented to other transforms.
 */
public abstract class Collider extends Component {
  public boolean trigger;
  /** Shape centre relative to the entity origin (in local, unscaled units). */
  public final Vector3f offset = new Vector3f();

  public abstract Vector3f worldCenter(Vector3f dest);

  /** Radius of a sphere around {@link #worldCenter} that contains the whole shape. */
  public abstract float boundingRadius();
}
