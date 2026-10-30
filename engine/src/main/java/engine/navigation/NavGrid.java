package engine.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.joml.Vector3f;

import engine.ecs.Scene;
import engine.physics.CharacterController;
import engine.physics.Collider;
import engine.physics.MeshCollider;
import engine.physics.PhysicsSystem;
import engine.physics.Rigidbody;

/**
 * Where an agent can walk, as a grid of cells over the XZ plane, each holding up to {@link #MAX_FLOORS} walkable
 * heights (a floor under a bridge and the bridge itself, the storeys of a building). Baked from the colliders: rays
 * go down every column and stop at each surface; a surface is walkable if it is flat enough and an upright capsule of
 * the agent's size fits on it. Two cells next to each other (diagonals too, but not around corners) are connected if
 * their floors differ by at most the step height. {@link #findPath} runs A* over the cells and straightens the result.
 *
 * <p>
 * Only static colliders count by default (no rigidbody, or a kinematic one, and no character), so crates and the
 * agents themselves do not punch holes into the grid. Regions can be baked again when the level changes ({@link #rebake}), and cells blocked or freed by game
 * code ({@link #setBlocked}), for doors and the like.
 */
public class NavGrid {
  /** Walkable floors kept per cell; lower ones beyond this are dropped. */
  public static final int MAX_FLOORS = 4;

  /** How an agent's walkable space is measured when baking. */
  public static final class Settings {
    public float cellSize = 0.5f;
    public float agentRadius = 0.3f;
    public float agentHeight = 1.8f;
    public float stepHeight = 0.35f;
    public float maxSlopeDegrees = 50f;
    /** Layers that are ground or obstacles ({@link Collider#layer}). */
    public int layerMask = ~0;
    /**
     * Which colliders shape the grid; by default the static ones (no rigidbody, or a kinematic one) that are not
     * characters.
     */
    public Predicate<Collider> include = c -> {
      final var body = c.entity().findOrNull(Rigidbody.class);
      return (body == null || body.kinematic) && !c.entity().has(CharacterController.class);
    };
  }

  private final Settings settings;
  private final float minX;
  private final float minY;
  private final float minZ;
  private final float maxY;
  private final int width;
  private final int depth;
  /** Floor height per (cell, floor), NaN where there is none; floors of a cell are sorted from the top down. */
  private final float[] heights;
  private final boolean[] blocked;

  // A* state, reused between searches
  private float[] g;
  private int[] parent;
  private int[] visited;
  private int search;
  private final NodeHeap open = new NodeHeap();

  /**
   * An empty grid over the box from ({@code minX}, {@code minY}, {@code minZ}) to ({@code maxX}, {@code maxY},
   * {@code maxZ}); call {@link #bake} to fill it.
   */
  public NavGrid(Settings settings, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    if (settings.cellSize <= 0f || maxX <= minX || maxZ <= minZ || maxY <= minY) {
      throw new IllegalArgumentException("empty navigation grid");
    }
    this.settings = settings;
    this.minX = minX;
    this.minY = minY;
    this.minZ = minZ;
    this.maxY = maxY;
    this.width = Math.max(1, (int) Math.ceil((maxX - minX) / settings.cellSize));
    this.depth = Math.max(1, (int) Math.ceil((maxZ - minZ) / settings.cellSize));
    final int nodes = this.width * this.depth * MAX_FLOORS;
    this.heights = new float[nodes];
    Arrays.fill(this.heights, Float.NaN);
    this.blocked = new boolean[nodes];
  }

  /** Bakes a grid over the box (see the constructor) from the scene's colliders. */
  public static NavGrid bake(PhysicsSystem physics, Scene scene, Settings settings, float minX, float minY,
      float minZ, float maxX, float maxY, float maxZ) {
    final var grid = new NavGrid(settings, minX, minY, minZ, maxX, maxY, maxZ);
    grid.rebake(physics, scene, minX, minZ, maxX, maxZ);
    return grid;
  }

