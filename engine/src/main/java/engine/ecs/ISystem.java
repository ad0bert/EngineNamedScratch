package engine.ecs;

public interface ISystem {
  /** Fixed-step update; {@code dt} is in seconds. */
  void update(Scene scene, double dt);

  /**
   * Whether this system keeps running while the simulation is paused (a pause menu is open). Physics, animation and
   * component updates stop; debug displays and the like keep going.
   */
  default boolean runsWhilePaused() {
    return false;
  }
}
