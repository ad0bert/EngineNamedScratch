package engine.spatial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uniform grid over bounding spheres, keyed by integer ids chosen by the caller. A sphere is registered in every cell
 * its bounding box touches; spheres bigger than one cell (radius &gt; cellSize) go to a separate "large" list instead,
 * so a ground plane does not fill thousands of cells. Used for the physics broadphase (rebuilt every step) and for
 * culling static render geometry (persistent, updated incrementally).
 */
public final class SpatialGrid {

  /** Growable list of ints without boxing. */
  public static final class IntList {
    private int[] data = new int[4];
    private int size;

    public void add(int v) {
      if (this.size == this.data.length) {
        this.data = Arrays.copyOf(this.data, this.size * 2);
      }
      this.data[this.size++] = v;
    }

    public int get(int i) {
      return this.data[i];
    }

    public int size() {
      return this.size;
    }

    void removeValue(int v) {
      for (int i = 0; i < this.size; i++) {
        if (this.data[i] == v) {
          this.data[i] = this.data[--this.size];
          return;
        }
      }
    }

    void clear() {
      this.size = 0;
    }
  }

  /** Receives each occupied cell with its world-space bounds. */
  @FunctionalInterface
  public interface CellVisitor {
    void visit(IntList ids, float minX, float minY, float minZ, float maxX, float maxY, float maxZ);
  }

  private final float cellSize;
  private final Map<Long, IntList> cells = new HashMap<>();
  private final IntList large = new IntList();
  private final List<long[]> membership = new ArrayList<>(); // id -> cell keys, or null for large/absent
  private final List<Boolean> isLarge = new ArrayList<>();
  // extent of all cells ever used since the last clear(); shrinking on removal is not worth the bookkeeping
  private final int[] boundsMin = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
  private final int[] boundsMax = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };

  public SpatialGrid(float cellSize) {
    if (cellSize <= 0f) {
      throw new IllegalArgumentException("cellSize must be positive");
    }
    this.cellSize = cellSize;
  }

  public float cellSize() {
    return this.cellSize;
  }

  /** Registers (or re-registers) {@code id}. */
  public void insert(int id, float x, float y, float z, float radius) {
    remove(id);
    while (this.membership.size() <= id) {
      this.membership.add(null);
      this.isLarge.add(Boolean.FALSE);
    }
    if (radius > this.cellSize) {
      this.large.add(id);
      this.isLarge.set(id, Boolean.TRUE);
      return;
    }
    final int x0 = cell(x - radius);
    final int x1 = cell(x + radius);
    final int y0 = cell(y - radius);
    final int y1 = cell(y + radius);
    final int z0 = cell(z - radius);
    final int z1 = cell(z + radius);
    this.boundsMin[0] = Math.min(this.boundsMin[0], x0);
    this.boundsMin[1] = Math.min(this.boundsMin[1], y0);
    this.boundsMin[2] = Math.min(this.boundsMin[2], z0);
    this.boundsMax[0] = Math.max(this.boundsMax[0], x1);
    this.boundsMax[1] = Math.max(this.boundsMax[1], y1);
    this.boundsMax[2] = Math.max(this.boundsMax[2], z1);
    final long[] keys = new long[(x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)];
    int n = 0;
    for (int cx = x0; cx <= x1; cx++) {
      for (int cy = y0; cy <= y1; cy++) {
        for (int cz = z0; cz <= z1; cz++) {
          final long key = key(cx, cy, cz);
          this.cells.computeIfAbsent(key, k -> new IntList()).add(id);
          keys[n++] = key;
        }
      }
    }
    this.membership.set(id, keys);
  }

  public void remove(int id) {
    if (id < 0 || id >= this.membership.size()) {
      return;
    }
    if (this.isLarge.get(id)) {
      this.large.removeValue(id);
      this.isLarge.set(id, Boolean.FALSE);
      return;
    }
    final long[] keys = this.membership.get(id);
    if (keys != null) {
      for (long key : keys) {
        final IntList list = this.cells.get(key);
        if (list != null) {
          list.removeValue(id);
          if (list.size() == 0) {
            this.cells.remove(key);
          }
        }
      }
      this.membership.set(id, null);
    }
  }

  public void clear() {
    this.cells.clear();
    this.large.clear();
    this.membership.clear();
    this.isLarge.clear();
    java.util.Arrays.fill(this.boundsMin, Integer.MAX_VALUE);
    java.util.Arrays.fill(this.boundsMax, Integer.MIN_VALUE);
  }

  /** Ids of spheres too large for the grid; callers must test them against everything. */
  public IntList large() {
    return this.large;
  }

  public int occupiedCells() {
    return this.cells.size();
  }

  /**
   * Visits the occupied cells that may overlap the box. When the box covers fewer grid cells than are occupied, only
   * those coordinates are looked up (clamped to the extent of everything ever inserted); otherwise every occupied
   * cell is visited. The visitor must still test the cell bounds it receives: cells outside the box may be visited.
   */
  public void forEachCellInBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
      CellVisitor visitor) {
    if (this.cells.isEmpty()) {
      return;
    }
    final int x0 = Math.max(cell(minX), this.boundsMin[0]);
    final int y0 = Math.max(cell(minY), this.boundsMin[1]);
    final int z0 = Math.max(cell(minZ), this.boundsMin[2]);
    final int x1 = Math.min(cell(maxX), this.boundsMax[0]);
    final int y1 = Math.min(cell(maxY), this.boundsMax[1]);
    final int z1 = Math.min(cell(maxZ), this.boundsMax[2]);
    if (x1 < x0 || y1 < y0 || z1 < z0) {
      return;
    }
    final long count = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
    if (count >= this.cells.size()) {
      forEachCell(visitor);
      return;
    }
    for (int cx = x0; cx <= x1; cx++) {
      for (int cy = y0; cy <= y1; cy++) {
        for (int cz = z0; cz <= z1; cz++) {
          final IntList list = this.cells.get(key(cx, cy, cz));
          if (list != null) {
            final float bx = cx * this.cellSize;
            final float by = cy * this.cellSize;
            final float bz = cz * this.cellSize;
            visitor.visit(list, bx, by, bz, bx + this.cellSize, by + this.cellSize, bz + this.cellSize);
          }
        }
      }
    }
  }

  public void forEachCell(CellVisitor visitor) {
    for (Map.Entry<Long, IntList> e : this.cells.entrySet()) {
      final long k = e.getKey();
      final float minX = decodeX(k) * this.cellSize;
      final float minY = decodeY(k) * this.cellSize;
      final float minZ = decodeZ(k) * this.cellSize;
      visitor.visit(e.getValue(), minX, minY, minZ, minX + this.cellSize, minY + this.cellSize,
          minZ + this.cellSize);
    }
  }

  private int cell(float v) {
    return (int) Math.floor(v / this.cellSize);
  }

  private static final long MASK = 0x1FFFFFL; // 21 bits per axis
  private static final int BIAS = 1 << 20;

  private static long key(int x, int y, int z) {
    return ((long) (x + BIAS) & MASK) << 42 | ((long) (y + BIAS) & MASK) << 21 | ((long) (z + BIAS) & MASK);
  }

  private static int decodeX(long k) {
    return (int) ((k >> 42) & MASK) - BIAS;
  }

  private static int decodeY(long k) {
    return (int) ((k >> 21) & MASK) - BIAS;
  }

  private static int decodeZ(long k) {
    return (int) (k & MASK) - BIAS;
  }
}
