package engine.physics;

import org.joml.Vector3f;

import engine.physics.Narrowphase.Box;
import engine.physics.Narrowphase.Contact;
import engine.physics.Narrowphase.RayResult;
import engine.physics.Narrowphase.Sphere;

/**
 * Contact tests for capsules and mesh triangles (the box and sphere tests live in {@link Narrowphase}). Same
 * conventions: the normal points from shape A towards shape B, depths are penetrations (negative within a speculative
 * margin), manifold points lie halfway between the surfaces, and nothing is allocated.
 *
 * <p>
 * Triangles belong to a {@link MeshCollider} and are always shape B. A one-sided triangle only pushes things out of
 * its front (the side its counter-clockwise winding faces) and ignores shapes entirely behind it.
 */
final class ShapeTests {
  private ShapeTests() {
  }

  /** A segment swept by a sphere; mutable so a collider can refresh it in place. */
  static final class Capsule {
    final Vector3f a = new Vector3f();
    final Vector3f b = new Vector3f();
    float r;
  }

  /** Per-thread scratch so the tests do not allocate. */
  private static final class Scratch {
    final Vector3f p = new Vector3f();
    final Vector3f q = new Vector3f();
    final Vector3f c1 = new Vector3f();
    final Vector3f c2 = new Vector3f();
    final Vector3f d = new Vector3f();
    final Vector3f e = new Vector3f();
    final Vector3f n = new Vector3f();
    final Vector3f m = new Vector3f();
    final Vector3f v0 = new Vector3f();
    final Vector3f v1 = new Vector3f();
    final Vector3f v2 = new Vector3f();
    final Vector3f l = new Vector3f();
    final Vector3f best = new Vector3f();
    final Sphere sphere = new Sphere(new Vector3f(), 0f);
    final Contact extra = new Contact();
    final Contact main = new Contact();
    final float[] st = new float[2];
  }

  private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

  // ---- geometry helpers --------------------------------------------------------------------------------------

