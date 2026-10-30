package engine.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import engine.ecs.Component;
import engine.ecs.Entity;
import engine.ecs.Scene;

/**
 * A walking body for players and NPCs: moved by sweeping its capsule instead of by forces, so it never bounces,
 * tips over or slides down gentle slopes. Needs an upright {@link CapsuleCollider} on the same entity and no
 * {@link Rigidbody}. Game code sets where it wants to go ({@link #walk}) and asks for {@link #jump jumps}; the
 * {@link PhysicsSystem} moves it at the start of each step:
 *
 * <ul>
 * <li>collide and slide: the capsule moves until it touches something, then along it (up to four surfaces a step,
 * creases between two walls included);</li>
 * <li>steps up ledges up to {@link #stepHeight}, walks up slopes up to {@link #maxSlopeDegrees} and treats steeper
 * ones as walls;</li>
 * <li>stays on the ground walking down slopes and steps (up to {@link #snapDistance}), falls with the system's gravity
 * otherwise;</li>
 * <li>rides what it stands on (kinematic or dynamic bodies, static colliders moved by code) and pushes dynamic bodies
 * out of its way as if it weighed {@link #mass} kilograms;</li>
 * <li>is pushed out of colliders that move into it.</li>
 * </ul>
 *
 * <p>
 * Other bodies see it as a static collider moved by code, so they collide with it, rest on it and are carried by it.
 * Its capsule stays upright: turn the entity only about Y.
 */
public class CharacterController extends Component {
  private static final Logger log = LoggerFactory.getLogger(CharacterController.class);
  private static final int MAX_SLIDES = 4;
  private static final int MAX_DEPENETRATION = 4;

  /** Collision flags of the last step: touched the ground, a wall, a ceiling. */
  public static final int BELOW = 1;
  public static final int SIDES = 2;
  public static final int ABOVE = 4;

  /** Highest ledge it walks up without jumping, in metres. */
  public float stepHeight = 0.35f;
  /** Steepest slope it walks on; steeper ones are walls (it slides down them when falling). */
  public float maxSlopeDegrees = 50f;
  /** Gap kept between the capsule and everything, so it never starts a move touching a surface. */
  public float skinWidth = 0.02f;
  /** How far down it follows the ground while walking (down slopes and steps) instead of starting to fall. */
  public float snapDistance = 0.3f;
  /** Multiplies the physics system's gravity. */
  public float gravityScale = 1f;
  /** Fastest it falls, in m/s. */
  public float maxFallSpeed = 50f;
  /** A jump asked for this long after walking off a ledge still works, in seconds. */
  public float coyoteTime = 0.1f;
  /** Weight for pushing dynamic bodies, in kg; 0 does not push them. */
  public float mass = 80f;
  /** Moves along with the ground it stands on. */
  public boolean rideMovingGround = true;
  /** Layers that block it ({@link Collider#layer}). */
  public int layerMask = ~0;

  /** Current velocity; its horizontal part is replaced by {@link #walk} every step, its vertical part by gravity. */
  public final Vector3f velocity = new Vector3f();

  private final Vector3f walkVelocity = new Vector3f();
  private float jumpSpeed;
  private boolean jumpRequested;
  private boolean grounded;
  private float airTime = Float.MAX_VALUE;
  private int flags;
  private Collider ground;
  private final Vector3f groundNormal = new Vector3f(0f, 1f, 0f);
  private final Vector3f groundPoint = new Vector3f();
  // where it stands on the ground, in the ground entity's local space (riding)
  private final Vector3f groundLocal = new Vector3f();
  private final Matrix4f groundMatrix = new Matrix4f();
  private boolean hasGroundPose;
  private boolean warned;

  // scratch
  private final ShapeCast cast = new ShapeCast();
  private final List<Collider> nearby = new ArrayList<>();
  private final Vector3f a = new Vector3f();
  private final Vector3f b = new Vector3f();
  private float r;
  private final Vector3f delta = new Vector3f();
  private final Vector3f dir = new Vector3f();
  private final Vector3f remaining = new Vector3f();
  private final Vector3f normal = new Vector3f();
  private final Vector3f lastPlane = new Vector3f();
  private final Vector3f tmp = new Vector3f();
  private final Vector3f start = new Vector3f();
  private final Vector3f shift = new Vector3f();
  private final Matrix4f matrix = new Matrix4f();
  private Entity self;
  private final Predicate<Collider> blocks = c -> !c.trigger && c.entity() != this.self
      && (this.layerMask & c.layerBit()) != 0;

