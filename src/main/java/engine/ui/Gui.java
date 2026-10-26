package engine.ui;

import java.util.HashMap;
import java.util.Map;

import engine.input.InputState;
import engine.render.UiCanvas;
import engine.render.UiFont;

/**
 * Immediate-mode widgets on top of a {@link UiCanvas}: panels, labels, buttons, checkboxes, sliders and text fields,
 * laid out top to bottom inside a panel (or side by side after {@link #row(int)}). Widgets are plain method calls that
 * draw themselves and return what the user did, so there is no widget tree to keep in sync with game state:
 *
 * <pre>
 * engine.addOverlay((ui, w, h) -&gt; {
 *   gui.begin(ui, w, h, !engine.isMouseCaptured());
 *   gui.beginPanel("Settings", 10, 10, 220);
 *   if (gui.button("Reset")) { ... }
 *   shadows = gui.checkbox("Shadows", shadows);
 *   volume = gui.slider("Volume", volume, 0, 1);
 *   gui.endPanel();
 *   gui.end();
 * });
 * </pre>
 *
 * <p>
 * A widget is identified by its panel and label; to show two widgets with the same text, add a suffix after
 * {@code "##"} (it is not displayed): {@code button("OK##second")}. Clicks count when the button is released over the
 * widget it went down on. While a text field has the keyboard focus it consumes typed text, Backspace deletes and
 * Enter (or a click elsewhere) ends editing. {@link #wantsMouse()} tells the game (and the engine's mouse capture)
 * whether the pointer is over the interface.
 */
public class Gui {
  /** Colours and sizes; change them freely. Colours are RGBA. */
  public static class Style {
    public UiFont font = UiFont.DEFAULT;
    public float padding = 6f;
    public float spacing = 4f;
    public float[] panel = { 0.08f, 0.09f, 0.11f, 0.86f };
    public float[] title = { 0.16f, 0.32f, 0.55f, 1f };
    public float[] widget = { 0.22f, 0.23f, 0.27f, 1f };
    public float[] hot = { 0.3f, 0.32f, 0.38f, 1f };
    public float[] active = { 0.38f, 0.42f, 0.52f, 1f };
    public float[] accent = { 0.32f, 0.62f, 1f, 1f };
    public float[] text = { 0.95f, 0.95f, 0.97f, 1f };
    public float[] dimText = { 0.7f, 0.72f, 0.78f, 1f };
  }

  public final Style style = new Style();
  private final InputState input;

  private UiCanvas ui;
  private boolean interactive;
  private float mouseX;
  private float mouseY;
  private boolean mouseDown;
  private boolean pressed;
  private boolean released;
  private int seenPresses;
  private int seenReleases;
  private String typed = "";

  private String hot;
  private String active;
  private String focused;
  private boolean focusClaimed;
  private boolean overPanel;
  private volatile boolean wantsMouse;

  // layout of the current panel
  private String panelId = "";
  private float panelX;
  private float panelY;
  private float panelWidth;
  private float cursorY;
  private final Map<String, Float> panelHeights = new HashMap<>();
  private int rowColumns;
  private int rowIndex;
  private float rowHeight;

  /** Rectangle of the widget being laid out. */
  private float wx;
  private float wy;
  private float ww;
  private float wh;

  public Gui(InputState input) {
    this.input = input;
  }

  /**
   * Starts a frame. {@code interactive} false draws the widgets but ignores the mouse and keyboard (e.g. while the
   * mouse is captured for looking around).
   */
  public void begin(UiCanvas ui, int width, int height, boolean interactive) {
    this.ui = ui;
    this.interactive = interactive;
    this.mouseX = this.input.mouseX();
    this.mouseY = this.input.mouseY();
    this.mouseDown = this.input.isMouseDown(InputState.BUTTON_LEFT);
    // counters rather than the current state: a click shorter than a frame still counts
    final int presses = this.input.mousePresses(InputState.BUTTON_LEFT);
    final int releases = this.input.mouseReleases(InputState.BUTTON_LEFT);
    this.pressed = interactive && presses != this.seenPresses;
    this.released = interactive && releases != this.seenReleases;
    this.seenPresses = presses;
    this.seenReleases = releases;
    // always drained, so text typed while no field had the focus does not appear in the next one that gets it
    final var text = interactive ? this.input.consumeTypedText() : "";
    this.typed = this.focused != null ? text : "";
    this.hot = null;
    this.overPanel = false;
    this.focusClaimed = false;
    if (!interactive) {
      this.active = null;
      this.focused = null;
    }
  }

  /** Ends the frame: a release ends any drag, a click outside every text field ends editing. */
  public void end() {
    if (this.released || !this.mouseDown && !this.pressed) {
      this.active = null;
    }
    if (this.pressed && !this.focusClaimed) {
      this.focused = null;
    }
    this.wantsMouse = this.interactive && (this.overPanel || this.active != null);
  }

