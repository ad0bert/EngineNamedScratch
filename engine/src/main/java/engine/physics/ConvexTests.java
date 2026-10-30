package engine.physics;

import org.joml.Vector3f;

import engine.physics.Narrowphase.Box;
import engine.physics.Narrowphase.Contact;

/**
 * Contacts between any two convex shapes given by support functions (cylinders and convex hulls against everything
 * else): GJK finds the distance between the shapes' cores (spheres and capsules are a point or a segment grown by a
 * radius, so shallow contacts with them never need more), EPA the penetration when the cores overlap. The manifold
 * is then built like the box-box one: the surface features of both shapes facing each other (a face polygon, an edge
 * or a point) are clipped against each other, and the points of the incident feature behind the reference feature
 * become the contact points. Same conventions as {@link Narrowphase}; nothing is allocated.
 */
final class ConvexTests {
  private ConvexTests() {
  }

  /** A convex shape: a core given by its support function, grown by {@link #radius()}. */
  interface Shape {
    /** The point of the core farthest in direction {@code d} (not necessarily normalized). */
    void support(Vector3f d, Vector3f dest);

    /** Radius around the core (spheres and capsules); 0 for shapes with corners. */
    float radius();

    void center(Vector3f dest);

    /** The surface feature facing direction {@code d} (normalized): a face polygon, an edge or a point. */
    void feature(Vector3f d, Feature dest);
  }

  /** Up to {@link #CAP} surface points; for a polygon (three or more) also its outward normal. */
  static final class Feature {
    static final int CAP = 64;
    final float[] xyz = new float[CAP * 3];
    int count;
    final Vector3f normal = new Vector3f();

    void add(float x, float y, float z) {
      if (this.count < CAP) {
        this.xyz[this.count * 3] = x;
        this.xyz[this.count * 3 + 1] = y;
        this.xyz[this.count * 3 + 2] = z;
        this.count++;
      }
    }

    void add(Vector3f v) {
      add(v.x, v.y, v.z);
    }
  }

  // ---- shape adapters ------------------------------------------------------------------------------------------

  static final class BoxShape implements Shape {
    Box box;

    @Override
    public void support(Vector3f d, Vector3f dest) {
      dest.set(this.box.c());
      for (int i = 0; i < 3; i++) {
        final var axis = this.box.axes()[i];
        dest.fma(axis.dot(d) >= 0f ? this.box.half()[i] : -this.box.half()[i], axis);
      }
    }

    @Override
    public float radius() {
      return 0f;
    }

    @Override
    public void center(Vector3f dest) {
      dest.set(this.box.c());
    }

    @Override
    public void feature(Vector3f d, Feature dest) {
      int k = 0;
      float best = -1f;
      for (int i = 0; i < 3; i++) {
        final float dot = Math.abs(this.box.axes()[i].dot(d));
        if (dot > best) {
          best = dot;
          k = i;
        }
      }
      final var ak = this.box.axes()[k];
      final float sign = ak.dot(d) >= 0f ? 1f : -1f;
      final var u = this.box.axes()[(k + 1) % 3];
      final var v = this.box.axes()[(k + 2) % 3];
      final float hu = this.box.half()[(k + 1) % 3];
      final float hv = this.box.half()[(k + 2) % 3];
      final var c = this.box.c();
      final float h = sign * this.box.half()[k];
      dest.count = 0;
      for (int i = 0; i < 4; i++) {
        final float su = i == 0 || i == 3 ? hu : -hu;
        final float sv = i < 2 ? hv : -hv;
        dest.add(c.x + h * ak.x + su * u.x + sv * v.x, c.y + h * ak.y + su * u.y + sv * v.y,
            c.z + h * ak.z + su * u.z + sv * v.z);
      }
      dest.normal.set(ak).mul(sign);
    }
  }

  /** A sphere: a point grown by a radius. */
  static final class BallShape implements Shape {
    final Vector3f c = new Vector3f();
    float r;

    @Override
    public void support(Vector3f d, Vector3f dest) {
      dest.set(this.c);
    }

    @Override
    public float radius() {
      return this.r;
    }

    @Override
    public void center(Vector3f dest) {
      dest.set(this.c);
    }

    @Override
    public void feature(Vector3f d, Feature dest) {
      dest.count = 0;
      dest.add(this.c.x + this.r * d.x, this.c.y + this.r * d.y, this.c.z + this.r * d.z);
    }
  }

  /** A capsule: a segment grown by a radius. */
  static final class SegmentShape implements Shape {
    final Vector3f a = new Vector3f();
    final Vector3f b = new Vector3f();
    float r;

    @Override
    public void support(Vector3f d, Vector3f dest) {
      dest.set(this.a.dot(d) >= this.b.dot(d) ? this.a : this.b);
    }

    @Override
    public float radius() {
      return this.r;
    }

    @Override
    public void center(Vector3f dest) {
      dest.set(this.a).add(this.b).mul(0.5f);
    }

    /** Both ends pushed out by the radius: exact for the end touching, the other one is clipped away if far. */
    @Override
    public void feature(Vector3f d, Feature dest) {
      dest.count = 0;
      dest.add(this.a.x + this.r * d.x, this.a.y + this.r * d.y, this.a.z + this.r * d.z);
      if (this.a.distanceSquared(this.b) > 1e-10f) {
        dest.add(this.b.x + this.r * d.x, this.b.y + this.r * d.y, this.b.z + this.r * d.z);
      }
    }
  }

