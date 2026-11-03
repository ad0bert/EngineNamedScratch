package engine.core;

/**
 * Code that runs at fixed points of every update step, around the systems. Hooks are for things that have to happen
 * once per step at a well-defined moment whatever the systems do, such as networking: apply what was received before
 * the simulation runs, send what changed after it.
 *
 * <p>
 * One step is, in order: pending game state changes and scene adds/removes are applied, transforms remember their
 * previous state, posted messages are delivered, gamepads are polled, <b>{@link #beforeSystems}</b>, the systems, the
 * game's and the game states' update, <b>{@link #afterStep}</b>, then the step's input presses are cleared.
 *
 * <p>
 * Hooks run while the simulation is paused, too (the systems may not). They run on the update thread, in the order
 * they were added; a hook may add or remove hooks while they run.
 */
public interface StepHook {
  /**
   * Before the systems. {@code tick} is the number of the step being run ({@link Engine#tick()}), {@code dt} its
   * length in seconds.
   */
  default void beforeSystems(Engine engine, long tick, double dt) {
  }

  /**
   * After the systems and the game's update, while this step's input presses ({@code wasActionPressed}) can still be
   * read.
   */
  default void afterStep(Engine engine, long tick, double dt) {
  }
}
