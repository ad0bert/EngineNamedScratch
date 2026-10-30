package engine.physics;

import org.joml.Vector3f;

import engine.spatial.SpatialGrid;

/**
 * Sweeps a sphere or capsule (a segment grown by a radius) along a straight line against colliders, by conservative
 * advancement on the GJK distance: the shape moves forward by the gap divided by its approach speed until the gap is
 * below {@link #TOLERANCE}. For a translating convex shape the gap is a convex function of the distance travelled, so
 * once the shape stops approaching it can never touch. Exact for every shape, corners included; meshes are swept
 * triangle by triangle. Not thread-safe: one instance per caller.
 */
final class ShapeCast {
  static final float TOLERANCE = 1e-4f;
  private static final int MAX_ITERATIONS = 32;

  final ConvexTests.SegmentShape moving = new ConvexTests.SegmentShape();
  private final ConvexTests.TriangleShape triangle = new ConvexTests.TriangleShape();
  private final Vector3f a0 = new Vector3f();
  private final Vector3f b0 = new Vector3f();
  private final Vector3f n = new Vector3f();
  private final Vector3f p = new Vector3f();
  private final SpatialGrid.IntList triangles = new SpatialGrid.IntList();
  /** Candidates of the current cast (filled and emptied by {@link PhysicsSystem#cast}). */
  final java.util.List<Collider> candidates = new java.util.ArrayList<>();

  // best hit so far
  float best;
  Collider bestCollider;
  final Vector3f bestNormal = new Vector3f();
  final Vector3f bestPoint = new Vector3f();

  /** Starts a cast of the capsule {@code a}-{@code b} with radius {@code r} (a sphere when a equals b). */
  void reset(Vector3f a, Vector3f b, float r, float maxDistance) {
    this.a0.set(a);
    this.b0.set(b);
    this.moving.r = r;
    this.best = maxDistance;
    this.bestCollider = null;
  }

  /** Tests one collider (its shape is refreshed first) and keeps it if it is hit before the best so far. */
  void test(Collider c, Vector3f dir) {
    c.refresh();
    if (c instanceof MeshCollider m) {
      testMesh(m, dir);
      return;
    }
    final var shape = ConvexTests.shapeOf(c, true);
    if (shape != null) {
      keep(c, cast(shape, dir, this.best));
    }
  }

  /** True if the shape (at the start of the cast) touches or overlaps the collider (its shape is refreshed first). */
  boolean overlaps(Collider c) {
    return penetration(c, this.push) >= 0f;
  }

  private final Vector3f push = new Vector3f();
  private final Narrowphase.Contact contact = new Narrowphase.Contact();

  /**
   * How deep the shape (at the start of the cast) is inside the collider, with the unit direction to push it out in
   * {@code out}; negative when apart (the gap). A shape whose core is inside a convex collider is pushed out along
   * the line from the collider's centre. One-sided triangles push to their front only.
   */
  float penetration(Collider c, Vector3f out) {
    c.refresh();
    this.moving.a.set(this.a0);
    this.moving.b.set(this.b0);
    if (!(c instanceof MeshCollider m)) {
      final var shape = ConvexTests.shapeOf(c, true);
      if (shape == null) {
        return -Float.MAX_VALUE;
      }
      final float gap = ConvexTests.surfaceDistance(this.moving, shape, this.n, this.p);
      if (gap != -Float.MAX_VALUE) {
        out.set(this.n).negate();
        return -gap;
      }
      // the cores overlap: the penetration from EPA
      if (!ConvexTests.collide(this.moving, shape, this.contact, 0f)) {
        return -Float.MAX_VALUE;
      }
      out.set(this.contact.normal()).negate();
      return this.contact.depth();
    }
    final float r = this.moving.r;
    this.triangles.clear();
    m.candidates(Math.min(this.a0.x, this.b0.x) - r, Math.min(this.a0.y, this.b0.y) - r,
        Math.min(this.a0.z, this.b0.z) - r, Math.max(this.a0.x, this.b0.x) + r, Math.max(this.a0.y, this.b0.y) + r,
        Math.max(this.a0.z, this.b0.z) + r, this.triangles);
    final var tri = this.triangle;
    float deepest = -Float.MAX_VALUE;
    for (int k = 0; k < this.triangles.size(); k++) {
      final int t = this.triangles.get(k);
      m.vertex(t, 0, tri.v0);
      m.vertex(t, 1, tri.v1);
      m.vertex(t, 2, tri.v2);
      m.normal(t, tri.n);
      float gap = ConvexTests.surfaceDistance(this.moving, tri, this.n, this.p);
      if (gap == -Float.MAX_VALUE) { // the segment pierces the triangle: out of its front by the lower end's depth
        this.n.set(tri.n).negate();
        final float low = Math.min(tri.n.dot(this.a0), tri.n.dot(this.b0)) - tri.n.dot(tri.v0);
        gap = low - r;
      } else if (!m.twoSided && this.n.dot(tri.n) > 1e-4f) {
        continue; // behind a one-sided triangle
      }
      if (-gap > deepest) {
        deepest = -gap;
        out.set(this.n).negate();
      }
    }
    return deepest;
  }

