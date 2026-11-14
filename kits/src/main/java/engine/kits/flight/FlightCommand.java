package engine.kits.flight;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.Quantize;
import engine.net.replication.CommandCodec;
import engine.net.replication.InputCommand;

/**
 * A pilot's input for one step: pushes along the ship's three axes and the full orientation to fly in, so that ships
 * can loop and roll (no yaw and pitch can say "upside down"). Set {@link #CODEC} on the replication on the host and on
 * clients ({@code replication.setCommandCodec(FlightCommand.CODEC)}) and fly with a {@link FlightMotor}.
 *
 * @param tick     the host step it is for
 * @param moveX    sideways, -1 (left) to 1 (right)
 * @param moveY    down (-1) and up (1)
 * @param moveZ    back (-1) and forward (1)
 * @param aim      the orientation to fly in (looking along its -Z); copied, so it cannot change later
 * @param buttons  up to eight buttons ({@link #button(int)})
 * @param viewTick the host step the pilot was seeing, for lag compensation; -1 if unknown
 */
public record FlightCommand(long tick, float moveX, float moveY, float moveZ, Quaternionfc aim, int buttons,
    double viewTick) implements InputCommand {
  private static final int MOVE_STEPS = 127;

  /** Reads and writes flight commands: three moves of 8 bits, the orientation in 47, the buttons in 8. */
  public static final CommandCodec<FlightCommand> CODEC = new CommandCodec<>() {
    @Override
    public Class<FlightCommand> type() {
      return FlightCommand.class;
    }

    @Override
    public void write(FlightCommand c, BitWriter out) {
      out.writeInt(move(c.moveX), -MOVE_STEPS, MOVE_STEPS)
          .writeInt(move(c.moveY), -MOVE_STEPS, MOVE_STEPS)
          .writeInt(move(c.moveZ), -MOVE_STEPS, MOVE_STEPS)
          .writeBits(Quantize.rotation(c.aim), Quantize.ROTATION_BITS)
          .writeBits(c.buttons & 0xFF, 8);
    }

    @Override
    public FlightCommand read(BitReader in, long tick, double viewTick) {
      final float x = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
      final float y = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
      final float z = in.readInt(-MOVE_STEPS, MOVE_STEPS) / (float) MOVE_STEPS;
      final var aim = Quantize.rotation(in.readBits(Quantize.ROTATION_BITS), new Quaternionf());
      return new FlightCommand(tick, x, y, z, aim, (int) in.readBits(8), viewTick);
    }

    @Override
    public FlightCommand idle(long tick) {
      return new FlightCommand(tick, 0f, 0f, 0f, new Quaternionf(), 0, -1);
    }
  };

  public FlightCommand {
    aim = aim == null ? new Quaternionf() : new Quaternionf(aim); // a copy: commands are values
  }

  /** A command for host step {@code tick} with no view (the replication fills it in). */
  public FlightCommand(long tick, float moveX, float moveY, float moveZ, Quaternionfc aim, int buttons) {
    this(tick, moveX, moveY, moveZ, aim, buttons, -1);
  }

  /** The bit of button {@code index} (0..7). */
  public static int button(int index) {
    if (index < 0 || index > 7) {
      throw new IllegalArgumentException("buttons are 0..7");
    }
    return 1 << index;
  }

  @Override
  public FlightCommand at(long newTick, double newViewTick) {
    return new FlightCommand(newTick, this.moveX, this.moveY, this.moveZ, this.aim, this.buttons, newViewTick);
  }

  private static int move(float value) {
    return Float.isNaN(value) ? 0 : Math.round(Math.clamp(value, -1f, 1f) * MOVE_STEPS);
  }
}