  /**
   * Walks with this velocity (m/s; the vertical part is ignored) from the next step on, until changed. Zero stands
   * still.
   */
  public void walk(Vector3f horizontalVelocity) {
    this.walkVelocity.set(horizontalVelocity.x, 0f, horizontalVelocity.z);
  }

  public void walk(float x, float z) {
    this.walkVelocity.set(x, 0f, z);
  }

  public Vector3f walkVelocity(Vector3f dest) {
    return dest.set(this.walkVelocity);
  }

  /**
   * Jumps at the next step with this upward speed (m/s) if it is on the ground then, or left it less than
   * {@link #coyoteTime} ago. Returns whether it can jump now.
   */
  public boolean jump(float speed) {
    this.jumpSpeed = speed;
    this.jumpRequested = true;
    return canJump();
  }

  public boolean canJump() {
    return this.grounded || this.airTime <= this.coyoteTime;
  }

  /** Standing on walkable ground after the last step. */
  public boolean isGrounded() {
    return this.grounded;
  }

  /** What it stands on, or null in the air. */
  public Collider ground() {
    return this.grounded ? this.ground : null;
  }

  /** Normal of the ground it stands on (up in the air). */
  public Vector3f groundNormal(Vector3f dest) {
    return dest.set(this.groundNormal);
  }

  /** {@link #BELOW}, {@link #SIDES} and {@link #ABOVE} for what it touched in the last step. */
  public int collisionFlags() {
    return this.flags;
  }

  /** Moves it to {@code position} (its entity's world position) without sweeping, and stops it. */
  public void teleport(Vector3f position) {
    final var t = entity().transform();
    t.translateWorld(t.worldPosition(this.tmp).negate().add(position));
    this.velocity.zero();
    this.grounded = false;
    this.hasGroundPose = false;
    this.airTime = Float.MAX_VALUE;
  }

  private boolean walkable(Vector3f n) {
    return n.y >= (float) Math.cos(Math.toRadians(this.maxSlopeDegrees)) - 1e-4f;
  }

  /**
   * Whether the last sweep's hit is ground to stand on. A capsule resting on the edge of a step or a box touches it
   * with a tilted normal; then the surface just beyond the edge (away from the capsule's axis) decides, and its
   * normal becomes the hit's.
   */
  private boolean walkableHit(PhysicsSystem physics, Scene scene) {
    final var n = this.cast.bestNormal;
    if (walkable(n)) {
      return true;
    }
    if (n.y <= 0f) {
      return false;
    }
    final var p = this.cast.bestPoint;
    float hx = p.x - this.a.x;
    float hz = p.z - this.a.z;
    final float len = (float) Math.sqrt(hx * hx + hz * hz);
    if (len < 1e-4f) {
      return false;
    }
    hx /= len;
    hz /= len;
    final float probe = 0.02f;
    this.probeOrigin.set(p.x + hx * probe, p.y + probe, p.z + hz * probe);
    final var hit = physics.raycast(scene, this.probeOrigin, DOWN, 3f * probe, this.blocks);
    if (hit.isEmpty() || hit.get().distance() <= 1e-5f || !walkable(hit.get().normal())) { // inside: a slope
      return false;
    }
    n.set(hit.get().normal());
    return true;
  }

  private static final Vector3f DOWN = new Vector3f(0f, -1f, 0f);
  private final Vector3f probeOrigin = new Vector3f();

  /** One step: called by {@link PhysicsSystem} before it integrates the bodies. */
  void step(PhysicsSystem physics, Scene scene, float dt) {
    final var e = entity();
    final var capsule = e.findOrNull(CapsuleCollider.class);
    if (capsule == null || !capsule.isEnabled() || e.has(Rigidbody.class)) {
      if (!this.warned) {
        this.warned = true;
        log.warn("{}: a CharacterController needs a CapsuleCollider and no Rigidbody; it does not move", e);
      }
      return;
    }
    this.self = e;
    final var shape = capsule.world();
    this.a.set(shape.a);
    this.b.set(shape.b);
    this.r = shape.r;
    this.flags = 0;
    final boolean wasGrounded = this.grounded;

    if (wasGrounded && this.rideMovingGround) {
      ride(physics, scene);
    }
    depenetrate(physics, scene);

    final boolean jumping = this.jumpRequested && canJump();
    this.jumpRequested = false;
    if (jumping) {
      this.velocity.y = this.jumpSpeed;
      this.airTime = Float.MAX_VALUE; // one jump per take-off
    } else if (wasGrounded) {
      this.velocity.y = 0f;
    } else {
      this.velocity.y += physics.gravity.y * this.gravityScale * dt;
      this.velocity.y = Math.max(this.velocity.y, -this.maxFallSpeed);
    }
    this.velocity.x = this.walkVelocity.x;
    this.velocity.z = this.walkVelocity.z;

    this.grounded = false;
    if (wasGrounded && !jumping) {
      walkOnGround(physics, scene, dt);
    } else {
      slide(physics, scene, this.delta.set(this.velocity).mul(dt), false, dt);
    }
    probeGround(physics, scene);
    if (this.grounded) {
      this.airTime = 0f;
      if (this.velocity.y < 0f) {
        this.velocity.y = 0f;
      }
    } else if (this.airTime != Float.MAX_VALUE) {
      this.airTime += dt;
    }
    rememberGround();
  }

