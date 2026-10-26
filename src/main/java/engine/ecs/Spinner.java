package engine.ecs;

import org.joml.Vector3f;

/** Rotates the entity continuously; driven by the fixed update, not by rendering. */
public class Spinner extends Component {
  /** Turning speed about the local x, y and z axes. */
  public final Vector3f degreesPerSecond;

  public Spinner(float x, float y, float z) {
    this.degreesPerSecond = new Vector3f(x, y, z);
  }

  @Override
  public void update(double dt) {
    final float s = (float) (Math.PI / 180.0 * dt);
    entity().transform().rotation().rotateXYZ(this.degreesPerSecond.x * s, this.degreesPerSecond.y * s,
        this.degreesPerSecond.z * s);
  }
}
