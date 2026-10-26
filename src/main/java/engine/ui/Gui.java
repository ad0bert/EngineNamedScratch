package engine.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import engine.input.InputState;
import engine.render.UiCanvas;
import engine.render.UiFont;

/**
 * Immediate-mode widgets on top of a {@link UiCanvas}: panels, labels, buttons, checkboxes, sliders, text fields and
 * text areas, laid out top to bottom inside a panel (or side by side after {@link #row(int)}), optionally inside a
 * scrolling region. Widgets are plain method calls that draw themselves and return what the user did, so there is no
 * widget tree to keep in sync with game state:
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
 * {@code "##"} (it is not displayed): {@code button("OK##second")}.
 *
 * <p>
 * Mouse: clicks count when the button is released over the widget it went down on; the wheel scrolls the region under
 * the pointer, and scroll bars can be dragged. Keyboard: Tab and Shift+Tab move the focus through the widgets, Enter
 * or Space presses the focused button or checkbox, the arrow keys (and Home/End) move a focused slider. Text fields
 * and areas edit like any text box: a cursor that the arrows, Home and End move (with Shift to select), mouse
 * placement and drag selection, Ctrl+A, Backspace and Delete, Ctrl+C/X/V through a {@link Clipboard} (the
 * system one unless the constructor is given another); Enter ends editing a field and starts a new line in an area.
 * {@link #paragraph} shows text wrapped at word boundaries. {@link #wantsMouse()} and {@link #wantsKeyboard()} tell the game what the interface is using.
 */
public class Gui {
  /** Colours and sizes; change them freely. Colours are RGBA. */
  public static class Style {
    public UiFont font = UiFont.DEFAULT;
    public float padding = 6f;
    public float spacing = 4f;
    public float scrollbar = 8f;
    public float[] panel = { 0.08f, 0.09f, 0.11f, 0.86f };
    public float[] title = { 0.16f, 0.32f, 0.55f, 1f };
    public float[] widget = { 0.22f, 0.23f, 0.27f, 1f };
    public float[] hot = { 0.3f, 0.32f, 0.38f, 1f };
    public float[] active = { 0.38f, 0.42f, 0.52f, 1f };
    public float[] accent = { 0.32f, 0.62f, 1f, 1f };
    public float[] selection = { 0.25f, 0.45f, 0.8f, 0.6f };
    public float[] text = { 0.95f, 0.95f, 0.97f, 1f };
    public float[] dimText = { 0.7f, 0.72f, 0.78f, 1f };
  }

  /** A scrolling region being laid out. */
  private static final class Scroll {
    String id;
    float x;
    float y;
    float w;
    float h;
    float offset;
    float savedWidth;
  }

  public final Style style = new Style();
  private final InputState input;
  private final Clipboard clipboard;

  private UiCanvas ui;
  private boolean interactive;
  private float mouseX;
  private float mouseY;
  private boolean mouseDown;
  private boolean pressed;
  private boolean released;
  private int seenPresses;
  private int seenReleases;
  private float wheel;
  /** Keys and characters for the focused widget this frame. */
  private String typed = "";

  private String hot;
  private String active;
  private String focused;
  private boolean focusFromKeyboard;
  private boolean focusClaimed;
  private boolean overPanel;
  private volatile boolean wantsMouse;
  private volatile boolean wantsKeyboard;
  private List<String> focusOrder = new ArrayList<>();
  private List<String> lastFocusOrder = new ArrayList<>();
  private TextEdit edit;
  private String editId;

  // clip rectangles for hit testing (x0, y0, x1, y1), mirroring what the canvas clips
  private final float[] clips = new float[64 * 4];
  private int clipDepth;

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
  private final ArrayDeque<Scroll> scrolls = new ArrayDeque<>();
  private final Map<String, Float> scrollOffsets = new HashMap<>();
  private final Map<String, Float> contentHeights = new HashMap<>();
  private float grab;

  /** Rectangle of the widget being laid out. */
  private float wx;
  private float wy;
  private float ww;
  private float wh;

  public Gui(InputState input) {
    this(input, Clipboard.system());
  }

