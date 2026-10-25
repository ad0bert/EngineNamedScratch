package engine.physics;

import org.joml.Vector3f;

/** Pairwise overlap tests. Contact normals point from shape A towards shape B. */
public final class Narrowphase {
  private Narrowphase() {
  }

  /** Penetration of A into B: push A by {@code -normal * depth} (or B by {@code +normal * depth}) to separate. */
  public record Contact(Vector3f normal, float depth) {
  }

  record Box(Vector3f c, Vector3f[] axes, float[] half) {
  }

  record Sphere(Vector3f c, float r) {
  }

  static Contact test(Collider a, Collider b) {
    if (a instanceof BoxCollider ba && b instanceof BoxCollider bb) {
      return boxBox(ba.world(), bb.world());
    }
    if (a instanceof SphereCollider sa && b instanceof SphereCollider sb) {
      return sphereSphere(sa.world(), sb.world());
    }
    if (a instanceof SphereCollider sa && b instanceof BoxCollider bb) {
      return sphereBox(sa.world(), bb.world());
    }
    if (a instanceof BoxCollider ba && b instanceof SphereCollider sb) {
      final Contact c = sphereBox(sb.world(), ba.world());
      return c == null ? null : new Contact(c.normal().negate(), c.depth());
    }
    return null;
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
    if (c instanceof BoxCollider b) {
      return sphereBox(s, b.world()) != null;
    }
    if (c instanceof SphereCollider other) {
      return sphereSphere(s, other.world()) != null;
    }
    return false;
  }

  static Contact sphereSphere(Sphere a, Sphere b) {
    final Vector3f d = new Vector3f(b.c()).sub(a.c());
    final float len = d.length();
    final float sum = a.r() + b.r();
    if (len >= sum) {
      return null;
    }
    final Vector3f n = len > 1e-6f ? d.div(len) : new Vector3f(0f, 1f, 0f);
    return new Contact(n, sum - len);
  }

  /** Normal points from the sphere towards the box. */
  static Contact sphereBox(Sphere s, Box b) {
    final Vector3f d = new Vector3f(s.c()).sub(b.c());
    final float[] p = new float[3];
    final Vector3f closest = new Vector3f(b.c());
    for (int i = 0; i < 3; i++) {
      p[i] = d.dot(b.axes()[i]);
      final float q = Math.max(-b.half()[i], Math.min(b.half()[i], p[i]));
      closest.fma(q, b.axes()[i]);
    }
    final Vector3f diff = closest.sub(s.c(), new Vector3f()); // sphere centre -> nearest box point
    final float dist2 = diff.lengthSquared();
    if (dist2 > s.r() * s.r()) {
      return null;
    }
    if (dist2 > 1e-10f) {
      final float dist = (float) Math.sqrt(dist2);
      return new Contact(diff.div(dist), s.r() - dist);
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
    final Vector3f outward = new Vector3f(b.axes()[axis]).mul(p[axis] >= 0f ? 1f : -1f);
    return new Contact(outward.negate(), s.r() + bestGap);
  }

  /** Separating-axis test over the 15 candidate axes; keeps the axis of least penetration. */
  static Contact boxBox(Box a, Box b) {
    final Vector3f t = new Vector3f(b.c()).sub(a.c());
    float best = Float.MAX_VALUE;
    Vector3f bestAxis = null;
    final Vector3f l = new Vector3f();
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
        return null; // separating axis found
      }
      // prefer face axes: an edge axis must be clearly better to win
      final boolean better = i < 6 ? overlap < best : overlap < best * 0.95f - 1e-4f;
      if (better) {
        best = overlap;
        bestAxis = new Vector3f(l);
      }
    }
    if (bestAxis == null) {
      return null;
    }
    if (t.dot(bestAxis) < 0f) {
      bestAxis.negate();
    }
    return new Contact(bestAxis, best);
  }

  private static float radius(Box box, Vector3f axis) {
    return box.half()[0] * Math.abs(box.axes()[0].dot(axis)) + box.half()[1] * Math.abs(box.axes()[1].dot(axis))
        + box.half()[2] * Math.abs(box.axes()[2].dot(axis));
  }
}
