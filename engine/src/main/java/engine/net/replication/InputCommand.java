package engine.net.replication;

import engine.net.BitReader;
import engine.net.BitWriter;

/**
 * A player's input for one step: how it wants to move and look, and which buttons are down. Clients send these to the
 * host instead of positions; the host and the client's own prediction both feed them to a {@link CharacterMotor}.
 * Values are rounded to what the network carries ({@link #quantized()}) before they are used anywhere, so the host and
 * the client compute exactly the same movement.
 *
 * @param tick     the host step it is for
 * @param moveX    sideways, -1 (left) to 1 (right)
 * @param moveZ    forward, -1 (back) to 1 (forward)
 * @param yaw      turn about the world up axis in degrees (0 looks along -Z)
 * @param pitch    look up (+) or down (-) in degrees, -89..89
 * @param buttons  bit {@link #JUMP} and up to seven game buttons ({@link #button(int)})
 * @param viewTick the host step the player was seeing (its interpolated copies), for lag compensation; -1 if unknown
 */
public record InputCommand(long tick, float moveX, float moveZ, float yaw, float pitch, int buttons,
    double viewTick) {
  /** Bit of the jump button. */
  public static final int JUMP = 1;
  /** No input. */
  public static final InputCommand NONE = new InputCommand(0, 0f, 0f, 0f, 0f, 0, -1);
  /** Moves are sent as whole numbers -127..127, so that 0 (standing still) stays exactly 0. */
  static final int MOVE_STEPS = 127;
  static final int YAW_BITS = 16;
  static final int PITCH_BITS = 14;
  static final float MAX_VIEW_LAG = 255f;

  /** The bit of game button {@code index} (0..6). */
  public static int button(int index) {
    if (index < 0 || index > 6) {
      throw new IllegalArgumentException("game buttons are 0..6");
    }
    return 2 << index;
  }

  public boolean isDown(int buttonBits) {
    return (this.buttons & buttonBits) == buttonBits;
  }

  public InputCommand withTick(long newTick) {
    return new InputCommand(newTick, this.moveX, this.moveZ, this.yaw, this.pitch, this.buttons,
        this.viewTick < 0 ? -1 : this.viewTick + (newTick - this.tick));
  }

  /** The same command as it arrives after a trip over the network. */
  public InputCommand quantized() {
    final var w = new BitWriter(16);
    writeBody(w);
    return readBody(new BitReader(w.toByteArray()), this.tick);
  }

  void writeBody(BitWriter out) {
    out.writeInt(move(this.moveX), -MOVE_STEPS, MOVE_STEPS)
        .writeInt(move(this.moveZ), -MOVE_STEPS, MOVE_STEPS)
        .writeFloat(wrap(this.yaw), 0f, 360f, YAW_BITS)
        .writeFloat(this.pitch, -89f, 89f, PITCH_BITS)
        .writeBits(this.buttons & 0xFF, 8);
    final boolean hasView = this.viewTick >= 0;
    out.writeBoolean(hasView);
    if (hasView) {
      out.writeFloat((float) (this.tick - this.viewTick), 0f, MAX_VIEW_LAG, 16);
    }
  }

  static InputCommand readBody(BitReader in, long tick) {
    final float moveX = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
    final float moveZ = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
    float yaw = in.readFloat(0f, 360f, YAW_BITS);
    final float pitch = in.readFloat(-89f, 89f, PITCH_BITS);
    final int buttons = (int) in.readBits(8);
    final double view = in.readBoolean() ? tick - in.readFloat(0f, MAX_VIEW_LAG, 16) : -1;
    return new InputCommand(tick, moveX, moveZ, yaw, pitch, buttons, view);
  }

  private static int move(float value) {
    return Float.isNaN(value) ? 0 : Math.round(Math.clamp(value, -1f, 1f) * MOVE_STEPS);
  }

  private static float wrap(float degrees) {
    final float d = degrees % 360f;
    return d < 0 ? d + 360f : d;
  }
}
