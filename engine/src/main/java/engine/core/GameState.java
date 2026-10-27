package engine.core;

import engine.render.UiCanvas;

/**
 * One screen or mode of a game: a title menu, a level, a pause menu, a loading screen. The engine keeps them on a
 * stack ({@link GameStates}): the top state gets updates, every state draws (bottom first, so a pause menu covers the
 * level's HUD), and the top state decides whether the scene keeps simulating.
 *
 * <p>
 * All methods run on the engine thread, at the start of a fixed step (transitions) or during it (updates).
 */
public interface GameState {
  /** Pushed onto the stack (or replaced another state): build what this state shows. */
  default void enter(Engine engine) {
  }

  /** Popped or replaced: tear down what {@link #enter} built. */
  default void exit(Engine engine) {
  }

  /** Another state was pushed on top of this one. */
  default void obscured(Engine engine) {
  }

  /** The state on top of this one was popped: this one is on top again. */
  default void revealed(Engine engine) {
  }

  /** Called on every fixed step while this is the top state, after the engine's systems. */
  default void update(Engine engine, double dt) {
  }

  /** 2D content (HUD, menus) drawn every frame while the state is on the stack. */
  default void draw(Engine engine, UiCanvas ui, int width, int height) {
  }

  /**
   * Whether the scene simulates (physics, animation, component updates) while this is the top state. A pause menu
   * returns false.
   */
  default boolean simulates() {
    return true;
  }
}
