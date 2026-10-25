package engine.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.messaging.IMessageService;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;
import engine.spatial.SpatialGrid;

/**
 * Semi-implicit Euler integration plus an iterative impulse solver with positional correction and Coulomb friction.
 * Candidate pairs come from a uniform-grid broadphase over bounding spheres (with a separate path for colliders much
 * larger than a cell, such as ground planes), bodies that come to rest fall asleep, and entities may be parented:
 * positions and velocities are in world space and are converted back into the parent's space.
 *
 * <p>
 * Emits {@link CollisionEvent}s (also for triggers) when overlaps start and stop, and offers {@link #raycast} and
 * {@link #overlapSphere} queries against the current colliders.
 */
public class PhysicsSystem implements ISystem {
  private static final float SLOP = 0.005f;
  private static final float CORRECTION_PERCENT = 0.8f;
  /** Approach speeds below this do not bounce; avoids endless micro-bounces of resting bodies. */
  private static final float REST_SPEED = 1.0f;
  /** Bounding spheres are grown by this much so near-touching pairs reach the narrowphase. */
  private static final float PAIR_MARGIN = 0.05f;

  public final Vector3f gravity = new Vector3f(0f, -9.81f, 0f);
  public int solverIterations = 4;
  public boolean sleepingEnabled = true;
  /** Bodies slower than this (m/s) count as resting. */
  public float sleepSpeed = 0.1f;
  /** Seconds a body must keep resting before it falls asleep. */
  public float sleepTime = 0.5f;

  private final PhysicsStats stats = new PhysicsStats();
  private final IMessageService messages;
  private Map<Long, Entity[]> active = new HashMap<>();
  private SpatialGrid grid;
  private final Vector3f tmp = new Vector3f();
  private final Vector3f tmpPos = new Vector3f();

  /** Test hook: pair every collider with every other one instead of using the grid. */
  boolean bruteForceBroadphase;

  public PhysicsSystem(IMessageService messages) {
    this.messages = messages;
  }

  public PhysicsStats stats() {
    return this.stats;
  }

  record Pair(Collider a, Collider b, Rigidbody ra, Rigidbody rb) {
  }

  private static float awakeInverseMass(Rigidbody r) {
    return r == null || r.sleeping ? 0f : r.inverseMass();
  }

