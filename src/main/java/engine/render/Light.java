package engine.render;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * A light. {@link Type#DIRECTIONAL} lights shine along the entity's forward (-Z) axis; {@link Type#POINT} lights
 * radiate from the entity position and fade out at {@link #range}. At most {@link #MAX_LIGHTS} lights are used per
 * frame; the first shadow-casting directional light gets cascaded shadow maps, and shadow-casting point lights (up to
 * {@link RenderSettings#maxPointShadows}) get cube shadow maps reaching to their range.
 */
public class Light extends Component {
  public static final int MAX_LIGHTS = 8;
  public static final int MAX_CASCADES = 4;

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
  /**
   * Render a shadow map: cascades for the first directional light, a cube map for point lights (off by default for
   * those: each costs six extra passes over the casters in range).
   */
  public boolean castShadows = true;
  /** Point lights only: near plane of the shadow cube; geometry closer to the light than this casts no shadow. */
  public float shadowNear = 0.05f;
  /**
   * Directional lights only: how far from the camera (along its view direction, in world units) shadows are drawn.
   * Beyond this distance surfaces are lit without shadows (with a short fade).
   */
  public float shadowDistance = 80f;
  /** Directional lights only: number of cascaded shadow maps, 1 to {@link #MAX_CASCADES}. */
  public int shadowCascades = 3;
  /** Blend between uniform (0) and logarithmic (1) cascade splits; higher gives the nearest cascade more detail. */
  public float cascadeLambda = 0.7f;
  private final Quaternionf rotation = new Quaternionf();

  public static Light point(float r, float g, float b, float intensity, float range) {
    final var l = new Light();
    l.type = Type.POINT;
    l.color.set(r, g, b);
    l.intensity = intensity;
    l.range = range;
    l.castShadows = false;
    return l;
  }

  /** Direction the light travels, in world space. */
  public Vector3f direction(Vector3f dest) {
    return entity().transform().worldRotation(this.rotation).transform(dest.set(0f, 0f, -1f));
  }
}
