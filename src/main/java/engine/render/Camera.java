package engine.render;

import org.joml.Matrix4f;

import engine.ecs.Component;

/** Perspective camera looking along its entity's forward (-Z) axis. Orientation comes from the entity transform. */
public class Camera extends Component {
  public float fovDegrees = 60f;
  public float near = 0.1f;
  public float far = 1000f;

  public Matrix4f viewMatrix(Matrix4f dest) {
    // world matrix without scale would be ideal; cameras are not scaled in practice
    return entity().transform().worldMatrix(dest).invert();
  }

  public Matrix4f projectionMatrix(float aspect, Matrix4f dest) {
    return dest.setPerspective((float) Math.toRadians(this.fovDegrees), aspect, this.near, this.far);
  }
}
