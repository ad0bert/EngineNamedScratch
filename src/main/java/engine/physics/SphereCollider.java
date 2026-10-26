package engine.physics;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class SphereCollider extends Collider {
  public float radius = 0.5f;

  // cached world shape, refreshed in place
  private final Matrix4f matrix = new Matrix4f();
  private final Vector3f scale = new Vector3f();
  private final Narrowphase.Sphere sphere = new Narrowphase.Sphere(new Vector3f(), 0f);

  public SphereCollider() {
  }

  public SphereCollider(float radius) {
    this.radius = radius;
  }

  @Override
  void refresh() {
    final var m = entity().transform().worldMatrix(this.matrix);
    m.getScale(this.scale);
    m.transformPosition(this.sphere.c().set(this.offset));
    this.sphere.r = this.radius * Math.max(this.scale.x, Math.max(this.scale.y, this.scale.z));
  }

  /** Refreshes and returns the cached world sphere (shared: do not keep it across calls). */
  Narrowphase.Sphere world() {
    refresh();
    return this.sphere;
  }

  /** The cached world sphere as of the last {@link #refresh()}. */
  Narrowphase.Sphere cachedSphere() {
    return this.sphere;
  }

  @Override
  public Vector3f worldCenter(Vector3f dest) {
    return entity().transform().worldMatrix(this.matrix).transformPosition(dest.set(this.offset));
  }

  @Override
  public float boundingRadius() {
    refresh();
    return this.sphere.r;
  }

  @Override
  void cachedCenter(Vector3f dest) {
    dest.set(this.sphere.c());
  }

  @Override
  float cachedInnerRadius() {
    return this.sphere.r;
  }

  @Override
  Matrix4f cachedMatrix() {
    return this.matrix;
  }

  @Override
  float cachedVolume() {
    final float r = this.sphere.r;
    return 4.18879f * r * r * r;
  }

  @Override
  void addCachedInertia(float mass, Matrix3f dest) {
    final float i = 0.4f * mass * this.sphere.r * this.sphere.r;
    dest.m00 += i;
    dest.m11 += i;
    dest.m22 += i;
  }

  @Override
  float cachedRadius() {
    return this.sphere.r;
  }
}
