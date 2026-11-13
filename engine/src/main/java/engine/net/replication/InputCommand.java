package engine.net.replication;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.Quantize;

/**
 * A player's input for one step: how it wants to move and look, and which buttons are down. Clients send these to the
 * host instead of positions; the host and the client's own prediction both feed them to the player's
 * {@link PredictedMotor} (a {@link CharacterMotor} for walking, a {@link FlightMotor} for flying). Values are rounded
 * to what the network carries ({@link #quantized()}) before they are used anywhere, so the host and the client compute
 * exactly the same movement.
 *
 * <p>
 * Walking characters look with {@code yaw} and {@code pitch}. Things that turn freely (ships, which can loop and roll)
 * send their whole orientation as {@code aim} instead; {@code moveY} pushes up and down. Both cost nothing on the wire
 * while unused.
 *
 * @param tick     the host step it is for
 * @param moveX    sideways, -1 (left) to 1 (right)
 * @param moveY    up and down, -1 (down) to 1 (up)
 * @param moveZ    forward, -1 (back) to 1 (forward)
 * @param yaw      turn about the world up axis in degrees (0 looks along -Z)
 * @param pitch    look up (+) or down (-) in degrees, -89..89
 * @param aim      the full orientation to look in (looking along its -Z), or null to use yaw and pitch; do not change
 *                 it after making the command
 * @param buttons  bit {@link #JUMP} and up to seven game buttons ({@link #button(int)})
 * @param viewTick the host step the player was seeing (its interpolated copies), for lag compensation; -1 if unknown
 */
public record InputCommand(long tick, float moveX, float moveY, float moveZ, float yaw, float pitch, Quaternionfc aim,
    int buttons, double viewTick) {
  /** Bit of the jump button. */
  public static final int JUMP = 1;
  /** No input. */
  public static final InputCommand NONE = new InputCommand(0, 0f, 0f, 0f, 0f, 0f, null, 0, -1);
  /** Moves are sent as whole numbers -127..127, so that 0 (standing still) stays exactly 0. */
  static final int MOVE_STEPS = 127;
  static final int YAW_BITS = 16;
  static final int PITCH_BITS = 14;
  static final float MAX_VIEW_LAG = 255f;

  public InputCommand {
    if (aim != null) {
      aim = new Quaternionf(aim); // a copy: commands are values
    }
  }

  /** A walking command: no up and down, no free orientation. */
  public InputCommand(long tick, float moveX, float moveZ, float yaw, float pitch, int buttons, double viewTick) {
    this(tick, moveX, 0f, moveZ, yaw, pitch, null, buttons, viewTick);
  }

  /** A flying command: pushes on three axes and looks along {@code aim}'s -Z. */
  public static InputCommand flying(long tick, float moveX, float moveY, float moveZ, Quaternionfc aim, int buttons) {
    return new InputCommand(tick, moveX, moveY, moveZ, 0f, 0f, aim, buttons, -1);
  }

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

  /**
   * Where the command looks: {@link #aim} if it has one, else the rotation of {@link #yaw} about the up axis followed
   * by {@link #pitch} about the sideways one.
   */
  public Quaternionf orientation(Quaternionf dest) {
    if (this.aim != null) {
      return dest.set(this.aim);
    }
    return dest.identity().rotateY((float) Math.toRadians(this.yaw)).rotateX((float) Math.toRadians(this.pitch));
  }

  public InputCommand withTick(long newTick) {
    return new InputCommand(newTick, this.moveX, this.moveY, this.moveZ, this.yaw, this.pitch, this.aim, this.buttons,
        this.viewTick < 0 ? -1 : this.viewTick + (newTick - this.tick));
  }

  /** The same command for another step, seeing host step {@code newViewTick}. */
  public InputCommand withTickAndView(long newTick, double newViewTick) {
    return new InputCommand(newTick, this.moveX, this.moveY, this.moveZ, this.yaw, this.pitch, this.aim, this.buttons,
        newViewTick);
  }

  /** The same command as it arrives after a trip over the network. */
  public InputCommand quantized() {
    final var w = new BitWriter(24);
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
    final int y = move(this.moveY);
    out.writeBoolean(y != 0);
    if (y != 0) {
      out.writeInt(y, -MOVE_STEPS, MOVE_STEPS);
    }
    out.writeBoolean(this.aim != null);
    if (this.aim != null) {
      out.writeBits(Quantize.rotation(this.aim), Quantize.ROTATION_BITS);
    }
  }

  static InputCommand readBody(BitReader in, long tick) {
    final float moveX = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
    final float moveZ = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
    final float yaw = in.readFloat(0f, 360f, YAW_BITS);
    final float pitch = in.readFloat(-89f, 89f, PITCH_BITS);
    final int buttons = (int) in.readBits(8);
    final double view = in.readBoolean() ? tick - in.readFloat(0f, MAX_VIEW_LAG, 16) : -1;
    final float moveY = in.readBoolean() ? in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS : 0f;
    final Quaternionf aim = in.readBoolean()
        ? Quantize.rotation(in.readBits(Quantize.ROTATION_BITS), new Quaternionf())
        : null;
    return new InputCommand(tick, moveX, moveY, moveZ, yaw, pitch, aim, buttons, view);
  }

  private static int move(float value) {
    return Float.isNaN(value) ? 0 : Math.round(Math.clamp(value, -1f, 1f) * MOVE_STEPS);
  }

  private static float wrap(float degrees) {
    final float d = degrees % 360f;
    return d < 0 ? d + 360f : d;
  }
}
