package engine.net.replication;

import java.util.Arrays;

import org.joml.Vector2f;

import engine.input.InputState;

/**
 * Makes {@link StandardCommand}s from the input bindings: the mouse (and the right stick) turn, the movement actions
 * (and the left stick) move, and each button of the command is down while its action is. The action names default to
 * those {@link engine.input.FirstPersonController} uses ("forward", "back", "left", "right", and "jump" on button 0,
 * which {@link CharacterMotor} jumps with); a game with other bindings sets its own ({@link #setMoveActions},
 * {@link #setButton}).
 */
public final class ActionCommandSource implements CommandSource {
  private final InputState input;
  private final String[] buttons = new String[StandardCommand.BUTTONS];
  private String forward = "forward";
  private String back = "back";
  private String left = "left";
  private String right = "right";
  public float sensitivityDegPerPixel = 0.12f;
  public float stickDegreesPerSecond = 150f;
  /** Use the first connected gamepad as well. */
  public boolean gamepad = true;
  private float yaw;
  private float pitch;
  private final Vector2f stick = new Vector2f();

  public ActionCommandSource(InputState input) {
    this.input = input;
    this.buttons[0] = "jump";
  }

  /** The actions that move forward, back, left and right (null: none). */
  public ActionCommandSource setMoveActions(String forwardAction, String backAction, String leftAction,
      String rightAction) {
    this.forward = forwardAction;
    this.back = backAction;
    this.left = leftAction;
    this.right = rightAction;
    return this;
  }

  /** Presses button {@code index} (0..7, {@link StandardCommand#button(int)}) while {@code action} is down; null: never. */
  public ActionCommandSource setButton(int index, String action) {
    StandardCommand.button(index);
    this.buttons[index] = action;
    return this;
  }

  /** Same as {@link #setButton}. */
  public ActionCommandSource addButton(int index, String action) {
    return setButton(index, action);
  }

  /** Unmaps every button, including the default "jump" on button 0. */
  public ActionCommandSource clearButtons() {
    Arrays.fill(this.buttons, null);
    return this;
  }

  /** Turns the view, e.g. to face the way a player spawned. */
  public void setLook(float yawDegrees, float pitchDegrees) {
    this.yaw = yawDegrees;
    this.pitch = Math.clamp(pitchDegrees, -89f, 89f);
  }

  @Override
  public StandardCommand sample(long tick, double dt) {
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
    if (down(this.forward)) {
      z += 1f;
    }
    if (down(this.back)) {
      z -= 1f;
    }
    if (down(this.right)) {
      x += 1f;
    }
    if (down(this.left)) {
      x -= 1f;
    }
    int bits = 0;
    for (int i = 0; i < this.buttons.length; i++) {
      if (down(this.buttons[i])) {
        bits |= StandardCommand.button(i);
      }
    }
    return new StandardCommand(tick, Math.clamp(x, -1f, 1f), Math.clamp(z, -1f, 1f), this.yaw, this.pitch, bits,
        -1); // the replication fills in what the player sees
  }

  private boolean down(String action) {
    return action != null && this.input.isActionDown(action);
  }
}
