package engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class MeshDataTest {
  private static Vector3f tangent(MeshData m, int vertex) {
    final int o = vertex * MeshData.FLOATS_PER_VERTEX + MeshData.TANGENT_OFFSET;
    return new Vector3f(m.vertices()[o], m.vertices()[o + 1], m.vertices()[o + 2]);
  }

  private static float handedness(MeshData m, int vertex) {
    return m.vertices()[vertex * MeshData.FLOATS_PER_VERTEX + MeshData.TANGENT_OFFSET + 3];
  }

  @Test
  void aQuadsTangentPointsAlongIncreasingU() {
    final var q = MeshFactory.quad(2f, 1f);
    for (int v = 0; v < 4; v++) {
      assertEquals(1f, tangent(q, v).x, 1e-5f);
      assertEquals(1f, handedness(q, v), 0f);
    }
  }

  @Test
  void aMirroredUvLayoutFlipsTheHandedness() {
    // the quad's u runs right to left: tangent points to -x and the bitangent (+v, up) is on the other side
    final float[] v = {
        -1, -1, 0, 0, 0, 1, 1, 0,
        1, -1, 0, 0, 0, 1, 0, 0,
        1, 1, 0, 0, 0, 1, 0, 1,
        -1, 1, 0, 0, 0, 1, 1, 1 };
    final var m = MeshData.triangles(v, new int[] { 0, 1, 2, 0, 2, 3 });
    for (int i = 0; i < 4; i++) {
      assertEquals(-1f, tangent(m, i).x, 1e-5f);
      assertEquals(-1f, handedness(m, i), 0f);
    }
  }

  @Test
  void tangentsArePerpendicularToTheNormalOnACurvedMesh() {
    final var s = MeshFactory.sphere(1f, 16, 8);
    int checked = 0;
    for (int i = 0; i < s.vertexCount(); i++) {
      final int o = i * MeshData.FLOATS_PER_VERTEX;
      final var n = new Vector3f(s.vertices()[o + 3], s.vertices()[o + 4], s.vertices()[o + 5]);
      assertEquals(1f, n.length(), 1e-4f);
      final var p = new Vector3f(s.vertices()[o], s.vertices()[o + 1], s.vertices()[o + 2]);
      assertEquals(1f, p.length(), 1e-4f, "on the sphere");
      final var t = tangent(s, i);
      if (t.lengthSquared() > 0f) { // the poles have no defined u direction
        assertEquals(0f, t.dot(n), 1e-4f);
        assertEquals(1f, t.length(), 1e-4f);
        checked++;
      }
    }
    assertTrue(checked > s.vertexCount() / 2);
    assertEquals(16 * 8 * 6, s.indices().length);
  }

  @Test
  void pointsAndMeshesWithoutUvsGetNoTangent() {
    final var p = MeshFactory.points(java.util.List.of(new Vector3f(1f, 2f, 3f)));
    assertEquals(0f, tangent(p, 0).length(), 0f);
    final float[] v = { 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0 };
    final var flat = MeshData.triangles(v, new int[] { 0, 1, 2 });
    assertEquals(0f, tangent(flat, 0).length(), 0f, "the shader then derives a frame on screen");
  }
}