  /** True while the pointer is over a panel or a widget is being dragged; safe to call from any thread. */
  public boolean wantsMouse() {
    return this.wantsMouse;
  }

  /** True while a text field is being edited, so the game can ignore its key bindings. */
  public boolean wantsKeyboard() {
    return this.focused != null;
  }

  // ---- panels and layout -------------------------------------------------------------------------------------

  /**
   * Starts a panel with a title bar at x, y. Its background is as tall as the panel's content was in the last frame
   * (immediate mode only knows the height once everything is laid out).
   */
  public void beginPanel(String title, float x, float y, float width) {
    this.panelId = title;
    this.panelX = x;
    this.panelY = y;
    this.panelWidth = width;
    final float titleHeight = lineHeight() + 2f * this.style.padding;
    final float height = this.panelHeights.getOrDefault(title, titleHeight);
    if (this.interactive && inside(x, y, width, height)) {
      this.overPanel = true;
    }
    fill(x, y, width, height, this.style.panel);
    fill(x, y, width, titleHeight, this.style.title);
    text(display(title), x + this.style.padding, y + this.style.padding, this.style.text);
    this.cursorY = y + titleHeight + this.style.padding;
    this.rowColumns = 0;
  }

  public void endPanel() {
    endRow();
    this.panelHeights.put(this.panelId, this.cursorY - this.panelY + this.style.padding - this.style.spacing);
    this.panelId = "";
  }

  /** Lays out the next {@code columns} widgets side by side, sharing the panel's width. */
  public void row(int columns) {
    endRow();
    this.rowColumns = Math.max(1, columns);
    this.rowIndex = 0;
    this.rowHeight = 0f;
  }

  /** Vertical gap. */
  public void space(float pixels) {
    endRow();
    this.cursorY += pixels;
  }

  private void endRow() {
    if (this.rowColumns > 0 && this.rowIndex > 0) {
      this.cursorY += this.rowHeight + this.style.spacing;
    }
    this.rowColumns = 0;
  }

  /** Reserves the rectangle of the next widget. */
  private void place(float height) {
    final float inner = this.panelWidth - 2f * this.style.padding;
    if (this.rowColumns > 0) {
      final float w = (inner - (this.rowColumns - 1) * this.style.spacing) / this.rowColumns;
      this.wx = this.panelX + this.style.padding + this.rowIndex * (w + this.style.spacing);
      this.wy = this.cursorY;
      this.ww = w;
      this.wh = height;
      this.rowHeight = Math.max(this.rowHeight, height);
      if (++this.rowIndex == this.rowColumns) { // the row is full: back to one widget per line
        this.cursorY += this.rowHeight + this.style.spacing;
        this.rowColumns = 0;
      }
    } else {
      this.wx = this.panelX + this.style.padding;
      this.wy = this.cursorY;
      this.ww = inner;
      this.wh = height;
      this.cursorY += height + this.style.spacing;
    }
  }

  private float widgetHeight() {
    return lineHeight() + 2f * this.style.padding;
  }

  // ---- widgets -----------------------------------------------------------------------------------------------

  public void label(String text) {
    place(lineHeight());
    text(fit(display(text), this.ww), this.wx, this.wy, this.style.dimText);
  }

  /** @return true in the frame the button was clicked */
  public boolean button(String label) {
    final var id = id(label);
    place(widgetHeight());
    final boolean over = interact(id);
    final boolean clicked = this.released && over && id.equals(this.active);
    fill(this.wx, this.wy, this.ww, this.wh, colour(id, over));
    final var shown = fit(display(label), this.ww - 2f * this.style.padding);
    text(shown, this.wx + (this.ww - this.ui.textWidth(this.style.font, shown, 1f)) / 2f,
        this.wy + this.style.padding, this.style.text);
    return clicked;
  }

  /** @return the new value (toggled in the frame the box was clicked) */
  public boolean checkbox(String label, boolean value) {
    final var id = id(label);
    place(widgetHeight());
    final boolean over = interact(id);
    final boolean result = this.released && over && id.equals(this.active) ? !value : value;
    final float box = this.wh - 2f * this.style.padding;
    final float bx = this.wx;
    final float by = this.wy + this.style.padding;
    fill(bx, by, box, box, colour(id, over));
    if (result) {
      final float inset = Math.max(2f, box * 0.22f);
      fill(bx + inset, by + inset, box - 2f * inset, box - 2f * inset, this.style.accent);
    }
    text(fit(display(label), this.ww - box - this.style.padding), bx + box + this.style.padding,
        this.wy + this.style.padding, this.style.text);
    return result;
  }

