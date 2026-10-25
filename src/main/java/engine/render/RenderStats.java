package engine.render;

/** Counters for the last frame, filled in by the backend. */
public class RenderStats {
  public int drawCalls;
  public int instances;
  public int culled;
  public int shadowCasters;
  public int lights;

  public void reset() {
    this.drawCalls = 0;
    this.instances = 0;
    this.culled = 0;
    this.shadowCasters = 0;
    this.lights = 0;
  }
}