  /** One triangle of a mesh; {@link #n} is its front normal. */
  static final class TriangleShape implements Shape {
    final Vector3f v0 = new Vector3f();
    final Vector3f v1 = new Vector3f();
    final Vector3f v2 = new Vector3f();
    final Vector3f n = new Vector3f();

    @Override
    public void support(Vector3f d, Vector3f dest) {
      final float d0 = this.v0.dot(d);
      final float d1 = this.v1.dot(d);
      final float d2 = this.v2.dot(d);
      dest.set(d0 >= d1 && d0 >= d2 ? this.v0 : d1 >= d2 ? this.v1 : this.v2);
    }

    @Override
    public float radius() {
      return 0f;
    }

    @Override
    public void center(Vector3f dest) {
      dest.set(this.v0).add(this.v1).add(this.v2).div(3f);
    }

    @Override
    public void feature(Vector3f d, Feature dest) {
      dest.count = 0;
      dest.add(this.v0);
      dest.add(this.v1);
      dest.add(this.v2);
      dest.normal.set(this.n);
      if (this.n.dot(d) < 0f) {
        dest.normal.negate();
      }
    }
  }

  // ---- scratch -----------------------------------------------------------------------------------------------

  private static final int MAX_VERTICES = 64;
  private static final int MAX_FACES = 256;
  private static final int MAX_EDGES = 128;
  private static final int CLIP_CAP = 2 * Feature.CAP + 16;

  private static final class Scratch {
    // GJK simplex: w = pa - pb
    final Vector3f[] w = vectors(4);
    final Vector3f[] pa = vectors(4);
    final Vector3f[] pb = vectors(4);
    final float[] lambda = new float[4];
    int n;
    final Vector3f v = new Vector3f();
    final Vector3f dir = new Vector3f();
    final Vector3f ca = new Vector3f();
    final Vector3f cb = new Vector3f();
    final Vector3f t0 = new Vector3f();
    final Vector3f t1 = new Vector3f();
    final Vector3f t2 = new Vector3f();
    final Vector3f t3 = new Vector3f();
    final float[] bary = new float[3];
    final float[] bestBary = new float[3];
    final int[] tri = new int[3];
    // EPA polytope
    final Vector3f[] ew = vectors(MAX_VERTICES);
    final Vector3f[] ea = vectors(MAX_VERTICES);
    final Vector3f[] eb = vectors(MAX_VERTICES);
    int ev;
    final int[] faces = new int[MAX_FACES * 3];
    final float[] faceNormals = new float[MAX_FACES * 3];
    final float[] faceDist = new float[MAX_FACES];
    final boolean[] alive = new boolean[MAX_FACES];
    int ef;
    final int[] edges = new int[MAX_EDGES * 2];
    int edgeCount;
    final Vector3f normal = new Vector3f();
    // manifold
    final Feature fa = new Feature();
    final Feature fb = new Feature();
    float[] polyA = new float[CLIP_CAP * 3];
    float[] polyB = new float[CLIP_CAP * 3];
    final float[] candPos = new float[CLIP_CAP * 3];
    final float[] candDepth = new float[CLIP_CAP];
    final boolean[] taken = new boolean[CLIP_CAP];
    final Vector3f nr = new Vector3f();
    final Vector3f plane = new Vector3f();
    final Vector3f centroid = new Vector3f();
    // adapters for colliders without their own shape
    final BoxShape boxA = new BoxShape();
    final BoxShape boxB = new BoxShape();
    final BallShape ballA = new BallShape();
    final BallShape ballB = new BallShape();
    final SegmentShape segmentA = new SegmentShape();
    final SegmentShape segmentB = new SegmentShape();
    final TriangleShape triangle = new TriangleShape();
  }

  private static Vector3f[] vectors(int n) {
    final var v = new Vector3f[n];
    for (int i = 0; i < n; i++) {
      v[i] = new Vector3f();
    }
    return v;
  }

  private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

  /**
   * The convex shape of a collider (its cached world shape), or null for a mesh. {@code second} picks the second set
   * of adapters, so both sides of a pair can be boxes, spheres or capsules.
   */
  static Shape shapeOf(Collider c, boolean second) {
    final var sc = SCRATCH.get();
    if (c instanceof CylinderCollider cyl) {
      return cyl.cachedShape();
    }
    if (c instanceof ConvexHullCollider hull) {
      return hull.cachedShape();
    }
    if (c instanceof BoxCollider b) {
      final var s = second ? sc.boxB : sc.boxA;
      s.box = b.cachedBox();
      return s;
    }
    if (c instanceof SphereCollider sp) {
      final var s = second ? sc.ballB : sc.ballA;
      s.c.set(sp.cachedSphere().c());
      s.r = sp.cachedSphere().r;
      return s;
    }
    if (c instanceof CapsuleCollider cap) {
      final var s = second ? sc.segmentB : sc.segmentA;
      s.a.set(cap.cachedCapsule().a);
      s.b.set(cap.cachedCapsule().b);
      s.r = cap.cachedCapsule().r;
      return s;
    }
    return null;
  }

  static BallShape ball(Vector3f c, float r) {
    final var s = SCRATCH.get().ballA;
    s.c.set(c);
    s.r = r;
    return s;
  }

