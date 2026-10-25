package engine.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.messaging.IMessageService;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;

/**
 * Semi-implicit Euler integration plus an iterative impulse solver with positional correction and Coulomb friction.
 * Pairs are found by brute force (bounding spheres first), so it suits scenes with up to a few hundred colliders.
 * Emits {@link CollisionEvent}s (also for triggers) when overlaps start and stop.
 */
public class PhysicsSystem implements ISystem {
  private static final float SLOP = 0.005f;
  private static final float CORRECTION_PERCENT = 0.8f;
  /** Approach speeds below this do not bounce; avoids endless micro-bounces of resting bodies. */
  private static final float REST_SPEED = 1.0f;

  public final Vector3f gravity = new Vector3f(0f, -9.81f, 0f);
  public int solverIterations = 4;

  private final IMessageService messages;
  private Map<Long, Entity[]> active = new HashMap<>();

  public PhysicsSystem(IMessageService messages) {
    this.messages = messages;
  }

  private record Pair(Collider a, Collider b, Rigidbody ra, Rigidbody rb) {
    boolean solvable() {
      return !this.a.trigger && !this.b.trigger && (invMass(this.ra) > 0f || invMass(this.rb) > 0f);
    }
  }

  private static float invMass(Rigidbody r) {
    return r == null ? 0f : r.inverseMass();
  }

  @Override
  public void update(Scene scene, double dtD) {
    final float dt = (float) dtD;
    integrate(scene, dt);

    final List<Collider> colliders = scene.components(Collider.class);
    final List<Pair> pairs = findPairs(colliders);

    final Map<Long, Entity[]> now = new HashMap<>();
    for (Pair p : pairs) {
      if (Narrowphase.test(p.a(), p.b()) != null) {
        now.put(key(p.a().entity(), p.b().entity()), new Entity[] { p.a().entity(), p.b().entity() });
      }
    }

    final List<Pair> solvable = pairs.stream().filter(Pair::solvable).toList();
    for (int i = 0; i < this.solverIterations; i++) {
      for (Pair p : solvable) {
        final Narrowphase.Contact c = Narrowphase.test(p.a(), p.b());
        if (c != null) {
          resolve(p, c);
        }
      }
    }

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

  private void integrate(Scene scene, float dt) {
    for (Rigidbody b : scene.components(Rigidbody.class)) {
      final Vector3f pos = b.entity().transform().position();
      if (b.kinematic) {
        // velocity of a moved-by-code body is estimated from how far it travelled since the last step
        if (b.hasLastPosition && dt > 0f) {
          b.velocity.set(pos).sub(b.lastPosition).div(dt);
        }
        b.lastPosition.set(pos);
        b.hasLastPosition = true;
      } else {
        if (b.useGravity) {
          b.velocity.fma(dt, this.gravity);
        }
        b.velocity.mul(Math.max(0f, 1f - b.linearDamping * dt));
        pos.fma(dt, b.velocity);
      }
    }
  }

  private List<Pair> findPairs(List<Collider> colliders) {
    final List<Pair> pairs = new ArrayList<>();
    final Vector3f ca = new Vector3f();
    final Vector3f cb = new Vector3f();
    final float[] radius = new float[colliders.size()];
    final Rigidbody[] bodies = new Rigidbody[colliders.size()];
    for (int i = 0; i < colliders.size(); i++) {
      radius[i] = colliders.get(i).boundingRadius();
      bodies[i] = colliders.get(i).entity().find(Rigidbody.class).orElse(null);
    }
    for (int i = 0; i < colliders.size(); i++) {
      for (int j = i + 1; j < colliders.size(); j++) {
        final Collider a = colliders.get(i);
        final Collider b = colliders.get(j);
        if (a.entity() == b.entity()) {
          continue;
        }
        if (bodies[i] == null && bodies[j] == null && !a.trigger && !b.trigger) {
          continue; // static vs static: nothing to resolve and no events wanted
        }
        a.worldCenter(ca);
        b.worldCenter(cb);
        final float reach = radius[i] + radius[j] + 0.1f;
        if (ca.distanceSquared(cb) <= reach * reach) {
          pairs.add(new Pair(a, b, bodies[i], bodies[j]));
        }
      }
    }
    return pairs;
  }

  private void resolve(Pair p, Narrowphase.Contact c) {
    final float invA = invMass(p.ra());
    final float invB = invMass(p.rb());
    final float total = invA + invB;
    if (total <= 0f) {
      return;
    }
    final Vector3f n = c.normal();

    // positional correction, split by inverse mass
    final float corr = Math.max(c.depth() - SLOP, 0f) / total * CORRECTION_PERCENT;
    p.a().entity().transform().position().fma(-corr * invA, n);
    p.b().entity().transform().position().fma(corr * invB, n);

    final Vector3f va = p.ra() != null ? p.ra().velocity : new Vector3f();
    final Vector3f vb = p.rb() != null ? p.rb().velocity : new Vector3f();
    final Vector3f rv = new Vector3f(vb).sub(va);
    final float vn = rv.dot(n);
    if (vn >= 0f) {
      return; // already separating
    }
    final float e = vn > -REST_SPEED ? 0f : combineMin(p.ra(), p.rb());
    final float j = -(1f + e) * vn / total;
    if (p.ra() != null && invA > 0f) {
      va.fma(-j * invA, n);
    }
    if (p.rb() != null && invB > 0f) {
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
      if (p.ra() != null && invA > 0f) {
        va.fma(-jt * invA, tangent);
      }
      if (p.rb() != null && invB > 0f) {
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

  private void emit(Entity[] pair, boolean started) {
    this.messages.send(MessageObject.builder().sender(this).queue(MessageQueue.COLLISION)
        .payload(new CollisionEvent(pair[0], pair[1], started)).build());
  }
}
