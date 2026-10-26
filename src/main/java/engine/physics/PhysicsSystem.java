package engine.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.ecs.Transform;
import engine.messaging.IMessageService;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;
import engine.spatial.SpatialGrid;
import lombok.Getter;

/**
 * Semi-implicit Euler integration plus an iterative impulse solver with positional correction and Coulomb friction.
 * Contact impulses are warm-started from the previous step, so stacks converge quickly. Candidate pairs come from a
 * uniform-grid broadphase over bounding spheres (with a separate path for colliders much larger than a cell, such as
 * ground planes), bodies that come to rest fall asleep, and entities may be parented: positions and velocities are in
 * world space and are converted back into the parent's space.
 *
 * <p>
 * Kinematic bodies and static colliders that game code moves or turns get a velocity and spin estimated from that
 * motion, so they wake, carry and drag along the bodies resting on them.
 *
 * <p>
 * Emits {@link CollisionEvent}s (also for triggers) when overlaps start and stop, and offers {@link #raycast} and
 * {@link #overlapSphere} queries. Queries find their candidates through the broadphase grid of the last step (exact
 * shapes are always tested at their current position), so they cost little in large scenes; before the first step,
 * or after colliders were added or removed, they fall back to testing every collider.
 *
 * <p>
 * A step does not allocate once it has warmed up: collider shapes are cached and refreshed in place, narrowphase tests
 * fill a reused contact, pairs are pooled and the pair bookkeeping uses primitive hash tables.
 */
public class PhysicsSystem implements ISystem {
  private static final float SLOP = 0.005f;
  private static final float CORRECTION_PERCENT = 0.8f;
  /** Approach speeds below this do not bounce; avoids endless micro-bounces of resting bodies. */
  private static final float REST_SPEED = 1.0f;
  /** Bounding spheres are grown by this much so near-touching pairs reach the narrowphase. */
  private static final float PAIR_MARGIN = 0.05f;
  /**
   * Shapes closer than this are solved as speculative contacts: they may approach until they touch but not further.
   * Keeps a resting contact (and its warm-start impulses) alive through tiny bounces.
   */
  private static final float SPECULATIVE_MARGIN = 0.02f;
  /** Spin is capped so a freak impulse cannot make a body rotate faster than a step can resolve. */
  private static final float MAX_ANGULAR_SPEED = 50f;
  /** Stand in for the motion and inertia of a static collider; never written to because its inverse mass is zero. */
  private static final Vector3f NO_VELOCITY = new Vector3f();
  private static final Vector3f NO_SPIN = new Vector3f();
  private static final Matrix3f NO_INERTIA = new Matrix3f().zero();

  public final Vector3f gravity = new Vector3f(0f, -9.81f, 0f);
  /** Rounds of positional correction per step. */
  public int solverIterations = 4;
  /** Rounds of the impulse solver per step; more makes tall stacks steadier. */
  public int velocityIterations = 10;
  /** Start each persisting contact from the impulses it ended the last step with. */
  public boolean warmStarting = true;
  boolean warmFrictionOn = true;
  public boolean sleepingEnabled = true;
  /** Bodies slower than this (m/s) count as resting. */
  public float sleepSpeed = 0.1f;
  /** Bodies turning slower than this (rad/s) count as resting. */
  public float sleepAngularSpeed = 0.2f;
  /** Seconds a body must keep resting before it falls asleep. */
  public float sleepTime = 0.5f;

  @Getter private final PhysicsStats stats = new PhysicsStats();
  private final IMessageService messages;
  private PairTable active = new PairTable();
  private PairTable now = new PairTable();
  private SpatialGrid grid;
  private final Vector3f tmp = new Vector3f();
  private final Vector3f tmpPos = new Vector3f();
  private final Vector3f tmpCenter = new Vector3f();
  private final Vector3f relativeVelocity = new Vector3f();
  private final Vector3f cross = new Vector3f();
  private final Vector3f crossB = new Vector3f();
  private final Vector3f impulse = new Vector3f();
  private final Quaternionf deltaRotation = new Quaternionf();
  private final Quaternionf tmpRotation = new Quaternionf();
  private final Matrix4f tmpMatrix = new Matrix4f();
  private final Narrowphase.Contact contact = new Narrowphase.Contact();
  private final ContactCache contactCache = new ContactCache();
  private final Vector3f[] warmFriction = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
  private int step;
  private float dt;

  // broadphase scratch, reused every step
  private final List<Pair> pairs = new ArrayList<>();
  private final List<Pair> sparePairs = new ArrayList<>();
  private final List<Pair> solvable = new ArrayList<>();
  private final LongSet seen = new LongSet();
  private float[] cx = new float[64];
  private float[] cy = new float[64];
  private float[] cz = new float[64];
  private float[] radius = new float[64];
  private Rigidbody[] bodyOf = new Rigidbody[64];
  private List<Collider> colliders;
  private final SpatialGrid.CellVisitor cellPairs = this::visitCell;

  // query scratch: collider index -> stamp of the last query that tested it
  private int[] queryStamps = new int[64];
  private int queryStamp;

  /** Test hook: pair every collider with every other one instead of using the grid. */
  boolean bruteForceBroadphase;

  public PhysicsSystem(IMessageService messages) {
    this.messages = messages;
  }


  /**
   * A candidate pair; instances are pooled and only valid until the next {@link #findPairs}. Also carries the contact
   * constraint (one row per manifold point) while the solver runs.
   */
  static final class Pair {
    private Collider a;
    private Collider b;
    private Rigidbody ra;
    private Rigidbody rb;
    private boolean hit;
    private long key;

