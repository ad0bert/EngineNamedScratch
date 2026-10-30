package engine.render;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.ecs.Scene;
import engine.ecs.Transform;
import engine.spatial.SpatialGrid;
import lombok.Getter;

/**
 * Backend-independent part of drawing a scene: works out where every {@link MeshRenderer} is this frame, culls against
 * frustums and prepares draw batches. It is free of graphics calls (so it can be unit-tested and benchmarked), and it
 * avoids per-frame allocation and redundant work:
 * <ul>
 * <li>renderers whose transform did not change since the last fixed step reuse their cached matrices;</li>
 * <li>renderers marked {@link MeshRenderer#setStaticGeometry static} live in a persistent {@link SpatialGrid}, so
 * culling touches whole cells instead of individual objects, and they cost nothing per frame while off-screen. If a
 * static renderer is moved anyway, the change is noticed at the next fixed step (its transform is tracked) and its
 * cell registration is rebuilt;</li>
 * <li>objects are tested against their bounding sphere first and, if that passes, their world-space bounding box;</li>
 * <li>optionally, grid cells hidden behind other geometry in the last frame are skipped (occlusion culling): the
 * backend tests the {@link #occlusionCandidates() visible cells} against the depth buffer and reports back through
 * {@link OcclusionCell#setOccluded}.</li>
 * </ul>
 */
public final class RenderQueue {
  /** 16 floats model matrix + 9 floats normal matrix. */
  public static final int FLOATS_PER_INSTANCE = 25;

  public enum Pass {
    MAIN, SHADOW
  }

  private static final Comparator<MeshRenderer> BY_MESH = Comparator.comparingInt((MeshRenderer r) -> r.drawn.id());
  private static final Comparator<MeshRenderer> BY_MESH_MATERIAL = BY_MESH
      .thenComparingInt((MeshRenderer r) -> r.material.sortKey);
  private static final Comparator<MeshRenderer> FAR_TO_NEAR = (a, b) -> Float.compare(b.distSq, a.distSq);
  /** Static renderers are re-checked against their transforms this often, to catch moves without a fixed step. */
  private static final int VALIDATE_EVERY_FRAMES = 300;
  private static final int OCCLUDED = 1;

  /**
   * A grid cell of static geometry that was inside the view frustum in the last main-pass cull. The backend renders
   * its box with an occlusion query and reports whether any of it was visible. Instances are reused every frame.
   */
  public static final class OcclusionCell {
    private SpatialGrid.IntList cell;
    @Getter private float minX;
    @Getter private float minY;
    @Getter private float minZ;
    @Getter private float maxX;
    @Getter private float maxY;
    @Getter private float maxZ;

    /** Opaque identity of the cell, stable across frames while the cell stays occupied. */
    public Object key() {
      return this.cell;
    }

    /** Records the query result; the cell's objects are skipped by the next main pass while it is occluded. */
    public void setOccluded(boolean occluded) {
      setOccluded(this.cell, occluded);
    }

    /**
     * Records a result for a cell remembered by {@link #key()} from an earlier frame. If that cell was emptied and
     * recycled in the meantime, the result lands on another cell and is corrected by its next query, one frame later.
     */
    public static void setOccluded(Object key, boolean occluded) {
      ((SpatialGrid.IntList) key).setTag(occluded ? OCCLUDED : 0);
    }
  }

  private final SpatialGrid grid;
  private final List<MeshRenderer> gridItems = new ArrayList<>(); // grid id -> static renderer (null = free slot)
  private final ArrayDeque<Integer> freeIds = new ArrayDeque<>();
  private final List<MeshRenderer> dynamic = new ArrayList<>();
  private final Vector3f cameraPos = new Vector3f();
  private final Matrix4f scratch = new Matrix4f();
  private final FrustumIntersection frustum = new FrustumIntersection();
  private final Vector3f boundsMin = new Vector3f();
  private final Vector3f boundsMax = new Vector3f();
  private final Matrix4f inverse = new Matrix4f();
  private final Vector3f corner = new Vector3f();
  private final List<OcclusionCell> cells = new ArrayList<>();
  private int cellCount;
  private final List<SpatialGrid.IntList> hiddenCells = new ArrayList<>();

  private List<MeshRenderer> lastAll;
  private int seenInvalidations;
  private long seenTrackedChanges;
  private int reconcileStamp;
  private int queryStamp;
  /** Number of enabled renderers seen in the last {@link #collect}. */
  @Getter private int candidates;
  /** Renderers skipped by the last main-pass cull because their cells were occluded. */
  @Getter private int occluded;
  private int framesSinceValidation;
  private float lodBias = 1f;
  private long frame;

  public RenderQueue() {
    this(16f);
  }

  /** @param staticCellSize edge length of the cells used for static geometry; roughly the size of a room or chunk */
  public RenderQueue(float staticCellSize) {
    this.grid = new SpatialGrid(staticCellSize);
  }

