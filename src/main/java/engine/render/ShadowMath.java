package engine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Pure math for directional shadow maps and culling, kept free of GL so it can be unit-tested. */
public final class ShadowMath {
  private ShadowMath() {
  }

  /**
   * Orthographic light view-projection covering a square of {@code 2*halfSize} around {@code focus}. The focus is
   * snapped to the shadow-map texel grid so shadows do not shimmer while the camera moves.
   */
  public static Matrix4f lightViewProj(Vector3f lightDir, Vector3f focus, float halfSize, int mapSize,
      Matrix4f dest) {
    final Vector3f d = new Vector3f(lightDir).normalize();
    final Vector3f helper = Math.abs(d.y) > 0.99f ? new Vector3f(1f, 0f, 0f) : new Vector3f(0f, 1f, 0f);
    final Vector3f right = d.cross(helper, new Vector3f()).normalize();
    final Vector3f up = right.cross(d, new Vector3f()).normalize();

    final float texel = 2f * halfSize / mapSize;
    final Vector3f snapped = new Vector3f(focus);
    snapped.fma(-mod(focus.dot(right), texel), right);
    snapped.fma(-mod(focus.dot(up), texel), up);

    final float depth = halfSize * 2f;
    final Vector3f eye = snapped.fma(-depth, d, new Vector3f());
    final Matrix4f view = new Matrix4f().lookAt(eye, snapped, up);
    final Matrix4f proj = new Matrix4f().setOrtho(-halfSize, halfSize, -halfSize, halfSize, 0.1f, depth * 2f);
    return proj.mul(view, dest);
  }

  private static float mod(float v, float m) {
    return v - (float) Math.floor(v / m) * m;
  }

  /**
   * World-space bounding sphere of a mesh under a model matrix. Writes the centre to {@code outCenter} and returns
   * the radius (scaled by the largest axis scale).
   */
  public static float worldSphere(MeshData data, Matrix4f model, Vector3f outCenter) {
    final Vector3f localCenter = data.center();
    final float localRadius = data.halfExtents().length();
    model.transformPosition(localCenter, outCenter);
    final Vector3f s = model.getScale(new Vector3f());
    return localRadius * Math.max(s.x, Math.max(s.y, s.z));
  }
}
