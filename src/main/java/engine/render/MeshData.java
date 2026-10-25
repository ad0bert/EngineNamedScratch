package engine.render;

import org.joml.Vector3f;

/**
 * CPU-side mesh: interleaved position(3), normal(3), uv(2) floats plus optional triangle indices. With
 * {@code indices == null} the vertices are drawn in order (used for points).
 */
public record MeshData(float[] vertices, int[] indices, DrawMode drawMode, Vector3f min, Vector3f max) {
  public static final int FLOATS_PER_VERTEX = 8;
  public static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

  public enum DrawMode {
    TRIANGLES, POINTS
  }

  public static MeshData triangles(float[] vertices, int[] indices) {
    return build(vertices, indices, DrawMode.TRIANGLES);
  }

  public static MeshData points(float[] vertices) {
    return build(vertices, null, DrawMode.POINTS);
  }

  private static MeshData build(float[] vertices, int[] indices, DrawMode mode) {
    final Vector3f min = new Vector3f(Float.MAX_VALUE);
    final Vector3f max = new Vector3f(-Float.MAX_VALUE);
    for (int i = 0; i < vertices.length; i += FLOATS_PER_VERTEX) {
      min.min(new Vector3f(vertices[i], vertices[i + 1], vertices[i + 2]));
      max.max(new Vector3f(vertices[i], vertices[i + 1], vertices[i + 2]));
    }
    if (vertices.length == 0) {
      min.set(0f);
      max.set(0f);
    }
    return new MeshData(vertices, indices, mode, min, max);
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
