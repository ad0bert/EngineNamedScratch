package engine.audio;

/**
 * How a spatial sound gets quieter with distance. Within the minimum distance it plays at full volume; the curves
 * below describe what happens between the minimum and the maximum distance. Computed by the engine rather than the
 * sound API, so every backend sounds the same and voices can be ranked by how loud they really are.
 */
public enum Rolloff {
  /**
   * Physical falloff, {@code min / (min + rolloff * (d - min))} (OpenAL's inverse distance clamped model); beyond the
   * maximum distance the sound stays at the level it had there. With rolloff 1, every doubling of the distance halves
   * the amplitude (-6 dB).
   */
  INVERSE,
  /** Straight line from full volume at the minimum to silence at the maximum distance, scaled by the rolloff. */
  LINEAR,
  /** {@code (d / min) ^ -rolloff}, clamped at the maximum distance (OpenAL's exponent distance clamped model). */
  EXPONENTIAL;

  /** The gain (0..1) at distance {@code d}. */
  public float gain(float d, float minDistance, float maxDistance, float rolloff) {
    final float min = Math.max(1e-4f, minDistance);
    final float max = Math.max(min, maxDistance);
    if (d <= min) {
      return 1f;
    }
    final float c = Math.min(d, max);
    return switch (this) {
    case INVERSE -> min / (min + rolloff * (c - min));
    case LINEAR -> max > min ? Math.max(0f, 1f - rolloff * (c - min) / (max - min)) : 1f;
    case EXPONENTIAL -> (float) Math.pow(c / min, -rolloff);
    };
  }
}
