package engine.render;

/** Counters for the last frame, filled in by the backend. */
public class RenderStats {
  public int drawCalls;
  public int instances;
  /** Renderers outside the view frustum. */
  public int culled;
  public int shadowCasters;
  public int shadowCascades;
  /** Point lights that rendered a shadow cube this frame. */
  public int pointShadows;
  public int lights;
  /** Static renderers skipped because their grid cells were hidden behind other geometry. */
  public int occluded;
  /** Grid cells tested with occlusion queries. */
  public int occlusionQueries;

  public void reset() {
    this.pointShadows = 0;
    this.occluded = 0;
    this.occlusionQueries = 0;
    this.drawCalls = 0;
    this.instances = 0;
    this.culled = 0;
    this.shadowCasters = 0;
    this.shadowCascades = 0;
    this.lights = 0;
  }
}
