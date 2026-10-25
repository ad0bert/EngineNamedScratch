package engine.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import engine.render.MeshData;

class ObjLoaderTest {

  private static MeshData parse(String obj) throws IOException {
    return ObjLoader.load(new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)), "test");
  }

  @Test
  void quadsAreTriangulated() throws IOException {
    final var m = parse("""
        v 0 0 0
        v 1 0 0
        v 1 1 0
        v 0 1 0
        f 1 2 3 4
        """);
    assertEquals(6, m.indices().length);
    assertEquals(4, m.vertexCount());
  }

  @Test
  void missingNormalsBecomeFlatNormals() throws IOException {
    final var m = parse("""
        v 0 0 0
        v 1 0 0
        v 0 1 0
        f 1 2 3
        """);
    assertEquals(1f, m.vertices()[5], 1e-6); // normal z of the first vertex
  }

  @Test
  void negativeIndicesAndFullFaceFormat() throws IOException {
    final var m = parse("""
        v 0 0 0
        v 1 0 0
        v 0 1 0
        vt 0 0
        vt 1 0
        vt 0 1
        vn 0 0 1
        f -3/-3/-1 -2/-2/-1 -1/-1/-1
        """);
    assertEquals(3, m.vertexCount());
    assertEquals(1f, m.vertices()[MeshData.FLOATS_PER_VERTEX + 6], 1e-6); // u of 2nd vertex
  }

  @Test
  void vertexOnlyAndDoubleSlashFormats() throws IOException {
    final var m = parse("""
        v 0 0 0
        v 1 0 0
        v 0 1 0
        vn 0 0 1
        f 1//1 2//1 3//1
        """);
    assertEquals(3, m.vertexCount());
  }

  @Test
  void sharedVerticesAreDeduplicated() throws IOException {
    final var m = parse("""
        v 0 0 0
        v 1 0 0
        v 1 1 0
        v 0 1 0
        vn 0 0 1
        f 1//1 2//1 3//1
        f 1//1 3//1 4//1
        """);
    assertEquals(4, m.vertexCount());
    assertEquals(6, m.indices().length);
  }

  @Test
  void emptyModelsAndBadLinesAreErrors() {
    assertThrows(IOException.class, () -> parse("v 0 0 0\n"));
    assertThrows(IOException.class, () -> parse("v 0 0 0\nf 1 2 9\n"));
  }

  @Test
  void bundledModelsLoad() throws IOException {
    for (String path : new String[] { "objects/box.obj", "objects/capsule/capsule.obj" }) {
      try (var in = ObjLoaderTest.class.getClassLoader().getResourceAsStream(path)) {
        final var m = ObjLoader.load(in, path);
        assertEquals(0, m.indices().length % 3);
      }
    }
  }
}
