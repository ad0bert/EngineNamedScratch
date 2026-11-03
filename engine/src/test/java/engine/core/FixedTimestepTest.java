package engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void alphaIsTheFractionOfAStepLeftOver() {
    final var t = new FixedTimestep(0.01, 0.25);
    assertEquals(0f, t.alpha(), 0f);
    t.advance(0, dt -> {
    });
    t.advance(15 * MS, dt -> {
    }); // one step, 5ms left over
    assertEquals(0.5f, t.alpha(), 1e-4f);
    t.advance(15 * MS + 1, dt -> {
    });
    assertTrue(t.alpha() >= 0f && t.alpha() <= 1f);
  }

  @Test
  void longStallsAreClamped() {
    final var t = new FixedTimestep(0.01, 0.25);
    t.advance(0, dt -> {
    });
    assertEquals(25, t.advance(60_000 * MS, dt -> {
    }));
  }

  @Test
  void timeScaleChangesHowOftenStepsRunButNotTheirLength() {
    final var t = new FixedTimestep(0.01, 0.25);
    t.setTimeScale(2.0);
    t.advance(0, dt -> {
    });
    assertEquals(2, t.advance(10 * MS, dt -> assertEquals(0.01, dt, 1e-12))); // 10ms at double speed: 2 steps
    t.setTimeScale(0.5);
    assertEquals(0, t.advance(15 * MS, dt -> {
    })); // 5ms at half speed: 2.5ms of simulation
    assertEquals(1, t.advance(30 * MS, dt -> {
    })); // +15ms -> 7.5ms more: 10ms
  }

  @Test
  void timeScaleMustBePositive() {
    final var t = new FixedTimestep(0.01, 0.25);
    assertThrows(IllegalArgumentException.class, () -> t.setTimeScale(0));
    assertThrows(IllegalArgumentException.class, () -> t.setTimeScale(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> t.setTimeScale(Double.POSITIVE_INFINITY));
  }

  @Test
  void nanosUntilNextStepAccountsForTheScale() {
    final var t = new FixedTimestep(0.01, 0.25);
    t.advance(0, dt -> {
    });
    t.advance(4 * MS, dt -> {
    }); // 4ms of 10 in the accumulator
    assertEquals(6 * MS, t.nanosUntilNextStep(), 1000);
    t.setTimeScale(2.0);
    assertEquals(3 * MS, t.nanosUntilNextStep(), 1000);
  }
}