  /** Closest point to {@code p} on segment ab; returns its parameter (0 at a, 1 at b). */
  static float closestOnSegment(Vector3f p, Vector3f a, Vector3f b, Vector3f dest) {
    final float abx = b.x - a.x;
    final float aby = b.y - a.y;
    final float abz = b.z - a.z;
    final float len2 = abx * abx + aby * aby + abz * abz;
    float t = len2 > 1e-12f ? ((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / len2 : 0f;
    t = Math.max(0f, Math.min(1f, t));
    dest.set(a.x + abx * t, a.y + aby * t, a.z + abz * t);
    return t;
  }

  /**
   * Closest points between segments p1q1 and p2q2 (Ericson, Real-Time Collision Detection 5.1.9). Writes them to
   * {@code c1}/{@code c2} and their parameters to {@code st}; returns the squared distance.
   */
  static float closestSegmentSegment(Vector3f p1, Vector3f q1, Vector3f p2, Vector3f q2, Vector3f c1, Vector3f c2,
      float[] st) {
    final float d1x = q1.x - p1.x;
    final float d1y = q1.y - p1.y;
    final float d1z = q1.z - p1.z;
    final float d2x = q2.x - p2.x;
    final float d2y = q2.y - p2.y;
    final float d2z = q2.z - p2.z;
    final float rx = p1.x - p2.x;
    final float ry = p1.y - p2.y;
    final float rz = p1.z - p2.z;
    final float a = d1x * d1x + d1y * d1y + d1z * d1z;
    final float e = d2x * d2x + d2y * d2y + d2z * d2z;
    final float f = d2x * rx + d2y * ry + d2z * rz;
    float s;
    float t;
    if (a <= 1e-12f && e <= 1e-12f) {
      s = 0f;
      t = 0f;
    } else if (a <= 1e-12f) {
      s = 0f;
      t = clamp01(f / e);
    } else {
      final float c = d1x * rx + d1y * ry + d1z * rz;
      if (e <= 1e-12f) {
        t = 0f;
        s = clamp01(-c / a);
      } else {
        final float b = d1x * d2x + d1y * d2y + d1z * d2z;
        final float denom = a * e - b * b;
        s = denom > 1e-12f ? clamp01((b * f - c * e) / denom) : 0f;
        t = (b * s + f) / e;
        if (t < 0f) {
          t = 0f;
          s = clamp01(-c / a);
        } else if (t > 1f) {
          t = 1f;
          s = clamp01((b - c) / a);
        }
      }
    }
    c1.set(p1.x + d1x * s, p1.y + d1y * s, p1.z + d1z * s);
    c2.set(p2.x + d2x * t, p2.y + d2y * t, p2.z + d2z * t);
    st[0] = s;
    st[1] = t;
    return c1.distanceSquared(c2);
  }

  private static float clamp01(float v) {
    return Math.max(0f, Math.min(1f, v));
  }

  /** Closest point to {@code p} on triangle abc (Ericson 5.1.5). */
  static Vector3f closestOnTriangle(Vector3f p, Vector3f a, Vector3f b, Vector3f c, Vector3f dest) {
    final float abx = b.x - a.x;
    final float aby = b.y - a.y;
    final float abz = b.z - a.z;
    final float acx = c.x - a.x;
    final float acy = c.y - a.y;
    final float acz = c.z - a.z;
    final float apx = p.x - a.x;
    final float apy = p.y - a.y;
    final float apz = p.z - a.z;
    final float d1 = abx * apx + aby * apy + abz * apz;
    final float d2 = acx * apx + acy * apy + acz * apz;
    if (d1 <= 0f && d2 <= 0f) {
      return dest.set(a);
    }
    final float bpx = p.x - b.x;
    final float bpy = p.y - b.y;
    final float bpz = p.z - b.z;
    final float d3 = abx * bpx + aby * bpy + abz * bpz;
    final float d4 = acx * bpx + acy * bpy + acz * bpz;
    if (d3 >= 0f && d4 <= d3) {
      return dest.set(b);
    }
    final float vc = d1 * d4 - d3 * d2;
    if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
      final float v = d1 / (d1 - d3);
      return dest.set(a.x + abx * v, a.y + aby * v, a.z + abz * v);
    }
    final float cpx = p.x - c.x;
    final float cpy = p.y - c.y;
    final float cpz = p.z - c.z;
    final float d5 = abx * cpx + aby * cpy + abz * cpz;
    final float d6 = acx * cpx + acy * cpy + acz * cpz;
    if (d6 >= 0f && d5 <= d6) {
      return dest.set(c);
    }
    final float vb = d5 * d2 - d1 * d6;
    if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
      final float w = d2 / (d2 - d6);
      return dest.set(a.x + acx * w, a.y + acy * w, a.z + acz * w);
    }
    final float va = d3 * d6 - d5 * d4;
    if (va <= 0f && d4 - d3 >= 0f && d5 - d6 >= 0f) {
      final float w = (d4 - d3) / (d4 - d3 + (d5 - d6));
      return dest.set(b.x + (c.x - b.x) * w, b.y + (c.y - b.y) * w, b.z + (c.z - b.z) * w);
    }
    final float denom = 1f / (va + vb + vc);
    final float v = vb * denom;
    final float w = vc * denom;
    return dest.set(a.x + abx * v + acx * w, a.y + aby * v + acy * w, a.z + abz * v + acz * w);
  }

  /** Fills {@code out} with one contact between a ball at {@code c} and the point {@code q} of B. */
  private static boolean ballToPoint(Vector3f c, float r, Vector3f q, Vector3f fallbackNormal, Contact out,
      float margin) {
    final var n = out.normal.set(q).sub(c);
    final float dist = n.length();
    if (dist > r + margin) {
      return false;
    }
    if (dist > 1e-6f) {
      n.div(dist);
    } else {
      n.set(fallbackNormal);
    }
    out.depth = r - dist;
    final float along = r - 0.5f * out.depth;
    out.single(c.x + n.x * along, c.y + n.y * along, c.z + n.z * along, out.depth);
    return true;
  }

