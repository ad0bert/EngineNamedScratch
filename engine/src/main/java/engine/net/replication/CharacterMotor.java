package engine.net.replication;

import org.joml.Vector3f;

import engine.ecs.Component;
import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.physics.CharacterController;

/**
 * Drives a {@link CharacterController} from {@link InputCommand}s: turns the entity to the command's yaw, the
 * {@link #head} to its pitch, walks the way the command pushes and jumps when the jump button goes down. Because a
 * command alone decides the movement, the host can run a client's commands and the client can run the same ones ahead
 * of time (prediction); see {@link ClientReplication} and {@link ServerReplication}.
 *
 * <p>
 * Put it on a root entity with a {@link engine.physics.CapsuleCollider} and a {@link CharacterController}. Other
 * players see the pitch through {@link NetState}. After a correction from the host, the predicting client slides
 * {@link #visual} back into place over a few steps instead of letting it jump.
 */
public class CharacterMotor extends Component implements NetState {
  /** Walking speed in m/s. */
  public float speed = 4f;
  /** Upward speed of a jump in m/s. */
  public float jumpSpeed = 5f;
  /** The child that looks up and down (with the camera); null tilts nothing. */
  public Entity head;
  /**
   * The child that is drawn (meshes, the camera): moved by the remaining error of a correction, which fades out in
   * about a tenth of a second. Null: corrections show at once.
   */
  public Entity visual;

  private InputCommand last = InputCommand.NONE;
  private InputCommand previous = InputCommand.NONE;
  private final Vector3f move = new Vector3f();
  private final Vector3f visualHome = new Vector3f();
  private final Vector3f visualOffset = new Vector3f();
  private boolean visualHomeKnown;
  private boolean predictedHere;

  /** Uses {@code command} for the coming step: turn, walk, maybe jump. */
  public void apply(InputCommand command) {
    this.previous = this.last;
    this.last = command;
    final var t = entity().transform();
    final float yaw = (float) Math.toRadians(command.yaw());
    t.rotation().identity().rotateY(yaw);
    applyPitch(command.pitch());
    final var body = entity().findOrNull(CharacterController.class);
    if (body == null) {
      return;
    }
    // 0 yaw looks along -Z; right is +X then
    final float sin = (float) Math.sin(yaw);
    final float cos = (float) Math.cos(yaw);
    final float x = command.moveX();
    final float z = command.moveZ();
    this.move.set(x * cos - z * sin, 0f, -x * sin - z * cos);
    final float length = this.move.length();
    if (length > 1f) {
      this.move.div(length);
    }
    body.walk(this.move.mul(this.speed));
    if (pressed(InputCommand.JUMP)) {
      body.jump(this.jumpSpeed);
    }
  }

  /** The command in use. */
  public InputCommand lastCommand() {
    return this.last;
  }

  /** Whether the buttons are down in the command in use. */
  public boolean isDown(int buttons) {
    return this.last.isDown(buttons);
  }

  /** Whether the buttons went down with the command in use (they were not all down in the one before). */
  public boolean pressed(int buttons) {
    return this.last.isDown(buttons) && !this.previous.isDown(buttons);
  }

  /** The pitch in degrees: of the command in use, or as received for other players' copies. */
  public float pitch() {
    return this.last.pitch();
  }

  /** For replaying after a correction: what the command before the replayed one was. */
  void restoreLast(InputCommand command) {
    this.last = command;
  }

  void setPredictedHere(boolean value) {
    this.predictedHere = value;
  }

  boolean isPredictedHere() {
    return this.predictedHere;
  }

  /** Adds a correction's error (old position minus new) to the visual's offset. */
  void addCorrection(Vector3f worldError) {
    if (this.visual == null) {
      return;
    }
    if (!this.visualHomeKnown) {
      this.visualHome.set(this.visual.transform().position());
      this.visualHomeKnown = true;
    }
    this.visualOffset.add(worldError);
    if (this.visualOffset.length() > 2f) {
      this.visualOffset.zero(); // too far to slide: show it where it is
    }
    placeVisual();
  }

  /** Lets the visual's offset fade; called every step on the predicting client. */
  void fadeCorrection(double dt) {
    if (this.visual == null || !this.visualHomeKnown) {
      return;
    }
    this.visualOffset.mul((float) Math.exp(-dt * 20.0));
    if (this.visualOffset.lengthSquared() < 1e-8f) {
      this.visualOffset.zero();
    }
    placeVisual();
  }

  private void placeVisual() {
    // the offset is in world space, the visual's position in the entity's (turned only about Y)
    final var local = entity().transform().rotation().transformInverse(new Vector3f(this.visualOffset));
    this.visual.transform().position().set(this.visualHome).add(local);
  }

  private void applyPitch(float pitchDegrees) {
    if (this.head != null) {
      this.head.transform().rotation().identity().rotateX((float) Math.toRadians(pitchDegrees));
    }
  }

  @Override
  public void writeState(BitWriter out) {
    out.writeFloat(this.last.pitch(), -89f, 89f, InputCommand.PITCH_BITS);
  }

  @Override
  public void readState(BitReader in) {
    final float pitch = in.readFloat(-89f, 89f, InputCommand.PITCH_BITS);
    if (!this.predictedHere) { // the predicting client has its own, newer pitch
      this.last = new InputCommand(this.last.tick(), 0f, 0f, this.last.yaw(), pitch, 0, -1);
      applyPitch(pitch);
    }
  }
}
