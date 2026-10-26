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

  /**
   * Penetration of A into B: push A by {@code -normal * depth} (or B by {@code +normal * depth}) to separate.
   * {@link #depth()} is the deepest penetration; the contact manifold holds up to {@link #MAX_POINTS} world-space
   * points (halfway between the two surfaces) with their own depths. Box-box manifolds are only built when
   * {@code wantPoints} is set, because clipping costs more than the overlap test. Tests run with a margin also report
   * shapes that are up to that far apart: their depths are then negative (the gap).
   */
  public static final class Contact {
    public static final int MAX_POINTS = 4;

    private final Vector3f normal = new Vector3f();
    private float depth;
    private int count;
    private final Vector3f[] points = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
    private final float[] depths = new float[MAX_POINTS];
    boolean wantPoints;

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

    public int count() {
      return this.count;
    }

    public Vector3f point(int i) {
      return this.points[i];
    }

    public float depth(int i) {
      return this.depths[i];
    }

    private void single(float x, float y, float z, float d) {
      this.count = 1;
      this.points[0].set(x, y, z);
      this.depths[0] = d;
    }
  }

  /** Per-thread scratch vectors so the tests below do not allocate. */
  private static final class Scratch {
    static final int CAP = 16; // clipping a quad against four planes yields at most eight vertices

    final Vector3f t = new Vector3f();
    final Vector3f l = new Vector3f();
    final Vector3f closest = new Vector3f();
    final Vector3f diff = new Vector3f();
    final Vector3f nRef = new Vector3f();
    final float[] p = new float[3];
    float[] polyA = new float[CAP * 3];
    float[] polyB = new float[CAP * 3];
    final float[] candPos = new float[CAP * 3];
    final float[] candDepth = new float[CAP];
    final boolean[] taken = new boolean[CAP];
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
    return test(a, b, out, 0f);
  }

  /**
   * Like {@link #test(Collider, Collider, Contact)}, but also reports shapes that are up to {@code margin} apart
   * (speculative contacts, with a negative depth).
   */
  static boolean test(Collider a, Collider b, Contact out, float margin) {
    if (a instanceof BoxCollider ba && b instanceof BoxCollider bb) {
      return boxBox(ba.cachedBox(), bb.cachedBox(), out, margin);
    }
    if (a instanceof SphereCollider sa && b instanceof SphereCollider sb) {
      return sphereSphere(sa.cachedSphere(), sb.cachedSphere(), out, margin);
    }
    if (a instanceof SphereCollider sa && b instanceof BoxCollider bb) {
      return sphereBox(sa.cachedSphere(), bb.cachedBox(), out, margin);
    }
    if (a instanceof BoxCollider ba && b instanceof SphereCollider sb) {
      if (!sphereBox(sb.cachedSphere(), ba.cachedBox(), out, margin)) {
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
    final var oc = new Vector3f(o).sub(s.c());
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
    final var rel = new Vector3f(o).sub(box.c());
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
    final var scratch = new Contact();
    if (c instanceof BoxCollider b) {
      return sphereBox(s, b.world(), scratch);
    }
    if (c instanceof SphereCollider other) {
      return sphereSphere(s, other.world(), scratch);
    }
    return false;
  }

  static Contact sphereSphere(Sphere a, Sphere b) {
    final var c = new Contact();
    return sphereSphere(a, b, c) ? c : null;
  }

  static boolean sphereSphere(Sphere a, Sphere b, Contact out) {
    return sphereSphere(a, b, out, 0f);
  }

  static boolean sphereSphere(Sphere a, Sphere b, Contact out, float margin) {
    final var n = out.normal.set(b.c()).sub(a.c());
    final float len = n.length();
    final float sum = a.r() + b.r();
    if (len >= sum + margin) {
      return false;
    }
    if (len > 1e-6f) {
      n.div(len);
    } else {
      n.set(0f, 1f, 0f);
    }
    out.depth = sum - len;
    final float along = a.r() - 0.5f * out.depth;
    out.single(a.c().x + n.x * along, a.c().y + n.y * along, a.c().z + n.z * along, out.depth);
    return true;
  }

  /** Normal points from the sphere towards the box. */
  static Contact sphereBox(Sphere s, Box b) {
    final var c = new Contact();
    return sphereBox(s, b, c) ? c : null;
  }

  static boolean sphereBox(Sphere s, Box b, Contact out) {
    return sphereBox(s, b, out, 0f);
  }

  static boolean sphereBox(Sphere s, Box b, Contact out, float margin) {
    final var sc = SCRATCH.get();
    final var d = sc.t.set(s.c()).sub(b.c());
    final float[] p = sc.p;
    final var closest = sc.closest.set(b.c());
    for (int i = 0; i < 3; i++) {
      p[i] = d.dot(b.axes()[i]);
      final float q = Math.max(-b.half()[i], Math.min(b.half()[i], p[i]));
      closest.fma(q, b.axes()[i]);
    }
    final Vector3f diff = closest.sub(s.c(), sc.diff); // sphere centre -> nearest box point
    final float dist2 = diff.lengthSquared();
    final float reach = s.r() + margin;
    if (dist2 > reach * reach) {
      return false;
    }
    if (dist2 > 1e-10f) {
      final float dist = (float) Math.sqrt(dist2);
      out.normal.set(diff).div(dist);
      out.depth = s.r() - dist;
      final float along = s.r() - 0.5f * out.depth;
      out.single(s.c().x + out.normal.x * along, s.c().y + out.normal.y * along, s.c().z + out.normal.z * along,
          out.depth);
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
    final float along = s.r() - 0.5f * out.depth;
    out.single(s.c().x + out.normal.x * along, s.c().y + out.normal.y * along, s.c().z + out.normal.z * along,
        out.depth);
    return true;
  }

  /** Separating-axis test over the 15 candidate axes; keeps the axis of least penetration. */
  static Contact boxBox(Box a, Box b) {
    final var c = new Contact();
    c.wantPoints = true;
    return boxBox(a, b, c) ? c : null;
  }

  static boolean boxBox(Box a, Box b, Contact out) {
    return boxBox(a, b, out, 0f);
  }

  static boolean boxBox(Box a, Box b, Contact out, float margin) {
    final var sc = SCRATCH.get();
    final var t = sc.t.set(b.c()).sub(a.c());
    float best = Float.MAX_VALUE;
    int bestAxis = -1;
    boolean found = false;
    final var l = sc.l;
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
      if (overlap <= -margin) {
        return false; // separating axis found (further apart than the margin)
      }
      // prefer face axes: an edge axis must be clearly better to win
      final boolean better = i < 6 ? overlap < best : overlap < best - 0.05f * Math.abs(best) - 1e-4f;
      if (better) {
        best = overlap;
        bestAxis = i;
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
    out.count = 0;
    if (out.wantPoints) {
      if (bestAxis < 6) {
        faceContact(a, b, bestAxis, out, sc, margin);
      } else {
        edgeContact(a, b, bestAxis, out, sc);
      }
    }
    return true;
  }

  /**
   * Face contact: the incident box's most facing face is clipped against the side planes of the reference face, and
   * the vertices that ended up behind the reference face become the manifold.
   */
  private static void faceContact(Box a, Box b, int axisIndex, Contact out, Scratch sc, float margin) {
    final boolean refIsA = axisIndex < 3;
    final Box ref = refIsA ? a : b;
    final Box inc = refIsA ? b : a;
    final int ri = refIsA ? axisIndex : axisIndex - 3;
    final var nRef = sc.nRef.set(out.normal); // from the reference box towards the incident one
    if (!refIsA) {
      nRef.negate();
    }

    int k = 0;
    float bestDot = -1f;
    for (int i = 0; i < 3; i++) {
      final float d = Math.abs(inc.axes()[i].dot(nRef));
      if (d > bestDot) {
        bestDot = d;
        k = i;
      }
    }
    final float faceSign = inc.axes()[k].dot(nRef) > 0f ? -1f : 1f;
    final var u = inc.axes()[(k + 1) % 3];
    final var v = inc.axes()[(k + 2) % 3];
    final float hu = inc.half()[(k + 1) % 3];
    final float hv = inc.half()[(k + 2) % 3];
    final var axisK = inc.axes()[k];
    final float cx = inc.c().x + faceSign * inc.half()[k] * axisK.x;
    final float cy = inc.c().y + faceSign * inc.half()[k] * axisK.y;
    final float cz = inc.c().z + faceSign * inc.half()[k] * axisK.z;
    float[] poly = sc.polyA;
    float[] other = sc.polyB;
    for (int i = 0; i < 4; i++) {
      final float su = i == 0 || i == 3 ? hu : -hu;
      final float sv = i < 2 ? hv : -hv;
      poly[i * 3] = cx + su * u.x + sv * v.x;
      poly[i * 3 + 1] = cy + su * u.y + sv * v.y;
      poly[i * 3 + 2] = cz + su * u.z + sv * v.z;
    }
    int n = 4;
    for (int side = 0; side < 4 && n > 0; side++) {
      final int axis = (ri + 1 + side / 2) % 3;
      final float sign = side % 2 == 0 ? 1f : -1f;
      n = clip(poly, n, other, ref.axes()[axis], sign, ref.half()[axis], ref.c());
      final var swap = poly;
      poly = other;
      other = swap;
    }

    final float fx = ref.c().x + nRef.x * ref.half()[ri];
    final float fy = ref.c().y + nRef.y * ref.half()[ri];
    final float fz = ref.c().z + nRef.z * ref.half()[ri];
    int count = 0;
    int shallowest = -1;
    float shallowestS = -Float.MAX_VALUE;
    float[] cand = sc.candPos;
    for (int i = 0; i < n; i++) {
      final float s = nRef.x * (poly[i * 3] - fx) + nRef.y * (poly[i * 3 + 1] - fy)
          + nRef.z * (poly[i * 3 + 2] - fz);
      if (s > shallowestS) {
        shallowestS = s;
        shallowest = i;
      }
      if (s <= margin) {
        cand[count * 3] = poly[i * 3] - 0.5f * s * nRef.x;
        cand[count * 3 + 1] = poly[i * 3 + 1] - 0.5f * s * nRef.y;
        cand[count * 3 + 2] = poly[i * 3 + 2] - 0.5f * s * nRef.z;
        sc.candDepth[count] = -s;
        count++;
      }
    }
    if (count == 0) {
      if (shallowest < 0) {
        // numerically degenerate: fall back to the middle of the incident face
        out.single(cx, cy, cz, out.depth);
        return;
      }
      cand[0] = poly[shallowest * 3];
      cand[1] = poly[shallowest * 3 + 1];
      cand[2] = poly[shallowest * 3 + 2];
      sc.candDepth[0] = margin > 0f ? -shallowestS : Math.max(-shallowestS, 1e-4f);
      count = 1;
    }
    reduce(out, sc, count);
  }

  /** Sutherland-Hodgman against the half space {@code sign * axis . (p - origin) <= limit}; returns the new count. */
  private static int clip(float[] in, int n, float[] out, Vector3f axis, float sign, float limit, Vector3f origin) {
    int m = 0;
    final float ax = axis.x * sign;
    final float ay = axis.y * sign;
    final float az = axis.z * sign;
    final float ox = origin.x;
    final float oy = origin.y;
    final float oz = origin.z;
    for (int i = 0; i < n; i++) {
      final int j = (i + 1) % n;
      final float di = ax * (in[i * 3] - ox) + ay * (in[i * 3 + 1] - oy) + az * (in[i * 3 + 2] - oz) - limit;
      final float dj = ax * (in[j * 3] - ox) + ay * (in[j * 3 + 1] - oy) + az * (in[j * 3 + 2] - oz) - limit;
      if (di <= 0f && m < Scratch.CAP) {
        out[m * 3] = in[i * 3];
        out[m * 3 + 1] = in[i * 3 + 1];
        out[m * 3 + 2] = in[i * 3 + 2];
        m++;
      }
      if (((di < 0f && dj > 0f) || (di > 0f && dj < 0f)) && m < Scratch.CAP) {
        final float f = di / (di - dj);
        out[m * 3] = in[i * 3] + f * (in[j * 3] - in[i * 3]);
        out[m * 3 + 1] = in[i * 3 + 1] + f * (in[j * 3 + 1] - in[i * 3 + 1]);
        out[m * 3 + 2] = in[i * 3 + 2] + f * (in[j * 3 + 2] - in[i * 3 + 2]);
        m++;
      }
    }
    return m;
  }

  /** Copies the candidates into the contact, keeping at most four that span the largest area. */
  private static void reduce(Contact out, Scratch sc, int count) {
    final float[] p = sc.candPos;
    final float[] d = sc.candDepth;
    if (count <= Contact.MAX_POINTS) {
      out.count = count;
      for (int i = 0; i < count; i++) {
        out.points[i].set(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
        out.depths[i] = d[i];
      }
      return;
    }
    java.util.Arrays.fill(sc.taken, 0, count, false);
    int first = 0; // deepest point
    for (int i = 1; i < count; i++) {
      if (d[i] > d[first]) {
        first = i;
      }
    }
    sc.taken[first] = true;
    int second = -1; // farthest from it
    float far = -1f;
    for (int i = 0; i < count; i++) {
      if (sc.taken[i]) {
        continue;
      }
      final float dx = p[i * 3] - p[first * 3];
      final float dy = p[i * 3 + 1] - p[first * 3 + 1];
      final float dz = p[i * 3 + 2] - p[first * 3 + 2];
      final float dist = dx * dx + dy * dy + dz * dz;
      if (dist > far) {
        far = dist;
        second = i;
      }
    }
    sc.taken[second] = true;
    // the points on either side of that edge that enclose the largest signed areas
    final var n = sc.nRef;
    final float ex = p[second * 3] - p[first * 3];
    final float ey = p[second * 3 + 1] - p[first * 3 + 1];
    final float ez = p[second * 3 + 2] - p[first * 3 + 2];
    int third = -1;
    int fourth = -1;
    float maxArea = 0f;
    float minArea = 0f;
    for (int i = 0; i < count; i++) {
      if (sc.taken[i]) {
        continue;
      }
      final float dx = p[i * 3] - p[first * 3];
      final float dy = p[i * 3 + 1] - p[first * 3 + 1];
      final float dz = p[i * 3 + 2] - p[first * 3 + 2];
      final float area = n.x * (ey * dz - ez * dy) + n.y * (ez * dx - ex * dz) + n.z * (ex * dy - ey * dx);
      if (area > maxArea) {
        maxArea = area;
        third = i;
      }
      if (area < minArea) {
        minArea = area;
        fourth = i;
      }
    }
    out.count = 0;
    addPoint(out, p, d, first);
    addPoint(out, p, d, second);
    addPoint(out, p, d, third);
    addPoint(out, p, d, fourth);
  }

  private static void addPoint(Contact out, float[] p, float[] d, int i) {
    if (i >= 0) {
      out.points[out.count].set(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
      out.depths[out.count] = d[i];
      out.count++;
    }
  }

  /** Edge contact: a single point halfway between the closest points of the two supporting edges. */
  private static void edgeContact(Box a, Box b, int axisIndex, Contact out, Scratch sc) {
    final int ia = (axisIndex - 6) / 3;
    final int ib = (axisIndex - 6) % 3;
    final var n = out.normal;
    float pax = a.c().x;
    float pay = a.c().y;
    float paz = a.c().z;
    float pbx = b.c().x;
    float pby = b.c().y;
    float pbz = b.c().z;
    for (int j = 0; j < 3; j++) {
      if (j != ia) {
        final var ax = a.axes()[j];
        final float s = (ax.dot(n) >= 0f ? 1f : -1f) * a.half()[j];
        pax += s * ax.x;
        pay += s * ax.y;
        paz += s * ax.z;
      }
      if (j != ib) {
        final var bx = b.axes()[j];
        final float s = (bx.dot(n) >= 0f ? -1f : 1f) * b.half()[j];
        pbx += s * bx.x;
        pby += s * bx.y;
        pbz += s * bx.z;
      }
    }
    final var d1 = a.axes()[ia];
    final var d2 = b.axes()[ib];
    final float rx = pax - pbx;
    final float ry = pay - pby;
    final float rz = paz - pbz;
    final float bq = d1.dot(d2);
    final float c = d1.x * rx + d1.y * ry + d1.z * rz;
    final float f = d2.x * rx + d2.y * ry + d2.z * rz;
    final float denom = 1f - bq * bq;
    final float s = denom > 1e-8f ? (bq * f - c) / denom : 0f;
    final float t = bq * s + f;
    final float mx = 0.5f * (pax + s * d1.x + pbx + t * d2.x);
    final float my = 0.5f * (pay + s * d1.y + pby + t * d2.y);
    final float mz = 0.5f * (paz + s * d1.z + pbz + t * d2.z);
    out.single(mx, my, mz, out.depth);
  }

  private static float radius(Box box, Vector3f axis) {
    return box.half()[0] * Math.abs(box.axes()[0].dot(axis)) + box.half()[1] * Math.abs(box.axes()[1].dot(axis))
        + box.half()[2] * Math.abs(box.axes()[2].dot(axis));
  }
}
