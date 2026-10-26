package engine.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import engine.input.InputState;
import engine.render.UiCanvas;
import engine.render.UiFont;

class GuiTest {
  /** Every character is 8 px wide, lines are 16 px; records what was drawn. */
  static final class FakeCanvas implements UiCanvas {
    final List<float[]> rects = new ArrayList<>();
    final List<String> texts = new ArrayList<>();
    int clips;

    @Override
    public void pushClip(float x, float y, float w, float h) {
      this.clips++;
    }

    @Override
    public void drawRect(float x, float y, float w, float h, float r, float g, float b, float a) {
      this.rects.add(new float[] { x, y, w, h });
    }

    @Override
    public void drawText(UiFont font, String text, float x, float y, float scale, float r, float g, float b,
        float a) {
      this.texts.add(text);
    }

    @Override
    public float textWidth(UiFont font, String text, float scale) {
      return text.codePointCount(0, text.length()) * 8f * scale;
    }

    @Override
    public float lineHeight(UiFont font, float scale) {
      return 16f * scale;
    }
  }

  private final InputState input = new InputState();
  private final Gui gui = new Gui(this.input);
  private FakeCanvas canvas = new FakeCanvas();

  // the panel is at 10,10, 200 wide; title bar 28 high (16 + 2 * 6), first widget at y = 44, widgets are 28 high
  private static final float FIRST_Y = 10f + 28f + 6f;
  private static final float ROW = 28f + 4f;

  private void frame(Runnable widgets) {
    this.canvas = new FakeCanvas();
    this.gui.begin(this.canvas, 800, 600, true);
    this.gui.beginPanel("Panel", 10f, 10f, 200f);
    widgets.run();
    this.gui.endPanel();
    this.gui.end();
  }

  private void pointAt(float x, float y) {
    this.input.mouseMovedTo((int) x, (int) y);
  }

  @Test
  void aButtonClicksOnReleaseOverIt() {
    final boolean[] clicked = new boolean[1];
    final Runnable ui = () -> clicked[0] = this.gui.button("Go");
    pointAt(50f, FIRST_Y + 10f);
    frame(ui);
    assertFalse(clicked[0]);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    frame(ui);
    assertFalse(clicked[0], "not on press");
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    assertTrue(clicked[0], "on release");
    frame(ui);
    assertFalse(clicked[0], "only once");
  }

  @Test
  void aClickFasterThanAFrameStillCounts() {
    final boolean[] clicked = new boolean[1];
    pointAt(50f, FIRST_Y + 10f);
    frame(() -> clicked[0] = this.gui.button("Go"));
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(() -> clicked[0] = this.gui.button("Go"));
    assertTrue(clicked[0]);
  }

