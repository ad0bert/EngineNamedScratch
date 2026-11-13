package engine.net.replication;

import java.util.Arrays;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import engine.core.Engine;
import engine.ecs.Component;
import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;

/**
 * Moves a player's entity from {@link InputCommand}s, the same way on the host and on the client that owns it, so that
 * the client can move its own player at once and check later what the host made of the same commands (client-side
 * prediction; see {@link ClientReplication}). {@link CharacterMotor} walks a character, {@link FlightMotor} flies a
 * ship; games can write their own for other movement.
 *
 * <p>
 * A motor must follow three rules: the movement of a step depends only on its state and the step's command (no random
 * numbers, no wall-clock time); {@link #writePredicted} writes everything that movement depends on, and
 * {@link #readPredicted} puts it all back; and {@link #replayStep} does to the entity what the engine's systems would
 * do in a step (nothing, for a motor that moves the entity in {@link #move} itself).
 *
 * <p>
 * Every step, before the systems, the host calls {@link #move} on each player's motor with that player's command; the
 * predicting client calls it on its own player with the command it is about to send. After a correction from the host
 * the client puts the motor in the host's state and replays the commands since; the {@link #visual} child then slides
 * back into place over a few steps instead of jumping.
 */
public abstract class PredictedMotor extends Component {
  /**
   * The child that is drawn (meshes, the camera): moved by the remaining error of a correction, which fades out in
   * about a tenth of a second. Null: corrections show at once.
   */
  public Entity visual;

  private InputCommand last = InputCommand.NONE;
  private InputCommand previous = InputCommand.NONE;
  private final Vector3f visualHome = new Vector3f();
  private final Vector3f visualOffset = new Vector3f();
  private boolean visualHomeKnown;
  private boolean predictedHere;
  private boolean replaying;

  /** Uses {@code command} for the coming step of {@code dt} seconds: the subclass's {@link #move}. */
  final void drive(InputCommand command, float dt) {
    this.previous = this.last;
    this.last = command;
    move(command, dt);
  }

  /** Applies a command for the coming step of {@code dt} seconds (before the systems run). */
  protected abstract void move(InputCommand command, float dt);

  /**
   * During a replay, after {@link #move}: what the engine's systems do to this entity in a step of {@code dt}
   * seconds. Nothing by default (for motors that move the entity themselves).
   */
  protected void replayStep(Engine engine, float dt) {
  }

  /**
   * Whether this motor can be predicted as built (e.g. it has the components it needs); a client's own player whose
   * motor says no is shown as a plain copy instead.
   */
  protected boolean canPredict() {
    return true;
  }

  /** Writes the state the movement depends on (position, velocity, ...), as the host sends it to the player. */
  protected abstract void writePredicted(BitWriter out);

  /** Puts back what {@link #writePredicted} wrote: the host's state, before replaying commands. */
  protected abstract void readPredicted(BitReader in);

  /**
   * Whether the client's prediction ({@code mine}) and the host's state ({@code host}), both written by
   * {@link #writePredicted}, agree well enough to need no correction. Byte for byte by default; override to allow
   * for rounding.
   */
  protected boolean matches(byte[] mine, byte[] host) {
    return Arrays.equals(mine, host);
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

  /** Whether this is the predicting client's own player (as opposed to the host's, or a copy). */
  public boolean isPredictedHere() {
    return this.predictedHere;
  }

  /**
   * Whether the client is replaying commands after a correction (the moves are not new; e.g. do not play sounds, and
   * leave the transform's previous position for render interpolation alone).
   */
  public boolean isReplaying() {
    return this.replaying;
  }

  void setReplaying(boolean value) {
    this.replaying = value;
  }

  /** Replaces the command in use without moving, e.g. with what a copy received about its player's look. */
  protected void setLastCommand(InputCommand command) {
    this.last = command;
  }

  /** For replaying after a correction: what the command before the replayed one was. */
  void restoreLast(InputCommand command) {
    this.last = command;
  }

  void setPredictedHere(boolean value) {
    this.predictedHere = value;
  }

  byte[] savePredicted() {
    final var out = new BitWriter(64);
    writePredicted(out);
    return out.toByteArray();
  }

  void restorePredicted(byte[] state) {
    readPredicted(new BitReader(state));
  }

  /** Adds a correction's error (old position minus new) to the visual's offset. */
  void addCorrection(Vector3fc worldError) {
    if (this.visual == null) {
      return;
    }
    if (!this.visualHomeKnown) {
      this.visualHome.set(this.visual.transform().position());
      this.visualHomeKnown = true;
    }
    this.visualOffset.add(worldError);
    if (this.visualOffset.length() > 2f) {
      this.visualOffset.zero(); // too far to slide (the host moved it on purpose): show it where it is
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
    // the offset is in world space, the visual's position in the entity's
    final var local = entity().transform().rotation().transformInverse(new Vector3f(this.visualOffset));
    this.visual.transform().position().set(this.visualHome).add(local);
  }
}
