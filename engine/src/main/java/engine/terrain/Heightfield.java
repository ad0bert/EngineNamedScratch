package engine.terrain;

import org.joml.Vector3f;

import engine.render.ImageData;

/**
 * A grid of heights: {@code columns} samples along X and {@code rows} along Z, {@code spacing} metres apart, the first
 * at the origin. {@link #heightAt} interpolates between them (the two triangles of each cell, as the mesh and the
 * collider of a {@link Terrain} have them). Made from a function, an image, or the built-in fractal noise.
 */
public final class Heightfield {
  /** Height in metres at a point of the plane (local metres from the first sample). */
  @FunctionalInterface
  public interface HeightFunction {
    float height(float x, float z);
  }

  private final int columns;
  private final int rows;
  private final float spacing;
  private final float[] heights;

  /** @param heights {@code columns * rows} heights, row by row (row index along Z) */
  public Heightfield(int columns, int rows, float spacing, float[] heights) {
    if (columns < 2 || rows < 2 || spacing <= 0f || heights.length != columns * rows) {
      throw new IllegalArgumentException("a heightfield needs at least 2 x 2 samples and one height per sample");
    }
    this.columns = columns;
    this.rows = rows;
    this.spacing = spacing;
    this.heights = heights.clone();
  }

  /** Samples {@code f} at every grid point. */
  public static Heightfield generate(int columns, int rows, float spacing, HeightFunction f) {
    final float[] h = new float[columns * rows];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < columns; c++) {
        h[r * columns + c] = f.height(c * spacing, r * spacing);
      }
    }
    return new Heightfield(columns, rows, spacing, h);
  }

  /**
   * One sample per pixel (rows from the top of the image along +Z), the brightness mapped from 0..1 to
   * {@code minHeight..maxHeight}.
   */
  public static Heightfield fromImage(ImageData image, float spacing, float minHeight, float maxHeight) {
    final int w = image.width();
    final int h = image.height();
    final float[] out = new float[w * h];
    final byte[] p = image.rgba();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        final int o = (y * w + x) * 4;
        final float v = ((p[o] & 0xFF) + (p[o + 1] & 0xFF) + (p[o + 2] & 0xFF)) / (3f * 255f);
        out[y * w + x] = minHeight + (maxHeight - minHeight) * v;
      }
    }
    return new Heightfield(w, h, spacing, out);
  }

  /**
   * Fractal value noise in -1..1 (roughly): {@code octaves} layers, each twice the frequency and half the amplitude
   * of the last, the first with features about {@code 1 / frequency} metres wide. Smooth, tileable by nothing,
   * deterministic per seed.
   */
  public static float fbm(float x, float z, float frequency, int octaves, long seed) {
    float sum = 0f;
    float amplitude = 1f;
    float norm = 0f;
    float f = frequency;
    for (int i = 0; i < octaves; i++) {
      sum += amplitude * valueNoise(x * f, z * f, seed + i * 7919L);
      norm += amplitude;
      amplitude *= 0.5f;
      f *= 2f;
    }
    return sum / norm;
  }

  /** Smoothly interpolated random values on the integer lattice, in -1..1. */
  static float valueNoise(float x, float z, long seed) {
    final int x0 = (int) Math.floor(x);
    final int z0 = (int) Math.floor(z);
    final float tx = smooth(x - x0);
    final float tz = smooth(z - z0);
    final float a = lattice(x0, z0, seed);
    final float b = lattice(x0 + 1, z0, seed);
    final float c = lattice(x0, z0 + 1, seed);
    final float d = lattice(x0 + 1, z0 + 1, seed);
    return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
  }

  private static float smooth(float t) {
    return t * t * t * (t * (t * 6f - 15f) + 10f);
  }

  private static float lerp(float a, float b, float t) {
    return a + (b - a) * t;
  }

  private static float lattice(int x, int z, long seed) {
    long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
    h ^= h >>> 33;
    h *= 0xFF51AFD7ED558CCDL;
    h ^= h >>> 33;
    return (h >>> 40) / (float) (1L << 23) - 1f;
  }

  public int columns() {
    return this.columns;
  }

  public int rows() {
    return this.rows;
  }

  public float spacing() {
    return this.spacing;
  }

  /** Extent along X in metres. */
  public float width() {
    return (this.columns - 1) * this.spacing;
  }

  /** Extent along Z in metres. */
  public float depth() {
    return (this.rows - 1) * this.spacing;
  }

  /** The height of sample (column, row), clamped to the grid. */
  public float sample(int column, int row) {
    final int c = Math.max(0, Math.min(this.columns - 1, column));
    final int r = Math.max(0, Math.min(this.rows - 1, row));
    return this.heights[r * this.columns + c];
  }

  /**
   * The surface height at local (x, z), on the cell's triangles (split from (c, r) to (c + 1, r + 1)); clamped to the
   * grid's edge outside.
   */
  public float heightAt(float x, float z) {
    final float gx = Math.max(0f, Math.min(this.columns - 1, x / this.spacing));
    final float gz = Math.max(0f, Math.min(this.rows - 1, z / this.spacing));
    final int c = Math.min(this.columns - 2, (int) gx);
    final int r = Math.min(this.rows - 2, (int) gz);
    final float fx = gx - c;
    final float fz = gz - r;
    final float h00 = sample(c, r);
    final float h11 = sample(c + 1, r + 1);
    if (fx >= fz) { // triangle (c, r), (c + 1, r), (c + 1, r + 1)
      return h00 + (sample(c + 1, r) - h00) * fx + (h11 - sample(c + 1, r)) * fz;
    }
    return h00 + (sample(c, r + 1) - h00) * fz + (h11 - sample(c, r + 1)) * fx;
  }

  /** The smooth normal at sample (column, row), from the heights around it. */
  public Vector3f normal(int column, int row, Vector3f dest) {
    final float dx = sample(column + 1, row) - sample(column - 1, row);
    final float dz = sample(column, row + 1) - sample(column, row - 1);
    final float sx = (Math.min(this.columns - 1, column + 1) - Math.max(0, column - 1)) * this.spacing;
    final float sz = (Math.min(this.rows - 1, row + 1) - Math.max(0, row - 1)) * this.spacing;
    return dest.set(-dx / sx, 1f, -dz / sz).normalize();
  }
}