  // ---- contact -----------------------------------------------------------------------------------------------

  /** Contact between two convex shapes; normal from A towards B. */
  static boolean collide(Shape a, Shape b, Contact out, float margin) {
    final var sc = SCRATCH.get();
    final float rSum = a.radius() + b.radius();
    final float dist = gjk(a, b, sc);
    final var n = sc.normal;
    float depth;
    if (dist > 1e-5f) {
      if (dist > rSum + margin) {
        return false;
      }
      n.set(sc.cb).sub(sc.ca).div(dist);
      depth = rSum - dist;
      // the surfaces: halfway between them is the fallback contact point
      sc.ca.fma(a.radius(), n);
      sc.cb.fma(-b.radius(), n);
    } else {
      depth = epa(a, b, sc);
      if (depth < 0f) { // degenerate: separate along the centres, by the overlap of the shapes along that axis
        a.center(sc.t0);
        b.center(sc.t1);
        n.set(sc.t1).sub(sc.t0);
        if (n.lengthSquared() < 1e-12f) {
          n.set(0f, 1f, 0f);
        }
        n.normalize();
        fullSupport(a, n, sc.ca);
        fullSupport(b, sc.t2.set(n).negate(), sc.cb);
        depth = sc.ca.dot(n) - sc.cb.dot(n);
      }
    }
    out.normal.set(n);
    out.depth = depth;
    manifold(a, b, out, margin, sc);
    return true;
  }

  /**
   * Contact of a convex shape (A) with triangle t of a mesh (B). A one-sided triangle ignores shapes entirely behind
   * it and pushes those that poke through out of its front; near-ties go to the triangle's normal, so edges inside
   * a flat mesh do not make things bump.
   */
  static boolean triangle(Shape a, MeshCollider m, int t, Contact out, float margin) {
    final var sc = SCRATCH.get();
    final var tri = sc.triangle;
    m.vertex(t, 0, tri.v0);
    m.vertex(t, 1, tri.v1);
    m.vertex(t, 2, tri.v2);
    m.normal(t, tri.n);
    final float plane = tri.n.dot(tri.v0);
    // how far A reaches below and above the triangle's plane
    fullSupport(a, sc.t3.set(tri.n).negate(), sc.t0);
    final float low = sc.t0.dot(tri.n) - plane;
    fullSupport(a, tri.n, sc.t0);
    final float high = sc.t0.dot(tri.n) - plane;
    if (!m.twoSided && high < 0f) {
      return false; // entirely behind
    }
    a.center(sc.t0);
    final boolean behind = sc.t0.dot(tri.n) < plane;
    if (!m.twoSided && behind) {
      if (!collide(a, tri, out, margin)) {
        return false; // behind the plane, but beside the triangle
      }
      out.normal.set(tri.n).negate(); // out of the front, however deep
      out.depth = -low;
      manifold(a, tri, out, margin, sc);
      return true;
    }
    if (!collide(a, tri, out, margin)) {
      return false;
    }
    // the triangle's face as the separating direction: how far A must move to clear the plane
    final float faceDepth = behind ? high : -low;
    if (faceDepth <= out.depth + 0.05f * Math.abs(out.depth) + 2e-3f) {
      out.normal.set(tri.n);
      if (!behind) {
        out.normal.negate();
      }
      out.depth = faceDepth;
      manifold(a, tri, out, margin, sc);
    }
    return true;
  }

  static void fullSupport(Shape s, Vector3f d, Vector3f dest) {
    s.support(d, dest);
    final float r = s.radius();
    if (r > 0f) {
      final float len = d.length();
      if (len > 1e-12f) {
        dest.fma(r / len, d);
      }
    }
  }

  // ---- GJK ---------------------------------------------------------------------------------------------------

  /** Support point of the cores' Minkowski difference A - B in direction d, into simplex slot i. */
  private static void coreSupport(Shape a, Shape b, Vector3f d, int i, Scratch sc) {
    a.support(d, sc.pa[i]);
    b.support(sc.t3.set(d).negate(), sc.pb[i]);
    sc.w[i].set(sc.pa[i]).sub(sc.pb[i]);
  }

  /**
   * Distance between the cores of A and B (closest points in {@code ca}/{@code cb}), or 0 when they overlap (the
   * simplex is then left for EPA).
   */
  private static float gjk(Shape a, Shape b, Scratch sc) {
    a.center(sc.t0);
    b.center(sc.t1);
    final var d = sc.dir.set(sc.t1).sub(sc.t0);
    if (d.lengthSquared() < 1e-12f) {
      d.set(1f, 0f, 0f);
    }
    coreSupport(a, b, d.negate(), 0, sc); // towards B - A seen from A - B
    sc.n = 1;
    sc.lambda[0] = 1f;
    sc.v.set(sc.w[0]);
    float vv = sc.v.lengthSquared();
    for (int iteration = 0; iteration < 48; iteration++) {
      if (vv < 1e-12f) {
        return 0f;
      }
      coreSupport(a, b, sc.dir.set(sc.v).negate(), sc.n, sc);
      final var w = sc.w[sc.n];
      if (vv - sc.v.dot(w) <= 1e-6f * vv + 1e-12f) {
        break; // no point of A - B is closer to the origin than v
      }
      boolean duplicate = false;
      for (int i = 0; i < sc.n; i++) {
        duplicate |= sc.w[i].distanceSquared(w) < 1e-14f;
      }
      if (duplicate) {
        break;
      }
      sc.n++;
      if (!closest(sc)) {
        return 0f; // the origin is inside the tetrahedron
      }
      sc.v.zero();
      for (int i = 0; i < sc.n; i++) {
        sc.v.fma(sc.lambda[i], sc.w[i]);
      }
      final float next = sc.v.lengthSquared();
      if (next >= vv * (1f - 1e-7f)) {
        vv = next;
        break; // no progress any more
      }
      vv = next;
    }
    sc.ca.zero();
    sc.cb.zero();
    for (int i = 0; i < sc.n; i++) {
      sc.ca.fma(sc.lambda[i], sc.pa[i]);
      sc.cb.fma(sc.lambda[i], sc.pb[i]);
    }
    return (float) Math.sqrt(vv);
  }

