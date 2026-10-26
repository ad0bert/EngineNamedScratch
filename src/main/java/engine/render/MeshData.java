package engine.render;

import org.joml.Vector3f;

/**
 * CPU-side mesh: interleaved position(3), normal(3), uv(2), tangent(4) floats plus optional triangle indices. With
 * {@code indices == null} the vertices are drawn in order (used for points).
 *
 * <p>
 * The factory methods take vertices <em>without</em> tangents ({@link #INPUT_FLOATS_PER_VERTEX} floats each) and
 * derive them from the texture coordinates: xyz is the direction of increasing u, w (+1 or -1) the handedness of the
 * uv layout, so mirrored texture halves are lit correctly. A vertex whose triangles have no usable uvs gets a zero
 * tangent, and the shader then falls back to screen-space derivatives.
 *
 * <p>
 * A skinned mesh also carries {@link #skin}: {@link #SKIN_FLOATS_PER_VERTEX} floats per vertex, four joint indices
 * (whole numbers stored as floats) and their four weights; see {@link #withSkin} and {@link Skin}.
 */
public record MeshData(float[] vertices, int[] indices, DrawMode drawMode, Vector3f min, Vector3f max,
    float[] skin) {
  public static final int FLOATS_PER_VERTEX = 12;
  public static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;
  /** Layout of the vertices given to the factory methods: position, normal, uv. */
  public static final int INPUT_FLOATS_PER_VERTEX = 8;
  public static final int TANGENT_OFFSET = 8;
  /** Joint indices (4) and weights (4) per vertex of a skinned mesh. */
  public static final int SKIN_FLOATS_PER_VERTEX = 8;

  /** A mesh without skinning data. */
  public MeshData(float[] vertices, int[] indices, DrawMode drawMode, Vector3f min, Vector3f max) {
    this(vertices, indices, drawMode, min, max, null);
  }

  public enum DrawMode {
    TRIANGLES, POINTS
  }

  /** @param vertices position, normal, uv per vertex ({@link #INPUT_FLOATS_PER_VERTEX} floats) */
  public static MeshData triangles(float[] vertices, int[] indices) {
    return build(withTangents(vertices, indices), indices, DrawMode.TRIANGLES);
  }

  /** @param vertices position, normal, uv per vertex ({@link #INPUT_FLOATS_PER_VERTEX} floats) */
  public static MeshData points(float[] vertices) {
    return build(withTangents(vertices, null), null, DrawMode.POINTS);
  }

  /** Vertices that already carry tangents ({@link #FLOATS_PER_VERTEX} floats each), e.g. from an asset pipeline. */
  public static MeshData trianglesWithTangents(float[] vertices, int[] indices) {
    if (vertices.length % FLOATS_PER_VERTEX != 0) {
      throw new IllegalArgumentException("vertex array is not a multiple of " + FLOATS_PER_VERTEX);
    }
    return build(vertices, indices, DrawMode.TRIANGLES);
  }

  private static MeshData build(float[] vertices, int[] indices, DrawMode mode) {
    final var min = new Vector3f(Float.MAX_VALUE);
    final var max = new Vector3f(-Float.MAX_VALUE);
    for (int i = 0; i < vertices.length; i += FLOATS_PER_VERTEX) {
      min.set(Math.min(min.x, vertices[i]), Math.min(min.y, vertices[i + 1]), Math.min(min.z, vertices[i + 2]));
      max.set(Math.max(max.x, vertices[i]), Math.max(max.y, vertices[i + 1]), Math.max(max.z, vertices[i + 2]));
    }
    if (vertices.length == 0) {
      min.set(0f);
      max.set(0f);
    }
    return new MeshData(vertices, indices, mode, min, max);
  }

  /**
   * Expands position/normal/uv vertices with per-vertex tangents accumulated over the triangles (Lengyel's method),
   * made perpendicular to the normal; the bitangent direction decides the sign in w.
   */
  static float[] withTangents(float[] in, int[] indices) {
    if (in.length % INPUT_FLOATS_PER_VERTEX != 0) {
      throw new IllegalArgumentException("vertex array is not a multiple of " + INPUT_FLOATS_PER_VERTEX);
    }
    final int n = in.length / INPUT_FLOATS_PER_VERTEX;
    final float[] out = new float[n * FLOATS_PER_VERTEX];
    for (int v = 0; v < n; v++) {
      System.arraycopy(in, v * INPUT_FLOATS_PER_VERTEX, out, v * FLOATS_PER_VERTEX, INPUT_FLOATS_PER_VERTEX);
    }
    if (indices == null) {
      return out;
    }
    final float[] tan = new float[n * 3];
    final float[] bit = new float[n * 3];
    for (int t = 0; t + 2 < indices.length; t += 3) {
      final int i0 = indices[t];
      final int i1 = indices[t + 1];
      final int i2 = indices[t + 2];
      final int a = i0 * INPUT_FLOATS_PER_VERTEX;
      final int b = i1 * INPUT_FLOATS_PER_VERTEX;
      final int c = i2 * INPUT_FLOATS_PER_VERTEX;
      final float e1x = in[b] - in[a];
      final float e1y = in[b + 1] - in[a + 1];
      final float e1z = in[b + 2] - in[a + 2];
      final float e2x = in[c] - in[a];
      final float e2y = in[c + 1] - in[a + 1];
      final float e2z = in[c + 2] - in[a + 2];
      final float du1 = in[b + 6] - in[a + 6];
      final float dv1 = in[b + 7] - in[a + 7];
      final float du2 = in[c + 6] - in[a + 6];
      final float dv2 = in[c + 7] - in[a + 7];
      final float det = du1 * dv2 - du2 * dv1;
      if (Math.abs(det) < 1e-12f) {
        continue; // no uv area: this triangle says nothing about the tangent
      }
      final float r = 1f / det;
      final float tx = (e1x * dv2 - e2x * dv1) * r;
      final float ty = (e1y * dv2 - e2y * dv1) * r;
      final float tz = (e1z * dv2 - e2z * dv1) * r;
      final float bx = (e2x * du1 - e1x * du2) * r;
      final float by = (e2y * du1 - e1y * du2) * r;
      final float bz = (e2z * du1 - e1z * du2) * r;
      for (int k = 0; k < 3; k++) {
        final int vi = k == 0 ? i0 : k == 1 ? i1 : i2;
        tan[vi * 3] += tx;
        tan[vi * 3 + 1] += ty;
        tan[vi * 3 + 2] += tz;
        bit[vi * 3] += bx;
        bit[vi * 3 + 1] += by;
        bit[vi * 3 + 2] += bz;
      }
    }
    for (int v = 0; v < n; v++) {
      final int o = v * FLOATS_PER_VERTEX;
      final float nx = out[o + 3];
      final float ny = out[o + 4];
      final float nz = out[o + 5];
      float tx = tan[v * 3];
      float ty = tan[v * 3 + 1];
      float tz = tan[v * 3 + 2];
      final float d = nx * tx + ny * ty + nz * tz; // Gram-Schmidt against the normal
      tx -= nx * d;
      ty -= ny * d;
      tz -= nz * d;
      final float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
      if (len < 1e-8f) {
        continue; // stays zero: the shader derives a frame on screen
      }
      tx /= len;
      ty /= len;
      tz /= len;
      // handedness: does (n x t) point along the accumulated bitangent?
      final float cx = ny * tz - nz * ty;
      final float cy = nz * tx - nx * tz;
      final float cz = nx * ty - ny * tx;
      final float w = cx * bit[v * 3] + cy * bit[v * 3 + 1] + cz * bit[v * 3 + 2] < 0f ? -1f : 1f;
      out[o + TANGENT_OFFSET] = tx;
      out[o + TANGENT_OFFSET + 1] = ty;
      out[o + TANGENT_OFFSET + 2] = tz;
      out[o + TANGENT_OFFSET + 3] = w;
    }
    return out;
  }

  /**
   * The same mesh with joint indices and weights ({@link #SKIN_FLOATS_PER_VERTEX} per vertex), for GPU skinning.
   * Weights are normalized to sum to 1.
   */
  public MeshData withSkin(float[] skin) {
    if (skin.length != vertexCount() * SKIN_FLOATS_PER_VERTEX) {
      throw new IllegalArgumentException("need " + SKIN_FLOATS_PER_VERTEX + " skin floats per vertex");
    }
    final float[] s = skin.clone();
    for (int v = 0; v < s.length; v += SKIN_FLOATS_PER_VERTEX) {
      final float sum = s[v + 4] + s[v + 5] + s[v + 6] + s[v + 7];
      if (sum > 0f) {
        for (int k = 4; k < 8; k++) {
          s[v + k] /= sum;
        }
      } else {
        s[v + 4] = 1f; // unweighted vertices follow the first joint
      }
    }
    return new MeshData(this.vertices, this.indices, this.drawMode, this.min, this.max, s);
  }

  public boolean isSkinned() {
    return this.skin != null;
  }

  public int vertexCount() {
    return this.vertices.length / FLOATS_PER_VERTEX;
  }

  public int drawCount() {
    return this.indices != null ? this.indices.length : vertexCount();
  }

  /** Half the size of the axis-aligned bounding box. */
  public Vector3f halfExtents() {
    return new Vector3f(this.max).sub(this.min).mul(0.5f);
  }

  public Vector3f center() {
    return new Vector3f(this.min).add(this.max).mul(0.5f);
  }
}
