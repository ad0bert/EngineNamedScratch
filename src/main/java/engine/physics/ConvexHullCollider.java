package engine.physics;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.render.MeshData;

/**
 * The convex hull of a point cloud (for example a model's vertices): rocks, crates with bevels, furniture, debris.
 * It suits dynamic bodies: its volume, centre of mass and inertia are those of the solid hull. The hull is built once
 * (quickhull) and keeps at most {@code maxVertices} points, the ones that matter most for its shape; contacts cost
 * time in proportion to that count. Follows the entity's rotation and scale (also non-uniform scale).
 */
public class ConvexHullCollider extends Collider {
  public static final int DEFAULT_MAX_VERTICES = 64;

  private final HullBuilder.Hull hull;
  private final float localVolume;
  private final Vector3f localCentroid = new Vector3f();
  /** Second moment of the solid (density 1) about its centroid, in local space. */
  private final Matrix3f localCovariance = new Matrix3f();

  // cached world shape, refreshed in place
  private final Matrix4f matrix = new Matrix4f();
  private final Matrix4f builtFor = new Matrix4f();
  private boolean built;
  private final Matrix3f normalMatrix = new Matrix3f();
  private final Matrix3f linear = new Matrix3f();
  private final Matrix3f covariance = new Matrix3f();
  private final Vector3f tmp = new Vector3f();
  private final Shape shape;

  /** @param points xyz per point; the hull keeps at most {@link #DEFAULT_MAX_VERTICES} of them */
  public ConvexHullCollider(float[] points) {
    this(points, DEFAULT_MAX_VERTICES);
  }

