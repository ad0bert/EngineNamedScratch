package engine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.ecs.Component;

/** Perspective camera looking along its entity's forward (-Z) axis. Orientation comes from the entity transform. */
public class Camera extends Component {
  public float fovDegrees = 60f;
  public float near = 0.1f;
  public float far = 1000f;

  /** View matrix at the interpolated render state; cameras are not expected to be scaled. */
  public Matrix4f viewMatrix(float alpha, Matrix4f dest) {
    return entity().transform().interpolatedWorldMatrix(alpha, dest).invert();
  }

  /**
   * World-space ray through a point of the screen, for picking. {@code ndcX/ndcY} are in -1..1 (x right, y up; the
   * screen centre is 0,0). Uses the current (non-interpolated) transform, matching the physics state.
   */
  public void viewRay(float ndcX, float ndcY, float aspect, Vector3f outOrigin, Vector3f outDir) {
    final Matrix4f world = entity().transform().worldMatrix(new Matrix4f());
    final float tan = (float) Math.tan(Math.toRadians(this.fovDegrees) / 2.0);
    world.getTranslation(outOrigin);
    world.transformDirection(outDir.set(ndcX * tan * aspect, ndcY * tan, -1f)).normalize();
  }

  public Matrix4f projectionMatrix(float aspect, Matrix4f dest) {
    return dest.setPerspective((float) Math.toRadians(this.fovDegrees), aspect, this.near, this.far);
  }
}
