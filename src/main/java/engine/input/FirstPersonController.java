package engine.input;

import org.joml.Vector3f;

import com.jogamp.newt.event.KeyEvent;

import engine.ecs.Component;
import lombok.Getter;

/**
 * Free-fly first-person controller. Mouse look sets yaw/pitch on the entity rotation; movement uses the key bindings
 * forward/back/left/right/up/down (bound with defaults by {@link #bindDefaults(InputState)}).
 */
public class FirstPersonController extends Component {
  private final InputState input;
  public float speed = 4f;
  public float sensitivityDegPerPixel = 0.12f;
  @Getter private float yaw;
  @Getter private float pitch;

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
  }

  @Override
  public void update(double dt) {
    this.yaw -= this.input.consumeMouseDx() * this.sensitivityDegPerPixel;
    this.pitch -= this.input.consumeMouseDy() * this.sensitivityDegPerPixel;
    this.pitch = Math.max(-89f, Math.min(89f, this.pitch));
    final var t = entity().transform();
    t.rotation().identity().rotateY((float) Math.toRadians(this.yaw)).rotateX((float) Math.toRadians(this.pitch));

    final var move = new Vector3f();
    final var fwd = t.forward(new Vector3f());
    final var right = t.right(new Vector3f());
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
    if (move.lengthSquared() > 0f) {
      t.position().add(move.normalize(this.speed * (float) dt));
    }
  }
}
