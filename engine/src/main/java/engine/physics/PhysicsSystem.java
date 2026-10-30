package engine.physics;

import java.util.ArrayList;
import java.util.Arrays;
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
import engine.render.ParticleCollider;
import engine.render.ParticleEmitter;
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
  /** Slot of a pair's arrays that holds the manifold centroid (after the up to four contact points). */
  private static final int CENTER = Narrowphase.Contact.MAX_POINTS;
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
  /**
   * Bodies that would move further than their inscribed radius in one step (or are marked
   * {@link Rigidbody#continuous}) sweep it along their path and stop at the first collider in the way, so fast
   * bodies do not tunnel through thin geometry.
   */
  public boolean continuousCollision = true;
  boolean warmFrictionOn = true;
  public boolean sleepingEnabled = true;
  /** Bodies slower than this (m/s) count as resting. */
  public float sleepSpeed = 0.1f;
  /** Bodies turning slower than this (rad/s) count as resting. */
  public float sleepAngularSpeed = 0.2f;
  /** Seconds a body must keep resting before it falls asleep. */
  public float sleepTime = 0.5f;

  @Getter private final PhysicsStats stats = new PhysicsStats();
  /** Per layer, the bits of the layers it collides with. */
  private final int[] layerMasks = new int[32];
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
  private final SpatialGrid.IntList triangles = new SpatialGrid.IntList();
  private final LongSet jointPairs = new LongSet();
  private final List<Joint> activeJoints = new ArrayList<>();

  // query scratch: collider index -> stamp of the last query that tested it
  private int[] queryStamps = new int[64];
  private int queryStamp;

  /** Test hook: pair every collider with every other one instead of using the grid. */
  boolean bruteForceBroadphase;

  public PhysicsSystem(IMessageService messages) {
    Arrays.fill(this.layerMasks, ~0);
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
    /** Triangle of a {@link MeshCollider} side, or -1: pairs with a mesh are split into one pair per triangle. */
    private int tri = -1;

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
    // arms from each body's reference point to the manifold points; slot CENTER is the manifold's centroid
    private final Vector3f[] rA = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
    private final Vector3f[] rB = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
    private final float[] massN = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] massT1 = new float[Narrowphase.Contact.MAX_POINTS + 1];
    private final float[] massT2 = new float[Narrowphase.Contact.MAX_POINTS + 1];
    private final float[] bias = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] jn = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] jt1 = new float[Narrowphase.Contact.MAX_POINTS];
    private final float[] jt2 = new float[Narrowphase.Contact.MAX_POINTS];
    /** Manifolds of several points take their friction at the centroid: two tangent rows and a twist. */
    private boolean central;
    private float centralT1;
    private float centralT2;
    private float twist;
    private float massTwist;
    private float twistRadius;
    /** Inverse of the (regularized) coupling matrix of the normal rows, row-major; valid when {@code block}. */
    private final float[] blockInverse = new float[Narrowphase.Contact.MAX_POINTS * Narrowphase.Contact.MAX_POINTS];
    private boolean block;

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
    // characters move first, against the world as the last step left it; bodies then see them as moved colliders
    final var characters = scene.components(CharacterController.class);
    for (int i = 0; i < characters.size(); i++) {
      if (characters.get(i).isEnabled()) {
        characters.get(i).step(this, scene, dt);
      }
    }
    if (this.warmStarting) {
      this.contactCache.swap();
    } else {
      this.contactCache.clear();
    }
    integrate(bodies, colliders, dt);

    final var joints = scene.components(Joint.class);
    this.jointPairs.clear();
    for (int i = 0; i < joints.size(); i++) {
      final var j = joints.get(i);
      if (!j.collideConnected && j.connected != null) {
        this.jointPairs.add(key(j.entity(), j.connected));
      }
    }

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
      p.hit = Narrowphase.test(p.a, p.b, p.tri, this.contact, solid ? SPECULATIVE_MARGIN : 0f);
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
    // joined bodies hold each other up, and sleep and wake together
    for (int i = 0; i < joints.size(); i++) {
      final var j = joints.get(i);
      final var ja = j.entity().findOrNull(Rigidbody.class);
      final var jb = j.connected != null ? j.connected.findOrNull(Rigidbody.class) : null;
      touch(ja);
      touch(jb);
      if (ja != null && jb != null && ja.sleeping != jb.sleeping && !ja.kinematic && !jb.kinematic) {
        ja.wakeUp();
        jb.wakeUp();
      }
      final boolean asleep = ja != null && ja.sleeping || jb != null && jb.sleeping;
      if (asleep && j.isEnabled() && j.drives()) { // e.g. a motor told to turn away from the limit it rests on
        if (ja != null && !ja.kinematic) {
          ja.wakeUp();
        }
        if (jb != null && !jb.kinematic) {
          jb.wakeUp();
        }
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
      for (int i = 0; i < joints.size(); i++) {
        joints.get(i).correctPositions(this);
      }
      for (int i = 0; i < this.solvable.size(); i++) {
        final var p = this.solvable.get(i);
        if (Narrowphase.test(p.a, p.b, p.tri, this.contact, 0f)) {
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

    this.activeJoints.clear();
    for (int i = 0; i < joints.size(); i++) {
      if (joints.get(i).begin(this, dt)) { // prepares the rows and warm-starts them
        this.activeJoints.add(joints.get(i));
      }
    }
    for (int it = 0; it < this.velocityIterations; it++) {
      for (int i = 0; i < this.activeJoints.size(); i++) {
        this.activeJoints.get(i).solve();
      }
      for (int i = 0; i < this.solvable.size(); i++) {
        solveConstraint(this.solvable.get(i));
      }
    }
    for (int i = 0; i < this.activeJoints.size(); i++) {
      this.activeJoints.get(i).finish();
    }
    if (this.warmStarting) {
      for (int i = 0; i < this.solvable.size(); i++) {
        final var p = this.solvable.get(i);
        if (p.count > 0) {
          if (p.central) { // cached per point: split evenly, summed again when warm starting
            for (int k = 0; k < p.count; k++) {
              p.jt1[k] = p.centralT1 / p.count;
              p.jt2[k] = p.centralT2 / p.count;
            }
          }
          this.contactCache.store(p.key, p.manifold, p.count, p.jn, p.jt1, p.jt2, p.t1, p.t2);
        }
      }
    }

    updateSleep(bodies, joints, dt);
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

  private void integrate(List<Rigidbody> bodies, List<Collider> colliders, float dt) {
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
        t.worldRotation(this.tmpRotation);
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
        this.tmp.set(b.velocity).mul(dt);
        if (this.continuousCollision) {
          sweep(b, colliders, this.tmp);
        }
        t.translateWorld(this.tmp);
        if (!b.lockRotation) {
          turn(b, t, dt);
        }
      }
    }
  }

  /**
   * Continuous collision: shortens {@code delta} so the body's inscribed sphere (a little smaller, so resting contacts
   * do not count) stops just before the first non-trigger collider along its path. Candidates come from the grid
   * cells around the swept segment (of the last step); colliders the sphere already overlaps, or moves away from,
   * are ignored, the contact solver deals with them.
   */
  private void sweep(Rigidbody b, List<Collider> colliders, Vector3f delta) {
    final float dist = delta.length();
    if (dist < 1e-6f) {
      return;
    }
    final var e = b.entity();
    float inner = Float.MAX_VALUE;
    int mask = 0;
    for (int i = 0; i < e.componentCount(); i++) {
      if (e.componentAt(i) instanceof Collider c && c.isEnabled() && !c.trigger) {
        inner = Math.min(inner, c.cachedInnerRadius());
        mask |= this.layerMasks[c.layer & 31];
      }
    }
    if (inner == Float.MAX_VALUE || inner <= 0f || !b.continuous && dist <= inner) {
      return;
    }
    if (massCenter(e, this.sweepOrigin, true) == 0) {
      return;
    }
    final var q = this.sweepQuery;
    q.reset(this.sweepOrigin, this.sweepDir.set(delta).div(dist), dist, 0.9f * inner, e);
    q.mask = mask;
    if (gridMatches(colliders)) {
      final int stamp = nextQueryStamp();
      this.sweepStamp = stamp;
      final float reach = q.inflate + PAIR_MARGIN;
      final var o = this.sweepOrigin;
      final float ex = o.x + delta.x;
      final float ey = o.y + delta.y;
      final float ez = o.z + delta.z;
      this.grid.forEachCellInBox(Math.min(o.x, ex) - reach, Math.min(o.y, ey) - reach, Math.min(o.z, ez) - reach,
          Math.max(o.x, ex) + reach, Math.max(o.y, ey) + reach, Math.max(o.z, ez) + reach, this.sweepVisitor);
      final var large = this.grid.large();
      for (int k = 0; k < large.size(); k++) {
        testOnce(q, large.get(k), stamp);
      }
    } else {
      for (int i = 0; i < colliders.size(); i++) {
        q.test(colliders.get(i));
      }
    }
    if (q.bestCollider != null) {
      delta.mul(Math.max(0f, q.best - 0.01f) / dist);
    }
  }

  private final RayQuery sweepQuery = new RayQuery();
  private final Vector3f sweepOrigin = new Vector3f();
  private final Vector3f sweepDir = new Vector3f();
  private int sweepStamp;
  private final SpatialGrid.CellVisitor sweepVisitor = (ids, x0, y0, z0, x1, y1, z1) -> {
    for (int k = 0; k < ids.size(); k++) {
      testOnce(this.sweepQuery, ids.get(k), this.sweepStamp);
    }
  };

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

  private void updateSleep(List<Rigidbody> bodies, List<Joint> joints, float dt) {
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
    }
    // joined bodies are as restless as the most restless of them (relaxed until nothing changes)
    for (boolean changed = true; changed;) {
      changed = false;
      for (int i = 0; i < joints.size(); i++) {
        final var j = joints.get(i);
        final var ja = j.entity().findOrNull(Rigidbody.class);
        final var jb = j.connected != null ? j.connected.findOrNull(Rigidbody.class) : null;
        if (ja != null && jb != null && !ja.sleeping && !jb.sleeping && ja.sleepTimer != jb.sleepTimer) {
          ja.sleepTimer = jb.sleepTimer = Math.min(ja.sleepTimer, jb.sleepTimer);
          changed = true;
        }
      }
    }
    for (int i = 0; i < bodies.size(); i++) {
      final var b = bodies.get(i);
      if (!b.sleeping && b.sleepTimer >= this.sleepTime) {
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
    indexColliders(colliders);

    if (this.bruteForceBroadphase) {
      for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
          tryPair(i, j);
        }
      }
    } else {
      this.grid.forEachCell(this.cellPairs);
      final var large = this.grid.large();
      for (int k = 0; k < large.size(); k++) {
        for (int j = 0; j < n; j++) {
          if (j != large.get(k)) {
            tryPair(large.get(k), j);
          }
        }
      }
    }
    expandMeshPairs();
    return this.pairs;
  }

  /**
   * Makes {@link #raycast} and {@link #overlapSphere} see every collider where it is now. Queries normally use the
   * broadphase grid of the last step; call this after moving colliders by code (or adding them) when a query must
   * see the change before the next step. Costs about as much as the broadphase part of a step.
   */
  public void syncQueries(Scene scene) {
    indexColliders(scene.components(Collider.class));
  }

  /** Refreshes every collider's cached shape and (unless brute force) rebuilds the grid over them. */
  private void indexColliders(List<Collider> colliders) {
    final int n = colliders.size();
    ensureCapacity(n);
    this.colliders = colliders;
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
      return;
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
  }

  /**
   * Replaces every pair with a mesh by one pair per triangle near the other collider (through the mesh's bounding
   * volume hierarchy); pairs whose box touches no triangle are dropped.
   */
  private void expandMeshPairs() {
    final int n = this.pairs.size();
    for (int i = 0; i < n; i++) {
      final var p = this.pairs.get(i);
      final MeshCollider mesh = p.b instanceof MeshCollider mb ? mb : p.a instanceof MeshCollider ma ? ma : null;
      if (mesh == null || p.a instanceof MeshCollider && p.b instanceof MeshCollider) {
        continue;
      }
      final var other = mesh == p.b ? p.a : p.b;
      other.cachedCenter(this.tmpCenter);
      final float r = other.cachedRadius() + PAIR_MARGIN;
      this.triangles.clear();
      mesh.candidates(this.tmpCenter.x - r, this.tmpCenter.y - r, this.tmpCenter.z - r, this.tmpCenter.x + r,
          this.tmpCenter.y + r, this.tmpCenter.z + r, this.triangles);
      p.tri = -2; // marks the original pair: removed below unless a triangle reuses it
      for (int k = 0; k < this.triangles.size(); k++) {
        final var q = k == 0 ? p
            : this.sparePairs.isEmpty() ? new Pair() : this.sparePairs.remove(this.sparePairs.size() - 1);
        q.a = p.a;
        q.b = p.b;
        q.ra = p.ra;
        q.rb = p.rb;
        q.hit = false;
        q.tri = this.triangles.get(k);
        q.key = ContactCache.key(p.a, p.b) ^ (q.tri + 1L) * 0x9E3779B97F4A7C15L;
        if (k > 0) {
          this.pairs.add(q);
        }
      }
    }
    // drop the mesh pairs that found no triangle (rare: the bounding spheres overlapped, the boxes did not)
    int w = 0;
    for (int i = 0; i < this.pairs.size(); i++) {
      final var p = this.pairs.get(i);
      if (p.tri == -2) {
        p.a = null;
        p.b = null;
        p.ra = null;
        p.rb = null;
        this.sparePairs.add(p);
      } else {
        this.pairs.set(w++, p);
      }
    }
    while (this.pairs.size() > w) {
      this.pairs.remove(this.pairs.size() - 1);
    }
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
    if ((this.layerMasks[a.layer & 31] & b.layerBit()) == 0) {
      return; // layers that do not collide (neither contacts nor trigger events)
    }
    if (this.jointPairs.size() > 0 && this.jointPairs.contains(key(a.entity(), b.entity()))) {
      return; // joined bodies do not collide unless the joint says so
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
    p.tri = -1;
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
  /** Moves a body's entity by a world offset (joint position correction), keeping its colliders current. */
  static void shift(Rigidbody b, Vector3f delta) {
    b.entity().transform().translateWorld(delta);
    refreshColliders(b.entity());
  }

  /** Turns a body's entity about its centre of mass by a small rotation vector (axis * angle). */
  void twist(Rigidbody b, Vector3f rotation) {
    final float angle = rotation.length();
    if (angle < 1e-7f) {
      return;
    }
    final var e = b.entity();
    final var t = e.transform();
    if (massCenter(e, this.tmpCenter, true) == 0) {
      t.worldPosition(this.tmpCenter);
    }
    t.worldPosition(this.tmpPos);
    this.deltaRotation.setAngleAxis(angle, rotation.x / angle, rotation.y / angle, rotation.z / angle);
    final var arm = this.tmpPos.sub(this.tmpCenter); // centre -> origin, turned with the body
    this.tmp.set(arm);
    this.deltaRotation.transform(this.tmp);
    t.rotateWorld(this.deltaRotation);
    t.translateWorld(this.tmp.sub(arm));
    refreshColliders(e);
  }

  /** Centre of mass and inverse inertia of a body for this step (used by joints). */
  void prepareBody(Rigidbody b) {
    prepare(b);
  }

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
    if (!Narrowphase.test(p.a, p.b, p.tri, c, SPECULATIVE_MARGIN) || c.count() == 0) {
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
    prepareBlock(p, n);
    prepareCentralFriction(p, n, refA, refB);
  }

  /**
   * Friction of a manifold with several points, applied at their centroid (plus a twist about the normal), instead of
   * point by point: per-point friction rows, solved in turn, tilt a resting box a little every round.
   */
  private void prepareCentralFriction(Pair p, Vector3f n, Vector3f refA, Vector3f refB) {
    p.central = p.count > 1;
    p.centralT1 = 0f;
    p.centralT2 = 0f;
    p.twist = 0f;
    if (!p.central) {
      return;
    }
    final var c = this.tmpCenter.zero();
    for (int k = 0; k < p.count; k++) {
      c.add(p.manifold.point(k));
    }
    c.div(p.count);
    float radius = 0f;
    for (int k = 0; k < p.count; k++) {
      radius += p.manifold.point(k).distance(c);
    }
    p.twistRadius = radius / p.count;
    if (refA != null) {
      p.rA[CENTER].set(c).sub(refA);
    } else {
      p.rA[CENTER].zero();
    }
    if (refB != null) {
      p.rB[CENTER].set(c).sub(refB);
    } else {
      p.rB[CENTER].zero();
    }
    p.massT1[CENTER] = 1f / (p.invA + p.invB + angularTerm(p.ia, p.rA[CENTER], p.t1) + angularTerm(p.ib, p.rB[CENTER],
        p.t1));
    p.massT2[CENTER] = 1f / (p.invA + p.invB + angularTerm(p.ia, p.rA[CENTER], p.t2) + angularTerm(p.ib, p.rB[CENTER],
        p.t2));
    final float k = p.ia.transform(this.cross.set(n)).dot(n) + p.ib.transform(this.crossB.set(n)).dot(n);
    p.massTwist = k > 1e-12f ? 1f / k : 0f;
  }

  private float normalSum(Pair p) {
    float sum = 0f;
    for (int k = 0; k < p.count; k++) {
      sum += p.jn[k];
    }
    return sum;
  }

  /** One round of the centroid friction rows, bounded by the friction cone of the summed normal impulse. */
  private void solveCentralFriction(Pair p) {
    final float limit = p.mu * normalSum(p);
    final var rv = this.relativeVelocity;
    float dj = -p.massT1[CENTER] * pointVelocity(p, CENTER, rv).dot(p.t1);
    float total = Math.max(-limit, Math.min(limit, p.centralT1 + dj));
    dj = total - p.centralT1;
    p.centralT1 = total;
    applyImpulse(p, CENTER, p.t1, dj);

    dj = -p.massT2[CENTER] * pointVelocity(p, CENTER, rv).dot(p.t2);
    total = Math.max(-limit, Math.min(limit, p.centralT2 + dj));
    dj = total - p.centralT2;
    p.centralT2 = total;
    applyImpulse(p, CENTER, p.t2, dj);

    if (p.massTwist > 0f) {
      final var n = p.manifold.normal();
      final float spin = this.cross.set(p.wb).sub(p.wa).dot(n);
      final float twistLimit = limit * p.twistRadius;
      final float t = Math.max(-twistLimit, Math.min(twistLimit, p.twist - p.massTwist * spin));
      final float d = t - p.twist;
      p.twist = t;
      if (p.invA > 0f) {
        p.wa.sub(p.ia.transform(this.cross.set(n).mul(d)));
      }
      if (p.invB > 0f) {
        p.wb.add(p.ib.transform(this.cross.set(n).mul(d)));
      }
    }
  }

  /**
   * Couples the normal rows of a manifold: K[i][j] is how much an impulse at point j changes the approach speed at
   * point i. Solving them together gives every point its fair share at once; solved one after another (in a fixed
   * order) the first point always gets too much, and the resulting slight tilt is what made tall stacks creep. Four
   * points on a face constrain only three degrees of freedom, so K is singular; a small regularization picks the
   * evenly spread solution.
   */
  private void prepareBlock(Pair p, Vector3f n) {
    final int m = p.count;
    p.block = m > 1;
    if (!p.block) {
      return;
    }
    final float[] k = this.blockMatrix;
    float trace = 0f;
    for (int i = 0; i < m; i++) {
      p.rA[i].cross(n, this.blockA[i]);
      p.rB[i].cross(n, this.blockB[i]);
    }
    for (int i = 0; i < m; i++) {
      p.ia.transform(this.cross.set(this.blockA[i]));
      p.ib.transform(this.crossB.set(this.blockB[i]));
      for (int j = 0; j < m; j++) {
        k[i * m + j] = p.invA + p.invB + this.blockA[j].dot(this.cross) + this.blockB[j].dot(this.crossB);
      }
      trace += k[i * m + i];
    }
    final float epsilon = 1e-3f * trace / m;
    for (int i = 0; i < m; i++) {
      k[i * m + i] += epsilon;
    }
    p.block = invert(k, m, p.blockInverse);
  }

  private final float[] blockMatrix = new float[Narrowphase.Contact.MAX_POINTS * Narrowphase.Contact.MAX_POINTS];
  private final float[] blockWork = new float[Narrowphase.Contact.MAX_POINTS * Narrowphase.Contact.MAX_POINTS * 2];
  private final Vector3f[] blockA = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
  private final Vector3f[] blockB = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
  private final float[] blockRhs = new float[Narrowphase.Contact.MAX_POINTS];

  /** Gauss-Jordan inverse of an m x m matrix (row-major) into {@code out}; false if it is (nearly) singular. */
  private boolean invert(float[] a, int m, float[] out) {
    final float[] w = this.blockWork; // [a | I], m rows of 2m
    final int cols = 2 * m;
    for (int i = 0; i < m; i++) {
      for (int j = 0; j < m; j++) {
        w[i * cols + j] = a[i * m + j];
        w[i * cols + m + j] = i == j ? 1f : 0f;
      }
    }
    for (int c = 0; c < m; c++) {
      int pivot = c;
      for (int r = c + 1; r < m; r++) {
        if (Math.abs(w[r * cols + c]) > Math.abs(w[pivot * cols + c])) {
          pivot = r;
        }
      }
      final float pv = w[pivot * cols + c];
      if (Math.abs(pv) < 1e-12f) {
        return false;
      }
      if (pivot != c) {
        for (int j = 0; j < cols; j++) {
          final float t = w[c * cols + j];
          w[c * cols + j] = w[pivot * cols + j];
          w[pivot * cols + j] = t;
        }
      }
      for (int j = 0; j < cols; j++) {
        w[c * cols + j] /= pv;
      }
      for (int r = 0; r < m; r++) {
        if (r != c) {
          final float f = w[r * cols + c];
          if (f != 0f) {
            for (int j = 0; j < cols; j++) {
              w[r * cols + j] -= f * w[c * cols + j];
            }
          }
        }
      }
    }
    for (int i = 0; i < m; i++) {
      for (int j = 0; j < m; j++) {
        out[i * m + j] = w[i * cols + m + j];
      }
    }
    return true;
  }

  /** Applies the impulses the pair's contacts ended the last step with. */
  private void warmStart(Pair p) {
    if (p.count == 0) {
      return;
    }
    final var n = p.manifold.normal();
    if (this.contactCache.lookup(p.key, p.manifold, p.jn, this.warmFriction)) {
      if (p.central) {
        float f1 = 0f;
        float f2 = 0f;
        for (int k = 0; k < p.count; k++) {
          applyImpulse(p, k, n, p.jn[k]);
          f1 += this.warmFriction[k].dot(p.t1);
          f2 += this.warmFriction[k].dot(p.t2);
        }
        final float limit = p.mu * normalSum(p);
        p.centralT1 = Math.max(-limit, Math.min(limit, f1));
        p.centralT2 = Math.max(-limit, Math.min(limit, f2));
        applyImpulse(p, CENTER, p.t1, p.centralT1);
        applyImpulse(p, CENTER, p.t2, p.centralT2);
        return;
      }
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
    if (p.central) {
      solveCentralFriction(p);
    }
    for (int k = 0; k < p.count && !p.central; k++) {
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
    if (p.block && solveBlock(p, n)) {
      return;
    }
    for (int k = 0; k < p.count; k++) {
      float dj = p.massN[k] * (p.bias[k] - pointVelocity(p, k, rv).dot(n));
      final float total = Math.max(p.jn[k] + dj, 0f);
      dj = total - p.jn[k];
      p.jn[k] = total;
      applyImpulse(p, k, n, dj);
    }
  }

  /**
   * All normal rows of a manifold at once: the impulse change that brings every point to its target speed. Used when
   * it keeps every accumulated impulse pushing; otherwise (a point lifting off) the rows are solved one by one.
   */
  private boolean solveBlock(Pair p, Vector3f n) {
    final int m = p.count;
    final float[] rhs = this.blockRhs;
    for (int k = 0; k < m; k++) {
      rhs[k] = p.bias[k] - pointVelocity(p, k, this.relativeVelocity).dot(n);
    }
    final float[] inv = p.blockInverse;
    for (int i = 0; i < m; i++) {
      float d = 0f;
      for (int j = 0; j < m; j++) {
        d += inv[i * m + j] * rhs[j];
      }
      if (p.jn[i] + d < 0f) {
        return false;
      }
      this.blockDelta[i] = d;
    }
    for (int i = 0; i < m; i++) {
      p.jn[i] += this.blockDelta[i];
      applyImpulse(p, i, n, this.blockDelta[i]);
    }
    return true;
  }

  private final float[] blockDelta = new float[Narrowphase.Contact.MAX_POINTS];

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


  /** Makes colliders on layers {@code a} and {@code b} (0 to 31) collide or pass through each other. */
  public void setLayersCollide(int a, int b, boolean collide) {
    checkLayer(a);
    checkLayer(b);
    if (collide) {
      this.layerMasks[a] |= 1 << b;
      this.layerMasks[b] |= 1 << a;
    } else {
      this.layerMasks[a] &= ~(1 << b);
      this.layerMasks[b] &= ~(1 << a);
    }
  }

  public boolean layersCollide(int a, int b) {
    checkLayer(a);
    checkLayer(b);
    return (this.layerMasks[a] & 1 << b) != 0;
  }

  /** The bits of the layers that layer {@code layer} collides with (a mask for queries made on its behalf). */
  public int collisionMask(int layer) {
    checkLayer(layer);
    return this.layerMasks[layer];
  }

  private static void checkLayer(int layer) {
    if (layer < 0 || layer > 31) {
      throw new IllegalArgumentException("layer " + layer + " is not between 0 and 31");
    }
  }

  /** A query filter: colliders that are not triggers and whose layer is in {@code layerMask}. */
  public static Predicate<Collider> solid(int layerMask) {
    return c -> !c.trigger && (layerMask & c.layerBit()) != 0;
  }

  /** Bit mask of the given layers, for {@link #solid(int)}. */
  public static int layers(int... layers) {
    int mask = 0;
    for (final int l : layers) {
      checkLayer(l);
      mask |= 1 << l;
    }
    return mask;
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

  /**
   * Particle collisions against this system's colliders (not triggers; those on the layers in {@code layerMask}),
   * for {@link ParticleEmitter#collider}: one ray per particle and step along its move, through the broadphase grid
   * of the last step. Allocates only when a particle hits something. One instance per emitter or shared, on the
   * update thread.
   */
  public ParticleCollider particleCollider(Scene scene, int layerMask) {
    final var q = new RayQuery();
    final var origin = new Vector3f();
    final var dir = new Vector3f();
    return (x0, y0, z0, x1, y1, z1, hit) -> {
      dir.set(x1 - x0, y1 - y0, z1 - z0);
      final float len = dir.length();
      if (len < 1e-6f) {
        return false;
      }
      dir.div(len);
      q.reset(origin.set(x0, y0, z0), dir, len, 0f, null);
      q.mask = layerMask;
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
        return false;
      }
      hit[0] = x0 + dir.x * q.best;
      hit[1] = y0 + dir.y * q.best;
      hit[2] = z0 + dir.z * q.best;
      hit[3] = q.bestNormal.x;
      hit[4] = q.bestNormal.y;
      hit[5] = q.bestNormal.z;
      return true;
    };
  }

  /**
   * The nearest hit so far of one raycast, or of a sphere sweep when {@code inflate > 0} (the ray against every
   * shape grown by that radius; starting inside a shape, or moving away from it, does not count then).
   */
  private static final class RayQuery {
    final Vector3f origin = new Vector3f();
    final Vector3f dir = new Vector3f();
    Predicate<Collider> accept;
    Entity skip;
    /** Layers a sweep (no filter) stops at. */
    int mask = ~0;
    float inflate;
    final Narrowphase.Sphere bounds = new Narrowphase.Sphere(new Vector3f(), 0f);
    float best;
    Collider bestCollider;
    Vector3f bestNormal;

    RayQuery() {
    }

    RayQuery(Vector3f origin, Vector3f dir, float maxDistance, Predicate<Collider> accept) {
      this.origin.set(origin);
      this.dir.set(dir);
      this.best = maxDistance;
      this.accept = accept;
    }

    void reset(Vector3f origin, Vector3f dir, float maxDistance, float inflate, Entity skip) {
      this.origin.set(origin);
      this.dir.set(dir);
      this.best = maxDistance;
      this.inflate = inflate;
      this.skip = skip;
      this.accept = null;
      this.mask = ~0;
      this.bestCollider = null;
      this.bestNormal = null;
    }

    void test(Collider c) {
      if (this.accept != null ? !this.accept.test(c)
          : c.trigger || c.entity() == this.skip || (this.mask & c.layerBit()) == 0) {
        return;
      }
      c.worldCenter(this.bounds.c());
      this.bounds.r = c.boundingRadius() + this.inflate;
      if (Narrowphase.raySphere(this.bounds, this.origin, this.dir, this.best) == null) {
        return; // cheap reject against the bounding sphere
      }
      final var r = this.inflate > 0f ? Narrowphase.sweepSphere(c, this.origin, this.dir, this.best, this.inflate)
          : Narrowphase.ray(c, this.origin, this.dir, this.best);
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

  /**
   * Nearest non-trigger collider a sphere of {@code radius} touches when moved from {@code origin} along {@code dir}
   * (need not be normalized) by up to {@code maxDistance}. The hit's distance is how far the sphere travels, its point
   * where it touches and its normal faces the sphere. A sphere that already touches something counts as a hit at
   * distance 0 if it moves into it and is not stopped by it if it moves away.
   */
  public Optional<RayHit> sphereCast(Scene scene, Vector3f origin, float radius, Vector3f dir, float maxDistance) {
    return capsuleCast(scene, origin, origin, radius, dir, maxDistance, c -> !c.trigger);
  }

  /** Like {@link #sphereCast(Scene, Vector3f, float, Vector3f, float)} for the colliders accepted by the filter. */
  public Optional<RayHit> sphereCast(Scene scene, Vector3f origin, float radius, Vector3f dir, float maxDistance,
      Predicate<Collider> accept) {
    return capsuleCast(scene, origin, origin, radius, dir, maxDistance, accept);
  }

  /**
   * Like {@link #sphereCast} for a capsule: the segment {@code a}-{@code b} grown by {@code radius}, moved as a whole
   * (it does not turn). Exact for every shape, including the corners of boxes and hulls and the triangles of meshes
   * (one-sided triangles stop it only from the front).
   */
  public Optional<RayHit> capsuleCast(Scene scene, Vector3f a, Vector3f b, float radius, Vector3f dir,
      float maxDistance, Predicate<Collider> accept) {
    if (dir.lengthSquared() < 1e-12f || maxDistance < 0f) {
      return Optional.empty();
    }
    final var cast = this.shapeCast;
    if (!cast(scene, cast, a, b, radius, new Vector3f(dir).normalize(), maxDistance, accept)) {
      return Optional.empty();
    }
    return Optional.of(new RayHit(cast.bestCollider, new Vector3f(cast.bestPoint), new Vector3f(cast.bestNormal),
        cast.best));
  }

  /**
   * The cast behind {@link #capsuleCast}, into a caller's {@link ShapeCast} (the hit is left in its best fields);
   * {@code dir} must be normalized. Does not allocate.
   */
  boolean cast(Scene scene, ShapeCast cast, Vector3f a, Vector3f b, float radius, Vector3f dir, float maxDistance,
      Predicate<Collider> accept) {
    cast.reset(a, b, radius, maxDistance);
    final float r = radius + PAIR_MARGIN;
    final float ex = dir.x * maxDistance;
    final float ey = dir.y * maxDistance;
    final float ez = dir.z * maxDistance;
    final var found = cast.candidates;
    collect(scene.components(Collider.class), Math.min(a.x, b.x) + Math.min(0f, ex) - r,
        Math.min(a.y, b.y) + Math.min(0f, ey) - r, Math.min(a.z, b.z) + Math.min(0f, ez) - r,
        Math.max(a.x, b.x) + Math.max(0f, ex) + r, Math.max(a.y, b.y) + Math.max(0f, ey) + r,
        Math.max(a.z, b.z) + Math.max(0f, ez) + r, found);
    for (int i = 0; i < found.size(); i++) {
      final var c = found.get(i);
      if (c.isEnabled() && accept.test(c)) {
        cast.test(c, dir);
      }
    }
    found.clear();
    return cast.bestCollider != null;
  }

  /** All colliders (including triggers) that overlap the capsule {@code a}-{@code b} of {@code radius}. */
  public List<Collider> overlapCapsule(Scene scene, Vector3f a, Vector3f b, float radius,
      Predicate<Collider> accept) {
    final List<Collider> out = new ArrayList<>();
    near(scene, a, b, radius, this.candidates);
    final var cast = this.shapeCast;
    cast.reset(a, b, radius, 0f);
    for (int i = 0; i < this.candidates.size(); i++) {
      final var c = this.candidates.get(i);
      if (c.isEnabled() && accept.test(c) && cast.overlaps(c)) {
        out.add(c);
      }
    }
    this.candidates.clear();
    return out;
  }

  /** The colliders that may touch the capsule {@code a}-{@code b} of {@code radius} (by the grid of the last step). */
  void near(Scene scene, Vector3f a, Vector3f b, float radius, List<Collider> out) {
    final float r = radius + PAIR_MARGIN;
    collect(scene.components(Collider.class), Math.min(a.x, b.x) - r, Math.min(a.y, b.y) - r,
        Math.min(a.z, b.z) - r, Math.max(a.x, b.x) + r, Math.max(a.y, b.y) + r, Math.max(a.z, b.z) + r, out);
  }

  private final ShapeCast shapeCast = new ShapeCast();
  private final List<Collider> candidates = new ArrayList<>();

  /**
   * The colliders whose grid cells overlap the box (all colliders before the first step or when the scene's colliders
   * changed since), each once.
   */
  private void collect(List<Collider> all, float x0, float y0, float z0, float x1, float y1, float z1,
      List<Collider> out) {
    out.clear();
    if (!gridMatches(all)) {
      out.addAll(all);
      return;
    }
    this.collectStamp = nextQueryStamp();
    this.collectOut = out;
    this.grid.forEachCellInBox(x0, y0, z0, x1, y1, z1, this.collectVisitor);
    this.collectOut = null;
    final var large = this.grid.large();
    for (int k = 0; k < large.size(); k++) {
      collectOnce(large.get(k), this.collectStamp, out);
    }
  }

  private int collectStamp;
  private List<Collider> collectOut;
  private final SpatialGrid.CellVisitor collectVisitor = (ids, x0, y0, z0, x1, y1, z1) -> {
    for (int k = 0; k < ids.size(); k++) {
      collectOnce(ids.get(k), this.collectStamp, this.collectOut);
    }
  };

  private void collectOnce(int index, int stamp, List<Collider> out) {
    if (this.queryStamps[index] != stamp) {
      this.queryStamps[index] = stamp;
      out.add(this.colliders.get(index));
    }
  }
}