  public ConvexHullCollider(float[] points, int maxVertices) {
    this.hull = HullBuilder.build(points, maxVertices);
    this.shape = new Shape(this.hull);
    final float[] p = this.hull.vertices();
    // a fan of tetrahedra from the vertices' mean to each face triangle
    double ox = 0;
    double oy = 0;
    double oz = 0;
    final int vn = this.hull.vertexCount();
    for (int i = 0; i < vn; i++) {
      ox += p[i * 3];
      oy += p[i * 3 + 1];
      oz += p[i * 3 + 2];
    }
    ox /= vn;
    oy /= vn;
    oz /= vn;
    double volume = 0;
    final double[] centroid = new double[3];
    final double[] second = new double[9];
    final double[] a = new double[3];
    final double[] b = new double[3];
    final double[] c = new double[3];
    for (int f = 0; f < this.hull.faceCount(); f++) {
      final int start = this.hull.faceStart()[f];
      final int end = this.hull.faceStart()[f + 1];
      final int i0 = this.hull.faceIndices()[start];
      for (int k = start + 1; k + 1 < end; k++) {
        load(p, i0, ox, oy, oz, a);
        load(p, this.hull.faceIndices()[k], ox, oy, oz, b);
        load(p, this.hull.faceIndices()[k + 1], ox, oy, oz, c);
        final double v = (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0])
            + a[2] * (b[0] * c[1] - b[1] * c[0])) / 6.0;
        volume += v;
        for (int r = 0; r < 3; r++) {
          final double sr = a[r] + b[r] + c[r];
          centroid[r] += v * sr / 4.0;
          for (int s = 0; s < 3; s++) {
            final double ss = a[s] + b[s] + c[s];
            // integral of x x^T over a tetrahedron with one corner at the origin
            second[r * 3 + s] += v / 20.0 * (a[r] * a[s] + b[r] * b[s] + c[r] * c[s] + sr * ss);
          }
        }
      }
    }
    this.localVolume = (float) volume;
    for (int r = 0; r < 3; r++) {
      centroid[r] /= volume;
    }
    for (int r = 0; r < 3; r++) {
      for (int s = 0; s < 3; s++) {
        this.localCovariance.set(s, r, (float) (second[r * 3 + s] - volume * centroid[r] * centroid[s]));
      }
    }
    this.localCentroid.set((float) (ox + centroid[0]), (float) (oy + centroid[1]), (float) (oz + centroid[2]));
  }

  /** The hull of a mesh's vertex positions. */
  public ConvexHullCollider(MeshData data) {
    this(data, DEFAULT_MAX_VERTICES);
  }

  public ConvexHullCollider(MeshData data, int maxVertices) {
    this(positions(data), maxVertices);
  }

  private static float[] positions(MeshData data) {
    final int n = data.vertexCount();
    final float[] p = new float[n * 3];
    for (int v = 0; v < n; v++) {
      System.arraycopy(data.vertices(), v * MeshData.FLOATS_PER_VERTEX, p, v * 3, 3);
    }
    return p;
  }

  private static void load(float[] p, int i, double ox, double oy, double oz, double[] dest) {
    dest[0] = p[i * 3] - ox;
    dest[1] = p[i * 3 + 1] - oy;
    dest[2] = p[i * 3 + 2] - oz;
  }

  public int vertexCount() {
    return this.hull.vertexCount();
  }

  public int faceCount() {
    return this.hull.faceCount();
  }

  /** Volume of the hull in its local space (unscaled). */
  public float localVolume() {
    return this.localVolume;
  }

  /** Centre of mass in the entity's local space (without {@link #offset}). */
  public Vector3f localCentroid(Vector3f dest) {
    return dest.set(this.localCentroid);
  }

  /** The cached world hull: vertices, face planes and the centre of mass. */
  static final class Shape implements ConvexTests.Shape {
    final HullBuilder.Hull hull;
    final float[] vertices;
    final float[] normals;
    final float[] offsets;
    final Vector3f center = new Vector3f();

    Shape(HullBuilder.Hull hull) {
      this.hull = hull;
      this.vertices = new float[hull.vertices().length];
      this.normals = new float[hull.normals().length];
      this.offsets = new float[hull.faceCount()];
    }

    @Override
    public void support(Vector3f d, Vector3f dest) {
      final float[] v = this.vertices;
      int best = 0;
      float bestDot = -Float.MAX_VALUE;
      for (int i = 0; i < v.length; i += 3) {
        final float dot = v[i] * d.x + v[i + 1] * d.y + v[i + 2] * d.z;
        if (dot > bestDot) {
          bestDot = dot;
          best = i;
        }
      }
      dest.set(v[best], v[best + 1], v[best + 2]);
    }

    @Override
    public float radius() {
      return 0f;
    }

    @Override
    public void center(Vector3f dest) {
      dest.set(this.center);
    }

    /** The face whose normal is closest to {@code d}. */
    @Override
    public void feature(Vector3f d, ConvexTests.Feature dest) {
      int best = 0;
      float bestDot = -Float.MAX_VALUE;
      final float[] n = this.normals;
      for (int f = 0; f < this.offsets.length; f++) {
        final float dot = n[f * 3] * d.x + n[f * 3 + 1] * d.y + n[f * 3 + 2] * d.z;
        if (dot > bestDot) {
          bestDot = dot;
          best = f;
        }
      }
      dest.count = 0;
      for (int k = this.hull.faceStart()[best]; k < this.hull.faceStart()[best + 1]; k++) {
        final int i = this.hull.faceIndices()[k] * 3;
        dest.add(this.vertices[i], this.vertices[i + 1], this.vertices[i + 2]);
      }
      dest.normal.set(n[best * 3], n[best * 3 + 1], n[best * 3 + 2]);
    }

    /** Ray against the face planes (Cyrus-Beck); {@code d} normalized, faces pushed out by {@code inflate}. */
    Narrowphase.RayResult ray(Vector3f o, Vector3f d, float maxDistance, float inflate) {
      float tMin = -Float.MAX_VALUE;
      float tMax = Float.MAX_VALUE;
      int hitFace = -1;
      final float[] n = this.normals;
      for (int f = 0; f < this.offsets.length; f++) {
        final float nd = n[f * 3] * d.x + n[f * 3 + 1] * d.y + n[f * 3 + 2] * d.z;
        final float inside = this.offsets[f] + inflate - (n[f * 3] * o.x + n[f * 3 + 1] * o.y + n[f * 3 + 2] * o.z);
        if (Math.abs(nd) < 1e-9f) {
          if (inside < 0f) {
            return null; // parallel to this face and outside it
          }
          continue;
        }
        final float t = inside / nd;
        if (nd < 0f) { // entering through this face
          if (t > tMin) {
            tMin = t;
            hitFace = f;
          }
        } else {
          tMax = Math.min(tMax, t);
        }
        if (tMin > tMax) {
          return null;
        }
      }
      if (tMax < 0f) {
        return null;
      }
      if (tMin < 0f || hitFace < 0) {
        return new Narrowphase.RayResult(0f, new Vector3f(d).negate()); // started inside
      }
      if (tMin > maxDistance) {
        return null;
      }
      return new Narrowphase.RayResult(tMin, new Vector3f(n[hitFace * 3], n[hitFace * 3 + 1], n[hitFace * 3 + 2]));
    }
  }

  @Override
  void refresh() {
    final var m = entity().transform().worldMatrix(this.matrix);
    if (this.built && m.equals(this.builtFor)) {
      return;
    }
    this.builtFor.set(m);
    this.built = true;
    final var s = this.shape;
    final float[] local = this.hull.vertices();
    final var v = this.tmp;
    for (int i = 0; i < local.length; i += 3) {
      m.transformPosition(v.set(local[i], local[i + 1], local[i + 2]).add(this.offset));
      s.vertices[i] = v.x;
      s.vertices[i + 1] = v.y;
      s.vertices[i + 2] = v.z;
    }
    m.normal(this.normalMatrix);
    final float[] ln = this.hull.normals();
    for (int f = 0; f < s.offsets.length; f++) {
      this.normalMatrix.transform(v.set(ln[f * 3], ln[f * 3 + 1], ln[f * 3 + 2])).normalize();
      s.normals[f * 3] = v.x;
      s.normals[f * 3 + 1] = v.y;
      s.normals[f * 3 + 2] = v.z;
      final int i = this.hull.faceIndices()[this.hull.faceStart()[f]] * 3;
      s.offsets[f] = v.x * s.vertices[i] + v.y * s.vertices[i + 1] + v.z * s.vertices[i + 2];
    }
    m.transformPosition(s.center.set(this.localCentroid).add(this.offset));
  }

  /** Refreshes and returns the cached world hull (shared: do not keep it across calls). */
  Shape world() {
    refresh();
    return this.shape;
  }

  Shape cachedShape() {
    return this.shape;
  }

  @Override
  public Vector3f worldCenter(Vector3f dest) {
    return entity().transform().worldMatrix(this.matrix).transformPosition(dest.set(this.localCentroid)
        .add(this.offset));
  }

  @Override
  public float boundingRadius() {
    refresh();
    return cachedRadius();
  }

  @Override
  void cachedCenter(Vector3f dest) {
    dest.set(this.shape.center);
  }

  @Override
  float cachedRadius() {
    final var c = this.shape.center;
    final float[] v = this.shape.vertices;
    float r2 = 0f;
    for (int i = 0; i < v.length; i += 3) {
      final float dx = v[i] - c.x;
      final float dy = v[i + 1] - c.y;
      final float dz = v[i + 2] - c.z;
      r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
    }
    return (float) Math.sqrt(r2);
  }

  @Override
  float cachedInnerRadius() {
    final var c = this.shape.center;
    final float[] n = this.shape.normals;
    float r = Float.MAX_VALUE;
    for (int f = 0; f < this.shape.offsets.length; f++) {
      r = Math.min(r, this.shape.offsets[f] - (n[f * 3] * c.x + n[f * 3 + 1] * c.y + n[f * 3 + 2] * c.z));
    }
    return Math.max(r, 0f);
  }

  @Override
  Matrix4f cachedMatrix() {
    return this.matrix;
  }

  @Override
  float cachedVolume() {
    return this.localVolume * Math.abs(this.matrix.get3x3(this.linear).determinant());
  }

  /** I = m / V (tr(C) E - C), with the covariance C = L C0 L^T carried into world space by the linear part L. */
  @Override
  void addCachedInertia(float mass, Matrix3f dest) {
    if (this.localVolume <= 0f) {
      return;
    }
    final var l = this.matrix.get3x3(this.linear);
    final var c = this.covariance.set(l).mul(this.localCovariance).mul(l.transpose());
    final float k = mass / this.localVolume;
    final float trace = c.m00 + c.m11 + c.m22;
    dest.m00 += k * (trace - c.m00);
    dest.m11 += k * (trace - c.m11);
    dest.m22 += k * (trace - c.m22);
    dest.m01 -= k * c.m01;
    dest.m10 -= k * c.m10;
    dest.m02 -= k * c.m02;
    dest.m20 -= k * c.m20;
    dest.m12 -= k * c.m12;
    dest.m21 -= k * c.m21;
  }
}
