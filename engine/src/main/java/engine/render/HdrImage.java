package engine.render;

/**
 * A high dynamic range image in linear light: three floats (red, green, blue) per pixel, rows from the top. Used for
 * environment maps ({@link Sky#image}); see {@code engine.resources.HdrLoader} for Radiance {@code .hdr} files.
 */
public record HdrImage(int width, int height, float[] rgb) {
  public HdrImage {
    if (width <= 0 || height <= 0 || rgb.length != width * height * 3) {
      throw new IllegalArgumentException("an HDR image needs width * height * 3 floats");
    }
  }

  /** Red, green and blue of a pixel into {@code dest[0..2]}. */
  public void pixel(int x, int y, float[] dest) {
    final int i = (y * this.width + x) * 3;
    dest[0] = this.rgb[i];
    dest[1] = this.rgb[i + 1];
    dest[2] = this.rgb[i + 2];
  }
}
