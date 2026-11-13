package engine.net.replication;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;

import engine.input.InputState;

/**
 * Makes {@link InputCommand}s from the input bindings, like {@link engine.input.FirstPersonController}: the mouse (and
 * the right stick) turn, "forward", "back", "left" and "right" (and the left stick) move, "jump" jumps. Game buttons
 * are added with {@link #addButton}.
 */
public final class ActionCommandSource implements CommandSource {
  private final InputState input;
  private final List<String> buttons = new ArrayList<>();
  public float sensitivityDegPerPixel = 0.12f;
  public float stickDegreesPerSecond = 150f;
  /** Use the first connected gamepad as well. */
  public boolean gamepad = true;
  private float yaw;
  private float pitch;
  private final Vector2f stick = new Vector2f();

  public ActionCommandSource(InputState input) {
    this.input = input;
  }

  /** Maps action {@code action} to game button {@code index} (0..6, {@link InputCommand#button(int)}). */
  public ActionCommandSource addButton(int index, String action) {
    InputCommand.button(index);
    while (this.buttons.size() <= index) {
      this.buttons.add(null);
    }
    this.buttons.set(index, action);
    return this;
  }

  /** Turns the view, e.g. to face the way a player spawned. */
  public void setLook(float yawDegrees, float pitchDegrees) {
    this.yaw = yawDegrees;
    this.pitch = Math.clamp(pitchDegrees, -89f, 89f);
  }

  @Override
  public InputCommand sample(long tick, double dt) {
    final var pad = this.gamepad ? this.input.firstGamepad() : null;
    this.yaw -= this.input.consumeMouseDx() * this.sensitivityDegPerPixel;
    this.pitch -= this.input.consumeMouseDy() * this.sensitivityDegPerPixel;
    float x = 0f;
    float z = 0f;
    if (pad != null) {
      final var look = pad.rightStick(this.stick);
      this.yaw -= look.x * this.stickDegreesPerSecond * (float) dt;
      this.pitch += look.y * this.stickDegreesPerSecond * (float) dt;
      final var move = pad.leftStick(this.stick);
      x += move.x;
      z += move.y;
    }
    this.pitch = Math.clamp(this.pitch, -89f, 89f);
    if (this.input.isActionDown("forward")) {
      z += 1f;
    }
    if (this.input.isActionDown("back")) {
      z -= 1f;
    }
    if (this.input.isActionDown("right")) {
      x += 1f;
    }
    if (this.input.isActionDown("left")) {
      x -= 1f;
    }
    int bits = this.input.isActionDown("jump") ? InputCommand.JUMP : 0; // bound to Space and the pad's Y
    for (int i = 0; i < this.buttons.size(); i++) {
      final String action = this.buttons.get(i);
      if (action != null && this.input.isActionDown(action)) {
        bits |= InputCommand.button(i);
      }
    }
    return new InputCommand(tick, Math.clamp(x, -1f, 1f), Math.clamp(z, -1f, 1f), this.yaw, this.pitch, bits,
        -1); // the replication fills in what the player sees
  }
}