  public int staticCount() {
    int n = 0;
    for (MeshRenderer r : this.gridItems) {
      if (r != null) {
        n++;
      }
    }
    return n;
  }

  public int dynamicCount() {
    return this.dynamic.size();
  }

  public int occupiedCells() {
    return this.grid.occupiedCells();
  }

  /** Refreshes the state of all renderers for this frame ({@code alpha}: interpolation between fixed steps). */
  public void collect(Scene scene, float alpha, Vector3f cameraPosition) {
    this.cameraPos.set(cameraPosition);
    this.frame++;
    final var all = scene.components(MeshRenderer.class);
    this.candidates = all.size();
    if (all != this.lastAll) { // the scene hands out a new list whenever its renderers change
      reconcile(all);
      this.lastAll = all;
    }
    if (MeshRenderer.invalidations() != this.seenInvalidations) {
      this.seenInvalidations = MeshRenderer.invalidations();
      reinsertInvalidated();
    }
    if (Transform.trackedChangeCount() != this.seenTrackedChanges) {
      this.seenTrackedChanges = Transform.trackedChangeCount();
      reinsertMoved();
    }
    for (int i = 0; i < this.dynamic.size(); i++) {
      updateDynamic(this.dynamic.get(i), alpha);
    }
    if (++this.framesSinceValidation >= VALIDATE_EVERY_FRAMES) {
      this.framesSinceValidation = 0;
      validateStatics();
    }
  }

  private void reconcile(List<MeshRenderer> all) {
    final int stamp = ++this.reconcileStamp;
    this.dynamic.clear();
    for (MeshRenderer r : all) {
      r.reconcileStamp = stamp;
      final boolean inGrid = r.gridId >= 0 && r.gridId < this.gridItems.size() && this.gridItems.get(r.gridId) == r;
      if (r.isStaticGeometry() && r.skin == null) { // a skinned mesh moves with its joints: never static
        if (!inGrid || !r.stateValid) {
          insertStatic(r);
        }
      } else {
        if (inGrid) {
          removeStatic(r.gridId);
        }
        this.dynamic.add(r);
      }
    }
    for (int id = 0; id < this.gridItems.size(); id++) {
      final var r = this.gridItems.get(id);
      if (r != null && r.reconcileStamp != stamp) {
        removeStatic(id); // removed from the scene, disabled, or no longer static
      }
    }
  }

  private void insertStatic(MeshRenderer r) {
    final var t = r.entity().transform();
    t.trackChanges();
    t.worldMatrix(r.model);
    r.cacheVersion = t.chainVersion();
    finishState(r);
    r.stateValid = true;
    int id = r.gridId;
    if (id < 0 || id >= this.gridItems.size() || this.gridItems.get(id) != r) {
      final var free = this.freeIds.poll();
      id = free != null ? free : this.gridItems.size();
      if (id == this.gridItems.size()) {
        this.gridItems.add(r);
      } else {
        this.gridItems.set(id, r);
      }
      r.gridId = id;
    }
    this.grid.insert(id, r.center.x, r.center.y, r.center.z, r.radius);
  }

  private void removeStatic(int id) {
    final var r = this.gridItems.get(id);
    this.grid.remove(id);
    this.gridItems.set(id, null);
    this.freeIds.add(id);
    if (r != null) {
      r.gridId = -1;
    }
  }

  private void reinsertInvalidated() {
    for (int id = 0; id < this.gridItems.size(); id++) {
      final var r = this.gridItems.get(id);
      if (r != null && !r.stateValid) {
        insertStatic(r);
      }
    }
  }

  /** A tracked transform changed somewhere: re-register the static renderers whose chain did. */
  private void reinsertMoved() {
    for (int id = 0; id < this.gridItems.size(); id++) {
      final var r = this.gridItems.get(id);
      if (r != null && r.entity().transform().chainVersion() != r.cacheVersion) {
        insertStatic(r);
      }
    }
  }

  private void updateDynamic(MeshRenderer r, float alpha) {
    final var t = r.entity().transform();
    if (t.isChainStatic()) {
      // unchanged since the last step: interpolation is a no-op, so compute once and reuse; the version catches
      // moves that happened and were snapshotted between two rendered frames
      final long version = t.chainVersion();
      if (!r.stateValid || r.cacheVersion != version) {
        t.worldMatrix(r.model);
        finishState(r);
        r.stateValid = true;
        r.cacheVersion = version;
      }
    } else {
      t.interpolatedWorldMatrix(alpha, r.model);
      finishState(r);
      r.stateValid = false;
    }
    if (r.skin != null) { // the joints may move while the mesh's own transform stands still
      r.skin.update(this.frame, alpha, r.model);
    }
  }

