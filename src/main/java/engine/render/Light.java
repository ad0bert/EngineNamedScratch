package engine.render;

import org.joml.Vector3f;

import engine.ecs.Component;

/** Directional light shining along the entity's forward (-Z) axis. */
public class Light extends Component {
  public final Vector3f color = new Vector3f(1f, 1f, 1f);
  public float intensity = 1f;
  public final Vector3f ambient = new Vector3f(0.25f, 0.25f, 0.28f);

  /** Direction the light travels, in world space. */
  public Vector3f direction(Vector3f dest) {
    final var m = entity().transform().worldMatrix(new org.joml.Matrix4f());
    return m.transformDirection(dest.set(0f, 0f, -1f)).normalize();
  }
}
