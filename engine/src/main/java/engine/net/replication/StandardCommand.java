package engine.net.replication;

import org.joml.Quaternionf;

import engine.net.BitReader;
import engine.net.BitWriter;

/**
 * The engine's ready-made {@link InputCommand}: how a player wants to move on the ground plane, where it looks (yaw
 * and pitch) and up to eight buttons. {@link ActionCommandSource} makes them from the input bindings,
 * {@link CharacterMotor} walks with them. It is the replication's default command type ({@link #CODEC}); games with
 * other input (flying with a free orientation, steering, clicking on units) bring their own.
 *
 * <p>
 * On the wire it takes 62 bits: each move as a whole number -127..127 (so that standing still stays exactly 0), yaw in
 * 16 bits, pitch in 14, the buttons in 8.
 *
 * @param tick     the host step it is for
 * @param moveX    sideways, -1 (left) to 1 (right)
 * @param moveZ    forward, -1 (back) to 1 (forward)
 * @param yaw      turn about the world up axis in degrees (0 looks along -Z)
 * @param pitch    look up (+) or down (-) in degrees, -89..89
 * @param buttons  up to eight buttons ({@link #button(int)}); what they mean is the game's business
 * @param viewTick the host step the player was seeing, for lag compensation; -1 if unknown
 */
public record StandardCommand(long tick, float moveX, float moveZ, float yaw, float pitch, int buttons,
    double viewTick) implements InputCommand {
  /** Moves are sent as whole numbers -127..127. */
  static final int MOVE_STEPS = 127;
  static final int YAW_BITS = 16;
  /** Bits of a pitch on the wire, also for games that send a pitch in their own {@link NetState}. */
  public static final int PITCH_BITS = 14;
  /** How many buttons a command carries. */
  public static final int BUTTONS = 8;

  /** Reads and writes standard commands; the replication's default. */
  public static final CommandCodec<StandardCommand> CODEC = new CommandCodec<>() {
    @Override
    public Class<StandardCommand> type() {
      return StandardCommand.class;
    }

    @Override
    public void write(StandardCommand c, BitWriter out) {
      out.writeInt(move(c.moveX), -MOVE_STEPS, MOVE_STEPS)
          .writeInt(move(c.moveZ), -MOVE_STEPS, MOVE_STEPS)
          .writeFloat(wrap(c.yaw), 0f, 360f, YAW_BITS)
          .writeFloat(c.pitch, -89f, 89f, PITCH_BITS)
          .writeBits(c.buttons & 0xFF, BUTTONS);
    }

    @Override
    public StandardCommand read(BitReader in, long tick, double viewTick) {
      final float moveX = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
      final float moveZ = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
      final float yaw = in.readFloat(0f, 360f, YAW_BITS);
      final float pitch = in.readFloat(-89f, 89f, PITCH_BITS);
      final int buttons = (int) in.readBits(BUTTONS);
      return new StandardCommand(tick, moveX, moveZ, yaw, pitch, buttons, viewTick);
    }

    @Override
    public StandardCommand idle(long tick) {
      return new StandardCommand(tick, 0f, 0f, 0f, 0f, 0, -1);
    }
  };

  /** The bit of button {@code index} (0..7). */
  public static int button(int index) {
    if (index < 0 || index >= BUTTONS) {
      throw new IllegalArgumentException("buttons are 0.." + (BUTTONS - 1));
    }
    return 1 << index;
  }

  @Override
  public StandardCommand at(long newTick, double newViewTick) {
    return new StandardCommand(newTick, this.moveX, this.moveZ, this.yaw, this.pitch, this.buttons, newViewTick);
  }

  /** The rotation of {@link #yaw} about the up axis followed by {@link #pitch} about the sideways one. */
  public Quaternionf orientation(Quaternionf dest) {
    return dest.identity().rotateY((float) Math.toRadians(this.yaw)).rotateX((float) Math.toRadians(this.pitch));
  }

  private static int move(float value) {
    return Float.isNaN(value) ? 0 : Math.round(Math.clamp(value, -1f, 1f) * MOVE_STEPS);
  }

  private static float wrap(float degrees) {
    final float d = degrees % 360f;
    return d < 0 ? d + 360f : d;
  }
}
