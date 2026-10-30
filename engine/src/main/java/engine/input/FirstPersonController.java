package engine.input;

import org.joml.Vector2f;
import org.joml.Vector3f;

import com.jogamp.newt.event.KeyEvent;

import engine.ecs.Component;
import engine.ecs.Entity;
import engine.physics.CharacterController;
import lombok.Getter;

/**
 * First-person controller. Mouse look sets yaw/pitch on the entity rotation; movement uses the key bindings
 * forward/back/left/right/up/down (bound with defaults by {@link #bindDefaults(InputState)}). With {@link #gamepad}
 * on, the first connected gamepad works at the same time: the left stick moves (slower when pushed less), the right
 * stick looks, the right and left triggers fly up and down.
 *
 * <p>
 * It flies freely unless the entity has an enabled {@link CharacterController}: then it walks, the "jump" action (Space, or the
 * gamepad's Y button) jumps, the entity only turns about Y (so its capsule stays upright) and the pitch goes to the
 * {@link #head} entity, usually a child holding the camera.
 */
public class FirstPersonController extends Component {
  private final InputState input;
  public float speed = 4f;
  public float sensitivityDegPerPixel = 0.12f;
  /** Use the first connected gamepad as well. */
  public boolean gamepad = true;
  /** Turning speed with the right stick fully pushed. */
  public float stickDegreesPerSecond = 150f;
  /** Walking: the entity that looks up and down (a child with the camera); null tilts nothing. */
  public Entity head;
  /** Walking: upward speed of a jump, in m/s. */
  public float jumpSpeed = 5f;
  private boolean jumpHeld;
  @Getter private float yaw;
  @Getter private float pitch;
  private final Vector3f move = new Vector3f();
  private final Vector3f fwd = new Vector3f();
  private final Vector3f right = new Vector3f();
  private final Vector2f stick = new Vector2f();

  public FirstPersonController(InputState input) {
    this.input = input;
  }

  public static void bindDefaults(InputState input) {
    input.bind("forward", KeyEvent.VK_W, KeyEvent.VK_UP);
    input.bind("back", KeyEvent.VK_S, KeyEvent.VK_DOWN);
    input.bind("left", KeyEvent.VK_A, KeyEvent.VK_LEFT);
    input.bind("right", KeyEvent.VK_D, KeyEvent.VK_RIGHT);
    input.bind("up", KeyEvent.VK_SPACE);
    input.bind("down", KeyEvent.VK_SHIFT);
    input.bind("jump", KeyEvent.VK_SPACE);
    input.bindGamepad("jump", Gamepad.BUTTON_Y);
  }

  /** Turns the view: yaw in degrees about the world up axis (0 looks along -Z), pitch in degrees up from level. */
  public void setLook(float yawDegrees, float pitchDegrees) {
    this.yaw = yawDegrees;
    this.pitch = Math.max(-89f, Math.min(89f, pitchDegrees));
  }

  @Override
  public void update(double dt) {
    final var pad = this.gamepad ? this.input.firstGamepad() : null;
    this.yaw -= this.input.consumeMouseDx() * this.sensitivityDegPerPixel;
    this.pitch -= this.input.consumeMouseDy() * this.sensitivityDegPerPixel;
    if (pad != null) {
      final var look = pad.rightStick(this.stick);
      this.yaw -= look.x * this.stickDegreesPerSecond * (float) dt;
      this.pitch += look.y * this.stickDegreesPerSecond * (float) dt;
    }
    this.pitch = Math.max(-89f, Math.min(89f, this.pitch));
    final var t = entity().transform();
    final var body = entity().findOrNull(CharacterController.class);
    if (body != null && body.isEnabled()) {
      walk(body, pad);
      return;
    }
    if (this.head != null) {
      this.head.transform().rotation().identity(); // flying: the whole entity looks up and down
    }
    t.rotation().identity().rotateY((float) Math.toRadians(this.yaw)).rotateX((float) Math.toRadians(this.pitch));

    final var move = this.move.zero();
    final var fwd = t.forward(this.fwd);
    final var right = t.right(this.right);
    if (this.input.isActionDown("forward")) {
      move.add(fwd);
    }
    if (this.input.isActionDown("back")) {
      move.sub(fwd);
    }
    if (this.input.isActionDown("right")) {
      move.add(right);
    }
    if (this.input.isActionDown("left")) {
      move.sub(right);
    }
    if (this.input.isActionDown("up")) {
      move.y += 1f;
    }
    if (this.input.isActionDown("down")) {
      move.y -= 1f;
    }
    if (pad != null) {
      final var walk = pad.leftStick(this.stick);
      move.fma(walk.y, fwd).fma(walk.x, right);
      move.y += pad.axis(Gamepad.AXIS_RIGHT_TRIGGER) - pad.axis(Gamepad.AXIS_LEFT_TRIGGER);
    }
    final float len = move.length();
    if (len > 0f) { // keys give full speed (also diagonally), a half-pushed stick half
      t.position().add(move.mul(this.speed * (float) dt / Math.max(1f, len)));
    }
  }

  private void walk(CharacterController body, GamepadState pad) {
    final var t = entity().transform();
    t.rotation().identity().rotateY((float) Math.toRadians(this.yaw));
    if (this.head != null) {
      this.head.transform().rotation().identity().rotateX((float) Math.toRadians(this.pitch));
    }
    final var fwd = t.forward(this.fwd);
    fwd.y = 0f;
    fwd.normalize();
    final var right = t.right(this.right);
    right.y = 0f;
    right.normalize();
    final var move = this.move.zero();
    if (this.input.isActionDown("forward")) {
      move.add(fwd);
    }
    if (this.input.isActionDown("back")) {
      move.sub(fwd);
    }
    if (this.input.isActionDown("right")) {
      move.add(right);
    }
    if (this.input.isActionDown("left")) {
      move.sub(right);
    }
    if (pad != null) {
      final var stickMove = pad.leftStick(this.stick);
      move.fma(stickMove.y, fwd).fma(stickMove.x, right);
    }
    final float len = move.length();
    body.walk(len > 0f ? move.mul(this.speed / Math.max(1f, len)) : move);
    final boolean jump = this.input.isActionDown("jump");
    if (jump && !this.jumpHeld) { // once per press; held down it does not bounce on landing
      body.jump(this.jumpSpeed);
    }
    this.jumpHeld = jump;
  }
}
