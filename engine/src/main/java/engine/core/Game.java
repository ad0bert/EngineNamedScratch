package engine.core;

/** Implemented by the game; the engine drives its lifecycle. */
public interface Game {
  /** Called on the GL thread once the context exists; build the scene and register systems here. */
  void init(Engine engine);

  /** Called after systems on every fixed step. Optional. */
  default void update(Engine engine, double dt) {
  }

  default void shutdown(Engine engine) {
  }
}
