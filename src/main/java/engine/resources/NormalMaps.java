package engine.resources;

import engine.render.ImageData;

/** Builds tangent-space normal maps from height data, so normal-mapped surfaces need no art assets. */
public final class NormalMaps {
  private NormalMaps() {
  }

  /**
   * Converts a height field (values around 0..1, row 0 at the bottom like {@link ImageData}) into an RGBA normal map
   * using central differences. The field wraps around at the borders, so the result tiles seamlessly.
   *
   * @param strength how steep the surface is; 1 means a height difference of 1 over one texel is a 45 degree slope
   */
  public static ImageData fromHeights(float[] heights, int width, int height, float strength) {
    if (heights.length != width * height) {
      throw new IllegalArgumentException("expected " + width * height + " heights, got " + heights.length);
    }
    final byte[] rgba = new byte[width * height * 4];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final float left = heights[y * width + (x + width - 1) % width];
        final float right = heights[y * width + (x + 1) % width];
        final float down = heights[((y + height - 1) % height) * width + x];
        final float up = heights[((y + 1) % height) * width + x];
        // the surface slopes up towards +x/+y, so the normal tilts the opposite way
        float nx = (left - right) * 0.5f * strength;
        float ny = (down - up) * 0.5f * strength;
        float nz = 1f;
        final float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= len;
        ny /= len;
        nz /= len;
        final int o = (y * width + x) * 4;
        rgba[o] = encode(nx);
        rgba[o + 1] = encode(ny);
        rgba[o + 2] = encode(nz);
        rgba[o + 3] = (byte) 255;
      }
    }
    return new ImageData(width, height, rgba);
  }

  /** Height field as a greyscale image (0 black, 1 white), e.g. for {@code Material.heightMap}. */
  public static ImageData heightImage(float[] heights, int width, int height) {
    if (heights.length != width * height) {
      throw new IllegalArgumentException("expected " + width * height + " heights, got " + heights.length);
    }
    final byte[] rgba = new byte[width * height * 4];
    for (int i = 0; i < heights.length; i++) {
      final byte v = (byte) Math.round(Math.max(0f, Math.min(1f, heights[i])) * 255f);
      rgba[i * 4] = v;
      rgba[i * 4 + 1] = v;
      rgba[i * 4 + 2] = v;
      rgba[i * 4 + 3] = (byte) 255;
    }
    return new ImageData(width, height, rgba);
  }

  /** Brightness (0..1) of every pixel of a greyscale (or colour, averaged) image. */
  public static float[] heights(ImageData image) {
    final byte[] p = image.rgba();
    final float[] h = new float[image.width() * image.height()];
    for (int i = 0; i < h.length; i++) {
      h[i] = ((p[i * 4] & 0xFF) + (p[i * 4 + 1] & 0xFF) + (p[i * 4 + 2] & 0xFF)) / (3f * 255f);
    }
    return h;
  }

  /**
   * True if the image looks like a height map (grey) rather than a tangent-space normal map (mostly light blue, with
   * blue the brightest channel). Wavefront MTL files use {@code bump} and {@code map_Bump} for both.
   */
  public static boolean looksLikeHeightMap(ImageData image) {
    final byte[] p = image.rgba();
    final int n = image.width() * image.height();
    final int step = Math.max(1, n / 4096); // a sample is plenty
    int grey = 0;
    int samples = 0;
    for (int i = 0; i < n; i += step) {
      final int r = p[i * 4] & 0xFF;
      final int g = p[i * 4 + 1] & 0xFF;
      final int b = p[i * 4 + 2] & 0xFF;
      if (Math.abs(r - g) < 12 && Math.abs(g - b) < 12) {
        grey++;
      }
      samples++;
    }
    return grey > samples * 0.9f;
  }

  private static byte encode(float v) {
    return (byte) Math.round((v * 0.5f + 0.5f) * 255f);
  }
}
