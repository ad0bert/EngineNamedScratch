package engine.render;

/**
 * Immediate-mode 2D drawing in window pixels; the origin is the top-left corner. Text is drawn with
 * {@link UiFont#DEFAULT} unless a font is given; any number of fonts may be used in one frame, and everything is drawn
 * in call order. Text drawn at a scale is rasterized at that size, so it stays sharp. Clip rectangles nest: drawing
 * is limited to the intersection of all pushed ones.
 */
public interface UiCanvas {
  /** Limits drawing to a rectangle (within the current clip) until the matching {@link #popClip()}. */
  default void pushClip(float x, float y, float w, float h) {
  }

  default void popClip() {
  }

  void drawRect(float x, float y, float w, float h, float r, float g, float b, float a);

  /** Draws (possibly multi-line) text with its top-left corner at x, y. */
  void drawText(UiFont font, String text, float x, float y, float scale, float r, float g, float b, float a);

  float textWidth(UiFont font, String text, float scale);

  /** Distance between two lines of text. */
  float lineHeight(UiFont font, float scale);

  default void drawText(String text, float x, float y, float scale, float r, float g, float b, float a) {
    drawText(UiFont.DEFAULT, text, x, y, scale, r, g, b, a);
  }

  default float textWidth(String text, float scale) {
    return textWidth(UiFont.DEFAULT, text, scale);
  }

  default float lineHeight(float scale) {
    return lineHeight(UiFont.DEFAULT, scale);
  }
}