  /**
   * Reduces the simplex to the smallest sub-simplex containing its point closest to the origin and sets the weights;
   * false if a tetrahedron contains the origin.
   */
  private static boolean closest(Scratch sc) {
    switch (sc.n) {
      case 2 -> {
        final var ab = sc.t0.set(sc.w[1]).sub(sc.w[0]);
        final float len2 = ab.lengthSquared();
        final float t = len2 > 1e-14f ? -sc.w[0].dot(ab) / len2 : 0f;
        if (t <= 0f) {
          keep(sc, 0, -1, 1f, 0f);
        } else if (t >= 1f) {
          keep(sc, 1, -1, 1f, 0f);
        } else {
          keep(sc, 0, 1, 1f - t, t);
        }
        return true;
      }
      case 3 -> {
        triangleWeights(sc.w[0], sc.w[1], sc.w[2], sc);
        keepTriangle(sc, 0, 1, 2);
        return true;
      }
      case 4 -> {
        // faces opposite each vertex; the origin is outside a face if it is on the other side from that vertex
        float best = Float.MAX_VALUE;
        int bestFace = -1;
        for (int f = 0; f < 4; f++) {
          final int i0 = f == 0 ? 1 : 0;
          final int i1 = f <= 1 ? 2 : 1;
          final int i2 = f <= 2 ? 3 : 2;
          final var p0 = sc.w[i0];
          final var nrm = sc.t1.set(sc.w[i1]).sub(p0).cross(sc.t2.set(sc.w[i2]).sub(p0));
          final float side = nrm.dot(sc.t2.set(sc.w[f]).sub(p0));
          final float origin = -nrm.dot(p0);
          if (Math.abs(side) > 1e-12f && side * origin >= 0f) {
            continue; // the origin is on the same side as the opposite vertex
          }
          triangleWeights(p0, sc.w[i1], sc.w[i2], sc);
          sc.t1.zero().fma(sc.bary[0], p0).fma(sc.bary[1], sc.w[i1]).fma(sc.bary[2], sc.w[i2]);
          final float dist = sc.t1.lengthSquared();
          if (dist < best) {
            best = dist;
            bestFace = f;
            System.arraycopy(sc.bary, 0, sc.bestBary, 0, 3);
            sc.tri[0] = i0;
            sc.tri[1] = i1;
            sc.tri[2] = i2;
          }
        }
        if (bestFace < 0) {
          return false;
        }
        System.arraycopy(sc.bestBary, 0, sc.bary, 0, 3);
        keepTriangle(sc, sc.tri[0], sc.tri[1], sc.tri[2]);
        return true;
      }
      default -> {
        sc.lambda[0] = 1f;
        return true;
      }
    }
  }

  /** Keeps the vertices of triangle (i0, i1, i2) (ascending) that have a weight in {@code bary}. */
  private static void keepTriangle(Scratch sc, int i0, int i1, int i2) {
    int slot = 0;
    slot = keepVertex(sc, i0, sc.bary[0], slot);
    slot = keepVertex(sc, i1, sc.bary[1], slot);
    slot = keepVertex(sc, i2, sc.bary[2], slot);
    if (slot == 0) {
      keepVertex(sc, i0, 1f, 0);
      slot = 1;
    }
    sc.n = slot;
  }

  /** Keeps vertices a and (if not -1) b with the given weights. */
  private static void keep(Scratch sc, int a, int b, float la, float lb) {
    int slot = keepVertex(sc, a, la, 0);
    if (b >= 0) {
      slot = keepVertex(sc, b, lb, slot);
    }
    sc.n = slot;
  }

  /** Moves simplex vertex i down into {@code slot} if it has a weight; returns the next free slot. */
  private static int keepVertex(Scratch sc, int i, float weight, int slot) {
    if (weight <= 0f) {
      return slot;
    }
    if (i != slot) {
      sc.w[slot].set(sc.w[i]);
      sc.pa[slot].set(sc.pa[i]);
      sc.pb[slot].set(sc.pb[i]);
    }
    sc.lambda[slot] = weight;
    return slot + 1;
  }

