package engine.render;

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
  /** Point lights with {@link Light#castShadows} beyond this many (in scene order) are lit without shadows. */
  public int maxPointShadows = Integer.getInteger("engine.maxPointShadows", 4);
  /**
   * Skip static geometry hidden behind other geometry: grid cells of static renderers are tested with hardware
   * occlusion queries against the depth buffer, and cells that were hidden in the last frame are not drawn. Objects
   * may appear one frame late when they come out from behind an occluder.
   */
  public boolean occlusionCulling = Boolean.parseBoolean(System.getProperty("engine.occlusionCulling", "true"));
  /** Tint the shadow cascades (debugging). */
  public boolean debugCascades = Boolean.getBoolean("engine.debugCascades");
}
