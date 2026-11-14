package engine.kits.flight;

import org.joml.Vector3f;

import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.replication.PredictedMotor;
import engine.net.replication.WorldSpace;

/**
 * Flies an entity through empty space from {@link FlightCommand}s: it turns to the command's orientation, the
 * command's moves push it along its own axes (forward, sideways, up), and it keeps drifting when they stop, as far as
 * {@link #drag} lets it. Nothing in the world stops it: collisions are the game's business, on the host. In a
 * {@link #space} that wraps around (a {@code Wraparound}) it comes back in on the far side when it leaves.
 *
 * <p>
 * Like every {@link PredictedMotor} it can be predicted: set {@link FlightCommand#CODEC} on the replications, spawn
 * the ship owned by a client and give that client's replication a command source that makes flight commands. It
 * moves itself, so it needs no rigid body or collider.
 */
public class FlightMotor extends PredictedMotor<FlightCommand> {
  /** Acceleration at full push, m/s². */
  public float thrust = 12f;
  /** Fraction of {@link #thrust} for pushing sideways, up and down, and backwards. */
  public float sideThrust = 0.5f;
  /** Top speed in m/s (0: none). */
  public float maxSpeed = 30f;
  /** How quickly the ship slows down by itself, per second (0: it drifts for ever). */
  public float drag = 0f;
  /** Deceleration in m/s² while {@link #brakeButton} is down; never below a stop. */
  public float brake = 15f;
  /** The command button bits that brake ({@link FlightCommand#button}); 0 for no brake. */
  public int brakeButton = 0;
  /** The space the ship lives in: positions are kept inside it and compared along it. */
  public WorldSpace space = WorldSpace.EUCLIDEAN;

  private final Vector3f velocity = new Vector3f();
  private final Vector3f push = new Vector3f();
  private final Vector3f before = new Vector3f();

  public FlightMotor() {
    super(FlightCommand.class);
  }

  /** The ship's velocity in m/s (world space); change it to push the ship, on the host. */
  public Vector3f velocity() {
    return this.velocity;
  }

  @Override
  protected void move(FlightCommand command, float dt) {
    final var t = entity().transform();
    t.rotation().set(command.aim());
    final float forward = command.moveZ() >= 0 ? command.moveZ() : command.moveZ() * this.sideThrust;
    this.push.set(command.moveX() * this.sideThrust, command.moveY() * this.sideThrust, -forward);
    if (this.push.lengthSquared() > 1f) {
      this.push.normalize();
    }
    command.aim().transform(this.push).mul(this.thrust * dt);
    this.velocity.add(this.push);
    if (this.drag > 0f) {
      this.velocity.mul((float) Math.exp(-this.drag * dt));
    }
    if (this.brakeButton != 0 && command.isDown(this.brakeButton)) {
      final float speed = this.velocity.length();
      final float slower = Math.max(0f, speed - this.brake * dt);
      this.velocity.mul(speed > 0f ? slower / speed : 0f);
    }
    if (this.maxSpeed > 0f && this.velocity.lengthSquared() > this.maxSpeed * this.maxSpeed) {
      this.velocity.normalize(this.maxSpeed);
    }
    final var position = t.position();
    position.fma(dt, this.velocity);
    if (this.space.wraps()) {
      this.before.set(position);
      this.space.normalize(position);
      if (!this.before.equals(position) && !isReplaying()) {
        t.shiftPrevious(this.before.sub(position).negate()); // smooth across the edge, not a sweep
      }
    }
  }

  @Override
  protected void writePredicted(BitWriter out) {
    out.writeVector(entity().transform().position()).writeVector(this.velocity);
  }

  @Override
  protected void readPredicted(BitReader in) {
    in.readVector(entity().transform().position());
    in.readVector(this.velocity);
  }

  @Override
  protected boolean matches(byte[] mine, byte[] host) {
    final var in = new BitReader(mine);
    final var a = in.readVector(new Vector3f());
    final var va = in.readVector(new Vector3f());
    final var hin = new BitReader(host);
    final var b = hin.readVector(new Vector3f());
    final var vb = hin.readVector(new Vector3f());
    return this.space.distanceSquared(a, b) < 1e-6f && va.distanceSquared(vb) < 1e-4f;
  }
}
