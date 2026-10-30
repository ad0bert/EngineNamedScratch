package engine.navigation;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import engine.ecs.Component;
import engine.physics.CapsuleCollider;
import engine.physics.CharacterController;

/**
 * Walks its entity to a destination along a {@link NavGrid} path. With a {@link CharacterController} on the entity it
 * steers that (so it collides, climbs steps and falls like a player); without one it moves the entity's position
 * along the path itself, at the floor's height. It turns to face where it goes (about Y) when {@link #turn} is set.
 * It plans again when the destination changes, when it has made no progress for {@link #stuckTime} seconds, and
 * every {@link #repathInterval} seconds if that is above zero (for targets that move).
 *
 * <p>
 * Positions are those of the entity's origin; {@link #footOffset} is how far its feet are below it (for a character
 * controller's capsule, its half height plus radius: set automatically on the first update).
 */
public class NavAgent extends Component {
  private final NavGrid grid;
  public float speed = 3f;
  /** Distance to a waypoint (on the XZ plane) at which it heads for the next one. */
  public float waypointRadius = 0.3f;
  /** Distance to the destination at which it stops. */
  public float stopDistance = 0.2f;
  /** Plans again this often while walking (seconds); 0 only when needed. */
  public float repathInterval;
  /** Plans again after this long without getting closer to the next waypoint. */
  public float stuckTime = 1f;
  /** Faces the direction it walks. */
  public boolean turn = true;
  /** How far the feet are below the entity origin; negative: work it out from a character controller's capsule. */
  public float footOffset = -1f;

  private final Vector3f destination = new Vector3f();
  private boolean hasDestination;
  private boolean arrived;
  private boolean needsPath;
  private final List<Vector3f> path = new ArrayList<>();
  private int next;
  private float sinceRepath;
  private float sinceProgress;
  private float bestDistance = Float.MAX_VALUE;
  private final Vector3f feet = new Vector3f();
  private final Vector3f move = new Vector3f();

  public NavAgent(NavGrid grid) {
    this.grid = grid;
  }

  /** Walks to {@code target} (planned at the next update). */
  public void setDestination(Vector3f target) {
    this.destination.set(target);
    this.hasDestination = true;
    this.needsPath = true;
    this.arrived = false;
  }

  /** Stops where it is and forgets the destination. */
  public void stop() {
    this.hasDestination = false;
    this.path.clear();
    halt();
  }

  public boolean hasDestination() {
    return this.hasDestination;
  }

  /** True once it got within {@link #stopDistance} of the destination (and until a new one is set). */
  public boolean hasArrived() {
    return this.arrived;
  }

  /** The planned waypoints (the last is the destination); empty before planning or when there is no way. */
  public List<Vector3f> path() {
    return this.path;
  }

  /** Waypoints already passed. */
  public int nextWaypoint() {
    return this.next;
  }

  /** True if the last plan found no way to the destination. */
  public boolean isPathBlocked() {
    return this.hasDestination && !this.needsPath && this.path.isEmpty();
  }

  @Override
  public void update(double dtD) {
    final float dt = (float) dtD;
    final var body = entity().findOrNull(CharacterController.class);
    if (this.footOffset < 0f) {
      final var capsule = entity().findOrNull(CapsuleCollider.class);
      this.footOffset = capsule != null ? capsule.halfHeight + capsule.radius - capsule.offset.y : 0f;
    }
    if (!this.hasDestination) {
      return;
    }
    final var t = entity().transform();
    t.worldPosition(this.feet).sub(0f, this.footOffset, 0f);
    this.sinceRepath += dt;
    if (this.needsPath || this.repathInterval > 0f && this.sinceRepath >= this.repathInterval) {
      plan();
    }
    if (this.path.isEmpty()) {
      halt();
      return;
    }
    // past the waypoints within reach
    while (this.next < this.path.size() - 1 && flat(this.path.get(this.next)) <= this.waypointRadius) {
      this.next++;
      this.bestDistance = Float.MAX_VALUE;
    }
    final var target = this.path.get(this.next);
    final float distance = flat(target);
    if (this.next == this.path.size() - 1 && distance <= this.stopDistance) {
      this.hasDestination = false;
      this.arrived = true;
      this.next = this.path.size();
      halt();
      return;
    }
    if (distance < this.bestDistance - 0.05f) {
      this.bestDistance = distance;
      this.sinceProgress = 0f;
    } else if ((this.sinceProgress += dt) > this.stuckTime) {
      this.needsPath = true; // pushed off the path or blocked: plan from here
      this.sinceProgress = 0f;
    }
    this.move.set(target.x - this.feet.x, 0f, target.z - this.feet.z);
    final float len = this.move.length();
    if (len < 1e-5f) {
      halt();
      return;
    }
    this.move.mul(this.speed / len);
    if (this.turn) {
      t.rotation().identity().rotateY((float) Math.atan2(-this.move.x, -this.move.z));
    }
    if (body != null && body.isEnabled()) {
      body.walk(this.move);
      return;
    }
    // no body: slide along the path on the floors
    final float stepLen = Math.min(len, this.speed * dt) / this.speed;
    t.position().add(this.move.x * stepLen, 0f, this.move.z * stepLen);
    this.feet.add(this.move.x * stepLen, 0f, this.move.z * stepLen);
    final float floor = this.grid.floorHeight(this.feet);
    t.position().y += (Float.isNaN(floor) ? target.y : floor) - this.feet.y;
  }

  private void plan() {
    this.needsPath = false;
    this.sinceRepath = 0f;
    this.path.clear();
    this.path.addAll(this.grid.findPath(this.feet, this.destination));
    this.next = this.path.size() > 1 ? 1 : 0;
    this.bestDistance = Float.MAX_VALUE;
    this.sinceProgress = 0f;
  }

  private void halt() {
    final var body = entity().findOrNull(CharacterController.class);
    if (body != null) {
      body.walk(0f, 0f);
    }
  }

  private float flat(Vector3f p) {
    return (float) Math.hypot(p.x - this.feet.x, p.z - this.feet.z);
  }
}
