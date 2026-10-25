package engine.render;

import java.awt.image.BufferedImage;

/**
 * Decoded RGBA8 pixels. Rows are stored bottom-to-top (OpenGL order) so that uv (0,0) is the bottom-left corner, the
 * same convention OBJ files use.
 */
public record ImageData(int width, int height, byte[] rgba) {

  public ImageData {
    if (rgba.length != width * height * 4) {
      throw new IllegalArgumentException("expected " + width * height * 4 + " bytes, got " + rgba.length);
    }
  }

  public static ImageData solid(int r, int g, int b, int a) {
    return new ImageData(1, 1, new byte[] { (byte) r, (byte) g, (byte) b, (byte) a });
  }

  public static ImageData fromBufferedImage(BufferedImage img) {
    final int w = img.getWidth();
    final int h = img.getHeight();
    final int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
    final byte[] out = new byte[w * h * 4];
    for (int y = 0; y < h; y++) {
      final int srcRow = (h - 1 - y) * w; // flip: image row 0 is the top
      for (int x = 0; x < w; x++) {
        final int p = argb[srcRow + x];
        final int o = (y * w + x) * 4;
        out[o] = (byte) (p >> 16);
        out[o + 1] = (byte) (p >> 8);
        out[o + 2] = (byte) p;
        out[o + 3] = (byte) (p >>> 24);
      }
    }
    return new ImageData(w, h, out);
  }
}
