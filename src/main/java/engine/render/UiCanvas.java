package engine.render;

/** Immediate-mode 2D drawing in window pixels; the origin is the top-left corner. */
public interface UiCanvas {
  void drawRect(float x, float y, float w, float h, float r, float g, float b, float a);

  /** Draws (possibly multi-line) text with its top-left corner at x, y. */
  void drawText(String text, float x, float y, float scale, float r, float g, float b, float a);

  float textWidth(String text, float scale);

  float lineHeight(float scale);
}
