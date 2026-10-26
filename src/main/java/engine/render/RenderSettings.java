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
   * Blend transparent surfaces without sorting (weighted blended order-independent transparency): intersecting and
   * overlapping transparent objects look right. Off: sorted back to front per object.
   */
  public boolean orderIndependentTransparency = Boolean
      .parseBoolean(System.getProperty("engine.orderIndependentTransparency", "true"));
  /** Background colour (sRGB). */
  public final Vector3f clearColor = new Vector3f(0.53f, 0.7f, 0.9f);
  /** Tint the shadow cascades (debugging). */
  public boolean debugCascades = Boolean.getBoolean("engine.debugCascades");

  private static float floatProperty(String name, float fallback) {
    final var v = System.getProperty(name);
    return v != null ? Float.parseFloat(v) : fallback;
  }
}
