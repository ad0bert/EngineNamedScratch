package engine.render;

import org.joml.Vector3f;

/**
 * Quality and culling switches of a {@link RenderBackend}, read every frame, so they can be changed at any time
 * (from the GL thread). The defaults can be overridden with system properties named after the fields, e.g.
 * {@code -Dengine.shadowMapSize=4096} or {@code -Dengine.occlusionCulling=false}.
 */
public class RenderSettings {
  /** Edge length in texels of each directional shadow cascade. */
  public int shadowMapSize = Integer.getInteger("engine.shadowMapSize", 2048);
  /** Edge length in texels of each face of a point light's cube shadow map. */
  public int pointShadowMapSize = Integer.getInteger("engine.pointShadowMapSize", 512);
  /**
   * Point and spot lights with {@link Light#castShadows} beyond this many (in scene order) are lit without shadows. A
   * point light uses six shadow maps, a spot light one; shadow maps of lights whose casters did not move are kept
   * from the last frame.
   */
  public int maxPointShadows = Integer.getInteger("engine.maxPointShadows", 4);
  /**
   * Skip static geometry hidden behind other geometry: grid cells of static renderers are tested with hardware
   * occlusion queries against the depth buffer, and cells that were hidden in the last frame are not drawn. Objects
   * may appear one frame late when they come out from behind an occluder.
   */
  public boolean occlusionCulling = Boolean.parseBoolean(System.getProperty("engine.occlusionCulling", "true"));
  /** Brightness multiplier before tone mapping. */
  public float exposure = floatProperty("engine.exposure", 1f);
  /** How much of the light above {@link #bloomThreshold} glows into its surroundings; 0 turns bloom off. */
  public float bloomStrength = floatProperty("engine.bloomStrength", 0.08f);
  /** Brightness (in linear HDR units, 1 = a white surface in full light) where bloom starts. */
  public float bloomThreshold = floatProperty("engine.bloomThreshold", 1f);
  /** Smooth jagged edges with FXAA. */
  public boolean antiAliasing = Boolean.parseBoolean(System.getProperty("engine.antiAliasing", "true"));
  /**
   * Screen-space ambient occlusion: the ambient (sky) light is darkened in creases, corners and under objects, where
   * little of the sky is in view. Direct light is not changed.
   */
  public boolean ambientOcclusion = Boolean.parseBoolean(System.getProperty("engine.ambientOcclusion", "true"));
  /** How far (metres) ambient occlusion looks around each point. */
  public float aoRadius = floatProperty("engine.aoRadius", 0.8f);
  /** Exponent on the unoccluded share: 1 as measured, higher darker. */
  public float aoStrength = floatProperty("engine.aoStrength", 2f);
  /**
   * Blend transparent surfaces without sorting (weighted blended order-independent transparency): intersecting and
   * overlapping transparent objects look right. Off: sorted back to front per object.
   */
  public boolean orderIndependentTransparency = Boolean
      .parseBoolean(System.getProperty("engine.orderIndependentTransparency", "true"));
  /** Background colour (sRGB), where there is no {@link #sky}. */
  public final Vector3f clearColor = new Vector3f(0.53f, 0.7f, 0.9f);
  /**
   * The sky behind the scene and the source of image-based lighting (replaces the lights' flat ambient colour), or
   * null for the plain {@link #clearColor} and flat ambient light.
   */
  public Sky sky;
  /** Brightness multiplier of the sky and its lighting. */
  public float skyIntensity = floatProperty("engine.skyIntensity", 1f);
  /** Turns the sky about the vertical axis, degrees. */
  public float skyRotation;
  /** Tint the shadow cascades (debugging). */
  public boolean debugCascades = Boolean.getBoolean("engine.debugCascades");
  /** Shows the ambient occlusion (white: open, black: occluded) instead of the scene's opaque surfaces. */
  public boolean debugAmbientOcclusion = Boolean.getBoolean("engine.debugAmbientOcclusion");

  private static float floatProperty(String name, float fallback) {
    final var v = System.getProperty(name);
    return v != null ? Float.parseFloat(v) : fallback;
  }
}
