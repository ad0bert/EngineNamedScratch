package engine.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import engine.input.InputState;

class TextEditTest {
  private static final String LEFT = String.valueOf(InputState.KEY_LEFT);
  private static final String RIGHT = String.valueOf(InputState.KEY_RIGHT);
  private static final String SHIFT_LEFT = String.valueOf((char) (InputState.KEY_LEFT + InputState.SHIFT));
  private static final String HOME = String.valueOf(InputState.KEY_HOME);
  private static final String END = String.valueOf(InputState.KEY_END);
  private static final String UP = String.valueOf(InputState.KEY_UP);
  private static final String DOWN = String.valueOf(InputState.KEY_DOWN);
  private static final String DELETE = String.valueOf(InputState.KEY_DELETE);

  @Test
  void typingInsertsAtTheCursor() {
    final var f = new TextEdit("wrd", false);
    f.apply(LEFT + LEFT + "o");
    assertEquals("word", f.text());
    assertEquals(2, f.cursor());
  }

  @Test
  void backspaceAndDeleteWorkOnBothSidesAndOnWholeCodePoints() {
    final var e = new TextEdit("a😀b", false);
    e.apply(LEFT + "\b");
    assertEquals("ab", e.text(), "the emoji (two chars) goes in one go");
    e.apply(HOME + DELETE);
    assertEquals("b", e.text());
  }

  @Test
  void shiftSelectsAndTypingReplacesTheSelection() {
    final var e = new TextEdit("hello world", false);
    e.apply(SHIFT_LEFT.repeat(5));
    assertTrue(e.hasSelection());
    assertEquals(6, e.selectionStart());
    e.apply("there");
    assertEquals("hello there", e.text());
    assertFalse(e.hasSelection());
    e.apply(String.valueOf(InputState.KEY_SELECT_ALL) + "\b");
    assertEquals("", e.text());
  }

  @Test
  void anArrowCollapsesASelectionToItsEdge() {
    final var e = new TextEdit("abcdef", false);
    e.apply(SHIFT_LEFT.repeat(3));
    e.apply(LEFT);
    assertEquals(3, e.cursor());
    assertFalse(e.hasSelection());
    e.apply(SHIFT_LEFT + RIGHT);
    assertEquals(3, e.cursor());
  }

  @Test
  void multiLineTextMovesBetweenLinesAtTheSameColumn() {
    final var e = new TextEdit("first line\nab\nthird line", true);
    e.moveTo(5, false); // after "first"
    e.apply(DOWN);
    assertEquals(13, e.cursor(), "the short line: clamped to its end");
    e.apply(DOWN);
    assertEquals(16, e.cursor(), "column 2 of the third line");
    e.apply(UP + UP);
    assertEquals(2, e.cursor(), "back to column 2 of the first line");
    e.apply(END + "!\n+");
    assertEquals("first line!\n+\nab\nthird line", e.text());
    e.apply(HOME);
    assertEquals(12, e.cursor());

    final var single = new TextEdit("x", false);
    single.apply("\n");
    assertEquals("x", single.text(), "Enter is not text in a single-line edit");
  }

  @Test
  void copyCutAndPasteGoThroughTheClipboard() {
    final var clipboard = Clipboard.inMemory();
    final var e = new TextEdit("hello world", false);
    e.setClipboard(clipboard);
    e.apply(String.valueOf(InputState.KEY_COPY));
    assertEquals("", clipboard.getText(), "nothing selected: nothing copied");
    e.apply(SHIFT_LEFT.repeat(5) + InputState.KEY_CUT);
    assertEquals("hello ", e.text());
    assertEquals("world", clipboard.getText());
    e.apply(HOME + InputState.KEY_PASTE + " ");
    assertEquals("world hello ", e.text());
    assertEquals(6, e.cursor());
    e.apply(String.valueOf(InputState.KEY_SELECT_ALL) + InputState.KEY_PASTE);
    assertEquals("world", e.text(), "pasting replaces the selection");
  }

  @Test
  void pastedTextIsCleanedForTheKindOfEdit() {
    assertEquals("a b c", TextEdit.clean("a\r\nb\nc", false));
    assertEquals("a\nb\nc", TextEdit.clean("a\r\nb\rc", true));
    assertEquals("a b", TextEdit.clean("a\tb\u0007", true));
    final var e = new TextEdit("x", true);
    e.apply(String.valueOf(InputState.KEY_PASTE));
    assertEquals("x", e.text(), "no clipboard: nothing happens");
  }

  @Test
  void wrappingBreaksAtSpacesKeepsLineBreaksAndSplitsLongWords() {
    final TextWrap.Measure eight = s -> s.codePointCount(0, s.length()) * 8f;
    assertEquals(java.util.List.of("one two", "three"), TextWrap.wrap("one two three", 60f, eight));
    assertEquals(java.util.List.of("a", "", "b c"), TextWrap.wrap("a\n\nb c", 60f, eight));
    assertEquals(java.util.List.of("abcdefg", "hij k"), TextWrap.wrap("abcdefghij k", 60f, eight));
    assertEquals(java.util.List.of(""), TextWrap.wrap("", 60f, eight));
  }
}
