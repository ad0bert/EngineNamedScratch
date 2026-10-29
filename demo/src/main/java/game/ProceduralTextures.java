package game;

import java.util.Random;

import engine.render.ImageData;
import engine.resources.NormalMaps;

/** Generates the demo's textures in code, so the repository needs no extra art assets. All results tile seamlessly. */
final class ProceduralTextures {
  private ProceduralTextures() {
  }

  /**
   * A brick albedo texture with matching normal, height and specular maps (4 bricks per row, 8 rows per tile): the
   * mortar is recessed and dull, the bricks slightly glossy.
   */
  record Surface(ImageData albedo, ImageData normal, ImageData height, ImageData specular) {
  }

  static Surface bricks(int size) {
    final int brickW = size / 4;
    final int brickH = size / 8;
    final var rnd = new Random(11);
    final float[] tone = new float[8 * 4];
    for (int i = 0; i < tone.length; i++) {
      tone[i] = 0.75f + rnd.nextFloat() * 0.5f; // every brick has its own shade
    }
    final float[] heights = new float[size * size];
    final byte[] rgba = new byte[size * size * 4];
    final byte[] gloss = new byte[size * size * 4];
    for (int y = 0; y < size; y++) {
      final int row = y / brickH;
      final int shift = (row & 1) == 1 ? brickW / 2 : 0; // running bond
      for (int x = 0; x < size; x++) {
        final int bx = (x + shift) % size;
        final int xs = bx % brickW;
        final int ys = y % brickH;
        final int edge = Math.min(Math.min(xs, brickW - 1 - xs), Math.min(ys, brickH - 1 - ys));
        final float h = Math.max(0f, Math.min(1f, (edge - 2) / 5f)); // 0 in the mortar joint, bevelled edge
        final float grain = (rnd.nextFloat() - 0.5f) * 0.12f;
        heights[y * size + x] = h * (0.85f + grain);
        final float shade = tone[row % 8 * 4 + bx / brickW] + grain;
        final int o = (y * size + x) * 4;
        if (h <= 0f) { // mortar
          rgba[o] = (byte) 150;
          rgba[o + 1] = (byte) 148;
          rgba[o + 2] = (byte) 140;
        } else {
          rgba[o] = (byte) clamp255(168 * shade);
          rgba[o + 1] = (byte) clamp255(72 * shade);
          rgba[o + 2] = (byte) clamp255(52 * shade);
        }
        rgba[o + 3] = (byte) 255;
        final byte g = (byte) (h <= 0f ? 20 : clamp255(200 * (1f - grain * 3f)));
        gloss[o] = g;
        gloss[o + 1] = g;
        gloss[o + 2] = g;
        gloss[o + 3] = (byte) 255;
      }
    }
    return new Surface(new ImageData(size, size, rgba), NormalMaps.fromHeights(heights, size, size, 7f),
        NormalMaps.heightImage(heights, size, size), new ImageData(size, size, gloss));
  }

  /** A metal grate: square holes (alpha 0) in a grey frame, for a cut-out material. */
  static ImageData grate(int size, int holes) {
    final byte[] rgba = new byte[size * size * 4];
    final int cell = size / holes;
    final int bar = Math.max(2, cell / 5);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final boolean solid = x % cell < bar || y % cell < bar;
        final int o = (y * size + x) * 4;
        rgba[o] = (byte) 110;
        rgba[o + 1] = (byte) 115;
        rgba[o + 2] = (byte) 125;
        rgba[o + 3] = (byte) (solid ? 255 : 0);
      }
    }
    return new ImageData(size, size, rgba);
  }

  /** A soft bumpy normal map (three octaves of value noise) for ground-like surfaces. */
  static ImageData bumps(int size, float strength, long seed) {
    final float[] heights = new float[size * size];
    final var rnd = new Random(seed);
    float amplitude = 1f;
    float total = 0f;
    for (int cells = 8; cells <= 32; cells *= 2) {
      final float[] grid = new float[cells * cells];
      for (int i = 0; i < grid.length; i++) {
        grid[i] = rnd.nextFloat();
      }
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          final float fx = (float) x * cells / size;
          final float fy = (float) y * cells / size;
          final int x0 = (int) fx;
          final int y0 = (int) fy;
          final float tx = smooth(fx - x0);
          final float ty = smooth(fy - y0);
          final float a = grid[y0 % cells * cells + x0 % cells];
          final float b = grid[y0 % cells * cells + (x0 + 1) % cells];
          final float c = grid[(y0 + 1) % cells * cells + x0 % cells];
          final float d = grid[(y0 + 1) % cells * cells + (x0 + 1) % cells];
          heights[y * size + x] += amplitude * ((a + (b - a) * tx) * (1f - ty) + (c + (d - c) * tx) * ty);
        }
      }
      total += amplitude;
      amplitude *= 0.5f;
    }
    for (int i = 0; i < heights.length; i++) {
      heights[i] /= total;
    }
    return NormalMaps.fromHeights(heights, size, size, strength);
  }

  /**
   * A 2 x 2 atlas of white smoke puffs: soft round shapes with a cloudy edge (value noise), alpha as coverage. Unlike
   * the other textures it does not tile: every frame fades out towards its edges.
   */
  static ImageData puffs(int size, long seed) {
    final int half = size / 2;
    final byte[] rgba = new byte[size * size * 4];
    final var rnd = new Random(seed);
    final int cells = 6;
    for (int frame = 0; frame < 4; frame++) {
      final float[] grid = new float[cells * cells];
      for (int i = 0; i < grid.length; i++) {
        grid[i] = rnd.nextFloat();
      }
      final int ox = (frame % 2) * half;
      final int oy = (frame / 2) * half;
      for (int y = 0; y < half; y++) {
        for (int x = 0; x < half; x++) {
          final float u = (x + 0.5f) / half * 2f - 1f;
          final float v = (y + 0.5f) / half * 2f - 1f;
          final float fx = (x + 0.5f) / half * (cells - 1);
          final float fy = (y + 0.5f) / half * (cells - 1);
          final int x0 = Math.min(cells - 2, (int) fx);
          final int y0 = Math.min(cells - 2, (int) fy);
          final float tx = smooth(fx - x0);
          final float ty = smooth(fy - y0);
          final float a = grid[y0 * cells + x0];
          final float b = grid[y0 * cells + x0 + 1];
          final float c = grid[(y0 + 1) * cells + x0];
          final float d = grid[(y0 + 1) * cells + x0 + 1];
          final float noise = (a + (b - a) * tx) * (1f - ty) + (c + (d - c) * tx) * ty;
          // the radius the puff reaches wobbles with the noise; inside it the density falls off smoothly
          final float r = (float) Math.sqrt(u * u + v * v) / (0.65f + 0.3f * noise);
          final float density = r >= 1f ? 0f : smooth(1f - r) * (0.75f + 0.25f * noise);
          final int o = ((oy + y) * size + ox + x) * 4;
          final byte shade = (byte) clamp255(225 + 30 * noise);
          rgba[o] = shade;
          rgba[o + 1] = shade;
          rgba[o + 2] = shade;
          rgba[o + 3] = (byte) clamp255(255 * density);
        }
      }
    }
    return new ImageData(size, size, rgba);
  }

  private static float smooth(float t) {
    return t * t * (3f - 2f * t);
  }

  private static int clamp255(float v) {
    return Math.max(0, Math.min(255, Math.round(v)));
  }
}