  public Settings settings() {
    return this.settings;
  }

  public int width() {
    return this.width;
  }

  public int depth() {
    return this.depth;
  }

  /** Number of walkable floors over all cells. */
  public int walkableCount() {
    int n = 0;
    for (int i = 0; i < this.heights.length; i++) {
      if (walkable(i)) {
        n++;
      }
    }
    return n;
  }

  /**
   * Measures the cells whose centres lie in the XZ rectangle again (after the level changed there). Blocked marks of
   * those cells are cleared.
   */
  public void rebake(PhysicsSystem physics, Scene scene, float x0, float z0, float x1, float z1) {
    physics.syncQueries(scene);
    final var s = this.settings;
    final float cos = (float) Math.cos(Math.toRadians(s.maxSlopeDegrees)) - 1e-4f;
    final Predicate<Collider> solid = c -> !c.trigger && (s.layerMask & c.layerBit()) != 0 && s.include.test(c);
    final List<Collider> passed = new ArrayList<>();
    final Predicate<Collider> ray = c -> solid.test(c) && !passed.contains(c);
    final var origin = new Vector3f();
    final var down = new Vector3f(0f, -1f, 0f);
    final var a = new Vector3f();
    final var b = new Vector3f();
    final float r = s.agentRadius;
    final float bottom = Math.max(s.stepHeight, 0.05f) + r; // capsule ends above the bumps it may step over
    final float top = Math.max(bottom, s.agentHeight - r);
    final int cx0 = Math.max(0, (int) Math.ceil((x0 - this.minX) / s.cellSize - 0.5f));
    final int cz0 = Math.max(0, (int) Math.ceil((z0 - this.minZ) / s.cellSize - 0.5f));
    final int cx1 = Math.min(this.width - 1, (int) Math.floor((x1 - this.minX) / s.cellSize - 0.5f));
    final int cz1 = Math.min(this.depth - 1, (int) Math.floor((z1 - this.minZ) / s.cellSize - 0.5f));
    for (int cz = cz0; cz <= cz1; cz++) {
      for (int cx = cx0; cx <= cx1; cx++) {
        final int base = (cz * this.width + cx) * MAX_FLOORS;
        Arrays.fill(this.heights, base, base + MAX_FLOORS, Float.NaN);
        Arrays.fill(this.blocked, base, base + MAX_FLOORS, false);
        final float x = this.minX + (cx + 0.5f) * s.cellSize;
        final float z = this.minZ + (cz + 0.5f) * s.cellSize;
        passed.clear();
        float y = this.maxY;
        int floors = 0;
        while (floors < MAX_FLOORS && y > this.minY) {
          final var hit = physics.raycast(scene, origin.set(x, y, z), down, y - this.minY, ray);
          if (hit.isEmpty()) {
            break;
          }
          final var h = hit.get();
          final float surface = h.point().y;
          if (!(h.collider() instanceof MeshCollider)) {
            passed.add(h.collider()); // a convex solid has one top along a vertical line; look below it next
          }
          y = surface - 1e-3f;
          if (h.distance() <= 0f || h.normal().y < cos) {
            continue; // started inside something, or too steep
          }
          a.set(x, surface + bottom, z);
          b.set(x, surface + top, z);
          // the surface must be open above (not the floor inside a box seen through its top) and the agent fit
          if (physics.overlapSphere(scene, origin.set(x, surface + 0.03f, z), 0.01f, solid).isEmpty()
              && physics.overlapCapsule(scene, a, b, r, solid).isEmpty()) {
            this.heights[base + floors++] = surface;
          }
        }
      }
    }
  }

  /** Blocks or frees the floor nearest to {@code position} (a door, a fire); returns false if there is none. */
  public boolean setBlocked(Vector3f position, boolean value) {
    final int node = nearestNode(position, 0);
    if (node < 0) {
      return false;
    }
    this.blocked[node] = value;
    return true;
  }