  private void testMesh(MeshCollider m, Vector3f dir) {
    // the box swept by the shape, against the mesh's bounding volume hierarchy
    final float r = this.moving.r + TOLERANCE;
    final float ex = dir.x * this.best;
    final float ey = dir.y * this.best;
    final float ez = dir.z * this.best;
    final float minX = Math.min(this.a0.x, this.b0.x) + Math.min(0f, ex) - r;
    final float minY = Math.min(this.a0.y, this.b0.y) + Math.min(0f, ey) - r;
    final float minZ = Math.min(this.a0.z, this.b0.z) + Math.min(0f, ez) - r;
    final float maxX = Math.max(this.a0.x, this.b0.x) + Math.max(0f, ex) + r;
    final float maxY = Math.max(this.a0.y, this.b0.y) + Math.max(0f, ey) + r;
    final float maxZ = Math.max(this.a0.z, this.b0.z) + Math.max(0f, ez) + r;
    this.triangles.clear();
    m.candidates(minX, minY, minZ, maxX, maxY, maxZ, this.triangles);
    final var tri = this.triangle;
    for (int k = 0; k < this.triangles.size(); k++) {
      final int t = this.triangles.get(k);
      m.normal(t, tri.n);
      if (!m.twoSided && tri.n.dot(dir) > 0f) {
        continue; // a one-sided triangle is only solid from the front
      }
      m.vertex(t, 0, tri.v0);
      m.vertex(t, 1, tri.v1);
      m.vertex(t, 2, tri.v2);
      keep(m, cast(tri, dir, this.best));
    }
  }

  private void keep(Collider c, float distance) {
    if (distance >= 0f && distance <= this.best) {
      this.best = distance;
      this.bestCollider = c;
      this.bestNormal.set(this.n).negate(); // from the surface towards the moving shape
      this.bestPoint.set(this.p);
    }
  }

  /**
   * Distance the moving shape travels along {@code dir} (normalized) before it touches {@code target}, or -1 if it
   * does not within {@code max}. Leaves the direction from the shape to the target in {@link #n} and the touching
   * point in {@link #p}. A shape that starts touching or overlapping counts as a hit at 0 if it moves into the
   * target, and as no hit if it moves away.
   */
  private float cast(ConvexTests.Shape target, Vector3f dir, float max) {
    float t = 0f;
    for (int it = 0; it < MAX_ITERATIONS; it++) {
      this.moving.a.set(this.a0).fma(t, dir);
      this.moving.b.set(this.b0).fma(t, dir);
      final float gap = ConvexTests.surfaceDistance(this.moving, target, this.n, this.p);
      if (gap == -Float.MAX_VALUE) { // deep inside: no direction to push out along
        if (t > 0f) {
          return t;
        }
        this.n.set(dir);
        this.moving.center(this.p);
        return 0f;
      }
      final float approach = dir.dot(this.n);
      if (gap <= TOLERANCE) {
        return t > 0f || approach > 0f ? t : -1f;
      }
      if (approach <= 1e-6f) {
        return -1f; // not getting closer: the gap only grows from here
      }
      t += gap / approach;
      if (t > max) {
        return -1f;
      }
    }
    return t;
  }
}
