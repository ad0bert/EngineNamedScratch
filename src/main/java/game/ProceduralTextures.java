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

  private static float smooth(float t) {
    return t * t * (3f - 2f * t);
  }

  private static int clamp255(float v) {
    return Math.max(0, Math.min(255, Math.round(v)));
  }
}
