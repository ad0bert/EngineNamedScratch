package engine.animation;

import java.util.Arrays;

/**
 * Keyframes of a value with {@link #width} floats (1 for a number, 3 for a position, 4 for a colour or a quaternion)
 * over time. Immutable, so clips can share their tracks between any number of animators.
 *
 * <p>
 * Between keys the value is held ({@link Interpolation#STEP}), interpolated in a straight line (or along the great
 * arc for rotations, see {@link #sample(float, float[], boolean)}) with an optional {@link Easing}, or follows a cubic
 * Hermite spline ({@link Interpolation#CUBIC_SPLINE}, the glTF layout: in-tangent, value, out-tangent per key).
 * Before the first and after the last key the end values are held.
 */
public final class Track {
  public enum Interpolation {
    STEP, LINEAR, CUBIC_SPLINE
  }

  private final float[] times;
  private final float[] values;
  private final int width;
  private final Interpolation interpolation;
  private final Easing easing;

  /**
   * @param times  ascending key times in seconds
   * @param values {@code width} floats per key ({@code 3 * width} for cubic splines)
   */
  public Track(float[] times, float[] values, int width, Interpolation interpolation, Easing easing) {
    final int per = interpolation == Interpolation.CUBIC_SPLINE ? 3 * width : width;
    if (times.length == 0 || values.length != times.length * per) {
      throw new IllegalArgumentException("a track needs " + per + " values per key and at least one key");
    }
    for (int i = 1; i < times.length; i++) {
      if (times[i] < times[i - 1]) {
        throw new IllegalArgumentException("key times must not decrease");
      }
    }
    this.times = times.clone();
    this.values = values.clone();
    this.width = width;
    this.interpolation = interpolation;
    this.easing = easing != null ? easing : Easing.LINEAR;
  }

  /** Linear keys. */
  public static Track linear(float[] times, float[] values, int width) {
    return new Track(times, values, width, Interpolation.LINEAR, Easing.LINEAR);
  }

  /** Two keys, from {@code from} at 0 to {@code to} at {@code seconds}, with easing: a tween. */
  public static Track tween(float[] from, float[] to, float seconds, Easing easing) {
    if (from.length != to.length) {
      throw new IllegalArgumentException("from and to differ in size");
    }
    final float[] v = Arrays.copyOf(from, from.length * 2);
    System.arraycopy(to, 0, v, from.length, to.length);
    return new Track(new float[] { 0f, seconds }, v, from.length, Interpolation.LINEAR, easing);
  }

  /** The same keys with another easing between them. */
  public Track withEasing(Easing easing) {
    return new Track(this.times, this.values, this.width, this.interpolation, easing);
  }

  public int width() {
    return this.width;
  }

  public Interpolation interpolation() {
    return this.interpolation;
  }

  public int keyCount() {
    return this.times.length;
  }

  /** Time of the last key. */
  public float duration() {
    return this.times[this.times.length - 1];
  }

  /**
   * Writes the value at {@code time} into {@code out[0..width)}. With {@code rotation} (width 4) the values are
   * quaternions (x, y, z, w): linear keys are slerped along the shorter arc, and the result is normalized.
   * Does not allocate.
   */
  public void sample(float time, float[] out, boolean rotation) {
    final int n = this.times.length;
    final int w = this.width;
    if (n == 1 || time <= this.times[0]) {
      key(0, out);
    } else if (time >= this.times[n - 1]) {
      key(n - 1, out);
    } else {
      final int i = segment(time);
      final float t0 = this.times[i];
      final float t1 = this.times[i + 1];
      final float dt = t1 - t0;
      final float u = dt > 0f ? (time - t0) / dt : 0f;
      switch (this.interpolation) {
      case STEP -> key(i, out);
      case LINEAR -> {
        final float e = this.easing.apply(u);
        if (rotation && w == 4) {
          slerp(this.values, i * 4, (i + 1) * 4, e, out);
        } else {
          for (int k = 0; k < w; k++) {
            final float a = this.values[i * w + k];
            out[k] = a + (this.values[(i + 1) * w + k] - a) * e;
          }
        }
      }
      case CUBIC_SPLINE -> {
        final float u2 = u * u;
        final float u3 = u2 * u;
        final float h00 = 2 * u3 - 3 * u2 + 1;
        final float h10 = u3 - 2 * u2 + u;
        final float h01 = -2 * u3 + 3 * u2;
        final float h11 = u3 - u2;
        final int a = i * 3 * w;
        final int b = (i + 1) * 3 * w;
        for (int k = 0; k < w; k++) {
          final float p0 = this.values[a + w + k];
          final float m0 = this.values[a + 2 * w + k] * dt; // out-tangent of key i
          final float p1 = this.values[b + w + k];
          final float m1 = this.values[b + k] * dt; // in-tangent of key i + 1
          out[k] = h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
        }
      }
      }
    }
    if (rotation && w == 4) {
      normalize(out);
    }
  }

  private void key(int i, float[] out) {
    final int per = this.interpolation == Interpolation.CUBIC_SPLINE ? 3 * this.width : this.width;
    final int offset = this.interpolation == Interpolation.CUBIC_SPLINE ? this.width : 0;
    System.arraycopy(this.values, i * per + offset, out, 0, this.width);
  }

  /** Index of the key that starts the segment containing {@code time} (binary search). */
  private int segment(float time) {
    int lo = 0;
    int hi = this.times.length - 1;
    while (hi - lo > 1) {
      final int mid = (lo + hi) >>> 1;
      if (this.times[mid] <= time) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** Spherical interpolation of the quaternions at {@code a} and {@code b} (shorter arc). */
  static void slerp(float[] v, int a, int b, float t, float[] out) {
    slerp(v[a], v[a + 1], v[a + 2], v[a + 3], v[b], v[b + 1], v[b + 2], v[b + 3], t, out);
  }

  static void slerp(float ax, float ay, float az, float aw, float bx, float by, float bz, float bw, float t,
      float[] out) {
    float dot = ax * bx + ay * by + az * bz + aw * bw;
    if (dot < 0f) { // the same rotation the shorter way round
      bx = -bx;
      by = -by;
      bz = -bz;
      bw = -bw;
      dot = -dot;
    }
    float wa;
    float wb;
    if (dot > 0.9995f) { // nearly equal: linear is exact enough and avoids dividing by sin(0)
      wa = 1f - t;
      wb = t;
    } else {
      final double theta = Math.acos(dot);
      final double sin = Math.sin(theta);
      wa = (float) (Math.sin((1 - t) * theta) / sin);
      wb = (float) (Math.sin(t * theta) / sin);
    }
    out[0] = ax * wa + bx * wb;
    out[1] = ay * wa + by * wb;
    out[2] = az * wa + bz * wb;
    out[3] = aw * wa + bw * wb;
  }

  static void normalize(float[] q) {
    final float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    if (len > 1e-8f) {
      q[0] /= len;
      q[1] /= len;
      q[2] /= len;
      q[3] /= len;
    } else {
      q[0] = q[1] = q[2] = 0f;
      q[3] = 1f;
    }
  }
}