  private static void finishState(MeshRenderer r) {
    r.model.normal(r.normal);
    r.radius = ShadowMath.worldSphere(r.mesh.data(), r.model, r.center);
    // world-space half size of the mesh's box under the model matrix: |M| * localHalf
    final var data = r.mesh.data();
    final float hx = 0.5f * (data.max().x - data.min().x);
    final float hy = 0.5f * (data.max().y - data.min().y);
    final float hz = 0.5f * (data.max().z - data.min().z);
    final var m = r.model;
    r.boxHalf.set(Math.abs(m.m00()) * hx + Math.abs(m.m10()) * hy + Math.abs(m.m20()) * hz,
        Math.abs(m.m01()) * hx + Math.abs(m.m11()) * hy + Math.abs(m.m21()) * hz,
        Math.abs(m.m02()) * hx + Math.abs(m.m12()) * hy + Math.abs(m.m22()) * hz);
    // a cube's sphere is 1.73 times its half size; only clearly longer or flatter shapes gain from a second test
    r.elongated = r.radius > 2f * Math.min(r.boxHalf.x, Math.min(r.boxHalf.y, r.boxHalf.z));
    if (r.skin != null) { // posed away from the bind pose the mesh can leave its bind-pose bounds: be generous
      r.radius *= 2f;
      r.elongated = false;
    }
  }

  /**
   * Safety net for static renderers moved without a fixed step in between (so no snapshot noticed it): compares
   * every cached matrix with the transform from time to time and re-registers those that moved.
   */
  private void validateStatics() {
    for (int id = 0; id < this.gridItems.size(); id++) {
      final var r = this.gridItems.get(id);
      if (r != null) {
        r.entity().transform().worldMatrix(this.scratch);
        if (!this.scratch.equals(r.model, 1e-4f)) {
          insertStatic(r);
        }
      }
    }
  }

  /**
   * Fills {@code out} with the renderers (current this frame) that intersect the view-projection volume. Only the
   * grid cells inside the volume's bounding box are visited, so large static worlds cost little while mostly
   * off-screen.
   */
  public void cull(Matrix4f viewProj, Pass pass, List<MeshRenderer> out) {
    cull(viewProj, pass, out, false);
  }

  /**
   * Like {@link #cull(Matrix4f, Pass, List)}; with {@code occlusion} (main pass only) static objects whose cells were
   * all reported occluded are skipped, and the visible cells are collected in {@link #occlusionCandidates()}.
   */
  public void cull(Matrix4f viewProj, Pass pass, List<MeshRenderer> out, boolean occlusion) {
    this.frustum.set(viewProj);
    frustumBounds(viewProj, this.boundsMin, this.boundsMax, this.inverse, this.corner);
    cull(this.frustum, this.boundsMin, this.boundsMax, pass, out, occlusion && pass == Pass.MAIN);
  }

  /** Like {@link #cull(Matrix4f, Pass, List)} for a bare frustum; visits every occupied grid cell. */
  public void cull(FrustumIntersection frustum, Pass pass, List<MeshRenderer> out) {
    cull(frustum, null, null, pass, out, false);
  }

  /** The cells of static geometry inside the frustum of the last occlusion-culled main pass. */
  public List<OcclusionCell> occlusionCandidates() {
    return this.cells.subList(0, this.cellCount);
  }

  private void cull(FrustumIntersection frustum, Vector3f boxMin, Vector3f boxMax, Pass pass,
      List<MeshRenderer> out, boolean occlusion) {
    out.clear();
    final int stamp = ++this.queryStamp;
    if (occlusion) {
      this.cellCount = 0;
      this.occluded = 0;
      this.hiddenCells.clear();
    }
    for (int i = 0; i < this.dynamic.size(); i++) {
      consider(this.dynamic.get(i), frustum, pass, out);
    }
    final SpatialGrid.CellVisitor visitor = (ids, x0, y0, z0, x1, y1, z1) -> {
      if (!frustum.testAab(x0, y0, z0, x1, y1, z1)) {
        return;
      }
      if (occlusion) {
        addCell(ids, x0, y0, z0, x1, y1, z1);
        if (ids.tag() == OCCLUDED) {
          this.hiddenCells.add(ids);
          return; // its objects are drawn only if another, visible cell holds them
        }
      }
      for (int k = 0; k < ids.size(); k++) {
        final var r = this.gridItems.get(ids.get(k));
        if (r.queryStamp != stamp) { // an object spanning several visible cells is considered once
          r.queryStamp = stamp;
          consider(r, frustum, pass, out);
        }
      }
    };
    if (boxMin == null) {
      this.grid.forEachCell(visitor);
    } else {
      this.grid.forEachCellInBox(boxMin.x, boxMin.y, boxMin.z, boxMax.x, boxMax.y, boxMax.z, visitor);
    }
    final var large = this.grid.large();
    for (int k = 0; k < large.size(); k++) {
      consider(this.gridItems.get(large.get(k)), frustum, pass, out);
    }
    // count the objects of hidden cells that no visible cell brought in (each once)
    for (int i = 0; i < this.hiddenCells.size(); i++) {
      final var ids = this.hiddenCells.get(i);
      for (int k = 0; k < ids.size(); k++) {
        final var r = this.gridItems.get(ids.get(k));
        if (r.queryStamp != stamp) {
          r.queryStamp = stamp;
          this.occluded++;
        }
      }
    }
  }

