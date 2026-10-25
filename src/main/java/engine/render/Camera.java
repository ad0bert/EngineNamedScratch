package engine.render;

import org.joml.Matrix4f;

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

  public Matrix4f projectionMatrix(float aspect, Matrix4f dest) {
    return dest.setPerspective((float) Math.toRadians(this.fovDegrees), aspect, this.near, this.far);
  }
}
