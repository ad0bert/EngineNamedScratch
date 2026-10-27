package engine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import engine.render.Overlay;
import engine.render.UiCanvas;
import lombok.extern.slf4j.Slf4j;

/**
 * The stack of {@link GameState}s. {@link #push}, {@link #pop}, {@link #replace} and {@link #clear} can be called at
 * any time on the engine thread; they take effect at the start of the next fixed step, in the order they were made, so
 * a state never ends in the middle of its own update. With no state on the stack the scene simply simulates, as for a
 * game that does not use states.
 */
@Slf4j
public class GameStates {
  private sealed interface Change {
  }

  private record Push(GameState state) implements Change {
  }

  private record Pop() implements Change {
  }

  private record Replace(GameState state) implements Change {
  }

  private record Clear() implements Change {
  }

  private final Engine engine;
  private final List<GameState> stack = new ArrayList<>();
  private final List<Change> pending = new ArrayList<>();

  GameStates(Engine engine) {
    this.engine = engine;
  }

  /** Puts a state on top of the current one (which is {@link GameState#obscured obscured}). */
  public void push(GameState state) {
    this.pending.add(new Push(state));
  }

  /** Removes the top state; the one below is {@link GameState#revealed revealed}. */
  public void pop() {
    this.pending.add(new Pop());
  }

  /** Exits the top state (if any) and enters this one in its place: from one level to the next. */
  public void replace(GameState state) {
    this.pending.add(new Replace(state));
  }

  /** Exits every state, top first: back to the title screen with {@code clear(); push(title)}. */
  public void clear() {
    this.pending.add(new Clear());
  }

  /** The top state, or null if the stack is empty (changes still pending are not counted). */
  public GameState current() {
    return this.stack.isEmpty() ? null : this.stack.getLast();
  }

  /** The states from bottom to top. */
  public List<GameState> states() {
    return Collections.unmodifiableList(this.stack);
  }

  public boolean hasPendingChanges() {
    return !this.pending.isEmpty();
  }

  /** Whether the scene simulates: the top state's choice, or true with no state. */
  public boolean simulates() {
    final var top = current();
    return top == null || top.simulates();
  }

  /** Applies the pending changes. Called by the engine at the start of every fixed step. */
  void applyPending() {
    // a state's enter or exit may ask for more changes: those are applied in the same round
    for (int i = 0; i < this.pending.size(); i++) {
      switch (this.pending.get(i)) {
      case Push(GameState state) -> {
        final var below = current();
        if (below != null) {
          below.obscured(this.engine);
        }
        this.stack.add(state);
        state.enter(this.engine);
      }
      case Pop() -> {
        if (this.stack.isEmpty()) {
          log.warn("pop() with no game state on the stack");
          continue;
        }
        this.stack.removeLast().exit(this.engine);
        final var revealed = current();
        if (revealed != null) {
          revealed.revealed(this.engine);
        }
      }
      case Replace(GameState state) -> {
        if (!this.stack.isEmpty()) {
          this.stack.removeLast().exit(this.engine);
        }
        this.stack.add(state);
        state.enter(this.engine);
      }
      case Clear() -> {
        while (!this.stack.isEmpty()) {
          this.stack.removeLast().exit(this.engine);
        }
      }
      }
    }
    this.pending.clear();
  }

  void update(double dt) {
    final var top = current();
    if (top != null) {
      top.update(this.engine, dt);
    }
  }

  /** Draws every state, bottom first. */
  Overlay overlay() {
    return (UiCanvas ui, int width, int height) -> {
      for (int i = 0; i < this.stack.size(); i++) {
        this.stack.get(i).draw(this.engine, ui, width, height);
      }
    };
  }

  /** Exits every state at shutdown. */
  void exitAll() {
    this.pending.clear();
    while (!this.stack.isEmpty()) {
      this.stack.removeLast().exit(this.engine);
    }
  }
}
