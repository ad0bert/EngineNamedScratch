package engine.audio;

import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * A space with its own acoustics: while the listener is inside the box (centred on the entity, aligned with the world
 * axes) positioned sounds reverberate like {@link #preset}, and with {@link #lowPass} below 1 everything heard is
 * muffled (under water, behind a door, a dream). Both fade in over {@link #fade} metres inside the box's faces. Where
 * zones overlap the one the listener is deepest in wins. Needs a backend with effects ({@link
 * AudioBackend#supportsEffects()}; OpenAL with the EFX extension); without, zones change nothing.
 */
public class ReverbZone extends Component {
  /** Full size of the box, centred on the entity. */
  public final Vector3f size = new Vector3f(10f, 4f, 10f);
  public ReverbPreset preset = ReverbPreset.ROOM;
  /** How much of each positioned sound goes into the reverb, 0..1. */
  public float wet = 1f;
  /** Metres inside the faces over which the zone fades in. */
  public float fade = 1f;
  /** Treble kept of everything heard inside, 0..1 (1 keeps it all). */
  public float lowPass = 1f;

  public ReverbZone() {
  }

  public ReverbZone(ReverbPreset preset, float sizeX, float sizeY, float sizeZ) {
    this.preset = preset;
    this.size.set(sizeX, sizeY, sizeZ);
  }

  /** 0 outside, rising to 1 at {@link #fade} metres inside the box. */
  public float weight(Vector3f point, Vector3f scratch) {
    entity().transform().worldPosition(scratch);
    final float dx = 0.5f * this.size.x - Math.abs(point.x - scratch.x);
    final float dy = 0.5f * this.size.y - Math.abs(point.y - scratch.y);
    final float dz = 0.5f * this.size.z - Math.abs(point.z - scratch.z);
    final float inside = Math.min(dx, Math.min(dy, dz));
    if (inside <= 0f) {
      return 0f;
    }
    return this.fade <= 0f ? 1f : Math.min(1f, inside / this.fade);
  }
}
