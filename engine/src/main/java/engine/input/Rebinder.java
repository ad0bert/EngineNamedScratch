package engine.input;

import java.util.ArrayList;
import java.util.List;

import com.jogamp.newt.event.KeyEvent;

/**
 * Lets the player change a binding in a controls menu: {@link #start} an action, then {@link #update} each step; the
 * next key or gamepad button pressed is bound to it. Escape (or {@link #cancel()}) stops waiting without a change.
 * With {@code replace} the new key replaces the action's keys (a new button its buttons); without, it is added.
 * {@link #lastConflicts()} names the other actions that use the same key or button afterwards, for the menu to show.
 */
public final class Rebinder {
  private final InputState input;
  private String action;
  private boolean replace;
  private boolean armed;
  private List<String> conflicts = List.of();

  public Rebinder(InputState input) {
    this.input = input;
  }

  /** Waits for a key or button for {@code action}, from the next step on (the click that started it does not count). */
  public void start(String action, boolean replace) {
    this.action = action;
    this.replace = replace;
    this.armed = false;
    this.conflicts = List.of();
  }

  public boolean isWaiting() {
    return this.action != null;
  }

  /** The action being bound, or null. */
  public String action() {
    return this.action;
  }

  public void cancel() {
    this.action = null;
  }

  /** Other actions bound to what was captured last (empty if none, or nothing was captured). */
  public List<String> lastConflicts() {
    return this.conflicts;
  }

  /** Binds the key or button pressed since the last step, if any; true when it bound one this step. */
  public boolean update() {
    if (this.action == null) {
      return false;
    }
    if (!this.armed) {
      this.armed = true; // skip whatever was pressed in the step that started it
      return false;
    }
    final short key = this.input.anyKeyPressed();
    if (key == KeyEvent.VK_ESCAPE) {
      cancel();
      return false;
    }
    if (key >= 0) {
      if (this.replace) {
        final int[] buttons = this.input.gamepadButtons(this.action);
        this.input.unbind(this.action);
        for (int b : buttons) {
          this.input.addGamepadButton(this.action, b);
        }
      }
      this.input.addKey(this.action, key);
      this.conflicts = without(this.input.actionsUsingKey(key));
      this.action = null;
      return true;
    }
    final int button = this.input.anyGamepadButtonPressed();
    if (button >= 0) {
      if (this.replace) {
        final short[] keys = this.input.keys(this.action);
        this.input.unbind(this.action);
        for (short k : keys) {
          this.input.addKey(this.action, k);
        }
      }
      this.input.addGamepadButton(this.action, button);
      this.conflicts = without(this.input.actionsUsingGamepadButton(button));
      this.action = null;
      return true;
    }
    return false;
  }

  private List<String> without(List<String> actions) {
    final var out = new ArrayList<>(actions);
    out.remove(this.action);
    return out;
  }
}
