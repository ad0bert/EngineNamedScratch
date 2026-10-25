package engine.physics;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Oriented box: follows the entity's rotation and scale. */
public class BoxCollider extends Collider {
  public final Vector3f halfExtents = new Vector3f(0.5f);

  public BoxCollider() {
  }

  public BoxCollider(Vector3f halfExtents) {
    this.halfExtents.set(halfExtents);
  }

  public BoxCollider(float hx, float hy, float hz) {
    this.halfExtents.set(hx, hy, hz);
  }

  Narrowphase.Box world() {
    final Matrix4f m = entity().transform().worldMatrix(new Matrix4f());
    final Vector3f center = m.transformPosition(new Vector3f(this.offset));
    final Vector3f[] axes = new Vector3f[3];
    final float[] half = new float[3];
    final float[] he = { this.halfExtents.x, this.halfExtents.y, this.halfExtents.z };
    for (int i = 0; i < 3; i++) {
      final Vector3f col = m.getColumn(i, new Vector3f());
      final float len = col.length();
      axes[i] = len > 1e-9f ? col.div(len) : new Vector3f(i == 0 ? 1f : 0f, i == 1 ? 1f : 0f, i == 2 ? 1f : 0f);
      half[i] = he[i] * len;
    }
    return new Narrowphase.Box(center, axes, half);
  }

  @Override
  public Vector3f worldCenter(Vector3f dest) {
    return entity().transform().worldMatrix(new Matrix4f()).transformPosition(dest.set(this.offset));
  }

  @Override
  public float boundingRadius() {
    final Narrowphase.Box b = world();
    return (float) Math.sqrt(b.half()[0] * b.half()[0] + b.half()[1] * b.half()[1] + b.half()[2] * b.half()[2]);
  }
}
