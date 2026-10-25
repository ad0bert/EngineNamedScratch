package engine.physics;

import org.joml.Vector3f;

/**
 * Pairwise overlap tests. Contact normals point from shape A towards shape B.
 *
 * <p>
 * The tests that run every physics step ({@link #test}, and the shape-pair methods taking a {@link Contact} to fill)
 * do not allocate; the convenience overloads that return a new {@link Contact} are meant for queries and tests.
 */
public final class Narrowphase {
  private Narrowphase() {
  }

  /** Penetration of A into B: push A by {@code -normal * depth} (or B by {@code +normal * depth}) to separate. */
  public static final class Contact {
    private final Vector3f normal = new Vector3f();
    private float depth;

    public Contact() {
    }

    public Contact(Vector3f normal, float depth) {
      this.normal.set(normal);
      this.depth = depth;
    }

    public Vector3f normal() {
      return this.normal;
    }

    public float depth() {
      return this.depth;
    }
  }

  /** Per-thread scratch vectors so the tests below do not allocate. */
  private static final class Scratch {
    final Vector3f t = new Vector3f();
    final Vector3f l = new Vector3f();
    final Vector3f closest = new Vector3f();
    final Vector3f diff = new Vector3f();
    final float[] p = new float[3];
  }

  private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

  record Box(Vector3f c, Vector3f[] axes, float[] half) {
  }

  /** Mutable so a collider can keep one instance and refresh it in place. */
  static final class Sphere {
    private final Vector3f c;
    float r;

    Sphere(Vector3f c, float r) {
      this.c = c;
      this.r = r;
    }

    Vector3f c() {
      return this.c;
    }

    float r() {
      return this.r;
    }
  }

  /** Tests the colliders' cached world shapes (see {@link Collider#refresh()}); fills {@code out} on overlap. */
  static boolean test(Collider a, Collider b, Contact out) {
    if (a instanceof BoxCollider ba && b instanceof BoxCollider bb) {
      return boxBox(ba.cachedBox(), bb.cachedBox(), out);
    }
    if (a instanceof SphereCollider sa && b instanceof SphereCollider sb) {
      return sphereSphere(sa.cachedSphere(), sb.cachedSphere(), out);
    }
    if (a instanceof SphereCollider sa && b instanceof BoxCollider bb) {
      return sphereBox(sa.cachedSphere(), bb.cachedBox(), out);
    }
    if (a instanceof BoxCollider ba && b instanceof SphereCollider sb) {
      if (!sphereBox(sb.cachedSphere(), ba.cachedBox(), out)) {
        return false;
      }
      out.normal.negate();
      return true;
    }
    return false;
  }

  /** Distance along a ray to a surface, and the surface normal there (facing the ray). */
  record RayResult(float distance, Vector3f normal) {
  }

  /** @param dir must be normalized; returns null for a miss or a hit beyond {@code maxDistance} */
  static RayResult ray(Collider c, Vector3f origin, Vector3f dir, float maxDistance) {
    if (c instanceof BoxCollider b) {
      return rayBox(b.world(), origin, dir, maxDistance);
    }
    if (c instanceof SphereCollider s) {
      return raySphere(s.world(), origin, dir, maxDistance);
    }
    return null;
  }

  static RayResult raySphere(Sphere s, Vector3f o, Vector3f d, float maxDistance) {
    final Vector3f oc = new Vector3f(o).sub(s.c());
    final float b = oc.dot(d);
    final float cc = oc.lengthSquared() - s.r() * s.r();
    if (cc > 0f && b > 0f) {
      return null; // outside and pointing away
    }
    final float disc = b * b - cc;
    if (disc < 0f) {
      return null;
    }
    final float t = -b - (float) Math.sqrt(disc);
    if (t < 0f) {
      return new RayResult(0f, new Vector3f(d).negate()); // started inside
    }
    if (t > maxDistance) {
      return null;
    }
    return new RayResult(t, oc.fma(t, d).div(s.r()));
  }

  /** Slab test in the box's own axes, so rotated boxes are hit exactly. */
  static RayResult rayBox(Box box, Vector3f o, Vector3f d, float maxDistance) {
    final Vector3f rel = new Vector3f(o).sub(box.c());
    float tMin = -Float.MAX_VALUE;
    float tMax = Float.MAX_VALUE;
    int hitAxis = -1;
    float hitSign = 1f;
    for (int i = 0; i < 3; i++) {
      final float lo = rel.dot(box.axes()[i]);
      final float ld = d.dot(box.axes()[i]);
      final float h = box.half()[i];
      if (Math.abs(ld) < 1e-8f) {
        if (Math.abs(lo) > h) {
          return null; // parallel to this slab and outside it
        }
        continue;
      }
      float t1 = (-h - lo) / ld;
      float t2 = (h - lo) / ld;
      float sign = -1f; // entering through the negative face
      if (t1 > t2) {
        final float tmp = t1;
        t1 = t2;
        t2 = tmp;
        sign = 1f;
      }
      if (t1 > tMin) {
        tMin = t1;
        hitAxis = i;
        hitSign = sign;
      }
      tMax = Math.min(tMax, t2);
      if (tMin > tMax) {
        return null;
      }
    }
    if (tMax < 0f) {
      return null; // box is behind the ray
    }
    if (tMin < 0f || hitAxis < 0) {
      return new RayResult(0f, new Vector3f(d).negate()); // started inside
    }
    if (tMin > maxDistance) {
      return null;
    }
    return new RayResult(tMin, new Vector3f(box.axes()[hitAxis]).mul(hitSign));
  }

