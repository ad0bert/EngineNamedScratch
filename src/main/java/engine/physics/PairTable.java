package engine.physics;

import java.util.Arrays;

import engine.ecs.Entity;

/**
 * Open-addressing map from a pair key to the two entities of the pair. Used to diff the touching pairs of consecutive
 * physics steps without boxing keys or allocating per-pair arrays. Slots are read by index so iterating allocates
 * nothing.
 */
final class PairTable {
  private long[] keys = new long[256];
  private Entity[] first = new Entity[256];
  private Entity[] second = new Entity[256];
  private int[] stamps = new int[256];
  private int stamp = 1;
  private int size;

  void clear() {
    this.stamp++;
    if (this.size > 0) { // do not keep removed entities alive through stale slots
      Arrays.fill(this.first, null);
      Arrays.fill(this.second, null);
    }
    this.size = 0;
  }

  void put(long key, Entity a, Entity b) {
    if ((this.size + 1) * 2 > this.keys.length) {
      grow();
    }
    final int mask = this.keys.length - 1;
    int i = LongSet.hash(key) & mask;
    while (this.stamps[i] == this.stamp) {
      if (this.keys[i] == key) {
        return;
      }
      i = (i + 1) & mask;
    }
    this.keys[i] = key;
    this.first[i] = a;
    this.second[i] = b;
    this.stamps[i] = this.stamp;
    this.size++;
  }

  boolean contains(long key) {
    final int mask = this.keys.length - 1;
    int i = LongSet.hash(key) & mask;
    while (this.stamps[i] == this.stamp) {
      if (this.keys[i] == key) {
        return true;
      }
      i = (i + 1) & mask;
    }
    return false;
  }

  int capacity() {
    return this.keys.length;
  }

  boolean occupied(int slot) {
    return this.stamps[slot] == this.stamp;
  }

  long key(int slot) {
    return this.keys[slot];
  }

  Entity first(int slot) {
    return this.first[slot];
  }

  Entity second(int slot) {
    return this.second[slot];
  }

  private void grow() {
    final long[] oldKeys = this.keys;
    final var oldFirst = this.first;
    final var oldSecond = this.second;
    final int[] oldStamps = this.stamps;
    final int n = oldKeys.length * 2;
    this.keys = new long[n];
    this.first = new Entity[n];
    this.second = new Entity[n];
    this.stamps = new int[n];
    final int mask = n - 1;
    for (int j = 0; j < oldKeys.length; j++) {
      if (oldStamps[j] == this.stamp) {
        int i = LongSet.hash(oldKeys[j]) & mask;
        while (this.stamps[i] == this.stamp) {
          i = (i + 1) & mask;
        }
        this.keys[i] = oldKeys[j];
        this.first[i] = oldFirst[j];
        this.second[i] = oldSecond[j];
        this.stamps[i] = this.stamp;
      }
    }
  }
}
