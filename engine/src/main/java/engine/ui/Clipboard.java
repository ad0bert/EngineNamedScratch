package engine.ui;

/**
 * Plain-text clipboard used by text editing (Ctrl+C, Ctrl+X, Ctrl+V). {@link #system()} is the desktop's clipboard
 * through Java's AWT toolkit where there is one, and a clipboard private to the program when running headless or
 * when the system clipboard cannot be reached; another platform layer can supply its own implementation.
 */
public interface Clipboard {
  /** The clipboard's text, or an empty string when it holds none. */
  String getText();

  void setText(String text);

  /** A clipboard that lives in this program only (headless runs, tests). */
  static Clipboard inMemory() {
    return new Clipboard() {
      private volatile String text = "";

      @Override
      public String getText() {
        return this.text;
      }

      @Override
      public void setText(String text) {
        this.text = text == null ? "" : text;
      }
    };
  }

  /** The desktop clipboard, or an in-memory one where there is none (made on first use, then shared). */
  static Clipboard system() {
    return SystemClipboard.INSTANCE;
  }
}
