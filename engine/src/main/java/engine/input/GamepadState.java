package engine.input;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.joml.Vector2f;

/**
 * One gamepad slot of {@link InputState}: connection, buttons (with presses since the last frame) and axes. Raw axis
 * values are stored as reported; the readers apply the dead zones. Thread-safe like the rest of the input state.
 */
public class GamepadState {
  /** Stick motion inside this radius is ignored (worn sticks rarely rest exactly at the centre). */
  public volatile float stickDeadZone = 0.2f;
  /** Trigger travel below this is ignored. */
  public volatile float triggerDeadZone = 0.05f;
  /** Values beyond this count as fully pushed. */
  public volatile float outerDeadZone = 0.95f;

  private volatile boolean connected;
  private volatile String name = "";
  private final AtomicIntegerArray buttons = new AtomicIntegerArray(Gamepad.BUTTON_COUNT);
  private final AtomicInteger pressed = new AtomicInteger(); // bit per button, cleared by endFrame
  private final AtomicIntegerArray presses = new AtomicIntegerArray(Gamepad.BUTTON_COUNT);
  private final AtomicIntegerArray axes = new AtomicIntegerArray(Gamepad.AXIS_COUNT); // float bits
  private final AtomicInteger connections = new AtomicInteger();
  private volatile boolean canRumble;
  private volatile float rumbleStrong;
  private volatile float rumbleWeak;
  private volatile long rumbleUntil;

  void setConnected(boolean connected, String name) {
    if (connected && !this.connected) {
      this.connections.incrementAndGet();
    }
    this.connected = connected;
    this.name = name != null ? name : "";
    if (!connected) {
      this.canRumble = false;
      this.rumbleUntil = 0L;
      for (int b = 0; b < Gamepad.BUTTON_COUNT; b++) {
        this.buttons.set(b, 0);
      }
      for (int a = 0; a < Gamepad.AXIS_COUNT; a++) {
        this.axes.set(a, 0);
      }
    }
  }

  void setButton(int button, boolean down) {
    if (button < 0 || button >= Gamepad.BUTTON_COUNT) {
      return;
    }
    if (this.buttons.getAndSet(button, down ? 1 : 0) == 0 && down) {
      this.presses.incrementAndGet(button);
      this.pressed.getAndUpdate(bits -> bits | 1 << button);
    }
  }

  void setAxis(int axis, float value) {
    if (axis >= 0 && axis < Gamepad.AXIS_COUNT) {
      this.axes.set(axis, Float.floatToIntBits(value));
    }
  }

  void endFrame() {
    this.pressed.set(0);
  }

  /**
   * Shakes the pad for {@code seconds}: {@code strong} drives the heavy (low-frequency) motor, {@code weak} the light
   * one, each 0..1. A new call replaces the one running; pads without motors ignore it.
   */
  public void rumble(float strong, float weak, float seconds) {
    this.rumbleStrong = Math.max(0f, Math.min(1f, strong));
    this.rumbleWeak = Math.max(0f, Math.min(1f, weak));
    this.rumbleUntil = System.nanoTime() + (long) (Math.max(0f, seconds) * 1e9);
  }

  public void stopRumble() {
    this.rumbleUntil = 0L;
  }

  /** The strong motor's level the backend should apply at {@code nanoTime} (0 once the rumble is over). */
  public float rumbleStrong(long nanoTime) {
    return nanoTime < this.rumbleUntil ? this.rumbleStrong : 0f;
  }

  public float rumbleWeak(long nanoTime) {
    return nanoTime < this.rumbleUntil ? this.rumbleWeak : 0f;
  }

  /** True if the backend found rumble motors on this pad. */
  public boolean canRumble() {
    return this.canRumble;
  }

  void setCanRumble(boolean value) {
    this.canRumble = value;
  }

  public boolean isConnected() {
    return this.connected;
  }

  public String name() {
    return this.name;
  }

  /** How often a pad was connected to this slot; compare with an earlier value to notice a (re)connection. */
  public int connections() {
    return this.connections.get();
  }

  public boolean isButtonDown(int button) {
    return button >= 0 && button < Gamepad.BUTTON_COUNT && this.buttons.get(button) == 1;
  }

  /** True once per press, until the end of the frame. */
  public boolean wasButtonPressed(int button) {
    return button >= 0 && button < Gamepad.BUTTON_COUNT && (this.pressed.get() & 1 << button) != 0;
  }

  /** How often the button went down since the start. */
  public int buttonPresses(int button) {
    return button >= 0 && button < Gamepad.BUTTON_COUNT ? this.presses.get(button) : 0;
  }

  /** The axis as reported, without dead zone. */
  public float rawAxis(int axis) {
    return axis >= 0 && axis < Gamepad.AXIS_COUNT ? Float.intBitsToFloat(this.axes.get(axis)) : 0f;
  }

  /**
   * One axis with its dead zone: triggers use {@link #triggerDeadZone}; a stick axis uses the stick's radial dead
   * zone (so it matches {@link #leftStick}/{@link #rightStick}).
   */
  public float axis(int axis) {
    if (Gamepad.isTrigger(axis)) {
      return Gamepad.axialDeadZone(rawAxis(axis), this.triggerDeadZone, this.outerDeadZone);
    }
    final int x = axis & ~1; // the stick's x axis (0 or 2)
    final var v = Gamepad.radialDeadZone(rawAxis(x), rawAxis(x + 1), this.stickDeadZone, this.outerDeadZone,
        SCRATCH.get());
    return (axis & 1) == 0 ? v.x : v.y;
  }

  public Vector2f leftStick(Vector2f dest) {
    return Gamepad.radialDeadZone(rawAxis(Gamepad.AXIS_LEFT_X), rawAxis(Gamepad.AXIS_LEFT_Y), this.stickDeadZone,
        this.outerDeadZone, dest);
  }

  public Vector2f rightStick(Vector2f dest) {
    return Gamepad.radialDeadZone(rawAxis(Gamepad.AXIS_RIGHT_X), rawAxis(Gamepad.AXIS_RIGHT_Y), this.stickDeadZone,
        this.outerDeadZone, dest);
  }

  private static final ThreadLocal<Vector2f> SCRATCH = ThreadLocal.withInitial(Vector2f::new);
}
