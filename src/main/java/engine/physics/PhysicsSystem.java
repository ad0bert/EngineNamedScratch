package engine.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.joml.Matrix3f;
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
 * Candidate pairs come from a uniform-grid broadphase over bounding spheres (with a separate path for colliders much
 * larger than a cell, such as ground planes), bodies that come to rest fall asleep, and entities may be parented:
 * positions and velocities are in world space and are converted back into the parent's space.
 *
 * <p>
 * Emits {@link CollisionEvent}s (also for triggers) when overlaps start and stop, and offers {@link #raycast} and
 * {@link #overlapSphere} queries against the current colliders.
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
  private final Narrowphase.Contact contact = new Narrowphase.Contact();
  private int step;

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
    final var bodies = scene.components(Rigidbody.class);
    final var colliders = scene.components(Collider.class);
    for (int i = 0; i < bodies.size(); i++) {
      bodies.get(i).touchedThisStep = false;
    }
    integrate(bodies, dt);

    final var pairs = findPairs(colliders);

    // Before solving, an awake body that rests on something has already gained one step of gravity; a sleeper is
    // only woken by bodies clearly faster than that, otherwise a stack could never fall asleep.
    final float wakeSpeed = this.sleepSpeed + 2f * dt * this.gravity.length();
    final float wakeSpeedSq = wakeSpeed * wakeSpeed;

    // detection: events, wake-ups and "is resting on something" bookkeeping
    this.now.clear();
    int contacts = 0;
    for (int i = 0; i < pairs.size(); i++) {
      final var p = pairs.get(i);
      p.hit = Narrowphase.test(p.a, p.b, this.contact);
      if (!p.hit) {
        continue;
      }
      contacts++;
      final var ea = p.a.entity();
      final var eb = p.b.entity();
      this.now.put(key(ea, eb), ea, eb);
      if (!p.a.trigger && !p.b.trigger) {
        touch(p.ra);
        touch(p.rb);
        wakeOnImpact(p.ra, p.rb, wakeSpeedSq);
        wakeOnImpact(p.rb, p.ra, wakeSpeedSq);
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
    for (int it = 0; it < this.velocityIterations; it++) {
      for (int i = 0; i < this.solvable.size(); i++) {
        solveConstraint(this.solvable.get(i));
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

  /** A sleeping body is woken by an awake body that is moving (or a kinematic body that is being moved). */
  private void wakeOnImpact(Rigidbody sleeper, Rigidbody other, float wakeSpeedSq) {
    if (sleeper != null && sleeper.sleeping && other != null && !other.sleeping) {
      final float limit = other.kinematic ? 0.0025f : wakeSpeedSq;
      if (other.velocity.lengthSquared() > limit) {
        sleeper.wakeUp();
      }
    }
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
        // velocity of a moved-by-code body is estimated from how far it travelled since the last step
        if (b.hasLastPosition && dt > 0f) {
          b.velocity.set(this.tmpPos).sub(b.lastPosition).div(dt);
        }
        b.lastPosition.set(this.tmpPos);
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

  /** Turns the entity by its angular velocity, about the centre of its collider rather than its origin. */
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
    final var primary = primaryCollider(b.entity());
    if (primary == null || primary.offset.lengthSquared() == 0f) {
      t.rotateWorld(this.deltaRotation);
      return;
    }
    primary.worldCenter(this.tmpCenter);
    t.worldPosition(this.tmpPos);
    final var arm = this.tmpCenter.sub(this.tmpPos); // origin -> centre of mass, kept in place by the turn
    this.tmp.set(arm);
    t.rotateWorld(this.deltaRotation);
    this.deltaRotation.transform(this.tmp);
    t.translateWorld(arm.sub(this.tmp));
  }

  /** The collider that defines a body's centre of mass and inertia: the first one that is not a trigger. */
  private static Collider primaryCollider(Entity e) {
    for (int i = 0; i < e.componentCount(); i++) {
      if (e.componentAt(i) instanceof Collider c && !c.trigger) {
        return c;
      }
    }
    return null;
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
    p.a = a;
    p.b = b;
    p.ra = bi;
    p.rb = bj;
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

  /** Centre of mass and world-space inverse inertia tensor of a dynamic body, computed once per step. */
  private void prepare(Rigidbody b) {
    if (b.preparedStep == this.step) {
      return;
    }
    b.preparedStep = this.step;
    final var primary = primaryCollider(b.entity());
    if (primary != null) {
      primary.cachedCenter(b.centerOfMass);
    } else {
      b.entity().transform().worldPosition(b.centerOfMass);
    }
    final var m = b.invInertiaWorld.zero();
    if (b.lockRotation || primary == null) {
      return;
    }
    if (primary instanceof BoxCollider box) {
      final var shape = box.cachedBox();
      final float[] h = shape.half();
      for (int i = 0; i < 3; i++) {
        final float h1 = h[(i + 1) % 3];
        final float h2 = h[(i + 2) % 3];
        final float inv = 3f / (b.mass * Math.max(h1 * h1 + h2 * h2, 1e-6f));
        final var ax = shape.axes()[i];
        m.m00 += inv * ax.x * ax.x;
        m.m11 += inv * ax.y * ax.y;
        m.m22 += inv * ax.z * ax.z;
        final float xy = inv * ax.x * ax.y;
        final float xz = inv * ax.x * ax.z;
        final float yz = inv * ax.y * ax.z;
        m.m01 += xy;
        m.m10 += xy;
        m.m02 += xz;
        m.m20 += xz;
        m.m12 += yz;
        m.m21 += yz;
      }
    } else if (primary instanceof SphereCollider sphere) {
      final float r = sphere.cachedRadius();
      final float inv = 2.5f / (b.mass * Math.max(r * r, 1e-6f));
      m.m00 = inv;
      m.m11 = inv;
      m.m22 = inv;
    }
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
    if (!Narrowphase.test(p.a, p.b, c) || c.count() == 0) {
      return;
    }
    if (invA > 0f) {
      prepare(p.ra);
    }
    if (invB > 0f) {
      prepare(p.rb);
    }
    p.invA = invA;
    p.invB = invB;
    p.ia = invA > 0f ? p.ra.invInertiaWorld : NO_INERTIA;
    p.ib = invB > 0f ? p.rb.invInertiaWorld : NO_INERTIA;
    p.va = p.ra != null ? p.ra.velocity : NO_VELOCITY;
    p.vb = p.rb != null ? p.rb.velocity : NO_VELOCITY;
    p.wa = p.ra != null ? p.ra.angularVelocity : NO_SPIN;
    p.wb = p.rb != null ? p.rb.angularVelocity : NO_SPIN;
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
      if (invA > 0f) {
        p.rA[k].set(c.point(k)).sub(p.ra.centerOfMass);
      } else {
        p.rA[k].zero();
      }
      if (invB > 0f) {
        p.rB[k].set(c.point(k)).sub(p.rb.centerOfMass);
      } else {
        p.rB[k].zero();
      }
      final float vn = pointVelocity(p, k, this.relativeVelocity).dot(n);
      p.bias[k] = vn < -REST_SPEED ? -e * vn : 0f;
      p.massN[k] = 1f / (invA + invB + angularTerm(p.ia, p.rA[k], n) + angularTerm(p.ib, p.rB[k], n));
      p.massT1[k] = 1f / (invA + invB + angularTerm(p.ia, p.rA[k], p.t1) + angularTerm(p.ib, p.rB[k], p.t1));
      p.massT2[k] = 1f / (invA + invB + angularTerm(p.ia, p.rA[k], p.t2) + angularTerm(p.ib, p.rB[k], p.t2));
      p.jn[k] = 0f;
      p.jt1[k] = 0f;
      p.jt2[k] = 0f;
    }
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

  /** One round of sequential impulses with accumulated, clamped impulses (non-penetration, then friction). */
  private void solveConstraint(Pair p) {
    final var n = p.manifold.normal();
    final var rv = this.relativeVelocity;
    for (int k = 0; k < p.count; k++) {
      float dj = p.massN[k] * (p.bias[k] - pointVelocity(p, k, rv).dot(n));
      float total = Math.max(p.jn[k] + dj, 0f);
      dj = total - p.jn[k];
      p.jn[k] = total;
      applyImpulse(p, k, n, dj);

      final float limit = p.mu * p.jn[k];
      dj = -p.massT1[k] * pointVelocity(p, k, rv).dot(p.t1);
      total = Math.max(-limit, Math.min(limit, p.jt1[k] + dj));
      dj = total - p.jt1[k];
      p.jt1[k] = total;
      applyImpulse(p, k, p.t1, dj);

      dj = -p.massT2[k] * pointVelocity(p, k, rv).dot(p.t2);
      total = Math.max(-limit, Math.min(limit, p.jt2[k] + dj));
      dj = total - p.jt2[k];
      p.jt2[k] = total;
      applyImpulse(p, k, p.t2, dj);
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
    final var d = new Vector3f(dir).normalize();
    float best = maxDistance;
    Collider bestCollider = null;
    Narrowphase.RayResult bestResult = null;
    final var center = new Vector3f();
    for (Collider c : scene.components(Collider.class)) {
      if (!accept.test(c)) {
        continue;
      }
      c.worldCenter(center);
      if (Narrowphase.raySphere(new Narrowphase.Sphere(new Vector3f(center), c.boundingRadius()), origin, d,
          best) == null) {
        continue; // cheap reject against the bounding sphere
      }
      final var r = Narrowphase.ray(c, origin, d, best);
      if (r != null && r.distance() <= best) {
        best = r.distance();
        bestCollider = c;
        bestResult = r;
      }
    }
    if (bestCollider == null) {
      return Optional.empty();
    }
    return Optional.of(new RayHit(bestCollider, new Vector3f(origin).fma(bestResult.distance(), d),
        bestResult.normal(), bestResult.distance()));
  }

  /** All colliders (including triggers) that overlap the sphere and are accepted by the filter. */
  public List<Collider> overlapSphere(Scene scene, Vector3f center, float radius, Predicate<Collider> accept) {
    final var s = new Narrowphase.Sphere(new Vector3f(center), radius);
    final List<Collider> out = new ArrayList<>();
    for (Collider c : scene.components(Collider.class)) {
      if (accept.test(c) && Narrowphase.overlapsSphere(c, s)) {
        out.add(c);
      }
    }
    return out;
  }
}
