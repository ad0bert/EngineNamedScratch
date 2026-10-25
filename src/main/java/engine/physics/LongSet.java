package engine.physics;

/** Open-addressing set of longs that is cleared in O(1) and does not box; reused every physics step. */
final class LongSet {
  private long[] keys = new long[256];
  private int[] stamps = new int[256];
  private int stamp = 1;
  private int size;

  void clear() {
    this.stamp++;
    this.size = 0;
  }

  /** @return true if {@code key} was not yet present */
  boolean add(long key) {
    if ((this.size + 1) * 2 > this.keys.length) {
      grow();
    }
    final int mask = this.keys.length - 1;
    int i = hash(key) & mask;
    while (this.stamps[i] == this.stamp) {
      if (this.keys[i] == key) {
        return false;
      }
      i = (i + 1) & mask;
    }
    this.keys[i] = key;
    this.stamps[i] = this.stamp;
    this.size++;
    return true;
  }

  private void grow() {
    final long[] oldKeys = this.keys;
    final int[] oldStamps = this.stamps;
    this.keys = new long[oldKeys.length * 2];
    this.stamps = new int[oldKeys.length * 2];
    final int mask = this.keys.length - 1;
    for (int j = 0; j < oldKeys.length; j++) {
      if (oldStamps[j] == this.stamp) {
        int i = hash(oldKeys[j]) & mask;
        while (this.stamps[i] == this.stamp) {
          i = (i + 1) & mask;
        }
        this.keys[i] = oldKeys[j];
        this.stamps[i] = this.stamp;
      }
    }
  }

  static int hash(long k) {
    return (int) ((k * 0x9E3779B97F4A7C15L) >>> 32);
  }
}
