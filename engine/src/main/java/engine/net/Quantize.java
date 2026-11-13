package engine.net;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * Rounds positions and rotations to what the network carries, as whole numbers: the sender and the receiver then hold
 * exactly the same values, so later changes can be sent as differences from them.
 */
public final class Quantize {
  /** Position steps per metre: about a millimetre. */
  public static final float POSITION_SCALE = 1024f;
  /** Bits of a packed rotation: 2 for the left-out component, 15 for each of the other three. */
  public static final int ROTATION_BITS = 47;
  static final float ROTATION_RANGE = 0.70710679f; // the three smaller components of a unit quaternion: +-1/sqrt(2)
  private static final int COMPONENT_BITS = 15;
  private static final int COMPONENT_MAX = (1 << COMPONENT_BITS) - 1;

  private Quantize() {
  }

  /** A coordinate in steps of 1/1024 m (up to about +-2 million metres). */
  public static int position(float metres) {
    return Float.isNaN(metres) ? 0 : (int) Math.clamp(Math.round(metres * POSITION_SCALE), -Integer.MAX_VALUE,
        Integer.MAX_VALUE);
  }

  public static float position(int steps) {
    return steps / POSITION_SCALE;
  }

  /** A unit rotation in {@link #ROTATION_BITS} bits ("smallest three"); q and -q pack the same. */
  public static long rotation(Quaternionfc q) {
    float x = q.x();
    float y = q.y();
    float z = q.z();
    float w = q.w();
    final float length = (float) Math.sqrt(x * x + y * y + z * z + w * w);
    if (!(length > 0)) {
      x = 0;
      y = 0;
      z = 0;
      w = 1;
    } else {
      x /= length;
      y /= length;
      z /= length;
      w /= length;
    }
    final float[] c = { x, y, z, w };
    int largest = 0;
    for (int i = 1; i < 4; i++) {
      if (Math.abs(c[i]) > Math.abs(c[largest])) {
        largest = i;
      }
    }
    final float sign = c[largest] < 0 ? -1f : 1f;
    long packed = largest;
    int shift = 2;
    for (int i = 0; i < 4; i++) {
      if (i != largest) {
        final float t = Math.clamp((c[i] * sign + ROTATION_RANGE) / (2 * ROTATION_RANGE), 0f, 1f);
        packed |= (long) Math.round(t * COMPONENT_MAX) << shift;
        shift += COMPONENT_BITS;
      }
    }
    return packed;
  }

  public static Quaternionf rotation(long packed, Quaternionf dest) {
    final int largest = (int) (packed & 3);
    final float[] c = new float[4];
    float sum = 0;
    int shift = 2;
    for (int i = 0; i < 4; i++) {
      if (i != largest) {
        final long q = (packed >>> shift) & COMPONENT_MAX;
        c[i] = (q / (float) COMPONENT_MAX) * 2 * ROTATION_RANGE - ROTATION_RANGE;
        sum += c[i] * c[i];
        shift += COMPONENT_BITS;
      }
    }
    c[largest] = (float) Math.sqrt(Math.max(0f, 1f - sum));
    return dest.set(c[0], c[1], c[2], c[3]).normalize();
  }

  /** Signed to unsigned so that small numbers of either sign stay small ({@link BitWriter#writeVarInt}). */
  public static int zigzag(int value) {
    return (value << 1) ^ (value >> 31);
  }

  public static int unzigzag(int value) {
    return (value >>> 1) ^ -(value & 1);
  }
}