  // ---- capsules ----------------------------------------------------------------------------------------------

  /** Normal from the sphere towards the capsule. */
  static boolean sphereCapsule(Sphere s, Capsule c, Contact out, float margin) {
    final var sc = SCRATCH.get();
    closestOnSegment(s.c(), c.a, c.b, sc.q);
    // the sphere, grown by the capsule's radius, against the closest point of the capsule's axis
    if (!ballToPoint(s.c(), s.r + c.r, sc.q, sc.n.set(0f, -1f, 0f), out, margin)) {
      return false;
    }
    final float along = s.r - 0.5f * out.depth;
    out.single(s.c().x + out.normal.x * along, s.c().y + out.normal.y * along, s.c().z + out.normal.z * along,
        out.depth);
    return true;
  }

  static boolean capsuleCapsule(Capsule a, Capsule b, Contact out, float margin) {
    final var sc = SCRATCH.get();
    final float dist2 = closestSegmentSegment(a.a, a.b, b.a, b.b, sc.c1, sc.c2, sc.st);
    final float reach = a.r + b.r;
    if (dist2 > (reach + margin) * (reach + margin)) {
      return false;
    }
    final var n = out.normal.set(sc.c2).sub(sc.c1);
    final float dist = (float) Math.sqrt(dist2);
    if (dist > 1e-6f) {
      n.div(dist);
    } else { // axes cross: any direction perpendicular to both
      a.b.sub(a.a, sc.d).cross(sc.e.set(b.b).sub(b.a), n);
      if (n.lengthSquared() < 1e-12f) {
        n.set(0f, 1f, 0f);
      }
      n.normalize();
    }
    out.depth = reach - dist;
    out.count = 0;
    // side by side: support both ends of the overlap, so a capsule lying on another does not rock
    sc.d.set(a.b).sub(a.a);
    sc.e.set(b.b).sub(b.a);
    final float la = sc.d.length();
    final float lb = sc.e.length();
    if (la > 1e-4f && lb > 1e-4f && Math.abs(sc.d.dot(sc.e)) > 0.98f * la * lb) {
      final float t0 = clamp01(sc.p.set(b.a).sub(a.a).dot(sc.d) / (la * la));
      final float t1 = clamp01(sc.p.set(b.b).sub(a.a).dot(sc.d) / (la * la));
      if (Math.abs(t1 - t0) * la > 0.01f) {
        addSegmentPoint(a, b, t0, out);
        addSegmentPoint(a, b, t1, out);
        if (out.count > 0) {
          return true;
        }
      }
    }
    final float along = a.r - 0.5f * out.depth;
    out.single(sc.c1.x + n.x * along, sc.c1.y + n.y * along, sc.c1.z + n.z * along, out.depth);
    return true;
  }

  private static void addSegmentPoint(Capsule a, Capsule b, float t, Contact out) {
    final var sc = SCRATCH.get();
    sc.p.set(a.a).lerp(a.b, t);
    closestOnSegment(sc.p, b.a, b.b, sc.q);
    final float depth = a.r + b.r - sc.q.sub(sc.p).dot(out.normal);
    final float along = a.r - 0.5f * depth;
    out.points[out.count].set(sc.p).fma(along, out.normal);
    out.depths[out.count] = depth;
    out.count++;
  }