  /** Barycentric weights of the point of triangle abc closest to the origin (Ericson 5.1.5), into {@code bary}. */
  private static void triangleWeights(Vector3f a, Vector3f b, Vector3f c, Scratch sc) {
    final float[] o = sc.bary;
    final var ab = sc.t1.set(b).sub(a);
    final var ac = sc.t2.set(c).sub(a);
    final float d1 = -ab.dot(a);
    final float d2 = -ac.dot(a);
    if (d1 <= 0f && d2 <= 0f) {
      set(o, 1f, 0f, 0f);
      return;
    }
    final float d3 = -ab.dot(b);
    final float d4 = -ac.dot(b);
    if (d3 >= 0f && d4 <= d3) {
      set(o, 0f, 1f, 0f);
      return;
    }
    final float vc = d1 * d4 - d3 * d2;
    if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
      final float v = d1 / (d1 - d3);
      set(o, 1f - v, v, 0f);
      return;
    }
    final float d5 = -ab.dot(c);
    final float d6 = -ac.dot(c);
    if (d6 >= 0f && d5 <= d6) {
      set(o, 0f, 0f, 1f);
      return;
    }
    final float vb = d5 * d2 - d1 * d6;
    if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
      final float w = d2 / (d2 - d6);
      set(o, 1f - w, 0f, w);
      return;
    }
    final float va = d3 * d6 - d5 * d4;
    if (va <= 0f && d4 - d3 >= 0f && d5 - d6 >= 0f) {
      final float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
      set(o, 0f, 1f - w, w);
      return;
    }
    final float sum = va + vb + vc;
    if (!(sum > 1e-20f)) { // degenerate (flat) triangle: its nearest vertex
      final float la = a.lengthSquared();
      final float lb = b.lengthSquared();
      final float lc = c.lengthSquared();
      set(o, la <= lb && la <= lc ? 1f : 0f, lb < la && lb <= lc ? 1f : 0f, lc < la && lc < lb ? 1f : 0f);
      return;
    }
    final float v = vb / sum;
    final float w = vc / sum;
    set(o, 1f - v - w, v, w);
  }

  private static void set(float[] o, float a, float b, float c) {
    o[0] = a;
    o[1] = b;
    o[2] = c;
  }

  // ---- EPA ---------------------------------------------------------------------------------------------------

  /** The faces of a tetrahedron, each with the vertex opposite it. */
  private static final int[][] TETRAHEDRON = { { 0, 1, 2, 3 }, { 0, 3, 1, 2 }, { 0, 2, 3, 1 }, { 1, 3, 2, 0 } };

  private static void epaVertex(Shape a, Shape b, Vector3f d, Scratch sc) {
    final int i = sc.ev++;
    fullSupport(a, d, sc.ea[i]);
    fullSupport(b, sc.t3.set(d).negate(), sc.eb[i]);
    sc.ew[i].set(sc.ea[i]).sub(sc.eb[i]);
  }

  private static void addFace(int i, int j, int k, Scratch sc) {
    if (sc.ef >= MAX_FACES) {
      compactFaces(sc);
      if (sc.ef >= MAX_FACES) {
        return;
      }
    }
    final int f = sc.ef++;
    sc.faces[f * 3] = i;
    sc.faces[f * 3 + 1] = j;
    sc.faces[f * 3 + 2] = k;
    final var n = sc.t1.set(sc.ew[j]).sub(sc.ew[i]).cross(sc.t2.set(sc.ew[k]).sub(sc.ew[i]));
    final float len = n.length();
    sc.alive[f] = true;
    if (len < 1e-12f) {
      sc.faceNormals[f * 3] = 0f;
      sc.faceNormals[f * 3 + 1] = 0f;
      sc.faceNormals[f * 3 + 2] = 0f;
      sc.faceDist[f] = Float.MAX_VALUE; // never the closest, removed when a neighbour is
      return;
    }
    n.div(len);
    sc.faceNormals[f * 3] = n.x;
    sc.faceNormals[f * 3 + 1] = n.y;
    sc.faceNormals[f * 3 + 2] = n.z;
    sc.faceDist[f] = n.dot(sc.ew[i]);
  }

  private static void compactFaces(Scratch sc) {
    int m = 0;
    for (int f = 0; f < sc.ef; f++) {
      if (!sc.alive[f]) {
        continue;
      }
      System.arraycopy(sc.faces, f * 3, sc.faces, m * 3, 3);
      System.arraycopy(sc.faceNormals, f * 3, sc.faceNormals, m * 3, 3);
      sc.faceDist[m] = sc.faceDist[f];
      sc.alive[m] = true;
      m++;
    }
    sc.ef = m;
  }

  private static void addEdge(int i, int j, Scratch sc) {
    for (int e = 0; e < sc.edgeCount; e++) {
      if (sc.edges[e * 2] == j && sc.edges[e * 2 + 1] == i) { // shared with another removed face: not horizon
        sc.edgeCount--;
        sc.edges[e * 2] = sc.edges[sc.edgeCount * 2];
        sc.edges[e * 2 + 1] = sc.edges[sc.edgeCount * 2 + 1];
        return;
      }
    }
    if (sc.edgeCount < MAX_EDGES) {
      sc.edges[sc.edgeCount * 2] = i;
      sc.edges[sc.edgeCount * 2 + 1] = j;
      sc.edgeCount++;
    }
  }

  /**
   * Penetration depth from the overlapping simplex GJK left: the normal (from A towards B) in {@code normal}, the
   * deepest points of A and B in {@code ca}/{@code cb}; -1 if the polytope is degenerate.
   */
  private static float epa(Shape a, Shape b, Scratch sc) {
    sc.ev = 0;
    for (int i = 0; i < sc.n; i++) {
      sc.ea[i].set(sc.pa[i]);
      sc.eb[i].set(sc.pb[i]);
      sc.ew[i].set(sc.w[i]);
    }
    sc.ev = sc.n;
    if (!blowUp(a, b, sc)) {
      return -1f;
    }
    sc.ef = 0;
    // a tetrahedron with outward faces
    for (final int[] f : TETRAHEDRON) {
      final var n = sc.t0.set(sc.ew[f[1]]).sub(sc.ew[f[0]]).cross(sc.t1.set(sc.ew[f[2]]).sub(sc.ew[f[0]]));
      if (n.dot(sc.t1.set(sc.ew[f[3]]).sub(sc.ew[f[0]])) > 0f) {
        addFace(f[0], f[2], f[1], sc);
      } else {
        addFace(f[0], f[1], f[2], sc);
      }
    }
    int best = -1;
    for (int iteration = 0; iteration < 64; iteration++) {
      best = -1;
      float bestDist = Float.MAX_VALUE;
      for (int f = 0; f < sc.ef; f++) {
        if (sc.alive[f] && sc.faceDist[f] < bestDist) {
          bestDist = sc.faceDist[f];
          best = f;
        }
      }
      if (best < 0 || bestDist == Float.MAX_VALUE) {
        return -1f;
      }
      final var n = sc.normal.set(sc.faceNormals[best * 3], sc.faceNormals[best * 3 + 1],
          sc.faceNormals[best * 3 + 2]);
      if (sc.ev >= MAX_VERTICES) {
        break;
      }
      epaVertex(a, b, n, sc);
      final int nv = sc.ev - 1;
      final var w = sc.ew[nv];
      if (w.dot(n) - bestDist < 1e-4f + 1e-4f * Math.abs(bestDist)) {
        sc.ev--;
        break; // the closest face is on the surface of A - B
      }
      sc.edgeCount = 0;
      for (int f = 0; f < sc.ef; f++) {
        if (!sc.alive[f]) {
          continue;
        }
        final int i0 = sc.faces[f * 3];
        final float side = sc.faceNormals[f * 3] * (w.x - sc.ew[i0].x)
            + sc.faceNormals[f * 3 + 1] * (w.y - sc.ew[i0].y) + sc.faceNormals[f * 3 + 2] * (w.z - sc.ew[i0].z);
        if (side > 1e-7f) {
          sc.alive[f] = false;
          addEdge(sc.faces[f * 3], sc.faces[f * 3 + 1], sc);
          addEdge(sc.faces[f * 3 + 1], sc.faces[f * 3 + 2], sc);
          addEdge(sc.faces[f * 3 + 2], sc.faces[f * 3], sc);
        }
      }
      for (int e = 0; e < sc.edgeCount; e++) {
        addFace(sc.edges[e * 2], sc.edges[e * 2 + 1], nv, sc);
      }
    }
    if (best < 0) {
      return -1f;
    }
    final float depth = Math.max(0f, sc.faceDist[best]);
    final var n = sc.normal.set(sc.faceNormals[best * 3], sc.faceNormals[best * 3 + 1],
        sc.faceNormals[best * 3 + 2]);
    // barycentric weights of the origin's projection on that face give the deepest points
    final int i0 = sc.faces[best * 3];
    final int i1 = sc.faces[best * 3 + 1];
    final int i2 = sc.faces[best * 3 + 2];
    final var p = sc.t0.set(n).mul(sc.faceDist[best]);
    final var e0 = sc.t1.set(sc.ew[i1]).sub(sc.ew[i0]);
    final var e1 = sc.t2.set(sc.ew[i2]).sub(sc.ew[i0]);
    final var e2 = sc.t3.set(p).sub(sc.ew[i0]);
    final float d00 = e0.dot(e0);
    final float d01 = e0.dot(e1);
    final float d11 = e1.dot(e1);
    final float d20 = e2.dot(e0);
    final float d21 = e2.dot(e1);
    final float denom = d00 * d11 - d01 * d01;
    float v = 1f / 3f;
    float u = 1f / 3f;
    if (Math.abs(denom) > 1e-20f) {
      v = (d11 * d20 - d01 * d21) / denom;
      u = (d00 * d21 - d01 * d20) / denom;
    }
    final float s = 1f - v - u;
    sc.ca.zero().fma(s, sc.ea[i0]).fma(v, sc.ea[i1]).fma(u, sc.ea[i2]);
    sc.cb.zero().fma(s, sc.eb[i0]).fma(v, sc.eb[i1]).fma(u, sc.eb[i2]);
    return depth;
  }

  /** Grows the GJK simplex (one to three points) into a tetrahedron of support points. */
  private static boolean blowUp(Shape a, Shape b, Scratch sc) {
    if (sc.ev == 1) {
      for (int i = 0; i < 6 && sc.ev == 1; i++) {
        sc.dir.set(i / 2 == 0 ? 1f : 0f, i / 2 == 1 ? 1f : 0f, i / 2 == 2 ? 1f : 0f).mul(i % 2 == 0 ? 1f : -1f);
        epaVertex(a, b, sc.dir, sc);
        if (sc.ew[1].distanceSquared(sc.ew[0]) < 1e-12f) {
          sc.ev--;
        }
      }
    }
    if (sc.ev == 2) {
      final var line = sc.t0.set(sc.ew[1]).sub(sc.ew[0]).normalize();
      final var e1 = HingeJoint.perpendicular(line, sc.normal);
      for (int i = 0; i < 4 && sc.ev == 2; i++) {
        if (i == 0) {
          sc.dir.set(e1);
        } else if (i == 1) {
          sc.dir.set(e1).negate();
        } else {
          line.cross(e1, sc.dir);
          if (i == 3) {
            sc.dir.negate();
          }
        }
        epaVertex(a, b, sc.dir, sc);
        final var off = sc.t1.set(sc.ew[2]).sub(sc.ew[0]);
        if (off.sub(sc.t2.set(line).mul(off.dot(line))).lengthSquared() < 1e-12f) {
          sc.ev--;
        }
      }
    }
    if (sc.ev == 3) {
      final var n = sc.normal.set(sc.ew[1]).sub(sc.ew[0]).cross(sc.t0.set(sc.ew[2]).sub(sc.ew[0]));
      if (n.lengthSquared() < 1e-20f) {
        return false;
      }
      n.normalize();
      for (int i = 0; i < 2 && sc.ev == 3; i++) {
        sc.dir.set(n).mul(i == 0 ? 1f : -1f);
        epaVertex(a, b, sc.dir, sc);
        if (Math.abs(sc.t1.set(sc.ew[3]).sub(sc.ew[0]).dot(n)) < 1e-6f) {
          sc.ev--;
        }
      }
    }
    return sc.ev == 4;
  }

  // ---- manifold ----------------------------------------------------------------------------------------------

  /**
   * Builds the contact points for the normal and depth in {@code out}: the feature of the shape that faces the
   * other one best is the reference, the other one's feature is clipped to its sides, and the clipped points within
   * the margin behind the reference become the manifold. A reference face almost square to the normal takes over its
   * direction exactly, so a flat face resting on another does not rock.
   */
  private static void manifold(Shape a, Shape b, Contact out, float margin, Scratch sc) {
    final var n = out.normal;
    a.feature(n, sc.fa);
    b.feature(sc.t0.set(n).negate(), sc.fb);
    final var fa = sc.fa;
    final var fb = sc.fb;
    boolean refIsA;
    if (fa.count >= 3 && fb.count >= 3) {
      refIsA = fa.normal.dot(n) >= -fb.normal.dot(n) - 1e-4f;
    } else {
      refIsA = fa.count >= fb.count;
    }
    final var ref = refIsA ? fa : fb;
    final var inc = refIsA ? fb : fa;
    final var nr = sc.nr.set(n);
    if (!refIsA) {
      nr.negate(); // from the reference towards the incident shape
    }
    final boolean snapped = ref.count >= 3 && ref.normal.dot(nr) > 0.999f;
    if (snapped) {
      nr.set(ref.normal);
      n.set(nr);
      if (!refIsA) {
        n.negate();
      }
    }
    if (ref.count == 1) { // two rounded shapes or points: the closest points found above
      out.single(0.5f * (sc.ca.x + sc.cb.x), 0.5f * (sc.ca.y + sc.cb.y), 0.5f * (sc.ca.z + sc.cb.z), out.depth);
      return;
    }
    float offset = -Float.MAX_VALUE;
    for (int i = 0; i < ref.count; i++) {
      offset = Math.max(offset, nr.x * ref.xyz[i * 3] + nr.y * ref.xyz[i * 3 + 1] + nr.z * ref.xyz[i * 3 + 2]);
    }
    float[] poly = sc.polyA;
    float[] other = sc.polyB;
    System.arraycopy(inc.xyz, 0, poly, 0, inc.count * 3);
    int count = inc.count;
    if (ref.count == 2) { // an edge: clip to the planes through its ends
      final var e = sc.plane.set(ref.xyz[3] - ref.xyz[0], ref.xyz[4] - ref.xyz[1], ref.xyz[5] - ref.xyz[2]);
      final float len = e.length();
      if (len > 1e-6f) {
        e.div(len);
        count = clip(poly, count, other, e, e.x * ref.xyz[3] + e.y * ref.xyz[4] + e.z * ref.xyz[5]);
        float[] swap = poly;
        poly = other;
        other = swap;
        e.negate();
        count = clip(poly, count, other, e, e.x * ref.xyz[0] + e.y * ref.xyz[1] + e.z * ref.xyz[2]);
        swap = poly;
        poly = other;
        other = swap;
      }
    } else {
      final var c = sc.centroid.zero();
      for (int i = 0; i < ref.count; i++) {
        c.add(ref.xyz[i * 3], ref.xyz[i * 3 + 1], ref.xyz[i * 3 + 2]);
      }
      c.div(ref.count);
      for (int i = 0; i < ref.count && count > 0; i++) {
        final int j = (i + 1) % ref.count;
        final float x0 = ref.xyz[i * 3];
        final float y0 = ref.xyz[i * 3 + 1];
        final float z0 = ref.xyz[i * 3 + 2];
        // side plane through the edge, parallel to the normal, facing away from the face's centre
        final var side = sc.t1.set(ref.xyz[j * 3] - x0, ref.xyz[j * 3 + 1] - y0, ref.xyz[j * 3 + 2] - z0).cross(nr);
        final float len = side.length();
        if (len < 1e-9f) {
          continue;
        }
        side.div(len);
        if (side.x * (c.x - x0) + side.y * (c.y - y0) + side.z * (c.z - z0) > 0f) {
          side.negate();
        }
        count = clip(poly, count, other, side, side.x * x0 + side.y * y0 + side.z * z0);
        final float[] swap = poly;
        poly = other;
        other = swap;
      }
    }
    int found = 0;
    float deepest = -Float.MAX_VALUE;
    for (int i = 0; i < count; i++) {
      final float s = nr.x * poly[i * 3] + nr.y * poly[i * 3 + 1] + nr.z * poly[i * 3 + 2] - offset;
      if (s <= margin && found < CLIP_CAP) {
        sc.candPos[found * 3] = poly[i * 3] - 0.5f * s * nr.x;
        sc.candPos[found * 3 + 1] = poly[i * 3 + 1] - 0.5f * s * nr.y;
        sc.candPos[found * 3 + 2] = poly[i * 3 + 2] - 0.5f * s * nr.z;
        sc.candDepth[found] = -s;
        deepest = Math.max(deepest, -s);
        found++;
      }
    }
    if (found == 0) {
      out.single(0.5f * (sc.ca.x + sc.cb.x), 0.5f * (sc.ca.y + sc.cb.y), 0.5f * (sc.ca.z + sc.cb.z), out.depth);
      return;
    }
    if (snapped) {
      out.depth = deepest; // measured on the face the normal now belongs to
    }
    Narrowphase.reduce(out, sc.candPos, sc.candDepth, sc.taken, nr, found);
  }

  /** Sutherland-Hodgman against the half space {@code plane . p <= limit}; returns the new count. */
  private static int clip(float[] in, int n, float[] out, Vector3f plane, float limit) {
    if (n == 1) {
      final float d = plane.x * in[0] + plane.y * in[1] + plane.z * in[2] - limit;
      if (d <= 0f) {
        System.arraycopy(in, 0, out, 0, 3);
        return 1;
      }
      return 0;
    }
    int m = 0;
    final int edges = n == 2 ? 1 : n; // a segment is not closed
    if (n == 2) {
      final float d0 = plane.x * in[0] + plane.y * in[1] + plane.z * in[2] - limit;
      final float d1 = plane.x * in[3] + plane.y * in[4] + plane.z * in[5] - limit;
      if (d0 <= 0f) {
        System.arraycopy(in, 0, out, m++ * 3, 3);
      }
      if ((d0 < 0f && d1 > 0f) || (d0 > 0f && d1 < 0f)) {
        final float f = d0 / (d0 - d1);
        out[m * 3] = in[0] + f * (in[3] - in[0]);
        out[m * 3 + 1] = in[1] + f * (in[4] - in[1]);
        out[m * 3 + 2] = in[2] + f * (in[5] - in[2]);
        m++;
      }
      if (d1 <= 0f) {
        System.arraycopy(in, 3, out, m++ * 3, 3);
      }
      return m;
    }
    for (int i = 0; i < edges; i++) {
      final int j = (i + 1) % n;
      final float di = plane.x * in[i * 3] + plane.y * in[i * 3 + 1] + plane.z * in[i * 3 + 2] - limit;
      final float dj = plane.x * in[j * 3] + plane.y * in[j * 3 + 1] + plane.z * in[j * 3 + 2] - limit;
      if (di <= 0f && m < CLIP_CAP) {
        System.arraycopy(in, i * 3, out, m * 3, 3);
        m++;
      }
      if (((di < 0f && dj > 0f) || (di > 0f && dj < 0f)) && m < CLIP_CAP) {
        final float f = di / (di - dj);
        out[m * 3] = in[i * 3] + f * (in[j * 3] - in[i * 3]);
        out[m * 3 + 1] = in[i * 3 + 1] + f * (in[j * 3 + 1] - in[i * 3 + 1]);
        out[m * 3 + 2] = in[i * 3 + 2] + f * (in[j * 3 + 2] - in[i * 3 + 2]);
        m++;
      }
    }
    return m;
  }

  /**
   * Gap between the surfaces of A and B (shape casts): fills {@code normal} (unit, from A towards B) and
   * {@code pointOnB} (the closest point of B's surface). Negative when the surfaces overlap but the cores do not;
   * {@code -Float.MAX_VALUE} when the cores overlap (then neither vector is set).
   */
  static float surfaceDistance(Shape a, Shape b, Vector3f normal, Vector3f pointOnB) {
    final var sc = SCRATCH.get();
    final float dist = gjk(a, b, sc);
    if (dist <= 1e-6f) {
      return -Float.MAX_VALUE;
    }
    normal.set(sc.cb).sub(sc.ca).div(dist);
    pointOnB.set(sc.cb).fma(-b.radius(), normal);
    return dist - a.radius() - b.radius();
  }

  /** True if the shape overlaps the ball (overlapSphere queries). */
  static boolean overlapsBall(Shape s, Vector3f c, float r) {
    final var sc = SCRATCH.get();
    final var ball = sc.ballB;
    ball.c.set(c);
    ball.r = r;
    final float dist = gjk(s, ball, sc);
    return dist <= s.radius() + r;
  }
}