  @Test
  void pressingElsewhereAndReleasingOverAButtonIsNoClick() {
    final boolean[] clicked = new boolean[1];
    final Runnable ui = () -> clicked[0] = this.gui.button("Go");
    pointAt(500f, 500f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    frame(ui);
    pointAt(50f, FIRST_Y + 10f);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    assertFalse(clicked[0]);
  }

  @Test
  void checkboxesToggleAndSlidersFollowTheDrag() {
    final boolean[] box = { false };
    final float[] value = { 0f };
    final Runnable ui = () -> {
      box[0] = this.gui.checkbox("Box", box[0]);
      value[0] = this.gui.slider("Value", value[0], 0f, 10f);
    };
    pointAt(20f, FIRST_Y + 10f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    assertTrue(box[0]);

    // the slider spans x 16..204 in the second row; drag from its left quarter to its right end and beyond
    pointAt(16f + 188f * 0.25f, FIRST_Y + ROW + 10f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    frame(ui);
    assertEquals(2.5f, value[0], 0.05f);
    pointAt(400f, 300f); // dragging may leave the widget
    frame(ui);
    assertEquals(10f, value[0], 0f);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    pointAt(100f, FIRST_Y + ROW + 10f); // hovering without a press changes nothing
    frame(ui);
    assertEquals(10f, value[0], 0f);
    assertTrue(box[0]);
  }

  @Test
  void textFieldsEditWhileFocused() {
    final String[] text = { "ab" };
    final Runnable ui = () -> text[0] = this.gui.textField("Name", text[0]);
    this.input.typed('x'); // typed before the field has the focus: ignored
    pointAt(100f, FIRST_Y + 10f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    assertTrue(this.gui.wantsKeyboard());
    this.input.consumeTypedText();
    for (char c : "cdé\b\bz".toCharArray()) {
      this.input.typed(c);
    }
    frame(ui);
    assertEquals("abcz", text[0]);
    this.input.typed('!');
    this.input.typed('\n');
    frame(ui);
    assertEquals("abcz!", text[0]);
    assertFalse(this.gui.wantsKeyboard(), "Enter ends editing");
    this.input.typed('?');
    frame(ui);
    assertEquals("abcz!", text[0]);
  }

  @Test
  void clickingElsewhereEndsEditing() {
    final String[] text = { "" };
    final Runnable ui = () -> text[0] = this.gui.textField("Name", text[0]);
    pointAt(100f, FIRST_Y + 10f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    assertTrue(this.gui.wantsKeyboard());
    pointAt(600f, 500f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    assertFalse(this.gui.wantsKeyboard());
  }

  @Test
  void rowsShareTheWidthAndThenLayoutContinuesBelow() {
    frame(() -> {
      this.gui.row(2);
      this.gui.button("A");
      this.gui.button("B");
      this.gui.button("C");
    });
    // panel background, title bar, then one rect per button
    final var a = this.canvas.rects.get(2);
    final var b = this.canvas.rects.get(3);
    final var c = this.canvas.rects.get(4);
    assertEquals(a[1], b[1], 0f, "side by side");
    assertEquals(a[2], b[2], 0f, "equal widths");
    assertEquals(188f, a[2] + b[2] + 4f, 1e-4f, "together as wide as the panel's inside");
    assertEquals(188f, c[2], 1e-4f, "the next widget has the whole width again");
    assertEquals(a[1] + ROW, c[1], 1e-4f);
  }

  @Test
  void thePanelBackgroundFitsItsContentFromTheSecondFrameOn() {
    final Runnable ui = () -> {
      this.gui.button("A");
      this.gui.button("B");
    };
    frame(ui);
    frame(ui);
    final var background = this.canvas.rects.get(0);
    assertEquals(28f + 6f + 2f * ROW - 4f + 6f, background[3], 1e-4f);
  }

  @Test
  void theGuiWantsTheMouseOnlyOverItsPanels() {
    pointAt(50f, 20f);
    frame(() -> this.gui.label("hi"));
    assertTrue(this.gui.wantsMouse());
    pointAt(500f, 400f);
    frame(() -> this.gui.label("hi"));
    assertFalse(this.gui.wantsMouse());

    this.canvas = new FakeCanvas();
    pointAt(50f, 20f);
    this.gui.begin(this.canvas, 800, 600, false); // e.g. while the mouse looks around
    this.gui.beginPanel("Panel", 10f, 10f, 200f);
    this.gui.endPanel();
    this.gui.end();
    assertFalse(this.gui.wantsMouse());
  }

  @Test
  void labelsHideTheirIdSuffixAndLongTextIsShortened() {
    frame(() -> {
      this.gui.button("OK##first");
      this.gui.button("OK##second");
      this.gui.label("a label that is far too long to fit into two hundred pixels");
    });
    assertEquals(2, this.canvas.texts.stream().filter("OK"::equals).count());
    final var shortened = this.canvas.texts.get(this.canvas.texts.size() - 1);
    assertTrue(shortened.endsWith("…"));
    assertTrue(shortened.length() * 8f <= 188f);
    assertEquals("ab", Gui.edit("abc", "\b"));
    assertEquals("", Gui.edit("", "\b\b"));
  }

  // ---- keyboard ----------------------------------------------------------------------------------------------

  @Test
  void tabMovesTheFocusAndEnterPressesTheFocusedButton() {
    final boolean[] clicked = new boolean[2];
    final Runnable ui = () -> {
      clicked[0] = this.gui.button("First");
      clicked[1] = this.gui.button("Second");
    };
    frame(ui); // the gui learns its widgets
    this.input.typed('\t');
    frame(ui);
    this.input.typed('\t');
    this.input.typed('\n');
    frame(ui);
    assertFalse(clicked[0]);
    assertTrue(clicked[1], "two tabs reached the second button");
    this.input.typed(InputState.KEY_BACKTAB);
    this.input.typed(' ');
    frame(ui);
    assertTrue(clicked[0], "shift+tab went back; space presses too");
  }

  @Test
  void aFocusedSliderMovesWithTheArrowKeys() {
    final float[] v = { 5f };
    final Runnable ui = () -> v[0] = this.gui.slider("V", v[0], 0f, 10f);
    frame(ui);
    this.input.typed('\t');
    frame(ui);
    this.input.typed(InputState.KEY_RIGHT);
    this.input.typed(InputState.KEY_RIGHT);
    frame(ui);
    assertEquals(6f, v[0], 1e-5f);
    this.input.typed(InputState.KEY_HOME);
    frame(ui);
    assertEquals(0f, v[0], 0f);
  }

  @Test
  void clickingIntoATextFieldPlacesTheCursor() {
    final String[] text = { "abcdef" };
    final Runnable ui = () -> text[0] = this.gui.textField("##t", text[0]);
    pointAt(16f + 6f + 8f * 2f + 1f, FIRST_Y + 10f); // between the c and the d... nearest to after "ab"
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    this.input.typed('X');
    frame(ui);
    assertEquals("abXcdef", text[0]);
  }

  @Test
  void textAreasTakeNewLines() {
    final String[] text = { "one" };
    final Runnable ui = () -> text[0] = this.gui.textArea("Notes", text[0], 3);
    pointAt(100f, FIRST_Y + 10f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    for (char c : (InputState.KEY_END + "\ntwo").toCharArray()) {
      this.input.typed(c);
    }
    frame(ui);
    assertEquals("one\ntwo", text[0]);
    assertTrue(this.gui.wantsKeyboard(), "still editing: Enter does not end a text area");
  }

  // ---- scrolling ---------------------------------------------------------------------------------------------

  @Test
  void aScrollRegionClipsItsContentAndScrollsWithTheWheel() {
    final boolean[] clicked = new boolean[10];
    final Runnable ui = () -> {
      this.gui.beginScroll("list", 100f);
      for (int i = 0; i < 10; i++) {
        clicked[i] = this.gui.button("Item " + i);
      }
      this.gui.endScroll();
    };
    frame(ui);
    frame(ui); // now the content height is known
    assertTrue(this.canvas.clips > 0);

    // item 5 is at 5 * 32 = 160 px into the content: hidden below the 100 px region, so it cannot be clicked
    pointAt(50f, FIRST_Y + 160f + 10f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    assertFalse(clicked[5]);

    pointAt(50f, FIRST_Y + 50f);
    this.input.mouseWheel(-2f); // towards the user: scroll down by 2 * 3 lines = 96 px
    frame(ui);
    pointAt(50f, FIRST_Y + 160f - 96f + 10f);
    this.input.mouseButton(InputState.BUTTON_LEFT, true);
    this.input.mouseButton(InputState.BUTTON_LEFT, false);
    frame(ui);
    assertTrue(clicked[5], "scrolled into view and clickable");
  }
}
