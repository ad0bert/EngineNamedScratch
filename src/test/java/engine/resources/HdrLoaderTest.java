package engine.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import engine.render.HdrImage;
import engine.render.Sky;

class HdrLoaderTest {
  private static byte[] header(String resolution) {
    return ("#?RADIANCE\n# made by a test\nFORMAT=32-bit_rle_rgbe\nEXPOSURE=1.0\n\n" + resolution + "\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  /** RGBE of a colour, the usual way (frexp). */
  private static byte[] rgbe(float r, float g, float b) {
    final float m = Math.max(r, Math.max(g, b));
    if (m < 1e-32f) {
      return new byte[4];
    }
    final int e = Math.getExponent(m) + 1;
    final float f = Math.scalb(256f, -e);
    return new byte[] { (byte) (int) (r * f), (byte) (int) (g * f), (byte) (int) (b * f), (byte) (e + 128) };
  }

  private static float[] pixel(HdrImage image, int x, int y) {
    final float[] p = new float[3];
    image.pixel(x, y, p);
    return p;
  }

  @Test
  void readsFlatPixelsTopRowFirst() throws IOException {
    final var out = new ByteArrayOutputStream();
    out.write(header("-Y 2 +X 3"));
    final float[][] colours = { { 1f, 0.5f, 0.25f }, { 4f, 2f, 1f }, { 0f, 0f, 0f }, { 0.1f, 0.2f, 0.3f },
        { 100f, 50f, 10f }, { 1f, 1f, 1f } };
    for (final float[] c : colours) {
      out.write(rgbe(c[0], c[1], c[2]));
    }
    final var image = HdrLoader.load(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(3, image.width());
    assertEquals(2, image.height());
    for (int i = 0; i < colours.length; i++) {
      final float[] p = pixel(image, i % 3, i / 3);
      final float max = Math.max(colours[i][0], Math.max(colours[i][1], colours[i][2]));
      for (int k = 0; k < 3; k++) { // eight bits of mantissa shared by the three channels
        assertEquals(colours[i][k], p[k], max / 128f + 1e-6f, "pixel " + i + " channel " + k);
      }
    }
  }

  @Test
  void readsRunLengthEncodedScanlinesAndFlipsBottomUpImages() throws IOException {
    final int width = 10;
    final var out = new ByteArrayOutputStream();
    out.write(header("+Y 2 +X " + width));
    final byte[] red = rgbe(2f, 0f, 0f);
    final byte[] blue = rgbe(0f, 0f, 0.5f);
    for (int row = 0; row < 2; row++) {
      final byte[] c = row == 0 ? red : blue; // stored bottom row first
      out.write(new byte[] { 2, 2, 0, (byte) width });
      for (int channel = 0; channel < 4; channel++) {
        // a run of 7, then 3 literal bytes
        out.write(new byte[] { (byte) (128 + 7), c[channel], 3, c[channel], c[channel], c[channel] });
      }
    }
    final var image = HdrLoader.load(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(width, image.width());
    assertEquals(0.5f, pixel(image, 9, 0)[2], 0.01f, "the top row is the last one stored");
    assertEquals(2f, pixel(image, 0, 1)[0], 0.02f);
    assertEquals(0f, pixel(image, 4, 1)[2], 1e-6f);
  }

  @Test
  void readsOldStyleRunsThatRepeatThePreviousPixel() throws IOException {
    final var out = new ByteArrayOutputStream();
    out.write(header("-Y 1 +X 5"));
    out.write(rgbe(1f, 1f, 1f));
    out.write(new byte[] { 1, 1, 1, 3 }); // three more of it
    out.write(rgbe(0.5f, 0f, 0f));
    final var image = HdrLoader.load(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(1f, pixel(image, 3, 0)[1], 0.02f);
    assertEquals(0.5f, pixel(image, 4, 0)[0], 0.01f);
  }

  @Test
  void rejectsOtherFilesAndTruncatedOnes() {
    assertThrows(IOException.class,
        () -> HdrLoader.load(new ByteArrayInputStream("P6\n1 1\n255\n".getBytes(StandardCharsets.US_ASCII))));
    final var truncated = new ByteArrayOutputStream();
    truncated.writeBytes(header("-Y 4 +X 4"));
    truncated.writeBytes(rgbe(1f, 1f, 1f));
    assertThrows(IOException.class, () -> HdrLoader.load(new ByteArrayInputStream(truncated.toByteArray())));
  }

  @Test
  void aSkyRebakesOnlyWhenWhatItBakesChanges() {
    final var sky = Sky.procedural();
    final long key = sky.bakeKey();
    sky.sunSize = 2f; // the sun is drawn, not baked
    assertEquals(key, sky.bakeKey());
    sky.horizon.set(1f, 0.5f, 0.2f);
    assertNotEquals(key, sky.bakeKey());
    final var image = Sky.image(new HdrImage(2, 1, new float[6]));
    assertEquals(false, image.sunDisc, "an image brings its own sun");
    assertNotEquals(sky.bakeKey(), image.bakeKey());
  }
}
