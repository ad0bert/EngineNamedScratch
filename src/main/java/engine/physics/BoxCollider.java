package engine.physics;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Oriented box: follows the entity's rotation and scale. */
public class BoxCollider extends Collider {
  public final Vector3f halfExtents = new Vector3f(0.5f);

  // cached world shape, refreshed in place
  private final Matrix4f matrix = new Matrix4f();
  private final Narrowphase.Box box = new Narrowphase.Box(new Vector3f(),
      new Vector3f[] { new Vector3f(), new Vector3f(), new Vector3f() }, new float[3]);

  public BoxCollider() {
  }

  public BoxCollider(Vector3f halfExtents) {
    this.halfExtents.set(halfExtents);
  }

  public BoxCollider(float hx, float hy, float hz) {
    this.halfExtents.set(hx, hy, hz);
  }

  @Override
  void refresh() {
    final Matrix4f m = entity().transform().worldMatrix(this.matrix);
    m.transformPosition(this.box.c().set(this.offset));
    for (int i = 0; i < 3; i++) {
      final Vector3f axis = m.getColumn(i, this.box.axes()[i]);
      final float len = axis.length();
      if (len > 1e-9f) {
        axis.div(len);
      } else {
        axis.set(i == 0 ? 1f : 0f, i == 1 ? 1f : 0f, i == 2 ? 1f : 0f);
      }
      this.box.half()[i] = (i == 0 ? this.halfExtents.x : i == 1 ? this.halfExtents.y : this.halfExtents.z) * len;
    }
  }

  /** Refreshes and returns the cached world box (shared: do not keep it across calls). */
  Narrowphase.Box world() {
    refresh();
    return this.box;
  }

  /** The cached world box as of the last {@link #refresh()}. */
  Narrowphase.Box cachedBox() {
    return this.box;
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
    dest.set(this.box.c());
  }

  @Override
  float cachedRadius() {
    final float[] h = this.box.half();
    return (float) Math.sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]);
  }
}
