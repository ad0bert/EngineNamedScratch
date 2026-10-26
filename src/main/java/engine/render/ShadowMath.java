package engine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Pure math for cascaded directional shadow maps and culling, kept free of GL so it can be unit-tested. */
public final class ShadowMath {
  private ShadowMath() {
  }

  /**
   * Far distances (from the camera, along its view direction) of {@code count} shadow cascades covering
   * {@code near..far}. {@code lambda} blends between uniform splits (0) and logarithmic splits (1); logarithmic gives
   * near cascades more resolution, uniform is cheaper to reason about. The last split is exactly {@code far}.
   */
  public static float[] cascadeSplits(float near, float far, int count, float lambda) {
    final float[] splits = new float[count];
    for (int i = 1; i <= count; i++) {
      final float p = (float) i / count;
      final float logarithmic = near * (float) Math.pow(far / near, p);
      final float uniform = near + (far - near) * p;
      splits[i - 1] = lambda * logarithmic + (1f - lambda) * uniform;
    }
    splits[count - 1] = far;
    return splits;
  }

  /**
   * Smallest sphere (centred on the view axis) around the part of the camera frustum between distances {@code near}
   * and {@code far}. A sphere does not change size when the camera turns, so the shadow map keeps a constant
   * resolution and does not shimmer. Writes the centre to {@code outCenter} and returns the radius, rounded up to
   * 1/16 of a unit.
   */
  public static float sliceBoundingSphere(Vector3f camPos, Vector3f right, Vector3f up, Vector3f forward,
      float fovYRadians, float aspect, float near, float far, Vector3f outCenter) {
    final float tanY = (float) Math.tan(fovYRadians / 2.0);
    final float tanX = tanY * aspect;
    // distance along the axis that is equally far from the near and the far corners
    final float k2 = tanX * tanX + tanY * tanY;
    final float c = Math.min(far, 0.5f * (near + far) * (1f + k2));
    outCenter.set(camPos).fma(c, forward);

    float radiusSq = 0f;
    final var corner = new Vector3f();
    for (int i = 0; i < 8; i++) {
      final float d = (i & 1) == 0 ? near : far;
      final float sx = (i & 2) == 0 ? -1f : 1f;
      final float sy = (i & 4) == 0 ? -1f : 1f;
      corner.set(camPos).fma(d, forward).fma(sx * d * tanX, right).fma(sy * d * tanY, up);
      radiusSq = Math.max(radiusSq, corner.distanceSquared(outCenter));
    }
    return (float) Math.ceil(Math.sqrt(radiusSq) * 16.0) / 16f;
  }

  /**
   * Orthographic light view-projection covering a sphere of {@code radius} around {@code center}. The centre is
   * snapped to the shadow-map texel grid so shadows do not shimmer while the camera moves. The volume reaches back
   * {@code 4 * radius} towards the light, which keeps depth precision high; casters further towards the light are
   * still drawn (see {@link #cascadeCullingVolume}) because the renderer enables depth clamping for the shadow pass.
   */
  public static Matrix4f cascadeViewProj(Vector3f lightDir, Vector3f center, float radius, int mapSize,
      Matrix4f dest) {
    return cascadeViewProj(lightDir, center, radius, mapSize, radius * 4f, dest);
  }

  /**
   * The volume in which shadow casters of a cascade are looked for: the cascade's volume extended {@code reach}
   * world units towards the light. Casters in front of the cascade's near plane are clamped onto it, so they still
   * shadow everything in the cascade.
   */
  public static Matrix4f cascadeCullingVolume(Vector3f lightDir, Vector3f center, float radius, int mapSize,
      float reach, Matrix4f dest) {
    return cascadeViewProj(lightDir, center, radius, mapSize, Math.max(radius * 4f, reach), dest);
  }

  private static Matrix4f cascadeViewProj(Vector3f lightDir, Vector3f center, float radius, int mapSize, float back,
      Matrix4f dest) {
    final var d = new Vector3f(lightDir).normalize();
    final var helper = Math.abs(d.y) > 0.99f ? new Vector3f(1f, 0f, 0f) : new Vector3f(0f, 1f, 0f);
    final var right = d.cross(helper, new Vector3f()).normalize();
    final var up = right.cross(d, new Vector3f()).normalize();

    final float texel = 2f * radius / mapSize;
    final var snapped = new Vector3f(center);
    snapped.fma(-mod(center.dot(right), texel), right);
    snapped.fma(-mod(center.dot(up), texel), up);

    final var eye = snapped.fma(-back, d, new Vector3f());
    final var view = new Matrix4f().lookAt(eye, snapped, up);
    final var proj = new Matrix4f().setOrtho(-radius, radius, -radius, radius, 0.1f, back + radius);
    return proj.mul(view, dest);
  }

  /** Look direction of the six faces of a point light's shadow cube (+x, -x, +y, -y, +z, -z); see basic.frag. */
  public static final Vector3f[] CUBE_FORWARD = { new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
      new Vector3f(0, 1, 0), new Vector3f(0, -1, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1) };
  /** Up direction of the six cube faces. */
  public static final Vector3f[] CUBE_UP = { new Vector3f(0, 1, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1),
      new Vector3f(0, 0, -1), new Vector3f(0, 1, 0), new Vector3f(0, 1, 0) };

  /** 90 degree perspective view-projection of one face of a point light's shadow cube. */
  public static Matrix4f cubeFaceViewProj(Vector3f lightPos, int face, float near, float far, Matrix4f dest) {
    final var f = CUBE_FORWARD[face];
    final var u = CUBE_UP[face];
    final var proj = new Matrix4f().setPerspective((float) Math.toRadians(90.0), 1f, near, far);
    final var view = new Matrix4f().lookAt(lightPos.x, lightPos.y, lightPos.z, lightPos.x + f.x, lightPos.y + f.y,
        lightPos.z + f.z, u.x, u.y, u.z);
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
    // runs for every moving renderer every frame: no temporary objects
    final var min = data.min();
    final var max = data.max();
    final float hx = 0.5f * (max.x - min.x);
    final float hy = 0.5f * (max.y - min.y);
    final float hz = 0.5f * (max.z - min.z);
    model.transformPosition(outCenter.set(min).add(max).mul(0.5f));
    final float sx = model.m00() * model.m00() + model.m01() * model.m01() + model.m02() * model.m02();
    final float sy = model.m10() * model.m10() + model.m11() * model.m11() + model.m12() * model.m12();
    final float sz = model.m20() * model.m20() + model.m21() * model.m21() + model.m22() * model.m22();
    return (float) Math.sqrt((hx * hx + hy * hy + hz * hz) * Math.max(sx, Math.max(sy, sz)));
  }
}
