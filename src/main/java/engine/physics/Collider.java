package engine.physics;

import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * Base for collision shapes. An entity with a collider but no {@link Rigidbody} is static; it may still be moved by
 * code (a lift, a door), and bodies touching it are then woken and carried along. A {@code trigger} reports overlaps
 * through {@link CollisionEvent} but never pushes anything. Shapes use the entity's world transform. An entity may
 * have several colliders: together they form one compound body.
 *
 * <p>
 * Concrete shapes keep their world-space shape cached: {@link #refresh()} recomputes it in place (no allocation) from
 * the entity's transform, and the physics step uses the cached values.
 */
public abstract class Collider extends Component {
  private static final AtomicInteger NEXT_ID = new AtomicInteger();

  public boolean trigger;
  /** Shape centre relative to the entity origin (in local, unscaled units). */
  public final Vector3f offset = new Vector3f();

  /** Unique per collider; orders pairs and keys the contact cache. */
  final int id = NEXT_ID.incrementAndGet();

  // motion of a static collider (no rigidbody) that game code moves, estimated once per step by PhysicsSystem
  final Vector3f motionOrigin = new Vector3f();
  final Vector3f motionVelocity = new Vector3f();
  final Vector3f motionSpin = new Vector3f();
  final Quaternionf lastRotation = new Quaternionf();
  final Matrix4f lastMatrix = new Matrix4f();
  boolean hasLastPose;
  boolean moving;

  public abstract Vector3f worldCenter(Vector3f dest);

  /** Radius of a sphere around {@link #worldCenter} that contains the whole shape. */
  public abstract float boundingRadius();

  /** Radius of the largest sphere around the centre inside the cached shape (continuous collision); 0 for none. */
  abstract float cachedInnerRadius();

  /** The entity's world matrix as of the last {@link #refresh()}. */
  abstract Matrix4f cachedMatrix();

  /** Volume of the world-space shape (valid after {@link #refresh()}); splits a compound body's mass. */
  abstract float cachedVolume();

  /**
   * Adds this shape's world-space inertia tensor about its own centre (valid after {@link #refresh()}) for the given
   * mass to {@code dest}.
   */
  abstract void addCachedInertia(float mass, Matrix3f dest);

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
