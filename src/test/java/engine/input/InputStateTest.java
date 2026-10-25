package engine.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;

class InputStateTest {

  @Test
  void bindingsAndPressedFlags() {
    final var s = new InputState();
    s.bind("jump", (short) 10, (short) 11);
    assertFalse(s.isActionDown("jump"));
    s.keyDown((short) 11);
    assertTrue(s.isActionDown("jump"));
    assertTrue(s.wasActionPressed("jump"));
    s.endFrame();
    assertFalse(s.wasActionPressed("jump"));
    assertTrue(s.isActionDown("jump"));
    s.keyUp((short) 11);
    assertFalse(s.isActionDown("jump"));
    assertFalse(s.isActionDown("unbound"));
  }

  @Test
  void mouseDeltaIsConsumed() {
    final var s = new InputState();
    s.mouseMoved(3, -2);
    s.mouseMoved(1, 1);
    assertEquals(4, s.consumeMouseDx());
    assertEquals(-1, s.consumeMouseDy());
    assertEquals(0, s.consumeMouseDx());
  }

  @Test
  void controllerLooksAndMoves() {
    final var s = new InputState();
    s.bind("forward", (short) 1);
    final var e = new Entity();
    final var c = e.add(new FirstPersonController(s));
    c.speed = 2f;

    s.mouseMoved(-(int) (90 / c.sensitivityDegPerPixel), 0); // look left by 90 degrees
    s.keyDown((short) 1);
    c.update(1.0);

    final var f = e.transform().forward(new Vector3f());
    assertEquals(-1f, f.x, 1e-3);
    assertEquals(-2f, e.transform().position().x, 1e-3);
  }

  @Test
  void pitchIsClamped() {
    final var s = new InputState();
    final var c = new Entity().add(new FirstPersonController(s));
    s.mouseMoved(0, -100_000);
    c.update(0.01);
    assertEquals(89f, c.pitch(), 1e-4);
  }
}
