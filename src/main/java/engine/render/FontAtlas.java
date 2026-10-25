package engine.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import lombok.Getter;

/**
 * Monospaced glyph atlas for ASCII 32..126 rendered with Java2D (works headless). The last cell is solid white and is
 * used to draw rectangles with the same shader and texture.
 */
public final class FontAtlas {
  public static final int FIRST = 32;
  public static final int LAST = 126;
  private static final int COLS = 16;
  private static final int ROWS = 6;
  private static final int PAD = 1;

  @Getter private final ImageData image;
  private final int cellW;
  private final int cellH;
  /** Horizontal distance between two characters, in pixels at scale 1. */
  @Getter private final int advance;

  public FontAtlas(int pixelSize) {
    System.setProperty("java.awt.headless", "true");
    final var font = new Font(Font.MONOSPACED, Font.BOLD, pixelSize);
    final var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    final var pg = probe.createGraphics();
    final var fm = pg.getFontMetrics(font);
    this.advance = Math.max(1, fm.charWidth('M'));
    this.cellW = this.advance + 2 * PAD;
    this.cellH = fm.getHeight() + 2 * PAD;
    final int ascent = fm.getAscent();
    pg.dispose();

    final var img = new BufferedImage(COLS * this.cellW, ROWS * this.cellH, BufferedImage.TYPE_INT_ARGB);
    final var g = img.createGraphics();
    g.setFont(font);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(Color.WHITE);
    for (int c = FIRST; c <= LAST; c++) {
      final int i = c - FIRST;
      g.drawString(String.valueOf((char) c), (i % COLS) * this.cellW + PAD, (i / COLS) * this.cellH + PAD + ascent);
    }
    final int solid = solidIndex();
    g.fillRect((solid % COLS) * this.cellW, (solid / COLS) * this.cellH, this.cellW, this.cellH);
    g.dispose();
    this.image = ImageData.fromBufferedImage(img);
  }

  private static int solidIndex() {
    return COLS * ROWS - 1;
  }

  public int lineHeight() {
    return this.cellH - 2 * PAD;
  }

  public int cellPad() {
    return PAD;
  }

  public int cellWidth() {
    return this.cellW;
  }

  public int cellHeight() {
    return this.cellH;
  }

  /** uv rectangle of a glyph cell as {u0, vTop, u1, vBottom}; unknown characters map to '?'. */
  public void glyphUv(char c, float[] out) {
    final int i = (c < FIRST || c > LAST ? '?' : c) - FIRST;
    cellUv(i, out, false);
  }

  /** uv rectangle collapsed to the middle of the solid cell, so any stretch of it is plain white. */
  public void solidUv(float[] out) {
    cellUv(solidIndex(), out, true);
  }

  private void cellUv(int index, float[] out, boolean centerOnly) {
    final float w = COLS * this.cellW;
    final float h = ROWS * this.cellH;
    final float x0 = (index % COLS) * this.cellW;
    final float y0 = (index / COLS) * this.cellH;
    if (centerOnly) {
      out[0] = out[2] = (x0 + this.cellW / 2f) / w;
      out[1] = out[3] = 1f - (y0 + this.cellH / 2f) / h;
    } else {
      out[0] = x0 / w;
      out[2] = (x0 + this.cellW) / w;
      out[1] = 1f - y0 / h; // image rows are stored bottom-to-top
      out[3] = 1f - (y0 + this.cellH) / h;
    }
  }
}