  /**
   * Normal from the capsule towards the box. The point of the axis closest to the box is found by a ternary search
   * (the distance along a segment to a convex shape has a single minimum); the ends of the axis add support points
   * when the capsule lies flat on the box.
   */
  static boolean capsuleBox(Capsule c, Box b, Contact out, float margin) {
    final var sc = SCRATCH.get();
    float lo = 0f;
    float hi = 1f;
    for (int i = 0; i < 24; i++) {
      final float m1 = lo + (hi - lo) / 3f;
      final float m2 = hi - (hi - lo) / 3f;
      if (boxDistanceSq(c, m1, b, sc) < boxDistanceSq(c, m2, b, sc)) {
        hi = m2;
      } else {
        lo = m1;
      }
    }
    final float t = 0.5f * (lo + hi);
    final var s = sc.sphere;
    s.r = c.r;
    s.c().set(c.a).lerp(c.b, t);
    if (!Narrowphase.sphereBox(s, b, out, margin)) {
      return false;
    }
    final float length = c.a.distance(c.b);
    for (int end = 0; end < 2 && length > 1e-4f; end++) {
      if (Math.abs(end - t) * length < 0.05f) {
        continue; // the main contact is already there
      }
      s.c().set(end == 0 ? c.a : c.b);
      final var extra = sc.extra;
      if (Narrowphase.sphereBox(s, b, extra, margin) && extra.normal.dot(out.normal) > 0.95f
          && out.count < Contact.MAX_POINTS) {
        out.points[out.count].set(extra.points[0]);
        out.depths[out.count] = extra.depth;
        out.count++;
      }
    }
    return true;
  }

  private static float boxDistanceSq(Capsule c, float t, Box b, Scratch sc) {
    sc.p.set(c.a).lerp(c.b, t);
    final var rel = sc.d.set(sc.p).sub(b.c());
    float dist2 = 0f;
    for (int i = 0; i < 3; i++) {
      final float proj = rel.dot(b.axes()[i]);
      final float excess = Math.abs(proj) - b.half()[i];
      if (excess > 0f) {
        dist2 += excess * excess;
      }
    }
    return dist2;
  }

  // ---- triangles ---------------------------------------------------------------------------------------------

  private static void loadTriangle(MeshCollider m, int t, Scratch sc) {
    m.vertex(t, 0, sc.v0);
    m.vertex(t, 1, sc.v1);
    m.vertex(t, 2, sc.v2);
    m.normal(t, sc.n);
  }

  /** A ball (centre, radius) against triangle t of a mesh; normal from the ball towards the triangle. */
  private static boolean ballTriangle(Vector3f c, float r, MeshCollider m, Contact out, float margin, Scratch sc) {
    final float planeDist = sc.n.dot(sc.p.set(c).sub(sc.v0));
    final boolean behind = !m.twoSided && planeDist < 0f;
    if (behind && planeDist < -r) {
      return false; // entirely behind a one-sided triangle
    }
    closestOnTriangle(c, sc.v0, sc.v1, sc.v2, sc.q);
    if (behind) { // the centre is behind the surface but the ball still pokes through: out of the front
      if (c.distance(sc.q) > r + margin) {
        return false; // behind, but beside the triangle
      }
      out.normal.set(sc.n).negate();
      out.depth = r - planeDist;
      final float along = r - 0.5f * out.depth;
      out.single(c.x - sc.n.x * along, c.y - sc.n.y * along, c.z - sc.n.z * along, out.depth);
      return true;
    }
    final var fallback = sc.m.set(sc.n);
    if (planeDist >= 0f) { // in front: the triangle lies along -n from the ball
      fallback.negate();
    }
    return ballToPoint(c, r, sc.q, fallback, out, margin);
  }

  static boolean sphereTriangle(Sphere s, MeshCollider m, int t, Contact out, float margin) {
    final var sc = SCRATCH.get();
    loadTriangle(m, t, sc);
    return ballTriangle(s.c(), s.r, m, out, margin, sc);
  }

