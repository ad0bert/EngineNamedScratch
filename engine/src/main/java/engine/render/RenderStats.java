package engine.render;

/** Counters for the last frame, filled in by the backend. */
public class RenderStats {
  public int drawCalls;
  public int instances;
  /** Renderers outside the view frustum. */
  public int culled;
  public int shadowCasters;
  public int shadowCascades;
  /** Point and spot lights with shadow maps this frame. */
  public int pointShadows;
  /** Point/spot shadow maps kept from the last frame because nothing in them moved. */
  public int cachedShadowMaps;
  public int lights;
  /** Static renderers skipped because their grid cells were hidden behind other geometry. */
  public int occluded;
  /** Grid cells tested with occlusion queries. */
  public int occlusionQueries;
  /** Particles drawn, and the emitters they came from. */
  public int particles;
  public int particleEmitters;

  public void reset() {
    this.pointShadows = 0;
    this.cachedShadowMaps = 0;
    this.occluded = 0;
    this.occlusionQueries = 0;
    this.particles = 0;
    this.particleEmitters = 0;
    this.drawCalls = 0;
    this.instances = 0;
    this.culled = 0;
    this.shadowCasters = 0;
    this.shadowCascades = 0;
    this.lights = 0;
  }
}
