package engine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * A light. {@link Type#DIRECTIONAL} lights shine along the entity's forward (-Z) axis; {@link Type#POINT} lights
 * radiate from the entity position and fade out at {@link #range}. At most {@link #MAX_LIGHTS} lights are used per
 * frame; the first shadow-casting directional light gets a shadow map.
 */
public class Light extends Component {
  public static final int MAX_LIGHTS = 8;

  public enum Type {
    DIRECTIONAL, POINT
  }

  public Type type = Type.DIRECTIONAL;
  public final Vector3f color = new Vector3f(1f, 1f, 1f);
  public float intensity = 1f;
  /** Point lights only: distance at which the light has faded to zero. */
  public float range = 15f;
  /** Ambient term (applied once, taken from the first light). */
  public final Vector3f ambient = new Vector3f(0.25f, 0.25f, 0.28f);
  /** Directional lights only: render a shadow map. */
  public boolean castShadows = true;
  /** Half-size of the area around the camera covered by the shadow map, in world units. */
  public float shadowDistance = 25f;

  public static Light point(float r, float g, float b, float intensity, float range) {
    final Light l = new Light();
    l.type = Type.POINT;
    l.color.set(r, g, b);
    l.intensity = intensity;
    l.range = range;
    l.castShadows = false;
    return l;
  }

  /** Direction the light travels, in world space. */
  public Vector3f direction(Vector3f dest) {
    return entity().transform().worldMatrix(new Matrix4f()).transformDirection(dest.set(0f, 0f, -1f)).normalize();
  }
}
