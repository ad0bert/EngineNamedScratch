package engine.net.replication;

/**
 * A player's input for one host step. Clients send these to the host instead of positions; the host and the client's
 * own prediction both feed them to the player's {@link PredictedMotor}. What a command holds is up to the game: the
 * engine only needs the step it is for and the step the player was seeing. {@link StandardCommand} (move, look,
 * buttons) fits walking and most first- and third-person games; a game with other input writes its own record and a
 * {@link CommandCodec} for it, and sets that codec on the replication on the host and on clients
 * ({@link Replication#setCommandCodec}).
 *
 * <p>
 * Commands are values: do not change one after making it. Values are rounded to what the network carries
 * ({@link CommandCodec#overTheWire}) before they are used anywhere, so that the host and the client compute exactly the
 * same movement.
 */
public interface InputCommand {
  /** The host step it is for. */
  long tick();

  /** The host step the player was seeing (its interpolated copies), for lag compensation; -1 if unknown. */
  double viewTick();

  /** The same input for host step {@code tick}, seeing host step {@code viewTick} (-1: unknown). */
  InputCommand at(long tick, double viewTick);

  /** The buttons that are down, as bits; 0 for commands without buttons. */
  default int buttons() {
    return 0;
  }

  /** Whether all of {@code buttonBits} are down. */
  default boolean isDown(int buttonBits) {
    return (buttons() & buttonBits) == buttonBits;
  }

  /** The same input for another step: the view moves along by as many steps (it stays unknown if it was). */
  default InputCommand repeatedAt(long newTick) {
    final double view = viewTick();
    return at(newTick, view < 0 ? -1 : view + (newTick - tick()));
  }
}
