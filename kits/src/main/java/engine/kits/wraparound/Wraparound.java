package engine.kits.wraparound;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import engine.net.replication.WorldSpace;

/**
 * A box of space that wraps around on every side: what leaves it on one side comes back in on the opposite one (an
 * arena in the style of the old arcade games, in 3D). Points are kept in {@code [min, max)} on each axis, and the way
 * from one point to another is the shortest one, which may cross an edge.
 *
 * <p>
 * It is a {@link WorldSpace}: give it to the replication on the host and on clients
 * ({@code replication.setWorldSpace(box)}) so that copies crossing an edge blend the short way instead of sweeping
 * through the whole box, corrections of predicted players are measured the short way, and lag compensation and
 * {@code RelevanceFilter.byDistance(radius, box)} measure the same way. {@code FlightMotor.space} keeps a flying player
 * inside it.
 */
public final class Wraparound implements WorldSpace {
  private final Vector3f min = new Vector3f();
  private final Vector3f max = new Vector3f();
  private final Vector3f size = new Vector3f();

  public Wraparound(Vector3fc min, Vector3fc max) {
    if (!(max.x() > min.x() && max.y() > min.y() && max.z() > min.z())) {
      throw new IllegalArgumentException("max must be greater than min on every axis");
    }
    this.min.set(min);
    this.max.set(max);
    max.sub(min, this.size);
  }

  /** A cube centred on the origin, {@code halfSize} from the centre to each face. */
  public static Wraparound cube(float halfSize) {
    return new Wraparound(new Vector3f(-halfSize), new Vector3f(halfSize));
  }

  public Vector3fc min() {
    return this.min;
  }

  public Vector3fc max() {
    return this.max;
  }

  public Vector3fc size() {
    return this.size;
  }

  /** Puts {@code point} back inside the box (in place) and returns it. */
  public Vector3f wrap(Vector3f point) {
    point.x = wrap(point.x, this.min.x, this.size.x);
    point.y = wrap(point.y, this.min.y, this.size.y);
    point.z = wrap(point.z, this.min.z, this.size.z);
    return point;
  }

  /** Same as {@link #wrap}. */
  @Override
  public Vector3f normalize(Vector3f point) {
    return wrap(point);
  }

  @Override
  public boolean wraps() {
    return true;
  }

  /** The shortest offset from {@code from} to {@code to}, crossing edges where that is shorter. */
  @Override
  public Vector3f delta(Vector3fc from, Vector3fc to, Vector3f dest) {
    return dest.set(shortest(to.x() - from.x(), this.size.x), shortest(to.y() - from.y(), this.size.y),
        shortest(to.z() - from.z(), this.size.z));
  }

  /** The shortest distance between two points, squared. */
  @Override
  public float distanceSquared(Vector3fc a, Vector3fc b) {
    final float x = shortest(b.x() - a.x(), this.size.x);
    final float y = shortest(b.y() - a.y(), this.size.y);
    final float z = shortest(b.z() - a.z(), this.size.z);
    return x * x + y * y + z * z;
  }

  /** The point {@code t} of the way from {@code a} to {@code b} the short way, inside the box. */
  @Override
  public Vector3f lerp(Vector3fc a, Vector3fc b, float t, Vector3f dest) {
    final float x = a.x() + shortest(b.x() - a.x(), this.size.x) * t;
    final float y = a.y() + shortest(b.y() - a.y(), this.size.y) * t;
    final float z = a.z() + shortest(b.z() - a.z(), this.size.z) * t;
    return wrap(dest.set(x, y, z));
  }

  private static float wrap(float value, float min, float size) {
    if (value >= min && value < min + size) {
      return value;
    }
    float v = (value - min) % size;
    if (v < 0f) {
      v += size;
    }
    final float result = min + v;
    return result >= min + size ? min : result; // rounding can land exactly on the far face
  }

  private static float shortest(float d, float size) {
    float r = d % size;
    if (r > size * 0.5f) {
      r -= size;
    } else if (r < -size * 0.5f) {
      r += size;
    }
    return r;
  }

  @Override
  public String toString() {
    return "Wraparound[" + this.min + " .. " + this.max + "]";
  }
}
