package engine.ui;

import engine.input.InputState;

/**
 * Text being edited: the text, the cursor and the selection anchor (the selection runs between them), as indices into
 * the string. {@link #apply} feeds it keys as {@link InputState} delivers them (characters plus the {@code KEY_*}
 * editing keys); the cursor always moves by whole code points. Copy, cut and paste go through a {@link Clipboard}
 * ({@link #setClipboard}; without one they do nothing). Pure logic, no drawing.
 */
public final class TextEdit {
  private String text;
  private int cursor;
  private int anchor;
  private final boolean multiLine;
  private Clipboard clipboard;

  public TextEdit(String text, boolean multiLine) {
    this.text = text;
    this.multiLine = multiLine;
    this.cursor = text.length();
    this.anchor = this.cursor;
  }

  public String text() {
    return this.text;
  }

  public int cursor() {
    return this.cursor;
  }

  public int selectionStart() {
    return Math.min(this.cursor, this.anchor);
  }

  public int selectionEnd() {
    return Math.max(this.cursor, this.anchor);
  }

  public boolean hasSelection() {
    return this.cursor != this.anchor;
  }

  /** Replaces the text from outside (the game changed it); keeps the cursor in range. */
  public void setText(String text) {
    if (!text.equals(this.text)) {
      this.text = text;
      this.cursor = Math.min(this.cursor, text.length());
      this.anchor = Math.min(this.anchor, text.length());
    }
  }

  /** Puts the cursor at {@code index}; {@code extend} keeps the anchor (Shift-click). */
  public void moveTo(int index, boolean extend) {
    this.cursor = Math.max(0, Math.min(this.text.length(), index));
    if (!extend) {
      this.anchor = this.cursor;
    }
  }

  public void selectAll() {
    this.anchor = 0;
    this.cursor = this.text.length();
  }

  public void setClipboard(Clipboard clipboard) {
    this.clipboard = clipboard;
  }

  public String selectedText() {
    return this.text.substring(selectionStart(), selectionEnd());
  }

  /** Puts the selection on the clipboard; false (and the clipboard untouched) if nothing is selected. */
  public boolean copy() {
    if (!hasSelection() || this.clipboard == null) {
      return false;
    }
    this.clipboard.setText(selectedText());
    return true;
  }

  public void cut() {
    if (copy()) {
      deleteSelection();
    }
  }

  /**
   * Replaces the selection with the clipboard's text. Line breaks become spaces in a single-line edit; tabs become
   * spaces and other control characters are dropped.
   */
  public void paste() {
    if (this.clipboard == null) {
      return;
    }
    final var pasted = clean(this.clipboard.getText(), this.multiLine);
    if (!pasted.isEmpty() || hasSelection()) {
      insert(pasted);
    }
  }

  static String clean(String s, boolean multiLine) {
    final var sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\r') {
        if (i + 1 < s.length() && s.charAt(i + 1) == '\n') {
          continue; // the \n follows
        }
        c = '\n';
      }
      if (c == '\n') {
        sb.append(multiLine ? '\n' : ' ');
      } else if (c == '\t') {
        sb.append(' ');
      } else if (!Character.isISOControl(c) && (c < '\uE000' || c > '\uF8FF')) {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Applies typed characters and editing keys. In a single-line edit Enter is not inserted; it (and Tab) is left to
   * the caller.
   */
  public void apply(String keys) {
    for (int i = 0; i < keys.length(); i++) {
      final char c = keys.charAt(i);
      final boolean shift = c >= InputState.KEY_LEFT + InputState.SHIFT
          && c <= InputState.KEY_END + InputState.SHIFT;
      final char key = shift ? (char) (c - InputState.SHIFT) : c;
      switch (key) {
      case '\b' -> {
        if (hasSelection()) {
          deleteSelection();
        } else if (this.cursor > 0) {
          final int from = this.text.offsetByCodePoints(this.cursor, -1);
          replace(from, this.cursor, "");
        }
      }
      case InputState.KEY_DELETE -> {
        if (hasSelection()) {
          deleteSelection();
        } else if (this.cursor < this.text.length()) {
          replace(this.cursor, this.text.offsetByCodePoints(this.cursor, 1), "");
        }
      }
      case InputState.KEY_LEFT -> move(!shift && hasSelection() ? selectionStart()
          : this.cursor > 0 ? this.text.offsetByCodePoints(this.cursor, -1) : 0, shift);
      case InputState.KEY_RIGHT -> move(!shift && hasSelection() ? selectionEnd()
          : this.cursor < this.text.length() ? this.text.offsetByCodePoints(this.cursor, 1) : this.text.length(),
          shift);
      case InputState.KEY_HOME -> move(lineStart(this.cursor), shift);
      case InputState.KEY_END -> move(lineEnd(this.cursor), shift);
      case InputState.KEY_UP -> move(verticalTarget(-1), shift);
      case InputState.KEY_DOWN -> move(verticalTarget(1), shift);
      case InputState.KEY_SELECT_ALL -> selectAll();
      case InputState.KEY_COPY -> copy();
      case InputState.KEY_CUT -> cut();
      case InputState.KEY_PASTE -> paste();
      case '\n' -> {
        if (this.multiLine) {
          insert("\n");
        }
      }
      case '\t', InputState.KEY_BACKTAB -> {
        // focus navigation, not text
      }
      default -> {
        if (!Character.isISOControl(c) && (c < '' || c > '')) {
          insert(String.valueOf(c));
        }
      }
      }
    }
  }

  private void move(int index, boolean extend) {
    moveTo(index, extend);
  }

  private void insert(String s) {
    replace(selectionStart(), selectionEnd(), s);
  }

  private void deleteSelection() {
    replace(selectionStart(), selectionEnd(), "");
  }

  private void replace(int from, int to, String s) {
    this.text = this.text.substring(0, from) + s + this.text.substring(to);
    this.cursor = from + s.length();
    this.anchor = this.cursor;
  }

  public int lineStart(int index) {
    return this.text.lastIndexOf('\n', index - 1) + 1;
  }

  public int lineEnd(int index) {
    final int end = this.text.indexOf('\n', index);
    return end < 0 ? this.text.length() : end;
  }

  /** The index one line up or down at the same column (in code points), clamped to that line. */
  private int verticalTarget(int direction) {
    final int start = lineStart(this.cursor);
    final int column = this.text.codePointCount(start, this.cursor);
    final int targetStart;
    if (direction < 0) {
      if (start == 0) {
        return 0;
      }
      targetStart = lineStart(start - 1);
    } else {
      final int end = lineEnd(this.cursor);
      if (end >= this.text.length()) {
        return this.text.length();
      }
      targetStart = end + 1;
    }
    final int targetEnd = lineEnd(targetStart);
    final int available = this.text.codePointCount(targetStart, targetEnd);
    return this.text.offsetByCodePoints(targetStart, Math.min(column, available));
  }
}
