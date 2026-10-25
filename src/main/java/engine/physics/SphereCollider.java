package engine.physics;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class SphereCollider extends Collider {
  public float radius = 0.5f;

  public SphereCollider() {
  }

  public SphereCollider(float radius) {
    this.radius = radius;
  }

  Narrowphase.Sphere world() {
    final Matrix4f m = entity().transform().worldMatrix(new Matrix4f());
    final Vector3f s = m.getScale(new Vector3f());
    return new Narrowphase.Sphere(m.transformPosition(new Vector3f(this.offset)),
        this.radius * Math.max(s.x, Math.max(s.y, s.z)));
  }

  @Override
  public Vector3f worldCenter(Vector3f dest) {
    return entity().transform().worldMatrix(new Matrix4f()).transformPosition(dest.set(this.offset));
  }

  @Override
  public float boundingRadius() {
    return world().r();
  }
}
