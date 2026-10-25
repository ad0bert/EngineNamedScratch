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
 * Mark renderers that never move as {@link #setStaticGeometry static}: the engine then caches their matrices and
 * culls them through a spatial grid instead of testing each one every frame. If a static renderer's entity (or one of
 * its parents) does move, call {@link #invalidate()} afterwards.
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
  float distSq;
  boolean stateValid;
  long cacheVersion;
  int gridId = -1;
  int queryStamp;
  int reconcileStamp;
  boolean warned;

  public MeshRenderer(GpuMesh mesh, Material material) {
    this.mesh = mesh;
    this.material = material;
  }

  public boolean isStaticGeometry() {
    return this.staticGeometry;
  }

  /** Promise that this renderer's world transform will not change (see class comment). */
  public MeshRenderer setStaticGeometry(boolean value) {
    if (this.staticGeometry != value) {
      this.staticGeometry = value;
      this.stateValid = false;
      Entity.touch(); // regroups the renderer in the next frame
    }
    return this;
  }

  /** Tells the engine that a static renderer has moved, so its cached state and grid cells are rebuilt. */
  public void invalidate() {
    this.stateValid = false;
    invalidations++;
  }

  static int invalidations() {
    return invalidations;
  }
}