  public Gui(InputState input, Clipboard clipboard) {
    this.input = input;
    this.clipboard = clipboard;
  }

  public Clipboard clipboard() {
    return this.clipboard;
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
    this.wheel = interactive ? this.input.consumeWheel() : 0f;
    // always drained, so text typed while nothing had the focus does not appear in the next widget that gets it
    final var text = interactive ? this.input.consumeTypedText() : "";
    final var forWidget = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '\t' || c == InputState.KEY_BACKTAB) {
        moveFocus(c == '\t' ? 1 : -1);
        forWidget.setLength(0); // what was typed before belonged to the widget that lost the focus
      } else if (this.focused != null) {
        forWidget.append(c);
      }
    }
    this.typed = forWidget.toString();
    this.hot = null;
    this.overPanel = false;
    this.focusClaimed = false;
    this.clipDepth = 0;
    final var order = this.lastFocusOrder;
    this.lastFocusOrder = this.focusOrder;
    this.focusOrder = order;
    this.focusOrder.clear();
    if (!interactive) {
      this.active = null;
      this.focused = null;
    }
  }

  private void moveFocus(int direction) {
    final var order = this.focusOrder; // last frame's order: begin() swaps the lists right after this
    if (order.isEmpty()) {
      return;
    }
    final int at = this.focused != null ? order.indexOf(this.focused) : -1;
    final int n = order.size();
    final int next = at < 0 ? (direction > 0 ? 0 : n - 1) : (at + direction + n) % n;
    this.focused = order.get(next);
    this.focusFromKeyboard = true;
  }

  /** Ends the frame: a release ends any drag, a click outside every widget ends the focus. */
  public void end() {
    if (this.released || !this.mouseDown && !this.pressed) {
      this.active = null;
    }
    if (this.pressed && !this.focusClaimed) {
      this.focused = null;
    }
    if (this.focused == null || !this.focused.equals(this.editId)) {
      this.edit = null;
      this.editId = null;
    }
    this.wantsMouse = this.interactive && (this.overPanel || this.active != null);
    this.wantsKeyboard = this.interactive && this.editId != null;
  }

  /** True while the pointer is over a panel or a widget is being dragged; safe to call from any thread. */
  public boolean wantsMouse() {
    return this.wantsMouse;
  }

  /** True while text is being edited, so the game can ignore its key bindings; safe to call from any thread. */
  public boolean wantsKeyboard() {
    return this.wantsKeyboard || this.editId != null;
  }

  // ---- panels, layout and scrolling --------------------------------------------------------------------------

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
    while (!this.scrolls.isEmpty()) {
      endScroll(); // forgiving: an unclosed scroll region ends with its panel
    }
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

  /**
   * Starts a region {@code height} pixels tall whose content scrolls (wheel or scroll bar) when it is taller; widgets
   * outside the region are clipped and cannot be clicked. Ends with {@link #endScroll()}.
   */
  public void beginScroll(String id, float height) {
    endRow();
    place(height);
    final var s = new Scroll();
    s.id = this.panelId + "/" + id;
    s.x = this.wx;
    s.y = this.wy;
    s.w = this.ww;
    s.h = height;
    final float content = this.contentHeights.getOrDefault(s.id, 0f);
    float offset = this.scrollOffsets.getOrDefault(s.id, 0f);
    if (this.wheel != 0f && inside(s.x, s.y, s.w, s.h)) {
      offset -= this.wheel * 3f * lineHeight();
      this.wheel = 0f; // the innermost region under the pointer takes it
    }
    s.offset = Math.max(0f, Math.min(offset, content - height));
    this.scrollOffsets.put(s.id, s.offset);
    fill(s.x, s.y, s.w, s.h, this.style.panel);
    pushClip(s.x, s.y, s.w, s.h);
    // the content is laid out as if the panel were narrower (room for the bar) and shifted up by the offset
    s.savedWidth = this.panelWidth;
    this.panelWidth -= this.style.scrollbar + this.style.spacing;
    this.cursorY = s.y - s.offset;
    this.scrolls.push(s);
  }

  public void endScroll() {
    endRow();
    final var s = this.scrolls.pop();
    final float content = this.cursorY + s.offset - s.y - this.style.spacing;
    this.contentHeights.put(s.id, content);
    popClip();
    this.panelWidth = s.savedWidth;
    this.cursorY = s.y + s.h + this.style.spacing;
    if (content <= s.h) {
      return;
    }
    // the scroll bar: drag the thumb (or click the track to jump there)
    final float barX = s.x + s.w - this.style.scrollbar;
    final float thumbH = Math.max(this.style.scrollbar * 2f, s.h * s.h / content);
    final float travel = s.h - thumbH;
    final float thumbY = s.y + travel * s.offset / (content - s.h);
    final var barId = s.id + "#bar";
    final boolean over = this.interactive && inside(barX, s.y, this.style.scrollbar, s.h);
    if (over && this.pressed) {
      this.active = barId;
      this.focusClaimed = true;
      this.grab = this.mouseY >= thumbY && this.mouseY < thumbY + thumbH ? this.mouseY - thumbY : thumbH / 2f;
    }
    float offset = s.offset;
    if (barId.equals(this.active) && this.mouseDown && travel > 0f) {
      final float t = Math.max(0f, Math.min(1f, (this.mouseY - this.grab - s.y) / travel));
      offset = t * (content - s.h);
      this.scrollOffsets.put(s.id, offset);
    }
    fill(barX, s.y, this.style.scrollbar, s.h, this.style.widget);
    fill(barX, s.y + travel * offset / (content - s.h), this.style.scrollbar, thumbH,
        barId.equals(this.active) ? this.style.accent : over ? this.style.active : this.style.hot);
  }

  private void endRow() {
    if (this.rowColumns > 0 && this.rowIndex > 0) {
      this.cursorY += this.rowHeight + this.style.spacing;
    }
    this.rowColumns = 0;
  }

  /** Reserves the rectangle of the next widget. */
  /** Width the next widget gets: the panel's inner width, or a row cell's. */
  private float nextWidth() {
    final float inner = this.panelWidth - 2f * this.style.padding;
    return this.rowColumns > 0 ? (inner - (this.rowColumns - 1) * this.style.spacing) / this.rowColumns : inner;
  }

  private void place(float height) {
    final float inner = this.panelWidth - 2f * this.style.padding;
    if (this.rowColumns > 0) {
      final float w = nextWidth();
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

  private void pushClip(float x, float y, float w, float h) {
    float x0 = x;
    float y0 = y;
    float x1 = x + w;
    float y1 = y + h;
    if (this.clipDepth > 0) {
      final int o = (this.clipDepth - 1) * 4;
      x0 = Math.max(x0, this.clips[o]);
      y0 = Math.max(y0, this.clips[o + 1]);
      x1 = Math.min(x1, this.clips[o + 2]);
      y1 = Math.min(y1, this.clips[o + 3]);
    }
    final int o = this.clipDepth * 4;
    this.clips[o] = x0;
    this.clips[o + 1] = y0;
    this.clips[o + 2] = x1;
    this.clips[o + 3] = y1;
    this.clipDepth++;
    this.ui.pushClip(x, y, w, h);
  }

  private void popClip() {
    this.clipDepth--;
    this.ui.popClip();
  }

  // ---- widgets -----------------------------------------------------------------------------------------------

  public void label(String text) {
    place(lineHeight());
    text(fit(display(text), this.ww), this.wx, this.wy, this.style.dimText);
  }

  /**
   * Text wrapped at word boundaries to the width of the panel (or row cell), as many lines as it needs; explicit line
   * breaks are kept, and a word longer than a line is broken inside.
   */
  public void paragraph(String text) {
    final float width = Math.max(1f, nextWidth());
    final var lines = TextWrap.wrap(display(text), width, s -> this.ui.textWidth(this.style.font, s, 1f));
    place(Math.max(1, lines.size()) * lineHeight());
    final float lh = lineHeight();
    for (int i = 0; i < lines.size(); i++) {
      text(lines.get(i), this.wx, this.wy + i * lh, this.style.dimText);
    }
  }

  /** @return true in the frame the button was clicked (or pressed with Enter/Space while focused) */
  public boolean button(String label) {
    final var id = id(label);
    place(widgetHeight());
    final boolean over = interact(id);
    final boolean clicked = this.released && over && id.equals(this.active) || activatedByKey(id);
    fill(this.wx, this.wy, this.ww, this.wh, colour(id, over));
    focusRing(id);
    final var shown = fit(display(label), this.ww - 2f * this.style.padding);
    text(shown, this.wx + (this.ww - this.ui.textWidth(this.style.font, shown, 1f)) / 2f,
        this.wy + this.style.padding, this.style.text);
    return clicked;
  }

  /** @return the new value (toggled in the frame the box was clicked, or Enter/Space pressed while focused) */
  public boolean checkbox(String label, boolean value) {
    final var id = id(label);
    place(widgetHeight());
    final boolean over = interact(id);
    final boolean toggle = this.released && over && id.equals(this.active) || activatedByKey(id);
    final boolean result = toggle != value;
    final float box = this.wh - 2f * this.style.padding;
    final float bx = this.wx;
    final float by = this.wy + this.style.padding;
    fill(bx, by, box, box, colour(id, over));
    if (result) {
      final float inset = Math.max(2f, box * 0.22f);
      fill(bx + inset, by + inset, box - 2f * inset, box - 2f * inset, this.style.accent);
    }
    focusRing(id);
    text(fit(display(label), this.ww - box - this.style.padding), bx + box + this.style.padding,
        this.wy + this.style.padding, this.style.text);
    return result;
  }

  /**
   * A horizontal slider; dragging sets the value, and while focused the arrow keys move it by a twentieth of the
   * range (Home/End: to the ends). @return the new value, within min..max
   */
  public float slider(String label, float value, float min, float max) {
    final var id = id(label);
    place(widgetHeight());
    final boolean over = interact(id);
    float v = Math.max(min, Math.min(max, value));
    if (id.equals(this.active) && this.mouseDown && max > min) {
      final float t = Math.max(0f, Math.min(1f, (this.mouseX - this.wx) / this.ww));
      v = min + t * (max - min);
    }
    if (id.equals(this.focused)) {
      final float step = (max - min) / 20f;
      for (int i = 0; i < this.typed.length(); i++) {
        final char c = this.typed.charAt(i);
        final char key = c >= InputState.KEY_LEFT + InputState.SHIFT && c <= InputState.KEY_END + InputState.SHIFT
            ? (char) (c - InputState.SHIFT)
            : c; // Shift makes no difference here
        if (key == InputState.KEY_LEFT || key == InputState.KEY_DOWN) {
          v = Math.max(min, v - step);
        } else if (key == InputState.KEY_RIGHT || key == InputState.KEY_UP) {
          v = Math.min(max, v + step);
        } else if (key == InputState.KEY_HOME) {
          v = min;
        } else if (key == InputState.KEY_END) {
          v = max;
        }
      }
    }
    fill(this.wx, this.wy, this.ww, this.wh, colour(id, over));
    final float t = max > min ? (v - min) / (max - min) : 0f;
    fill(this.wx, this.wy, this.ww * t, this.wh, this.style.title);
    fill(this.wx + this.ww * t - 2f, this.wy, 4f, this.wh, this.style.accent);
    focusRing(id);
    final var shown = fit(display(label) + ": " + format(v), this.ww - 2f * this.style.padding);
    text(shown, this.wx + (this.ww - this.ui.textWidth(this.style.font, shown, 1f)) / 2f,
        this.wy + this.style.padding, this.style.text);
    return v;
  }

  /** A single-line text box (see the class comment for the keys). @return the (possibly edited) text */
  public String textField(String label, String value) {
    return textBox(label, value, false, 1);
  }

  /** A multi-line text box, {@code lines} lines tall; Enter starts a new line. @return the (possibly edited) text */
  public String textArea(String label, String value, int lines) {
    return textBox(label, value, true, Math.max(1, lines));
  }

  private String textBox(String label, String value, boolean multiLine, int lines) {
    final var id = id(label);
    final float lh = lineHeight();
    place(lines * lh + 2f * this.style.padding);
    final boolean over = interact(id);
    final var prefix = display(label);
    final float labelWidth = prefix.isEmpty() || multiLine ? 0f
        : this.ui.textWidth(this.style.font, prefix, 1f) + this.style.padding;
    final float tx = this.wx + this.style.padding + labelWidth;
    final float ty = this.wy + this.style.padding;
    final float innerW = this.ww - 2f * this.style.padding - labelWidth;
    final float innerH = lines * lh;

    final boolean editing = id.equals(this.focused);
    var text = value;
    if (editing) {
      if (!id.equals(this.editId)) {
        this.edit = new TextEdit(value, multiLine);
        this.edit.setClipboard(this.clipboard);
        this.editId = id;
        if (this.focusFromKeyboard) {
          this.edit.selectAll();
        }
      }
      final var e = this.edit;
      e.setText(value);
      final float[] scroll = scrollFor(e, tx, ty, innerW, innerH, multiLine);
      if (this.pressed && over) {
        e.moveTo(indexAt(e.text(), this.mouseX - tx + scroll[0], this.mouseY - ty + scroll[1], multiLine), false);
      } else if (id.equals(this.active) && this.mouseDown) {
        e.moveTo(indexAt(e.text(), this.mouseX - tx + scroll[0], this.mouseY - ty + scroll[1], multiLine), true);
      }
      e.apply(this.typed);
      text = e.text();
      if (!multiLine && this.typed.indexOf('\n') >= 0) {
        this.focused = null; // Enter ends editing a field
      }
    }
    fill(this.wx, this.wy, this.ww, this.wh, editing ? this.style.active : colour(id, over));
    if (labelWidth > 0f) {
      text(prefix, this.wx + this.style.padding, ty, this.style.dimText);
    }
    pushClip(tx, ty, innerW, innerH);
    if (editing && this.edit != null) {
      final var e = this.edit;
      final float[] scroll = scrollFor(e, tx, ty, innerW, innerH, multiLine);
      final float ox = tx - scroll[0];
      final float oy = ty - scroll[1];
      if (e.hasSelection()) {
        drawSelection(e, ox, oy, lh);
      }
      text(text, ox, oy, this.style.text);
      if ((System.nanoTime() / 500_000_000L) % 2 == 0) {
        final float cx = ox + caretX(e.text(), e.cursor());
        final float cy = oy + caretLine(e.text(), e.cursor()) * lh;
        fill(cx, cy, 1.5f, lh, this.style.text);
      }
    } else {
      text(text, tx, ty, this.style.text);
    }
    popClip();
    if (multiLine && !prefix.isEmpty() && !editing && text.isEmpty()) {
      text(prefix, tx, ty, this.style.dimText); // a placeholder
    }
    focusRing(id);
    return text;
  }

  /** Scroll offsets that keep the cursor in view: {x, y}. */
  private float[] scrollFor(TextEdit e, float tx, float ty, float w, float h, boolean multiLine) {
    final float cx = caretX(e.text(), e.cursor());
    final float cy = caretLine(e.text(), e.cursor()) * lineHeight();
    final float sx = Math.max(0f, cx - w + 2f);
    final float sy = multiLine ? Math.max(0f, cy + lineHeight() - h) : 0f;
    this.scrollPair[0] = sx;
    this.scrollPair[1] = sy;
    return this.scrollPair;
  }

  private final float[] scrollPair = new float[2];

  /** x of the cursor within its line. */
  private float caretX(String text, int index) {
    final int start = text.lastIndexOf('\n', index - 1) + 1;
    return this.ui.textWidth(this.style.font, text.substring(start, index), 1f);
  }

  private static int caretLine(String text, int index) {
    int line = 0;
    for (int i = 0; i < index; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /** The index nearest to a point relative to the text's top left. */
  private int indexAt(String text, float x, float y, boolean multiLine) {
    int start = 0;
    if (multiLine) {
      int line = Math.max(0, (int) Math.floor(y / lineHeight()));
      while (line-- > 0) {
        final int next = text.indexOf('\n', start);
        if (next < 0) {
          break;
        }
        start = next + 1;
      }
    }
    int end = text.indexOf('\n', start);
    if (end < 0) {
      end = text.length();
    }
    int best = start;
    float bestDistance = Math.abs(x);
    for (int i = start; i < end;) {
      i = text.offsetByCodePoints(i, 1);
      final float d = Math.abs(this.ui.textWidth(this.style.font, text.substring(start, i), 1f) - x);
      if (d < bestDistance) {
        bestDistance = d;
        best = i;
      }
    }
    return best;
  }

  private void drawSelection(TextEdit e, float ox, float oy, float lh) {
    final var text = e.text();
    final int from = e.selectionStart();
    final int to = e.selectionEnd();
    int lineStart = text.lastIndexOf('\n', from - 1) + 1;
    int line = caretLine(text, from);
    while (lineStart <= to) {
      int lineEnd = text.indexOf('\n', lineStart);
      if (lineEnd < 0) {
        lineEnd = text.length();
      }
      final int a = Math.max(from, lineStart);
      final int b = Math.min(to, lineEnd);
      final float x0 = this.ui.textWidth(this.style.font, text.substring(lineStart, a), 1f);
      final float x1 = this.ui.textWidth(this.style.font, text.substring(lineStart, b), 1f) + (b < to ? 4f : 0f);
      fill(ox + x0, oy + line * lh, Math.max(1f, x1 - x0), lh, this.style.selection);
      if (lineEnd >= text.length()) {
        break;
      }
      lineStart = lineEnd + 1;
      line++;
    }
  }

  /** Applies typed characters: printable ones are appended, {@code '\b'} deletes the last code point. */
  static String edit(String value, String typed) {
    final var e = new TextEdit(value, false);
    e.apply(typed);
    return e.text();
  }

  // ---- helpers -----------------------------------------------------------------------------------------------

  /**
   * Hover, press and focus handling shared by all widgets; returns whether the pointer is over the widget. Every
   * interactive widget can take the keyboard focus.
   */
  private boolean interact(String id) {
    if (!this.focusOrder.contains(id)) {
      this.focusOrder.add(id);
    }
    final boolean over = this.interactive && inside(this.wx, this.wy, this.ww, this.wh);
    if (over) {
      this.hot = id;
      if (this.pressed) {
        this.active = id;
        this.focused = id;
        this.focusFromKeyboard = false;
        this.focusClaimed = true;
      }
    }
    return over;
  }

  /** Enter or Space while the widget has the focus. */
  private boolean activatedByKey(String id) {
    return id.equals(this.focused) && (this.typed.indexOf('\n') >= 0 || this.typed.indexOf(' ') >= 0);
  }

  /** An outline around the focused widget, when the focus came from the keyboard. */
  private void focusRing(String id) {
    if (this.focusFromKeyboard && id.equals(this.focused)) {
      final float[] c = this.style.accent;
      fill(this.wx - 2f, this.wy - 2f, this.ww + 4f, 2f, c);
      fill(this.wx - 2f, this.wy + this.wh, this.ww + 4f, 2f, c);
      fill(this.wx - 2f, this.wy, 2f, this.wh, c);
      fill(this.wx + this.ww, this.wy, 2f, this.wh, c);
    }
  }

  private float[] colour(String id, boolean over) {
    if (id.equals(this.active) && this.mouseDown) {
      return this.style.active;
    }
    return over ? this.style.hot : this.style.widget;
  }

  /** Is the pointer in the rectangle, and in the visible (clipped) part of it? */
  private boolean inside(float x, float y, float w, float h) {
    if (this.mouseX < x || this.mouseX >= x + w || this.mouseY < y || this.mouseY >= y + h) {
      return false;
    }
    if (this.clipDepth > 0) {
      final int o = (this.clipDepth - 1) * 4;
      return this.mouseX >= this.clips[o] && this.mouseX < this.clips[o + 2] && this.mouseY >= this.clips[o + 1]
          && this.mouseY < this.clips[o + 3];
    }
    return true;
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
