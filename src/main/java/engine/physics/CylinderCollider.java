package engine.physics;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A solid cylinder standing along the entity's local Y axis (or X/Z, see {@link #axis}): barrels, wheels, pillars,
 * logs. It stands flat on its caps and rolls on its side. {@link #halfHeight} is half its length; scaling stretches
 * the length by the scale along the axis and the radius by the largest of the other two.
 */
public class CylinderCollider extends Collider {
  public float radius = 0.5f;
  public float halfHeight = 0.5f;
  /** Local axis of the cylinder: 0 x, 1 y (default), 2 z. */
  public int axis = 1;

  // cached world shape, refreshed in place
  private final Matrix4f matrix = new Matrix4f();
  private final Shape shape = new Shape();

  public CylinderCollider() {
  }

  public CylinderCollider(float radius, float halfHeight) {
    this.radius = radius;
    this.halfHeight = halfHeight;
  }

  /**
   * The cached world cylinder: centre, unit axis and two unit directions across it, half length, radius. For contacts
   * it is a slightly smaller cylinder grown by {@link #round} (its edges are rounded by about a centimetre), so the
   * shallow overlaps of resting contacts are found by GJK alone.
   */
  static final class Shape implements ConvexTests.Shape {
    /** Points on a cap's rim used for contact manifolds. */
    private static final int RIM = 16;
    private static final float[] COS = new float[RIM];
    private static final float[] SIN = new float[RIM];

    static {
      for (int i = 0; i < RIM; i++) {
        COS[i] = (float) Math.cos(2 * Math.PI * i / RIM);
        SIN[i] = (float) Math.sin(2 * Math.PI * i / RIM);
      }
    }

    final Vector3f c = new Vector3f();
    final Vector3f u = new Vector3f(0f, 1f, 0f);
    final Vector3f e1 = new Vector3f(1f, 0f, 0f);
    final Vector3f e2 = new Vector3f(0f, 0f, 1f);
    float h;
    float r;
    float round;

    @Override
    public void support(Vector3f d, Vector3f dest) {
      final float du = d.dot(this.u);
      final float h = this.h - this.round;
      dest.set(this.c).fma(du >= 0f ? h : -h, this.u);
      // the radial part of d, without a temporary
      final float rx = d.x - du * this.u.x;
      final float ry = d.y - du * this.u.y;
      final float rz = d.z - du * this.u.z;
      final float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
      if (len > 1e-9f) {
        final float s = (this.r - this.round) / len;
        dest.add(rx * s, ry * s, rz * s);
      }
    }

    @Override
    public float radius() {
      return this.round;
    }

    @Override
    public void center(Vector3f dest) {
      dest.set(this.c);
    }

    /** A cap (as a polygon on its rim) when it faces {@code d}, otherwise the line along the side. */
    @Override
    public void feature(Vector3f d, ConvexTests.Feature dest) {
      final float du = d.dot(this.u);
      dest.count = 0;
      if (Math.abs(du) > 0.7f) {
        final float sign = du > 0f ? 1f : -1f;
        final float cx = this.c.x + sign * this.h * this.u.x;
        final float cy = this.c.y + sign * this.h * this.u.y;
        final float cz = this.c.z + sign * this.h * this.u.z;
        for (int i = 0; i < RIM; i++) {
          final float a = this.r * COS[i];
          final float b = this.r * SIN[i];
          dest.add(cx + a * this.e1.x + b * this.e2.x, cy + a * this.e1.y + b * this.e2.y,
              cz + a * this.e1.z + b * this.e2.z);
        }
        dest.normal.set(this.u).mul(sign);
        return;
      }
      float rx = d.x - du * this.u.x;
      float ry = d.y - du * this.u.y;
      float rz = d.z - du * this.u.z;
      final float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
      if (len < 1e-9f) {
        rx = this.e1.x;
        ry = this.e1.y;
        rz = this.e1.z;
      } else {
        rx *= this.r / len;
        ry *= this.r / len;
        rz *= this.r / len;
      }
      dest.add(this.c.x - this.h * this.u.x + rx, this.c.y - this.h * this.u.y + ry, this.c.z - this.h * this.u.z + rz);
      dest.add(this.c.x + this.h * this.u.x + rx, this.c.y + this.h * this.u.y + ry, this.c.z + this.h * this.u.z + rz);
    }
  }

  @Override
  void refresh() {
    final var m = entity().transform().worldMatrix(this.matrix);
    final var s = this.shape;
    m.transformPosition(s.c.set(this.offset));
    final int a1 = (this.axis + 1) % 3;
    final int a2 = (this.axis + 2) % 3;
    final float length = unitColumn(m, this.axis, s.u);
    final float side = Math.max(unitColumn(m, a1, s.e1), unitColumn(m, a2, s.e2));
    // keep the frame orthonormal even under shear
    s.e1.fma(-s.e1.dot(s.u), s.u);
    if (s.e1.lengthSquared() < 1e-12f) {
      HingeJoint.perpendicular(s.u, s.e1);
    }
    s.e1.normalize();
    s.u.cross(s.e1, s.e2);
    s.h = this.halfHeight * length;
    s.r = this.radius * side;
    s.round = Math.min(0.01f, 0.1f * Math.min(s.h, s.r));
  }

  /** Column i of the matrix normalized into dest; returns its length (the scale along it). */
  private static float unitColumn(Matrix4f m, int i, Vector3f dest) {
    m.getColumn(i, dest);
    final float len = dest.length();
    if (len > 1e-9f) {
      dest.div(len);
    } else {
      dest.set(i == 0 ? 1f : 0f, i == 1 ? 1f : 0f, i == 2 ? 1f : 0f);
    }
    return len;
  }

  /** Refreshes and returns the cached world cylinder (shared: do not keep it across calls). */
  Shape world() {
    refresh();
    return this.shape;
  }

  Shape cachedShape() {
    return this.shape;
  }

  @Override
  public Vector3f worldCenter(Vector3f dest) {
    return entity().transform().worldMatrix(this.matrix).transformPosition(dest.set(this.offset));
  }

  @Override
  public float boundingRadius() {
    refresh();
    return cachedRadius();
  }

  @Override
  void cachedCenter(Vector3f dest) {
    dest.set(this.shape.c);
  }

  @Override
  float cachedRadius() {
    return (float) Math.sqrt(this.shape.h * this.shape.h + this.shape.r * this.shape.r);
  }

  @Override
  float cachedInnerRadius() {
    return Math.min(this.shape.h, this.shape.r);
  }

  @Override
  Matrix4f cachedMatrix() {
    return this.matrix;
  }

  @Override
  float cachedVolume() {
    return (float) Math.PI * this.shape.r * this.shape.r * 2f * this.shape.h;
  }

  @Override
  void addCachedInertia(float mass, Matrix3f dest) {
    final float r = this.shape.r;
    final float h = this.shape.h;
    final float along = mass * r * r / 2f;
    final float across = mass * (3f * r * r + 4f * h * h) / 12f;
    // across * E + (along - across) * u u^T
    dest.m00 += across;
    dest.m11 += across;
    dest.m22 += across;
    PhysicsSystem.addAxisInertia(dest, this.shape.u, along - across);
  }

  /** Ray against the cylinder's side and caps; {@code d} normalized. */
  static Narrowphase.RayResult ray(Shape s, Vector3f o, Vector3f d, float maxDistance) {
    final float ox = o.x - s.c.x;
    final float oy = o.y - s.c.y;
    final float oz = o.z - s.c.z;
    final float oa = ox * s.u.x + oy * s.u.y + oz * s.u.z;
    final float da = d.dot(s.u);
    // radial parts of the origin and the direction
    final float orx = ox - oa * s.u.x;
    final float ory = oy - oa * s.u.y;
    final float orz = oz - oa * s.u.z;
    final float drx = d.x - da * s.u.x;
    final float dry = d.y - da * s.u.y;
    final float drz = d.z - da * s.u.z;
    final float oo = orx * orx + ory * ory + orz * orz;
    if (Math.abs(oa) <= s.h && oo <= s.r * s.r) {
      return new Narrowphase.RayResult(0f, new Vector3f(d).negate()); // started inside
    }
    float best = Float.MAX_VALUE;
    final var normal = new Vector3f();
    final float a = drx * drx + dry * dry + drz * drz;
    if (a > 1e-12f) {
      final float b = orx * drx + ory * dry + orz * drz;
      final float c = oo - s.r * s.r;
      final float disc = b * b - a * c;
      if (disc >= 0f) {
        final float t = (-b - (float) Math.sqrt(disc)) / a;
        if (t >= 0f && Math.abs(oa + t * da) <= s.h) {
          best = t;
          normal.set(orx + t * drx, ory + t * dry, orz + t * drz).normalize();
        }
      }
    }
    if (Math.abs(da) > 1e-12f) {
      for (int end = -1; end <= 1; end += 2) {
        final float t = (end * s.h - oa) / da;
        if (t < 0f || t >= best || end * da > 0f) {
          continue; // behind, farther, or reaching this cap from inside
        }
        final float px = orx + t * drx;
        final float py = ory + t * dry;
        final float pz = orz + t * drz;
        if (px * px + py * py + pz * pz <= s.r * s.r) {
          best = t;
          normal.set(s.u).mul(end);
        }
      }
    }
    return best <= maxDistance ? new Narrowphase.RayResult(best, normal) : null;
  }
}
