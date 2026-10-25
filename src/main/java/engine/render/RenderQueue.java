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
import lombok.extern.slf4j.Slf4j;

/**
 * Backend-independent part of drawing a scene: works out where every {@link MeshRenderer} is this frame, culls against
 * frustums and prepares draw batches. It is free of graphics calls (so it can be unit-tested and benchmarked), and it
 * avoids per-frame allocation and redundant work:
 * <ul>
 * <li>renderers whose transform did not change since the last fixed step reuse their cached matrices;</li>
 * <li>renderers marked {@link MeshRenderer#setStaticGeometry static} live in a persistent {@link SpatialGrid}, so
 * culling touches whole cells instead of individual objects, and they cost nothing per frame while off-screen.</li>
 * </ul>
 */
@Slf4j
public final class RenderQueue {
  /** 16 floats model matrix + 9 floats normal matrix. */
  public static final int FLOATS_PER_INSTANCE = 25;

  public enum Pass {
    MAIN, SHADOW
  }

  private static final Comparator<MeshRenderer> BY_MESH = Comparator.comparingInt((MeshRenderer r) -> r.mesh.id());
  private static final Comparator<MeshRenderer> BY_MESH_MATERIAL = BY_MESH
      .thenComparingInt((MeshRenderer r) -> r.material.sortKey);
  private static final Comparator<MeshRenderer> FAR_TO_NEAR = (a, b) -> Float.compare(b.distSq, a.distSq);
  private static final int VALIDATE_EVERY_FRAMES = 300;

  private final SpatialGrid grid;
  private final List<MeshRenderer> gridItems = new ArrayList<>(); // grid id -> static renderer (null = free slot)
  private final ArrayDeque<Integer> freeIds = new ArrayDeque<>();
  private final List<MeshRenderer> dynamic = new ArrayList<>();
  private final Vector3f cameraPos = new Vector3f();
  private final Matrix4f scratch = new Matrix4f();
  private final FrustumIntersection frustum = new FrustumIntersection();
  private final Vector3f boundsMin = new Vector3f();
  private final Vector3f boundsMax = new Vector3f();

  private List<MeshRenderer> lastAll;
  private int seenInvalidations;
  private int reconcileStamp;
  private int queryStamp;
  /** Number of enabled renderers seen in the last {@link #collect}. */
  @Getter private int candidates;
  private int framesSinceValidation;

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
      if (r.isStaticGeometry()) {
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
    r.entity().transform().worldMatrix(r.model);
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
  }

  private static void finishState(MeshRenderer r) {
    r.model.normal(r.normal);
    r.radius = ShadowMath.worldSphere(r.mesh.data(), r.model, r.center);
  }

  /** Debug check: static renderers that moved without {@link MeshRenderer#invalidate()} are reported once. */
  private void validateStatics() {
    for (MeshRenderer r : this.gridItems) {
      if (r != null && !r.warned) {
        r.entity().transform().worldMatrix(this.scratch);
        if (!this.scratch.equals(r.model, 1e-4f)) {
          r.warned = true;
          log.warn("Static renderer of {} has moved; call MeshRenderer.invalidate() after moving static geometry",
              r.entity());
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
    this.frustum.set(viewProj);
    frustumBounds(viewProj, this.boundsMin, this.boundsMax);
    cull(this.frustum, this.boundsMin, this.boundsMax, pass, out);
  }

  /** Like {@link #cull(Matrix4f, Pass, List)} for a bare frustum; visits every occupied grid cell. */
  public void cull(FrustumIntersection frustum, Pass pass, List<MeshRenderer> out) {
    cull(frustum, null, null, pass, out);
  }

  private void cull(FrustumIntersection frustum, Vector3f boxMin, Vector3f boxMax, Pass pass,
      List<MeshRenderer> out) {
    out.clear();
    final int stamp = ++this.queryStamp;
    for (int i = 0; i < this.dynamic.size(); i++) {
      consider(this.dynamic.get(i), frustum, pass, out);
    }
    final SpatialGrid.CellVisitor visitor = (ids, x0, y0, z0, x1, y1, z1) -> {
      if (!frustum.testAab(x0, y0, z0, x1, y1, z1)) {
        return;
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
  }

  /** Axis-aligned bounds of the volume described by a view-projection matrix (perspective or orthographic). */
  static void frustumBounds(Matrix4f viewProj, Vector3f outMin, Vector3f outMax) {
    final var inverse = new Matrix4f(viewProj).invert();
    outMin.set(Float.MAX_VALUE);
    outMax.set(-Float.MAX_VALUE);
    final var corner = new Vector3f();
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
    if (frustum.testSphere(r.center.x, r.center.y, r.center.z, r.radius)) {
      r.distSq = r.center.distanceSquared(this.cameraPos);
      out.add(r);
    }
  }

  /** Orders renderers so that draws of the same mesh (and material) are adjacent. */
  public static void sortForBatching(List<MeshRenderer> list, boolean byMaterial) {
    list.sort(byMaterial ? BY_MESH_MATERIAL : BY_MESH);
  }

  public static void sortBackToFront(List<MeshRenderer> list) {
    list.sort(FAR_TO_NEAR);
  }

  /** True if both renderers can be drawn by one instanced call. */
  public static boolean sameBatch(MeshRenderer a, MeshRenderer b, boolean byMaterial) {
    return a.mesh == b.mesh && (!byMaterial || a.material == b.material);
  }

  /** Writes model + normal matrix of {@code r} at instance slot {@code index} (absolute puts, position unchanged). */
  public static void writeInstance(MeshRenderer r, FloatBuffer buf, int index) {
    r.model.get(index * FLOATS_PER_INSTANCE, buf);
    r.normal.get(index * FLOATS_PER_INSTANCE + 16, buf);
  }

  public static Matrix4f modelMatrix(MeshRenderer r) {
    return r.model;
  }

  public static float distanceSquared(MeshRenderer r) {
    return r.distSq;
  }
}
