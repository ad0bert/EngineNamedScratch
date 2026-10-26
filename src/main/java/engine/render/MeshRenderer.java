package engine.render;

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
  private boolean staticGeometry;

  // render state, owned by RenderQueue
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
