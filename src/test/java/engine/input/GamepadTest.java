package engine.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;

class GamepadTest {

  @Test
  void radialDeadZoneKeepsDirectionAndRescales() {
    final var v = new Vector2f();
    Gamepad.radialDeadZone(0.1f, 0.1f, 0.2f, 1f, v);
    assertEquals(0f, v.length()); // inside the dead zone
    Gamepad.radialDeadZone(0.6f, 0f, 0.2f, 1f, v);
    assertEquals(0.5f, v.x, 1e-6f); // (0.6 - 0.2) / (1 - 0.2)
    Gamepad.radialDeadZone(0.5f, 0.5f, 0.2f, 0.6f, v);
    assertEquals(1f, v.length(), 1e-6f); // beyond the outer edge: full length
    assertEquals(v.x, v.y, 1e-6f); // diagonals stay diagonal
    // per axis, a diagonal with a small y would lose its y; radially it keeps it
    Gamepad.radialDeadZone(0.8f, 0.15f, 0.2f, 1f, v);
    assertTrue(v.y > 0f);
  }

  @Test
  void axialDeadZone() {
    assertEquals(0f, Gamepad.axialDeadZone(0.04f, 0.05f, 0.95f));
    assertEquals(0.5f, Gamepad.axialDeadZone(0.5f, 0.05f, 0.95f), 1e-6f);
    assertEquals(-1f, Gamepad.axialDeadZone(-1f, 0.05f, 0.95f));
  }

  @Test
  void connectionButtonsAndPresses() {
    final var s = new InputState();
    assertNull(s.firstGamepad());
    s.gamepadConnected(1, "Pad");
    assertSame(s.gamepad(1), s.firstGamepad());
    assertEquals("Pad", s.firstGamepad().name());
    assertEquals(1, s.gamepad(1).connections());
    s.gamepadButton(1, Gamepad.BUTTON_A, true);
    s.gamepadButton(1, Gamepad.BUTTON_A, true); // still held: not a second press
    assertTrue(s.gamepad(1).isButtonDown(Gamepad.BUTTON_A));
    assertTrue(s.gamepad(1).wasButtonPressed(Gamepad.BUTTON_A));
    assertEquals(1, s.gamepad(1).buttonPresses(Gamepad.BUTTON_A));
    s.endFrame();
    assertFalse(s.gamepad(1).wasButtonPressed(Gamepad.BUTTON_A));
    s.gamepadAxis(1, Gamepad.AXIS_LEFT_TRIGGER, 0.5f);
    assertEquals(0.5f, s.gamepad(1).axis(Gamepad.AXIS_LEFT_TRIGGER), 1e-6f);
    s.gamepadDisconnected(1);
    assertNull(s.firstGamepad());
    assertFalse(s.gamepad(1).isButtonDown(Gamepad.BUTTON_A)); // released with the pad
    assertEquals(0f, s.gamepad(1).rawAxis(Gamepad.AXIS_LEFT_TRIGGER));
  }

  @Test
  void bindingsCoexistWithKeys() {
    final var s = new InputState();
    s.bind("jump", (short) 32);
    s.bindGamepad("jump", Gamepad.BUTTON_A);
    s.bindGamepadAxis("forward", Gamepad.AXIS_LEFT_Y, 0.5f);
    s.bindGamepadAxis("back", Gamepad.AXIS_LEFT_Y, -0.5f);
    s.gamepadConnected(0, "Pad");

    s.keyDown((short) 32);
    assertTrue(s.isActionDown("jump"));
    s.keyUp((short) 32);
    s.endFrame();
    assertFalse(s.isActionDown("jump"));
    s.gamepadButton(0, Gamepad.BUTTON_A, true);
    assertTrue(s.isActionDown("jump"));
    assertTrue(s.wasActionPressed("jump"));
    s.endFrame();
    assertFalse(s.wasActionPressed("jump"));

    s.gamepadAxis(0, Gamepad.AXIS_LEFT_Y, 0.3f); // past the dead zone, below the threshold
    assertFalse(s.isActionDown("forward"));
    s.gamepadAxis(0, Gamepad.AXIS_LEFT_Y, 0.9f);
    assertTrue(s.isActionDown("forward"));
    assertTrue(s.wasActionPressed("forward"));
    assertFalse(s.isActionDown("back"));
    s.endFrame();
    s.gamepadAxis(0, Gamepad.AXIS_LEFT_Y, 0.95f);
    assertFalse(s.wasActionPressed("forward")); // held, not pressed again
    s.gamepadAxis(0, Gamepad.AXIS_LEFT_Y, -1f);
    assertTrue(s.isActionDown("back"));
    s.gamepadDisconnected(0);
    assertFalse(s.isActionDown("back"));
  }

  @Test
  void controllerUsesTheSticks() {
    final var s = new InputState();
    final var e = new Entity();
    final var c = e.add(new FirstPersonController(s));
    c.speed = 2f;
    s.gamepadConnected(0, "Pad");
    s.gamepadAxis(0, Gamepad.AXIS_LEFT_Y, 1f); // full forward
    c.update(1.0);
    final var p = e.transform().position();
    assertEquals(-2f, p.z, 1e-4f);
    s.gamepadAxis(0, Gamepad.AXIS_LEFT_Y, 0.6f); // pushed less: slower
    c.update(1.0);
    assertEquals(-2f - 2f * (0.6f - 0.2f) / (0.95f - 0.2f), p.z, 1e-3f); // rescaled past the dead zones
    s.gamepadAxis(0, Gamepad.AXIS_LEFT_Y, 0f);
    s.gamepadAxis(0, Gamepad.AXIS_RIGHT_X, -1f); // look left
    c.update(0.5);
    assertEquals(c.stickDegreesPerSecond * 0.5f, c.yaw(), 1e-3f);
    s.gamepadAxis(0, Gamepad.AXIS_RIGHT_X, 0f);
    s.gamepadAxis(0, Gamepad.AXIS_RIGHT_TRIGGER, 1f); // fly up
    final float y = p.y;
    c.update(1.0);
    assertEquals(y + 2f, p.y, 1e-4f);
    c.gamepad = false;
    c.update(1.0);
    assertEquals(y + 2f, p.y, 1e-4f);
    assertEquals(new Vector3f(p), p);
  }
}
