package engine.net.replication;

/**
 * Where a player's {@link InputCommand}s come from: {@link ActionCommandSource} reads the keyboard, mouse and gamepad;
 * tests and bots supply their own. Called once per step, on the update thread.
 */
@FunctionalInterface
public interface CommandSource {
  /** The input for host step {@code tick}; {@code dt} is the step's length in seconds. */
  InputCommand sample(long tick, double dt);
}
