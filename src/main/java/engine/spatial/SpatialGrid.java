package engine.spatial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;

/**
 * Uniform grid over bounding spheres, keyed by integer ids chosen by the caller. A sphere is registered in every cell
 * its bounding box touches; spheres bigger than one cell (radius &gt; cellSize) go to a separate "large" list instead,
 * so a ground plane does not fill thousands of cells. Used for the physics broadphase (rebuilt every step) and for
 * culling static render geometry (persistent, updated incrementally).
 *
 * <p>
 * Cells live in a primitive-keyed hash map and cell lists and per-id bookkeeping arrays are recycled, so rebuilding the
 * grid every step ({@link #clear()} followed by inserts) allocates nothing once it has warmed up.
 */
public final class SpatialGrid {

  /** Growable list of ints without boxing. */
  public static final class IntList {
    private int[] data = new int[4];
    private int size;
    private int tag;

    /** A value the grid's owner may attach to a cell (e.g. occlusion state); reset when the cell is recycled. */
    public int tag() {
      return this.tag;
    }

    public void setTag(int tag) {
      this.tag = tag;
    }

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
      this.tag = 0;
    }
  }

  /** Receives each occupied cell with its world-space bounds. */
  @FunctionalInterface
  public interface CellVisitor {
    void visit(IntList ids, float minX, float minY, float minZ, float maxX, float maxY, float maxZ);
  }

  /** Open-addressing long to IntList map (linear probing, backward-shift deletion): no key boxing. */
  private static final class CellMap {
    private long[] keys = new long[64];
    private IntList[] vals = new IntList[64];
    private int size;

    private static int hash(long k) {
      return (int) ((k * 0x9E3779B97F4A7C15L) >>> 32);
    }

    int size() {
      return this.size;
    }

    IntList get(long key) {
      final int mask = this.keys.length - 1;
      int i = hash(key) & mask;
      while (this.vals[i] != null) {
        if (this.keys[i] == key) {
          return this.vals[i];
        }
        i = (i + 1) & mask;
      }
      return null;
    }

    void put(long key, IntList list) {
      if ((this.size + 1) * 2 > this.keys.length) {
        grow();
      }
      final int mask = this.keys.length - 1;
      int i = hash(key) & mask;
      while (this.vals[i] != null) {
        i = (i + 1) & mask;
      }
      this.keys[i] = key;
      this.vals[i] = list;
      this.size++;
    }

    void remove(long key) {
      final int mask = this.keys.length - 1;
      int i = hash(key) & mask;
      while (this.vals[i] != null && this.keys[i] != key) {
        i = (i + 1) & mask;
      }
      if (this.vals[i] == null) {
        return;
      }
      this.vals[i] = null;
      this.size--;
      int j = i;
      while (true) {
        j = (j + 1) & mask;
        if (this.vals[j] == null) {
          return;
        }
        final int home = hash(this.keys[j]) & mask;
        final boolean stays = i <= j ? i < home && home <= j : i < home || home <= j;
        if (!stays) {
          this.keys[i] = this.keys[j];
          this.vals[i] = this.vals[j];
          this.vals[j] = null;
          i = j;
        }
      }
    }

    /** Empties the map, handing the lists to {@code pool}. */
    void clear(List<IntList> pool) {
      if (this.size == 0) {
        return;
      }
      for (int i = 0; i < this.vals.length; i++) {
        if (this.vals[i] != null) {
          this.vals[i].clear();
          pool.add(this.vals[i]);
          this.vals[i] = null;
        }
      }
      this.size = 0;
    }

    private void grow() {
      final long[] oldKeys = this.keys;
      final var oldVals = this.vals;
      this.keys = new long[oldKeys.length * 2];
      this.vals = new IntList[oldKeys.length * 2];
      this.size = 0;
      for (int i = 0; i < oldKeys.length; i++) {
        if (oldVals[i] != null) {
          put(oldKeys[i], oldVals[i]);
        }
      }
    }
  }

  @Getter private final float cellSize;
  private final CellMap cells = new CellMap();
  private final List<IntList> pool = new ArrayList<>();
  /** Ids of spheres too large for the grid; callers must test them against everything. */
  @Getter private final IntList large = new IntList();
  // per id: the cell keys it is registered in (arrays are reused) and whether it went to the large list
  private long[][] memberKeys = new long[16][];
  private int[] memberCount = new int[16];
  private boolean[] isLarge = new boolean[16];
  private int idLimit; // ids >= idLimit have not been inserted since the last clear()
  // extent of all cells ever used since the last clear(); shrinking on removal is not worth the bookkeeping
  private final int[] boundsMin = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
  private final int[] boundsMax = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };

  public SpatialGrid(float cellSize) {
    if (cellSize <= 0f) {
      throw new IllegalArgumentException("cellSize must be positive");
    }
    this.cellSize = cellSize;
  }


  /** Registers (or re-registers) {@code id}. */
  public void insert(int id, float x, float y, float z, float radius) {
    remove(id);
    if (id >= this.memberKeys.length) {
      final int n = Math.max(id + 1, this.memberKeys.length * 2);
      this.memberKeys = Arrays.copyOf(this.memberKeys, n);
      this.memberCount = Arrays.copyOf(this.memberCount, n);
      this.isLarge = Arrays.copyOf(this.isLarge, n);
    }
    this.idLimit = Math.max(this.idLimit, id + 1);
    if (radius > this.cellSize) {
      this.large.add(id);
      this.isLarge[id] = true;
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
    final int cellCount = (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
    long[] keys = this.memberKeys[id];
    if (keys == null || keys.length < cellCount) {
      keys = new long[cellCount];
      this.memberKeys[id] = keys;
    }
    int n = 0;
    for (int cx = x0; cx <= x1; cx++) {
      for (int cy = y0; cy <= y1; cy++) {
        for (int cz = z0; cz <= z1; cz++) {
          final long key = key(cx, cy, cz);
          var list = this.cells.get(key);
          if (list == null) {
            list = this.pool.isEmpty() ? new IntList() : this.pool.remove(this.pool.size() - 1);
            this.cells.put(key, list);
          }
          list.add(id);
          keys[n++] = key;
        }
      }
    }
    this.memberCount[id] = n;
  }

  public void remove(int id) {
    if (id < 0 || id >= this.idLimit) {
      return;
    }
    if (this.isLarge[id]) {
      this.large.removeValue(id);
      this.isLarge[id] = false;
      return;
    }
    final long[] keys = this.memberKeys[id];
    final int count = this.memberCount[id];
    for (int i = 0; i < count; i++) {
      final long key = keys[i];
      final var list = this.cells.get(key);
      if (list != null) {
        list.removeValue(id);
        if (list.size() == 0) {
          this.cells.remove(key);
          list.clear();
          this.pool.add(list);
        }
      }
    }
    this.memberCount[id] = 0;
  }

  public void clear() {
    this.cells.clear(this.pool);
    this.large.clear();
    Arrays.fill(this.memberCount, 0, this.idLimit, 0);
    Arrays.fill(this.isLarge, 0, this.idLimit, false);
    this.idLimit = 0;
    Arrays.fill(this.boundsMin, Integer.MAX_VALUE);
    Arrays.fill(this.boundsMax, Integer.MIN_VALUE);
  }

  public int occupiedCells() {
    return this.cells.size();
  }

  /** Integer cell coordinate of a world coordinate. */
  public int cellCoordinate(float v) {
    return cell(v);
  }

  /** Ids registered in a cell (do not modify), or null if the cell is empty. */
  public IntList cellAt(int cx, int cy, int cz) {
    return this.cells.get(key(cx, cy, cz));
  }

  /**
   * Lowest cell coordinate along an axis (0 x, 1 y, 2 z) of everything inserted since the last clear; together with
   * {@link #maxCell} it bounds all occupied cells. Meaningless while {@link #occupiedCells()} is 0.
   */
  public int minCell(int axis) {
    return this.boundsMin[axis];
  }

  public int maxCell(int axis) {
    return this.boundsMax[axis];
  }

  /**
   * Visits the occupied cells that may overlap the box. When the box covers fewer grid cells than are occupied, only
   * those coordinates are looked up (clamped to the extent of everything ever inserted); otherwise every occupied
   * cell is visited. The visitor must still test the cell bounds it receives: cells outside the box may be visited.
   */
  public void forEachCellInBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
      CellVisitor visitor) {
    if (this.cells.size() == 0) {
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
          final var list = this.cells.get(key(cx, cy, cz));
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
    final var m = this.cells;
    for (int i = 0; i < m.vals.length; i++) {
      final var list = m.vals[i];
      if (list != null) {
        final long k = m.keys[i];
        final float minX = decodeX(k) * this.cellSize;
        final float minY = decodeY(k) * this.cellSize;
        final float minZ = decodeZ(k) * this.cellSize;
        visitor.visit(list, minX, minY, minZ, minX + this.cellSize, minY + this.cellSize, minZ + this.cellSize);
      }
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