    private final Narrowphase.Contact manifold = new Narrowphase.Contact();
    private int count;
    private float invA;
    private float invB;
    private float mu;
    private Matrix3f ia;
    private Matrix3f ib;
    private Vector3f va;
    private Vector3f vb;
    private Vector3f wa;
    private Vector3f wb;
    private final Vector3f t1 = new Vector3f();
    private final Vector3f t2 = new Vector3f();
    private final Vector3f[] rA = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
    private final Vector3f[] rB = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
    private final float[] massN = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] massT1 = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] massT2 = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] bias = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] jn = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] jt1 = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] jt2 = new float[Narrowphase.Contact.MAX_POINTS];

    Pair() {
      this.manifold.wantPoints = true;
    }

    Collider a() {
      return this.a;
    }

    Collider b() {
      return this.b;
    }

    Rigidbody ra() {
      return this.ra;
    }

    Rigidbody rb() {
      return this.rb;
    }
  }

  private static float awakeInverseMass(Rigidbody r) {
    return r == null || r.sleeping ? 0f : r.inverseMass();
  }

  @Override
  public void update(Scene scene, double dtD) {
    final float dt = (float) dtD;
    this.dt = dt;
    final var bodies = scene.components(Rigidbody.class);
    final var colliders = scene.components(Collider.class);
    for (int i = 0; i < bodies.size(); i++) {
      bodies.get(i).touchedThisStep = false;
    }
    if (this.warmStarting) {
      this.contactCache.swap();
    } else {
      this.contactCache.clear();
    }
    integrate(bodies, dt);

    final var pairs = findPairs(colliders);
    trackStaticMotion(colliders.size(), dt);

    // Before solving, an awake body that rests on something has already gained one step of gravity; a sleeper is
    // only woken by bodies clearly faster than that, otherwise a stack could never fall asleep.
    final float wakeSpeed = this.sleepSpeed + 2f * dt * this.gravity.length();
    final float wakeSpeedSq = wakeSpeed * wakeSpeed;

    // detection: events, wake-ups and "is resting on something" bookkeeping
    this.now.clear();
    int contacts = 0;
    for (int i = 0; i < pairs.size(); i++) {
      final var p = pairs.get(i);
      final boolean solid = !p.a.trigger && !p.b.trigger;
      p.hit = Narrowphase.test(p.a, p.b, this.contact, solid ? SPECULATIVE_MARGIN : 0f);
      if (!p.hit) {
        continue;
      }
      final float depth = this.contact.depth();
      if (depth > 0f) { // events report real overlaps only
        contacts++;
        final var ea = p.a.entity();
        final var eb = p.b.entity();
        this.now.put(key(ea, eb), ea, eb);
      }
      if (solid) {
        if (depth > -SLOP) { // resting on it, give or take the slop
          touch(p.ra);
          touch(p.rb);
        }
        // also within the speculative margin: a sleeper must wake before it is hit, or it would act like a wall
        wakeOnImpact(p.ra, p.rb, p.b, wakeSpeedSq);
        wakeOnImpact(p.rb, p.ra, p.a, wakeSpeedSq);
      }
    }
    for (int i = 0; i < bodies.size(); i++) {
      final var b = bodies.get(i);
      if (b.sleeping && !b.touchedThisStep) {
        b.wakeUp(); // lost its support (or never had one)
      }
    }

    // only pairs that touched at detection are solved: the rest were merely close
    this.solvable.clear();
    for (int i = 0; i < pairs.size(); i++) {
      final var p = pairs.get(i);
      if (p.hit && !p.a.trigger && !p.b.trigger
          && (awakeInverseMass(p.ra) > 0f || awakeInverseMass(p.rb) > 0f)) {
        this.solvable.add(p);
      }
    }
    // positions first (entities move, so the shape is tested again each round), then velocities on the final contacts
    for (int it = 0; it < this.solverIterations; it++) {
      for (int i = 0; i < this.solvable.size(); i++) {
        final var p = this.solvable.get(i);
        if (Narrowphase.test(p.a, p.b, this.contact)) {
          correctPosition(p, this.contact);
        }
      }
    }
    this.step++;
    for (int i = 0; i < this.solvable.size(); i++) {
      prepareConstraint(this.solvable.get(i));
    }
    if (this.warmStarting) { // only now: every rebound target above must see the velocities before any impulse
      for (int i = 0; i < this.solvable.size(); i++) {
        warmStart(this.solvable.get(i));
      }
    }

    for (int it = 0; it < this.velocityIterations; it++) {
      for (int i = 0; i < this.solvable.size(); i++) {
        solveConstraint(this.solvable.get(i));
      }
    }
    if (this.warmStarting) {
      for (int i = 0; i < this.solvable.size(); i++) {
        final var p = this.solvable.get(i);
        if (p.count > 0) {
          this.contactCache.store(p.key, p.manifold, p.count, p.jn, p.jt1, p.jt2, p.t1, p.t2);
        }
      }
    }

    updateSleep(bodies, dt);
    sendEvents();

    int sleeping = 0;
    for (int i = 0; i < bodies.size(); i++) {
      if (bodies.get(i).sleeping) {
        sleeping++;
      }
    }
    this.stats.colliders = colliders.size();
    this.stats.bodies = bodies.size();
    this.stats.sleepingBodies = sleeping;
    this.stats.candidatePairs = pairs.size();
    this.stats.contacts = contacts;
  }

  private static void touch(Rigidbody r) {
    if (r != null) {
      r.touchedThisStep = true;
    }
  }

  /**
   * A sleeping body is woken by an awake body that is moving, a kinematic body that is being moved or turned, or a
   * static collider that game code moves.
   */
  private static void wakeOnImpact(Rigidbody sleeper, Rigidbody other, Collider otherCollider, float wakeSpeedSq) {
    if (sleeper == null || !sleeper.sleeping) {
      return;
    }
    if (other == null) {
      if (otherCollider.moving) {
        sleeper.wakeUp();
      }
    } else if (!other.sleeping) {
      final boolean moving = other.kinematic
          ? other.velocity.lengthSquared() > 0.0025f || other.angularVelocity.lengthSquared() > 0.0025f
          : other.velocity.lengthSquared() > wakeSpeedSq;
      if (moving) {
        sleeper.wakeUp();
      }
    }
  }

  /**
   * Velocity and spin of static colliders whose entity was moved by code since the last step, estimated from the
   * change of the world matrix at the entity origin. Unmoved colliders cost one matrix comparison.
   */
  private void trackStaticMotion(int n, float dt) {
    for (int i = 0; i < n; i++) {
      final var c = this.colliders.get(i);
      if (this.bodyOf[i] != null || c.trigger) {
        continue;
      }
      final var m = c.cachedMatrix();
      if (c.hasLastPose && m.equals(c.lastMatrix)) {
        if (c.moving) {
          c.moving = false;
          c.motionVelocity.zero();
          c.motionSpin.zero();
        }
        continue;
      }
      m.getTranslation(this.tmpPos);
      m.getNormalizedRotation(this.tmpRotation);
      if (c.hasLastPose && dt > 0f) {
        c.motionVelocity.set(this.tmpPos).sub(c.motionOrigin).div(dt);
        spin(c.lastRotation, this.tmpRotation, dt, c.motionSpin);
        c.moving = true;
      }
      c.lastMatrix.set(m);
      c.motionOrigin.set(this.tmpPos);
      c.lastRotation.set(this.tmpRotation);
      c.hasLastPose = true;
    }
  }

  /** Angular velocity (axis * rad/s) that turns {@code from} into {@code to} in {@code dt}. */
  static Vector3f spin(Quaternionf from, Quaternionf to, float dt, Vector3f dest) {
    // delta = to * from^-1, taken the short way round
    final float fx = -from.x;
    final float fy = -from.y;
    final float fz = -from.z;
    final float fw = from.w;
    float x = to.w * fx + to.x * fw + to.y * fz - to.z * fy;
    float y = to.w * fy - to.x * fz + to.y * fw + to.z * fx;
    float z = to.w * fz + to.x * fy - to.y * fx + to.z * fw;
    float w = to.w * fw - to.x * fx - to.y * fy - to.z * fz;
    if (w < 0f) {
      x = -x;
      y = -y;
      z = -z;
      w = -w;
    }
    final float sinHalf = (float) Math.sqrt(x * x + y * y + z * z);
    if (sinHalf < 1e-7f || dt <= 0f) {
      return dest.zero();
    }
    final float angle = 2f * (float) Math.atan2(sinHalf, w);
    return dest.set(x, y, z).mul(angle / (sinHalf * dt));
  }

  private void integrate(List<Rigidbody> bodies, float dt) {
    for (int i = 0; i < bodies.size(); i++) {
      final var b = bodies.get(i);
      final var t = b.entity().transform();
      t.worldPosition(this.tmpPos);
      if (b.sleeping) {
        final boolean disturbed = !this.sleepingEnabled || !b.allowSleep || b.velocity.lengthSquared() > 0f
            || b.angularVelocity.lengthSquared() > 0f || this.tmpPos.distanceSquared(b.sleepPosition) > 1e-8f
            || !t.rotation().equals(b.sleepRotation);
        if (!disturbed) {
          continue;
        }
        b.wakeUp();
      }
      if (b.kinematic) {
        // velocity and spin of a moved-by-code body are estimated from how it moved and turned since the last step
        t.worldMatrix(this.tmpMatrix).getNormalizedRotation(this.tmpRotation);
        if (b.hasLastPosition && dt > 0f) {
          b.velocity.set(this.tmpPos).sub(b.lastPosition).div(dt);
          spin(b.lastRotation, this.tmpRotation, dt, b.angularVelocity);
        }
        b.lastPosition.set(this.tmpPos);
        b.lastRotation.set(this.tmpRotation);
        b.hasLastPosition = true;
      } else {
        if (b.useGravity) {
          b.velocity.fma(dt, this.gravity);
        }
        b.velocity.mul(Math.max(0f, 1f - b.linearDamping * dt));
        t.translateWorld(this.tmp.set(b.velocity).mul(dt));
        if (!b.lockRotation) {
          turn(b, t, dt);
        }
      }
    }
  }

  /** Turns the entity by its angular velocity, about its centre of mass rather than its origin. */
  private void turn(Rigidbody b, Transform t, float dt) {
    final var w = b.angularVelocity;
    w.mul(Math.max(0f, 1f - b.angularDamping * dt));
    final float speed = w.length();
    if (speed < 1e-6f) {
      return;
    }
    if (speed > MAX_ANGULAR_SPEED) {
      w.mul(MAX_ANGULAR_SPEED / speed);
    }
    final float rate = Math.min(speed, MAX_ANGULAR_SPEED);
    this.deltaRotation.setAngleAxis(rate * dt, w.x / speed, w.y / speed, w.z / speed);
    final var only = singleSolidCollider(b.entity());
    if (only != null && only.offset.lengthSquared() == 0f || massCenter(b.entity(), this.tmpCenter, true) == 0) {
      t.rotateWorld(this.deltaRotation); // the centre of mass is the origin
      return;
    }
    t.worldPosition(this.tmpPos);
    final var arm = this.tmpCenter.sub(this.tmpPos); // origin -> centre of mass, kept in place by the turn
    this.tmp.set(arm);
    t.rotateWorld(this.deltaRotation);
    this.deltaRotation.transform(this.tmp);
    t.translateWorld(arm.sub(this.tmp));
  }

  /** The entity's only non-trigger collider, or null if it has none or several. */
  private static Collider singleSolidCollider(Entity e) {
    Collider found = null;
    for (int i = 0; i < e.componentCount(); i++) {
      if (e.componentAt(i) instanceof Collider c && c.isEnabled() && !c.trigger) {
        if (found != null) {
          return null;
        }
        found = c;
      }
    }
    return found;
  }

  /**
   * Centre of mass of the entity's non-trigger colliders, weighted by volume. {@code refresh} recomputes their world
   * shapes first (needed before the broadphase has refreshed them this step). Returns the number of colliders; with
   * none, {@code dest} is left untouched.
   */
  private static int massCenter(Entity e, Vector3f dest, boolean refresh) {
    int count = 0;
    float volume = 0f;
    float x = 0f;
    float y = 0f;
    float z = 0f;
    float sx = 0f;
    float sy = 0f;
    float sz = 0f;
    for (int i = 0; i < e.componentCount(); i++) {
      if (e.componentAt(i) instanceof Collider c && c.isEnabled() && !c.trigger) {
        if (refresh) {
          c.refresh();
        }
        c.cachedCenter(dest);
        final float v = c.cachedVolume();
        x += dest.x * v;
        y += dest.y * v;
        z += dest.z * v;
        sx += dest.x;
        sy += dest.y;
        sz += dest.z;
        volume += v;
        count++;
      }
    }
    if (count > 1) {
      if (volume > 1e-12f) {
        dest.set(x / volume, y / volume, z / volume);
      } else {
        dest.set(sx / count, sy / count, sz / count);
      }
    }
    return count;
  }

  /** Adds {@code value * axis * axis^T} to {@code dest}: inertia {@code value} about a principal axis. */
  static void addAxisInertia(Matrix3f dest, Vector3f axis, float value) {
    dest.m00 += value * axis.x * axis.x;
    dest.m11 += value * axis.y * axis.y;
    dest.m22 += value * axis.z * axis.z;
    final float xy = value * axis.x * axis.y;
    final float xz = value * axis.x * axis.z;
    final float yz = value * axis.y * axis.z;
    dest.m01 += xy;
    dest.m10 += xy;
    dest.m02 += xz;
    dest.m20 += xz;
    dest.m12 += yz;
    dest.m21 += yz;
  }

  private void updateSleep(List<Rigidbody> bodies, float dt) {
    final float limit = this.sleepSpeed * this.sleepSpeed;
    final float spinLimit = this.sleepAngularSpeed * this.sleepAngularSpeed;
    for (int i = 0; i < bodies.size(); i++) {
      final var b = bodies.get(i);
      if (b.kinematic || b.sleeping || !b.allowSleep || !this.sleepingEnabled) {
        b.sleepTimer = 0f;
        continue;
      }
      // only a body that rests on something may sleep: floating bodies drifting slowly must keep moving
      if (b.touchedThisStep && b.velocity.lengthSquared() < limit && b.angularVelocity.lengthSquared() < spinLimit) {
        b.sleepTimer += dt;
      } else {
        b.sleepTimer = 0f;
      }
      if (b.sleepTimer >= this.sleepTime) {
        b.sleeping = true;
        b.velocity.zero();
        b.angularVelocity.zero();
        b.entity().transform().worldPosition(b.sleepPosition);
        b.sleepRotation.set(b.entity().transform().rotation());
      }
    }
  }

  /**
   * Refreshes every collider's cached shape and returns the candidate pairs. The returned list and its pairs are
   * reused by the next call.
   */
  List<Pair> findPairs(List<Collider> colliders) {
    final int n = colliders.size();
    ensureCapacity(n);
    this.colliders = colliders;
    for (int i = 0; i < this.pairs.size(); i++) {
      final var p = this.pairs.get(i);
      p.a = null;
      p.b = null;
      p.ra = null;
      p.rb = null;
      this.sparePairs.add(p);
    }
    this.pairs.clear();
    this.seen.clear();

    float dynamicRadiusSum = 0f;
    int dynamicCount = 0;
    for (int i = 0; i < n; i++) {
      final var col = colliders.get(i);
      col.refresh();
      col.cachedCenter(this.tmpCenter);
      this.cx[i] = this.tmpCenter.x;
      this.cy[i] = this.tmpCenter.y;
      this.cz[i] = this.tmpCenter.z;
      this.radius[i] = col.cachedRadius();
      final var body = col.entity().findOrNull(Rigidbody.class);
      this.bodyOf[i] = body;
      if (body != null && !body.kinematic) {
        dynamicRadiusSum += this.radius[i];
        dynamicCount++;
      }
    }

    if (this.bruteForceBroadphase) {
      for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
          tryPair(i, j);
        }
      }
      return this.pairs;
    }

    // cells a bit larger than the typical moving body, so a body touches few cells and cells hold few bodies
    final float cell = dynamicCount > 0 ? Math.max(0.5f, 2.2f * dynamicRadiusSum / dynamicCount) : 4f;
    if (this.grid == null || Math.abs(this.grid.cellSize() - cell) > 0.25f * cell) {
      this.grid = new SpatialGrid(cell);
    } else {
      this.grid.clear();
    }
    this.stats.cellSize = this.grid.cellSize();
    for (int i = 0; i < n; i++) {
      this.grid.insert(i, this.cx[i], this.cy[i], this.cz[i], this.radius[i] + PAIR_MARGIN);
    }
    this.grid.forEachCell(this.cellPairs);
    final var large = this.grid.large();
    for (int k = 0; k < large.size(); k++) {
      for (int j = 0; j < n; j++) {
        if (j != large.get(k)) {
          tryPair(large.get(k), j);
        }
      }
    }
    return this.pairs;
  }

  private void ensureCapacity(int n) {
    if (this.cx.length < n) {
      final int size = Math.max(n, this.cx.length * 2);
      this.cx = new float[size];
      this.cy = new float[size];
      this.cz = new float[size];
      this.radius = new float[size];
      this.bodyOf = new Rigidbody[size];
    }
  }

  private void visitCell(SpatialGrid.IntList ids, float x0, float y0, float z0, float x1, float y1, float z1) {
    for (int a = 0; a < ids.size(); a++) {
      for (int b = a + 1; b < ids.size(); b++) {
        tryPair(ids.get(a), ids.get(b));
      }
    }
  }

  private void tryPair(int i, int j) {
    // the cheap rejects come first: most candidates die here without touching the set
    final var bi = this.bodyOf[i];
    final var bj = this.bodyOf[j];
    final var a = this.colliders.get(i);
    final var b = this.colliders.get(j);
    if (bi == null && bj == null && !a.trigger && !b.trigger) {
      return; // static vs static: nothing to resolve and no events wanted
    }
    final float dx = this.cx[i] - this.cx[j];
    final float dy = this.cy[i] - this.cy[j];
    final float dz = this.cz[i] - this.cz[j];
    final float reach = this.radius[i] + this.radius[j] + 2f * PAIR_MARGIN;
    if (dx * dx + dy * dy + dz * dz > reach * reach) {
      return;
    }
    if (a.entity() == b.entity()) {
      return;
    }
    if (!this.seen.add((long) Math.min(i, j) << 32 | Math.max(i, j))) {
      return; // reached through another cell already
    }
    final var p = this.sparePairs.isEmpty() ? new Pair() : this.sparePairs.remove(this.sparePairs.size() - 1);
    // a stable order, so the contact normal and the cached impulses keep their meaning from step to step
    final boolean swap = a.id > b.id;
    p.a = swap ? b : a;
    p.b = swap ? a : b;
    p.ra = swap ? bj : bi;
    p.rb = swap ? bi : bj;
    p.key = ContactCache.key(p.a, p.b);
    p.hit = false;
    this.pairs.add(p);
  }

  /** Pushes the two bodies apart along the normal, split by inverse mass. */
  private void correctPosition(Pair p, Narrowphase.Contact c) {
    final float invA = awakeInverseMass(p.ra);
    final float invB = awakeInverseMass(p.rb);
    final float total = invA + invB;
    if (total <= 0f) {
      return;
    }
    final var n = c.normal();
    final float corr = Math.max(c.depth() - SLOP, 0f) / total * CORRECTION_PERCENT;
    if (corr > 0f) {
      if (invA > 0f) {
        p.a.entity().transform().translateWorld(this.tmp.set(n).mul(-corr * invA));
        refreshColliders(p.a.entity());
      }
      if (invB > 0f) {
        p.b.entity().transform().translateWorld(this.tmp.set(n).mul(corr * invB));
        refreshColliders(p.b.entity());
      }
    }
  }

  /**
   * Centre of mass and world-space inverse inertia tensor of a body, computed once per step from all its non-trigger
   * colliders (mass split by volume, combined with the parallel-axis theorem). A kinematic body moves about its
   * origin, where its velocity is measured, and has no inertia the solver could change.
   */
  private void prepare(Rigidbody b) {
    if (b.preparedStep == this.step) {
      return;
    }
    b.preparedStep = this.step;
    final var e = b.entity();
    final var inv = b.invInertiaWorld.zero();
    if (b.kinematic || massCenter(e, b.centerOfMass, false) == 0) {
      e.transform().worldPosition(b.centerOfMass);
      return;
    }
    if (b.lockRotation) {
      return;
    }
    float volume = 0f;
    int count = 0;
    for (int i = 0; i < e.componentCount(); i++) {
      if (e.componentAt(i) instanceof Collider c && c.isEnabled() && !c.trigger) {
        volume += c.cachedVolume();
        count++;
      }
    }
    final var inertia = b.inertiaWorld.zero();
    final var d = this.tmp;
    for (int i = 0; i < e.componentCount(); i++) {
      if (e.componentAt(i) instanceof Collider c && c.isEnabled() && !c.trigger) {
        final float m = volume > 1e-12f ? b.mass * c.cachedVolume() / volume : b.mass / count;
        c.addCachedInertia(m, inertia);
        if (count > 1) { // parallel axis: m * (|d|^2 E - d d^T)
          c.cachedCenter(d);
          d.sub(b.centerOfMass);
          final float dd = d.lengthSquared();
          inertia.m00 += m * (dd - d.x * d.x);
          inertia.m11 += m * (dd - d.y * d.y);
          inertia.m22 += m * (dd - d.z * d.z);
          inertia.m01 -= m * d.x * d.y;
          inertia.m10 -= m * d.x * d.y;
          inertia.m02 -= m * d.x * d.z;
          inertia.m20 -= m * d.x * d.z;
          inertia.m12 -= m * d.y * d.z;
          inertia.m21 -= m * d.y * d.z;
        }
      }
    }
    // a flat or needle-thin shape has (almost) no inertia about some axis; keep the tensor invertible
    final float floor = 1e-6f * Math.max(b.mass, 1e-6f);
    inertia.m00 = Math.max(inertia.m00, floor);
    inertia.m11 = Math.max(inertia.m11, floor);
    inertia.m22 = Math.max(inertia.m22, floor);
    inertia.invert(inv);
  }

  /** Test hook: recomputes and returns nothing; the body's centre of mass and inertia fields are refreshed. */
  void computeMassProperties(Rigidbody b) {
    final var e = b.entity();
    for (int i = 0; i < e.componentCount(); i++) {
      if (e.componentAt(i) instanceof Collider c) {
        c.refresh();
      }
    }
    b.preparedStep = this.step - 1;
    prepare(b);
  }

  /** Number of contact manifolds kept for warm starting the next step. */
  int cachedContacts() {
    return this.contactCache.size();
  }

  /** Turns the pair's final contact manifold into constraint rows: arms, effective masses and a rebound target. */
  private void prepareConstraint(Pair p) {
    p.count = 0;
    final float invA = awakeInverseMass(p.ra);
    final float invB = awakeInverseMass(p.rb);
    if (invA + invB <= 0f) {
      return;
    }
    final var c = p.manifold;
    if (!Narrowphase.test(p.a, p.b, c, SPECULATIVE_MARGIN) || c.count() == 0) {
      return;
    }
    p.invA = invA;
    p.invB = invB;
    final var refA = motionReference(p.ra, p.a);
    final var refB = motionReference(p.rb, p.b);
    p.ia = invA > 0f ? p.ra.invInertiaWorld : NO_INERTIA;
    p.ib = invB > 0f ? p.rb.invInertiaWorld : NO_INERTIA;
    p.va = p.ra != null ? p.ra.velocity : p.a.motionVelocity;
    p.vb = p.rb != null ? p.rb.velocity : p.b.motionVelocity;
    p.wa = p.ra != null ? p.ra.angularVelocity : p.a.motionSpin;
    p.wb = p.rb != null ? p.rb.angularVelocity : p.b.motionSpin;
    p.mu = (float) Math.sqrt(friction(p.ra) * friction(p.rb));

    final var n = c.normal();
    if (Math.abs(n.x) > 0.57735f) {
      p.t1.set(n.y, -n.x, 0f);
    } else {
      p.t1.set(0f, n.z, -n.y);
    }
    p.t1.normalize();
    n.cross(p.t1, p.t2);

    final float e = combineMin(p.ra, p.rb);
    p.count = c.count();
    for (int k = 0; k < p.count; k++) {
      if (refA != null) {
        p.rA[k].set(c.point(k)).sub(refA);
      } else {
        p.rA[k].zero();
      }
      if (refB != null) {
        p.rB[k].set(c.point(k)).sub(refB);
      } else {
        p.rB[k].zero();
      }
      final float vn = pointVelocity(p, k, this.relativeVelocity).dot(n);
      final float gap = -c.depth(k);
      if (vn < -REST_SPEED && vn * this.dt <= -gap) {
        p.bias[k] = -e * vn; // an impact (now, or before the next step): rebound
      } else if (gap > 0f) {
        p.bias[k] = this.dt > 0f ? -gap / this.dt : 0f; // a gap: it may close this step, but not more
      } else {
        p.bias[k] = 0f;
      }
      p.massN[k] = 1f / (invA + invB + angularTerm(p.ia, p.rA[k], n) + angularTerm(p.ib, p.rB[k], n));
      p.massT1[k] = 1f / (invA + invB + angularTerm(p.ia, p.rA[k], p.t1) + angularTerm(p.ib, p.rB[k], p.t1));
      p.massT2[k] = 1f / (invA + invB + angularTerm(p.ia, p.rA[k], p.t2) + angularTerm(p.ib, p.rB[k], p.t2));
      p.jn[k] = 0f;
      p.jt1[k] = 0f;
      p.jt2[k] = 0f;
    }
  }

  /** Applies the impulses the pair's contacts ended the last step with. */
  private void warmStart(Pair p) {
    if (p.count == 0) {
      return;
    }
    final var n = p.manifold.normal();
    if (this.contactCache.lookup(p.key, p.manifold, p.jn, this.warmFriction)) {
      for (int k = 0; k < p.count; k++) {
        final float limit = p.mu * p.jn[k];
        p.jt1[k] = Math.max(-limit, Math.min(limit, this.warmFriction[k].dot(p.t1)));
        p.jt2[k] = Math.max(-limit, Math.min(limit, this.warmFriction[k].dot(p.t2)));
        applyImpulse(p, k, n, p.jn[k]);
        applyImpulse(p, k, p.t1, p.jt1[k]);
        applyImpulse(p, k, p.t2, p.jt2[k]);
      }
    }
  }

  /**
   * The point a side's velocity and spin refer to: the centre of mass of an awake body, the origin of a kinematic
   * body or of a static collider that is being moved; null if that side does not move this step.
   */
  private Vector3f motionReference(Rigidbody r, Collider c) {
    if (r != null) {
      if (r.sleeping) {
        return null;
      }
      prepare(r);
      return r.centerOfMass;
    }
    return c.moving ? c.motionOrigin : null;
  }

  /** {@code dir . ((I (r x dir)) x r)}: how much turning a body adds to the effective mass along {@code dir}. */
  private float angularTerm(Matrix3f invInertia, Vector3f r, Vector3f dir) {
    r.cross(dir, this.cross);
    invInertia.transform(this.cross);
    return this.cross.cross(r, this.crossB).dot(dir);
  }

  /** Velocity of B's contact point relative to A's. */
  private Vector3f pointVelocity(Pair p, int k, Vector3f dest) {
    dest.set(p.vb).sub(p.va);
    dest.add(p.wb.cross(p.rB[k], this.cross));
    dest.sub(p.wa.cross(p.rA[k], this.cross));
    return dest;
  }

  /** Applies {@code dir * amount} to B and its opposite to A, at the contact point. */
  private void applyImpulse(Pair p, int k, Vector3f dir, float amount) {
    final var j = this.impulse.set(dir).mul(amount);
    if (p.invA > 0f) {
      p.va.fma(-p.invA, j);
      p.rA[k].cross(j, this.cross);
      p.wa.sub(p.ia.transform(this.cross));
    }
    if (p.invB > 0f) {
      p.vb.fma(p.invB, j);
      p.rB[k].cross(j, this.cross);
      p.wb.add(p.ib.transform(this.cross));
    }
  }

  /**
   * One round of sequential impulses with accumulated, clamped impulses: friction first (bounded by the normal
   * impulses so far), then non-penetration, so the last word in every round is the constraint that matters most.
   */
  private void solveConstraint(Pair p) {
    final var n = p.manifold.normal();
    final var rv = this.relativeVelocity;
    for (int k = 0; k < p.count; k++) {
      final float limit = p.mu * p.jn[k];
      float dj = -p.massT1[k] * pointVelocity(p, k, rv).dot(p.t1);
      float total = Math.max(-limit, Math.min(limit, p.jt1[k] + dj));
      dj = total - p.jt1[k];
      p.jt1[k] = total;
      applyImpulse(p, k, p.t1, dj);

      dj = -p.massT2[k] * pointVelocity(p, k, rv).dot(p.t2);
      total = Math.max(-limit, Math.min(limit, p.jt2[k] + dj));
      dj = total - p.jt2[k];
      p.jt2[k] = total;
      applyImpulse(p, k, p.t2, dj);
    }
    for (int k = 0; k < p.count; k++) {
      float dj = p.massN[k] * (p.bias[k] - pointVelocity(p, k, rv).dot(n));
      final float total = Math.max(p.jn[k] + dj, 0f);
      dj = total - p.jn[k];
      p.jn[k] = total;
      applyImpulse(p, k, n, dj);
    }
  }

  /** The solver moved an entity: later contact tests in this step must see its colliders at the new position. */
  private static void refreshColliders(Entity e) {
    for (int i = 0; i < e.componentCount(); i++) {
      if (e.componentAt(i) instanceof Collider c) {
        c.refresh();
      }
    }
  }

  private static float combineMin(Rigidbody a, Rigidbody b) {
    if (a == null) {
      return b.restitution;
    }
    if (b == null) {
      return a.restitution;
    }
    return Math.min(a.restitution, b.restitution);
  }

  private static float friction(Rigidbody r) {
    return r == null ? 0.5f : r.friction;
  }

  private static long key(Entity a, Entity b) {
    final long i = Math.min(a.id(), b.id());
    final long j = Math.max(a.id(), b.id());
    return i << 32 ^ j;
  }

  private void sendEvents() {
    final var now = this.now;
    final var before = this.active;
    for (int s = 0; s < now.capacity(); s++) {
      if (now.occupied(s) && !before.contains(now.key(s))) {
        emit(now.first(s), now.second(s), true);
      }
    }
    for (int s = 0; s < before.capacity(); s++) {
      if (before.occupied(s) && !now.contains(before.key(s))) {
        emit(before.first(s), before.second(s), false);
      }
    }
    this.active = now;
    this.now = before; // cleared at the start of the next detection
  }

  private void emit(Entity a, Entity b, boolean started) {
    this.messages.send(MessageObject.builder().sender(this).queue(MessageQueue.COLLISION)
        .payload(new CollisionEvent(a, b, started)).build());
  }


  /** Nearest non-trigger collider hit by the ray within {@code maxDistance}; {@code dir} need not be normalized. */
  public Optional<RayHit> raycast(Scene scene, Vector3f origin, Vector3f dir, float maxDistance) {
    return raycast(scene, origin, dir, maxDistance, c -> !c.trigger);
  }

  /** Like {@link #raycast(Scene, Vector3f, Vector3f, float)} but only considers colliders accepted by the filter. */
  public Optional<RayHit> raycast(Scene scene, Vector3f origin, Vector3f dir, float maxDistance,
      Predicate<Collider> accept) {
    if (dir.lengthSquared() < 1e-12f) {
      return Optional.empty();
    }
    final var q = new RayQuery(origin, new Vector3f(dir).normalize(), maxDistance, accept);
    final var all = scene.components(Collider.class);
    if (!gridMatches(all)) {
      for (int i = 0; i < all.size(); i++) {
        q.test(all.get(i));
      }
    } else {
      final int stamp = nextQueryStamp();
      final var large = this.grid.large();
      for (int k = 0; k < large.size(); k++) {
        testOnce(q, large.get(k), stamp);
      }
      walkGrid(q, stamp);
    }
    if (q.bestCollider == null) {
      return Optional.empty();
    }
    return Optional.of(new RayHit(q.bestCollider, new Vector3f(origin).fma(q.best, q.dir), q.bestNormal, q.best));
  }

  /** The nearest hit so far of one raycast. */
  private static final class RayQuery {
    final Vector3f origin;
    final Vector3f dir;
    final Predicate<Collider> accept;
    final Vector3f center = new Vector3f();
    final Narrowphase.Sphere bounds = new Narrowphase.Sphere(new Vector3f(), 0f);
    float best;
    Collider bestCollider;
    Vector3f bestNormal;

    RayQuery(Vector3f origin, Vector3f dir, float maxDistance, Predicate<Collider> accept) {
      this.origin = origin;
      this.dir = dir;
      this.best = maxDistance;
      this.accept = accept;
    }

    void test(Collider c) {
      if (!this.accept.test(c)) {
        return;
      }
      c.worldCenter(this.bounds.c());
      this.bounds.r = c.boundingRadius();
      if (Narrowphase.raySphere(this.bounds, this.origin, this.dir, this.best) == null) {
        return; // cheap reject against the bounding sphere
      }
      final var r = Narrowphase.ray(c, this.origin, this.dir, this.best);
      if (r != null && r.distance() <= this.best) {
        this.best = r.distance();
        this.bestCollider = c;
        this.bestNormal = r.normal();
      }
    }
  }

  /** True if the grid of the last step indexes exactly the scene's current colliders. */
  private boolean gridMatches(List<Collider> all) {
    return this.grid != null && !this.bruteForceBroadphase && all == this.colliders;
  }

  private int nextQueryStamp() {
    if (this.queryStamps.length < this.colliders.size()) {
      this.queryStamps = new int[Math.max(this.colliders.size(), this.queryStamps.length * 2)];
    }
    return ++this.queryStamp;
  }

  private void testOnce(RayQuery q, int index, int stamp) {
    if (this.queryStamps[index] != stamp) {
      this.queryStamps[index] = stamp;
      q.test(this.colliders.get(index));
    }
  }

  /**
   * Visits the grid cells along the ray in order (3D DDA) and tests their colliders, stopping once the next cell
   * starts beyond the nearest hit. The ray is first clipped to the box of all occupied cells.
   */
  private void walkGrid(RayQuery q, int stamp) {
    final var g = this.grid;
    if (g.occupiedCells() == 0) {
      return;
    }
    final float size = g.cellSize();
    final var o = q.origin;
    final var d = q.dir;
    // clip [0, best] to the occupied box
    float t0 = 0f;
    float t1 = q.best;
    for (int a = 0; a < 3; a++) {
      final float oa = a == 0 ? o.x : a == 1 ? o.y : o.z;
      final float da = a == 0 ? d.x : a == 1 ? d.y : d.z;
      final float lo = g.minCell(a) * size;
      final float hi = (g.maxCell(a) + 1) * size;
      if (Math.abs(da) < 1e-12f) {
        if (oa < lo || oa > hi) {
          return;
        }
        continue;
      }
      float ta = (lo - oa) / da;
      float tb = (hi - oa) / da;
      if (ta > tb) {
        final float tmpT = ta;
        ta = tb;
        tb = tmpT;
      }
      t0 = Math.max(t0, ta);
      t1 = Math.min(t1, tb);
      if (t0 > t1) {
        return;
      }
    }
    final float sx = o.x + d.x * t0;
    final float sy = o.y + d.y * t0;
    final float sz = o.z + d.z * t0;
    int cx = clampCell(g.cellCoordinate(sx), 0);
    int cy = clampCell(g.cellCoordinate(sy), 1);
    int cz = clampCell(g.cellCoordinate(sz), 2);
    final int stepX = d.x > 0f ? 1 : -1;
    final int stepY = d.y > 0f ? 1 : -1;
    final int stepZ = d.z > 0f ? 1 : -1;
    final float dtX = Math.abs(d.x) < 1e-12f ? Float.MAX_VALUE : size / Math.abs(d.x);
    final float dtY = Math.abs(d.y) < 1e-12f ? Float.MAX_VALUE : size / Math.abs(d.y);
    final float dtZ = Math.abs(d.z) < 1e-12f ? Float.MAX_VALUE : size / Math.abs(d.z);
    float nextX = boundary(o.x, d.x, cx, stepX, size);
    float nextY = boundary(o.y, d.y, cy, stepY, size);
    float nextZ = boundary(o.z, d.z, cz, stepZ, size);
    float enter = t0;
    while (enter <= q.best && enter <= t1) {
      final var ids = g.cellAt(cx, cy, cz);
      if (ids != null) {
        for (int k = 0; k < ids.size(); k++) {
          testOnce(q, ids.get(k), stamp);
        }
      }
      if (nextX <= nextY && nextX <= nextZ) {
        enter = nextX;
        nextX += dtX;
        cx += stepX;
      } else if (nextY <= nextZ) {
        enter = nextY;
        nextY += dtY;
        cy += stepY;
      } else {
        enter = nextZ;
        nextZ += dtZ;
        cz += stepZ;
      }
    }
  }

  private int clampCell(int c, int axis) {
    return Math.max(this.grid.minCell(axis), Math.min(this.grid.maxCell(axis), c));
  }

  /** Ray parameter at which the ray leaves cell {@code c} in the direction of {@code step}. */
  private static float boundary(float o, float d, int c, int step, float size) {
    if (Math.abs(d) < 1e-12f) {
      return Float.MAX_VALUE;
    }
    final float edge = (step > 0 ? c + 1 : c) * size;
    return (edge - o) / d;
  }

  /** All colliders (including triggers) that overlap the sphere and are accepted by the filter. */
  public List<Collider> overlapSphere(Scene scene, Vector3f center, float radius, Predicate<Collider> accept) {
    final var s = new Narrowphase.Sphere(new Vector3f(center), radius);
    final List<Collider> out = new ArrayList<>();
    final var all = scene.components(Collider.class);
    if (!gridMatches(all)) {
      for (int i = 0; i < all.size(); i++) {
        final var c = all.get(i);
        if (accept.test(c) && Narrowphase.overlapsSphere(c, s)) {
          out.add(c);
        }
      }
      return out;
    }
    final int stamp = nextQueryStamp();
    final SpatialGrid.CellVisitor visitor = (ids, x0, y0, z0, x1, y1, z1) -> {
      for (int k = 0; k < ids.size(); k++) {
        overlapOnce(ids.get(k), stamp, s, accept, out);
      }
    };
    final float reach = radius + PAIR_MARGIN;
    this.grid.forEachCellInBox(center.x - reach, center.y - reach, center.z - reach, center.x + reach,
        center.y + reach, center.z + reach, visitor);
    final var large = this.grid.large();
    for (int k = 0; k < large.size(); k++) {
      overlapOnce(large.get(k), stamp, s, accept, out);
    }
    return out;
  }

  private void overlapOnce(int index, int stamp, Narrowphase.Sphere s, Predicate<Collider> accept,
      List<Collider> out) {
    if (this.queryStamps[index] == stamp) {
      return;
    }
    this.queryStamps[index] = stamp;
    final var c = this.colliders.get(index);
    if (accept.test(c) && Narrowphase.overlapsSphere(c, s)) {
      out.add(c);
    }
  }
}