  /** True if the collider overlaps the sphere. */
  static boolean overlapsSphere(Collider c, Sphere s) {
    final Contact scratch = new Contact();
    if (c instanceof BoxCollider b) {
      return sphereBox(s, b.world(), scratch);
    }
    if (c instanceof SphereCollider other) {
      return sphereSphere(s, other.world(), scratch);
    }
    return false;
  }

  static Contact sphereSphere(Sphere a, Sphere b) {
    final Contact c = new Contact();
    return sphereSphere(a, b, c) ? c : null;
  }

  static boolean sphereSphere(Sphere a, Sphere b, Contact out) {
    final Vector3f n = out.normal.set(b.c()).sub(a.c());
    final float len = n.length();
    final float sum = a.r() + b.r();
    if (len >= sum) {
      return false;
    }
    if (len > 1e-6f) {
      n.div(len);
    } else {
      n.set(0f, 1f, 0f);
    }
    out.depth = sum - len;
    return true;
  }

  /** Normal points from the sphere towards the box. */
  static Contact sphereBox(Sphere s, Box b) {
    final Contact c = new Contact();
    return sphereBox(s, b, c) ? c : null;
  }

  static boolean sphereBox(Sphere s, Box b, Contact out) {
    final Scratch sc = SCRATCH.get();
    final Vector3f d = sc.t.set(s.c()).sub(b.c());
    final float[] p = sc.p;
    final Vector3f closest = sc.closest.set(b.c());
    for (int i = 0; i < 3; i++) {
      p[i] = d.dot(b.axes()[i]);
      final float q = Math.max(-b.half()[i], Math.min(b.half()[i], p[i]));
      closest.fma(q, b.axes()[i]);
    }
    final Vector3f diff = closest.sub(s.c(), sc.diff); // sphere centre -> nearest box point
    final float dist2 = diff.lengthSquared();
    if (dist2 > s.r() * s.r()) {
      return false;
    }
    if (dist2 > 1e-10f) {
      final float dist = (float) Math.sqrt(dist2);
      out.normal.set(diff).div(dist);
      out.depth = s.r() - dist;
      return true;
    }
    // centre inside the box: leave through the nearest face
    int axis = 0;
    float bestGap = Float.MAX_VALUE;
    for (int i = 0; i < 3; i++) {
      final float gap = b.half()[i] - Math.abs(p[i]);
      if (gap < bestGap) {
        bestGap = gap;
        axis = i;
      }
    }
    out.normal.set(b.axes()[axis]).mul(p[axis] >= 0f ? -1f : 1f); // the negated outward direction
    out.depth = s.r() + bestGap;
    return true;
  }

  /** Separating-axis test over the 15 candidate axes; keeps the axis of least penetration. */
  static Contact boxBox(Box a, Box b) {
    final Contact c = new Contact();
    return boxBox(a, b, c) ? c : null;
  }

  static boolean boxBox(Box a, Box b, Contact out) {
    final Scratch sc = SCRATCH.get();
    final Vector3f t = sc.t.set(b.c()).sub(a.c());
    float best = Float.MAX_VALUE;
    boolean found = false;
    final Vector3f l = sc.l;
    for (int i = 0; i < 15; i++) {
      if (i < 3) {
        l.set(a.axes()[i]);
      } else if (i < 6) {
        l.set(b.axes()[i - 3]);
      } else {
        a.axes()[(i - 6) / 3].cross(b.axes()[(i - 6) % 3], l);
        final float len = l.length();
        if (len < 1e-5f) {
          continue; // parallel edges: covered by the face axes
        }
        l.div(len);
      }
      final float overlap = radius(a, l) + radius(b, l) - Math.abs(t.dot(l));
      if (overlap <= 0f) {
        return false; // separating axis found
      }
      // prefer face axes: an edge axis must be clearly better to win
      final boolean better = i < 6 ? overlap < best : overlap < best * 0.95f - 1e-4f;
      if (better) {
        best = overlap;
        out.normal.set(l);
        found = true;
      }
    }
    if (!found) {
      return false;
    }
    if (t.dot(out.normal) < 0f) {
      out.normal.negate();
    }
    out.depth = best;
    return true;
  }

  private static float radius(Box box, Vector3f axis) {
    return box.half()[0] * Math.abs(box.axes()[0].dot(axis)) + box.half()[1] * Math.abs(box.axes()[1].dot(axis))
        + box.half()[2] * Math.abs(box.axes()[2].dot(axis));
  }
}
