package engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class GameStatesTest {
  private final List<String> log = new ArrayList<>();
  private final Engine engine = new Engine(new EngineConfig("Test", "1", 8, 8, 1 / 60.0), e -> {
  });
  private final GameStates states = this.engine.states();

  private final class Recording implements GameState {
    final String name;
    final boolean simulates;

    Recording(String name, boolean simulates) {
      this.name = name;
      this.simulates = simulates;
    }

    @Override
    public void enter(Engine e) {
      GameStatesTest.this.log.add(this.name + ".enter");
    }

    @Override
    public void exit(Engine e) {
      GameStatesTest.this.log.add(this.name + ".exit");
    }

    @Override
    public void obscured(Engine e) {
      GameStatesTest.this.log.add(this.name + ".obscured");
    }

    @Override
    public void revealed(Engine e) {
      GameStatesTest.this.log.add(this.name + ".revealed");
    }

    @Override
    public void update(Engine e, double dt) {
      GameStatesTest.this.log.add(this.name + ".update");
    }

    @Override
    public boolean simulates() {
      return this.simulates;
    }
  }

  @Test
  void changesWaitForTheNextStepAndRunInOrder() {
    final var level = new Recording("level", true);
    final var pause = new Recording("pause", false);
    this.states.push(level);
    assertNull(this.states.current(), "nothing happens before the step");
    assertTrue(this.states.hasPendingChanges());
    this.states.applyPending();
    this.states.push(pause);
    this.states.applyPending();
    this.states.update(0.1);
    this.states.pop();
    this.states.applyPending();
    this.states.update(0.1);
    assertEquals(List.of("level.enter", "level.obscured", "pause.enter", "pause.update", "pause.exit",
        "level.revealed", "level.update"), this.log);
    assertSame(level, this.states.current());
  }

  @Test
  void theTopStateDecidesWhetherTheSceneSimulates() {
    assertTrue(this.states.simulates(), "no state: simulate");
    assertFalse(this.engine.isPaused());
    this.states.push(new Recording("level", true));
    this.states.push(new Recording("pause", false));
    this.states.applyPending();
    assertTrue(this.engine.isPaused());
    this.states.pop();
    this.states.applyPending();
    assertFalse(this.engine.isPaused());
    this.engine.setPaused(true);
    assertTrue(this.engine.isPaused());
  }

  @Test
  void replaceAndClearExitTheStatesTheyRemove() {
    this.states.push(new Recording("title", true));
    this.states.replace(new Recording("level1", true));
    this.states.replace(new Recording("level2", true));
    this.states.push(new Recording("pause", false));
    this.states.applyPending();
    assertEquals(2, this.states.states().size());
    this.states.clear();
    this.states.applyPending();
    assertNull(this.states.current());
    assertEquals(List.of("title.enter", "title.exit", "level1.enter", "level1.exit", "level2.enter",
        "level2.obscured", "pause.enter", "pause.exit", "level2.exit"), this.log);
  }

  @Test
  void changesRequestedWhileEnteringRunInTheSameRound() {
    this.states.push(new GameState() {
      @Override
      public void enter(Engine e) {
        e.states().replace(new Recording("menu", true)); // a splash that goes straight on
      }
    });
    this.states.applyPending();
    assertEquals(List.of("menu.enter"), this.log);
    assertFalse(this.states.hasPendingChanges());
  }

  @Test
  void popOnAnEmptyStackIsIgnored() {
    this.states.pop();
    this.states.applyPending();
    assertNull(this.states.current());
  }
}