  /** Follows the ground's motion since the last step at the point it stands on. */
  private void ride(PhysicsSystem physics, Scene scene) {
    if (this.ground == null || !this.hasGroundPose || this.ground.entity() == null) {
      return;
    }
    final var m = this.ground.entity().transform().worldMatrix(this.matrix);
    if (m.equals(this.groundMatrix)) {
      return;
    }
    m.transformPosition(this.tmp.set(this.groundLocal)).sub(this.groundPoint);
    if (this.tmp.lengthSquared() > 1e-12f) {
      slide(physics, scene, this.delta.set(this.tmp), false, 0f);
    }
  }

  private void rememberGround() {
    if (!this.grounded || this.ground == null || this.ground.entity() == null) {
      this.hasGroundPose = false;
      return;
    }
    this.groundPoint.set(this.a).sub(0f, this.r, 0f); // under the lower end of the segment
    this.ground.entity().transform().worldMatrix(this.groundMatrix);
    this.groundMatrix.invert(this.matrix).transformPosition(this.groundLocal.set(this.groundPoint));
    this.hasGroundPose = true;
  }

  /** Up by the step height, along, then down again onto the ground (a step up or down, or flat ground). */
  private void walkOnGround(PhysicsSystem physics, Scene scene, float dt) {
    final float hx = this.velocity.x * dt;
    final float hz = this.velocity.z * dt;
    if (hx * hx + hz * hz < 1e-12f) {
      snap(physics, scene, this.snapDistance);
      return;
    }
    this.start.set(this.a);
    float up = 0f;
    if (this.stepHeight > 0f) {
      up = this.stepHeight;
      if (sweep(physics, scene, this.dir.set(0f, 1f, 0f), up + this.skinWidth)) {
        up = Math.max(0f, this.cast.best - this.skinWidth);
      }
      translate(0f, up, 0f);
    }
    slide(physics, scene, this.delta.set(hx, 0f, hz), true, dt);
    final float down = up + this.snapDistance + this.skinWidth;
    if (sweep(physics, scene, this.dir.set(0f, -1f, 0f), down) && walkableHit(physics, scene)) {
      translate(0f, -Math.max(0f, this.cast.best - this.skinWidth), 0f);
      return;
    }
    if (this.cast.bestCollider != null) {
      // landed on something too steep: no step, just walk into it
      translate(this.start.x - this.a.x, this.start.y - this.a.y, this.start.z - this.a.z);
      slide(physics, scene, this.delta.set(hx, 0f, hz), true, dt);
      return;
    }
    translate(0f, -up, 0f); // walked off a ledge: back to the height it was at, and fall from there
  }

  /** Moves down onto walkable ground up to {@code distance} below, if there is any. */
  private void snap(PhysicsSystem physics, Scene scene, float distance) {
    if (sweep(physics, scene, this.dir.set(0f, -1f, 0f), distance + this.skinWidth)
        && walkableHit(physics, scene)) {
      translate(0f, -Math.max(0f, this.cast.best - this.skinWidth), 0f);
    }
  }

  /** Looks just below the capsule for walkable ground. */
  private void probeGround(PhysicsSystem physics, Scene scene) {
    if (this.velocity.y > 1e-3f) {
      this.ground = null; // going up: not standing on anything
      return;
    }
    if (sweep(physics, scene, this.dir.set(0f, -1f, 0f), 2f * this.skinWidth)
        && walkableHit(physics, scene)) {
      this.grounded = true;
      this.ground = this.cast.bestCollider;
      this.groundNormal.set(this.cast.bestNormal);
      this.flags |= BELOW;
    } else {
      this.ground = null;
      this.groundNormal.set(0f, 1f, 0f);
    }
  }