  /** Normal from the capsule towards the triangle. */
  static boolean capsuleTriangle(Capsule c, MeshCollider m, int t, Contact out, float margin) {
    final var sc = SCRATCH.get();
    loadTriangle(m, t, sc);
    // the point of the axis closest to the triangle: an end, or a point closest to an edge, or where it pierces
    float bestDist = Float.MAX_VALUE;
    final var best = sc.best;
    final float da = sc.n.dot(sc.p.set(c.a).sub(sc.v0));
    final float db = sc.n.dot(sc.p.set(c.b).sub(sc.v0));
    if (da * db < 0f) {
      final float f = da / (da - db);
      sc.p.set(c.a).lerp(c.b, f);
      closestOnTriangle(sc.p, sc.v0, sc.v1, sc.v2, sc.q);
      if (sc.q.distanceSquared(sc.p) < 1e-10f) {
        best.set(da < db ? c.a : c.b); // pierced: the end below the surface
        bestDist = 0f;
      }
    }
    if (bestDist > 0f) {
      for (int end = 0; end < 2; end++) {
        final var p = end == 0 ? c.a : c.b;
        final float d2 = closestOnTriangle(p, sc.v0, sc.v1, sc.v2, sc.q).distanceSquared(p);
        if (d2 < bestDist) {
          bestDist = d2;
          best.set(p);
        }
      }
      for (int edge = 0; edge < 3; edge++) {
        final var ea = edge == 0 ? sc.v0 : edge == 1 ? sc.v1 : sc.v2;
        final var eb = edge == 0 ? sc.v1 : edge == 1 ? sc.v2 : sc.v0;
        final float d2 = closestSegmentSegment(c.a, c.b, ea, eb, sc.c1, sc.c2, sc.st);
        if (d2 < bestDist) {
          bestDist = d2;
          best.set(sc.c1);
        }
      }
    }
    if (!ballTriangle(best, c.r, m, out, margin, sc)) {
      return false;
    }
    // lying on the triangle: the ends support it too
    final float length = c.a.distance(c.b);
    for (int end = 0; end < 2 && length > 1e-4f; end++) {
      final var p = end == 0 ? c.a : c.b;
      if (p.distanceSquared(best) < 0.0025f) {
        continue;
      }
      sc.e.set(p);
      final var extra = sc.extra;
      if (ballTriangle(sc.e, c.r, m, extra, margin, sc) && extra.normal.dot(out.normal) > 0.95f
          && out.count < Contact.MAX_POINTS) {
        out.points[out.count].set(extra.points[0]);
        out.depths[out.count] = extra.depth;
        out.count++;
      }
    }
    return true;
  }

