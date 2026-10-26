package engine.physics;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A capsule: a cylinder with hemispherical ends, standing along the entity's local Y axis (or X/Z, see {@link #axis}).
 * Good for characters: it slides over steps and edges instead of catching on them like a box.
 * {@link #halfHeight} is half the length of the straight part, so the whole capsule is
 * {@code 2 * (halfHeight + radius)} long. Scaling stretches the length by the scale along the axis and the radius by
 * the largest of the other two.
 */
public class CapsuleCollider extends Collider {
  public float radius = 0.5f;
  public float halfHeight = 0.5f;
  /** Local axis of the capsule: 0 x, 1 y (default), 2 z. */
  public int axis = 1;

  // cached world shape, refreshed in place
  private final Matrix4f matrix = new Matrix4f();
  private final Vector3f scale = new Vector3f();
  private final ShapeTests.Capsule capsule = new ShapeTests.Capsule();

  public CapsuleCollider() {
  }

  public CapsuleCollider(float radius, float halfHeight) {
    this.radius = radius;
    this.halfHeight = halfHeight;
  }

  @Override
  void refresh() {
    final var m = entity().transform().worldMatrix(this.matrix);
    m.getScale(this.scale);
    final float ax = this.axis == 0 ? this.halfHeight : 0f;
    final float ay = this.axis == 1 ? this.halfHeight : 0f;
    final float az = this.axis == 2 ? this.halfHeight : 0f;
    m.transformPosition(this.capsule.a.set(this.offset).sub(ax, ay, az));
    m.transformPosition(this.capsule.b.set(this.offset).add(ax, ay, az));
    final float side = this.axis == 0 ? Math.max(this.scale.y, this.scale.z)
        : this.axis == 1 ? Math.max(this.scale.x, this.scale.z) : Math.max(this.scale.x, this.scale.y);
    this.capsule.r = this.radius * side;
  }

  /** Refreshes and returns the cached world capsule (shared: do not keep it across calls). */
  ShapeTests.Capsule world() {
    refresh();
    return this.capsule;
  }

  ShapeTests.Capsule cachedCapsule() {
    return this.capsule;
  }

  @Override
  public Vector3f worldCenter(Vector3f dest) {
    return entity().transform().worldMatrix(this.matrix).transformPosition(dest.set(this.offset));
  }

  @Override
  public float boundingRadius() {
    refresh();
    return cachedRadius();
  }

  @Override
  void cachedCenter(Vector3f dest) {
    dest.set(this.capsule.a).add(this.capsule.b).mul(0.5f);
  }

  @Override
  float cachedRadius() {
    return 0.5f * this.capsule.a.distance(this.capsule.b) + this.capsule.r;
  }

  @Override
  float cachedInnerRadius() {
    return this.capsule.r;
  }

  @Override
  Matrix4f cachedMatrix() {
    return this.matrix;
  }

  @Override
  float cachedVolume() {
    final float r = this.capsule.r;
    final float length = this.capsule.a.distance(this.capsule.b);
    return (float) Math.PI * r * r * (length + 4f / 3f * r);
  }

  @Override
  void addCachedInertia(float mass, Matrix3f dest) {
    final float r = this.capsule.r;
    final float length = this.capsule.a.distance(this.capsule.b);
    final float cylinder = (float) Math.PI * r * r * length;
    final float caps = 4f / 3f * (float) Math.PI * r * r * r;
    final float total = cylinder + caps;
    final float mc = total > 0f ? mass * cylinder / total : 0f;
    final float ms = mass - mc; // both hemispheres together
    final float along = mc * r * r / 2f + ms * 2f * r * r / 5f;
    final float across = mc * (length * length / 12f + r * r / 4f)
        + ms * (2f * r * r / 5f + length * length / 4f + 3f * length * r / 8f);
    final var dir = new Vector3f(this.capsule.b).sub(this.capsule.a);
    if (dir.lengthSquared() < 1e-12f) {
      dir.set(0f, 1f, 0f);
    }
    dir.normalize();
    // across is the same for every perpendicular axis: across * E + (along - across) * dir dir^T
    dest.m00 += across;
    dest.m11 += across;
    dest.m22 += across;
    PhysicsSystem.addAxisInertia(dest, dir, along - across);
  }
}
