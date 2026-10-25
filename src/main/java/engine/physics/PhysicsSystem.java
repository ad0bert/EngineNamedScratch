package engine.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.ecs.ISystem;
import engine.ecs.Scene;
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
  /** Stands in for the velocity of a static collider; never written to because its inverse mass is zero. */
  private static final Vector3f NO_VELOCITY = new Vector3f();

  public final Vector3f gravity = new Vector3f(0f, -9.81f, 0f);
  public int solverIterations = 4;
  public boolean sleepingEnabled = true;
  /** Bodies slower than this (m/s) count as resting. */
  public float sleepSpeed = 0.1f;
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
  private final Vector3f tangent = new Vector3f();
  private final Narrowphase.Contact contact = new Narrowphase.Contact();

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


  /** A candidate pair; instances are pooled and only valid until the next {@link #findPairs}. */
  static final class Pair {
    private Collider a;
    private Collider b;
    private Rigidbody ra;
    private Rigidbody rb;
    private boolean hit;

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
    for (int it = 0; it < this.solverIterations; it++) {
      for (int i = 0; i < this.solvable.size(); i++) {
        final var p = this.solvable.get(i);
        if (Narrowphase.test(p.a, p.b, this.contact)) {
          resolve(p, this.contact);
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
            || this.tmpPos.distanceSquared(b.sleepPosition) > 1e-8f;
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
      }
    }
  }

  private void updateSleep(List<Rigidbody> bodies, float dt) {
    final float limit = this.sleepSpeed * this.sleepSpeed;
    for (int i = 0; i < bodies.size(); i++) {
      final var b = bodies.get(i);
      if (b.kinematic || b.sleeping || !b.allowSleep || !this.sleepingEnabled) {
        b.sleepTimer = 0f;
        continue;
      }
      // only a body that rests on something may sleep: floating bodies drifting slowly must keep moving
      if (b.touchedThisStep && b.velocity.lengthSquared() < limit) {
        b.sleepTimer += dt;
      } else {
        b.sleepTimer = 0f;
      }
      if (b.sleepTimer >= this.sleepTime) {
        b.sleeping = true;
        b.velocity.zero();
        b.entity().transform().worldPosition(b.sleepPosition);
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

  private void resolve(Pair p, Narrowphase.Contact c) {
    final float invA = awakeInverseMass(p.ra);
    final float invB = awakeInverseMass(p.rb);
    final float total = invA + invB;
    if (total <= 0f) {
      return;
    }
    final var n = c.normal();

    // positional correction, split by inverse mass
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

    final var va = p.ra != null ? p.ra.velocity : NO_VELOCITY;
    final var vb = p.rb != null ? p.rb.velocity : NO_VELOCITY;
    final var rv = this.relativeVelocity.set(vb).sub(va);
    final float vn = rv.dot(n);
    if (vn >= 0f) {
      return; // already separating
    }
    final float e = vn > -REST_SPEED ? 0f : combineMin(p.ra, p.rb);
    final float j = -(1f + e) * vn / total;
    if (invA > 0f) {
      va.fma(-j * invA, n);
    }
    if (invB > 0f) {
      vb.fma(j * invB, n);
    }

    // friction along the tangent, limited by mu * normal impulse
    rv.set(vb).sub(va);
    final var tg = rv.fma(-rv.dot(n), n, this.tangent);
    final float tl = tg.length();
    if (tl > 1e-6f) {
      tg.div(tl);
      final float mu = (float) Math.sqrt(friction(p.ra) * friction(p.rb));
      final float jt = Math.max(-mu * j, Math.min(mu * j, -rv.dot(tg) / total));
      if (invA > 0f) {
        va.fma(-jt * invA, tg);
      }
      if (invB > 0f) {
        vb.fma(jt * invB, tg);
      }
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