  /** Blocks or frees every floor in the XZ rectangle. */
  public void setBlocked(float x0, float z0, float x1, float z1, boolean value) {
    for (int cz = Math.max(0, cellZ(z0)); cz <= Math.min(this.depth - 1, cellZ(z1)); cz++) {
      for (int cx = Math.max(0, cellX(x0)); cx <= Math.min(this.width - 1, cellX(x1)); cx++) {
        final int base = (cz * this.width + cx) * MAX_FLOORS;
        for (int f = 0; f < MAX_FLOORS; f++) {
          this.blocked[base + f] = value && !Float.isNaN(this.heights[base + f]);
        }
      }
    }
  }

  /** True if an agent can stand at {@code position} (on a floor of its cell within the step height below or above). */
  public boolean isWalkable(Vector3f position) {
    return nodeAt(cellX(position.x), cellZ(position.z), position.y, this.settings.stepHeight) >= 0;
  }

  /** Height of the floor under {@code position} within a step of its height, or NaN if there is none. */
  public float floorHeight(Vector3f position) {
    final int node = nodeAt(cellX(position.x), cellZ(position.z), position.y, this.settings.stepHeight);
    return node < 0 ? Float.NaN : this.heights[node];
  }

  /**
   * The walkable point nearest to {@code position} within {@code searchRadius} metres (on the XZ plane), or null.
   */
  public Vector3f nearestWalkable(Vector3f position, float searchRadius) {
    final int node = nearestNode(position, (int) Math.ceil(searchRadius / this.settings.cellSize));
    return node < 0 ? null : point(node, new Vector3f());
  }

  /**
   * A path from {@code from} to {@code to} as points on the floors (the first is where {@code from} stands, the last
   * where {@code to} does), straightened so it turns only where it must; empty if there is none. Both ends are moved
   * to the nearest walkable cell within two cells.
   */
  public List<Vector3f> findPath(Vector3f from, Vector3f to) {
    final List<Vector3f> out = new ArrayList<>();
    final int start = nearestNode(from, 2);
    final int goal = nearestNode(to, 2);
    if (start < 0 || goal < 0) {
      return out;
    }
    final int[] nodes = search(start, goal);
    if (nodes == null) {
      return out;
    }
    // straighten: from each kept point, skip ahead as far as a straight walk reaches
    int i = 0;
    out.add(point(nodes[0], new Vector3f()));
    while (i < nodes.length - 1) {
      int j = nodes.length - 1;
      while (j > i + 1 && !straight(nodes[i], nodes[j])) {
        j--;
      }
      out.add(point(nodes[j], new Vector3f()));
      i = j;
    }
    if (out.size() == 1) {
      out.add(point(goal, new Vector3f())); // start and goal in the same cell
    }
    return out;
  }

  // ---- cells and nodes -----------------------------------------------------------------------------------------

  private int cellX(float x) {
    return (int) Math.floor((x - this.minX) / this.settings.cellSize);
  }

  private int cellZ(float z) {
    return (int) Math.floor((z - this.minZ) / this.settings.cellSize);
  }

  private boolean walkable(int node) {
    return !Float.isNaN(this.heights[node]) && !this.blocked[node];
  }

  private Vector3f point(int node, Vector3f dest) {
    final int cell = node / MAX_FLOORS;
    final float cs = this.settings.cellSize;
    return dest.set(this.minX + (cell % this.width + 0.5f) * cs, this.heights[node],
        this.minZ + (cell / this.width + 0.5f) * cs);
  }

  /** The floor of cell (cx, cz) closest to {@code y} within {@code tolerance}, or -1. */
  private int nodeAt(int cx, int cz, float y, float tolerance) {
    if (cx < 0 || cz < 0 || cx >= this.width || cz >= this.depth) {
      return -1;
    }
    final int base = (cz * this.width + cx) * MAX_FLOORS;
    int best = -1;
    float bestDy = tolerance;
    for (int f = 0; f < MAX_FLOORS; f++) {
      if (walkable(base + f)) {
        final float dy = Math.abs(this.heights[base + f] - y);
        if (dy <= bestDy) {
          bestDy = dy;
          best = base + f;
        }
      }
    }
    return best;
  }

