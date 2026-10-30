package engine.render;

import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * A local reflection probe: the scene's opaque surfaces and the sky, captured into a cube map at the entity's world
 * position, then blurred per roughness like the sky. Surfaces inside the probe's box (centred on the entity, aligned
 * with the world axes) take their reflections and their ambient light from it instead of the sky, so a room shows
 * its own walls in a polished floor and is not lit by the open sky. Reflections are box-projected: rays are traced
 * to the box, so the box should match the room's walls.
 *
 * <p>
 * It is captured once, when first seen (at most one probe per frame); {@link #refresh()} captures it again, and
 * {@link #refreshFrames} keeps refreshing it. Up to four probes are used per frame, the nearest to the camera first;
 * where boxes overlap, the probe nearer the camera wins. Probes only replace the light of a sky
 * ({@link RenderSettings#sky}); without a sky they are not used.
 */
public class ReflectionProbe extends Component {
  /** Full size of the box, centred on the entity. */
  public final Vector3f size = new Vector3f(10f, 4f, 10f);
  /** Over this many metres inside the box's faces the probe fades into the sky (or the next probe). */
  public float fade = 1f;
  /** Multiplies the captured light. */
  public float intensity = 1f;
  /** Edge length of the captured cube faces, in texels (the blurred levels start at half of it). */
  public int resolution = 128;
  /** Near and far plane of the capture. */
  public float near = 0.05f;
  public float far = 200f;
  /** Captures again every this many frames (for moving scenes; each capture renders the scene six times); 0 never. */
  public int refreshFrames;

  private boolean dirty = true;

  public ReflectionProbe() {
  }

  public ReflectionProbe(float sizeX, float sizeY, float sizeZ) {
    this.size.set(sizeX, sizeY, sizeZ);
  }

  /** Captures it again at the next frame (after the scene around it changed). */
  public void refresh() {
    this.dirty = true;
  }

  /** True until the renderer has captured the requested refresh. */
  public boolean isDirty() {
    return this.dirty;
  }

  /** Called by the renderer once it captured the probe. */
  public void captured() {
    this.dirty = false;
  }

  /** The box's corners at the entity's current world position. */
  public void box(Vector3f min, Vector3f max) {
    entity().transform().worldPosition(min);
    max.set(min).fma(0.5f, this.size);
    min.fma(-0.5f, this.size);
  }
}
