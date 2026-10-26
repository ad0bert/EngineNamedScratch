package engine.physics;

import engine.spatial.SpatialGrid;

/**
 * Bounding volume hierarchy over the triangles of a {@link MeshCollider}: a binary tree of axis-aligned boxes, split at
 * the median of the longest axis, with up to {@link #LEAF_SIZE} triangles per leaf. Stored in flat arrays (built
 * once, queried without allocating).
 */
final class TriangleTree {
  private static final int LEAF_SIZE = 4;

  private float[] bounds = new float[0]; // per node: min xyz, max xyz
  private int[] first = new int[0]; // leaf: first index into order; inner: index of the left child
  private int[] count = new int[0]; // leaf: triangle count; inner: 0 (the right child follows the left subtree)
  private int[] right = new int[0];
  private int[] order = new int[0]; // triangle indices, grouped by leaf
  private int nodes;
  private float[] centroids = new float[0];
  private int[] stack = new int[64];

  /**
   * Builds the tree over triangles given as world positions ({@code vertices}, xyz per vertex) and
   * {@code indices} (three per triangle).
   */
  void build(float[] vertices, int[] indices) {
    final int tris = indices.length / 3;
    if (this.order.length != tris) {
      this.order = new int[tris];
      this.centroids = new float[tris * 3];
      final int maxNodes = Math.max(1, 2 * tris);
      this.bounds = new float[maxNodes * 6];
      this.first = new int[maxNodes];
      this.count = new int[maxNodes];
      this.right = new int[maxNodes];
    }
    for (int t = 0; t < tris; t++) {
      this.order[t] = t;
      for (int k = 0; k < 3; k++) {
        float sum = 0f;
        for (int v = 0; v < 3; v++) {
          sum += vertices[indices[t * 3 + v] * 3 + k];
        }
        this.centroids[t * 3 + k] = sum / 3f;
      }
    }
    this.nodes = 0;
    if (tris > 0) {
      buildNode(vertices, indices, 0, tris);
    }
  }

  private int buildNode(float[] v, int[] idx, int from, int to) {
    final int node = this.nodes++;
    final int b = node * 6;
    this.bounds[b] = this.bounds[b + 1] = this.bounds[b + 2] = Float.MAX_VALUE;
    this.bounds[b + 3] = this.bounds[b + 4] = this.bounds[b + 5] = -Float.MAX_VALUE;
    float cmin0 = Float.MAX_VALUE;
    float cmin1 = Float.MAX_VALUE;
    float cmin2 = Float.MAX_VALUE;
    float cmax0 = -Float.MAX_VALUE;
    float cmax1 = -Float.MAX_VALUE;
    float cmax2 = -Float.MAX_VALUE;
    for (int i = from; i < to; i++) {
      final int t = this.order[i];
      for (int k = 0; k < 3; k++) {
        final int vi = idx[t * 3 + k] * 3;
        for (int a = 0; a < 3; a++) {
          this.bounds[b + a] = Math.min(this.bounds[b + a], v[vi + a]);
          this.bounds[b + 3 + a] = Math.max(this.bounds[b + 3 + a], v[vi + a]);
        }
      }
      cmin0 = Math.min(cmin0, this.centroids[t * 3]);
      cmin1 = Math.min(cmin1, this.centroids[t * 3 + 1]);
      cmin2 = Math.min(cmin2, this.centroids[t * 3 + 2]);
      cmax0 = Math.max(cmax0, this.centroids[t * 3]);
      cmax1 = Math.max(cmax1, this.centroids[t * 3 + 1]);
      cmax2 = Math.max(cmax2, this.centroids[t * 3 + 2]);
    }
    final int n = to - from;
    if (n <= LEAF_SIZE) {
      this.first[node] = from;
      this.count[node] = n;
      return node;
    }
    final float ex = cmax0 - cmin0;
    final float ey = cmax1 - cmin1;
    final float ez = cmax2 - cmin2;
    final int axis = ex >= ey && ex >= ez ? 0 : ey >= ez ? 1 : 2;
    final int mid = (from + to) >>> 1;
    select(from, to - 1, mid, axis);
    this.count[node] = 0;
    this.first[node] = buildNode(v, idx, from, mid);
    this.right[node] = buildNode(v, idx, mid, to);
    return node;
  }

