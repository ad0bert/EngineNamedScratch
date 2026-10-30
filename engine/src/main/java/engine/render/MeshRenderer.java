package engine.render;

import java.util.Arrays;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.ecs.Component;
import engine.ecs.Entity;

/**
 * Draws a mesh with a material at the entity's world transform.
 *
 * <p>
 * Mark renderers that (almost) never move as {@link #setStaticGeometry static}: the engine then caches their matrices
 * and culls them through a spatial grid instead of testing each one every frame. Moving one anyway is allowed: the
 * engine notices the change at the next fixed step and re-registers it, so it may lag by a frame; call
 * {@link #invalidate()} after moving it to apply the change immediately.
 */
public class MeshRenderer extends Component {
  private static volatile int invalidations;

  public final GpuMesh mesh;
  public final Material material;
  public boolean castShadows = true;
  /**
   * Makes this a skinned renderer: the mesh (which needs {@link MeshData#skin} weights) is deformed by the skin's
   * joints on the GPU. Skinned renderers are always treated as moving and are drawn one at a time (not instanced).
   * Set it before the renderer is added to a scene.
   */
  public Skin skin;
  private boolean staticGeometry;
  /** Coarser meshes and the camera distances from which they are drawn instead, nearest first. */
  private GpuMesh[] lodMeshes = new GpuMesh[0];
  private float[] lodDistances = new float[0];
  /** Not drawn (nor casting shadows) beyond this camera distance; 0 always draws. */
  public float cullDistance;

  // render state, owned by RenderQueue
  /** The mesh chosen for the camera distance this frame. */
  GpuMesh drawn;
  final Matrix4f model = new Matrix4f();
  final Matrix3f normal = new Matrix3f();
  final Vector3f center = new Vector3f();
  float radius;
  /** Half size of the world-space bounding box (centred on {@link #center}). */
  final Vector3f boxHalf = new Vector3f();
  /** True if the box is worth testing after the sphere (long or flat shapes). */
  boolean elongated;
  float distSq;
  boolean stateValid;
  long cacheVersion;
  int gridId = -1;
  int queryStamp;
  int reconcileStamp;

  public MeshRenderer(GpuMesh mesh, Material material) {
    this.mesh = mesh;
    this.material = material;
    this.drawn = mesh;
  }

  /**
   * Draws {@code coarser} instead of the mesh (or of the last level added) when the camera is at least
   * {@code distance} away (times {@link RenderSettings#lodBias}). Levels must be added nearest first; bounds and
   * culling stay those of the full mesh, and {@link MeshSimplifier} makes such meshes.
   */
  public MeshRenderer addLod(float distance, GpuMesh coarser) {
    final int n = this.lodMeshes.length;
    if (n > 0 && distance <= this.lodDistances[n - 1]) {
      throw new IllegalArgumentException("levels of detail must be added nearest first");
    }
    if (coarser.data().drawMode() != this.mesh.data().drawMode()) {
      throw new IllegalArgumentException("a level of detail must have the mesh's draw mode");
    }
    this.lodMeshes = Arrays.copyOf(this.lodMeshes, n + 1);
    this.lodDistances = Arrays.copyOf(this.lodDistances, n + 1);
    this.lodMeshes[n] = coarser;
    this.lodDistances[n] = distance;
    return this;
  }

  public int lodCount() {
    return this.lodMeshes.length;
  }

  public GpuMesh lodMesh(int level) {
    return this.lodMeshes[level];
  }

  public float lodDistance(int level) {
    return this.lodDistances[level];
  }

  /** The mesh drawn in the last frame: the mesh itself or one of its levels of detail. */
  public GpuMesh drawnMesh() {
    return this.drawn;
  }

  /** Picks the level for a camera {@code distance} (already divided by the bias); false if culled by distance. */
  boolean chooseLod(float distance) {
    if (this.cullDistance > 0f && distance > this.cullDistance) {
      return false;
    }
    GpuMesh m = this.mesh;
    for (int i = 0; i < this.lodDistances.length && distance >= this.lodDistances[i]; i++) {
      m = this.lodMeshes[i];
    }
    this.drawn = m;
    return true;
  }

  public boolean isStaticGeometry() {
    return this.staticGeometry;
  }

  /** Hint that this renderer's world transform will rarely or never change (see class comment). */
  public MeshRenderer setStaticGeometry(boolean value) {
    if (this.staticGeometry != value) {
      this.staticGeometry = value;
      this.stateValid = false;
      Entity.touch(); // regroups the renderer in the next frame
    }
    return this;
  }

  /** Tells the engine that a static renderer has moved, so its cached state and grid cells are rebuilt now. */
  public void invalidate() {
    this.stateValid = false;
    invalidations++;
  }

  static int invalidations() {
    return invalidations;
  }
}