  /**
   * The floor under (or a little above) {@code p}, searching rings of cells up to {@code rings} away: the nearest
   * on the XZ plane, preferring floors at or below {@code p}'s height.
   */
  private int nearestNode(Vector3f p, int rings) {
    final int cx = cellX(p.x);
    final int cz = cellZ(p.z);
    final float cs = this.settings.cellSize;
    int best = -1;
    float bestCost = Float.MAX_VALUE;
    for (int ring = 0; ring <= rings && best < 0; ring++) {
      for (int z = cz - ring; z <= cz + ring; z++) {
        for (int x = cx - ring; x <= cx + ring; x++) {
          if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != ring || x < 0 || z < 0 || x >= this.width
              || z >= this.depth) {
            continue;
          }
          final int base = (z * this.width + x) * MAX_FLOORS;
          for (int f = 0; f < MAX_FLOORS; f++) {
            if (!walkable(base + f)) {
              continue;
            }
            final float dy = p.y - this.heights[base + f];
            // above the agent's head is far away, below it is where it would stand
            final float vertical = dy >= -this.settings.stepHeight ? Math.abs(dy) * 0.1f : -dy * 10f;
            final float px = this.minX + (x + 0.5f) * cs - p.x;
            final float pz = this.minZ + (z + 0.5f) * cs - p.z;
            final float cost = px * px + pz * pz + vertical * vertical;
            if (cost < bestCost) {
              bestCost = cost;
              best = base + f;
            }
          }
        }
      }
    }
    return best;
  }

  /** The floor of the cell next door reachable from {@code node} by a step, or -1. */
  private int neighbour(int node, int dx, int dz) {
    final int cell = node / MAX_FLOORS;
    return nodeAt(cell % this.width + dx, cell / this.width + dz, this.heights[node], this.settings.stepHeight);
  }

  private static final int[] DX = { 1, -1, 0, 0, 1, 1, -1, -1 };
  private static final int[] DZ = { 0, 0, 1, -1, 1, -1, 1, -1 };

  /** A* from start to goal; the nodes of the path in order, or null. */
  private int[] search(int start, int goal) {
    final int n = this.heights.length;
    if (this.g == null) {
      this.g = new float[n];
      this.parent = new int[n];
      this.visited = new int[n];
    }
    if (++this.search == 0) { // wrapped: forget every old mark
      Arrays.fill(this.visited, 0);
      this.search = 1;
    }
    final var goalPoint = point(goal, new Vector3f());
    final var p = new Vector3f();
    final var q = new Vector3f();
    this.open.clear();
    this.g[start] = 0f;
    this.parent[start] = -1;
    this.visited[start] = this.search;
    this.open.push(start, heuristic(point(start, p), goalPoint));
    final float cs = this.settings.cellSize;
    while (!this.open.isEmpty()) {
      final int node = this.open.pop();
      if (node == goal) {
        int count = 0;
        for (int k = node; k >= 0; k = this.parent[k]) {
          count++;
        }
        final int[] path = new int[count];
        for (int k = node; k >= 0; k = this.parent[k]) {
          path[--count] = k;
        }
        return path;
      }
      if (this.visited[node] == -this.search) {
        continue; // closed already (a stale heap entry)
      }
      this.visited[node] = -this.search;
      point(node, p);
      for (int d = 0; d < 8; d++) {
        final int next = neighbour(node, DX[d], DZ[d]);
        if (next < 0 || this.visited[next] == -this.search) {
          continue;
        }
        if (d >= 4 && (neighbour(node, DX[d], 0) < 0 || neighbour(node, 0, DZ[d]) < 0)) {
          continue; // no cutting corners
        }
        point(next, q);
        final float dy = q.y - p.y;
        final float step = (d >= 4 ? 1.41421356f : 1f) * cs;
        final float cost = this.g[node] + (float) Math.sqrt(step * step + dy * dy);
        if (this.visited[next] != this.search || cost < this.g[next]) {
          this.visited[next] = this.search;
          this.g[next] = cost;
          this.parent[next] = node;
          this.open.push(next, cost + heuristic(q, goalPoint));
        }
      }
    }
    return null;
  }

  /** Octile distance on the grid, plus the climb: never more than the real cost. */
  private static float heuristic(Vector3f a, Vector3f b) {
    final float dx = Math.abs(a.x - b.x);
    final float dz = Math.abs(a.z - b.z);
    final float flat = Math.max(dx, dz) + 0.41421356f * Math.min(dx, dz);
    final float dy = a.y - b.y;
    return (float) Math.sqrt(flat * flat + dy * dy);
  }

  /**
   * True if an agent can walk straight from node a to node b: every cell the line (widened to the agent's sides)
   * passes over has a floor within a step of the last one.
   */
  private boolean straight(int a, int b) {
    final var pa = point(a, new Vector3f());
    final var pb = point(b, new Vector3f());
    final float dx = pb.x - pa.x;
    final float dz = pb.z - pa.z;
    final float len = (float) Math.sqrt(dx * dx + dz * dz);
    final float cs = this.settings.cellSize;
    final int steps = Math.max(1, (int) Math.ceil(len / (cs * 0.25f)));
    // the centre line and two lines at the agent's sides, so a straight path does not graze corners
    final float side = Math.min(this.settings.agentRadius, 0.49f * cs);
    final float nx = len > 0f ? -dz / len * side : 0f;
    final float nz = len > 0f ? dx / len * side : 0f;
    for (int line = -1; line <= 1; line++) {
      float y = pa.y;
      for (int i = 0; i <= steps; i++) {
        final float t = (float) i / steps;
        final int node = nodeAt(cellX(pa.x + dx * t + nx * line), cellZ(pa.z + dz * t + nz * line), y,
            this.settings.stepHeight);
        if (node < 0) {
          return false;
        }
        y = this.heights[node];
      }
      if (Math.abs(y - pb.y) > this.settings.stepHeight) {
        return false;
      }
    }
    return true;
  }

  /** A binary min-heap of node indices by priority. */
  private static final class NodeHeap {
    private int[] nodes = new int[64];
    private float[] keys = new float[64];
    private int size;

    void clear() {
      this.size = 0;
    }

    boolean isEmpty() {
      return this.size == 0;
    }

    void push(int node, float key) {
      if (this.size == this.nodes.length) {
        this.nodes = Arrays.copyOf(this.nodes, this.size * 2);
        this.keys = Arrays.copyOf(this.keys, this.size * 2);
      }
      int i = this.size++;
      while (i > 0) {
        final int up = (i - 1) / 2;
        if (this.keys[up] <= key) {
          break;
        }
        this.nodes[i] = this.nodes[up];
        this.keys[i] = this.keys[up];
        i = up;
      }
      this.nodes[i] = node;
      this.keys[i] = key;
    }

    int pop() {
      final int top = this.nodes[0];
      final int last = this.nodes[--this.size];
      final float key = this.keys[this.size];
      int i = 0;
      while (true) {
        int child = 2 * i + 1;
        if (child >= this.size) {
          break;
        }
        if (child + 1 < this.size && this.keys[child + 1] < this.keys[child]) {
          child++;
        }
        if (this.keys[child] >= key) {
          break;
        }
        this.nodes[i] = this.nodes[child];
        this.keys[i] = this.keys[child];
        i = child;
      }
      this.nodes[i] = last;
      this.keys[i] = key;
      return top;
    }
  }
}
