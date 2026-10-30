package engine.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jogamp.newt.event.KeyEvent;

import engine.core.Settings;

class BindingsTest {
  @TempDir Path dir;

  private static InputState defaults() {
    final var in = new InputState();
    FirstPersonController.bindDefaults(in);
    in.bind("pause", KeyEvent.VK_P);
    in.bindGamepad("pause", Gamepad.BUTTON_START);
    return in;
  }

  @Test
  void keyAndButtonNames() {
    assertEquals("W", KeyNames.name(KeyEvent.VK_W));
    assertEquals("SPACE", KeyNames.name(KeyEvent.VK_SPACE));
    assertEquals("F5", KeyNames.name(KeyEvent.VK_F5));
    assertEquals(KeyEvent.VK_F5, (short) KeyNames.code("f5"));
    assertEquals(KeyEvent.VK_SHIFT, (short) KeyNames.code("VK_SHIFT"));
    assertEquals(0x1234, KeyNames.code(KeyNames.name((short) 0x1234)));
    assertEquals(-1, KeyNames.code("NO_SUCH_KEY"));
    assertEquals("RB", Gamepad.buttonName(Gamepad.BUTTON_RIGHT_BUMPER));
    assertEquals(Gamepad.BUTTON_DPAD_LEFT, Gamepad.button("dpad_left"));
  }

  @Test
  void bindingsAsTextRoundTrip() {
    final var in = defaults();
    assertTrue(in.actions().containsAll(Set.of("forward", "jump", "pause")));
    assertEquals("W, UP", in.describe("forward"));
    assertEquals("SPACE, pad:Y", in.describe("jump"));
    in.bindFromText("jump", "J, pad:a, pad:nonsense, ???");
    assertArrayEquals(new short[] { KeyEvent.VK_J }, in.keys("jump"));
    assertArrayEquals(new int[] { Gamepad.BUTTON_A }, in.gamepadButtons("jump"));
    in.bindFromText("jump", "");
    assertEquals("", in.describe("jump"));
    assertEquals(List.of("forward"), in.actionsUsingKey(KeyEvent.VK_W));
  }

  @Test
  void savedBindingsReplaceTheDefaultsTheyMention() throws Exception {
    final var settings = Settings.load(this.dir.resolve("controls.properties"));
    final var in = defaults();
    in.bindFromText("forward", "I");
    in.saveBindings(settings, "input.");
    settings.save();

    final var again = defaults();
    final var reloaded = Settings.load(this.dir.resolve("controls.properties"));
    reloaded.remove("input.back"); // an action the file does not mention keeps its default
    again.loadBindings(reloaded, "input.");
    assertArrayEquals(new short[] { KeyEvent.VK_I }, again.keys("forward"));
    assertEquals("S, DOWN", again.describe("back"));
    assertEquals("P, pad:START", again.describe("pause"));
  }

  @Test
  void theRebinderTakesTheNextKeyOrButton() {
    final var in = defaults();
    final var r = new Rebinder(in);
    r.start("jump", true);
    in.keyDown(KeyEvent.VK_ENTER); // the press that opened the menu entry: ignored
    assertFalse(r.update());
    in.endFrame();
    in.keyUp(KeyEvent.VK_ENTER);
    assertFalse(r.update(), "nothing pressed yet");
    in.keyDown(KeyEvent.VK_W);
    assertTrue(r.update());
    assertFalse(r.isWaiting());
    assertEquals("W, pad:Y", in.describe("jump"), "keys replaced, buttons kept");
    assertEquals(List.of("forward"), r.lastConflicts());
    in.endFrame();

    r.start("jump", false);
    r.update();
    in.gamepadConnected(0, "pad");
    in.gamepadButton(0, Gamepad.BUTTON_LEFT_BUMPER, true);
    assertTrue(r.update());
    assertEquals("W, pad:Y, pad:LB", in.describe("jump"), "added");
    in.endFrame();

    r.start("jump", true);
    r.update();
    in.keyDown(KeyEvent.VK_ESCAPE);
    assertFalse(r.update());
    assertFalse(r.isWaiting(), "cancelled");
    assertEquals("W, pad:Y, pad:LB", in.describe("jump"));
  }

  @Test
  void rumbleLastsAsLongAsAsked() throws InterruptedException {
    final var in = new InputState();
    in.gamepadConnected(1, "pad");
    in.gamepadCanRumble(1, true);
    assertTrue(in.gamepad(1).canRumble());
    in.rumble(0.8f, 2f, 0.05f);
    final long now = System.nanoTime();
    assertEquals(0.8f, in.gamepad(1).rumbleStrong(now));
    assertEquals(1f, in.gamepad(1).rumbleWeak(now), "clamped");
    assertEquals(0f, in.gamepad(0).rumbleStrong(now), "not connected");
    assertEquals(0f, in.gamepad(1).rumbleStrong(now + 60_000_000L));
    in.gamepad(1).stopRumble();
    assertEquals(0f, in.gamepad(1).rumbleStrong(System.nanoTime()));
    in.gamepadDisconnected(1);
    assertFalse(in.gamepad(1).canRumble());
  }
}