  private void addCell(SpatialGrid.IntList ids, float x0, float y0, float z0, float x1, float y1, float z1) {
    if (this.cellCount == this.cells.size()) {
      this.cells.add(new OcclusionCell());
    }
    final var c = this.cells.get(this.cellCount++);
    c.cell = ids;
    c.minX = x0;
    c.minY = y0;
    c.minZ = z0;
    c.maxX = x1;
    c.maxY = y1;
    c.maxZ = z1;
  }

  /** Axis-aligned bounds of the volume described by a view-projection matrix (perspective or orthographic). */
  static void frustumBounds(Matrix4f viewProj, Vector3f outMin, Vector3f outMax) {
    frustumBounds(viewProj, outMin, outMax, new Matrix4f(), new Vector3f());
  }

  private static void frustumBounds(Matrix4f viewProj, Vector3f outMin, Vector3f outMax, Matrix4f inverse,
      Vector3f corner) {
    viewProj.invert(inverse);
    outMin.set(Float.MAX_VALUE);
    outMax.set(-Float.MAX_VALUE);
    for (int i = 0; i < 8; i++) {
      inverse.transformProject((i & 1) == 0 ? -1f : 1f, (i & 2) == 0 ? -1f : 1f, (i & 4) == 0 ? -1f : 1f, corner);
      outMin.min(corner);
      outMax.max(corner);
    }
  }

  private void consider(MeshRenderer r, FrustumIntersection frustum, Pass pass, List<MeshRenderer> out) {
    if (pass == Pass.SHADOW && !(r.castShadows && !r.material.transparent
        && r.mesh.data().drawMode() == MeshData.DrawMode.TRIANGLES)) {
      return;
    }
    if (!frustum.testSphere(r.center.x, r.center.y, r.center.z, r.radius)) {
      return;
    }
    if (r.elongated) { // the sphere of a long or flat object reaches much further than the object: check its box
      final var c = r.center; // the sphere is centred on the box
      final var h = r.boxHalf;
      if (!frustum.testAab(c.x - h.x, c.y - h.y, c.z - h.z, c.x + h.x, c.y + h.y, c.z + h.z)) {
        return;
      }
    }
    r.distSq = r.center.distanceSquared(this.cameraPos);
    if (!r.chooseLod((float) Math.sqrt(r.distSq) / this.lodBias)) {
      return; // beyond its cull distance
    }
    out.add(r);
  }

  /** Orders renderers so that draws of the same mesh (and material) are adjacent. */
  public static void sortForBatching(List<MeshRenderer> list, boolean byMaterial) {
    list.sort(byMaterial ? BY_MESH_MATERIAL : BY_MESH);
  }

  public static void sortBackToFront(List<MeshRenderer> list) {
    list.sort(FAR_TO_NEAR);
  }

  /** True if both renderers can be drawn by one instanced call (never for skinned ones: each has its own pose). */
  public static boolean sameBatch(MeshRenderer a, MeshRenderer b, boolean byMaterial) {
    return a.drawn == b.drawn && (!byMaterial || a.material == b.material) && a.skin == null && b.skin == null;
  }

  /** Writes model + normal matrix of {@code r} at instance slot {@code index} (absolute puts, position unchanged). */
  public static void writeInstance(MeshRenderer r, FloatBuffer buf, int index) {
    r.model.get(index * FLOATS_PER_INSTANCE, buf);
    r.normal.get(index * FLOATS_PER_INSTANCE + 16, buf);
  }

  /** False while the renderer is moving (interpolated between steps); its cached state is valid otherwise. */
  public static boolean isSettled(MeshRenderer r) {
    return r.stateValid;
  }

  /** Changes whenever a settled renderer's transform (or an ancestor's) changed. */
  public static long version(MeshRenderer r) {
    return r.cacheVersion;
  }

  /**
   * Multiplies the distances at which levels of detail and distance culling switch: above 1 keeps detail further
   * out, below 1 switches earlier.
   */
  public void setLodBias(float bias) {
    this.lodBias = Math.max(1e-3f, bias);
  }

  public static Matrix4f modelMatrix(MeshRenderer r) {
    return r.model;
  }

  public static float distanceSquared(MeshRenderer r) {
    return r.distSq;
  }
}
