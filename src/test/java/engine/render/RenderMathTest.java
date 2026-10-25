package engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

class RenderMathTest {

  @Test
  void shadowMatrixCentresTheFocusPoint() {
    final var focus = new Vector3f(3f, 0f, -2f);
    final var m = ShadowMath.cascadeViewProj(new Vector3f(-0.4f, -0.8f, -0.3f), focus, 25f, 2048, new Matrix4f());
    final var ndc = m.transform(new Vector4f(focus, 1f));
    // snapping moves the centre by less than one texel (2/2048 in NDC)
    assertEquals(0f, ndc.x / ndc.w, 0.002f);
    assertEquals(0f, ndc.y / ndc.w, 0.002f);
    assertTrue(ndc.z / ndc.w > -1f && ndc.z / ndc.w < 1f);
  }

  @Test
  void shadowMatrixIsStableUnderSubTexelCameraMovement() {
    final var dir = new Vector3f(-0.4f, -0.8f, -0.3f);
    final float half = 25f;
    final float texel = 2f * half / 2048f;
    // pick a focus that sits 0.1 texel inside a grid cell, in light space
    final var d = new Vector3f(dir).normalize();
    final var right = d.cross(new Vector3f(0f, 1f, 0f), new Vector3f()).normalize();
    final var up = right.cross(d, new Vector3f()).normalize();
    final var base = new Vector3f().fma(Math.round(5f / texel) * texel + 0.1f * texel, right)
        .fma(Math.round(2f / texel) * texel + 0.1f * texel, up);
    final var moved = new Vector3f(base).fma(0.5f * texel, right).fma(0.5f * texel, up);

    final var a = ShadowMath.cascadeViewProj(dir, base, half, 2048, new Matrix4f());
    final var b = ShadowMath.cascadeViewProj(dir, moved, half, 2048, new Matrix4f());
    assertTrue(a.equals(b, 1e-3f), "sub-texel movement must not change the shadow matrix");

    final var far = new Vector3f(base).fma(3f * texel, right);
    final var c = ShadowMath.cascadeViewProj(dir, far, half, 2048, new Matrix4f());
    assertTrue(!a.equals(c, 1e-6f), "a multi-texel move must shift the shadow map");
  }

  @Test
  void straightDownLightDoesNotProduceNaN() {
    final var m = ShadowMath.cascadeViewProj(new Vector3f(0f, -1f, 0f), new Vector3f(), 10f, 1024, new Matrix4f());
    assertTrue(Float.isFinite(m.m00()) && Float.isFinite(m.m11()) && Float.isFinite(m.m22()));
  }

  @Test
  void worldSphereFollowsTranslationAndLargestScale() {
    final var cube = MeshFactory.cube(1f); // half extent 1, radius sqrt(3)
    final var center = new Vector3f();
    final var model = new Matrix4f().translate(5f, 0f, 0f).scale(2f, 1f, 1f);
    final float r = ShadowMath.worldSphere(cube, model, center);
    assertEquals(5f, center.x, 1e-5f);
    assertEquals((float) Math.sqrt(3) * 2f, r, 1e-4f);
  }

  @Test
  void meshDataBoundsAndFactories() {
    final var cube = MeshFactory.cube(0.5f);
    assertEquals(24, cube.vertexCount());
    assertEquals(36, cube.indices().length);
    assertEquals(0.5f, cube.halfExtents().x, 1e-6f);
    final var quad = MeshFactory.quad(4f, 2f);
    assertEquals(2f, quad.halfExtents().x, 1e-6f);
    assertEquals(1f, quad.halfExtents().y, 1e-6f);
    assertEquals(MeshData.DrawMode.POINTS,
        MeshFactory.points(java.util.List.of(new Vector3f(1f, 2f, 3f))).drawMode());
  }

  @Test
  void imageRowsAreStoredBottomUp() {
    final var img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
    img.setRGB(0, 0, 0xFFFF0000); // top-left red
    img.setRGB(0, 1, 0xFF0000FF); // bottom-left blue
    final var data = ImageData.fromBufferedImage(img);
    assertEquals((byte) 0, data.rgba()[0]); // first pixel = bottom-left = blue
    assertEquals((byte) 255, data.rgba()[2]);
    final int topLeft = (1 * 2 + 0) * 4;
    assertEquals((byte) 255, data.rgba()[topLeft]); // red
  }

  @Test
  void fontAtlasGlyphsAndSolidCell() {
    final var atlas = new FontAtlas(16);
    final var uv = new float[4];
    atlas.glyphUv('A', uv);
    assertTrue(uv[0] >= 0f && uv[2] <= 1f && uv[0] < uv[2]);
    assertTrue(uv[1] > uv[3] && uv[1] <= 1f && uv[3] >= 0f); // top v is larger: rows are bottom-up
    atlas.glyphUv('é', uv); // unknown characters fall back to '?'
    final var q = new float[4];
    atlas.glyphUv('?', q);
    assertEquals(q[0], uv[0], 0f);

    atlas.solidUv(uv);
    final var img = atlas.image();
    final int px = Math.min(img.width() - 1, Math.round(uv[0] * img.width()));
    final int py = Math.min(img.height() - 1, Math.round(uv[1] * img.height()));
    assertEquals((byte) 255, img.rgba()[(py * img.width() + px) * 4 + 3]);

    // the 'A' cell must contain some inked pixels
    atlas.glyphUv('A', uv);
    int ink = 0;
    for (int y = Math.round(uv[3] * img.height()); y < Math.round(uv[1] * img.height()); y++) {
      for (int x = Math.round(uv[0] * img.width()); x < Math.round(uv[2] * img.width()); x++) {
        if (img.rgba()[(y * img.width() + x) * 4 + 3] != 0) {
          ink++;
        }
      }
    }
    assertTrue(ink > 10, "glyph A should have visible pixels, had " + ink);
  }
}