  /** A horizontal slider; dragging sets the value. @return the new value, within min..max */
  public float slider(String label, float value, float min, float max) {
    final var id = id(label);
    place(widgetHeight());
    final boolean over = interact(id);
    float v = Math.max(min, Math.min(max, value));
    if (id.equals(this.active) && this.mouseDown && max > min) {
      final float t = Math.max(0f, Math.min(1f, (this.mouseX - this.wx) / this.ww));
      v = min + t * (max - min);
    }
    fill(this.wx, this.wy, this.ww, this.wh, colour(id, over));
    final float t = max > min ? (v - min) / (max - min) : 0f;
    fill(this.wx, this.wy, this.ww * t, this.wh, this.style.title);
    fill(this.wx + this.ww * t - 2f, this.wy, 4f, this.wh, this.style.accent);
    final var shown = fit(display(label) + ": " + format(v), this.ww - 2f * this.style.padding);
    text(shown, this.wx + (this.ww - this.ui.textWidth(this.style.font, shown, 1f)) / 2f,
        this.wy + this.style.padding, this.style.text);
    return v;
  }

  /**
   * A single-line text field. Clicking it starts editing; typed text is appended, Backspace deletes, Enter or a click
   * elsewhere ends editing. @return the (possibly edited) text
   */
  public String textField(String label, String value) {
    final var id = id(label);
    place(widgetHeight());
    final boolean over = interact(id);
    if (this.pressed && over) {
      this.focused = id;
      this.focusClaimed = true; // otherwise end() takes the focus away: the click was elsewhere
    }
    var text = value;
    final boolean editing = id.equals(this.focused);
    if (editing) {
      text = edit(value, this.typed);
      if (this.typed.indexOf('\n') >= 0) {
        this.focused = null;
      }
    }
    fill(this.wx, this.wy, this.ww, this.wh, editing ? this.style.active : colour(id, over));
    final var prefix = display(label);
    final float labelWidth = prefix.isEmpty() ? 0f
        : this.ui.textWidth(this.style.font, prefix, 1f) + this.style.padding;
    if (!prefix.isEmpty()) {
      text(prefix, this.wx + this.style.padding, this.wy + this.style.padding, this.style.dimText);
    }
    final boolean caret = editing && (System.nanoTime() / 500_000_000L) % 2 == 0;
    text(caret ? text + "|" : text, this.wx + this.style.padding + labelWidth, this.wy + this.style.padding,
        this.style.text);
    return text;
  }

  /** Applies typed characters: printable ones are appended, {@code '\b'} deletes the last code point. */
  static String edit(String value, String typed) {
    if (typed.isEmpty()) {
      return value;
    }
    final var sb = new StringBuilder(value);
    for (int i = 0; i < typed.length(); i++) {
      final char c = typed.charAt(i);
      if (c == '\b') {
        if (sb.length() > 0) {
          sb.setLength(sb.offsetByCodePoints(sb.length(), -1));
        }
      } else if (c != '\n') {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  // ---- helpers -----------------------------------------------------------------------------------------------

  /** Hover and press handling shared by all widgets; returns whether the pointer is over the widget. */
  private boolean interact(String id) {
    final boolean over = this.interactive && inside(this.wx, this.wy, this.ww, this.wh);
    if (over) {
      this.hot = id;
      if (this.pressed) {
        this.active = id;
      }
    }
    return over;
  }

  private float[] colour(String id, boolean over) {
    if (id.equals(this.active) && this.mouseDown) {
      return this.style.active;
    }
    return over ? this.style.hot : this.style.widget;
  }

  private boolean inside(float x, float y, float w, float h) {
    return this.mouseX >= x && this.mouseX < x + w && this.mouseY >= y && this.mouseY < y + h;
  }

  private String id(String label) {
    return this.panelId + "/" + label;
  }

  /** The text, shortened with an ellipsis if it is wider than {@code width}. */
  private String fit(String text, float width) {
    if (this.ui.textWidth(this.style.font, text, 1f) <= width) {
      return text;
    }
    int end = text.length();
    while (end > 0) {
      end = text.offsetByCodePoints(end, -1);
      final var shorter = text.substring(0, end) + "…";
      if (this.ui.textWidth(this.style.font, shorter, 1f) <= width) {
        return shorter;
      }
    }
    return "";
  }

  /** The label without its {@code ##} identity suffix. */
  static String display(String label) {
    final int cut = label.indexOf("##");
    return cut >= 0 ? label.substring(0, cut) : label;
  }

  private static String format(float v) {
    return Math.abs(v) >= 100f ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.2f", v);
  }

  private float lineHeight() {
    return this.ui.lineHeight(this.style.font, 1f);
  }

  private void fill(float x, float y, float w, float h, float[] c) {
    this.ui.drawRect(x, y, w, h, c[0], c[1], c[2], c[3]);
  }

  private void text(String s, float x, float y, float[] c) {
    this.ui.drawText(this.style.font, s, x, y, 1f, c[0], c[1], c[2], c[3]);
  }

  /** The id of the widget under the pointer in the last frame, or null. */
  String hotWidget() {
    return this.hot;
  }
}