  @Override
  public void update(Scene scene, double dtD) {
    final float dt = (float) dtD;
    final List<Rigidbody> bodies = scene.components(Rigidbody.class);
    final List<Collider> colliders = scene.components(Collider.class);
    for (Rigidbody b : bodies) {
      b.touchedThisStep = false;
    }
    integrate(bodies, dt);

    final List<Pair> pairs = findPairs(colliders);

    // Before solving, an awake body that rests on something has already gained one step of gravity; a sleeper is
    // only woken by bodies clearly faster than that, otherwise a stack could never fall asleep.
    final float wakeSpeed = this.sleepSpeed + 2f * dt * this.gravity.length();
    final float wakeSpeedSq = wakeSpeed * wakeSpeed;

    // detection: events, wake-ups and "is resting on something" bookkeeping
    final Map<Long, Entity[]> now = new HashMap<>();
    int contacts = 0;
    for (Pair p : pairs) {
      if (Narrowphase.test(p.a(), p.b()) == null) {
        continue;
      }
      contacts++;
      now.put(key(p.a().entity(), p.b().entity()), new Entity[] { p.a().entity(), p.b().entity() });
      if (!p.a().trigger && !p.b().trigger) {
        touch(p.ra());
        touch(p.rb());
        wakeOnImpact(p.ra(), p.rb(), wakeSpeedSq);
        wakeOnImpact(p.rb(), p.ra(), wakeSpeedSq);
      }
    }
    for (Rigidbody b : bodies) {
      if (b.sleeping && !b.touchedThisStep) {
        b.wakeUp(); // lost its support (or never had one)
      }
    }

    final List<Pair> solvable = new ArrayList<>();
    for (Pair p : pairs) {
      if (!p.a().trigger && !p.b().trigger && (awakeInverseMass(p.ra()) > 0f || awakeInverseMass(p.rb()) > 0f)) {
        solvable.add(p);
      }
    }
    for (int i = 0; i < this.solverIterations; i++) {
      for (Pair p : solvable) {
        final Narrowphase.Contact c = Narrowphase.test(p.a(), p.b());
        if (c != null) {
          resolve(p, c);
        }
      }
    }

    updateSleep(bodies, dt);
    sendEvents(now);

    this.stats.colliders = colliders.size();
    this.stats.bodies = bodies.size();
    this.stats.sleepingBodies = (int) bodies.stream().filter(b -> b.sleeping).count();
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
    for (Rigidbody b : bodies) {
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
    for (Rigidbody b : bodies) {
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

  List<Pair> findPairs(List<Collider> colliders) {
    final int n = colliders.size();
    final float[] cx = new float[n];
    final float[] cy = new float[n];
    final float[] cz = new float[n];
    final float[] radius = new float[n];
    final Rigidbody[] bodies = new Rigidbody[n];
    final Vector3f c = new Vector3f();
    float dynamicRadiusSum = 0f;
    int dynamicCount = 0;
    for (int i = 0; i < n; i++) {
      final Collider col = colliders.get(i);
      col.worldCenter(c);
      cx[i] = c.x;
      cy[i] = c.y;
      cz[i] = c.z;
      radius[i] = col.boundingRadius();
      bodies[i] = col.entity().find(Rigidbody.class).orElse(null);
      if (bodies[i] != null && !bodies[i].kinematic) {
        dynamicRadiusSum += radius[i];
        dynamicCount++;
      }
    }

    final List<Pair> pairs = new ArrayList<>();
    final Set<Long> seen = new HashSet<>();
    if (this.bruteForceBroadphase) {
      for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
          tryPair(colliders, bodies, cx, cy, cz, radius, i, j, seen, pairs);
        }
      }
      return pairs;
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
      this.grid.insert(i, cx[i], cy[i], cz[i], radius[i] + PAIR_MARGIN);
    }
    this.grid.forEachCell((ids, x0, y0, z0, x1, y1, z1) -> {
      for (int a = 0; a < ids.size(); a++) {
        for (int b = a + 1; b < ids.size(); b++) {
          tryPair(colliders, bodies, cx, cy, cz, radius, ids.get(a), ids.get(b), seen, pairs);
        }
      }
    });
    final SpatialGrid.IntList large = this.grid.large();
    for (int k = 0; k < large.size(); k++) {
      for (int j = 0; j < n; j++) {
        if (j != large.get(k)) {
          tryPair(colliders, bodies, cx, cy, cz, radius, large.get(k), j, seen, pairs);
        }
      }
    }
    return pairs;
  }

  private static void tryPair(List<Collider> colliders, Rigidbody[] bodies, float[] cx, float[] cy, float[] cz,
      float[] radius, int i, int j, Set<Long> seen, List<Pair> out) {
    final long k = (long) Math.min(i, j) << 32 | Math.max(i, j);
    if (!seen.add(k)) {
      return;
    }
    final Collider a = colliders.get(i);
    final Collider b = colliders.get(j);
    if (a.entity() == b.entity()) {
      return;
    }
    if (bodies[i] == null && bodies[j] == null && !a.trigger && !b.trigger) {
      return; // static vs static: nothing to resolve and no events wanted
    }
    final float dx = cx[i] - cx[j];
    final float dy = cy[i] - cy[j];
    final float dz = cz[i] - cz[j];
    final float reach = radius[i] + radius[j] + 2f * PAIR_MARGIN;
    if (dx * dx + dy * dy + dz * dz <= reach * reach) {
      out.add(new Pair(a, b, bodies[i], bodies[j]));
    }
  }

  private void resolve(Pair p, Narrowphase.Contact c) {
    final float invA = awakeInverseMass(p.ra());
    final float invB = awakeInverseMass(p.rb());
    final float total = invA + invB;
    if (total <= 0f) {
      return;
    }
    final Vector3f n = c.normal();

    // positional correction, split by inverse mass
    final float corr = Math.max(c.depth() - SLOP, 0f) / total * CORRECTION_PERCENT;
    if (invA > 0f) {
      p.a().entity().transform().translateWorld(this.tmp.set(n).mul(-corr * invA));
    }
    if (invB > 0f) {
      p.b().entity().transform().translateWorld(this.tmp.set(n).mul(corr * invB));
    }

    final Vector3f va = p.ra() != null ? p.ra().velocity : new Vector3f();
    final Vector3f vb = p.rb() != null ? p.rb().velocity : new Vector3f();
    final Vector3f rv = new Vector3f(vb).sub(va);
    final float vn = rv.dot(n);
    if (vn >= 0f) {
      return; // already separating
    }
    final float e = vn > -REST_SPEED ? 0f : combineMin(p.ra(), p.rb());
    final float j = -(1f + e) * vn / total;
    if (invA > 0f) {
      va.fma(-j * invA, n);
    }
    if (invB > 0f) {
      vb.fma(j * invB, n);
    }

    // friction along the tangent, limited by mu * normal impulse
    rv.set(vb).sub(va);
    final Vector3f tangent = rv.fma(-rv.dot(n), n, new Vector3f());
    final float tl = tangent.length();
    if (tl > 1e-6f) {
      tangent.div(tl);
      final float mu = (float) Math.sqrt(friction(p.ra()) * friction(p.rb()));
      final float jt = Math.max(-mu * j, Math.min(mu * j, -rv.dot(tangent) / total));
      if (invA > 0f) {
        va.fma(-jt * invA, tangent);
      }
      if (invB > 0f) {
        vb.fma(jt * invB, tangent);
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

  private void sendEvents(Map<Long, Entity[]> now) {
    for (Map.Entry<Long, Entity[]> e : now.entrySet()) {
      if (!this.active.containsKey(e.getKey())) {
        emit(e.getValue(), true);
      }
    }
    for (Map.Entry<Long, Entity[]> e : this.active.entrySet()) {
      if (!now.containsKey(e.getKey())) {
        emit(e.getValue(), false);
      }
    }
    this.active = now;
  }

  private void emit(Entity[] pair, boolean started) {
    this.messages.send(MessageObject.builder().sender(this).queue(MessageQueue.COLLISION)
        .payload(new CollisionEvent(pair[0], pair[1], started)).build());
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
    final Vector3f d = new Vector3f(dir).normalize();
    float best = maxDistance;
    Collider bestCollider = null;
    Narrowphase.RayResult bestResult = null;
    final Vector3f center = new Vector3f();
    for (Collider c : scene.components(Collider.class)) {
      if (!accept.test(c)) {
        continue;
      }
      c.worldCenter(center);
      if (Narrowphase.raySphere(new Narrowphase.Sphere(new Vector3f(center), c.boundingRadius()), origin, d,
          best) == null) {
        continue; // cheap reject against the bounding sphere
      }
      final Narrowphase.RayResult r = Narrowphase.ray(c, origin, d, best);
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
    final Narrowphase.Sphere s = new Narrowphase.Sphere(new Vector3f(center), radius);
    final List<Collider> out = new ArrayList<>();
    for (Collider c : scene.components(Collider.class)) {
      if (accept.test(c) && Narrowphase.overlapsSphere(c, s)) {
        out.add(c);
      }
    }
    return out;
  }
}