  /**
   * Separating-axis test of a box against triangle t (13 axes: the triangle's normal, the box's faces and the edge
   * cross products), then clipping like box-box: the box face against the triangle, or the triangle against a box
   * face, or a single point for an edge contact. Normal from the box towards the triangle.
   */
  static boolean boxTriangle(Box b, MeshCollider m, int t, Contact out, float margin) {
    final var sc = SCRATCH.get();
    loadTriangle(m, t, sc);
    final var c = b.c();
    final var n = sc.n;
    // the triangle's plane
    final float rn = Narrowphase.radius(b, n);
    final float dn = n.dot(sc.p.set(sc.v0).sub(c)); // > 0: the plane is on the +n side of the box centre
    float best;
    if (!m.twoSided) {
      if (dn > rn) {
        return false; // the box is entirely behind a one-sided triangle
      }
      best = rn + dn;
      out.normal.set(n).negate();
    } else if (dn > 0f) {
      best = rn - dn;
      out.normal.set(n);
    } else {
      best = rn + dn;
      out.normal.set(n).negate();
    }
    if (best <= -margin) {
      return false;
    }
    int bestAxis = -1; // -1 triangle face, 0..2 box face, 3 + i * 3 + j box axis i x triangle edge j
    final float faceDepth = best;
    final var l = sc.l;
    for (int axis = 0; axis < 12; axis++) {
      if (axis < 3) {
        l.set(b.axes()[axis]);
      } else {
        final int i = (axis - 3) / 3;
        final int j = (axis - 3) % 3;
        final var ea = j == 0 ? sc.v0 : j == 1 ? sc.v1 : sc.v2;
        final var eb = j == 0 ? sc.v1 : j == 1 ? sc.v2 : sc.v0;
        b.axes()[i].cross(sc.e.set(eb).sub(ea), l);
        final float len = l.length();
        if (len < 1e-5f) {
          continue;
        }
        l.div(len);
      }
      final float r = Narrowphase.radius(b, l);
      final float p0 = l.dot(sc.p.set(sc.v0).sub(c));
      final float p1 = l.dot(sc.p.set(sc.v1).sub(c));
      final float p2 = l.dot(sc.p.set(sc.v2).sub(c));
      final float min = Math.min(p0, Math.min(p1, p2));
      final float max = Math.max(p0, Math.max(p1, p2));
      final float plus = r - min; // separate by moving the box towards -l
      final float minus = max + r;
      final float depth = Math.min(plus, minus);
      if (depth <= -margin) {
        return false;
      }
      // the triangle's own normal wins near-ties: edges inside a flat mesh must not make things bump
      if (depth < best - 0.05f * Math.abs(best) - 2e-3f && depth < faceDepth - 0.05f * Math.abs(faceDepth) - 2e-3f) {
        best = depth;
        bestAxis = axis;
        out.normal.set(l);
        if (minus < plus) {
          out.normal.negate();
        }
      }
    }
    out.depth = best;
    out.count = 0;
    if (bestAxis < 0) {
      boxFaceOnTriangle(b, out, margin, sc);
    } else if (bestAxis < 3) {
      triangleOnBoxFace(b, bestAxis, out, margin, sc);
    } else {
      edgeOnTriangleEdge(b, bestAxis, out, sc);
    }
    if (out.count == 0) { // numerically degenerate: the triangle point nearest the box centre
      closestOnTriangle(c, sc.v0, sc.v1, sc.v2, sc.q);
      out.single(sc.q.x, sc.q.y, sc.q.z, out.depth);
    }
    return true;
  }

  /** The box face turned towards the triangle, clipped to the triangle's prism. */
  private static void boxFaceOnTriangle(Box b, Contact out, float margin, Scratch sc) {
    final var nf = out.normal;
    int k = 0;
    float bestDot = -1f;
    for (int i = 0; i < 3; i++) {
      final float d = Math.abs(b.axes()[i].dot(nf));
      if (d > bestDot) {
        bestDot = d;
        k = i;
      }
    }
    final float sign = b.axes()[k].dot(nf) > 0f ? 1f : -1f;
    final var u = b.axes()[(k + 1) % 3];
    final var v = b.axes()[(k + 2) % 3];
    final float hu = b.half()[(k + 1) % 3];
    final float hv = b.half()[(k + 2) % 3];
    final var ak = b.axes()[k];
    final float fx = b.c().x + sign * b.half()[k] * ak.x;
    final float fy = b.c().y + sign * b.half()[k] * ak.y;
    final float fz = b.c().z + sign * b.half()[k] * ak.z;
    final var nsc = Narrowphase.SCRATCH.get();
    float[] poly = nsc.polyA;
    float[] other = nsc.polyB;
    for (int i = 0; i < 4; i++) {
      final float su = i == 0 || i == 3 ? hu : -hu;
      final float sv = i < 2 ? hv : -hv;
      poly[i * 3] = fx + su * u.x + sv * v.x;
      poly[i * 3 + 1] = fy + su * u.y + sv * v.y;
      poly[i * 3 + 2] = fz + su * u.z + sv * v.z;
    }
    int count = 4;
    for (int edge = 0; edge < 3 && count > 0; edge++) {
      final var ea = edge == 0 ? sc.v0 : edge == 1 ? sc.v1 : sc.v2;
      final var eb = edge == 0 ? sc.v1 : edge == 1 ? sc.v2 : sc.v0;
      // outward side of the edge: (edge x normal) for counter-clockwise triangles
      sc.e.set(eb).sub(ea).cross(sc.n, sc.m).normalize();
      count = Narrowphase.clip(poly, count, other, sc.m, 1f, 0f, ea);
      final var swap = poly;
      poly = other;
      other = swap;
    }
    int found = 0;
    for (int i = 0; i < count; i++) {
      // how far the point got past the triangle's plane, along the contact normal
      final float s = nf.x * (poly[i * 3] - sc.v0.x) + nf.y * (poly[i * 3 + 1] - sc.v0.y)
          + nf.z * (poly[i * 3 + 2] - sc.v0.z);
      if (s >= -margin && found < Narrowphase.Scratch.CAP) {
        nsc.candPos[found * 3] = poly[i * 3] - 0.5f * s * nf.x;
        nsc.candPos[found * 3 + 1] = poly[i * 3 + 1] - 0.5f * s * nf.y;
        nsc.candPos[found * 3 + 2] = poly[i * 3 + 2] - 0.5f * s * nf.z;
        nsc.candDepth[found] = s;
        found++;
      }
    }
    if (found > 0) {
      nsc.nRef.set(nf);
      Narrowphase.reduce(out, nsc, found);
    }
  }

