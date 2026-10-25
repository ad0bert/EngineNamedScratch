package engine.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.render.ImageData;

class NormalMapsTest {

  private static Vector3f normalAt(ImageData img, int x, int y) {
    final int o = (y * img.width() + x) * 4;
    return new Vector3f((img.rgba()[o] & 0xFF) / 255f * 2f - 1f, (img.rgba()[o + 1] & 0xFF) / 255f * 2f - 1f,
        (img.rgba()[o + 2] & 0xFF) / 255f * 2f - 1f);
  }

  @Test
  void flatSurfacesPointStraightUp() {
    final ImageData img = NormalMaps.fromHeights(new float[16], 4, 4, 5f);
    final Vector3f n = normalAt(img, 1, 2);
    assertEquals(0f, n.x, 0.01f);
    assertEquals(0f, n.y, 0.01f);
    assertEquals(1f, n.z, 0.01f);
    assertEquals((byte) 255, img.rgba()[3]);
  }

  @Test
  void aSlopeTiltsTheNormalAwayFromTheHigherSide() {
    // height grows with x: the normal must lean towards -x
    final float[] h = new float[8 * 8];
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        h[y * 8 + x] = x * 0.1f;
      }
    }
    final Vector3f n = normalAt(NormalMaps.fromHeights(h, 8, 8, 4f), 3, 3);
    assertTrue(n.x < -0.1f, "x " + n.x);
    assertEquals(0f, n.y, 0.01f);
    assertTrue(n.z > 0.5f && n.z < 1f);
    assertEquals(1f, n.length(), 0.02f, "normals are unit length");

    // same for y
    final float[] hy = new float[8 * 8];
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        hy[y * 8 + x] = y * 0.1f;
      }
    }
    assertTrue(normalAt(NormalMaps.fromHeights(hy, 8, 8, 4f), 3, 3).y < -0.1f);
  }

  @Test
  void strengthScalesTheTilt() {
    final float[] h = new float[8 * 8];
    for (int i = 0; i < h.length; i++) {
      h[i] = (i % 8) * 0.05f;
    }
    final float weak = normalAt(NormalMaps.fromHeights(h, 8, 8, 1f), 3, 3).x;
    final float strong = normalAt(NormalMaps.fromHeights(h, 8, 8, 8f), 3, 3).x;
    assertTrue(strong < weak && weak < 0f);
  }

  @Test
  void bordersWrapSoTexturesTile() {
    final float[] h = new float[4 * 4];
    h[0] = 1f; // a single bump in the corner also affects the opposite borders
    final ImageData img = NormalMaps.fromHeights(h, 4, 4, 4f);
    assertTrue(normalAt(img, 3, 0).x < -0.1f, "pixel left of the bump (wrapped): the ground rises towards +x, so it leans to -x");
    assertTrue(normalAt(img, 0, 3).y < -0.1f, "pixel below the bump (wrapped): the ground rises towards +y, so it leans to -y");
  }

  @Test
  void wrongArraySizeIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> NormalMaps.fromHeights(new float[5], 2, 2, 1f));
  }
}
