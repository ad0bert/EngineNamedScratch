package engine.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

/**
 * Glyph atlas for one {@link UiFont}, rendered with Java2D (works headless). Glyphs are rasterized on first use and
 * packed into rows; the atlas grows when it is full. A small solid white block lets rectangles be drawn with the same
 * texture. Backends re-upload the image whenever {@link #version()} changes.
 *
 * <p>
 * Characters the font cannot display are drawn as the font's replacement glyph ({@code '?'} if it has none).
 */
public final class FontAtlas {
  /** Extra pixels around every glyph so filtering does not pick up the neighbours. */
  private static final int PAD = 2;
  private static final int SOLID = 4;
  private static final int MAX_SIZE = 4096;

  /** Where a glyph is in the atlas (pixels, top-left origin) and how far it advances the pen. */
  public record Glyph(int x, int y, int width, int height, int advance) {
  }

  private final Font font;
  private final FontMetrics metrics;
  private final Map<Integer, Glyph> glyphs = new HashMap<>();
  private BufferedImage image;
  private Graphics2D graphics;
  private int penX;
  private int penY;
  private int rowHeight;
  /** Rows start here: 0, or the old width once the atlas has grown sideways. */
  private int regionLeft;
  /** Increases whenever pixels were added (or the atlas grew). */
  @Getter private int version;
  private ImageData cachedImage;
  private int cachedVersion = -1;

  public FontAtlas(UiFont font) {
    System.setProperty("java.awt.headless", "true");
    this.font = new Font(font.family(), font.style(), font.size());
    final var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
    probe.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    probe.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    this.metrics = probe.getFontMetrics(this.font);
    probe.dispose();
    this.image = new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB);
    this.graphics = createGraphics(this.image);
    this.graphics.fillRect(0, 0, SOLID, SOLID);
    this.penX = SOLID + PAD;
    this.rowHeight = SOLID;
    for (int c = 32; c < 127; c++) { // the common case needs no uploads later
      glyph(c);
    }
  }

  /** Convenience for the old fixed-size monospaced atlas. */
  public FontAtlas(int pixelSize) {
    this(new UiFont("Monospaced", UiFont.BOLD, pixelSize));
  }

  private Graphics2D createGraphics(BufferedImage img) {
    final var g = img.createGraphics();
    g.setFont(this.font);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    g.setColor(Color.WHITE);
    return g;
  }

  /** Distance between two baselines, in pixels at scale 1. */
  public int lineHeight() {
    return this.metrics.getHeight();
  }

  public int ascent() {
    return this.metrics.getAscent();
  }

  public int padding() {
    return PAD;
  }

  public int width() {
    return this.image.getWidth();
  }

  public int height() {
    return this.image.getHeight();
  }

  /** Horizontal advance of a code point, in pixels at scale 1. */
  public int advance(int codePoint) {
    return glyph(codePoint).advance();
  }

  /** The glyph of a code point, rasterizing it into the atlas on first use. */
  public Glyph glyph(int codePoint) {
    final var known = this.glyphs.get(codePoint);
    if (known != null) {
      return known;
    }
    final int cp = this.font.canDisplay(codePoint) || codePoint == ' ' ? codePoint : fallback();
    if (cp != codePoint) {
      final var replacement = glyph(cp);
      this.glyphs.put(codePoint, replacement);
      return replacement;
    }
    final var text = new String(Character.toChars(cp));
    final int advance = this.metrics.stringWidth(text);
    // italic and some glyphs reach beyond their advance; give them room
    final int w = advance + this.metrics.getHeight() / 3 + 2 * PAD;
    final int h = this.metrics.getHeight() + 2 * PAD;
    if (this.penX + w > this.image.getWidth()) {
      this.penX = this.regionLeft;
      this.penY += this.rowHeight + PAD;
      this.rowHeight = 0;
    }
    while (this.penY + h > this.image.getHeight()) {
      grow();
    }
    this.graphics.drawString(text, this.penX + PAD, this.penY + PAD + this.metrics.getAscent());
    final var g = new Glyph(this.penX, this.penY, w, h, advance);
    this.glyphs.put(cp, g);
    this.penX += w + PAD;
    this.rowHeight = Math.max(this.rowHeight, h);
    this.version++;
    return g;
  }

  private int fallback() {
    final int replacement = 0xFFFD;
    return this.font.canDisplay(replacement) ? replacement : '?';
  }

  /**
   * Doubles the height (rows continue below) up to the maximum, then the width (a new column of rows starts at the
   * top, right of everything so far). Glyph positions stay valid; only the uv scale changes.
   */
  private void grow() {
    final int w = this.image.getWidth();
    final int h = this.image.getHeight();
    final boolean taller = h < MAX_SIZE;
    if (!taller && w >= MAX_SIZE) {
      throw new IllegalStateException("Font atlas is full");
    }
    final var bigger = new BufferedImage(taller ? w : w * 2, taller ? h * 2 : h, BufferedImage.TYPE_INT_ARGB);
    final var g = createGraphics(bigger);
    g.drawImage(this.image, 0, 0, null);
    this.graphics.dispose();
    this.image = bigger;
    this.graphics = g;
    if (!taller) {
      this.regionLeft = w;
      this.penX = w;
      this.penY = 0;
      this.rowHeight = 0;
    }
    this.version++;
  }

  /** The atlas pixels (bottom row first, like every {@link ImageData}); converted again only after changes. */
  public ImageData image() {
    if (this.cachedVersion != this.version) {
      this.cachedImage = ImageData.fromBufferedImage(this.image);
      this.cachedVersion = this.version;
    }
    return this.cachedImage;
  }

  /** uv rectangle of a glyph as {u0, vTop, u1, vBottom}. */
  public void glyphUv(Glyph g, float[] out) {
    final float w = this.image.getWidth();
    final float h = this.image.getHeight();
    out[0] = g.x() / w;
    out[2] = (g.x() + g.width()) / w;
    out[1] = 1f - g.y() / h; // image rows are stored bottom-to-top
    out[3] = 1f - (g.y() + g.height()) / h;
  }

  /** uv rectangle collapsed to the middle of the solid block, so any stretch of it is plain white. */
  public void solidUv(float[] out) {
    out[0] = out[2] = SOLID / 2f / this.image.getWidth();
    out[1] = out[3] = 1f - SOLID / 2f / this.image.getHeight();
  }
}