  /** The triangle clipped to the side planes of the box face it presses on. */
  private static void triangleOnBoxFace(Box b, int axis, Contact out, float margin, Scratch sc) {
    final var nsc = Narrowphase.SCRATCH.get();
    final var nRef = nsc.nRef.set(out.normal); // the box face's outward normal
    float[] poly = nsc.polyA;
    float[] other = nsc.polyB;
    poly[0] = sc.v0.x;
    poly[1] = sc.v0.y;
    poly[2] = sc.v0.z;
    poly[3] = sc.v1.x;
    poly[4] = sc.v1.y;
    poly[5] = sc.v1.z;
    poly[6] = sc.v2.x;
    poly[7] = sc.v2.y;
    poly[8] = sc.v2.z;
    int count = 3;
    for (int side = 0; side < 4 && count > 0; side++) {
      final int ax = (axis + 1 + side / 2) % 3;
      count = Narrowphase.clip(poly, count, other, b.axes()[ax], side % 2 == 0 ? 1f : -1f, b.half()[ax], b.c());
      final var swap = poly;
      poly = other;
      other = swap;
    }
    final float fx = b.c().x + nRef.x * b.half()[axis];
    final float fy = b.c().y + nRef.y * b.half()[axis];
    final float fz = b.c().z + nRef.z * b.half()[axis];
    int found = 0;
    for (int i = 0; i < count; i++) {
      final float s = nRef.x * (poly[i * 3] - fx) + nRef.y * (poly[i * 3 + 1] - fy) + nRef.z * (poly[i * 3 + 2] - fz);
      if (s <= margin && found < Narrowphase.Scratch.CAP) {
        nsc.candPos[found * 3] = poly[i * 3] - 0.5f * s * nRef.x;
        nsc.candPos[found * 3 + 1] = poly[i * 3 + 1] - 0.5f * s * nRef.y;
        nsc.candPos[found * 3 + 2] = poly[i * 3 + 2] - 0.5f * s * nRef.z;
        nsc.candDepth[found] = -s;
        found++;
      }
    }
    if (found > 0) {
      Narrowphase.reduce(out, nsc, found);
    }
  }

  /** One point halfway between the supporting box edge and the triangle edge. */
  private static void edgeOnTriangleEdge(Box b, int axis, Contact out, Scratch sc) {
    final int i = (axis - 3) / 3;
    final int j = (axis - 3) % 3;
    final var n = out.normal;
    sc.p.set(b.c());
    for (int k = 0; k < 3; k++) {
      if (k != i) {
        sc.p.fma((b.axes()[k].dot(n) >= 0f ? 1f : -1f) * b.half()[k], b.axes()[k]);
      }
    }
    sc.c1.set(sc.p).fma(-b.half()[i], b.axes()[i]);
    sc.c2.set(sc.p).fma(b.half()[i], b.axes()[i]);
    final var ea = j == 0 ? sc.v0 : j == 1 ? sc.v1 : sc.v2;
    final var eb = j == 0 ? sc.v1 : j == 1 ? sc.v2 : sc.v0;
    sc.d.set(sc.c1);
    sc.e.set(sc.c2);
    closestSegmentSegment(sc.d, sc.e, ea, eb, sc.c1, sc.c2, sc.st);
    out.single(0.5f * (sc.c1.x + sc.c2.x), 0.5f * (sc.c1.y + sc.c2.y), 0.5f * (sc.c1.z + sc.c2.z), out.depth);
  }

