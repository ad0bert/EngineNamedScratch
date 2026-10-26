package engine.physics;

import java.util.Arrays;

import org.joml.Vector3f;

/**
 * Accumulated contact impulses of the last step, keyed by collider pair, used to warm-start the solver: a contact
 * that persists starts from the impulse it ended with instead of from zero, so stacks converge in a few iterations
 * and stop sagging. Points are matched by proximity, friction is stored as a world-space vector so it survives the
 * small turns of the contact normal between steps.
 *
 * <p>
 * Two tables are used alternately (one read, one written) and cleared in O(1) with stamps, so pairs that stopped
 * touching are dropped automatically and nothing is allocated once the tables have grown.
 */
final class ContactCache {
  /** Per entry: normal (3), then per point: position (3), normal impulse (1), friction impulse vector (3). */
  private static final int POINT_FLOATS = 7;
  private static final int STRIDE = 3 + Narrowphase.Contact.MAX_POINTS * POINT_FLOATS;

  private static final class Table {
    long[] keys = new long[256];
    int[] stamps = new int[256];
    int[] counts = new int[256];
    float[] data = new float[256 * STRIDE];
    int stamp = 1;
    int size;

    void clear() {
      this.stamp++;
      this.size = 0;
    }

    int find(long key) {
      final int mask = this.keys.length - 1;
      int i = LongSet.hash(key) & mask;
      while (this.stamps[i] == this.stamp) {
        if (this.keys[i] == key) {
          return i;
        }
        i = (i + 1) & mask;
      }
      return -1;
    }

    int insert(long key) {
      if ((this.size + 1) * 2 > this.keys.length) {
        grow();
      }
      final int mask = this.keys.length - 1;
      int i = LongSet.hash(key) & mask;
      while (this.stamps[i] == this.stamp) {
        if (this.keys[i] == key) {
          return i;
        }
        i = (i + 1) & mask;
      }
      this.keys[i] = key;
      this.stamps[i] = this.stamp;
      this.size++;
      return i;
    }

    private void grow() {
      final long[] oldKeys = this.keys;
      final int[] oldStamps = this.stamps;
      final int[] oldCounts = this.counts;
      final float[] oldData = this.data;
      final int n = oldKeys.length * 2;
      this.keys = new long[n];
      this.stamps = new int[n];
      this.counts = new int[n];
      this.data = new float[n * STRIDE];
      final int mask = n - 1;
      for (int j = 0; j < oldKeys.length; j++) {
        if (oldStamps[j] == this.stamp) {
          int i = LongSet.hash(oldKeys[j]) & mask;
          while (this.stamps[i] == this.stamp) {
            i = (i + 1) & mask;
          }
          this.keys[i] = oldKeys[j];
          this.stamps[i] = this.stamp;
          this.counts[i] = oldCounts[j];
          System.arraycopy(oldData, j * STRIDE, this.data, i * STRIDE, STRIDE);
        }
      }
    }
  }

  /** Points further apart than this (m) are different contacts. */
  private static final float MATCH_DISTANCE = 0.1f;
  /** Normals that turned more than about 18 degrees start cold. */
  private static final float MATCH_NORMAL_DOT = 0.95f;

  private Table previous = new Table();
  private Table current = new Table();
  private final boolean[] used = new boolean[Narrowphase.Contact.MAX_POINTS];

  /** Starts a step: what was stored during the last step becomes readable, the write table is emptied. */
  void swap() {
    final var t = this.previous;
    this.previous = this.current;
    this.current = t;
    this.current.clear();
  }

  void clear() {
    this.previous.clear();
    this.current.clear();
  }

  int size() {
    return this.current.size;
  }

  static long key(Collider a, Collider b) {
    return (long) a.id << 32 | (b.id & 0xFFFFFFFFL);
  }

  /**
   * Looks up the impulses of the last step for each point of the new manifold.
   *
   * @param outNormal     receives the normal impulse per point (0 when the point is new)
   * @param outFriction   receives the world-space friction impulse per point (zero when new)
   * @return true if anything was found
   */
  boolean lookup(long key, Narrowphase.Contact c, float[] outNormal, Vector3f[] outFriction) {
    for (int k = 0; k < c.count(); k++) {
      outNormal[k] = 0f;
      outFriction[k].zero();
    }
    final var t = this.previous;
    final int slot = t.find(key);
    if (slot < 0) {
      return false;
    }
    final float[] d = t.data;
    final int base = slot * STRIDE;
    final var n = c.normal();
    if (n.x * d[base] + n.y * d[base + 1] + n.z * d[base + 2] < MATCH_NORMAL_DOT) {
      return false;
    }
    final int oldCount = t.counts[slot];
    Arrays.fill(this.used, false);
    boolean any = false;
    for (int k = 0; k < c.count(); k++) {
      final var p = c.point(k);
      int best = -1;
      float bestDist = MATCH_DISTANCE * MATCH_DISTANCE;
      for (int o = 0; o < oldCount; o++) {
        if (this.used[o]) {
          continue;
        }
        final int pb = base + 3 + o * POINT_FLOATS;
        final float dx = p.x - d[pb];
        final float dy = p.y - d[pb + 1];
        final float dz = p.z - d[pb + 2];
        final float dist = dx * dx + dy * dy + dz * dz;
        if (dist < bestDist) {
          bestDist = dist;
          best = o;
        }
      }
      if (best >= 0) {
        this.used[best] = true;
        final int pb = base + 3 + best * POINT_FLOATS;
        outNormal[k] = d[pb + 3];
        outFriction[k].set(d[pb + 4], d[pb + 5], d[pb + 6]);
        any = true;
      }
    }
    return any;
  }

  /** Stores the final impulses of a solved manifold for the next step. */
  void store(long key, Narrowphase.Contact c, int count, float[] normalImpulse, float[] tangent1Impulse,
      float[] tangent2Impulse, Vector3f t1, Vector3f t2) {
    final var t = this.current;
    final int slot = t.insert(key);
    final float[] d = t.data;
    final int base = slot * STRIDE;
    final var n = c.normal();
    d[base] = n.x;
    d[base + 1] = n.y;
    d[base + 2] = n.z;
    t.counts[slot] = count;
    for (int k = 0; k < count; k++) {
      final int pb = base + 3 + k * POINT_FLOATS;
      final var p = c.point(k);
      d[pb] = p.x;
      d[pb + 1] = p.y;
      d[pb + 2] = p.z;
      d[pb + 3] = normalImpulse[k];
      d[pb + 4] = tangent1Impulse[k] * t1.x + tangent2Impulse[k] * t2.x;
      d[pb + 5] = tangent1Impulse[k] * t1.y + tangent2Impulse[k] * t2.y;
      d[pb + 6] = tangent1Impulse[k] * t1.z + tangent2Impulse[k] * t2.z;
    }
  }
}
