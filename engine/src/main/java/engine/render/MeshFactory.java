package engine.render;

import java.util.List;

import org.joml.Vector3f;

public final class MeshFactory {
  private MeshFactory() {
  }

  /** Unit-textured cube with {@code size} as half-extent: 24 vertices so every face has its own normal and uvs. */
  public static MeshData cube(float size) {
    // face: normal, then 4 corners (counter-clockwise seen from outside)
    final float[][] faces = {
        { 0, 0, 1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1 },
        { 0, 0, -1, 1, -1, -1, -1, -1, -1, -1, 1, -1, 1, 1, -1 },
        { 0, 1, 0, -1, 1, 1, 1, 1, 1, 1, 1, -1, -1, 1, -1 },
        { 0, -1, 0, -1, -1, -1, 1, -1, -1, 1, -1, 1, -1, -1, 1 },
        { 1, 0, 0, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1, 1, 1 },
        { -1, 0, 0, -1, -1, -1, -1, -1, 1, -1, 1, 1, -1, 1, -1 } };
    final float[][] uv = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };
    final float[] v = new float[24 * MeshData.INPUT_FLOATS_PER_VERTEX];
    final int[] idx = new int[36];
    int vi = 0;
    int ii = 0;
    for (int f = 0; f < 6; f++) {
      final float[] face = faces[f];
      final int base = f * 4;
      for (int c = 0; c < 4; c++) {
        v[vi++] = face[3 + c * 3] * size;
        v[vi++] = face[4 + c * 3] * size;
        v[vi++] = face[5 + c * 3] * size;
        v[vi++] = face[0];
        v[vi++] = face[1];
        v[vi++] = face[2];
        v[vi++] = uv[c][0];
        v[vi++] = uv[c][1];
      }
      idx[ii++] = base;
      idx[ii++] = base + 1;
      idx[ii++] = base + 2;
      idx[ii++] = base;
      idx[ii++] = base + 2;
      idx[ii++] = base + 3;
    }
    return MeshData.triangles(v, idx);
  }

  /** Quad in the XY plane facing +Z, centred on the origin. */
  public static MeshData quad(float width, float height) {
    return quad(width, height, 1f, 1f);
  }

  /** Like {@link #quad(float, float)} with the texture repeated {@code uRepeat} x {@code vRepeat} times. */
  public static MeshData quad(float width, float height, float uRepeat, float vRepeat) {
    final float w = width / 2;
    final float h = height / 2;
    final float[] v = {
        -w, -h, 0, 0, 0, 1, 0, 0,
        w, -h, 0, 0, 0, 1, uRepeat, 0,
        w, h, 0, 0, 0, 1, uRepeat, vRepeat,
        -w, h, 0, 0, 0, 1, 0, vRepeat };
    return MeshData.triangles(v, new int[] { 0, 1, 2, 0, 2, 3 });
  }

  /**
   * UV sphere centred on the origin: {@code segments} slices around the y axis, {@code rings} stacks from pole to
   * pole; u runs once around, v from the bottom (0) to the top (1).
   */
  public static MeshData sphere(float radius, int segments, int rings) {
    final int cols = segments + 1; // the seam is duplicated so u can run from 0 to 1
    final float[] v = new float[cols * (rings + 1) * MeshData.INPUT_FLOATS_PER_VERTEX];
    int vi = 0;
    for (int r = 0; r <= rings; r++) {
      final double phi = Math.PI * r / rings; // from the south pole up
      final float y = (float) -Math.cos(phi);
      final float ring = (float) Math.sin(phi);
      for (int s = 0; s < cols; s++) {
        final double theta = 2 * Math.PI * s / segments;
        final float x = (float) Math.cos(theta) * ring;
        final float z = (float) -Math.sin(theta) * ring;
        v[vi++] = x * radius;
        v[vi++] = y * radius;
        v[vi++] = z * radius;
        v[vi++] = x;
        v[vi++] = y;
        v[vi++] = z;
        v[vi++] = (float) s / segments;
        v[vi++] = (float) r / rings;
      }
    }
    final int[] idx = new int[segments * rings * 6];
    int ii = 0;
    for (int r = 0; r < rings; r++) {
      for (int s = 0; s < segments; s++) {
        final int a = r * cols + s;
        final int b = a + cols;
        idx[ii++] = a;
        idx[ii++] = a + 1;
        idx[ii++] = b + 1;
        idx[ii++] = a;
        idx[ii++] = b + 1;
        idx[ii++] = b;
      }
    }
    return MeshData.triangles(v, idx);
  }

  public static MeshData points(List<Vector3f> positions) {
    final float[] v = new float[positions.size() * MeshData.INPUT_FLOATS_PER_VERTEX];
    int i = 0;
    for (Vector3f p : positions) {
      v[i++] = p.x;
      v[i++] = p.y;
      v[i++] = p.z;
      v[i++] = 0;
      v[i++] = 1;
      v[i++] = 0;
      v[i++] = 0;
      v[i++] = 0;
    }
    return MeshData.points(v);
  }
}
