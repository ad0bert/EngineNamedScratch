package engine.input;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Thread-safe polled input state, written by the window thread and read by the update thread. The writing methods
 * are public so that any platform layer (or a test, or a replay) can feed it; {@link InputListener} does so for the
 * JOGL window.
 *
 * <p>
 * Besides keys and relative mouse motion (for mouse look) it keeps what user interfaces need: the pointer position,
 * button states with press and release counters (so a click shorter than a frame is not lost), the wheel and typed
 * text; and up to {@link #MAX_GAMEPADS} gamepads ({@link GamepadState}). Actions can be bound to keys, gamepad
 * buttons and gamepad axes at the same time; an action is down if any of its bindings is.
 */
public class InputState {
  public static final int MAX_GAMEPADS = 4;
  /** Mouse buttons are numbered from 1 (left, middle, right, ...) up to this. */
  public static final int MAX_BUTTONS = 8;
  public static final int BUTTON_LEFT = 1;
  public static final int BUTTON_MIDDLE = 2;
  public static final int BUTTON_RIGHT = 3;

  // Editing and navigation keys arrive in the typed text as these characters (private use area), so text input keeps
  // the order of keys and characters. Enter is '\n', Backspace '\b', Tab '\t'.
  public static final char KEY_LEFT = '\uE000';
  public static final char KEY_RIGHT = '\uE001';
  public static final char KEY_UP = '\uE002';
  public static final char KEY_DOWN = '\uE003';
  public static final char KEY_HOME = '\uE004';
  public static final char KEY_END = '\uE005';
  public static final char KEY_DELETE = '\uE006';
  public static final char KEY_BACKTAB = '\uE007';
  public static final char KEY_SELECT_ALL = '\uE008';
  /** Ctrl+C, Ctrl+X, Ctrl+V (also Ctrl+Insert, Shift+Delete, Shift+Insert, and Cmd on macOS). */
  public static final char KEY_COPY = '\uE009';
  public static final char KEY_CUT = '\uE00A';
  public static final char KEY_PASTE = '\uE00B';
  /** Added to an arrow, Home or End key when Shift is held (extends the selection). */
  public static final char SHIFT = '\u0010';

  private final Set<Short> keysDown = ConcurrentHashMap.newKeySet();
  private final Set<Short> keysPressedThisFrame = ConcurrentHashMap.newKeySet();
  private final AtomicInteger mouseDx = new AtomicInteger();
  private final AtomicInteger mouseDy = new AtomicInteger();
  private final Map<String, short[]> bindings = new ConcurrentHashMap<>();
  private volatile int mouseX;
  private volatile int mouseY;
  private final AtomicIntegerArray buttonsDown = new AtomicIntegerArray(MAX_BUTTONS + 1);
  private final AtomicIntegerArray buttonPresses = new AtomicIntegerArray(MAX_BUTTONS + 1);
  private final AtomicIntegerArray buttonReleases = new AtomicIntegerArray(MAX_BUTTONS + 1);
  private final AtomicInteger wheelSteps = new AtomicInteger(); // in 1/100 of a notch
  private final ConcurrentLinkedQueue<Character> typed = new ConcurrentLinkedQueue<>();
  private final AtomicInteger typedCount = new AtomicInteger();
  /** Typed text nobody reads is dropped beyond this many characters (oldest first). */
  private static final int MAX_TYPED = 256;
  private final GamepadState[] gamepads = new GamepadState[MAX_GAMEPADS];
  private final Map<String, int[]> padBindings = new ConcurrentHashMap<>();
  private final List<AxisBinding> axisBindings = new CopyOnWriteArrayList<>();

  /** An action that is down while a gamepad axis is pushed beyond a threshold. */
  private static final class AxisBinding {
    final String action;
    final int axis;
    final float threshold;
    final boolean[] down = new boolean[MAX_GAMEPADS];
    volatile boolean pressed;

    AxisBinding(String action, int axis, float threshold) {
      this.action = action;
      this.axis = axis;
      this.threshold = threshold;
    }
  }

  public InputState() {
    for (int i = 0; i < MAX_GAMEPADS; i++) {
      this.gamepads[i] = new GamepadState();
    }
  }

  public void keyDown(short key) {
    if (this.keysDown.add(key)) {
      this.keysPressedThisFrame.add(key);
    }
  }

  public void keyUp(short key) {
    this.keysDown.remove(key);
  }

  /** Relative motion for mouse look (pixels since the last report). */
  public void mouseMoved(int dx, int dy) {
    this.mouseDx.addAndGet(dx);
    this.mouseDy.addAndGet(dy);
  }

  /** Absolute pointer position in window pixels, origin at the top left. */
  public void mouseMovedTo(int x, int y) {
    this.mouseX = x;
    this.mouseY = y;
  }

  public void mouseButton(int button, boolean down) {
    if (button < 1 || button > MAX_BUTTONS) {
      return;
    }
    if (down && this.buttonsDown.getAndSet(button, 1) == 0) {
      this.buttonPresses.incrementAndGet(button);
    } else if (!down && this.buttonsDown.getAndSet(button, 0) == 1) {
      this.buttonReleases.incrementAndGet(button);
    }
  }

  /** Wheel rotation in notches; positive is away from the user. */
  public void mouseWheel(float notches) {
    this.wheelSteps.addAndGet(Math.round(notches * 100f));
  }

  /** A character typed by the user; {@code '\b'} stands for backspace and {@code '\n'} for enter. */
  public void typed(char c) {
    this.typed.add(c);
    if (this.typedCount.incrementAndGet() > MAX_TYPED && this.typed.poll() != null) {
      this.typedCount.decrementAndGet();
    }
  }

  public int mouseX() {
    return this.mouseX;
  }

  public int mouseY() {
    return this.mouseY;
  }

  public boolean isMouseDown(int button) {
    return button >= 1 && button <= MAX_BUTTONS && this.buttonsDown.get(button) == 1;
  }

  /** How often the button went down since the start; compare with an earlier value to see new presses. */
  public int mousePresses(int button) {
    return button >= 1 && button <= MAX_BUTTONS ? this.buttonPresses.get(button) : 0;
  }

  /** How often the button went up since the start. */
  public int mouseReleases(int button) {
    return button >= 1 && button <= MAX_BUTTONS ? this.buttonReleases.get(button) : 0;
  }

  /** Wheel notches since the last call; resets the accumulator. */
  public float consumeWheel() {
    return this.wheelSteps.getAndSet(0) / 100f;
  }

  /** Text typed since the last call (see {@link #typed}); resets the queue. */
  public String consumeTypedText() {
    if (this.typed.isEmpty()) {
      return "";
    }
    final var sb = new StringBuilder();
    for (Character c = this.typed.poll(); c != null; c = this.typed.poll()) {
      this.typedCount.decrementAndGet();
      sb.append(c.charValue());
    }
    return sb.toString();
  }

  /** A gamepad was plugged into slot {@code pad} (0..{@link #MAX_GAMEPADS}-1). */
  public void gamepadConnected(int pad, String name) {
    if (pad >= 0 && pad < MAX_GAMEPADS) {
      this.gamepads[pad].setConnected(true, name);
    }
  }

  /** The gamepad in the slot went away; its buttons and axes read as released. */
  public void gamepadDisconnected(int pad) {
    if (pad >= 0 && pad < MAX_GAMEPADS) {
      this.gamepads[pad].setConnected(false, null);
      for (int i = 0; i < this.axisBindings.size(); i++) {
        this.axisBindings.get(i).down[pad] = false;
      }
    }
  }

  /** @param button one of the {@code Gamepad.BUTTON_*} constants */
  public void gamepadButton(int pad, int button, boolean down) {
    if (pad >= 0 && pad < MAX_GAMEPADS) {
      this.gamepads[pad].setButton(button, down);
    }
  }

  /** @param axis one of the {@code Gamepad.AXIS_*} constants; the raw value (-1..1, triggers 0..1) */
  public void gamepadAxis(int pad, int axis, float value) {
    if (pad < 0 || pad >= MAX_GAMEPADS) {
      return;
    }
    final var g = this.gamepads[pad];
    g.setAxis(axis, value);
    for (int i = 0; i < this.axisBindings.size(); i++) { // index loops: no iterator garbage
      final var b = this.axisBindings.get(i);
      if (b.axis == axis) {
        final float v = g.axis(axis);
        final boolean down = b.threshold >= 0f ? v >= b.threshold : v <= b.threshold;
        if (down && !b.down[pad]) {
          b.pressed = true;
        }
        b.down[pad] = down;
      }
    }
  }

  /** The gamepad slot (connected or not). */
  public GamepadState gamepad(int pad) {
    return this.gamepads[pad];
  }

  /** The first connected gamepad, or null if there is none. */
  public GamepadState firstGamepad() {
    for (GamepadState g : this.gamepads) {
      if (g.isConnected()) {
        return g;
      }
    }
    return null;
  }

  public void releaseAll() {
    this.keysDown.clear();
    for (int b = 1; b <= MAX_BUTTONS; b++) {
      mouseButton(b, false);
    }
  }

  public boolean isKeyDown(short key) {
    return this.keysDown.contains(key);
  }

  /** True once per physical key press; cleared by {@link #endFrame()}. */
  public boolean wasKeyPressed(short key) {
    return this.keysPressedThisFrame.contains(key);
  }

  /** Binds an action to keys (replacing earlier key bindings of it); gamepad bindings stay. */
  public void bind(String action, short... keys) {
    this.bindings.put(action, keys);
  }

  /** Binds an action to gamepad buttons ({@code Gamepad.BUTTON_*}) on any pad; key bindings stay. */
  public void bindGamepad(String action, int... buttons) {
    this.padBindings.put(action, buttons);
  }

  /**
   * Makes an action down while a gamepad axis (after its dead zone) is beyond {@code threshold}: a positive threshold
   * means pushed at least that far in the positive direction, a negative one in the negative direction. Adds to the
   * action's other bindings, so a stick can drive the same "forward" as W.
   */
  public void bindGamepadAxis(String action, int axis, float threshold) {
    this.axisBindings.add(new AxisBinding(action, axis, threshold));
  }

  public boolean isActionDown(String action) {
    final short[] keys = this.bindings.get(action);
    if (keys != null) {
      for (short k : keys) {
        if (isKeyDown(k)) {
          return true;
        }
      }
    }
    final int[] buttons = this.padBindings.get(action);
    if (buttons != null) {
      for (GamepadState g : this.gamepads) {
        for (int b : buttons) {
          if (g.isButtonDown(b)) {
            return true;
          }
        }
      }
    }
    for (int i = 0; i < this.axisBindings.size(); i++) {
      final var b = this.axisBindings.get(i);
      if (b.action.equals(action)) {
        for (boolean d : b.down) {
          if (d) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public boolean wasActionPressed(String action) {
    final short[] keys = this.bindings.get(action);
    if (keys != null) {
      for (short k : keys) {
        if (wasKeyPressed(k)) {
          return true;
        }
      }
    }
    final int[] buttons = this.padBindings.get(action);
    if (buttons != null) {
      for (GamepadState g : this.gamepads) {
        for (int b : buttons) {
          if (g.wasButtonPressed(b)) {
            return true;
          }
        }
      }
    }
    for (int i = 0; i < this.axisBindings.size(); i++) {
      final var b = this.axisBindings.get(i);
      if (b.pressed && b.action.equals(action)) {
        return true;
      }
    }
    return false;
  }

  /** Mouse movement since the last call, in pixels; resets the accumulator. */
  public int consumeMouseDx() {
    return this.mouseDx.getAndSet(0);
  }

  public int consumeMouseDy() {
    return this.mouseDy.getAndSet(0);
  }

  public void endFrame() {
    this.keysPressedThisFrame.clear();
    for (GamepadState g : this.gamepads) {
      g.endFrame();
    }
    for (int i = 0; i < this.axisBindings.size(); i++) {
      final var b = this.axisBindings.get(i);
      b.pressed = false;
    }
  }
}
