package engine.render;

/**
 * A font for {@link UiCanvas}: a Java2D font family (a logical name such as {@code "SansSerif"}, {@code "Serif"},
 * {@code "Monospaced"}, or any installed family), a style and a pixel size. Glyphs are rasterized on first use, so any
 * character the font can display works, not only ASCII.
 */
public record UiFont(String family, int style, int size) {
  public static final int PLAIN = 0;
  public static final int BOLD = 1;
  public static final int ITALIC = 2;

  /** Proportional sans-serif, 16 px: the default for text without an explicit font. */
  public static final UiFont DEFAULT = new UiFont("SansSerif", PLAIN, 16);
  /** Fixed-width, for numbers that must not jitter (debug read-outs). */
  public static final UiFont MONOSPACED = new UiFont("Monospaced", BOLD, 15);

  public UiFont {
    if (size <= 0) {
      throw new IllegalArgumentException("size must be positive");
    }
  }
}
