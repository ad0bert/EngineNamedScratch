package engine.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ProfilerTest {
  private static void spin(long nanos) {
    final long end = System.nanoTime() + nanos;
    while (System.nanoTime() < end) {
      Thread.onSpinWait();
    }
  }

  @Test
  void sumsSectionsPerFrameAndKeepsHistory() {
    final var p = new Profiler();
    p.endFrame(); // starts the first frame
    p.begin("physics");
    spin(2_000_000);
    p.end();
    p.begin("physics"); // a second fixed step in the same frame
    spin(1_000_000);
    p.end();
    p.begin("render");
    p.begin("render/shadows"); // nested: both count their own whole time
    spin(1_000_000);
    p.end();
    p.end();
    p.endFrame();

    assertEquals(List.of("physics", "render", "render/shadows"), p.sections());
    assertTrue(p.ms("physics", 0) >= 3f && p.ms("physics", 0) < 30f, "" + p.ms("physics", 0));
    assertTrue(p.ms("render", 0) >= p.ms("render/shadows", 0));
    assertTrue(p.frameMs(0) >= p.ms("physics", 0) + p.ms("render", 0));
    assertEquals(2, p.frames());

    p.endFrame(); // an empty frame
    assertEquals(0f, p.ms("physics", 0));
    assertEquals(p.ms("physics", 1) / 3f, p.average("physics", 3), 1e-4f);
    assertEquals(0f, p.average("unknown", 3));
  }

  @Test
  void wrapsRoundItsHistory() {
    final var p = new Profiler();
    final int id = p.section("work");
    for (int i = 0; i < Profiler.HISTORY + 10; i++) {
      p.add(id, i * 1_000_000L);
      p.endFrame();
    }
    assertEquals(Profiler.HISTORY, p.frames());
    assertEquals(Profiler.HISTORY + 9, p.ms("work", 0), 1e-3f);
    assertEquals(10, p.ms("work", Profiler.HISTORY - 1), 1e-3f);
  }

  @Test
  void unbalancedEndsAreCaughtAndDisabledCostsNothing() {
    final var p = new Profiler();
    assertThrows(IllegalStateException.class, p::end);
    p.enabled = false;
    p.begin("x");
    p.end();
    p.end();
    p.endFrame();
    assertEquals(0, p.frames());
  }

  @Test
  void theOverlayListsTheSlowestSections() {
    final var p = new Profiler();
    p.add(p.section("fast"), 1_000_000L);
    p.add(p.section("slow"), 5_000_000L);
    p.endFrame();
    final var text = DebugOverlay.profile(p);
    assertTrue(text.indexOf("slow") < text.indexOf("fast"), text);
    assertTrue(text.contains("5.00 ms"), text);
  }
}