  /**
   * Collide and slide: moves by {@code move} until something is in the way, then along its surface. With
   * {@code steepAsWalls} (walking) slopes too steep to walk on block like vertical walls instead of being climbed.
   */
  private void slide(PhysicsSystem physics, Scene scene, Vector3f move, boolean steepAsWalls, float dt) {
    this.remaining.set(move);
    boolean hasPlane = false;
    for (int i = 0; i < MAX_SLIDES; i++) {
      final float len = this.remaining.length();
      if (len < 1e-6f) {
        return;
      }
      this.dir.set(this.remaining).div(len);
      if (!sweep(physics, scene, this.dir, len + this.skinWidth)) {
        translate(this.remaining.x, this.remaining.y, this.remaining.z);
        return;
      }
      final float travel = Math.max(0f, this.cast.best - this.skinWidth);
      translate(this.dir.x * travel, this.dir.y * travel, this.dir.z * travel);
      final var hit = this.cast.bestCollider;
      final var n = this.normal.set(this.cast.bestNormal);
      if (walkable(n)) {
        this.flags |= BELOW;
      } else if (n.y < -0.7f) {
        this.flags |= ABOVE;
      } else {
        this.flags |= SIDES;
      }
      if (dt > 0f) {
        push(hit, n, dt);
      }
      if (steepAsWalls && !walkable(n) && n.y > 0f) {
        n.y = 0f;
        if (n.lengthSquared() < 1e-8f) {
          return;
        }
        n.normalize();
      }
      // what is left of the move, along the surface
      this.remaining.set(this.dir).mul(len - travel);
      this.remaining.fma(-this.remaining.dot(n), n);
      final float into = this.velocity.dot(n);
      if (into < 0f) {
        this.velocity.fma(-into, n); // stop moving into it (landing, hitting the head, walls)
      }
      if (hasPlane && this.remaining.dot(this.lastPlane) < 0f) {
        // squeezed between two surfaces: along their crease, or stop in a corner
        this.tmp.set(this.lastPlane).cross(n);
        if (this.tmp.lengthSquared() < 1e-8f) {
          return;
        }
        this.tmp.normalize();
        this.remaining.set(this.tmp).mul(this.remaining.dot(this.tmp));
      }
      this.lastPlane.set(n);
      hasPlane = true;
    }
  }

  /** Gives a dynamic body in the way the momentum of an inelastic collision with the character. */
  private void push(Collider hit, Vector3f n, float dt) {
    if (this.mass <= 0f) {
      return;
    }
    final var body = hit.entity().findOrNull(Rigidbody.class);
    if (body == null || body.kinematic || body.inverseMass() <= 0f) {
      return;
    }
    final float mine = -this.velocity.dot(n); // speed into the surface
    final float theirs = -body.velocity.dot(n);
    if (mine <= theirs) {
      return;
    }
    final float bodyMass = 1f / body.inverseMass();
    final float change = this.mass * (mine - theirs) / (this.mass + bodyMass);
    body.velocity.fma(-change, n);
    body.wakeUp();
  }

  /** Pushes the capsule out of colliders it overlaps (that moved into it). */
  private void depenetrate(PhysicsSystem physics, Scene scene) {
    for (int it = 0; it < MAX_DEPENETRATION; it++) {
      physics.near(scene, this.a, this.b, this.r, this.nearby);
      float deepest = 0f;
      this.cast.reset(this.a, this.b, this.r, 0f);
      for (int i = 0; i < this.nearby.size(); i++) {
        final var c = this.nearby.get(i);
        if (!c.isEnabled() || !this.blocks.test(c)) {
          continue;
        }
        final float depth = this.cast.penetration(c, this.tmp);
        if (depth > deepest) {
          deepest = depth;
          this.normal.set(this.tmp);
        }
      }
      this.nearby.clear();
      if (deepest <= 1e-4f) {
        return;
      }
      final float d = deepest + this.skinWidth * 0.5f;
      translate(this.normal.x * d, this.normal.y * d, this.normal.z * d);
    }
  }

  private boolean sweep(PhysicsSystem physics, Scene scene, Vector3f direction, float distance) {
    return physics.cast(scene, this.cast, this.a, this.b, this.r, direction, distance, this.blocks);
  }

  private void translate(float x, float y, float z) {
    if (x == 0f && y == 0f && z == 0f) {
      return;
    }
    this.a.add(x, y, z);
    this.b.add(x, y, z);
    entity().transform().translateWorld(this.shift.set(x, y, z));
  }
}
