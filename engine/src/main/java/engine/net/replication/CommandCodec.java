package engine.net.replication;

import engine.net.BitReader;
import engine.net.BitWriter;

/**
 * How a game's {@link InputCommand}s go over the network. The replication writes the step and the view itself; a
 * codec writes and reads the rest. Set the same codec on the host and on clients ({@link Replication#setCommandCodec});
 * the default is {@link StandardCommand#CODEC}.
 *
 * <pre>{@code
 * record Steer(long tick, double viewTick, float wheel, float throttle) implements InputCommand {
 *   public Steer at(long t, double v) { return new Steer(t, v, wheel, throttle); }
 *   static final CommandCodec<Steer> CODEC = new CommandCodec<>() {
 *     public Class<Steer> type() { return Steer.class; }
 *     public void write(Steer c, BitWriter out) { out.writeFloat(c.wheel, -1, 1, 10).writeFloat(c.throttle, 0, 1, 8); }
 *     public Steer read(BitReader in, long t, double v) { return new Steer(t, v, in.readFloat(-1, 1, 10), in.readFloat(0, 1, 8)); }
 *     public Steer idle(long t) { return new Steer(t, -1, 0, 0); }
 *   };
 * }
 * }</pre>
 *
 * @param <C> the command type
 */
public interface CommandCodec<C extends InputCommand> {
  /** The commands this codec handles; others are refused. */
  Class<C> type();

  /** Writes everything of {@code command} but its {@code tick} and {@code viewTick}. */
  void write(C command, BitWriter out);

  /** Reads what {@link #write} wrote, for host step {@code tick} seeing {@code viewTick}. */
  C read(BitReader in, long tick, double viewTick);

  /** No input, for host step {@code tick}: what a player does before its first command arrives. */
  C idle(long tick);

  /** The command as it arrives after a trip over the network (rounded like the network rounds it). */
  default C overTheWire(C command) {
    final var out = new BitWriter(32);
    Replication.writeCommand(this, command, out);
    return type().cast(Replication.readCommand(this, new BitReader(out.toByteArray()), command.tick()));
  }
}
