package engine.ecs;

public interface ISystem {
  /** Fixed-step update; {@code dt} is in seconds. */
  void update(Scene scene, double dt);
}
