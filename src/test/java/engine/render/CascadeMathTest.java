package engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class CascadeMathTest {
  private static final float FOV = (float) Math.toRadians(60);
  private static final float ASPECT = 1.6f;

  @Test
  void splitsAreIncreasingAndEndExactlyAtFar() {
    for (float lambda : new float[] { 0f, 0.5f, 0.7f, 1f }) {
      final float[] s = ShadowMath.cascadeSplits(0.1f, 80f, 4, lambda);
      assertEquals(4, s.length);
      assertEquals(80f, s[3], 0f);
      for (int i = 1; i < s.length; i++) {
        assertTrue(s[i] > s[i - 1], "lambda " + lambda);
      }
      assertTrue(s[0] > 0.1f);
    }
  }

  @Test
  void lambdaBlendsUniformAndLogarithmicSplits() {
    final float[] uniform = ShadowMath.cascadeSplits(1f, 101f, 4, 0f);
    assertEquals(26f, uniform[0], 1e-4f);
    assertEquals(51f, uniform[1], 1e-4f);
    final float[] log = ShadowMath.cascadeSplits(1f, 100f, 2, 1f);
    assertEquals(10f, log[0], 1e-3f); // geometric mean
    final float[] mixed = ShadowMath.cascadeSplits(1f, 100f, 2, 0.5f);
    assertEquals(0.5f * 10f + 0.5f * 50.5f, mixed[0], 1e-3f);
    assertTrue(log[0] < ShadowMath.cascadeSplits(1f, 100f, 2, 0f)[0], "log splits give the near cascade less depth");
  }

  /** The 8 corners of the frustum slice between two distances. */
  private static Vector3f[] corners(Vector3f pos, Vector3f right, Vector3f up, Vector3f fwd, float near, float far) {
    final float tanY = (float) Math.tan(FOV / 2);
    final float tanX = tanY * ASPECT;
    final var out = new Vector3f[8];
    for (int i = 0; i < 8; i++) {
      final float d = (i & 1) == 0 ? near : far;
      out[i] = new Vector3f(pos).fma(d, fwd).fma(((i & 2) == 0 ? -1f : 1f) * d * tanX, right)
          .fma(((i & 4) == 0 ? -1f : 1f) * d * tanY, up);
    }
    return out;
  }

  @Test
  void boundingSphereContainsTheWholeSliceAndSitsOnTheViewAxis() {
    final var pos = new Vector3f(3f, 2f, -7f);
    final var fwd = new Vector3f(0.3f, -0.1f, -1f).normalize();
    final var right = fwd.cross(new Vector3f(0f, 1f, 0f), new Vector3f()).normalize();
    final var up = right.cross(fwd, new Vector3f()).normalize();
    for (float[] range : new float[][] { { 0.1f, 5f }, { 5f, 20f }, { 20f, 80f } }) {
      final var center = new Vector3f();
      final float r = ShadowMath.sliceBoundingSphere(pos, right, up, fwd, FOV, ASPECT, range[0], range[1], center);
      for (Vector3f c : corners(pos, right, up, fwd, range[0], range[1])) {
        assertTrue(c.distance(center) <= r + 1e-3f, "corner outside the sphere for " + range[0] + ".." + range[1]);
      }
      // on the axis: no sideways offset
      final var offset = new Vector3f(center).sub(pos);
      assertEquals(0f, offset.dot(right), 1e-3f);
      assertEquals(0f, offset.dot(up), 1e-3f);
      assertTrue(offset.dot(fwd) >= range[0] && offset.dot(fwd) <= range[1] + 1e-3f);
    }
  }

  @Test
  void sphereSizeDoesNotDependOnWhereTheCameraLooks() {
    final var center = new Vector3f();
    float radius = -1f;
    for (float yawDeg : new float[] { 0f, 37f, 123f, 250f }) {
      final var q = new Quaternionf().rotateY((float) Math.toRadians(yawDeg)).rotateX(0.3f);
      final var fwd = q.transform(new Vector3f(0f, 0f, -1f));
      final var right = q.transform(new Vector3f(1f, 0f, 0f));
      final var up = q.transform(new Vector3f(0f, 1f, 0f));
      final float r = ShadowMath.sliceBoundingSphere(new Vector3f(10f, 3f, 4f), right, up, fwd, FOV, ASPECT, 5f,
          25f, center);
      if (radius < 0) {
        radius = r;
      }
      assertEquals(radius, r, 1e-4f, "a constant radius keeps shadow resolution stable while turning");
    }
  }

  @Test
  void cascadeMatrixMapsTheWholeSliceIntoTheShadowMap() {
    final var pos = new Vector3f(0f, 5f, 0f);
    final var fwd = new Vector3f(0f, -0.2f, -1f).normalize();
    final var right = fwd.cross(new Vector3f(0f, 1f, 0f), new Vector3f()).normalize();
    final var up = right.cross(fwd, new Vector3f()).normalize();
    final var lightDir = new Vector3f(-0.4f, -0.8f, -0.3f);
    final float[] splits = ShadowMath.cascadeSplits(0.1f, 60f, 3, 0.7f);
    float near = 0.1f;
    for (float far : splits) {
      final var center = new Vector3f();
      final float r = ShadowMath.sliceBoundingSphere(pos, right, up, fwd, FOV, ASPECT, near, far, center);
      final var m = ShadowMath.cascadeViewProj(lightDir, center, r, 2048, new Matrix4f());
      for (Vector3f c : corners(pos, right, up, fwd, near, far)) {
        final var ndc = m.transform(new Vector4f(c, 1f));
        // snapping may shift the volume by up to a texel (2/2048 in NDC)
        assertTrue(Math.abs(ndc.x) <= 1.01f && Math.abs(ndc.y) <= 1.01f, "x/y inside: " + ndc);
        assertTrue(ndc.z >= -1f && ndc.z <= 1f, "depth inside: " + ndc.z);
      }
      near = far;
    }
  }

  @Test
  void cascadeMatrixIgnoresSubTexelMovementOfTheSphere() {
    final var dir = new Vector3f(-0.4f, -0.8f, -0.3f);
    final float radius = 12f;
    final float texel = 2f * radius / 1024f;
    final var d = new Vector3f(dir).normalize();
    final var right = d.cross(new Vector3f(0f, 1f, 0f), new Vector3f()).normalize();
    final var up = right.cross(d, new Vector3f()).normalize();
    final var base = new Vector3f().fma(Math.round(3f / texel) * texel + 0.2f * texel, right)
        .fma(Math.round(1f / texel) * texel + 0.2f * texel, up);
    final var a = ShadowMath.cascadeViewProj(dir, base, radius, 1024, new Matrix4f());
    final var b = ShadowMath.cascadeViewProj(dir, new Vector3f(base).fma(0.5f * texel, right), radius, 1024,
        new Matrix4f());
    assertTrue(a.equals(b, 1e-3f));
  }

  @Test
  void theCullingVolumeReachesFurtherTowardsTheLightThanTheCascade() {
    final var dir = new Vector3f(0f, -1f, 0f); // light shining down
    final var center = new Vector3f(0f, 0f, 0f);
    final var cascade = ShadowMath.cascadeViewProj(dir, center, 10f, 1024, new Matrix4f());
    final var volume = ShadowMath.cascadeCullingVolume(dir, center, 10f, 1024, 500f, new Matrix4f());
    final var tower = new Vector4f(0f, 100f, 0f, 1f); // a tall caster 100 m above the slice (> 4 radii)
    final var inCascade = cascade.transform(new Vector4f(tower));
    final var inVolume = volume.transform(new Vector4f(tower));
    assertTrue(inCascade.z / inCascade.w < -1f, "in front of the cascade's near plane (clamped when drawn)");
    assertTrue(Math.abs(inVolume.z / inVolume.w) <= 1f, "but inside the volume casters are looked for in");
    // the sides are the same: x/y of a point map identically
    final var side = new Vector4f(7f, 0f, -3f, 1f);
    assertEquals(cascade.transform(new Vector4f(side)).x, volume.transform(new Vector4f(side)).x, 1e-4f);
    assertEquals(cascade.transform(new Vector4f(side)).y, volume.transform(new Vector4f(side)).y, 1e-4f);
  }

  /** Java copy of the lookup in basic.frag's pointShadow(): face, uv and depth of a point seen from the light. */
  private static float[] shaderLookup(Vector3f d, float near, float far) {
    final var a = new Vector3f(Math.abs(d.x), Math.abs(d.y), Math.abs(d.z));
    final int face = a.x >= a.y && a.x >= a.z ? (d.x > 0 ? 0 : 1) : a.y >= a.z ? (d.y > 0 ? 2 : 3) : (d.z > 0 ? 4 : 5);
    final var f = ShadowMath.CUBE_FORWARD[face];
    final var u = ShadowMath.CUBE_UP[face];
    final var r = f.cross(u, new Vector3f());
    final float z = d.dot(f);
    final float ndc = (far + near) / (far - near) - 2f * far * near / ((far - near) * z);
    return new float[] { face, d.dot(r) / z * 0.5f + 0.5f, d.dot(u) / z * 0.5f + 0.5f, ndc * 0.5f + 0.5f };
  }

  @Test
  void cubeFaceMatricesMatchTheLookupInTheShader() {
    final var light = new Vector3f(1f, 2f, 3f);
    final var rnd = new java.util.Random(3);
    for (int i = 0; i < 200; i++) {
      final var d = new Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).mul(20f);
      final float[] lookup = shaderLookup(d, 0.05f, 15f);
      final var m = ShadowMath.cubeFaceViewProj(light, (int) lookup[0], 0.05f, 15f, new Matrix4f());
      final var clip = m.transform(new Vector4f(light.x + d.x, light.y + d.y, light.z + d.z, 1f));
      assertEquals(lookup[1], clip.x / clip.w * 0.5f + 0.5f, 1e-4f, "u " + i);
      assertEquals(lookup[2], clip.y / clip.w * 0.5f + 0.5f, 1e-4f, "v " + i);
      assertEquals(lookup[3], clip.z / clip.w * 0.5f + 0.5f, 1e-4f, "depth " + i);
      assertTrue(lookup[1] >= 0f && lookup[1] <= 1f && lookup[2] >= 0f && lookup[2] <= 1f, "on the chosen face");
    }
  }
}