  /** Quickselect: puts the triangle with the k-th smallest centroid (on the axis) at k. */
  private void select(int lo, int hi, int k, int axis) {
    while (hi > lo) {
      final float pivot = this.centroids[this.order[(lo + hi) >>> 1] * 3 + axis];
      int i = lo;
      int j = hi;
      while (i <= j) {
        while (this.centroids[this.order[i] * 3 + axis] < pivot) {
          i++;
        }
        while (this.centroids[this.order[j] * 3 + axis] > pivot) {
          j--;
        }
        if (i <= j) {
          final int t = this.order[i];
          this.order[i] = this.order[j];
          this.order[j] = t;
          i++;
          j--;
        }
      }
      if (k <= j) {
        hi = j;
      } else if (k >= i) {
        lo = i;
      } else {
        return;
      }
    }
  }

  /** Bounds of everything: {min xyz, max xyz}; meaningless for an empty tree. */
  void rootBounds(float[] out) {
    System.arraycopy(this.bounds, 0, out, 0, 6);
  }

  boolean isEmpty() {
    return this.nodes == 0;
  }

  /** Adds the triangles whose boxes overlap the given box to {@code out}. */
  void query(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, SpatialGrid.IntList out) {
    if (this.nodes == 0) {
      return;
    }
    this.top = 0;
    push(0);
    while (this.top > 0) {
      final int node = this.stack[--this.top];
      final int b = node * 6;
      if (this.bounds[b] > maxX || this.bounds[b + 3] < minX || this.bounds[b + 1] > maxY
          || this.bounds[b + 4] < minY || this.bounds[b + 2] > maxZ || this.bounds[b + 5] < minZ) {
        continue;
      }
      if (this.count[node] > 0) {
        for (int i = this.first[node]; i < this.first[node] + this.count[node]; i++) {
          out.add(this.order[i]);
        }
      } else {
        push(this.first[node]);
        push(this.right[node]);
      }
    }
  }

  private int top;

  private void push(int node) {
    // the tree is balanced, but grow anyway rather than trust that
    if (this.top >= this.stack.length) {
      this.stack = java.util.Arrays.copyOf(this.stack, this.stack.length * 2);
    }
    this.stack[this.top++] = node;
  }

  /** Receives candidate triangles of a ray; returns the new maximum distance (to prune farther boxes). */
  @FunctionalInterface
  interface RayVisitor {
    float visit(int triangle, float maxDistance);
  }

  /** Visits the triangles whose boxes the ray (normalized direction) enters before {@code maxDistance}. */
  void ray(float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance, RayVisitor visitor) {
    if (this.nodes == 0) {
      return;
    }
    final float ix = 1f / dx;
    final float iy = 1f / dy;
    final float iz = 1f / dz;
    float best = maxDistance;
    this.top = 0;
    push(0);
    while (this.top > 0) {
      final int node = this.stack[--this.top];
      final int b = node * 6;
      float t0 = 0f;
      float t1 = best;
      float ta = (this.bounds[b] - ox) * ix;
      float tb = (this.bounds[b + 3] - ox) * ix;
      t0 = Math.max(t0, Math.min(ta, tb));
      t1 = Math.min(t1, Math.max(ta, tb));
      ta = (this.bounds[b + 1] - oy) * iy;
      tb = (this.bounds[b + 4] - oy) * iy;
      t0 = Math.max(t0, Math.min(ta, tb));
      t1 = Math.min(t1, Math.max(ta, tb));
      ta = (this.bounds[b + 2] - oz) * iz;
      tb = (this.bounds[b + 5] - oz) * iz;
      t0 = Math.max(t0, Math.min(ta, tb));
      t1 = Math.min(t1, Math.max(ta, tb));
      if (t0 > t1 || Float.isNaN(t0) || Float.isNaN(t1)) {
        continue;
      }
      if (this.count[node] > 0) {
        for (int i = this.first[node]; i < this.first[node] + this.count[node]; i++) {
          best = Math.min(best, visitor.visit(this.order[i], best));
        }
      } else {
        push(this.first[node]);
        push(this.right[node]);
      }
    }
  }
}