  // ---- rays --------------------------------------------------------------------------------------------------

  /** Ray against a capsule (cylinder body plus end caps); {@code d} normalized. */
  static RayResult rayCapsule(Capsule cap, Vector3f o, Vector3f d, float maxDistance) {
    final var axisPoint = new Vector3f();
    closestOnSegment(o, cap.a, cap.b, axisPoint);
    if (axisPoint.distanceSquared(o) <= cap.r * cap.r) {
      return new RayResult(0f, new Vector3f(d).negate()); // started inside
    }
    final var ba = new Vector3f(cap.b).sub(cap.a);
    final var oa = new Vector3f(o).sub(cap.a);
    final float baba = ba.dot(ba);
    final float bard = ba.dot(d);
    final float baoa = ba.dot(oa);
    final float rdoa = d.dot(oa);
    final float oaoa = oa.dot(oa);
    float best = Float.MAX_VALUE;
    final float a = baba - bard * bard;
    if (a > 1e-9f) {
      final float b = baba * rdoa - baoa * bard;
      final float c = baba * oaoa - baoa * baoa - cap.r * cap.r * baba;
      final float h = b * b - a * c;
      if (h >= 0f) {
        final float t = (-b - (float) Math.sqrt(h)) / a;
        final float y = baoa + t * bard;
        if (t >= 0f && y > 0f && y < baba) {
          best = t;
        }
      }
    }
    for (int end = 0; end < 2; end++) { // the caps (also when the body was missed or hit from an end)
      final var center = end == 0 ? cap.a : cap.b;
      final var oc = new Vector3f(o).sub(center);
      final float b = d.dot(oc);
      final float c = oc.dot(oc) - cap.r * cap.r;
      final float h = b * b - c;
      if (h >= 0f) {
        final float t = -b - (float) Math.sqrt(h);
        if (t >= 0f && t < best) {
          best = t;
        }
      }
    }
    if (best > maxDistance) {
      return null;
    }
    final var hit = new Vector3f(o).fma(best, d);
    closestOnSegment(hit, cap.a, cap.b, axisPoint);
    return new RayResult(best, hit.sub(axisPoint).normalize());
  }

  /**
   * Möller-Trumbore ray-triangle test; returns the distance or -1. One-sided triangles are only hit from the front.
   */
  static float rayTriangle(MeshCollider m, int t, Vector3f o, Vector3f d, float maxDistance) {
    final var sc = SCRATCH.get();
    loadTriangle(m, t, sc);
    if (!m.twoSided && sc.n.dot(d) > 0f) {
      return -1f;
    }
    final var e1 = sc.c1.set(sc.v1).sub(sc.v0);
    final var e2 = sc.c2.set(sc.v2).sub(sc.v0);
    final var p = d.cross(e2, sc.p);
    final float det = e1.dot(p);
    if (Math.abs(det) < 1e-12f) {
      return -1f;
    }
    final float inv = 1f / det;
    final var s = sc.q.set(o).sub(sc.v0);
    final float u = s.dot(p) * inv;
    if (u < 0f || u > 1f) {
      return -1f;
    }
    final var q = s.cross(e1, sc.e);
    final float v = d.dot(q) * inv;
    if (v < 0f || u + v > 1f) {
      return -1f;
    }
    final float dist = e2.dot(q) * inv;
    return dist >= 0f && dist <= maxDistance ? dist : -1f;
  }
}
