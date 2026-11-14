package engine.net.replication;

import org.joml.Vector3f;

import engine.core.Engine;
import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.physics.CharacterController;

/**
 * Drives a {@link CharacterController} from {@link StandardCommand}s: turns the entity to the command's yaw, the
 * {@link #head} to its pitch, walks the way the command pushes and jumps when {@link #jumpButton} goes down. Because a
 * command alone decides the movement, the host can run a client's commands and the client can run the same ones ahead
 * of time (prediction); see {@link PredictedMotor}, {@link ClientReplication} and {@link ServerReplication}.
 *
 * <p>
 * Put it on a root entity with a {@link engine.physics.CapsuleCollider} and a {@link CharacterController}. Other
 * players see the pitch through {@link NetState}. After a correction from the host, the predicting client slides
 * {@link #visual} back into place over a few steps instead of letting it jump.
 */
public class CharacterMotor extends PredictedMotor<StandardCommand> implements NetState {
  /** Walking speed in m/s. */
  public float speed = 4f;
  /** Upward speed of a jump in m/s. */
  public float jumpSpeed = 5f;
  /** The child that looks up and down (with the camera); null tilts nothing. */
  public Entity head;
  /**
   * The command button bits that jump (default button 0, which {@link ActionCommandSource} binds to the "jump"
   * action); 0 never jumps.
   */
  public int jumpButton = StandardCommand.button(0);

  private final Vector3f move = new Vector3f();
  private final CharacterController.State scratch = new CharacterController.State();

  public CharacterMotor() {
    super(StandardCommand.class);
  }

  /** Uses {@code command} for the coming step: turn, walk, maybe jump. */
  public void apply(StandardCommand command) {
    drive(command, 0f);
  }

  @Override
  protected void move(StandardCommand command, float dt) {
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
    if (this.jumpButton != 0 && pressed(this.jumpButton)) {
      body.jump(this.jumpSpeed);
    }
  }

  @Override
  protected void replayStep(Engine engine, float dt) {
    engine.physics().replayCharacter(engine.scene(), entity().get(CharacterController.class), dt);
  }

  @Override
  protected boolean canPredict() {
    return entity().findOrNull(CharacterController.class) != null;
  }

  @Override
  protected void writePredicted(BitWriter out) {
    writeCharacter(out, entity().get(CharacterController.class).save(this.scratch));
  }

  @Override
  protected void readPredicted(BitReader in) {
    entity().get(CharacterController.class).restore(readCharacter(in));
  }

  @Override
  protected boolean matches(byte[] mine, byte[] host) {
    final var a = readCharacter(new BitReader(mine));
    final var b = readCharacter(new BitReader(host));
    return a.position.distanceSquared(b.position) < 1e-6f && a.velocity.distanceSquared(b.velocity) < 1e-4f
        && a.grounded == b.grounded;
  }

  private static void writeCharacter(BitWriter out, CharacterController.State s) {
    out.writeVector(s.position).writeVector(s.velocity).writeFloat(s.walk.x).writeFloat(s.walk.z)
        .writeBoolean(s.grounded).writeFloat(s.airTime);
  }

  private static CharacterController.State readCharacter(BitReader in) {
    final var s = new CharacterController.State();
    in.readVector(s.position);
    in.readVector(s.velocity);
    s.walk.set(in.readFloat(), 0f, in.readFloat());
    s.grounded = in.readBoolean();
    s.airTime = in.readFloat();
    return s;
  }

  /** The pitch in degrees: of the command in use, or as received for other players' copies. */
  public float pitch() {
    final var last = lastCommand();
    return last == null ? 0f : last.pitch();
  }

  private void applyPitch(float pitchDegrees) {
    if (this.head != null) {
      this.head.transform().rotation().identity().rotateX((float) Math.toRadians(pitchDegrees));
    }
  }

  @Override
  public void writeState(BitWriter out) {
    out.writeFloat(pitch(), -89f, 89f, StandardCommand.PITCH_BITS);
  }

  @Override
  public void readState(BitReader in) {
    final float pitch = in.readFloat(-89f, 89f, StandardCommand.PITCH_BITS);
    if (!isPredictedHere()) { // the predicting client has its own, newer pitch
      final var last = lastCommand();
      setLastCommand(last == null ? new StandardCommand(0, 0f, 0f, 0f, pitch, 0, -1)
          : new StandardCommand(last.tick(), 0f, 0f, last.yaw(), pitch, 0, -1));
      applyPitch(pitch);
    }
  }
}
