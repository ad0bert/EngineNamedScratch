package engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FixedTimestepTest {
  private static final long MS = 1_000_000L;

  @Test
  void firstCallOnlyStartsTheClock() {
    final var t = new FixedTimestep(0.01, 0.25);
    assertEquals(0, t.advance(5_000 * MS, dt -> {
    }));
  }

  @Test
  void runsWholeStepsAndKeepsTheRemainder() {
    final var t = new FixedTimestep(0.01, 0.25);
    t.advance(0, dt -> {
    });
    assertEquals(2, t.advance(25 * MS, dt -> assertEquals(0.01, dt, 1e-12))); // 25ms -> 2 steps, 5ms left
    assertEquals(1, t.advance(30 * MS, dt -> {
    })); // +5ms -> 10ms -> 1 step
    assertEquals(0.03, t.elapsed(), 1e-9);
  }

  @Test
  void longStallsAreClamped() {
    final var t = new FixedTimestep(0.01, 0.25);
    t.advance(0, dt -> {
    });
    assertEquals(25, t.advance(60_000 * MS, dt -> {
    }));
  }
}
