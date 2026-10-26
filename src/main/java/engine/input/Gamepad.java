package engine.input;

import org.joml.Vector2f;

/**
 * The standard gamepad layout (Xbox naming; on other pads A is the bottom face button, B the right one and so on) and
 * the dead-zone helpers. Stick axes are -1..1 with +X right and +Y <em>up</em> (pushed away from the player); triggers
 * are 0..1.
 */
public final class Gamepad {
  public static final int BUTTON_A = 0;
  public static final int BUTTON_B = 1;
  public static final int BUTTON_X = 2;
  public static final int BUTTON_Y = 3;
  public static final int BUTTON_LEFT_BUMPER = 4;
  public static final int BUTTON_RIGHT_BUMPER = 5;
  public static final int BUTTON_BACK = 6;
  public static final int BUTTON_START = 7;
  public static final int BUTTON_LEFT_STICK = 8;
  public static final int BUTTON_RIGHT_STICK = 9;
  public static final int BUTTON_GUIDE = 10;
  public static final int BUTTON_DPAD_UP = 11;
  public static final int BUTTON_DPAD_DOWN = 12;
  public static final int BUTTON_DPAD_LEFT = 13;
  public static final int BUTTON_DPAD_RIGHT = 14;
  public static final int BUTTON_COUNT = 15;

  public static final int AXIS_LEFT_X = 0;
  public static final int AXIS_LEFT_Y = 1;
  public static final int AXIS_RIGHT_X = 2;
  public static final int AXIS_RIGHT_Y = 3;
  public static final int AXIS_LEFT_TRIGGER = 4;
  public static final int AXIS_RIGHT_TRIGGER = 5;
  public static final int AXIS_COUNT = 6;

  private Gamepad() {
  }

  /**
   * Radial dead zone for a stick: inside {@code inner} the result is zero, beyond {@code outer} it has length 1, in
   * between the length is rescaled linearly so the stick starts smoothly from zero. The direction is kept, so
   * diagonals are not snapped to the axes (as they would be with a per-axis dead zone).
   */
  public static Vector2f radialDeadZone(float x, float y, float inner, float outer, Vector2f dest) {
    final float len = (float) Math.sqrt(x * x + y * y);
    if (len <= inner || len == 0f) {
      return dest.zero();
    }
    final float scaled = outer > inner ? Math.min(1f, (len - inner) / (outer - inner)) : 1f;
    return dest.set(x / len * scaled, y / len * scaled);
  }

  /** Dead zone for one axis (a trigger, or one direction of a stick): same rescaling as {@link #radialDeadZone}. */
  public static float axialDeadZone(float v, float inner, float outer) {
    final float a = Math.abs(v);
    if (a <= inner) {
      return 0f;
    }
    final float scaled = outer > inner ? Math.min(1f, (a - inner) / (outer - inner)) : 1f;
    return Math.copySign(scaled, v);
  }

  public static boolean isTrigger(int axis) {
    return axis == AXIS_LEFT_TRIGGER || axis == AXIS_RIGHT_TRIGGER;
  }
}
