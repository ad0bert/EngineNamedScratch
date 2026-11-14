package engine.core;

import java.util.List;

/** Implemented by the game; the engine drives its lifecycle. */
public interface Game {
  /**
   * The plugins to install, in order, before {@link #init}: by default {@link EnginePlugins#defaults()}. A game that
   * wants less returns e.g. {@code EnginePlugins.defaultsWithout(DebugOverlayPlugin.class)} or {@code List.of()}; one
   * that wants more, {@code EnginePlugins.defaultsAnd(new MyPlugin())}.
   */
  default List<EnginePlugin> plugins() {
    return EnginePlugins.defaults();
  }

  /** Called on the GL thread once the context exists; build the scene and register systems here. */
  void init(Engine engine);

  /** Called after systems on every fixed step. Optional. */
  default void update(Engine engine, double dt) {
  }

  default void shutdown(Engine engine) {
  }
}
