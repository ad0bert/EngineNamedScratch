package engine.render;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Coarser versions of a triangle mesh for distant levels of detail ({@link MeshRenderer#addLod}), by vertex
 * clustering: the mesh's box is cut into cubes, the vertices in a cube whose normals point roughly the same way (the
 * same of six axis directions, so a box keeps its corners) become one vertex at their average, and triangles that
 * collapse are dropped. Fast and robust for any input (no manifold needed); it does not preserve uv seams, which is
 * rarely visible at the distances such levels are used. Skin weights are those of one vertex of each cluster.
 */
public final class MeshSimplifier {
  private MeshSimplifier() {
  }

  /** Clusters vertices in cubes of edge {@code cellSize} (model units). */
  public static MeshData cluster(MeshData mesh, float cellSize) {
    if (mesh.drawMode() != MeshData.DrawMode.TRIANGLES || mesh.indices() == null) {
      throw new IllegalArgumentException("only indexed triangle meshes can be simplified");
    }
    if (cellSize <= 0f) {
      return mesh;
    }
    final float[] v = mesh.vertices();
    final int[] idx = mesh.indices();
    final int stride = MeshData.FLOATS_PER_VERTEX;
    final int n = v.length / stride;
    final var min = mesh.min();
    final Map<Long, Integer> clusters = new HashMap<>();
    final int[] clusterOf = new int[n];
    final int[] count = new int[n];
    final int[] firstVertex = new int[n];
    float[] sum = new float[n * stride];
    int clusterCount = 0;
    for (int i = 0; i < n; i++) {
      final int o = i * stride;
      final long cx = (long) Math.floor((v[o] - min.x) / cellSize);
      final long cy = (long) Math.floor((v[o + 1] - min.y) / cellSize);
      final long cz = (long) Math.floor((v[o + 2] - min.z) / cellSize);
      final long key = ((cx & 0xFFFFF) << 43) | ((cy & 0xFFFFF) << 23) | ((cz & 0xFFFFF) << 3) | facing(v, o);
      Integer c = clusters.get(key);
      if (c == null) {
        c = clusterCount++;
        clusters.put(key, c);
        firstVertex[c] = i;
      }
      clusterOf[i] = c;
      count[c]++;
      for (int k = 0; k < stride; k++) {
        sum[c * stride + k] += v[o + k];
      }
    }
    final float[] out = new float[clusterCount * stride];
    for (int c = 0; c < clusterCount; c++) {
      final int o = c * stride;
      for (int k = 0; k < stride; k++) {
        out[o + k] = sum[o + k] / count[c];
      }
      normalize(out, o + 3, 3);
      normalize(out, o + MeshData.TANGENT_OFFSET, 3);
      // keep the handedness sign exact
      out[o + 11] = v[firstVertex[c] * stride + 11] >= 0f ? 1f : -1f;
    }
    sum = null;
    final int[] tris = new int[idx.length];
    int t = 0;
    final Set<Long> seen = new HashSet<>();
    for (int i = 0; i + 2 < idx.length; i += 3) {
      final int a = clusterOf[idx[i]];
      final int b = clusterOf[idx[i + 1]];
      final int c = clusterOf[idx[i + 2]];
      if (a == b || b == c || a == c) {
        continue; // collapsed
      }
      // the same triangle again (with any rotation of its corners) is drawn once
      final int lo = Math.min(a, Math.min(b, c));
      final int x = lo == a ? b : lo == b ? c : a;
      final int y = lo == a ? c : lo == b ? a : b;
      if (!seen.add(((long) lo << 42) | ((long) x << 21) | y)) {
        continue;
      }
      tris[t++] = a;
      tris[t++] = b;
      tris[t++] = c;
    }
    float[] skin = null;
    if (mesh.skin() != null) {
      final int ss = MeshData.SKIN_FLOATS_PER_VERTEX;
      skin = new float[clusterCount * ss];
      for (int c = 0; c < clusterCount; c++) {
        System.arraycopy(mesh.skin(), firstVertex[c] * ss, skin, c * ss, ss);
      }
    }
    final var data = MeshData.trianglesWithTangents(out, Arrays.copyOf(tris, t));
    return skin != null ? data.withSkin(skin) : data;
  }

  /**
   * About {@code ratio} (0..1) of the triangles: the cell size is searched for (a few clustering passes). A ratio of
   * 1 or more returns the mesh itself.
   */
  public static MeshData simplify(MeshData mesh, float ratio) {
    final int target = Math.max(1, Math.round(mesh.indices().length / 3 * ratio));
    if (ratio >= 1f || mesh.indices().length / 3 <= target) {
      return mesh;
    }
    final float size = Math.max(mesh.max().x - mesh.min().x,
        Math.max(mesh.max().y - mesh.min().y, mesh.max().z - mesh.min().z));
    float lo = size * 1e-4f;
    float hi = size;
    MeshData best = cluster(mesh, hi);
    for (int i = 0; i < 16; i++) {
      final float mid = (float) Math.sqrt(lo * hi); // cell sizes span orders of magnitude: search them evenly
      final var m = cluster(mesh, mid);
      final int tris = m.indices().length / 3;
      if (tris > target) {
        lo = mid;
      } else {
        hi = mid;
        best = m;
      }
      if (Math.abs(tris - target) <= Math.max(1, target / 20)) {
        return m;
      }
    }
    return best;
  }

  /** The axis direction (0..5) the vertex normal is closest to. */
  private static int facing(float[] v, int o) {
    final float x = v[o + 3];
    final float y = v[o + 4];
    final float z = v[o + 5];
    final float ax = Math.abs(x);
    final float ay = Math.abs(y);
    final float az = Math.abs(z);
    if (ax >= ay && ax >= az) {
      return x >= 0f ? 0 : 1;
    }
    if (ay >= az) {
      return y >= 0f ? 2 : 3;
    }
    return z >= 0f ? 4 : 5;
  }

  private static void normalize(float[] a, int o, int n) {
    float len = 0f;
    for (int i = 0; i < n; i++) {
      len += a[o + i] * a[o + i];
    }
    if (len > 1e-16f) {
      final float inv = (float) (1.0 / Math.sqrt(len));
      for (int i = 0; i < n; i++) {
        a[o + i] *= inv;
      }
    }
  }
}
